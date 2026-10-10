package com.nz.xdsdisplay;

import java.util.Random;

/**
 * 「采样率把姿态修正时间常数压短了」的回归验证（PC 端直接跑）。
 *
 * 背景：GravityFusion 的姿态修正增益原来是**每帧固定 α**（0.05 / 0.001 / 0.0001），
 * 那是按 50Hz 标定的。把传感器从 50Hz 提到 400Hz 后，同一个 α 每帧修正 8 倍快：
 *   dev<1.5% 档：τ = 1/(0.05×394) = 51ms（50Hz 时是 400ms）
 *   dev<3.5% 档：τ = 2.5s（50Hz 时是 20s）
 * 姿态于是把「真实运动加速度」也当成重力吸进去 → lin 读数自己往 0 收。
 * 真机表现就是用户说的：圆点抖一下就回中心、不平滑（像丢帧）。
 *
 * 本测试把**改前**的类冻结成 FusionBeforeRateFix，和改后的活代码喂同一段合成数据，
 * 在 50 / 200 / 394Hz 三个采样率下比较读数：
 *   1) 改后：三个采样率读数一致（时间常数按 dt 归一化）
 *   2) 改后 @50Hz ≈ 改前 @50Hz（新增益就是旧标定，手感不会变）
 *   3) 改前 @394Hz 的读数明显塌掉（复现用户报的回归）
 *
 * 场景（手机装成 tilt=30° = 把立上仰；地面系前向加速度）：
 *   0~2s   静止（量静置噪声底的均值/标准差）
 *   2~6s   持续 0.25G（量稳态读数有没有被吃掉）
 *   6~6.4s 脉冲 0.50G（量「点一下」的峰值）
 *   6.4~12s 1.5Hz 摆动 ±0.10G（量骑行中那种小幅加减速还剩多少 = 「跟不跟手」）
 */
public class GravityFusionRateTest {

    static int pass, fail;

