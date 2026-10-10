package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * 速度卡右侧那条航空 HUD 式的「带状刻度」（竖直滚动带）。
 *
 * <p>刻度带固定在中间一行（= 当前车速）上下滚动，窗口高度固定
 * {@link #SPAN_KMH} km/h，所以带子滚动的速度就是真实加速度 ——
 * 一眼能看出「正在加速 / 正在掉速」，比只看一个数字有信息量。
 *
 * <p>小刻度 {@link #TICK_MINOR}（1km/h）、大刻度 {@link #TICK_MAJOR}
 * （5km/h，带数字）。中心那行用一条高亮横线钉住。
 *
 * <p>没有速度数据（{@link #setSpeed(float)} 传负）时整条压暗，刻度留在原地。
 *
 * <p>带子用时间常数的指数平滑 + 自驱动重绘（α = 1 − e^(−dt/τ)，
 * τ = {@link #TAU_MS}）：render() 只有 5Hz，直接跟着跳会一顿一顿的。
 */
public class SpeedTape extends View {

    /**
     * 可见窗口（km/h）：**10** —— 用户要求「显示更小的速度区间，从而展示速度变化」。
     * 旧版 25 是「离 30 / 40 还有多远」的巡航视野，带子几乎不动；10 把 ±5km/h 拉满整个
     * 带高，脚下加一脚、掉一点速都看得出来（绝对值仍看中间那个大数字，带子负责「变化」）。
     */
    private static final float SPAN_KMH = 10f;
    /** 带子跟手的时间常数（ms） */
    private static final float TAU_MS = 320f;
    private static final float DT_MAX_MS = 500f;
    private static final float TICK_MINOR = 1f;   // 窗口小了，刻度也细一档
    private static final float TICK_MAJOR = 5f;

    private final Paint pSpine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float shown;            // 带子中心对应的速度（平滑后）
    private float target;
    private boolean has;
    private boolean started;
    private long lastMs;

    public SpeedTape(Context c) {
        this(c, null);
    }

    public SpeedTape(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;

        pSpine.setStyle(Paint.Style.FILL);
        pSpine.setColor(UiTheme.muted());
        pSpine.setAlpha(0x40);

        pMinor.setStyle(Paint.Style.FILL);
        pMinor.setColor(UiTheme.c(UiTheme.ACCENT3));
        pMinor.setAlpha(0x4D);

        pMajor.setStyle(Paint.Style.FILL);
        pMajor.setColor(UiTheme.c(UiTheme.ACCENT3));
        pMajor.setAlpha(0xC0);

        pNum.setColor(UiTheme.text());
        pNum.setAlpha(0xB4);
        pNum.setTextSize(d * 9.5f);
        pNum.setTypeface(Typeface.MONOSPACE);
        pNum.setTextAlign(Paint.Align.LEFT);

        pCenter.setStyle(Paint.Style.FILL);
        pCenter.setColor(UiTheme.c(UiTheme.ACCENT3));
        lastMs = System.currentTimeMillis();
    }

    /** 当前车速（km/h）：传负 = 没有速度数据（整条压暗） */
    public void setSpeed(float kmh) {
        if (kmh >= 0f && !Float.isNaN(kmh)) {
            target = kmh;
            has = true;
            if (!started) {           // 第一帧别从 0 滚上去
                shown = kmh;
                started = true;
            }
        } else {
            has = false;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 8) return;

        long now = System.currentTimeMillis();
        float dt = Math.max(0f, Math.min(DT_MAX_MS, now - lastMs));
        lastMs = now;
        if (has) {
            float k = 1f - (float) Math.exp(-dt / TAU_MS);
            shown += (target - shown) * k;
        }

        float d = getResources().getDisplayMetrics().density;
        float midY = h / 2f;
        float pxPerKmh = h / SPAN_KMH;         // 窗口固定：带子滚多快 = 加速度
        float spineW = Math.max(1f, d);
        int alpha = has ? 0xFF : 0x66;         // 没数据就整条压暗

        // 背脊（刻度都从这条线往右长）
        pSpine.setAlpha(has ? 0x40 : 0x22);
        c.drawRect(0, 0, spineW, h, pSpine);

        float v0 = shown - SPAN_KMH / 2f;
        float v1 = shown + SPAN_KMH / 2f;
        float majorLen = w * 0.42f;
        float minorLen = w * 0.18f;

        // 小刻度 → 大刻度（大刻度带数字）
        for (float v = (float) Math.ceil(v0 / TICK_MINOR) * TICK_MINOR; v <= v1; v += TICK_MINOR) {
            if (v < -0.01f) continue;
            float y = midY - (v - shown) * pxPerKmh;
            if (y < 1f || y > h - 1f) continue;
            boolean major = Math.abs(v / TICK_MAJOR - Math.round(v / TICK_MAJOR)) < 0.001f;
            if (major) {
                pMajor.setAlpha(alpha == 0xFF ? 0xC0 : 0x55);
                c.drawRect(spineW, y - d * 0.6f, spineW + majorLen, y + d * 0.6f, pMajor);
                pNum.setAlpha(has ? 0xB4 : 0x5A);
                // 数字压在刻度右边，贴着窗口右缘
                c.drawText(String.format(Locale.US, "%d", Math.round(v)),
                        spineW + majorLen + d * 1.5f, y + d * 3.4f, pNum);
            } else {
                pMinor.setAlpha(alpha == 0xFF ? 0x4D : 0x22);
                c.drawRect(spineW, y - d * 0.5f, spineW + minorLen, y + d * 0.5f, pMinor);
            }
        }

        // 中心高亮行：当前车速钉在这条线上
        pCenter.setAlpha(has ? 0xE6 : 0x55);
        c.drawRect(0, midY - d * 1.1f, w, midY + d * 1.1f, pCenter);

        if (has && Math.abs(target - shown) > 0.05f) postInvalidateOnAnimation();
    }
}
