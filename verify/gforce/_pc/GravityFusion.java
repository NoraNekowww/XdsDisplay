package com.nz.xdsdisplay;

/**
 * 姿态估计 + 重力补偿（纯算法、无 Android 依赖，PC 端 javac 直接跑）。
 *
 * 输入一律是**屏幕坐标系**（x 右、y 上、z 出屏）——MainActivity 先把设备系转过来：
 *   setAccel：加速度计（m/s²，**含重力**）
 *   setGyro ：陀螺仪（rad/s）
 *
 * 目的：把加速度表达在「**地面水平**」坐标里，而不是手机平面：
 *   up    = 估计的重力方向（互补滤波：陀螺积分负责快变，加速度计负责慢修正）
 *   fwd   = 屏幕「上」方向在地面上的投影 = 车头方向（手机怎么歪装都不影响读数）
 *   right = 屏幕「右」方向在地面上的投影
 *   lin   = 实测加速度 − up×g0（= 运动加速度，含竖直分量）
 *
 * 于是 lin 在地面系的分量 linF / linR / linU 不随手机的俯仰 / 横滚变化：
 * 手机装在把立上仰 30°，圆点也不会因为这个仰角而偏。
 *
 * 加速度计修正增益按「动态程度」自适应：|a| 离 1g 越远说明越在做机动，
 * 这时几乎只信陀螺（否则持续加速会被低通当成重力，读数自己衰减掉）。
 * 没有陀螺时退化成「低通估重力」，即旧行为（仍然做了水平化）。
 */
public final class GravityFusion {

    public static final double G0 = 9.80665;

    /** 采样间隔上限（s）：陀螺两次采样隔太久就当作断了，不积分 */
    private static final double DT_MAX = 0.05;

    /** 「上」方向单位向量（屏幕系），ready 前无效 */
    private double ux, uy, uz;
    private boolean ready;

    /** 最近一帧原始加速度（屏幕系，m/s²），算 lin 用 */
    private double ax, ay, az;

    private long lastGyroNs;
    private boolean hasGyro;
    private long gyroSamples;

    /** 当前地面系的三个基向量（屏幕系） */
    private double fx, fy, fz;      // 前
    private double rx, ry, rz;      // 右
    private double lf, lr, lu;      // 线性加速度（g）在地面系的分量

    /**
     * 喂一帧陀螺仪（屏幕系，rad/s）。
     *
     * @param gx,gy,gz 角速度
     * @param tNs      纳秒时间戳（SensorEvent.timestamp）
     */
    public void setGyro(float gx, float gy, float gz, long tNs) {
        hasGyro = true;
        gyroSamples++;
        if (!ready) {
            lastGyroNs = tNs;
            return;
        }
        if (lastGyroNs == 0) {
            lastGyroNs = tNs;
            return;
        }
        double dt = (tNs - lastGyroNs) / 1e9;
        lastGyroNs = tNs;
        if (dt <= 0) return;
        if (dt > DT_MAX) return;             // 断流：不瞎积分，等加速度计把姿态拉回来
        // 世界「上」在设备系里是固定的：du/dt = −ω × u
        double cx = gy * uz - gz * uy;
        double cy = gz * ux - gx * uz;
        double cz = gx * uy - gy * ux;
        double nx = ux - cx * dt;
        double ny = uy - cy * dt;
        double nz = uz - cz * dt;
        set(nx, ny, nz);
    }

