package com.nz.xdsdisplay;

import java.io.ByteArrayOutputStream;

/**
 * CpsParser 踩踏平衡字段自检（PC 端直接跑，不需要真机）。
 *
 * 依据 Bluetooth SIG Cycling Power Measurement (0x2A63) / GATT Specification Supplement：
 *   flags bit0 = Pedal Power Balance Present
 *   flags bit1 = Pedal Power Balance Reference   0 = Unknown / 1 = Left（枚举里没有 Right）
 *   平衡字段  = uint8，单位 %、分辨率 0.5 → 值 / 2 就是百分比，且恒为「参考脚」那一侧的占比。
 *
 * 于是「平衡条左半截 = 左脚占比」这条 UI 不变量只靠三点撑着，本自检就盯这三点：
 *   ① 值必须 /2（不是当整数百分比，更不能取反）；
 *   ② 不能把最高位当「参考脚标志」掩掉 —— 0x80 = 64.0%，掩成 0x00 就变 0%；
 *   ③ 前面多挂几个 flag（累积扭矩/轮/曲柄/能量）时平衡字段的偏移不能走歪。
 */
public class CpsBalanceTest {

    static int pass, fail;

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
                + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (ok) pass++;
        else fail++;
    }

    /** flags 组装器 */
    static int bits(int... bs) {
        int f = 0;
        for (int b : bs) f |= (1 << b);
        return f;
    }

    static final int B0_BAL = 0, B1_REF = 1, B2_TORQUE = 2, B4_WHEEL = 4, B5_CRANK = 5, B11_ENERGY = 11;

    /** 一帧 CPS：flags + 功率 + 尾巴（尾巴按 flag 顺序自己拼） */
    static byte[] cps(int flags, int power, byte[] tail) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(flags & 0xFF);
        o.write((flags >> 8) & 0xFF);
        o.write(power & 0xFF);
        o.write((power >> 8) & 0xFF);
        if (tail != null) o.write(tail, 0, tail.length);
        return o.toByteArray();
    }

    static byte[] tail(int... us) {
        byte[] b = new byte[us.length];
        for (int i = 0; i < us.length; i++) b[i] = (byte) (us[i] & 0xFF);
        return b;
    }

    static CpsParser.Data run(int flags, int power, byte[] tail) {
        byte[] f = cps(flags, power, tail);
        CpsParser.Data d = new CpsParser.Data();
        CpsParser.parse(f, 0, f.length, d);
        return d;
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    public static void main(String[] args) {
        System.out.println("== CpsParser 踩踏平衡方向 / 解码自检 ==");

        // ① 表声明参考脚 = 左脚：0x70 = 112 → 56.0%
        CpsParser.Data d = run(bits(B0_BAL, B1_REF), 200, tail(0x70));
        check("bit1=1（表声明左脚）：112 → 56.0%", near(d.balanceLeftPct, 56.0), "got " + d.balanceLeftPct);
        check("bit1=1 → balanceRefLeft = true", d.balanceRefLeft, "refLeft=" + d.balanceRefLeft);
        check("hasBalance / powerW 正常", d.hasBalance && d.powerW == 200,
                "has=" + d.hasBalance + " p=" + d.powerW);

        // ② 表没声明参考脚（bit1=0）：值依旧当左脚占比用，不做任何取反
        d = run(bits(B0_BAL), 200, tail(0x70));
        check("bit1=0（未声明）：112 → 仍是 56.0%（不取反）", near(d.balanceLeftPct, 56.0), "got " + d.balanceLeftPct);
        check("bit1=0 → balanceRefLeft = false", !d.balanceRefLeft, "refLeft=" + d.balanceRefLeft);
        check("未声明时 describe() 有备注", d.describe().contains("表未声明参考脚"), d.describe());

        // ③ 方向对称：右脚多的一帧就得 < 50%，别把左右写反
        d = run(bits(B0_BAL, B1_REF), 200, tail(88));
        check("88 → 44.0%（<50 = 右脚多）", near(d.balanceLeftPct, 44.0), "got " + d.balanceLeftPct);

        // ④ 0x80 / 0xC8：掩掉 bit7 就会把 64% 读成 0%、100% 读成 36%
        d = run(bits(B0_BAL, B1_REF), 200, tail(0x80));
        check("0x80 → 64.0%（没掩 bit7）", near(d.balanceLeftPct, 64.0), "got " + d.balanceLeftPct);
        d = run(bits(B0_BAL, B1_REF), 200, tail(0xC8));
        check("0xC8 → 100.0%（满量程）", near(d.balanceLeftPct, 100.0), "got " + d.balanceLeftPct);

        // ⑤ 边界 0x00 = 纯右腿踩
        d = run(bits(B0_BAL, B1_REF), 200, tail(0x00));
        check("0x00 → 0.0%", near(d.balanceLeftPct, 0.0) && d.hasBalance, "got " + d.balanceLeftPct);

        // ⑥ 后面挂满别的字段：偏移不能走歪（扭矩2 + 轮4+2 + 曲柄2+2 = 12 字节，再能量2）
        int flags = bits(B0_BAL, B1_REF, B2_TORQUE, B4_WHEEL, B5_CRANK, B11_ENERGY);
        byte[] t = tail(0x70, 0x11, 0x22, 0x01, 0x00, 0x00, 0x00, 0x34, 0x12, 0x05, 0x00, 0x67, 0x00, 0xE8, 0x03);
        d = run(flags, 250, t);
        check("多 flag 叠加：平衡仍 56.0%", near(d.balanceLeftPct, 56.0), "got " + d.balanceLeftPct);
        check("多 flag 叠加：轮/曲柄/能量偏移正确",
                d.hasWheel && d.wheelRevs == 1 && d.wheelEvent == 0x1234
                        && d.hasCrank && d.crankRevs == 5 && d.crankEvent == 0x0067
                        && d.hasEnergy && d.energyKj == 1000,
                "wheel=" + d.wheelRevs + "/" + Integer.toHexString(d.wheelEvent)
                        + " crank=" + d.crankRevs + "/" + d.crankEvent + " energy=" + d.energyKj);

        // ⑦ bit0 没置位：整帧不该有平衡数据
        d = run(bits(B1_REF), 200, tail());
        check("bit0=0 → 无平衡数据", !d.hasBalance && d.balanceLeftPct == -1,
                "has=" + d.hasBalance + " v=" + d.balanceLeftPct);

        // ⑧ 截断帧（只有 flags + 功率）：不崩、不给平衡值
        d = run(bits(B0_BAL, B1_REF), 200, null);
        check("截断帧 → 不崩且无平衡数据", !d.hasBalance && d.balanceLeftPct == -1,
                "has=" + d.hasBalance + " v=" + d.balanceLeftPct);

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
