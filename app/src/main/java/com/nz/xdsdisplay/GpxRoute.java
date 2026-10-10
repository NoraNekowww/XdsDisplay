package com.nz.xdsdisplay;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GPX 路书：把选中的 .gpx 读成一条轨迹，算总距离。
 *
 * 实测踩过的坑（行者 / XDS 码表导出的文件都踩过，别再简化回去）：
 *  1. **不能对空元素调 nextText()** —— 很多导出文件里有 `<name></name>` 或 `<name/>`，
 *     nextText() 会直接抛 XmlPullParserException，整个导入就废了。所以名字是自己收 TEXT。
 *  2. **不能只认 "trkpt"** —— 有的导出带命名空间前缀（`<gpx:trkpt>`）或干脆用别的标签
 *     （`<coord lat= lon=>`）。现在只要**任何元素带 lat+lon 属性**就当轨迹点。
 *  3. **不一定真是 UTF-8** —— 老设备/行者有时是 GBK(GB18030)，字节流直接喂解析器会报错。
 *     现在先自己按 BOM / 严格 UTF-8 试，失败退 GB18030。
 *  4. XML 彻底坏掉时（有的文件根本不是合法 XML）退化成**文本扫描**：正则抓 lat/lon 属性，
 *     再退一步认 KML 的 `<coordinates>lon,lat</coordinates>`。
 *
 * 选中的文件会拷到私有目录 `filesDir/route.gpx`，下次开机自动加载 —— 因为
 * SAF 给的 content:// 权限只在当次会话有效，直接留着 URI 下次就打不开了。
 */
public class GpxRoute {

    private static final String TAG = "GPX";
    public static final String FILE_NAME = "route.gpx";
    private static final String PREFS = "xdsdisplay";
    private static final String KEY_NAME = "gpx_name";
    /** 画图用的点数上限（路书动辄上万点） */
    private static final int MAX_DRAW_POINTS = 4000;
    /** 解析出来的点数上限，防有人把整个世界的地图塞进来 */
    private static final int MAX_POINTS = 200000;

    /** lat 在前：`lat="39.9" ... lon="116.4"` */
    private static final Pattern P_LAT_LON = Pattern.compile(
            "(?:lat|latitude|lng-lat)\\s*=\\s*[\"']\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[\"'][^>]{0,200}?"
                    + "(?:lon|lng|long|longitude)\\s*=\\s*[\"']\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[\"']",
            Pattern.CASE_INSENSITIVE);
    /** lon 在前 */
    private static final Pattern P_LON_LAT = Pattern.compile(
            "(?:lon|lng|long|longitude)\\s*=\\s*[\"']\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[\"'][^>]{0,200}?"
                    + "(?:lat|latitude)\\s*=\\s*[\"']\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[\"']",
            Pattern.CASE_INSENSITIVE);
    /** KML 风格：<coordinates>lon,lat[,ele] ...</coordinates> */
    private static final Pattern P_KML_BLOCK = Pattern.compile(
            "<(?:gx:)?coordinates[^>]*>(.*?)</(?:gx:)?coordinates>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern P_KML_PT = Pattern.compile(
            "(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)");

    /** WGS-84 的 {lat, lon} */
    public final List<double[]> pts = new ArrayList<>();
    public double distanceM;
    public String name = "";

    /** 真实导入的文件名（给日志/界面兜底用） */
    public String sourceName = "";

    public boolean isEmpty() {
        return pts.size() < 2;
    }

    public int size() {
        return pts.size();
    }

    /** 给界面用的一行标签，比如 "Ride 42.3km" */
    public String label() {
        String n = (name == null || name.isEmpty()) ? "GPX" : name;
        return String.format(Locale.US, "%s %.1fkm", n, distanceM / 1000.0);
    }

    /** 抽稀后的点，给地图画线用 */
    public List<double[]> drawPoints() {
        int n = pts.size();
        if (n <= MAX_DRAW_POINTS) return new ArrayList<>(pts);
        int stride = (n + MAX_DRAW_POINTS - 1) / MAX_DRAW_POINTS;
        List<double[]> out = new ArrayList<>(MAX_DRAW_POINTS + 1);
        for (int i = 0; i < n; i += stride) out.add(pts.get(i));
        if (out.get(out.size() - 1) != pts.get(n - 1)) out.add(pts.get(n - 1));
        return out;
    }

    // ================================================================

    private void add(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
        if (lat == 0.0 && lon == 0.0) return;
        if (pts.size() >= MAX_POINTS) return;
        if (!pts.isEmpty()) {
            double[] last = pts.get(pts.size() - 1);
            if (last[0] == lat && last[1] == lon) return;
            distanceM += RideStats.haversine(last[0], last[1], lat, lon);
        }
        pts.add(new double[]{lat, lon});
    }

    private void reset() {
        pts.clear();
        distanceM = 0;
    }

    // ---- 主入口：字节 → 轨迹 ----

