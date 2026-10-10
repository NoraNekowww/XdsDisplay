import com.nz.xdsdisplay.WindDsp;

/**
 * WindDsp 的 PC 端验证 harness（纯 Java，javac/java 直接跑）。
 *
 * 合成三组 3 秒 / 16kHz 信号：
 *   A) 纯风噪：宽带噪声带限 200-1500Hz + 缓慢起伏包络
 *   B) 风噪 + 车噪：A 上叠加 60Hz + 120Hz 强低频（幅度约风噪的 3 倍）
 *   C) 风噪 + 车噪 + 花鼓：B 上再叠加每 0.25s 一个的窄带冲击（~350Hz 衰减振铃）
 *
 * 对每种信号分别跑 MODE_LEGACY 与 MODE_BAND，逐帧打印
 *   rms / bandDb / level / wind / rejected，并汇总三种信号下
 *   BAND 与 LEGACY 的 level 均值/波动。
 */
public class WindDspHarness {

    static final int SR = 16000;
    static final int SECS = 3;
    static final int N = SR * SECS;     // 48000
    static final int FRAME = 512;       // 32ms，和 WindNoise 的读块一致

    public static void main(String[] args) {
        float[] wind = synthWind();
        float[] car = synthCar();
        float[] drum = synthDrum();

        float[] A = mix(wind, null, null);
        float[] B = mix(wind, car, null);
        float[] C = mix(wind, car, drum);

        System.out.println("=== WindDsp harness  sr=" + SR + "Hz  len=" + SECS + "s  frame=" + FRAME
                + " samples (" + (FRAME * 1000 / SR) + "ms)  frames=" + (N / FRAME) + " ===");
        System.out.println();

        String[] names = {"A pure wind", "B wind+car(60/120Hz x3)", "C wind+car+drum(every0.25s)"};
        float[][] sigs = {A, B, C};

        double[] legacyMean = new double[3];
        double[] bandMean = new double[3];
        double[] legacyStd = new double[3];
        double[] bandStd = new double[3];

        for (int s = 0; s < 3; s++) {
            System.out.println("---- SIGNAL " + names[s] + " ----");
            double[] leg = run(sigs[s], WindDsp.MODE_LEGACY);
            double[] ban = run(sigs[s], WindDsp.MODE_BAND);
            legacyMean[s] = leg[0];
            legacyStd[s] = leg[1];
            bandMean[s] = ban[0];
            bandStd[s] = ban[1];
            System.out.println();
        }

        System.out.println("================ SUMMARY ================");
        System.out.printf("%-30s %16s %16s%n", "signal", "LEGACY mean lvl", "BAND mean lvl");
        for (int s = 0; s < 3; s++) {
            System.out.printf("%-30s %16.4f %16.4f%n", names[s], legacyMean[s], bandMean[s]);
        }
        System.out.println();
        System.out.printf("%-30s %16s %16s%n", "signal", "LEGACY std", "BAND std");
        for (int s = 0; s < 3; s++) {
            System.out.printf("%-30s %16.4f %16.4f%n", names[s], legacyStd[s], bandStd[s]);
        }
        System.out.println();
        System.out.printf("LEGACY level mean A->C delta: %+.4f  (full-band: car+drum inflate it)%n",
                legacyMean[2] - legacyMean[0]);
        System.out.printf("BAND   level mean A->C delta: %+.4f  (band-pass + median: stays flat)%n",
                bandMean[2] - bandMean[0]);
    }

    /** 跑一种模式，逐帧打印，返回 {mean, std, min, max}。 */
    static double[] run(float[] sig, int mode) {
        short[] buf = toShort(sig);
        WindDsp d = new WindDsp(SR);
        d.setMode(mode);
        System.out.println("  mode=" + mode + "  " + WindDsp.MODE_NAMES[mode]);
        System.out.printf("  %5s %7s %9s %9s %9s %6s %6s%n",
                "frame", "t(s)", "rms", "bandDb", "level", "wind", "rej");

        double sum = 0, sum2 = 0;
        float mn = 2f, mx = -1f;
        int frames = buf.length / FRAME;
        for (int f = 0; f < frames; f++) {
            WindDsp.Result r = d.process(buf, f * FRAME, FRAME);
            System.out.printf("  %5d %7.3f %9.5f %9.2f %9.4f %6s %6s%n",
                    f, (f + 1) * (double) FRAME / SR, r.rms, r.bandDb, r.level,
                    r.wind ? "YES" : "no", r.rejected ? "REJ" : "-");
            sum += r.level;
            sum2 += (double) r.level * r.level;
            if (r.level < mn) mn = r.level;
            if (r.level > mx) mx = r.level;
        }
        double mean = sum / frames;
        double var = sum2 / frames - mean * mean;
        if (var < 0) var = 0;
        double std = Math.sqrt(var);
        System.out.printf("  -> frames=%d  level mean=%.4f  std=%.4f  min/max=%.4f/%.4f%n",
                frames, mean, std, mn, mx);
        return new double[]{mean, std, mn, mx};
    }

