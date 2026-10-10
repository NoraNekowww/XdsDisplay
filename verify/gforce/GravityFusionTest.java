package com.nz.xdsdisplay;

/**
 * GravityFusion 自检（PC 端直接跑，不需要真机）。
 *
 * 造的场景是「屏幕系」：x 右、y 上（屏幕的"上"）、z 出屏。
 * tilt 度 = 手机相对水平面的倾角（0 = 屏幕朝上平放，装在把立上一般 20~40°），
 * 再叠一个地面系的前向/横向/竖直加速度，看融合出来的读数对不对：
 *   - 静态只受重力：不管装成什么角度，linF/linR/linU 都该 ≈ 0（旧算法在这里会因为
 *     重力投影直接读出 0.5~0.9G 的假读数）
 *   - 刹车 / 加速脉冲：读数 = 真实运动加速度（g）
 *   - 竖直线性加速度：进 linU（z 轴融合）
 *   - 陀螺仪：纯旋转时姿态必须跟得上，零角速度时不许漂
 *
 * 真机上有陀螺仪（GravityFusion 里两条路径的修正增益不同），所以除第 6 项外都按
 * 「有陀螺」喂帧：每帧同时喂零角速度的陀螺数据，跟真机一致。
 */
public class GravityFusionTest {

    static int pass, fail;

    /** 全局虚拟时间（秒），跨 feed 保持单调，跟真机的纳秒流一个意思 */
    static double T = 0;

    static long NS(double s) {
        return (long) (s * 1e9);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
                + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (ok) pass++;
        else fail++;
    }

    /** 真实「上」方向（屏幕系）：tiltDeg = 手机相对水平面的倾角（0 = 屏幕朝上平放） */
    static double[] up(double tiltDeg) {
        double t = Math.toRadians(tiltDeg);
        return new double[]{0, Math.sin(t), Math.cos(t)};
    }

    static double[] norm(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1e-12) return new double[]{0, 0, 0};
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    /** 地面系基（和 GravityFusion 同一套规则：前 = 屏幕 y 的水平投影） */
    static double[][] basis(double[] u) {
        double d = u[1];
        double[] f = norm(new double[]{-d * u[0], 1 - d * u[1], -d * u[2]});
        double d2 = u[0];
        double[] r = norm(new double[]{1 - d2 * u[0], -d2 * u[1], -d2 * u[2]});
        return new double[][]{f, r};
    }

    static void feed(GravityFusion f, double tiltDeg, double af, double ar, double au,
                     double seconds, int rate) {
        feed(f, tiltDeg, af, ar, au, seconds, rate, true);
    }

    /** 退化路径：不喂陀螺仪 */
    static void feedNoGyro(GravityFusion f, double tiltDeg, double af, double ar, double au,
                           double seconds, int rate) {
        feed(f, tiltDeg, af, ar, au, seconds, rate, false);
    }

    /**
     * 喂一段合成数据：手机装成 tiltDeg，地面系加速度 (af, ar, au) 单位 g，持续 seconds 秒。
     *
     * @param withGyro true = 同时喂零角速度陀螺（真机有陀螺时就是这情形）
     */
    static void feed(GravityFusion f, double tiltDeg, double af, double ar, double au,
                     double seconds, int rate, boolean withGyro) {
        double[] u = up(tiltDeg);
        double[][] br = basis(u);
        double[] fwd = br[0], rgt = br[1];
        double[] a = new double[3];
        for (int k = 0; k < 3; k++) {
            a[k] = u[k] * GravityFusion.G0 + (fwd[k] * af + rgt[k] * ar + u[k] * au) * GravityFusion.G0;
        }
        int n = (int) Math.round(seconds * rate);
        for (int i = 0; i < n; i++) {
            T += 1.0 / rate;
            f.setAccel((float) a[0], (float) a[1], (float) a[2], NS(T));
            if (withGyro) f.setGyro(0, 0, 0, NS(T));
        }
    }