    /**
     * 喂一帧加速度计（屏幕系，m/s²，含重力）。
     */
    public void setAccel(float x, float y, float z, long tNs) {
        ax = x;
        ay = y;
        az = z;
        double m = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (m > 0.5 * G0) {
            double nx = x / m, ny = y / m, nz = z / m;
            if (!ready) {
                set(nx, ny, nz);
                ready = true;
            } else {
                // 动态程度：|a| 离 1g 越远，越说明这帧测的是「合力」而不是重力。
                //   静止/匀速（dev≈0）   ：快速锁住姿态（陀螺漂移也一并被拉回）
                //   轻微机动（dev 1.5~3.5%）：慢慢修正，避免被持续的加减速带偏
                //   明显机动（dev > 3.5%）  ：几乎冻结 —— 否则 0.3g 的加速会被当成
                //                              「重力变向了」，读数自己衰减到 0
                double dev = Math.abs(m - G0) / G0;
                double alpha;
                if (dev < 0.015) alpha = hasGyro ? 0.05 : 0.10;
                else if (dev < 0.035) alpha = hasGyro ? 0.001 : 0.01;
                else alpha = hasGyro ? 0.0001 : 0.003;
                double nx2 = ux + (nx - ux) * alpha;
                double ny2 = uy + (ny - uy) * alpha;
                double nz2 = uz + (nz - uz) * alpha;
                set(nx2, ny2, nz2);
            }
        }
        rebuildBasis();
    }

    /** 归一化写入 u，并重建地面系基向量 */
    private void set(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-9) return;
        ux = x / n;
        uy = y / n;
        uz = z / n;
        ready = true;
        rebuildBasis();
    }

    /**
     * 由「上」向量造地面水平基：
     *   前 = 屏幕 y（上）在地面上的投影；手机竖着放（屏幕垂直）时投影退化，
     *        改用屏幕 z（出屏方向）在地面上的投影 —— 相当于「屏幕朝哪边，哪边就是前」。
     *   右 = 屏幕 x（右）在地面上的投影（同样退化时用 z 的垂直分量兜底）。
     */
    private void rebuildBasis() {
        // 前
        double d = uy;                       // ŷ·u
        double ex = 0 - d * ux;
        double ey = 1 - d * uy;
        double ez = 0 - d * uz;
        double m = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (m < 0.15) {                      // 屏幕几乎垂直：换成 z 的水平投影
            d = uz;
            ex = 0 - d * ux;
            ey = 0 - d * uy;
            ez = 1 - d * uz;
            m = Math.sqrt(ex * ex + ey * ey + ez * ez);
        }
        if (m < 1e-6) { fx = 0; fy = 1; fz = 0; }
        else { fx = ex / m; fy = ey / m; fz = ez / m; }

        // 右
        d = ux;                              // x̂·u
        ex = 1 - d * ux;
        ey = 0 - d * uy;
        ez = 0 - d * uz;
        m = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (m < 1e-6) { rx = 1; ry = 0; rz = 0; }
        else { rx = ex / m; ry = ey / m; rz = ez / m; }

        // 线性加速度 = 实测 − 重力（都在屏幕系），再投影到地面系
        double lx = ax - ux * G0;
        double ly = ay - uy * G0;
        double lz = az - uz * G0;
        lf = (lx * fx + ly * fy + lz * fz) / G0;
        lr = (lx * rx + ly * ry + lz * rz) / G0;
        lu = (lx * ux + ly * uy + lz * uz) / G0;
    }

    public boolean ready() {
        return ready;
    }

    public boolean hasGyro() {
        return hasGyro;
    }

    public long gyroSamples() {
        return gyroSamples;
    }

    /** 估计的「上」方向（屏幕系） */
    public double upX() { return ux; }
    public double upY() { return uy; }
    public double upZ() { return uz; }

    /** 线性加速度在地面系的分量（g）：前 / 右 / 上 */
    public double linF() { return lf; }
    public double linR() { return lr; }
    public double linU() { return lu; }

    /** 手机相对水平面的倾角（度）：0 = 屏幕朝上平放 */
    public double tiltDeg() {
        double c = Math.min(1.0, Math.max(-1.0, uz));
        return Math.toDegrees(Math.acos(c));
    }

    public void reset() {
        ready = false;
        lastGyroNs = 0;
        gyroSamples = 0;
        ax = ay = 0;
        az = G0;
        set(0, 0, 1);
        ready = false;
        lf = lr = lu = 0;
    }
}
