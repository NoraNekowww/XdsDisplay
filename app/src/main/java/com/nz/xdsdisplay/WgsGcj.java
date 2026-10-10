package com.nz.xdsdisplay;

/**
 * WGS-84 ↔ GCJ-02 坐标转换（国测局偏移）。
 *
 * 为什么需要：手机 GPS 输出的是 WGS-84 原始坐标，但国内地图（高德/腾讯/百度）的瓦片
 * 用的是 GCJ-02（俗称火星坐标）。直接用 WGS-84 去贴国内瓦片，轨迹会整体偏移
 * 100~700 米 —— 看着就是"轨迹不在地图上"。上瓦片之前先把坐标转过去即可。
 *
 * 算法是公开的标准实现（仅在国境内需要加偏移）。
 */
public final class WgsGcj {

    private WgsGcj() {}

    private static final double PI = Math.PI;
    private static final double A = 6378245.0;                  // 克拉索夫斯基椭球长半轴
    private static final double EE = 0.00669342162296594323;    // 第一偏心率平方

    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