    static long NS(double s) { return (long) (s * 1e9); }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
                + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (ok) pass++;
        else fail++;
    }

    /** 两种实现共用的调用面（活代码 / 冻结的改前代码） */
    interface F {
        void accel(float x, float y, float z, long tNs);

        void gyro(float x, float y, float z, long tNs);

        double linF();

        void reset();
    }

    static F live() {
        final GravityFusion g = new GravityFusion();
        return new F() {
            public void accel(float x, float y, float z, long t) { g.setAccel(x, y, z, t); }

            public void gyro(float x, float y, float z, long t) { g.setGyro(x, y, z, t); }

            public double linF() { return g.linF(); }

            public void reset() { g.reset(); }
        };
    }

    static F before() {
        final FusionBeforeRateFix g = new FusionBeforeRateFix();
        return new F() {
            public void accel(float x, float y, float z, long t) { g.setAccel(x, y, z, t); }

            public void gyro(float x, float y, float z, long t) { g.setGyro(x, y, z, t); }

            public double linF() { return g.linF(); }

            public void reset() { g.reset(); }
        };
    }

    static double[] norm(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1e-12) return new double[]{0, 0, 0};
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    /** 地面系前向 = 屏幕 y 的水平投影（与 GravityFusion.rebuildBasis 同一套规则） */
    static double[] fwd(double[] u) {
        double d = u[1];
        return norm(new double[]{-d * u[0], 1 - d * u[1], -d * u[2]});
    }

    /** 时间线：地面系前向加速度（G） */
    static double afAt(double t) {
        if (t < 2.0) return 0;
        if (t < 6.0) return 0.25;
        if (t < 6.4) return 0.50;
        return 0.10 * Math.sin(2 * Math.PI * 1.5 * (t - 6.4));
    }

    static class R {
        double bias, sd;        // 静置段的均值 / 标准差
        double s1, s2, s4;      // 持续 0.25G 的 1/2/4 秒读数
        double peak;            // 0.50G 脉冲峰值
        double sway;            // 1.5Hz ±0.10G 摆动的 RMS（稳态，理论 0.0707）
    }

    /** 以 rate 采样跑完 12s 时间线，收集指标 */
    static R run(F f, int rate) {
        double tilt = Math.toRadians(30);
        double[] u = new double[]{0, Math.sin(tilt), Math.cos(tilt)};
        double[] fw = fwd(u);
        Random rnd = new Random(20261010L);
        double sig = 0.03;                                  // 加速度噪声 σ=0.03 m/s² ≈ 0.003G
        R r = new R();
        double biasSum = 0, biasSq = 0, swaySq = 0;
        int biasN = 0, swayN = 0;
        double t, step = 1.0 / rate;
        for (int i = 0; i < (int) (12.0 * rate); i++) {
            t = i / (double) rate;                          // 用精确采样序号，别累加浮点（会在相位边界漂过去）
            double af = afAt(t);
            double[] a = new double[3];
            for (int k = 0; k < 3; k++) {
                a[k] = u[k] * GravityFusion.G0 + fw[k] * af * GravityFusion.G0
                        + rnd.nextGaussian() * sig;
            }
            long ts = NS(t);
            f.gyro(0, 0, 0, ts);                            // 真机有陀螺：同时喂零角速度
            f.accel((float) a[0], (float) a[1], (float) a[2], ts);
            double lf = f.linF();
            if (t >= 0.5 && t < 1.5) { biasSum += lf; biasSq += lf * lf; biasN++; }
            if (Math.abs(t - 3.0) < step / 2) r.s1 = lf;    // 持续 0.25G 里第 1s
            if (Math.abs(t - 4.0) < step / 2) r.s2 = lf;    // 第 2s
            if (Math.abs(t - 5.0) < step / 2) r.s4 = lf;    // 第 3s（0.25G 稳态内）
            if (t >= 6.0 && t < 6.4) r.peak = Math.max(r.peak, lf);
            if (t >= 9.0 && t < 12.0) { swaySq += lf * lf; swayN++; }
            t += step;
        }
        if (biasN > 0) {
            r.bias = biasSum / biasN;
            double var = biasSq / biasN - r.bias * r.bias;
            r.sd = Math.sqrt(Math.max(0, var));
        }
        r.sway = swayN > 0 ? Math.sqrt(swaySq / swayN) : 0;
        return r;
    }

    static String line(String impl, int rate, R r) {
        return String.format(java.util.Locale.US,
                "%-4s %3dHz | 静置 bias=%+.4f sd=%.4f | 0.25G: 1s=%+.3f 2s=%+.3f 3s=%+.3f | 0.5G峰=%+.3f | 摆动RMS=%.4f",
                impl, rate, r.bias, r.sd, r.s1, r.s2, r.s4, r.peak, r.sway);
    }

    static double rel(double a, double b) {
        double d = Math.max(Math.abs(a), Math.abs(b));
        return d < 1e-9 ? 0 : Math.abs(a - b) / d;
    }

    static boolean near(R a, R b, double tol) {
        return rel(a.s1, b.s1) < tol && rel(a.s2, b.s2) < tol && rel(a.s4, b.s4) < tol
                && rel(a.peak, b.peak) < tol && rel(a.sway, b.sway) < tol;
    }

    public static void main(String[] args) {
        System.out.println("=== 姿态修正增益的采样率无关性（改前 vs 改后）===\n");
        int[] rates = {50, 200, 394};
        R[] nw = new R[rates.length];
        R[] od = new R[rates.length];
        for (int i = 0; i < rates.length; i++) {
            nw[i] = run(live(), rates[i]);
            od[i] = run(before(), rates[i]);
        }
        System.out.println("-- 逐项读数 --");
        for (int i = 0; i < rates.length; i++) System.out.println("  " + line("改后", rates[i], nw[i]));
        for (int i = 0; i < rates.length; i++) System.out.println("  " + line("改前", rates[i], od[i]));

        System.out.println("\n-- 1) 改后：采样率变了，读数不该变（以 50Hz 为基准，±15%）--");
        for (int i = 1; i < rates.length; i++) {
            check("改后 " + rates[i] + "Hz ≈ 改后 50Hz", near(nw[0], nw[i], 0.15),
                    String.format(java.util.Locale.US,
                            "2s %+.3f/%+.3f  4s %+.3f/%+.3f  峰 %+.3f/%+.3f  摆动 %.4f/%.4f",
                            nw[i].s2, nw[0].s2, nw[i].s4, nw[0].s4, nw[i].peak, nw[0].peak,
                            nw[i].sway, nw[0].sway));
        }

        System.out.println("\n-- 2) 改后 @50Hz ≈ 改前 @50Hz（新增益 = 旧标定，手感不变）--");
        check("50Hz 下两版一致（±15%）", near(nw[0], od[0], 0.15),
                String.format(java.util.Locale.US, "2s %+.3f/%+.3f  4s %+.3f/%+.3f  峰 %+.3f/%+.3f  摆动 %.4f/%.4f",
                        nw[0].s2, od[0].s2, nw[0].s4, od[0].s4, nw[0].peak, od[0].peak,
                        nw[0].sway, od[0].sway));

        System.out.println("\n-- 3) 改前 @394Hz 塌掉（复现用户报的「不平滑/丢帧」）--");
        R o = od[2], n = nw[2];
        check("改前 394Hz 稳态读数被吃掉 >25%", rel(o.s2, n.s2) > 0.25,
                String.format(java.util.Locale.US, "2s: 改前 %+.3f vs 改后 %+.3f（残留 %.0f%%）",
                        o.s2, n.s2, 100 * o.s2 / n.s2));
        check("改前 394Hz 脉冲峰值偏低 >25%", rel(o.peak, n.peak) > 0.25,
                String.format(java.util.Locale.US, "峰: 改前 %+.3f vs 改后 %+.3f", o.peak, n.peak));
        check("改前 394Hz 骑行摆动被吃掉 >25%（「不跟手」）", rel(o.sway, n.sway) > 0.25,
                String.format(java.util.Locale.US, "摆动RMS: 改前 %.4f vs 改后 %.4f",
                        o.sway, n.sway));
        check("改前采样率越高越糟（394Hz 比它自己 50Hz 更塌）", o.s2 < od[0].s2 * 0.9,
                String.format(java.util.Locale.US, "2s: 50Hz %+.3f → 394Hz %+.3f", od[0].s2, o.s2));

        System.out.println("\n=== 结果：" + pass + " 通过 / " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
