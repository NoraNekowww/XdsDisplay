package com.nz.xdsdisplay;

/**
 * 档位 / 齿比估算（纯算法、无 Android 依赖，PC 端 javac 直接跑）。
 *
 * 车：喜德盛 AD350 2025 款 —— 1×12 单盘（48T）+ 11-48T 飞轮、700c 轮胎。
 *
 *   齿比 = 轮转速 / 曲柄转速 = (车速 / 轮周长 × 60) / 踏频
 *
 * 也就是说：**用近几帧的车速与踏频之比**来算，而不是靠曲柄事件时间反推周期。
 * 与旧版的区别（都是真机上暴露出来的毛病）：
 *
 *   1) 单帧比值只当一票，真正的判据是窗口（≈1.3s、最多 5 帧）比值的**中位数**。
 *      旧版单帧 + 9% 容差，GPS 车速抖一下就落在两档之间 → 「换挡齿比不变」；
 *      中位数把单帧野值一次压掉，档位因此又能跟着换挡跳。
 *   2) 踏频 < 25rpm（滑行 / 停车 / 推车）**根本不采样**，保持上一稳定档位。
 *      旧版会拿残留的曲柄周期硬算，比值一冲高就吸附到最高档 → 「踏频 0 跳 12 档」。
 *   2b) 功率 < 20W **同样不采样**：空踩 / 溜腿时踏频看着正常，但车并没有真出力，
 *      这种帧算出来的齿比会乱跳（任务：功率低于 20W 的踏频不作齿比依据）。
 *   3) 命中后连续 2 次才切、切档后 800ms 保持 —— 比旧的「3 次 + 1.5s」快一倍，
 *      肉眼就是「一换挡马上跟上」，同时仍不会被单帧抖动带着跳档。
 *   4) 比值必须落在 0.85..4.9（48/48=1.000 … 48/11=4.364 两侧留余量）才算数，
 *      车速与踏频配不上对（比如 GPS 滞后）时宁可不显示。
 *   5) 滑行时**保持**上一档（2 分钟不踩才回到 --），不会跳 0、也不会跳到别的档。
 *   6) <b>统计学微调</b>（2026-10 收紧 —— 用户反馈旧版「矫正过度」）：上面所有判定都建立在
 *      「48T 牙盘 / 11-48T 飞轮 / 轮周长 2.146m」这套**出厂预设**上。真车里前置数据总有
 *      小偏差（换了外胎差 1~3%、GPS 距离整体缩水…），不修就是「一直差一档」。修法：把通过
 *      筛选的帧（不依赖当前档位判定！）按齿比做无监督聚类，骑一会儿就在用过的档位上堆出
 *      几个窄簇；再在 ±3%（{@link #CAL_SC_MIN}..{@link #CAL_SC_MAX}）里做一维网格搜索，
 *      取「让最多簇同时对上出厂齿比表」的那个整体尺度 s。
 *      <b>判定基准始终是出厂表</b>：某档判据中心 = 出厂理论值 × s × (1 + 该档微调)，
 *      微调取自实测簇、但被 {@link #CAL_TRIM_MAX}（±2.5%）夹住 —— 实测再离谱也拖不动多少。
 *      旧版是直接拿实测均值当判据中心、尺度还能一路搜到 ±15%，等于让算法把整张表重写一遍，
 *      结果就是用户说的「矫正过度」；现在一律收紧成「预设为主、微调为辅」。
 *      整体偏差一旦顶到 ±3% 边界（那是「预设不对」而不是「偏差」），**不硬掰**，
 *      {@link #scaleAtLimit()} 置真、设置界面提示核对轮周长/齿数表。
 *      校准结果落盘（{@link #exportCalibration()}），跨骑行累积；只有实测帧数够多
 *      （{@link #CAL_MIN_N}）的档位才参与，避免噪声。
 *
 * 档位号按骑行习惯**正向**给：1 = 最轻（48/48，爬坡）、12 = 最重最快（48/11）。
 * 齿比表本身是按飞轮齿数从小到大建的（下标 0 = 48/11），所以号数要反着数。
 *
 * 换车 / 换飞轮 / 换胎只改下面三个常量。
 */
