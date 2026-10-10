package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 实时轨迹地图：OpenStreetMap 瓦片（Web Mercator）+ 轨迹线 + 当前位置。
 * 支持单指拖动、双指缩放、一键跟随；没网时退化成"网格 + 轨迹"仍然可用。
 */
public class MapView extends View {

    private static final int TILE = 256;
    private static final int MIN_ZOOM = 4;
    private static final int MAX_ZOOM = 19;

    private final TileCache cache;

    private int zoom = 16;
    private double centerLat = Double.NaN;
    private double centerLon = Double.NaN;
    private boolean follow = true;

    private double markerLat = Double.NaN;
    private double markerLon;
    private float accuracyM = -1;

    private List<double[]> track = new ArrayList<>();
    /** 原始 WGS-84 轨迹点（还没投影的）。瓦片源是开机后异步探测出来的，
     *  探测结果出来时得拿它重投一遍，所以原始点必须留着 —— 见 setGcj02()。 */
    private List<double[]> trackWgs = new ArrayList<>();

    /** GPX 路书（已按瓦片源投好影），画在轨迹下面 */
    private List<double[]> route = new ArrayList<>();
    /** 原始 WGS-84 路书点，同上，别丢 */
    private List<double[]> routeWgs = new ArrayList<>();
    private String routeLabel = "";
    /** 视野正"套着"路书（fitRoute 造成的）。重投影后路书会整体平移，
     *  这种情况要再套一次，否则几百米一挪就出屏了 */
    private boolean routeFit;

    /** 蓝点的原始 WGS-84 位置，同样要留着 */
    private double rawLat = Double.NaN;
    private double rawLon;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint voidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scalePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    private final ScaleGestureDetector scaleDetector;
    private float scaleAccum = 1f;
    private float lastX, lastY;
    private boolean userMoved;

    public MapView(Context c) {
        this(c, null);
    }

