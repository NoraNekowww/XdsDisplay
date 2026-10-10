package com.nz.xdsdisplay;

/**
 * 标准蓝牙骑行功率测量 (0x2A63) 解码。
 *
 * 字段顺序（Bluetooth SIG Cycling Power Measurement）:
 *   flags(2) | 瞬时功率 sint16(2)
 *   [踩踏平衡 uint8]      flag bit0   (0.5% / LSB。字段恒为「参考脚」那一侧的功率占比，
 *                                  参考脚看 flag bit1：1 = 左脚、0 = 表自己没声明。
 *                                  规范枚举里只有 Unknown / Left、没有 Right，所以这个值
 *                                  一律按「左脚占比」用，绝不做 100-x 取反)
 *   [累积扭矩 uint16]     flag bit2   (1/32 Nm)
 *   [轮转数 uint32 + 轮事件时间 uint16] flag bit4  (1/2048 s)
 *   [曲柄转数 uint16 + 曲柄事件时间 uint16] flag bit5 (1/1024 s)
 *   [极限力 sint16×2]     flag bit6   (1/2 N)
 *   [极限扭矩 sint16×2]   flag bit7   (1/32 Nm)
 *   [极限角度 uint12×2]   flag bit8
 *   [上死点角 uint16]     flag bit9   (1 deg)
 *   [下死点角 uint16]     flag bit10
 *   [累积能量 uint16]     flag bit11  (kJ)
 *   [偏移补偿指示 uint8]  flag bit12
 */
public final class CpsParser {

    private CpsParser() {}

    public static final class Data {
        public int powerW;
        /** 左脚功率占比 %（= 平衡字段 / 2） */
        public double balanceLeftPct = -1;
        /** flag bit1 = Pedal Power Balance Reference：1 = 表明确声明参考脚是左脚（值为左脚占比） */
        public boolean balanceRefLeft;
        public boolean hasBalance;

        public int crankRevs = -1;
        public int crankEvent = -1;
        public boolean hasCrank;

        /** 中转机直接带过来的真实踏频（rpm），比曲柄推算及时得多 */
        public int cadenceRpm;
        public boolean hasCadence;

        public int wheelRevs = -1;
        public int wheelEvent = -1;
        public boolean hasWheel;

        public int energyKj = -1;
        public boolean hasEnergy;

        public int flags;
        public int length;
        public long timestampMs;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("功率 ").append(powerW).append("W");
            if (hasBalance) {
                sb.append("  左 ").append(String.format(java.util.Locale.CHINA, "%.1f%%", balanceLeftPct));
                if (!balanceRefLeft) sb.append("(表未声明参考脚)");
            }
            if (hasCrank) sb.append("  曲柄 ").append(crankRevs).append('@').append(crankEvent);
            if (hasWheel) sb.append("  轮 ").append(wheelRevs);
            sb.append(String.format("  flags=0x%04X len=%d", flags, length));
            return sb.toString();
        }
    }

    public static boolean parse(byte[] b, int off, int len, Data out) {
        if (b == null || out == null || len < 4) return false;
        if (off < 0 || off + len > b.length) return false;

        out.timestampMs = System.currentTimeMillis();
        out.length = len;
        out.flags = u16(b, off);

        int i = off + 2;
        final int end = off + len;

        out.powerW = s16(b, i);
        i += 2;

        if ((out.flags & (1 << 0)) != 0) {
            if (i + 1 > end) return true;
            // 平衡字段：uint8，单位 %、分辨率 0.5 → /2 即百分比。字段是「参考脚」的占比，
            // 参考脚由 flag bit1 指明（CSS: 0=Unknown / 1=Left；没有 Right 这个枚举值）。
            // 所以那边条子上「左边这截 = 左脚」永远成立，不需要按 bit1 取反。
            out.balanceLeftPct = (b[i] & 0xFF) / 2.0;
            out.balanceRefLeft = (out.flags & (1 << 1)) != 0;
            out.hasBalance = true;
            i += 1;
        }
        if ((out.flags & (1 << 2)) != 0) {
            if (i + 2 > end) return true;
            i += 2;   // 累积扭矩
        }
        if ((out.flags & (1 << 4)) != 0) {
            if (i + 6 > end) return true;
            out.wheelRevs = s32(b, i);
            out.wheelEvent = u16(b, i + 4);
            out.hasWheel = true;
            i += 6;
        }
        if ((out.flags & (1 << 5)) != 0) {
            if (i + 4 > end) return true;
            out.crankRevs = u16(b, i);
            out.crankEvent = u16(b, i + 2);
            out.hasCrank = true;
            i += 4;
        }
        if ((out.flags & (1 << 6)) != 0) {
            if (i + 4 > end) return true;
            i += 4;   // 极限力
        }
        if ((out.flags & (1 << 7)) != 0) {
            if (i + 4 > end) return true;
            i += 4;   // 极限扭矩
        }
        if ((out.flags & (1 << 8)) != 0) {
            if (i + 3 > end) return true;
            i += 3;   // 极限角度
        }
        if ((out.flags & (1 << 9)) != 0) {
            if (i + 2 > end) return true;
            i += 2;   // 上死点角
        }
        if ((out.flags & (1 << 10)) != 0) {
            if (i + 2 > end) return true;
            i += 2;   // 下死点角
        }
        if ((out.flags & (1 << 11)) != 0) {
            if (i + 2 > end) return true;
            out.energyKj = u16(b, i);
            out.hasEnergy = true;
            i += 2;
        }
        // bit12 偏移补偿指示：1 字节，不需要就不读了

        return true;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int s16(byte[] b, int o) {
        return (short) u16(b, o);
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