public class GearRatio {

    /** 牙盘齿数（AD350 2025 款：1×12 单盘 48T） */
    private static final int[] CHAINRINGS = {48};
    /** 飞轮齿数（2025 款 11-48T 12 速） */
    private static final int[] COGS = {11, 13, 15, 17, 19, 22, 25, 28, 32, 36, 42, 48};
    /** 车轮周长（m）：700×30C（2025 款原装胎）≈ (622 + 2×30) mm × π = 2.153，实测取 2.146 */
    public static final double WHEEL_CIRC_M = 2.146;

    private static final double[] TABLE = build();
    private static final String[] LABELS = makeLabels();

    private static final int    WIN_N      = 5;       // 窗口最多几帧
    private static final long   WIN_MS     = 1300L;   // 窗口最长时间跨度（"近几帧"）
    private static final int    MIN_N      = 2;       // 至少这么多帧才出档位
    private static final double CAD_MIN    = 25.0;    // 踏频下限：低于此不采样（滑行/推车）
    private static final double CAD_MAX    = 160.0;   // 踏频上限：以上说明踏频数据不对
    private static final double PWR_MIN    = 20.0;    // 功率下限（W）：低于此不采样（空踩，没真出力）
    private static final double SPEED_MIN  = 0.4;     // m/s（≈1.4km/h）：再慢 GPS 车速就是噪声
    private static final double SPEED_MAX  = 25.0;    // m/s（90km/h）
    private static final double SNAP_TOL   = 0.09;    // 9% 之内算命中（给 GPS 车速噪声留余量）
    private static final double RATIO_MIN  = 0.85;
    private static final double RATIO_MAX  = 4.9;
    private static final int    CONFIRM_N  = 2;       // 连续命中次数才切档
    private static final long   HOLD_MS    = 800L;    // 切档后的保持时间
    private static final long   STALE_MS   = 120000L; // 多久没踩踏算未知（滑行保持久一点）

    // ---- 统计学微调（见类注释第 6 条）：以出厂预设为基准，只做小幅度修正 ----------
    /**
     * 一个簇至少这么多帧，才算「这一档的实测齿比」。
     * 旧版 16 帧（4Hz 下 4 秒）太少 —— 换挡过渡、GPS 抖动凑几秒就成型，学得快也错得快；
     * 30 帧（4Hz 下 7.5s、真机 1Hz 下 30s）统计量才站得住。
     */
    private static final int    CAL_MIN_N    = 30;
    /** 至少要有这么多「成型」的簇，才敢拟合整体尺度 */
    private static final int    CAL_MIN_CL   = 3;
    /** 聚类半径（相对）：跟已有簇中心差 3.5% 以内就归进那个簇 */
    private static final double CAL_RADIUS   = 0.035;
    /** 最多跟踪几个簇（12 档 + 换挡过渡的杂簇余量） */
    private static final int    CAL_MAX      = 16;
    /** 尺度拟合时「这个簇对得上表里某一档」的相对容差 */
    private static final double CAL_MATCH_TOL = 0.05;
    /**
     * 整体尺度的搜索范围与步长：**只给 ±3%**（旧版 0.86..1.16 = ±15%，太野了）。
     * 换外胎/胎压/载重造成的轮周长差、GPS 距离的系统性缩水都在 3% 以内；
     * 再大就不是「偏差」而是「预设本身错了」，该去改 {@link #COGS} / {@link #WHEEL_CIRC_M}，
     * 不该让算法把整张判定表掰过去。
     */
    private static final double CAL_SC_MIN   = 0.97;
    private static final double CAL_SC_MAX   = 1.03;
    private static final double CAL_SC_STEP  = 0.001;
    /** 每档相对「出厂值 × 整体尺度」最多再微调这么多（吸收齿数表的小误差，±2.5%） */
    private static final double CAL_TRIM_MAX = 0.025;
    /** 每积这么多帧重算一次尺度（省电：几百 ms 一次就够） */
    private static final int    CAL_EVERY    = 8;
    /** 已校准档位的吸附容差：实测均值就在那儿，用不着 9% 那么松 */
    private static final double SNAP_TOL_CAL = 0.05;
    /** 单个簇的帧数上限：到顶就冻结中心（一辆车同一个档位骑 30 分钟以上的统计量足够，别再无限累积） */
    private static final int    CAL_N_MAX    = 8000;