    public static GpxRoute parse(InputStream in, String fallbackName) throws Exception {
        String text = decode(readAll(in));
        GpxRoute r = new GpxRoute();
        String fb = fallbackName == null ? "" : fallbackName.trim();
        r.sourceName = fb;
        r.name = fb;

        // 1) 正经 XML 解析（对空元素、命名空间前缀都忍了）
        try {
            r.parseXml(text);
        } catch (Exception e) {
            Logs.add(TAG, "XML 解析失败，改用文本扫描: " + e);
        }

        // 2) 一个点都没认出来 → 文本扫描（有些"gpx"根本不是合法 XML）
        if (r.pts.size() < 2) {
            r.reset();
            r.name = fb;
            r.scanText(text);
            if (r.pts.size() >= 2) Logs.add(TAG, "文本扫描成功，认出 " + r.pts.size() + " 个点");
        }

        if (r.pts.size() < 2) throw new IllegalStateException("没有轨迹点");
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 按 BOM / 严格 UTF-8 → GB18030 的顺序解码 */
    private static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (Exception e) {
            // 行者 / 老码表常见的是 GBK。GB18030 是它的超集，中文设备上一定有
            Logs.add(TAG, "文件不是 UTF-8，按 GB18030 解码");
            return new String(b, Charset.forName("GB18030"));
        }
    }

    // ---- XML 解析 ----

    private void parseXml(String text) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(text));

        StringBuilder nameBuf = null;
        boolean nameTaken = false;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                // 只要带 lat+lon 就是轨迹点，不管标签叫什么（trkpt / rtept / wpt / coord / 带前缀的）
                Double la = num(attr(p, "lat"), attr(p, "latitude"));
                Double lo = num(attr(p, "lon"), attr(p, "lng"), attr(p, "long"), attr(p, "longitude"));
                if (la != null && lo != null) add(la, lo);

                String t = local(p.getName());
                if (!nameTaken && "name".equals(t) && nameBuf == null) nameBuf = new StringBuilder();
            } else if (ev == XmlPullParser.TEXT) {
                if (nameBuf != null && nameBuf.length() < 120) nameBuf.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                if (nameBuf != null && "name".equals(local(p.getName()))) {
                    String s = nameBuf.toString().trim();
                    if (!s.isEmpty()) {
                        name = s.length() > 40 ? s.substring(0, 40) : s;
                        nameTaken = true;
                    }
                    nameBuf = null;
                }
            }
        }
    }

    /** 属性查找：先按原样找，再扫一遍（兼容 `gpx:lat` 这种带前缀的写法） */
    private static String attr(XmlPullParser p, String want) {
        String v = p.getAttributeValue(null, want);
        if (v != null) return v;
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            int c = n.indexOf(':');
            if (c >= 0) n = n.substring(c + 1);
            if (want.equalsIgnoreCase(n)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String local(String name) {
        int c = name.indexOf(':');
        return c >= 0 ? name.substring(c + 1) : name;
    }

    private static Double num(String... cands) {
        for (String s : cands) {
            if (s == null) continue;
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignore) {
                // 下一个
            }
        }
        return null;
    }

    // ---- 文本扫描兜底 ----

    private void scanText(String text) {
        // KML 的 <coordinates> 里是 lon,lat（顺序和 GPX 相反）
        Matcher mb = P_KML_BLOCK.matcher(text);
        while (mb.find()) {
            String body = mb.group(1);
            Matcher mp = P_KML_PT.matcher(body);
            while (mp.find()) {
                Double lon = num(mp.group(1)), lat = num(mp.group(2));
                if (lat != null && lon != null) add(lat, lon);
            }
        }
        if (pts.size() >= 2) return;

        Matcher m1 = P_LAT_LON.matcher(text);
        while (m1.find()) {
            Double la = num(m1.group(1)), lo = num(m1.group(2));
            if (la != null && lo != null) add(la, lo);
        }
        Matcher m2 = P_LON_LAT.matcher(text);
        while (m2.find()) {
            Double lo = num(m2.group(1)), la = num(m2.group(2));
            if (la != null && lo != null) add(la, lo);
        }
    }

    // ---- 文件 ----

    /** 把用户选的文件拷进私有目录，返回最终用的名字 */
    public static String copyToFiles(Context ctx, Uri src, String displayName) throws Exception {
        File dst = new File(ctx.getFilesDir(), FILE_NAME);
        InputStream in = ctx.getContentResolver().openInputStream(src);
        if (in == null) throw new IllegalStateException("cannot open picked file");
        try (InputStream is = in; FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        }
        String nm = displayName == null ? "" : displayName;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, nm).apply();
        return nm;
    }

    /** 开机加载上次导入的路书；没有返回 null */
    public static GpxRoute loadSaved(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            String nm = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_NAME, "");
            GpxRoute r = parse(in, nm);
            Logs.add(TAG, "已加载路书 " + r.label() + "（" + r.size() + " 点）");
            return r;
        } catch (Exception e) {
            Logs.add(TAG, "路书加载失败: " + e);
            return null;
        }
    }

    public static void clearSaved(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), FILE_NAME).delete();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NAME).apply();
        Logs.add(TAG, "路书已清除");
    }
}
