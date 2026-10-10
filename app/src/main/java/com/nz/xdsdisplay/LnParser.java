package com.nz.xdsdisplay;

import java.util.Locale;

/**
 * 标准蓝牙位置与速度 (0x2A67) 解码。
 *
 * 字段顺序（Bluetooth SIG Location and Speed）:
 *   flags(2) | 瞬时速度 uint16(2) | 总里程 uint24(3) | 纬度 sint32 + 经度 sint32(8)
 *   | 海拔 sint32(4) | 航向 uint16(2) | 滚动时间 uint8(1) | UTC 时间(7)
 * flags: bit0 速度 / bit1 里程 / bit2 位置 / bit3 海拔 / bit4 航向 / bit5 滚动时间 / bit6 UTC
 *        bit7-8 定位状态 / bit9-10 速度与距离格式(0=2D) / bit11 海拔来源 / bit12 航向来源
 */
public final class LnParser {

    private LnParser() {}

    public static final class Data {
        public float speedMps = Float.NaN;
        public boolean hasSpeed;

        public double totalDistanceM = -1;

        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public boolean hasLocation;

        public double elevationM = Double.NaN;
        public boolean hasElevation;
        public boolean elevFromBarometer;

        public float heading = -1;
        public boolean hasHeading;

        public int rollingTimeS = -1;

        public long utcMs;
        public boolean hasUtc;

        public int posStatus;
        public int flags;
        public int length;
        public long timestampMs;

        /** 定位质量（中继 NMEA GGA：0=无效 1=单点 2=差分 4=RTK 固定 5=RTK 浮动） */
        public int fixQuality;
        /** 参与定位的卫星数（顶栏 GPS 状态用；偶尔会掉到 0） */
        public int sats;

        public boolean positionValid() {
            return hasLocation && (posStatus == 1 || posStatus == 2);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (hasSpeed) sb.append(String.format(Locale.CHINA, "%.2f m/s", speedMps));
            if (hasLocation) sb.append(String.format(Locale.CHINA, "  %.6f,%.6f", lat, lon));
            if (hasElevation) sb.append(String.format(Locale.CHINA, "  %.1fm", elevationM));
            if (hasHeading) sb.append(String.format(Locale.CHINA, "  %.0f°", heading));
            sb.append("  pos=").append(posStatus);
            if (sats > 0) sb.append("  sats=").append(sats).append('/').append(fixQuality);
            return sb.toString();
        }
    }

    public static boolean parse(byte[] b, int off, int len, Data out) {
        if (b == null || out == null || len < 4) return false;
        if (off < 0 || off + len > b.length) return false;

        out.timestampMs = System.currentTimeMillis();
        out.length = len;
        out.flags = u16(b, off);
        out.posStatus = (out.flags >> 7) & 0x03;
        out.elevFromBarometer = ((out.flags >> 11) & 1) == 1;

        boolean format3d = ((out.flags >> 9) & 0x03) == 1;

        int i = off + 2;
        final int end = off + len;

        if ((out.flags & (1 << 0)) != 0) {
            if (i + 2 > end) return true;
            int raw = u16(b, i);
            // 2D: m/s, 1/256 ; 3D: km/h, 1/100
            out.speedMps = format3d ? (raw / 100.0f) / 3.6f : raw / 256.0f;
            out.hasSpeed = true;
            i += 2;
        }
        if ((out.flags & (1 << 1)) != 0) {
            if (i + 3 > end) return true;
            // 2D: m, 1/10
            int d = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16);
            out.totalDistanceM = d / 10.0;
            i += 3;
        }
        if ((out.flags & (1 << 2)) != 0) {
            if (i + 8 > end) return true;
            out.lat = s32(b, i) / 1e7;
            out.lon = s32(b, i + 4) / 1e7;
            out.hasLocation = true;
            i += 8;
        }
        if ((out.flags & (1 << 3)) != 0) {
            if (i + 4 > end) return true;
            out.elevationM = s32(b, i) / 100.0;
            out.hasElevation = true;
            i += 4;
        }
        if ((out.flags & (1 << 4)) != 0) {
            if (i + 2 > end) return true;
            out.heading = u16(b, i) / 100.0f;
            out.hasHeading = true;
            i += 2;
        }
        if ((out.flags & (1 << 5)) != 0) {
            if (i + 1 > end) return true;
            out.rollingTimeS = b[i] & 0xFF;
            i += 1;
        }
        if ((out.flags & (1 << 6)) != 0) {
            if (i + 7 > end) return true;
            int year = u16(b, i);
            int month = b[i + 2] & 0xFF;
            int day = b[i + 3] & 0xFF;
            int hour = b[i + 4] & 0xFF;
            int min = b[i + 5] & 0xFF;
            int sec = b[i + 6] & 0xFF;
            try {
                java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                c.clear();
                c.set(year, month - 1, day, hour, min, sec);
                out.utcMs = c.getTimeInMillis();
                out.hasUtc = true;
            } catch (Exception ignore) {
            }
            i += 7;
        }

        return true;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