    /** 近几帧的原始比值（环形窗口，0..winCount-1 有效） */
    private final double[] winR = new double[WIN_N];
    private final long[] winT = new long[WIN_N];
    private int winCount;

    private double speed;            // 最近一次有效车速（m/s）
    private double cadence;          // 最近一次有效踏频（rpm）
    private long lastSampleMs;
    private long lastMoveMs;
    private long lastSwitchMs;

    private double shown;            // 显示用齿比（吸附/平滑后）
    private int tableIdx = -1;       // 命中档位下标，-1 = 表外/未知
    private int candIdx = -1;        // 候选档位
    private int candCount;

    // ---- 统计学自动校准的状态（见类注释第 6 条）--------------
    /** 实测齿比聚类：每个簇的累加和 / 帧数 / 当前均值（前 clCount 个有效） */
    private final double[] clSum = new double[CAL_MAX];
    private final int[]    clN   = new int[CAL_MAX];
    private final double[] clC   = new double[CAL_MAX];
    private int clCount;
    /** 整体尺度：实测齿比 / 理论齿比（吸收轮周长、齿数表、GPS 距离的系统性偏差） */
    private double calScale = 1.0;
    /** 最近一次拟合出的尺度是否顶在 ±3% 边界（= 实测偏差超出微调范围，设置里提示核对预设） */
    private boolean calScaleAtLimit;
    /** 每档的实测齿比中心与支撑帧数（0 = 还没校准，用理论值 × calScale） */
    private final double[] calC = new double[TABLE.length];
    private final int[]    calS = new int[TABLE.length];
    /** 距上次重算尺度的帧数 */
    private int calSinceRecal;

    /**
     * 喂一帧：当前车速 + 当前踏频 + 当前功率（都来自中继转发）。
     *
     * @param speedMps   车速 m/s（GPS；&lt;=0 表示本帧没有可用车速）
     * @param cadenceRpm 踏频 rpm（功率计直供，或用曲柄事件时间推算）
     * @param powerW     功率 W（功率计直供；没有功率计就传一个够大的数，比如 200）
     */
    public void update(double speedMps, double cadenceRpm, double powerW) {
        update(speedMps, cadenceRpm, powerW, System.currentTimeMillis());
    }

    /**
     * 同上，但由调用方指定时间戳（自检用虚拟时钟；真机走上面的重载取真实时间）。
     */
    public void update(double speedMps, double cadenceRpm, double powerW, long nowMs) {
        long now = nowMs;

        boolean cadOk = cadenceRpm >= CAD_MIN && cadenceRpm <= CAD_MAX;
        boolean spOk = speedMps >= SPEED_MIN && speedMps <= SPEED_MAX;
        boolean pwrOk = powerW >= PWR_MIN;               // 空踩（<20W）不作齿比依据
        if (cadOk) cadence = cadenceRpm;
        if (spOk) speed = speedMps;

        // 滑行 / 停踩 / 空踩（功率过低）/ 车速不可用：不采样、不切档，保持上一稳定档位
        // （任务：踏频 0 不跳档；功率 < 20W 的踏频不作依据）
        if (!cadOk || !spOk || !pwrOk) return;

        double wheelRpm = 60.0 * speed / WHEEL_CIRC_M;
        double r = wheelRpm / cadence;
        if (r < RATIO_MIN || r > RATIO_MAX) return;   // 车速与踏频配不上对：丢掉这一帧

        push(r, now);
        lastSampleMs = now;
        lastMoveMs = now;

        double med = median(now);
        if (med > 0) {
            calFeed(med);            // 统计学校准：先「看」这一帧实测齿比，跟当前判定无关
            accept(med, now);
        }
    }