    // ---- 合成 ----
    static float[] synthWind() {
        java.util.Random rnd = new java.util.Random(20240501L);
        float[] w = new float[N];
        Biquad hp = Biquad.highPass(SR, 200, 0.707);
        Biquad lp = Biquad.lowPass(SR, 1500, 0.707);
        for (int i = 0; i < N; i++) {
            float x = (float) rnd.nextGaussian();
            w[i] = lp.step(hp.step(x));               // 带限到风噪频段
        }
        double sum = 0;
        for (int i = 0; i < N; i++) sum += (double) w[i] * w[i];
        float g = (float) (0.05 / (Math.sqrt(sum / N) + 1e-9));  // 目标 RMS = 0.05
        for (int i = 0; i < N; i++) {
            double t = (double) i / SR;
            double env = 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 0.35 * t)); // 缓慢起伏
            w[i] = (float) (w[i] * g * env);
        }
        return w;
    }

    static float[] synthCar() {
        float[] c = new float[N];
        float amp = 0.15f;   // 每个分量振幅 0.15 vs 风噪目标 RMS 0.05 -> 约 3x
        for (int i = 0; i < N; i++) {
            double t = (double) i / SR;
            c[i] = (float) (amp * (Math.sin(2 * Math.PI * 60 * t) + Math.sin(2 * Math.PI * 120 * t)));
        }
        return c;
    }

    static float[] synthDrum() {
        float[] d = new float[N];
        double period = 0.25;    // 每 0.25s 一下
        double decay = 0.012;    // 衰减振铃
        double freq = 350.0;     // 300-500Hz 窄带
        float amp = 0.8f;
        for (int i = 0; i < N; i++) {
            double t = (double) i / SR;
            double ph = t % period;                       // 距最近一次冲击的时间
            double e = Math.exp(-ph / decay);
            d[i] = (float) (amp * e * Math.sin(2 * Math.PI * freq * ph));
        }
        return d;
    }

    static float[] mix(float[] a, float[] b, float[] c) {
        float[] o = new float[N];
        for (int i = 0; i < N; i++) {
            float v = a[i];
            if (b != null) v += b[i];
            if (c != null) v += c[i];
            o[i] = v;
        }
        return o;
    }

    static short[] toShort(float[] f) {
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) {
            int v = Math.round(f[i] * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            s[i] = (short) v;
        }
        return s;
    }

    /** 用于合成带限噪声的二阶 biquad（与 WindDsp 独立实现）。 */
    static final class Biquad {
        float b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static Biquad highPass(int fs, double f0, double q) {
            Biquad b = new Biquad();
            double w0 = 2 * Math.PI * f0 / fs, cw = Math.cos(w0), sw = Math.sin(w0);
            double alpha = sw / (2 * q), a0 = 1 + alpha;
            b.b0 = (float) ((1 + cw) / 2 / a0);
            b.b1 = (float) (-(1 + cw) / a0);
            b.b2 = (float) ((1 + cw) / 2 / a0);
            b.a1 = (float) (-2 * cw / a0);
            b.a2 = (float) ((1 - alpha) / a0);
            return b;
        }

        static Biquad lowPass(int fs, double f0, double q) {
            Biquad b = new Biquad();
            double w0 = 2 * Math.PI * f0 / fs, cw = Math.cos(w0), sw = Math.sin(w0);
            double alpha = sw / (2 * q), a0 = 1 + alpha;
            b.b0 = (float) ((1 - cw) / 2 / a0);
            b.b1 = (float) ((1 - cw) / a0);
            b.b2 = (float) ((1 - cw) / 2 / a0);
            b.a1 = (float) (-2 * cw / a0);
            b.a2 = (float) ((1 - alpha) / a0);
            return b;
        }

        float step(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }
}