    public MapView(Context c, AttributeSet a) {
        super(c, a);
        cache = new TileCache(c);
        cache.setOnTileLoaded(this::postInvalidateOnAnimation);

        float d = getResources().getDisplayMetrics().density;

        voidPaint.setStyle(Paint.Style.FILL);
        voidPaint.setColor(getResources().getColor(R.color.map_water, null));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(0x14FFFFFF);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(5f * d);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeJoin(Paint.Join.ROUND);
        trackPaint.setColor(UiTheme.c(UiTheme.ACCENT));

        // 路书：蓝色虚线，跟实时轨迹（青色实线）区分开
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(5f * d);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setColor(UiTheme.c(UiTheme.ACCENT3));
        routePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{16f * d, 9f * d}, 0f));

        haloPaint.setStyle(Paint.Style.FILL);
        haloPaint.setColor(0x334D9CFF);

        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(UiTheme.c(UiTheme.ACCENT3));
        markerPaint.setShadowLayer(6f * d, 0, 2f * d, 0xAA000000);

        markerInnerPaint.setStyle(Paint.Style.FILL);
        markerInnerPaint.setColor(Color.WHITE);

        textPaint.setTextSize(14f * d);
        textPaint.setColor(0xE6FFFFFF);
        textPaint.setShadowLayer(3f * d, 0, 1f * d, 0xFF000000);

        scalePaint.setStyle(Paint.Style.STROKE);
        scalePaint.setStrokeWidth(2f * d);
        scalePaint.setColor(0xE6FFFFFF);
        scalePaint.setShadowLayer(3f * d, 0, 1f * d, 0xFF000000);

        setLayerType(LAYER_TYPE_SOFTWARE, null);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleAccum *= detector.getScaleFactor();
                if (scaleAccum > 1.5f) {
                    setZoom(zoom + 1);
                    scaleAccum = 1f;
                } else if (scaleAccum < 0.66f) {
                    setZoom(zoom - 1);
                    scaleAccum = 1f;
                }
                return true;
            }
        });
    }

    // ================= 对外接口 =================

    /** 瓦片源是 GCJ-02（高德等国内图）时，WGS-84 的 GPS 必须加偏移才能和底图对齐 */
    private boolean gcj02;

    public void setGcj02(boolean on) {
        if (this.gcj02 == on) return;
        this.gcj02 = on;
        // 关键：瓦片源是开机后异步探测的（高德要好几秒才探出来），而本地路书
        // 早就加载并画好了。标志一翻，之前按老坐标系投出来的点全部作废 ——
        // 必须拿原始 WGS-84 点重投，否则就是"路书/蓝点和底图差几百米"。
        int before = route == null ? 0 : route.size();
        track = project(trackWgs);
        route = project(routeWgs);
        if (!Double.isNaN(rawLat)) {
            double[] p = fix(rawLat, rawLon);
            markerLat = p[0];
            markerLon = p[1];
            if (follow) {                 // 跟随中：中心跟着蓝点一起挪
                centerLat = markerLat;
                centerLon = markerLon;
            }
        }
        if (routeFit && hasRoute()) {
            fitRoute();                   // 视野本来套着路书 → 跟着挪过去
        } else if (Double.isNaN(centerLat) && hasRoute()) {
            // 还没有定位：先把路书套进视野，免得整屏都是"等待定位"。
            // follow 要留住 —— 定位一到还得跟着人走（fitRoute 会把它关掉）。
            fitRoute();
            follow = true;
        }
        Logs.add("MAP", String.format(Locale.US,
                "坐标系切换 → %s，路书 %d 点重新投影（首点 %.6f,%.6f）",
                on ? "GCJ-02" : "WGS-84", before,
                route == null || route.isEmpty() ? 0.0 : route.get(0)[0],
                route == null || route.isEmpty() ? 0.0 : route.get(0)[1]));
        invalidate();
    }

    /** WGS-84 →（按需）GCJ-02 */
    private double[] fix(double lat, double lon) {
        if (!gcj02) return new double[]{lat, lon};
        return WgsGcj.wgs84ToGcj02(lat, lon);
    }

    public void setPosition(double lat, double lon, float accuracyMeters) {
        rawLat = lat;                     // 留原始 WGS-84，坐标系变了要重投
        rawLon = lon;
        double[] p = fix(lat, lon);
        this.markerLat = p[0];
        this.markerLon = p[1];
        this.accuracyM = accuracyMeters;
        if (Double.isNaN(centerLat) || follow) {
            centerLat = p[0];
            centerLon = p[1];
        }
        invalidate();
    }

    public void setTrack(List<double[]> points) {
        this.trackWgs = points;           // 存原始 WGS-84（null = 清除）
        this.track = project(points);
        invalidate();
    }

    public void setFollow(boolean follow) {
        this.follow = follow;
        if (follow) {
            routeFit = false;             // 用户/定位接管了视野
            if (!Double.isNaN(markerLat)) {
                centerLat = markerLat;
                centerLon = markerLon;
            }
        }
        invalidate();
    }

    /** 重投一组 WGS-84 点（按当前瓦片源决定要不要加 GCJ 偏移） */
    private List<double[]> reproject(List<double[]> src) {
        List<double[]> out = new ArrayList<>(src.size());
        for (double[] q : src) {
            double[] p = fix(q[0], q[1]);
            out.add(new double[]{p[0], p[1]});
        }
        return out;
    }

    /** 按当前瓦片源投一组点，返回新列表（不碰入参）。null 原样返回 */
    private List<double[]> project(List<double[]> wgs) {
        if (wgs == null) return null;
        if (!gcj02) return new ArrayList<>(wgs);
        return reproject(wgs);
    }

    /** 设置 GPX 路书（WGS-84 点 + 一行标签），传 null / 点数<2 就是清除 */
    public void setRoute(List<double[]> wgsPts, String label) {
        routeLabel = label == null ? "" : label;
        // 传进来的是原始 WGS-84：留一份原样的（瓦片源变了要重投），
        // route 里放按当前坐标系投好的
        routeWgs = (wgsPts == null || wgsPts.size() < 2)
                ? new ArrayList<>() : new ArrayList<>(wgsPts);
        route = project(routeWgs);
        invalidate();
    }

    public boolean hasRoute() {
        return route != null && route.size() >= 2;
    }

    /** 把整条路书套进视野（自动选缩放级别 + 居中），并退出跟随 */
    public void fitRoute() {
        if (!hasRoute()) return;
        int w = getWidth() > 0 ? getWidth() : 1080;
        int h = getHeight() > 0 ? getHeight() : 700;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] p : route) {
            if (p[0] < minLat) minLat = p[0];
            if (p[0] > maxLat) maxLat = p[0];
            if (p[1] < minLon) minLon = p[1];
            if (p[1] > maxLon) maxLon = p[1];
        }
        int best = MIN_ZOOM;
        for (int z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
            double spanX = lonToWorldX(maxLon, z) - lonToWorldX(minLon, z);
            double spanY = latToWorldY(minLat, z) - latToWorldY(maxLat, z);
            if (spanX <= w * 0.85 && spanY <= h * 0.75) {
                best = z;
                break;
            }
        }
        zoom = best;
        centerLat = (minLat + maxLat) / 2;
        centerLon = (minLon + maxLon) / 2;
        follow = false;
        routeFit = true;      // 之后坐标系变了要跟着重新套一次
        invalidate();
    }

    public boolean isFollow() { return follow; }

    public int getZoom() { return zoom; }

    public void setZoom(int z) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        routeFit = false;        // 手动改过缩放，就别再自动套路书了
        invalidate();
    }

    public void zoomIn() { setZoom(zoom + 1); }

    public void zoomOut() { setZoom(zoom - 1); }

    // ================= 投影 =================

    private static double clampLat(double lat) {
        return Math.max(-85.05112878, Math.min(85.05112878, lat));
    }

    private static double lonToWorldX(double lon, int z) {
        return (lon + 180.0) / 360.0 * (TILE << z);
    }

    private static double latToWorldY(double lat, int z) {
        double s = Math.sin(Math.toRadians(clampLat(lat)));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * (TILE << z);
    }

    private static double worldXToLon(double x, int z) {
        return x / (TILE << z) * 360.0 - 180.0;
    }

    private static double worldYToLat(double y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / (TILE << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** 当前纬度下每像素多少米 */
    private double metersPerPixel() {
        return 156543.03392 * Math.cos(Math.toRadians(clampLat(centerLat))) / (1 << zoom);
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth(), h = getHeight();
        canvas.drawColor(UiTheme.c(UiTheme.BG));

        if (Double.isNaN(centerLat)) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(18f * getResources().getDisplayMetrics().density);
            canvas.drawText(getContext().getString(R.string.map_waiting), w / 2f, h / 2f, textPaint);
            return;
        }

        double cx = lonToWorldX(centerLon, zoom);
        double cy = latToWorldY(centerLat, zoom);
        float ox = (float) (w / 2.0 - cx);
        float oy = (float) (h / 2.0 - cy);

        int n = 1 << zoom;
        int minX = (int) Math.floor(-ox / TILE);
        int maxX = (int) Math.floor((w - ox) / TILE);
        int minY = (int) Math.floor(-oy / TILE);
        int maxY = (int) Math.floor((h - oy) / TILE);

        for (int ty = minY; ty <= maxY; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = minX; tx <= maxX; tx++) {
                int wx = ((tx % n) + n) % n;
                float left = ox + tx * TILE;
                float top = oy + ty * TILE;
                Bitmap b = cache.get(zoom, wx, ty);
                if (b != null) {
                    canvas.drawBitmap(b, left, top, tilePaint);
                } else {
                    canvas.drawRect(left, top, left + TILE, top + TILE, voidPaint);
                    canvas.drawRect(left, top, left + TILE, top + TILE, gridPaint);
                }
            }
        }

        // ---- GPX 路书（画在轨迹下面）----
        if (route != null && route.size() >= 2) {
            path.reset();
            boolean rf = true;
            for (double[] pt : route) {
                float sx = (float) (ox + lonToWorldX(pt[1], zoom));
                float sy = (float) (oy + latToWorldY(pt[0], zoom));
                if (rf) {
                    path.moveTo(sx, sy);
                    rf = false;
                } else {
                    path.lineTo(sx, sy);
                }
            }
            canvas.drawPath(path, routePaint);
        }

        // ---- 轨迹 ----
        if (track != null && track.size() >= 2) {
            path.reset();
            boolean first = true;
            for (double[] pt : track) {
                float sx = (float) (ox + lonToWorldX(pt[1], zoom));
                float sy = (float) (oy + latToWorldY(pt[0], zoom));
                if (first) {
                    path.moveTo(sx, sy);
                    first = false;
                } else {
                    path.lineTo(sx, sy);
                }
            }
            canvas.drawPath(path, trackPaint);
        }

        // ---- 当前位置 ----
        if (!Double.isNaN(markerLat)) {
            float mx = (float) (ox + lonToWorldX(markerLon, zoom));
            float my = (float) (oy + latToWorldY(markerLat, zoom));
            float d = getResources().getDisplayMetrics().density;

            if (accuracyM > 0f) {
                float r = (float) (accuracyM / metersPerPixel());
                if (r > 6f * d && r < Math.max(w, h)) {
                    canvas.drawCircle(mx, my, r, haloPaint);
                }
            }
            canvas.drawCircle(mx, my, 9f * d, markerPaint);
            canvas.drawCircle(mx, my, 3.5f * d, markerInnerPaint);
        }

        // ---- 比例尺 ----
        drawScaleBar(canvas, w, h);

        // ---- 左上角信息 ----
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(13f * getResources().getDisplayMetrics().density);
        String tileName;
        switch (TileCache.sourceId()) {
            case 0: tileName = getContext().getString(R.string.tile_carto); break;
            case 1: tileName = getContext().getString(R.string.tile_amap); break;
            default: tileName = getContext().getString(R.string.tile_osm); break;
        }
        String info = tileName + "  z" + zoom
                + "  " + getContext().getString(follow ? R.string.map_follow : R.string.map_free)
                + "  " + getContext().getString(R.string.map_trk_fmt,
                        track == null ? 0 : track.size())
                + (hasRoute() ? "  ·  " + routeLabel : "");
        canvas.drawText(info, 8f * getResources().getDisplayMetrics().density,
                18f * getResources().getDisplayMetrics().density, textPaint);
        if (cache.networkSeemsDown()) {
            canvas.drawText(getContext().getString(R.string.map_tiles_down),
                    8f * getResources().getDisplayMetrics().density,
                    36f * getResources().getDisplayMetrics().density, textPaint);
        }

        // ---- 版权（OSM 要求） ----
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("© OpenStreetMap contributors",
                w - 6f * getResources().getDisplayMetrics().density,
                h - 6f * getResources().getDisplayMetrics().density, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawScaleBar(Canvas canvas, float w, float h) {
        float d = getResources().getDisplayMetrics().density;
        double mpp = metersPerPixel();
        double[] candidates = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000};
        double chosen = -1;
        for (double c : candidates) {
            double px = c / mpp;
            if (px >= 50 * d && px <= 130 * d) {
                chosen = c;
                break;
            }
        }
        if (chosen < 0) chosen = mpp * 80 * d;

        float px = (float) (chosen / mpp);
        float y = h - 20f * d;
        float x0 = 10f * d;
        canvas.drawLine(x0, y, x0 + px, y, scalePaint);
        canvas.drawLine(x0, y - 4f * d, x0, y + 4f * d, scalePaint);
        canvas.drawLine(x0 + px, y - 4f * d, x0 + px, y + 4f * d, scalePaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        String label = chosen >= 1000
                ? String.format(Locale.US, "%.0f km", chosen / 1000.0)
                : String.format(Locale.US, "%.0f m", chosen);
        canvas.drawText(label, x0, y - 8f * d, textPaint);
    }

    // ================= 手势 =================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                userMoved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) > 1.5f || Math.abs(dy) > 1.5f) {
                        userMoved = true;
                        follow = false;
                        panBy(dx, dy);
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;

            default:
                break;
        }
        return true;
    }

    private void panBy(float dxPx, float dyPx) {
        if (Double.isNaN(centerLat)) return;
        routeFit = false;        // 手动拖过地图，视野归用户
        double cx = lonToWorldX(centerLon, zoom) - dxPx;
        double cy = latToWorldY(centerLat, zoom) - dyPx;
        centerLon = worldXToLon(cx, zoom);
        centerLat = worldYToLat(cy, zoom);
        invalidate();
    }
}
