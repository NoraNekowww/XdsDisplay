package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenStreetMap 瓦片：内存 LRU + 磁盘缓存 + 后台下载。
 * 磁盘缓存放在 cacheDir/osm/z/x/y.png，出过图的区域之后再看不费流量。
 */
public class TileCache {

    private static final String[] HOSTS = {
            "https://tile.openstreetmap.org/",
            "https://tile.openstreetmap.de/",
    };

    /** 缓存 key 带上瓦片源，换源后不会拿到另一家的旧图 */
    private static String keyPrefix() {
        switch (source) {
            case 0:
                return "carto/";
            case 1:
                return "amap/";
            default:
                return "osm/";
        }
    }

    private final LruCache<String, Bitmap> mem =
            new LruCache<String, Bitmap>(48 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private final File dir;
    /** 离线包目录：下过离线地图的区域，优先从这里读 */
    private final File offDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<String> inflight = Collections.synchronizedSet(new HashSet<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private Runnable onTileLoaded;

    public TileCache(Context ctx) {
        File base = ctx.getExternalCacheDir();
        if (base == null) base = ctx.getCacheDir();
        dir = new File(base, "osm");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        offDir = offlineDirOf(ctx);
    }

    // ================================================================
    //  离线地图（下到 files/offline，不参与 LRU 淘汰）
    // ================================================================

    static File offlineDirOf(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "offline");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 离线瓦片文件名（key 带瓦片源，所以换源后离线包不会串；注意不能有斜杠，否则父目录不存在） */
    static String offlineKey(int z, int x, int y) {
        return keyPrefix().replace('/', '_') + z + "_" + x + "_" + y + ".png";
    }

    static File offlineFileFor(Context ctx, int z, int x, int y) {
        return new File(offlineDirOf(ctx), offlineKey(z, x, y));
    }

    /** 按当前源的候选地址抓一张瓦片存成文件（离线下载用） */
    static boolean fetchToFile(int z, int x, int y, File out) {
        for (String url : candidateUrls(z, x, y)) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "XdsDisplay/1.0 (bike computer)");
                if (c.getResponseCode() != 200) continue;
                try (java.io.InputStream in = c.getInputStream();
                     java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (out.length() > 0) return true;
            } catch (Exception ignore) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        return false;
    }

    public void setOnTileLoaded(Runnable r) {
        this.onTileLoaded = r;
    }

    public boolean networkSeemsDown() {
        return consecutiveFailures.get() >= 6;
    }

    /** 同步取（内存/磁盘命中则立即返回），未命中则排队下载并返回 null。 */
    public Bitmap get(int z, int x, int y) {
        String key = keyPrefix() + z + "/" + x + "/" + y;
        Bitmap b = mem.get(key);
        if (b != null) return b;

        // 离线包优先：下过的区域断网也能出图
        File off = new File(offDir, offlineKey(z, x, y));
        if (off.exists() && off.length() > 0) {
            Bitmap o = BitmapFactory.decodeFile(off.getAbsolutePath());
            if (o != null) {
                mem.put(key, o);
                return o;
            }
        }

        File f = new File(dir, key.replace('/', '_') + ".png");
        if (f.exists() && f.length() > 0) {
            Bitmap disk = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (disk != null) {
                mem.put(key, disk);
                return disk;
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }

        if (!networkSeemsDown() && inflight.add(key)) {
            pool.execute(() -> download(key, z, x, y, f));
        }
        return null;
    }

    /** 瓦片源：0=CARTO 深色（WGS-84）、1=高德（GCJ-02）、2=OSM（WGS-84） */
    private static volatile int source = 0;
    /** 当前源是否需要把 WGS-84 转成 GCJ-02 才能对齐 */
    private static volatile boolean gcj02 = false;
    private static volatile Boolean probed;

    public static boolean tileUsesGcj02() {
        return gcj02;
    }

    public static boolean probeDone() {
        return probed != null;
    }

    /** 瓦片源 id：0=CARTO 1=高德 2=OSM（给界面按语言取名字用） */
    public static int sourceId() {
        return source;
    }

    public static String sourceName() {
        switch (source) {
            case 0:
                return "CARTO dark";
            case 1:
                return "AMap";
            default:
                return "OSM";
        }
    }

    private static boolean httpOk(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 探测一次可用瓦片源：
     *   深色底图（CARTO dark）→ 高德 → OSM，谁通用谁。
     * 关键：只有高德那套是 GCJ-02，需要给 GPS 加偏移，另外两家都是 WGS-84 绝不能加
     * （加了轨迹会整体偏几百米）。
     */
    public static void probeSourceAsync(Runnable onDone) {
        if (probed != null) {
            if (onDone != null) onDone.run();
            return;
        }
        new Thread(() -> {
            if (httpOk("https://a.basemaps.cartocdn.com/dark_all/3/3/1.png", 5000)) {
                source = 0;
                gcj02 = false;
            } else if (httpOk("https://wprd01.is.autonavi.com/appmaptile"
                    + "?lang=zh_cn&size=1&scl=1&style=7&x=3&y=1&z=3", 5000)) {
                source = 1;
                gcj02 = true;
            } else {
                source = 2;
                gcj02 = false;
            }
            probed = true;
            Logs.add("MAP", "瓦片源 → " + sourceName() + (gcj02 ? "（GCJ-02 对齐）" : "（WGS-84，不做偏移）"));
            if (onDone != null) onDone.run();
        }, "tile-probe").start();
    }

    private static String[] candidateUrls(int z, int x, int y) {
        String carto = "https://" + (char) ('a' + Math.abs((z + x + y) % 4))
                + ".basemaps.cartocdn.com/dark_all/" + z + "/" + x + "/" + y + ".png";
        String osm1 = "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
        String osm2 = "https://tile.openstreetmap.de/" + z + "/" + x + "/" + y + ".png";
        if (source == 0) return new String[]{carto, osm1, osm2};
        if (source == 1) {
            int s = 1 + (int) (Math.abs((z * 31L + x * 7L + y) % 4));
            String amap = "https://wprd0" + s + ".is.autonavi.com/appmaptile"
                    + "?lang=zh_cn&size=1&scl=1&style=7&x=" + x + "&y=" + y + "&z=" + z;
            return new String[]{amap, osm1, osm2};
        }
        return new String[]{osm1, osm2};
    }

    private void download(String key, int z, int x, int y, File f) {
        Bitmap bmp = null;
        for (String urlStr : candidateUrls(z, x, y)) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("User-Agent", "XdsDisplay/1.0 (Android cycling computer)");
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (InputStream in = conn.getInputStream()) {
                        bmp = BitmapFactory.decodeStream(in);
                    }
                    if (bmp != null) {
                        consecutiveFailures.set(0);
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                        } catch (Exception ignore) {
                        }
                        break;
                    }
                } else {
                    consecutiveFailures.incrementAndGet();
                }
            } catch (Exception e) {
                consecutiveFailures.incrementAndGet();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        inflight.remove(key);
        if (bmp != null) {
            final Bitmap ready = bmp;
            mem.put(key, ready);
            main.post(() -> {
                if (onTileLoaded != null) onTileLoaded.run();
            });
        }
    }

    public void clearMemory() {
        mem.evictAll();
    }
}