    /** 入窗（满了丢最旧），并丢掉超时帧 */
    private void push(double r, long now) {
        if (winCount == WIN_N) {
            System.arraycopy(winR, 1, winR, 0, WIN_N - 1);
            System.arraycopy(winT, 1, winT, 0, WIN_N - 1);
            winCount--;
        }
        winR[winCount] = r;
        winT[winCount] = now;
        winCount++;
        trim(now);
    }

    /** 丢掉超出窗口时长的最旧帧（至少留 1 帧） */
    private void trim(long now) {
        int drop = 0;
        while (drop < winCount - 1 && now - winT[drop] > WIN_MS) drop++;
        if (drop > 0) {
            System.arraycopy(winR, drop, winR, 0, winCount - drop);
            System.arraycopy(winT, drop, winT, 0, winCount - drop);
            winCount -= drop;
        }
    }

    /** 窗口内比值的中位数；帧数不够返回 0 */
    private double median(long now) {
        trim(now);
        if (winCount < MIN_N) return 0;
        double[] c = new double[winCount];
        System.arraycopy(winR, 0, c, 0, winCount);
        java.util.Arrays.sort(c);
        int m = winCount / 2;
        return (winCount % 2 == 1) ? c[m] : (c[m - 1] + c[m]) / 2.0;
    }

    private void accept(double r, long now) {
        int idx = nearest(r);
        if (idx < 0) {
            // 落在两档之间（换挡瞬间 / 数据不干净）：保持原档，别乱跳
            if (tableIdx < 0) shown = (shown <= 0) ? r : shown + (r - shown) * 0.3;
            return;
        }
        if (idx == tableIdx) {
            shown += (center(idx) - shown) * 0.5;   // 稳定档：慢慢收敛到判据中心
            candIdx = -1;
            candCount = 0;
            return;
        }
        if (idx != candIdx) {                      // 新候选
            candIdx = idx;
            candCount = 1;
            return;
        }
        candCount++;
        if (candCount >= CONFIRM_N && now - lastSwitchMs > HOLD_MS) {
            tableIdx = idx;
            shown = center(idx);
            lastSwitchMs = now;
            candIdx = -1;
            candCount = 0;
            double dev = calS[idx] >= CAL_MIN_N ? (calC[idx] / TABLE[idx] - 1) * 100 : 0;
            Logs.add("GEAR", "档位 → " + positionText() + "  " + LABELS[idx] + "  "
                    + String.format(java.util.Locale.CHINA, "%.3f", center(idx))
                    + (dev != 0 ? String.format(java.util.Locale.CHINA, "  (实测偏 %+.1f%%)", dev) : ""));
        }
    }

    /** 最接近的一档（1×12 只有一组牙盘，取误差最小者）；都不在容差内返回 -1 */
    private int nearest(double r) {
        int best = -1;
        double bestErr = Double.MAX_VALUE;
        for (int i = 0; i < TABLE.length; i++) {
            double c = center(i);
            double tol = calS[i] >= CAL_MIN_N ? SNAP_TOL_CAL : SNAP_TOL;
            double err = Math.abs(r - c) / c;
            if (err > tol) continue;
            if (err < bestErr) {
                bestErr = err;
                best = i;
            }
        }
        return best;
    }

    /**
     * 某一档的判据中心 = 出厂理论值 × 整体尺度 × (1 + 该档微调)。
     *
     * <p>微调取自实测簇，但被 {@link #CAL_TRIM_MAX} 夹住：实测再离谱，判据中心也离不开
     * 「出厂值 × 尺度」2.5% 以上。旧版是「已校准就直接用实测均值当中心」，没有天花板 ——
     * 一个来路不明的簇就能把某档判据拖到旁边那档上，这就是「矫正过度」。
     */
    private double center(int i) {
        return TABLE[i] * calScale * (1.0 + trim(i));
    }

