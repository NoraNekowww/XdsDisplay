package com.nz.xdsdisplay;

import android.content.Context;

import java.io.File;

/**
 * 离线地图下载：把某个区域若干个缩放级别的瓦片抓到本地 offline/ 目录，
 * 出门没网也能出图（TileCache 读瓦片时会优先读离线包）。
 *
 * 两个注意点：
 *  1. 瓦片源是 GCJ-02 的（高德）时，要用偏移后的坐标算瓦片号，否则下的图和轨迹错位；
 *  2. 每张之间歇一下（默认 120ms），别把公共瓦片服务打爆 —— 也避免被封。
 */
public class OfflineTiles {

    private static final String TAG = "OFFLINE";

    /** 两张瓦片之间的间隔，礼貌些 */
    private static final long GAP_MS = 120L;
    /** 单次任务上限，防止手滑下了几万张 */
    private static final int MAX_TILES = 4000;

    public interface Listener {
        void onProgress(int done, int total, int failed);

        void onDone(int done, int total, int failed, boolean cancelled);
    }

    private static volatile boolean running;
    private static volatile boolean cancelRequested;

    public static boolean isRunning() {
        return running;
    }

    public static void cancel() {
        cancelRequested = true;
    }

    /** 离线包里已有多少张 */
    public static int count(Context ctx) {
        File[] fs = TileCache.offlineDirOf(ctx).listFiles();
        return fs == null ? 0 : fs.length;
    }

    /** 清空离线包 */
    public static int clear(Context ctx) {
        File[] fs = TileCache.offlineDirOf(ctx).listFiles();
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                if (f.delete()) n++;
            }
        }
        Logs.add(TAG, "已清除离线地图 " + n + " 张");
        return n;
    }

    /**
     * 开始下载。区域 = 以 (lat, lon) 为中心、radiusKm 为半径的方框。
     */
    public static void start(Context ctx, double lat, double lon, double radiusKm,
                             int zMin, int zMax, Listener listener) {
        if (running) return;
        running = true;
        cancelRequested = false;
        final Context app = ctx.getApplicationContext();

        // 高德那套瓦片是 GCJ-02，得先偏移才和瓦片号对得上
        if (TileCache.tileUsesGcj02()) {
            double[] g = WgsGcj.wgs84ToGcj02(lat, lon);
            lat = g[0];
            lon = g[1];
        }
        final double cLat = lat, cLon = lon;

        new Thread(() -> {
            // 等瓦片源探测出结果再动手 —— 否则会用默认源去下，结果全落到不可达的地址上
            for (int i = 0; i < 40 && !TileCache.probeDone(); i++) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignore) {
                }
            }

            int total = 0;
            for (int z = zMin; z <= zMax; z++) {
                int[] r = tileRange(cLat, cLon, radiusKm, z);
                total += (r[1] - r[0] + 1) * (r[3] - r[2] + 1);
            }
            if (total > MAX_TILES) total = MAX_TILES;   // 只下这么多就收手

            int done = 0, failed = 0;
            Logs.add(TAG, String.format(java.util.Locale.CHINA,
                    "开始下载离线地图：半径 %.0fkm，z%d-%d，约 %d 张（源：%s）",
                    radiusKm, zMin, zMax, total, TileCache.sourceName()));

            outer:
            for (int z = zMin; z <= zMax; z++) {
                int[] r = tileRange(cLat, cLon, radiusKm, z);
                for (int x = r[0]; x <= r[1]; x++) {
                    for (int y = r[2]; y <= r[3]; y++) {
                        if (cancelRequested || done >= MAX_TILES) break outer;
                        File out = TileCache.offlineFileFor(app, z, x, y);
                        if (!out.exists() || out.length() == 0) {
                            if (!TileCache.fetchToFile(z, x, y, out)) failed++;
                            try {
                                Thread.sleep(GAP_MS);
                            } catch (InterruptedException ignore) {
                            }
                        }
                        done++;
                        if (done % 10 == 0 || done == total) {
                            if (listener != null) listener.onProgress(done, total, failed);
                        }
                    }
                }
            }

            running = false;
            String msg = String.format(java.util.Locale.CHINA,
                    "离线地图下载%s：完成 %d 张（失败 %d）", cancelRequested ? "已取消" : "完成",
                    done, failed);
            Logs.add(TAG, msg);
            if (listener != null) listener.onDone(done, total, failed, cancelRequested);
        }, "offline-dl").start();
    }

    /** 返回 [x0, x1, y0, y1]，注意 y 是纬度越大越小 */
    private static int[] tileRange(double lat, double lon, double radiusKm, int z) {
        double dLat = radiusKm / 111.0;
        double cos = Math.cos(Math.toRadians(lat));
        if (cos < 0.01) cos = 0.01;
        double dLon = radiusKm / (111.0 * cos);
        int x0 = lon2tile(lon - dLon, z);
        int x1 = lon2tile(lon + dLon, z);
        int y0 = lat2tile(lat + dLat, z);
        int y1 = lat2tile(lat - dLat, z);
        int max = (1 << z) - 1;
        return new int[]{clamp(x0, 0, max), clamp(x1, 0, max), clamp(y0, 0, max), clamp(y1, 0, max)};
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int lon2tile(double lon, int z) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
    }

    private static int lat2tile(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }
}
