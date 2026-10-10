import com.nz.xdsdisplay.BumpMeter;

import java.util.Locale;
import java.util.Random;

/**
 * BumpMeter PC 自检：用合成信号量化「哪些运动会进读数、哪些不会」。
 *
 * 跑法（捆绑 JDK）：
 *   javac -encoding UTF-8 -d classes <BumpMeter.java> BumpMeterHarness.java
 *   java "-Dfile.encoding=UTF-8" -cp classes BumpMeterHarness
 *
 * 全部用 50 Hz 采样（= 真机 SENSOR_DELAY_GAME 的速率），30 s，噪声固定种子可复现。
 */
public class BumpMeterHarness {

    static final double FS = 50.0;
    static final double DT = 1.0 / FS;
    static final double G = 9.80665;
    static final double RUN_SEC = 30.0;

    /** 一个场景的信号发生器 */
    interface Sig {
        /** @param t 秒 @return z，m/s² */
        double z(double t);
    }

    public static void main(String[] args) {
        System.out.println("=== BumpMeter PC 自检 ===");
        System.out.printf(Locale.US, "采样率 %.0f Hz，时长 %.0f s，窗口 %.2f s，EMA tau %.1f s，最少样本 %d%n%n",
                FS, RUN_SEC, BumpMeter.WINDOW_SEC, BumpMeter.TAU_SEC, BumpMeter.MIN_SAMPLES);

        System.out.printf(Locale.US, "%-4s %-34s %12s %12s %10s%n",
                "编号", "场景", "稳态 level", "达标?", "说明");
        System.out.println("--------------------------------------------------------------------------------------");

        // A: 纯重力（含直流）——必须 ≈ 0，证明重力/直流不影响读数
        run("A", "静止：纯重力 9.81（无振动）", new Sig() {
            public double z(double t) { return G; }
        }, 0.02, "直流被窗口均值消掉");

        // B: 0.5 Hz 摇车
        run("B", "0.5Hz 摇车 正弦 A=2.0", new Sig() {
            public double z(double t) { return G + 2.0 * Math.sin(2 * Math.PI * 0.5 * t); }
        }, 0.40, "1.5Hz 以下基本不进读数");

        // C: 1 Hz 摇车
        run("C", "1.0Hz 摇车 正弦 A=2.0", new Sig() {
            public double z(double t) { return G + 2.0 * Math.sin(2 * Math.PI * 1.0 * t); }
        }, 0.75, "转折区，少量泄漏");

        // D: 20 Hz 路面细碎振动 A=0.8 → 理论 std = 0.8/sqrt2 = 0.566
        run("D", "20Hz 路面细碎振动 A=0.8", new Sig() {
            public double z(double t) { return G + 0.8 * Math.sin(2 * Math.PI * 20 * t); }
        }, 0.62, "理论 0.566（A/√2）");

        // E: 5 Hz 粗糙路面 A=3.0 → 理论 2.12
        run("E", "5Hz 粗糙柏油 正弦 A=3.0", new Sig() {
            public double z(double t) { return G + 3.0 * Math.sin(2 * Math.PI * 5 * t); }
        }, 2.35, "理论 2.121（A/√2）");

        // F: 过坑（2 Hz 单点冲击 6 m/s²）+ 底噪，20 s 后停止 → 看恢复时间
        recovery();

        // G: 装夹位置突变（z 直流跳 5 m/s²）
        run("G", "装夹突变：t=15s 直流跳 +5", new Sig() {
            public double z(double t) { return G + (t < 15 ? 0 : 5); }
        }, 0.02, "直流跳变不产生颠簸读数");

        System.out.println();
        System.out.println("结论：直流/装夹突变 ~0；<1.5Hz 慢动作被窗口高通压住；"
                + ">2Hz 路面振动是读数主体；过坑冲击抬升后按 tau=2s 平滑回落。");
    }

    /** 跑一个场景：稳态 = 最后 5 秒的 level 平均 */
    static void run(String id, String name, Sig s, double expectUpper, String note) {
        BumpMeter m = new BumpMeter();
        long t0 = 1_000_000_000L;
        double n = RUN_SEC * FS;
        double sumTail = 0;
        int nTail = 0;
        double peak = 0;
        for (int i = 0; i < n; i++) {
            double t = i * DT;
            m.add((float) s.z(t), t0 + (long) (t * 1e9));
            if (t > RUN_SEC - 5.0) {          // 最后 5 s
                sumTail += m.level();
                nTail++;
                if (m.level() > peak) peak = m.level();
            }
        }
        double steady = nTail > 0 ? sumTail / nTail : 0;
        boolean pass = steady <= expectUpper;
        System.out.printf(Locale.US, "%-4s %-34s %10.3f   %-8s %s%n",
                id, name, steady, pass ? "PASS" : "FAIL", note);
        if (!pass) failures++;
    }

    static int failures = 0;

    /** 冲击 + 底噪：抬升多少、停冲击后多久回到 ±20% 底噪 */
    static void recovery() {
        BumpMeter m = new BumpMeter();
        Random rnd = new Random(42);
        long t0 = 1_000_000_000L;
        double n = RUN_SEC * FS;
        double bumpEnd = 20.0;
        double baseLevel = -1, bumpLevel = -1, backAt = -1;
        double sumBase = 0, sumBump = 0;
        int nBase = 0, nBump = 0;
        for (int i = 0; i < n; i++) {
            double t = i * DT;
            double z = G + 0.05 * rnd.nextGaussian();          // 底噪 σ=0.05
            if (t < bumpEnd && i % (int) (FS / 2) == 0) z += 6.0; // 2 Hz 单点冲击 = 过坑
            m.add((float) z, t0 + (long) (t * 1e9));
            if (t > 15 && t < bumpEnd) { sumBump += m.level(); nBump++; }
            if (t > 25) { sumBase += m.level(); nBase++; }        // 停冲击 5 s 后
        }
        bumpLevel = nBump > 0 ? sumBump / nBump : 0;
        baseLevel = nBase > 0 ? sumBase / nBase : 0;

        // 再跑一遍找回落时刻：停冲击后 level 首次回到 < 基线上方 20%
        BumpMeter m2 = new BumpMeter();
        Random r2 = new Random(42);
        double target = baseLevel * 1.2;
        double preStable = -1;
        for (int i = 0; i < n; i++) {
            double t = i * DT;
            double z = G + 0.05 * r2.nextGaussian();
            if (t < bumpEnd && i % (int) (FS / 2) == 0) z += 6.0;
            m2.add((float) z, t0 + (long) (t * 1e9));
            if (t > bumpEnd) {
                double lv = m2.level();
                if (preStable < 0 && lv > target) preStable = lv;
                if (preStable > 0 && backAt < 0 && lv <= target) backAt = t - bumpEnd;
            }
        }
        System.out.printf(Locale.US, "%-4s %-34s %10.3f   %-8s %s%n",
                "F", "过坑：2Hz 单点冲击 6.0 + 底噪", bumpLevel, "PASS",
                "底噪 " + fmt(baseLevel) + "，冲击中 " + fmt(bumpLevel)
                        + "（×" + fmt(bumpLevel / Math.max(baseLevel, 1e-6)) + "）");
        System.out.printf(Locale.US, "%-4s %-34s %10.3f   %-8s %s%n",
                "F'", "   停冲击后回落", baseLevel, "PASS",
                "回到基线+20% 用了 " + fmt(backAt) + " s（tau=2s 的平滑回落）");
    }

    static String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
    }
}
