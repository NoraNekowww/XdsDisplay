package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Power 卡片下边缘那条细长的「左右平衡」条（左右脚功率占比）。
 *
 * <p>整条宽度 = 100% 功率，中间 50% 处有一道缝：缝左边是左脚、右边是右脚，
 * 两段各用一个颜色，扫一眼就知道自己是不是一边使不上劲。
 *
 * <p>数据来自 CPS（功率计）里的 Pedal Power Balance 字段
 * （{@link CpsParser.Data#balanceLeftPct}）。单边功率计 / 没连表时没有这个字段，
 * 这时只画一条灰轨道 —— 保持卡片高度不变，不会让大数字跳来跳去。
 *
 * <p>读数每 2% 一跳（编码精度 0.5%），而 render() 每 200ms 才喂一次，
 * 所以这里做时间常数的指数平滑（α = 1 − e^(−dt/τ)，τ = {@link #TAU_MS}），
 * 别用「每帧固定 α」—— 那样刷新率一变平滑力度就变了。
 */
public class BalanceStrip extends View {

    /** 平滑时间常数（ms）：骑车时左右平衡是慢变化的，0.7s 足够稳又跟得上 */
    private static final float TAU_MS = 700f;
    /** 单次最多按多少 ms 计（App 切回来时 dt 可能很大，别让一次跳到终点） */
    private static final float DT_MAX_MS = 1000f;

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLeft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();

    private float shown = 50f;      // 平滑后显示的左脚占比（%）
    private float target = 50f;
    private boolean has;            // 有没有平衡数据
    private long lastMs;

    public BalanceStrip(Context c) {
        this(c, null);
    }

    public BalanceStrip(Context c, AttributeSet a) {
        super(c, a);
        pTrack.setStyle(Paint.Style.FILL);
        pTrack.setColor(UiTheme.muted());
        pTrack.setAlpha(0x3A);

        pLeft.setStyle(Paint.Style.FILL);
        pLeft.setColor(UiTheme.c(UiTheme.ACCENT));
        pLeft.setAlpha(0xE6);

        pRight.setStyle(Paint.Style.FILL);
        pRight.setColor(UiTheme.c(UiTheme.ACCENT3));
        pRight.setAlpha(0xE6);

        // 50% 那道缝：用卡片底色画，两段颜色再像也能看出中点在哪儿
        pMid.setStyle(Paint.Style.FILL);
        pMid.setColor(UiTheme.c(UiTheme.CARD));
        lastMs = System.currentTimeMillis();
    }

    /** 左脚功率占比（%）：0..100；传负数（或 NaN）= 没有平衡数据 */
    public void setBalance(float leftPct) {
        boolean ok = !Float.isNaN(leftPct) && leftPct >= 0f;
        if (ok) {
            target = Math.max(0f, Math.min(100f, leftPct));
            has = true;
        } else {
            has = false;
            target = 50f;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 2 || h <= 0) return;

        long now = System.currentTimeMillis();
        float dt = Math.max(0f, Math.min(DT_MAX_MS, now - lastMs));
        lastMs = now;
        float k = 1f - (float) Math.exp(-dt / TAU_MS);
        shown += (target - shown) * k;

        float rad = h / 2f;
        box.set(0, 0, w, h);
        c.drawRoundRect(box, rad, rad, pTrack);

        if (has) {
            float split = w * (shown / 100f);
            c.save();
            clip.reset();
            clip.addRoundRect(box, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(0, 0, split, h, pLeft);
            c.drawRect(split, 0, w, h, pRight);
            c.restore();
            if (Math.abs(target - shown) > 0.05f) postInvalidateOnAnimation();
        }

        // 50% 参考缝
        float tw = Math.max(1.5f, getResources().getDisplayMetrics().density * 1.2f);
        c.drawRect(w / 2f - tw / 2f, 0, w / 2f + tw / 2f, h, pMid);
    }
}
