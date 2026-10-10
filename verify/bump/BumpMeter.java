package com.nz.xdsdisplay;

/**
 * 道路颠簸度量 = z 轴加速度「局部方差」的近期平均值。
 *
 * <p>用法：加速度计每来一个采样就 {@link #add(float, long)}，UI 刷新时读 {@link #level()}。
 *
 * <p>三个设计点（都是为了让读数真的对应「路面颠簸」）：
 * <ol>
 *   <li><b>只做局部方差</b>：滑动窗口（{@link #WINDOW_SEC}）内的样本先扣掉窗口均值再算方差。
 *       所以重力 9.8 和安装倾角这类直流分量天然被消掉 —— 上层喂原始 z（含重力）还是
 *       已扣重力的线性加速度 z，结果完全一样，不需要任何校准。</li>
 *   <li><b>窗口 = 高通</b>：扣均值这一步等价于一个转折频率 ≈ 0.44/{@link #WINDOW_SEC}
 *       （默认 0.25 s → ≈1.8 Hz）的高通。摇车、过弯压弯这类 &lt;1.5 Hz 的慢动作基本
 *       不进读数，路面细碎振动（&gt;2 Hz）才是主体。</li>
 *   <li><b>近期平均</b>：对窗口标准差做时间常数 {@link #TAU_SEC} 的指数滑动平均。
 *       单个坑不会让读数一闪而过，也不会一秒钟就掉回去 —— 骑行中看得清。</li>
 * </ol>
 *
 * <p>纯算法，不 import 任何 android.* —— {@code verify/bump/BumpMeterHarness.java}
 * 在 PC 上 javac/java 直接跑，用合成信号验证上面每一条（见 harness 输出）。
 */
public class BumpMeter {

    /**
     * 局部方差窗口（秒）。默认 0.25 s。
     * <p>调大 = 更稳但会把摇车算进来；调小 = 只留高频、读数更抖。
     * 改了它请重跑 {@code verify/bump} 的 harness 看数（那里量化了各频段的贡献）。
     */
    public static final double WINDOW_SEC = 0.25;

    /** 「近期平均」的指数滑动平均时间常数（秒） */
    public static final double TAU_SEC = 2.0;

    /** 窗口里至少要有这么多个样本才出读数（刚开机 / 断流后不报假数） */
    public static final int MIN_SAMPLES = 6;

    /**
     * 颠簸指数 0..100 的满量程（m/s²）。**15.0** ≈ 连续过烂路/搓板路才顶满。
     *
     * <p>2026-10 调过一档：原来是 5.0，用户反馈「指示条过于敏感」—— 柏油路正常骑行的
     * z 轴局部标准差就有 1~3 m/s²，5.0 的满量程意味着条子平时就半满、稍微一颠就顶格，
     * 看不出差别。15.0（≈1.5G）下同样的路只占 10%~20%，只有真过坑/烂路才明显抬起来。
     * 颜色档（{@link com.nz.xdsdisplay.WindBumpBars} 里的 50/80）是按**百分比**给的，
     * 所以警告阈值会跟着满量程一起等比上移，判读口径不变。
     *
     * <p>改这个常量请重跑 {@code verify/bump} 的 harness（那里按 m/s² 打读数）。
     */
    public static final float CEIL_MS2 = 15.0f;

    /** 采样间隔超过这个值视为断流：清空窗口重来，不让旧数据拖尾 */
    private static final double GAP_SEC = 0.5;

    /** 环形缓冲容量：窗口只按**时间戳**裁（{@link #WINDOW_SEC}），但缓冲满会先挤掉最老的样本，
     *  所以容量必须装得下「窗口时长 × 最高采样率」。一加 5T 提到 ≈400Hz 后 0.25 s = 100 个，
     *  512 个够 2000Hz 还有余量（提到高采样率前是 128）。 */
    private static final int CAP = 512;

    private final float[] zs = new float[CAP];
    private final double[] ts = new double[CAP];
    private int head, count;
    private double sum, sumSq;          // 窗口内 Σz / Σz²（滑动维护）
    private double level;               // m/s²，窗口标准差的指数平均
    private double lastWinT = Double.NaN;
    private double lastT = Double.NaN;
    private boolean primed;

    /**
     * 加一个采样。
     *
     * @param z      z 轴加速度，m/s²（含不含重力都行，见类注释第 1 条）
     * @param tNanos 采样时间戳（纳秒，直接用 {@code SensorEvent.timestamp}）
     */
    public void add(float z, long tNanos) {
        double t = tNanos / 1e9;
        if (!Double.isNaN(lastT) && (t - lastT) > GAP_SEC) reset();
        lastT = t;

        if (count == CAP) {                     // 缓冲满：先挤掉最老的
            sum -= zs[head];
            sumSq -= (double) zs[head] * zs[head];
            head = (head + 1) % CAP;
            count--;
        }
        int tail = (head + count) % CAP;
        zs[tail] = z;
        ts[tail] = t;
        count++;
        sum += z;
        sumSq += (double) z * z;

        while (count > 1 && (t - ts[head]) > WINDOW_SEC) {   // 只留窗口内的
            sum -= zs[head];
            sumSq -= (double) zs[head] * zs[head];
            head = (head + 1) % CAP;
            count--;
        }
        if (count < MIN_SAMPLES) return;        // 样本不够：还没读数

        double mean = sum / count;
        double var = sumSq / count - mean * mean;
        if (var < 0) var = 0;                   // 浮点误差兜底
        double sd = Math.sqrt(var);

        if (!primed) {
            level = sd;
            primed = true;
        } else {
            double dt = t - lastWinT;
            if (dt <= 0) dt = 1.0 / 50.0;
            if (dt > GAP_SEC) dt = GAP_SEC;
            double alpha = 1.0 - Math.exp(-dt / TAU_SEC);
            level += (sd - level) * alpha;
        }
        lastWinT = t;
    }

    /** 近期平均颠簸量（m/s²，= z 轴加速度的局部标准差）。还没数据时返回 0。 */
    public float level() {
        return primed ? (float) level : 0f;
    }

    /** 是否已经有有效读数（UI 用它决定显示数字还是 {@code --}） */
    public boolean hasData() {
        return primed;
    }

    /** 颠簸指数：level / {@link #CEIL_MS2} 归一到 0..100，给竖直条用 */
    public static float norm100(float level) {
        float v = level / CEIL_MS2 * 100f;
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }

    /** 清空（换了装夹位置 / 重新开骑时想从零开始可调） */
    public void reset() {
        head = 0;
        count = 0;
        sum = 0;
        sumSq = 0;
        level = 0;
        primed = false;
        lastWinT = Double.NaN;
        lastT = Double.NaN;
    }
}