    /** 第 i 档的微调量（相对「出厂值 × 整体尺度」），已夹在 ±{@link #CAL_TRIM_MAX} 内；未校准为 0 */
    private double trim(int i) {
        if (calS[i] < CAL_MIN_N || calC[i] <= 0) return 0;
        double dev = calC[i] / (TABLE[i] * calScale) - 1.0;
        if (dev > CAL_TRIM_MAX) return CAL_TRIM_MAX;
        if (dev < -CAL_TRIM_MAX) return -CAL_TRIM_MAX;
        return dev;
    }

    /** 吸附后的齿比；0 = 未知 */
    public double ratio() {
        return shown;
    }

    /** 档位名（"48/17"），表外或未知时返回空串 */
    public String label() {
        return tableIdx >= 0 ? LABELS[tableIdx] : "";
    }

    /**
     * 档位号文字：直接十进制（1..12），表外或未知返回空串。
     *
     * 正向编号：1 = 最轻（48/48，爬坡）、12 = 最重最快（48/11）。
     * 表是按飞轮齿数从小到大建的（下标 0 = 最重），所以号数 = 12 − 下标。
     * 旧版是「下标 + 1」，结果最快的那档显示成 1 —— 骑起来是倒序的（任务）。
     */
    public String positionText() {
        if (tableIdx < 0) return "";
        return String.valueOf(TABLE.length - tableIdx);
    }

    /** 当前档位的飞轮齿数；未知返回 0 */
    public int cogTeeth() {
        return tableIdx >= 0 ? COGS[tableIdx] : 0;
    }

    /** 牙盘齿数 */
    public static int chainring() {
        return CHAINRINGS[0];
    }

    /** 车轮周长（m） */
    public static double wheelCircM() {
        return WHEEL_CIRC_M;
    }

    public int tableIndex() {
        return tableIdx;
    }

    /** 当前值是否有效（有估算且最近还在踩 / 刚滑行不久） */
    public boolean known() {
        return shown > 0 && System.currentTimeMillis() - lastMoveMs < STALE_MS;
    }

    /** 最近一次有效踏频（rpm），给卡片副标题用；无数据返回 0 */
    public double cadenceRpm() {
        return cadence;
    }

    /** 当前轮转速（rpm）＝ 车速 / 轮周长 × 60；无车速返回 0 */
    public double wheelRpm() {
        return speed > 0 ? 60.0 * speed / WHEEL_CIRC_M : 0;
    }

    /** 窗口里当前有几帧（自检用） */
    public int windowCount() {
        return winCount;
    }

    public void reset() {
        winCount = 0;
        speed = 0;
        cadence = 0;
        lastSampleMs = 0;
        lastMoveMs = 0;
        lastSwitchMs = 0;
        shown = 0;
        tableIdx = -1;
        candIdx = -1;
        candCount = 0;
        calSinceRecal = 0;
        // 校准数据**不**在这里清：它是跨骑行累积的统计量，重置只在菜单里显式做
    }

    /** 齿比表（只读，供测试/自检用） */
    public static double[] table() {
        return TABLE.clone();
    }

    // ================= 统计学自动校准（见类注释第 6 条）=================