    public static void main(String[] args) {
        System.out.println("=== GravityFusion 自检（陀螺仪 + 加速度计 → 地面水平坐标系）===\n");

        System.out.println("-- 1) 静态（只受重力）：装成各种角度都不该读出假加速度 --");
        for (double tilt : new double[]{0, 15, 35, 55, 75}) {
            GravityFusion f = new GravityFusion();
            feed(f, tilt, 0, 0, 0, 1.5, 100);
            String d = String.format("tilt=%.0f°  linF=%+.4f linR=%+.4f linU=%+.4f  up=(%.3f,%.3f,%.3f) 估计倾角=%.1f°",
                    tilt, f.linF(), f.linR(), f.linU(), f.upX(), f.upY(), f.upZ(), f.tiltDeg());
            check(String.format("装成 %.0f° 静止 → 读数全 0（±0.01G）", tilt),
                    Math.abs(f.linF()) < 0.01 && Math.abs(f.linR()) < 0.01 && Math.abs(f.linU()) < 0.01, d);
        }

        System.out.println("\n-- 2) 装成 35° 时来 0.4s 的 0.30G 加速脉冲：读数必须认出 0.30G（不被倾角带偏） --");
        GravityFusion f2 = new GravityFusion();
        feed(f2, 35, 0, 0, 0, 1.5, 100);              // 先静置收敛
        feed(f2, 35, 0.30, 0, 0, 0.4, 100);           // 前向 0.30G
        String d2 = String.format("linF=%+.3f linR=%+.3f linU=%+.3f", f2.linF(), f2.linR(), f2.linU());
        check("前向加速脉冲 → linF ≈ +0.30G（±0.05）", Math.abs(f2.linF() - 0.30) < 0.05, d2);
        check("横向无窜扰 |linR| < 0.03", Math.abs(f2.linR()) < 0.03, d2);

        System.out.println("\n-- 3) 刹车（−0.25G）与竖直颠簸（+0.20G）--");
        GravityFusion f3 = new GravityFusion();
        feed(f3, 20, 0, 0, 0, 1.5, 100);
        feed(f3, 20, -0.25, 0, 0.20, 0.3, 100);
        check("刹车 → linF ≈ −0.25G", Math.abs(f3.linF() + 0.25) < 0.06,
                String.format("linF=%+.3f", f3.linF()));
        check("竖直分量进 linU ≈ +0.20G（z 轴融合）", Math.abs(f3.linU() - 0.20) < 0.06,
                String.format("linU=%+.3f", f3.linU()));

        System.out.println("\n-- 4) 陀螺仪：纯旋转 90°（屏幕从平放转到竖直）姿态要跟得上 --");
        GravityFusion f4 = new GravityFusion();
        feed(f4, 0, 0, 0, 0, 1.0, 100);               // 平放：u=(0,0,1)
        double w = Math.PI;                            // rad/s：0.5s 转 90°
        for (int i = 0; i < 50; i++) {
            T += 0.01;
            f4.setGyro((float) w, 0, 0, NS(T));
        }
        String d4 = String.format("up=(%.4f,%.4f,%.4f)", f4.upX(), f4.upY(), f4.upZ());
        check("绕 x 转 90° → uy ≈ 1（竖直）", f4.upY() > 0.99, d4);
        check("hasGyro()", f4.hasGyro(), "samples=" + f4.gyroSamples());

        System.out.println("\n-- 5) 陀螺仪零偏保持：静止 5 秒不准漂 --");
        GravityFusion f5 = new GravityFusion();
        feed(f5, 30, 0, 0, 0, 1.0, 100);
        double ux0 = f5.upX(), uy0 = f5.upY(), uz0 = f5.upZ();
        for (int i = 0; i < 500; i++) {
            T += 0.01;
            f5.setGyro(0, 0, 0, NS(T));
        }
        double drift = Math.toDegrees(Math.acos(Math.min(1, Math.abs(
                f5.upX() * ux0 + f5.upY() * uy0 + f5.upZ() * uz0))));
        check("零角速度 5s → 姿态漂移 < 0.1°", drift < 0.1, String.format("%.4f°", drift));

        System.out.println("\n-- 6) 没有陀螺仪（退化路径）：仍然做水平化 --");
        GravityFusion f6 = new GravityFusion();
        feedNoGyro(f6, 45, 0, 0, 0, 2.0, 100);
        check("无陀螺时静态读数仍 ≈ 0", Math.abs(f6.linF()) < 0.01 && Math.abs(f6.linU()) < 0.01,
                String.format("linF=%+.4f linU=%+.4f", f6.linF(), f6.linU()));
        check("hasGyro() = false", !f6.hasGyro(), "");

        System.out.println("\n-- 7) 装成 70°（把立上仰装）时前向 0.4s 推力：仍然读到前向加速度 --");
        GravityFusion f7 = new GravityFusion();
        feed(f7, 70, 0, 0, 0, 1.5, 100);              // 先静置收敛
        feed(f7, 70, 0.25, 0, 0, 0.4, 100);           // 沿地面「前」0.25G
        check("70° 装角 + 前向 0.25G → linF ≈ +0.25（±0.05）",
                Math.abs(f7.linF() - 0.25) < 0.05, String.format("linF=%+.3f linU=%+.3f", f7.linF(), f7.linU()));
        check("装角不窜到竖直分量 |linU| < 0.03（旧算法这里会读出 0.9G 假读数）",
                Math.abs(f7.linU()) < 0.03, String.format("linU=%+.3f", f7.linU()));

        System.out.println("\n-- 8) reset() --");
        f7.reset();
        check("reset 后 !ready()", !f7.ready(), "");

        System.out.println("\n=== 结果：" + pass + " 通过 / " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
