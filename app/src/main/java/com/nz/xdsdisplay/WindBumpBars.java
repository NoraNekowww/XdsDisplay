package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 风噪 / 颠簸：一个方块里并排的两根<b>竖直</b>长条，顶满整个控件高度。
 *
 * <ul>
 *   <li>左 WIND：风噪指数 0..100（{@link WindNoise#index()}），白色短横 = 峰值；</li>
 *   <li>右 BUMP：道路颠簸指数 0..100（{@link BumpMeter#norm100(float)}）。</li>
 * </ul>
 *
 * 不用大字号：条名（WIND / BUMP）和当前读数都以<b>半透明</b>字直接压在条上，
 * 条内底部 = 条名，条内顶部 = 读数。整块就是两根条，横竖屏同一套画法，
 * 字号按条宽/控件高取小值自适应。
 */
public class WindBumpBars extends View {

    private static final int OVERLAY_ALPHA = 150;   // 压在条上的半透明标注

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private volatile float wind, windPeak, bump;
    private String windTxt = "--", bumpTxt = "--";
    private String capWind = "WIND", capBump = "BUMP";

    public WindBumpBars(Context c) { super(c); init(); }
    public WindBumpBars(Context c, AttributeSet a) { super(c, a); init(); }
    public WindBumpBars(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        // 轨道比卡片底略亮一档，再加一圈细边 —— 空条也能看出它顶满整块高度
        track.setColor(mix(UiTheme.buttonFill(), UiTheme.text(), 0.10f));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.6f * getResources().getDisplayMetrics().density);
        edge.setColor((UiTheme.text() & 0x00FFFFFF) | (70 << 24));
        mark.setColor(overlay());
        mark.setStrokeWidth(2.5f);
        txt.setTextAlign(Paint.Align.CENTER);
        try {
            capWind = getResources().getString(R.string.bar_wind);
            capBump = getResources().getString(R.string.bar_bump);
        } catch (Exception ignored) {
        }
    }

    /** 左条：风噪指数 0..100 + 峰值 0..100 + 条内顶部读数文字 */
    public void setWind(float level, float peak, String valueText) {
        wind = clamp(level);
        windPeak = clamp(peak);
        windTxt = valueText;
        postInvalidateOnAnimation();
    }

    /** 右条：颠簸指数 0..100 + 条内顶部读数文字 */
    public void setBump(float level, String valueText) {
        bump = clamp(level);
        bumpTxt = valueText;
        postInvalidateOnAnimation();
    }

    private static float clamp(float v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }

    /** 两个 ARGB 按 f（0..1）线性混合 */
    private static int mix(int a, int b, float f) {
        int r = (int) (android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * f);
        int g = (int) (android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * f);
        int bl = (int) (android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float sd = getResources().getDisplayMetrics().scaledDensity;

        float gap = Math.max(4 * sd, w * 0.10f);
        float bw = (w - gap) / 2f;
        float rad = Math.min(bw * 0.45f, 6 * sd);
        float top = 1f, bot = h - 1f;              // 条顶满整个控件高度

        bar(c, 0f, bw, top, bot, wind, windPeak, windColor(wind), rad);
        bar(c, bw + gap, bw, top, bot, bump, -1f, bumpColor(bump), rad);

        // 半透明标注直接压在条上：底部=条名，顶部=当前读数
        float ts = Math.max(8 * sd, Math.min(12 * sd, Math.min(bw * 0.34f, h * 0.16f)));
        float pad = ts * 0.85f;
        int tone = overlay();
        float cx0 = bw / 2f, cx1 = bw + gap + bw / 2f;
        float maxW = bw * 0.94f;                   // 标注不超过条宽（「BUMP/15」这种长的自动缩字号）
        drawText(c, cx0, h - pad - ts * 0.5f, ts, capWind, tone, maxW);
        drawText(c, cx1, h - pad - ts * 0.5f, ts, capBump, tone, maxW);
        drawText(c, cx0, pad + ts * 0.5f, ts, windTxt, tone, maxW);
        drawText(c, cx1, pad + ts * 0.5f, ts, bumpTxt, tone, maxW);
    }

    /** 一根竖条：轨道 + 自下而上的填充 + 峰值横线 */
    private void bar(Canvas c, float x, float w, float top, float bot,
                     float level, float peak, int color, float rad) {
        r.set(x, top, x + w, bot);
        c.drawRoundRect(r, rad, rad, track);
        c.drawRoundRect(r, rad, rad, edge);

        float hgt = bot - top;
        float fh = hgt * level / 100f;
        if (fh > 1f) {
            r.set(x, bot - Math.max(fh, rad), x + w, bot);
            fill.setColor(color);
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (peak > 2f) {
            float y = bot - hgt * peak / 100f;
            if (y < top + 1f) y = top + 1f;
            if (y > bot - 1f) y = bot - 1f;
            c.drawLine(x + rad, y, x + w - rad, y, mark);
        }
    }

    /** 以 cy 为垂直中心画一行字；maxW &gt; 0 时超过它就自动缩字号（窄条里的长标注） */
    private void drawText(Canvas c, float cx, float cy, float ts, String s, int color, float maxW) {
        txt.setTextSize(ts);
        txt.setColor(color);
        if (maxW > 0) {
            float tw = txt.measureText(s);
            if (tw > maxW) {
                txt.setTextSize(ts * maxW / tw);
            }
        }
        Paint.FontMetrics fm = txt.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, txt);
    }

    /** 压在条上的半透明字色：跟主题文字同色、压低不透明度 */
    private int overlay() {
        return (UiTheme.text() & 0x00FFFFFF) | (OVERLAY_ALPHA << 24);
    }

    private int windColor(float lv) {
        if (lv < 40) return UiTheme.c(UiTheme.ACCENT);
        if (lv < 70) return getResources().getColor(R.color.warn, null);
        return getResources().getColor(R.color.err, null);
    }

    private int bumpColor(float lv) {
        if (lv < 50) return UiTheme.c(UiTheme.ACCENT2);
        if (lv < 80) return getResources().getColor(R.color.warn, null);
        return getResources().getColor(R.color.err, null);
    }
}