    /**
     * 把一帧实测齿比喂进聚类器。
     *
     * <p>关键：这里**不看当前档位判定**。判定整体偏了也照学不误 —— 学的是「这台车上
     * 实际出现过哪几个齿比」，这是纯粹的观测量，不属于任何理论假设。这正是能修掉
     * 「前置数据 vs 实车」整体偏移的原因。
     */
    private void calFeed(double r) {
        // 先合簇：±噪声下同一个档位会在附近长出好几个簇（谁都进不了别人的半径），
        // 合并成按帧数加权的均值，簇中心才是这一档真正的统计量（不是先到者的位置）。
        mergeClusters();
        int best = -1;
        double bestErr = Double.MAX_VALUE;
        for (int i = 0; i < clCount; i++) {
            double err = Math.abs(r - clC[i]) / clC[i];
            if (err < bestErr) {
                bestErr = err;
                best = i;
            }
        }
        if (best >= 0 && bestErr <= CAL_RADIUS) {
            // 归进最近的簇。注意用的是**帧数累加和**求均值，不是对均值再平均，
            // 否则早期帧权重过大、簇中心会飘。
            if (clN[best] < CAL_N_MAX) {
                clSum[best] += r;
                clN[best]++;
                clC[best] = clSum[best] / clN[best];
            }
        } else if (clCount < CAL_MAX) {
            clSum[clCount] = r;
            clN[clCount] = 1;
            clC[clCount] = r;
            clCount++;
        } else {
            // 簇满了：只允许顶掉「还没成型」的簇（换挡过渡期冒出来的杂簇），
            // 别把已经学好的档位冲掉。
            int weak = -1;
            for (int i = 0; i < clCount; i++) {
                if (clN[i] < CAL_MIN_N && (weak < 0 || clN[i] < clN[weak])) weak = i;
            }
            if (weak >= 0) {
                clSum[weak] = r;
                clN[weak] = 1;
                clC[weak] = r;
            }
        }

        if (++calSinceRecal >= CAL_EVERY) {
            calSinceRecal = 0;
            recalibrate();
        }
    }

