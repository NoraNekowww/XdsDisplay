package com.nz.xdsdisplay;

import java.util.Random;

/**
 * GearRatio 自检（PC 端直接跑，不需要真机）。
 *
 * 建模按 2025 款 AD350：1×12 单盘 48T + 11-48T 飞轮、700c（周长 2.146m）。
 * 输入形态 = 真机上的：每帧给「当前车速 + 当前踏频 + 当前功率」，车速由目标齿比反推（模拟 GPS），
 * 可叠噪声。默认 4Hz 喂帧（中继 CPS 帧率量级），功率默认 200W（要验空踩就用 frameP / feedP）。
 *
 * 时间戳走**虚拟时钟**（起点=真实毫秒，每帧 +250ms）：真机上帧是 250ms 一帧来的，
 * PC 上循环跑得飞快，用真实时钟会让「切档后 800ms 保持」永远不满足 → 假失败。
 */
public class GearRatioTest {

    static int pass, fail;
    static final double DT = 0.25;      // 喂帧间隔（s）
    static long CLK = System.currentTimeMillis();

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
                + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (ok) pass++;
        else fail++;
    }

    static double speedOf(double cadence, double ratio) {
        return cadence * ratio * GearRatio.wheelCircM() / 60.0;   // m/s
    }

    /** 喂一帧（虚拟时钟 +250ms），功率 200W = 正常踩踏 */
    static void frame(GearRatio g, double speedMps, double cadence) {
        frameP(g, speedMps, cadence, 200);
    }

    /** 喂一帧并指定功率（W）——用来验「功率 < 20W 的踏频不作齿比依据」 */
    static void frameP(GearRatio g, double speedMps, double cadence, double powerW) {
        CLK += (long) (DT * 1000);
        g.update(speedMps, cadence, powerW, CLK);
    }

    /** 所有档「判定中心 / 出厂值 − 1」的最大绝对值 —— 验证判定表没被算法重写 */
    static double maxCenterDev(GearRatio g) {
        double[] t = GearRatio.table();
        double m = 0;
        for (int i = 0; i < t.length; i++) {
            m = Math.max(m, Math.abs(g.centerRatio(i) / t[i] - 1.0));
        }
        return m;
    }

    /** 理想：踏频 cadence、齿比 ratio，连续 seconds 秒 */
    static void feed(GearRatio g, double cadence, double ratio, double seconds) {
        feedNoisy(g, cadence, ratio, seconds, 0, 1);
    }

    /** 同上，车速带 ±noisePct 随机噪声（模拟 GPS 抖动） */
    static void feedNoisy(GearRatio g, double cadence, double ratio, double seconds,
                          double noisePct, long seed) {
        Random rnd = new Random(seed);
        double base = speedOf(cadence, ratio);
        int n = (int) Math.round(seconds / DT);
        for (int i = 0; i < n; i++) {
            double v = noisePct <= 0 ? base : base * (1 + (rnd.nextDouble() * 2 - 1) * noisePct);
            frame(g, v, cadence);
        }
    }

    public static void main(String[] args) throws Exception {
        double[] table = GearRatio.table();
        String[] labels = GearRatio.tableLabels();
        System.out.println("=== GearRatio 自检（AD350 2025 款：1×12 / 48T / 11-48T / 700c）===");
        System.out.println("齿比表 " + table.length + " 档：" + labels[0] + "="
                + String.format("%.3f", table[0]) + " … " + labels[table.length - 1] + "="
                + String.format("%.3f", table[table.length - 1])
                + "，轮周长 " + GearRatio.wheelCircM() + "m，喂帧 " + DT + "s/帧");

        check("12 档（1×12）", table.length == 12, String.valueOf(table.length));
        check("首档 = 48/11 = 4.364", Math.abs(table[0] - 48.0 / 11) < 1e-9, String.format("%.4f", table[0]));
        check("末档 = 48/48 = 1.000", Math.abs(table[11] - 1.0) < 1e-9, String.format("%.4f", table[11]));
        check("档位名用 / （48/17）", "48/17".equals(labels[3]), labels[3]);
        check("轮周长 700c = 2.146m", Math.abs(GearRatio.wheelCircM() - 2.146) < 1e-9,
                String.valueOf(GearRatio.wheelCircM()));
        check("牙盘 48T", GearRatio.chainring() == 48, String.valueOf(GearRatio.chainring()));

        System.out.println("\n-- 1) 稳定档吸附：90rpm / 48-17（第 4 档） --");
        GearRatio g = new GearRatio();
        feed(g, 90, 48.0 / 17, 6);
        check("吸附到 48/17 = 2.824", Math.abs(g.ratio() - 48.0 / 17) < 0.03, String.format("%.3f", g.ratio()));
        check("档位名 48/17", "48/17".equals(g.label()), g.label());
        check("档位号 \"9\"（正向：1=最轻 48/48 … 12=最重 48/11）", "9".equals(g.positionText()), g.positionText());
        check("飞轮齿数 17", g.cogTeeth() == 17, String.valueOf(g.cogTeeth()));
        check("known()", g.known(), "");
        check("轮转速 ≈ 254rpm（90×2.824）", Math.abs(g.wheelRpm() - 90 * 48.0 / 17) < 6,
                String.format("%.1f", g.wheelRpm()));
        check("踏频回读 ≈ 90rpm", Math.abs(g.cadenceRpm() - 90) < 1,
                String.format("%.1f", g.cadenceRpm()));

        System.out.println("\n-- 2) 换挡响应：48/22 → 48/17，几帧内必须跟上（旧算法要 3 次命中 + 1.5s） --");
        GearRatio gr = new GearRatio();
        feed(gr, 85, 48.0 / 22, 4);
        boolean pre = "48/22".equals(gr.label());
        check("先稳定在 48/22（第 7 档）", pre, gr.label());
        int frames = 0;
        while (frames < 12 && !"48/17".equals(gr.label())) {
            frame(gr, speedOf(85, 48.0 / 17), 85);
            frames++;
        }
        check("换挡后 ≤4 帧（1s）切到 48/17", "48/17".equals(gr.label()) && frames <= 4,
                "用了 " + frames + " 帧（" + String.format("%.2f", frames * DT) + "s）→ " + gr.label());
        check("档位号跟上是 \"9\"", "9".equals(gr.positionText()), gr.positionText());

        System.out.println("\n-- 3) GPS 车速 ±4% 抖动 8 秒，档位不许跳 --");
        GearRatio gn = new GearRatio();
        feed(gn, 85, 48.0 / 22, 3);
        boolean steady = "48/22".equals(gn.label());
        for (int s = 0; s < 32 && steady; s++) {
            feedNoisy(gn, 85, 48.0 / 22, DT, 0.04, 100 + s);
            steady = "48/22".equals(gn.label()) && "7".equals(gn.positionText());
        }
        check("8 秒 ±4% 噪声后仍是 48/22（第 7 档）", steady,
                gn.label() + " 档位号=" + gn.positionText() + " 比值=" + String.format("%.3f", gn.ratio()));

        System.out.println("\n-- 4) ±6% 大幅抖动下也不切到相邻档 --");
        GearRatio gb = new GearRatio();
        feed(gb, 85, 48.0 / 28, 3);
        boolean steady2 = "48/28".equals(gb.label());
        for (int s = 0; s < 24 && steady2; s++) {
            feedNoisy(gb, 85, 48.0 / 28, DT, 0.06, 200 + s);
            steady2 = "48/28".equals(gb.label());
        }
        check("6 秒 ±6% 噪声后仍是 48/28", steady2, gb.label() + " 档位号=" + gb.positionText());

        System.out.println("\n-- 5) 【任务】踏频 0（滑行/停踩）+ 车速还在 12m/s：档位必须保持，不许跳 12 档 --");
        GearRatio gc = new GearRatio();
        feed(gc, 85, 48.0 / 22, 4);
        String before = gc.label();
        for (int i = 0; i < 40; i++) frame(gc, 12.0, 0);          // 10s：踏频 0，车速 43km/h
        check("先稳定在 48/22", "48/22".equals(before), before);
        check("踏频 0 十秒后仍是 48/22（第 7 档）", "48/22".equals(gc.label()) && "7".equals(gc.positionText()),
                gc.label() + " 档位号=" + gc.positionText());
        check("期间 known() 保持 true", gc.known(), "known=" + gc.known());
        for (int i = 0; i < 40; i++) frame(gc, 14.0, 3);          // 极慢踏频（推车）也不行
        check("踏频 3rpm 十秒后仍是 48/22", "48/22".equals(gc.label()), gc.label());

        System.out.println("\n-- 6) 车速/踏频配不上对（12m/s + 30rpm → 比值 11.2）不许乱吸附 --");
        GearRatio gd = new GearRatio();
        feed(gd, 85, 48.0 / 19, 4);
        for (int i = 0; i < 40; i++) frame(gd, 12.0, 30);
        check("仍是 48/19（第 8 档）", "48/19".equals(gd.label()), gd.label() + " " + gd.positionText());

        System.out.println("\n-- 7) 单帧野值（换挡瞬间）不切档 --");
        GearRatio ge = new GearRatio();
        feed(ge, 90, 48.0 / 17, 4);
        frame(ge, speedOf(90, 48.0 / 11), 90);                    // 一帧野值
        check("仍是 48/17", "48/17".equals(ge.label()), ge.label());

        System.out.println("\n-- 8) 逐档跟踪：12 档全跑一遍，每档必须命中正确下标 --");
        GearRatio gt = new GearRatio();
        int wrong = 0;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < table.length; i++) {
            double cad = 80 + i * 4;                              // 每档换一个踏频，避免凑巧
            feed(gt, cad, table[i], 3);
            if (gt.tableIndex() != i) {
                wrong++;
                if (bad.length() < 120) bad.append(labels[i]).append("→").append(gt.label()).append(" ");
            }
        }
        check("12 档全部命中", wrong == 0, wrong == 0 ? "0 错" : bad.toString());

        System.out.println("\n-- 9) 没有车速 / 车速太低（1km/h）→ 不产生档位 --");
        GearRatio gv = new GearRatio();
        for (int i = 0; i < 20; i++) frame(gv, 0, 90);
        check("无车速 → 无档位", gv.label().isEmpty() && !gv.known(),
                "label=\"" + gv.label() + "\"");
        for (int i = 0; i < 20; i++) frame(gv, 0.28, 90);
        check("1km/h → 仍无档位", gv.label().isEmpty() && !gv.known(), "label=\"" + gv.label() + "\"");

        System.out.println("\n-- 10) 【任务】档位号正向：1 = 最轻（48/48），12 = 最重最快（48/11） --");
        GearRatio ga = new GearRatio();
        feed(ga, 90, 48.0 / 36, 6);
        check("48/36 = 第 3 档 → \"3\"", "3".equals(ga.positionText()),
                ga.positionText() + " " + ga.label());
        GearRatio gb2 = new GearRatio();
        feed(gb2, 90, 48.0 / 42, 6);
        check("48/42 = 第 2 档 → \"2\"", "2".equals(gb2.positionText()),
                gb2.positionText() + " " + gb2.label());
        GearRatio gc2 = new GearRatio();
        feed(gc2, 100, 48.0 / 48, 6);
        check("48/48 = 第 1 档 → \"1\"（最轻）", "1".equals(gc2.positionText()),
                gc2.positionText() + " " + gc2.label());
        GearRatio g1 = new GearRatio();
        feed(g1, 100, 48.0 / 11, 6);
        check("48/11 = 第 12 档 → \"12\"（最重最快）", "12".equals(g1.positionText()),
                g1.positionText() + " " + g1.label());

        System.out.println("\n-- 11) 滑行 10s：档位保持、known() 不变 --");
        GearRatio g4 = new GearRatio();
        feed(g4, 90, 48.0 / 25, 8);
        check("先吸附到 48/25（第 6 档）", "48/25".equals(g4.label()), g4.label());
        for (int i = 0; i < 40; i++) frame(g4, 6.0, 0);
        check("滑行 10s 后仍显示 48/25", "48/25".equals(g4.label()) && g4.known(), g4.label());

        System.out.println("\n-- 12) reset() 后回到未知 --");
        g4.reset();
        check("reset 后无档位", g4.label().isEmpty() && !g4.known() && g4.ratio() == 0,
                "label=\"" + g4.label() + "\" ratio=" + g4.ratio());

        System.out.println("\n-- 13) 【任务】功率 < 20W（空踩 / 溜腿）不采样：档位冻结在上一稳定档 --");
        GearRatio gp = new GearRatio();
        feed(gp, 85, 48.0 / 22, 5);                       // 200W：先稳定在 48/22（第 7 档）
        boolean pre13 = "48/22".equals(gp.label());
        // 车速按 48/42（更轻的档）反推、踏频照旧 85rpm，但功率只有 8W = 空踩
        for (int i = 0; i < 60; i++) frameP(gp, speedOf(85, 48.0 / 42), 85, 8);
        check("先稳定在 48/22", pre13, String.valueOf(pre13));
        check("功率 8W 十五秒后仍是 48/22（第 7 档）",
                "48/22".equals(gp.label()) && "7".equals(gp.positionText()),
                gp.label() + " 档位号=" + gp.positionText());
        // 功率回到 200W → 恢复采样，应该吸附到当前车速对应的 48/42
        for (int i = 0; i < 16; i++) frameP(gp, speedOf(85, 48.0 / 42), 85, 200);
        check("功率回到 200W → 吸附到 48/42（第 2 档）",
                "48/42".equals(gp.label()) && "2".equals(gp.positionText()),
                gp.label() + " 档位号=" + gp.positionText());

        System.out.println("\n-- 14) 【任务】统计学微调：前置数据小偏 2% → 以出厂表为基准吸收掉 --");
        // 真车轮周长比常量小 2%（换外胎/胎压/载重就在这个量级），观测量（车速/踏频之比）
        // 就整体放大 1.02 → 不修的话最轻档 48/48 会往 48/45 一带偏，是「差一档」的苗头。
        GearRatio gCal = new GearRatio();
        double S = 1.02;
        feed(gCal, 80, table[11] * S, 20);           // 48/48 骑 20s（4Hz → 80 帧）
        feed(gCal, 80, table[6] * S, 20);            // 48/25 骑 20s
        feed(gCal, 80, table[0] * S, 20);            // 48/11 骑 20s
        check("拟合出整体尺度 ≈ 1.02（落在 ±3% 微调范围内）",
                Math.abs(gCal.scale() - 1.02) <= 0.005, String.format("%.4f", gCal.scale()));
        check("没顶到 ±3% 边界（说明这确实只是「偏差」）", !gCal.scaleAtLimit(),
                String.valueOf(gCal.scaleAtLimit()));
        check("3 个骑行过的档位都认领到实测齿比", gCal.calibratedCount() == 3,
                String.valueOf(gCal.calibratedCount()));
        check("最重档判到 48/11（第 12 档）",
                "48/11".equals(gCal.label()) && "12".equals(gCal.positionText()),
                gCal.label() + " 档位号=" + gCal.positionText());
        feed(gCal, 80, table[11] * S, 20);           // 回到最轻档
        check("回到最轻档 = 48/48（第 1 档）",
                "48/48".equals(gCal.label()) && "1".equals(gCal.positionText()),
                gCal.label() + " 档位号=" + gCal.positionText());
        check("判据中心始终贴着出厂表（每档都在 ±3% 尺度 + ±2.5% 微调 = ±5.5% 内）",
                maxCenterDev(gCal) <= 0.0551, String.format("%.4f", maxCenterDev(gCal)));

        System.out.println("\n-- 15) 校准落盘：导出 → 新实例导入 → 立刻生效（跨骑行累积） --");
        String blob = gCal.exportCalibration();
        GearRatio gImp = new GearRatio();
        int got = gImp.importCalibration(blob);
        check("导出/导入簇（≥3）", got >= 3, "got=" + got + "  blob=" + blob);
        check("导入后尺度沿用导出时的值", Math.abs(gImp.scale() - gCal.scale()) <= 0.001,
                String.format("%.4f → %.4f", gCal.scale(), gImp.scale()));
        check("导入后 3 档已是校准状态", gImp.calibratedCount() == 3,
                String.valueOf(gImp.calibratedCount()));
        for (int i = 0; i < 8; i++) frame(gImp, speedOf(80, table[11] * S), 80);
        check("不用重新学：一喂上就判对 48/48（第 1 档）",
                "48/48".equals(gImp.label()) && "1".equals(gImp.positionText()),
                "label=\"" + gImp.label() + "\" 档位号=" + gImp.positionText());
        check("坏数据整条丢掉（齿比 9.9 不在 0.5..6）",
                new GearRatio().importCalibration("s=1.10;c=9.9,5") == 0, "");
        check("空串/垃圾串不认",
                new GearRatio().importCalibration("") == 0
                        && new GearRatio().importCalibration("hello") == 0, "");

        System.out.println("\n-- 16) 前置数据本来就对（尺度 1.0）时不乱校准 --");
        GearRatio gOk = new GearRatio();
        feed(gOk, 90, table[11], 12);               // 48/48
        feed(gOk, 90, table[7], 12);                // 48/28
        feed(gOk, 90, table[3], 12);                // 48/17
        check("尺度保持 1.000", Math.abs(gOk.scale() - 1.0) < 1e-9,
                String.format("%.4f", gOk.scale()));
        check("出厂判定不变：仍判最重的 48/17（第 9 档）",
                "48/17".equals(gOk.label()) && "9".equals(gOk.positionText()),
                gOk.label() + " 档位号=" + gOk.positionText());
        gOk.resetCalibration();
        check("清空校准 → 回到出厂（0 档已校准、尺度 1.000）",
                gOk.calibratedCount() == 0 && Math.abs(gOk.scale() - 1.0) < 1e-9,
                gOk.calibratedCount() + " / " + String.format("%.4f", gOk.scale()));

        System.out.println("\n-- 17) 【用户反馈】前置数据整体偏 10%：不硬掰，判定表始终贴着出厂表 --");
        // 用户原话「挡位算法矫正过度，应以 AD350 预设为基准进行微调」。
        // 偏到 10% 已经不是「偏差」而是「预设本身不对」（换飞轮/换轮径/常量写错），
        // 这时算法只许停在 ±3% 边界、把标记立起来提示核对，绝不许自己长出一张无关的判定表。
        GearRatio gBig = new GearRatio();
        double SB = 1.10;
        feed(gBig, 80, table[11] * SB, 20);
        feed(gBig, 80, table[6] * SB, 20);
        feed(gBig, 80, table[0] * SB, 20);
        check("整体尺度没有掰到 1.10（停在 ±3% 以内）", Math.abs(gBig.scale() - 1.0) <= 0.0301,
                String.format("%.4f", gBig.scale()));
        check("标记「实测偏差超出微调范围」→ 设置里提示核对轮周长/齿数表",
                gBig.scaleAtLimit(), String.valueOf(gBig.scaleAtLimit()));
        check("每档判据中心仍在出厂值 ±6% 内（判定表没被重写）",
                maxCenterDev(gBig) <= 0.0551, String.format("%.4f", maxCenterDev(gBig)));

        System.out.println("\n-- 18) 单档微调上限：某档实测偏 4% 也只许挪 2.5% --");
        // 登记门槛是 5%（对得上表才认），微调上限是 2.5% —— 于是「登记得上但偏得多」的档
        // 只会把判据中心挪 2.5%，挪不到旁边那档上去（旧版它就把中心整个搬过去了）。
        GearRatio gCap = new GearRatio();
        feed(gCap, 80, table[11], 20);               // 48/48：与出厂表一致
        feed(gCap, 80, table[7], 20);                // 48/28：一致
        feed(gCap, 80, table[3], 20);                // 48/17：一致
        feed(gCap, 80, table[0] * 1.04, 20);         // 48/11：实测偏 +4%
        check("整体尺度仍是 1.000（三个准的档把尺度钉住）",
                Math.abs(gCap.scale() - 1.0) < 0.005, String.format("%.4f", gCap.scale()));
        check("偏 4% 的那一档被标记为「微调已顶上限」", gCap.trimCapped(0),
                "center/theory=" + String.format("%.4f", gCap.centerRatio(0) / table[0]));
        check("它的判定中心 = 尺度 × (1 + 2.5%)，没跟着那 4% 走",
                gCap.centerRatio(0) / table[0] <= gCap.scale() * 1.0251 + 1e-6
                        && gCap.centerRatio(0) / table[0] < 1.033,
                String.format("%.4f（尺度 %.4f）",
                        gCap.centerRatio(0) / table[0], gCap.scale()));

        System.out.println("\n=== 结果：" + pass + " 通过 / " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
