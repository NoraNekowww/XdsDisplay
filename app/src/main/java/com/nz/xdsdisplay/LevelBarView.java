package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 风噪电平条：0..100 的横向条 + 峰值刻度。
 * 颜色分段直观指示"风阻"大小：低=青(顺风/低速) → 中=黄 → 高=红。
 */
public class LevelBarView extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private volatile float value;      // 0..100
    private volatile float peakValue;  // 0..100

    public LevelBarView(Context c) { super(c); init(); }
    public LevelBarView(Context c, AttributeSet a) { super(c, a); init(); }
    public LevelBarView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        bg.setColor(UiTheme.buttonFill());
        peak.setColor(UiTheme.text());
        peak.setStrokeWidth(2.5f);
    }

    public void setLevel(float v, float peakV) {
        value = clamp(v);
        peakValue = clamp(peakV);
        postInvalidateOnAnimation();
    }

    private static float clamp(float v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float rad = h / 2f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, bg);

        int color = value < 40 ? UiTheme.c(UiTheme.ACCENT)
                : (value < 70 ? getResources().getColor(R.color.warn, null)
                : getResources().getColor(R.color.err, null));

        fill.setColor(color);
        float fw = w * value / 100f;
        if (fw > 1f) {
            r.set(0, 0, Math.max(fw, h), h);
            c.drawRoundRect(r, rad, rad, fill);
        }

        if (peakValue > 2f) {
            float x = Math.min(w - 1.5f, Math.max(1.5f, w * peakValue / 100f));
            c.drawLine(x, 1f, x, h - 1f, peak);
        }
    }
}