    /**
     * 把中心相距不到 {@link #CAL_RADIUS} 的簇合并成一个（按帧数加权）。
     *
     * <p>不合并的话：噪声让同一个档位的帧散落在中心两侧，每个簇的半径都兜不住对方，
     * 于是同一个档位会长出好几个簇，各自被当成一个「实测档位」—— 既挤爆
     * {@link #CAL_MAX}，又让「一档一个簇」的前提失效。
     */
    private void mergeClusters() {
        if (clCount < 2) return;
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < clCount; i++) {
                for (int j = i + 1; j < clCount; j++) {
                    if (Math.abs(clC[i] - clC[j]) / Math.min(clC[i], clC[j]) <= CAL_RADIUS) {
                        double n = clN[i] + clN[j];
                        clSum[i] += clSum[j];
                        clN[i] = (int) n;
                        clC[i] = clSum[i] / n;
                        // 尾巴挪到 j 的位置上
                        clCount--;
                        clC[j] = clC[clCount];
                        clN[j] = clN[clCount];
                        clSum[j] = clSum[clCount];
                        merged = true;
                        break outer;
                    }
                }
            }
        }
    }

    /**
     * 重算整体尺度 s 与各档实测中心。
     *
     * <p>一维网格搜索：s 在 {@link #CAL_SC_MIN}..{@link #CAL_SC_MAX} 上扫，
     * 让「所有已成型的实测簇」尽量同时对上理论齿比表的某一档。误差用高斯核加权
     * （(err/3%)² 衰减）、按簇的帧数加权 —— 帧数多的簇（骑得久的档位）话语权大。
     *
     * <p>认账的前提是≥70% 的帧都能对上（否则说明齿数表/轮周长根本不是差一个尺度，
     * 比如换了齿数不同的飞轮），此时保持 calScale 不变，宁可不校准也不要乱校准。
     */
    private void recalibrate() {
        int tot = 0;
        for (int i = 0; i < clCount; i++) {
            if (clN[i] >= CAL_MIN_N) tot += clN[i];
        }
        if (tot <= 0) return;
        int formed = 0;
        for (int i = 0; i < clCount; i++) if (clN[i] >= CAL_MIN_N) formed++;
        if (formed < CAL_MIN_CL) return;

        double bestS = 1.0, bestScore = -1;
        for (double s = CAL_SC_MIN; s <= CAL_SC_MAX + 1e-9; s += CAL_SC_STEP) {
            double score = 0;
            for (int i = 0; i < clCount; i++) {
                if (clN[i] < CAL_MIN_N) continue;
                // 尺度 s = 实测 / 理论：把实测簇**除**回去，看它对不对得上表里某一档
                double e = minErr(clC[i] / s) / 0.03;
                score += clN[i] * Math.exp(-e * e);
            }
            if (score > bestScore) {
                bestScore = score;
                bestS = s;
            }
        }

        int hit = 0;
        for (int i = 0; i < clCount; i++) {
            if (clN[i] >= CAL_MIN_N && minErr(clC[i] / bestS) <= CAL_MATCH_TOL) hit += clN[i];
        }
        if (hit < 0.7 * tot) {
            // 对不上：别硬校准。而且这多半是「预设本身不对」而不是「差一个尺度」，
            // 把标记立起来，设置界面据此提示用户去核对轮周长/齿数表。
            calScaleAtLimit = true;
            return;
        }

        boolean scaleChanged = Math.abs(bestS - calScale) > 0.001;
        calScale = bestS;
        // 顶在搜索边界上 = 实测偏差超出 ±3%：尺度到此为止，别硬掰
        calScaleAtLimit = bestS <= CAL_SC_MIN + CAL_SC_STEP * 1.5
                || bestS >= CAL_SC_MAX - CAL_SC_STEP * 1.5;

        for (int i = 0; i < clCount; i++) {
            if (clN[i] < CAL_MIN_N) continue;
            int gi = nearestTable(clC[i] / bestS);
            if (gi < 0) continue;
            // 一档只留帧数最多的那个簇（同一档不该有两个簇，除非换挡过渡留了个假的）
            if (clN[i] > calS[gi]) {
                calC[gi] = clC[i];
                calS[gi] = clN[i];
            }
        }
        if (scaleChanged) {
            Logs.add("GEAR", String.format(java.util.Locale.CHINA,
                    "微调尺度 %.4f（出厂偏 %+.1f%%）· 已校准 %d/%d 档%s",
                    bestS, (bestS - 1) * 100, calibratedCount(), TABLE.length,
                    calScaleAtLimit ? " · 已顶到 ±3% 边界，请核对轮周长/齿数表" : ""));
        }
    }

    /** v 到理论齿比表的最近相对误差 */
    private double minErr(double v) {
        double e = Double.MAX_VALUE;
        for (double t : TABLE) {
            double d = Math.abs(v - t) / t;
            if (d < e) e = d;
        }
        return e;
    }

    /** 理论齿比表里最接近 v 的档位下标；超出 {@link #CAL_MATCH_TOL} 返回 -1 */
    private int nearestTable(double v) {
        int best = -1;
        double bestErr = Double.MAX_VALUE;
        for (int i = 0; i < TABLE.length; i++) {
            double d = Math.abs(v - TABLE[i]) / TABLE[i];
            if (d < bestErr) {
                bestErr = d;
                best = i;
            }
        }
        return bestErr <= CAL_MATCH_TOL ? best : -1;
    }

    /** 已用实测数据校准的档位数（0..12） */
    public int calibratedCount() {
        int n = 0;
        for (int i = 0; i < TABLE.length; i++) if (calS[i] >= CAL_MIN_N) n++;
        return n;
    }

    /** 第 i 档的支撑帧数（下标 0 = 最重档） */
    public int samples(int i) {
        return calS[i];
    }

    /** 第 i 档的**实测**齿比中心；未校准返回 0（设置界面用它显示「实测偏差」） */
    public double measured(int i) {
        return calS[i] >= CAL_MIN_N ? calC[i] : 0;
    }

    /** 第 i 档**实际用于判定**的中心齿比（出厂值 × 整体尺度 × 有限微调） */
    public double centerRatio(int i) {
        return center(i);
    }

    /** 第 i 档的微调是否顶到了 ±{@link #CAL_TRIM_MAX} 上限（实测比允许的还偏） */
    public boolean trimCapped(int i) {
        if (calS[i] < CAL_MIN_N || calC[i] <= 0) return false;
        return Math.abs(calC[i] / (TABLE[i] * calScale) - 1.0) > CAL_TRIM_MAX;
    }

    /** 实测整体偏差是否超出 ±3% 微调范围（设置界面据此提示核对轮周长/齿数表） */
    public boolean scaleAtLimit() {
        return calScaleAtLimit;
    }

    /** 第 i 档的理论齿比 */
    public double theoretical(int i) {
        return TABLE[i];
    }

    /** 整体尺度（实测 / 理论） */
    public double scale() {
        return calScale;
    }

    /**
     * 导出校准状态（落盘用）：尺度 + 所有簇（含还没成型的，跨骑行继续累积）。
     *
     * <p>形如 {@code s=1.0412;c=1.082123,24;c=2.114318,31}；解析失败一律当没有。
     */
    public String exportCalibration() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US, "s=%.4f", calScale));
        for (int i = 0; i < clCount; i++) {
            sb.append(String.format(java.util.Locale.US, ";c=%.6f,%d", clC[i], clN[i]));
        }
        return sb.toString();
    }

    /**
     * 导入校准状态（{@link #exportCalibration()} 的格式）。
     *
     * <p>任何一项看起来不靠谱（齿比不在 0.5..6、帧数为负、超过上限）就整条丢掉，
     * 免得一份坏数据把档位判定带沟里。返回导入了几个簇。
     */
    public int importCalibration(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        clCount = 0;
        java.util.Arrays.fill(clN, 0);
        java.util.Arrays.fill(clSum, 0.0);
        java.util.Arrays.fill(calC, 0.0);
        java.util.Arrays.fill(calS, 0);
        calScale = 1.0;
        double scale = 1.0;
        int got = 0;
        for (String part : s.split(";")) {
            String p = part.trim();
            try {
                if (p.startsWith("s=")) {
                    double v = Double.parseDouble(p.substring(2));
                    if (v >= CAL_SC_MIN && v <= CAL_SC_MAX) scale = v;
                } else if (p.startsWith("c=")) {
                    String[] kv = p.substring(2).split(",");
                    if (kv.length != 2) continue;
                    double c = Double.parseDouble(kv[0]);
                    int n = Integer.parseInt(kv[1]);
                    if (c < 0.5 || c > 6.0 || n <= 0 || n > 2000000) continue;
                    if (clCount >= CAL_MAX) continue;
                    clC[clCount] = c;
                    clN[clCount] = n;
                    clSum[clCount] = c * n;
                    clCount++;
                    got++;
                }
            } catch (NumberFormatException ignore) {
                // 单项坏掉不影响其它项
            }
        }
        if (got == 0) {
            // 没有簇就退回出厂：尺度也别留着
            clCount = 0;
            calScale = 1.0;
            return 0;
        }
        calScale = scale;
        calScaleAtLimit = false;
        recalibrate();                            // 立刻按导入的簇重算各档中心
        return got;
    }

    /** 清空校准（菜单里「重置校准」用）：回到出厂齿比/轮周长 */
    public void resetCalibration() {
        clCount = 0;
        java.util.Arrays.fill(clN, 0);
        java.util.Arrays.fill(clSum, 0.0);
        java.util.Arrays.fill(calC, 0.0);
        java.util.Arrays.fill(calS, 0);
        calScale = 1.0;
        calScaleAtLimit = false;
        calSinceRecal = 0;
    }

    public static String[] tableLabels() {
        return LABELS.clone();
    }

    private static double[] build() {
        double[] t = new double[CHAINRINGS.length * COGS.length];
        int k = 0;
        for (int cr : CHAINRINGS) {
            for (int cog : COGS) t[k++] = (double) cr / cog;
        }
        return t;
    }

    private static String[] makeLabels() {
        String[] l = new String[CHAINRINGS.length * COGS.length];
        int k = 0;
        for (int cr : CHAINRINGS) {
            for (int cog : COGS) l[k++] = cr + "/" + cog;
        }
        return l;
    }
}
