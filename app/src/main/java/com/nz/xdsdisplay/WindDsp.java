package com.nz.xdsdisplay;

/**
 * 风噪频段过滤 DSP（纯算法，无 Android 依赖，PC 端 javac 可直接编译/运行）。
 *
 * 目标：让「风噪」只统计风噪频段（约 200Hz–1500Hz），把
 *   - 车流低频噪声（路面 / 轮胎 / 引擎，<150Hz 的强低频）
 *   - 花鼓 / 棘轮嗒嗒声（窄带周期性冲击）
 *   - 过车瞬间的尖峰
 * 尽量滤掉，不计入风噪电平。
 *
 * 处理链（MODE_BAND / MODE_BAND_STRONG）：
 *   6 阶 Butterworth 高通(200Hz) -> 2 阶低通(1500Hz)     真的改变频响
 *   -> 逐帧带内 RMS -> dBFS
 *   -> 5 帧中值（压周期性冲击尖峰）
 *   -> 过车判定（单帧电平 > 中值 * 2.5 -> rejected，且电平不跟涨）
 *   -> 150ms 时间常数平滑 -> 0..1 归一电平
 *   -> 自适应门限（21 帧基线中值 * 系数）判 wind
 *
 * MODE_LEGACY 走全带 RMS（旧行为，无滤波无抑制），仅作对照。
 */
public class WindDsp {

    public static final int MODE_LEGACY = 0;        // 全带（旧行为，对照用）
    public static final int MODE_BAND = 1;          // 带通 + 门限（默认）
    public static final int MODE_BAND_STRONG = 2;   // 带通 + 更强抑制
    public static final String[] MODE_NAMES = {"WIND FILTER OFF", "WIND FILTER ON", "WIND FILTER STRONG"};

    // ---- 频段（Hz）----
    private static final double HPF_HZ = 200.0;     // 风噪下沿；<150Hz 强低频判为车噪
    private static final double LPF_HZ = 1500.0;    // 风噪上沿

    // ---- dBFS 归一（与 WindNoise 的 FLOOR/CEIL 对齐）----
    private static final float FLOOR_DB = -70f;
    private static final float CEIL_DB = -10f;

    // ---- 电平平滑时间常数 ----
    private static final float TAU_S = 0.15f;       // ~150ms

    // ---- 冲击 / 过车抑制 ----
    private static final float OVERSHOOT = 2.5f;        // 单帧 > 5帧中值 * 2.5 -> rejected
    private static final float OVERSHOOT_STRONG = 1.8f; // STRONG 模式更敏感
    private static final float STRONG_LEVEL_GAIN = 0.85f;

    // ---- 自适应风噪门限（相对 baseline 中值）----
    private static final float WIND_COEF = 0.60f;   // wind = level > baseline中值 * 0.60

    // ---- 窗口 ----
    private static final int MED_N = 5;             // 5 帧中值（周期性冲击抑制）
    private static final int BASE_N = 21;           // 21 帧基线中值（自适应门限）

    private final int sampleRate;
    private int mode = MODE_BAND;

    // 6 阶 Butterworth 高通 = 三级二阶 biquad 级联（把 60/120Hz 车噪压到可忽略）
    private final Biquad hpA = new Biquad();
    private final Biquad hpB = new Biquad();
    private final Biquad hpC = new Biquad();
    private final Biquad lp = new Biquad();

    private float smoothLevel;
    private boolean smoothInit;

    private final float[] medRmsBuf = new float[MED_N];
    private int medRmsCount, medRmsIdx;

    private final float[] base = new float[BASE_N];
    private int baseCount, baseIdx;

    private final float[] scratch = new float[BASE_N];

    private final Result out = new Result();

    public WindDsp(int sampleRate) {
        this.sampleRate = (sampleRate > 0) ? sampleRate : 16000;
        double fs = this.sampleRate;
        // 6 阶 Butterworth 高通：三级 Q = 0.5176 / 0.7071 / 1.9319
        hpf(hpA, fs, HPF_HZ, 0.51763809);
        hpf(hpB, fs, HPF_HZ, 0.70710678);
        hpf(hpC, fs, HPF_HZ, 1.93185165);
        // 2 阶 Butterworth 低通：Q = 0.7071
        lpf(lp, fs, LPF_HZ, 0.70710678);
        reset();
    }

    public void setMode(int mode) {
        if (mode < MODE_LEGACY || mode > MODE_BAND_STRONG) mode = MODE_BAND;
        this.mode = mode;
        // 切换模式不重置滤波器/平滑状态，避免切换瞬间爆音。
    }

    public int mode() {
        return mode;
    }

    public void reset() {
        hpA.reset();
        hpB.reset();
        hpC.reset();
        lp.reset();
        smoothLevel = 0f;
        smoothInit = false;
        medRmsCount = 0;
        medRmsIdx = 0;
        baseCount = 0;
        baseIdx = 0;
        for (int i = 0; i < MED_N; i++) medRmsBuf[i] = 0f;
        for (int i = 0; i < BASE_N; i++) base[i] = 0f;
        out.rms = 0f;
        out.bandDb = -90f;
        out.level = 0f;
        out.wind = false;
        out.rejected = false;
    }

    public Result process(short[] buf, int off, int len) {
        if (buf == null || len <= 0 || off < 0 || off + len > buf.length) {
            return out;   // 非法输入：原样返回上一帧状态（不依赖调用方清零）
        }

        final boolean legacy = (mode == MODE_LEGACY);
        final float inv = 1f / 32768f;

        double sumFull = 0.0;
        double sumBand = 0.0;
        for (int i = 0; i < len; i++) {
            float x = buf[off + i] * inv;
            sumFull += (double) x * x;
            float y;
            if (legacy) {
                y = x;                       // 全带：不滤波
            } else {
                y = hpA.step(x);
                y = hpB.step(y);
                y = hpC.step(y);
                y = lp.step(y);
            }
            sumBand += (double) y * y;
        }

        float rmsFull = (float) Math.sqrt(sumFull / len);
        float rmsBand = (float) Math.sqrt(sumBand / len);
        float rawRms = legacy ? rmsFull : rmsBand;    // LEGACY 的“带内”就是全带

        // 5 帧中值（线性 RMS 域）—— 压周期性窄带冲击尖峰
        medRmsBuf[medRmsIdx] = rawRms;
        medRmsIdx = (medRmsIdx + 1) % MED_N;
        if (medRmsCount < MED_N) medRmsCount++;
        float medRms = median(medRmsBuf, medRmsCount);

        float levelRms = legacy ? rawRms : medRms;
        float bandDb = db(levelRms);
        float fullDb = db(rmsFull);

        float rawLevel = clamp01((db(rawRms) - FLOOR_DB) / (CEIL_DB - FLOOR_DB));

        // 21 帧基线中值（自适应门限，基于归一电平）
        base[baseIdx] = rawLevel;
        baseIdx = (baseIdx + 1) % BASE_N;
        if (baseCount < BASE_N) baseCount++;
        float baseline = median(base, baseCount);

        // 平滑系数：由帧时长与 150ms 时间常数决定，随 sampleRate 归一
        float frameT = (float) len / sampleRate;
        float alpha = 1f - (float) Math.exp(-frameT / TAU_S);
        if (alpha < 0.01f) alpha = 0.01f;
        if (alpha > 1f) alpha = 1f;

        boolean rejected = false;
        float target;
        if (legacy) {
            target = rawLevel;           // 旧行为：全带、无抑制
        } else {
            float overshoot = (mode == MODE_BAND_STRONG) ? OVERSHOOT_STRONG : OVERSHOOT;
            if (medRmsCount >= 3 && medRms > 1e-6f && rawRms > overshoot * medRms) {
                rejected = true;         // 过车 / 冲击：本帧 RMS 远高于中值
            }
            target = clamp01((bandDb - FLOOR_DB) / (CEIL_DB - FLOOR_DB)); // 中值驱动，尖峰被压下
            if (mode == MODE_BAND_STRONG) target *= STRONG_LEVEL_GAIN;
        }

        if (!smoothInit) {
            smoothLevel = target;
            smoothInit = true;
        } else {
            smoothLevel += (target - smoothLevel) * alpha;
        }
        smoothLevel = clamp01(smoothLevel);

        float thr = baseline * WIND_COEF;
        boolean wind = !rejected && smoothLevel > thr && smoothLevel > 1e-4f;

        out.rms = rmsFull;
        out.bandDb = legacy ? fullDb : bandDb;
        out.level = smoothLevel;
        out.wind = wind;
        out.rejected = rejected;
        return out;
    }

    // ------------------------------------------------------------------
    private static float db(float rms) {
        return 20f * (float) Math.log10(Math.max(rms, 1e-7f));
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 取 a[0..n-1] 的中值（n 很小，插入排序）。 */
    private float median(float[] a, int n) {
        if (n <= 0) return 0f;
        for (int i = 0; i < n; i++) scratch[i] = a[i];
        for (int i = 1; i < n; i++) {
            float k = scratch[i];
            int j = i - 1;
            while (j >= 0 && scratch[j] > k) {
                scratch[j + 1] = scratch[j];
                j--;
            }
            scratch[j + 1] = k;
        }
        return (n % 2 == 1) ? scratch[n / 2] : 0.5f * (scratch[n / 2 - 1] + scratch[n / 2]);
    }

    private static void hpf(Biquad q, double fs, double f0, double Q) {
        double w0 = 2.0 * Math.PI * f0 / fs;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * Q);
        double a0 = 1.0 + alpha;
        q.b0 = (float) ((1.0 + cw) / 2.0 / a0);
        q.b1 = (float) (-(1.0 + cw) / a0);
        q.b2 = (float) ((1.0 + cw) / 2.0 / a0);
        q.a1 = (float) (-2.0 * cw / a0);
        q.a2 = (float) ((1.0 - alpha) / a0);
    }

    private static void lpf(Biquad q, double fs, double f0, double Q) {
        double w0 = 2.0 * Math.PI * f0 / fs;
        double cw = Math.cos(w0), sw = Math.sin(w0);
        double alpha = sw / (2.0 * Q);
        double a0 = 1.0 + alpha;
        q.b0 = (float) ((1.0 - cw) / 2.0 / a0);
        q.b1 = (float) ((1.0 - cw) / a0);
        q.b2 = (float) ((1.0 - cw) / 2.0 / a0);
        q.a1 = (float) (-2.0 * cw / a0);
        q.a2 = (float) ((1.0 - alpha) / a0);
    }

    /** 直接 I 型二阶 biquad。 */
    private static final class Biquad {
        float b0, b1, b2, a1, a2;
        float x1, x2, y1, y2;

        void reset() {
            x1 = x2 = y1 = y2 = 0f;
        }

        float step(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /** 每帧复用同一实例（各字段每帧都会被写满，调用方无需清零）。 */
    public static final class Result {
        public float rms;        // 全带 RMS（0..1 归一，对照用）
        public float bandDb;     // 风噪频段电平，dBFS（负值）
        public float level;      // 0..1 归一的风噪电平（喂 UI 电平条）
        public boolean wind;     // 是否判定为风噪
        public boolean rejected; // 本帧被抑制（低频车噪 / 窄带冲击 / 过车尖峰）
    }
}
