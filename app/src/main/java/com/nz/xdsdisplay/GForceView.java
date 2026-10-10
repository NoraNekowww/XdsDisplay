package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * G 力圆盘（赛车游戏那种 "G 球" 表示法）。
 *
 * 输入的 (x, y) 是**屏幕坐标系**下的线性加速度（单位 g，已扣除重力）：
 *   x 正 = 屏幕向右，y 正 = 屏幕向上。
 *
 * 圆点表示的是"人/手机被推的方向"（= 惯性力 = −线性加速度），也就是真实 G 球里
 * 那个小球滚动的方向：刹车 → 圆点向上（前），加速 → 向下（后），右转 → 向左。
 * 中继/车把上把手机横过来装的时候，屏幕向上那一边就是"车头方向"。
 *
 * 圆盘上画**两个点**（任务）：
 *   主点（实心，带大小上色）= 平滑后的值 —— 看趋势、看稳态的加减速/过弯；
 *   副点（半透明小点）      = 这一刻的**原始值**，不参加平滑 —— 看抖动、看瞬时冲击。
 * 平滑按**时间**算（α = 1 − e^(−dt/τ)），所以传感器 50Hz 还是 200Hz 手感一致。
 * （旧版是每帧固定 α=0.22：在 100~200Hz 的机器上时间常数掉到 20~45ms，
 *   等于没平滑 —— 这就是「平滑失效」的原因。）
 *
 * 轴模式（长按切轴向）已经删除：太容易误触，而且装法早就在 pushG() 里按屏幕旋转算好了。
 */
public class GForceView extends View {

    /** 平滑时间常数（s）：0.15s —— 压得住手抖，跟随延时约 0.1s（阶跃 95% 在 0.45s 内）。
     *  旧值 0.35s 拖得太长（用户反馈「延迟有点高」）；这是唯一的手感旋钮，调它即可。
     *  想看「完全没有平滑」的样子，看半透明副点。 */
    private static final float TAU_S = 0.15f;
    /** 单帧 dt 的上下限：传感器偶发丢帧/补帧时别把 α 算飞 */
    private static final float DT_MIN = 0.002f;
    private static final float DT_MAX = 0.20f;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelP = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 实时副点（半透明、不显眼） */
    private final Paint ghostP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 平滑后的"被推方向"（g） */
    private volatile float fx, fy;
    /** 平滑后的竖直线性加速度（g，+ 向上）—— 颠簸/落地看这一路 */
    private volatile float fz;
    /** 原始（未平滑）的"被推方向"（g）—— 副点用 */
    private volatile float rx, ry, rz;
    /** 峰值方向与大小（3 秒窗口） */
    private volatile float px, py, peakMag;
    private volatile long peakMs;
    private volatile float mag;
    /**
     * 本帧是否已经投过重绘。push() 在 400Hz 下每秒会调 400 次 postInvalidateOnAnimation()，
     * 而屏幕只有 ~60 帧/秒 —— 每帧十几个无效的「请重绘」请求要 UI 线程逐个处理
     * （真机 gfxinfo 实测 90 分位帧时长 85ms）。压成每帧一次：样本照旧一个不落地进融合。
     */
    private volatile boolean invalPending;
    /** 平滑用的上一帧时间戳（ns），0 = 还没收到过 */
    private long lastNs;

    /** 满量程（g）——满量程打在半径的 80% 处，留点余量。
     *  0.625g：日常骑行的加减速、刹车、颠簸都能看清；原来的 1.25g 圆点几乎黏在圆心。 */
    private static final float FULL_SCALE = 0.625f;

    public GForceView(Context c) {
        super(c);
        init();
    }

    public GForceView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public GForceView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1.5f);
        grid.setColor(UiTheme.c(UiTheme.STROKE));

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f);
        ring.setColor(UiTheme.muted());

        dotP.setStyle(Paint.Style.FILL);
        dotP.setColor(UiTheme.c(UiTheme.ACCENT));

        trailP.setStyle(Paint.Style.STROKE);
        trailP.setStrokeWidth(2f);
        trailP.setColor(UiTheme.c(UiTheme.ACCENT3));

        textP.setColor(UiTheme.text());
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setFakeBoldText(true);

        labelP.setColor(UiTheme.muted());
        labelP.setTextAlign(Paint.Align.CENTER);
        labelP.setFakeBoldText(true);

        ghostP.setStyle(Paint.Style.FILL);
        ghostP.setColor(UiTheme.muted());
        ghostP.setAlpha(120);                 // 半透明：不抢主点的视觉
    }

    public float mag() {
        return mag;
    }

    /** 由传感器线程调用：线性加速度（g），屏幕坐标系，x 右 / y 上 */
    public void push(float ax, float ay) {
        push(ax, ay, 0f);
    }

    /**
     * 同上，外加竖直分量 az（g，+ 向上）。
     * 竖直分量单独画成圆盘右侧那根竖标尺 —— 这一路才是颠簸/落地/压包的读数。
     */
    public void push(float ax, float ay, float az) {
        // 圆点 = 被推的方向 = −线性加速度
        float tx = -ax, ty = -ay;
        rx = tx;
        ry = ty;
        rz = az;

        // 按**时间**平滑：α = 1 − e^(−dt/τ)，帧率变化手感不变
        long now = System.nanoTime();
        float dt = (lastNs == 0) ? DT_MIN : (now - lastNs) / 1e9f;
        lastNs = now;
        if (dt < DT_MIN) dt = DT_MIN;
        if (dt > DT_MAX) dt = DT_MAX;
        float a = 1f - (float) Math.exp(-dt / TAU_S);

        fx += (tx - fx) * a;
        fy += (ty - fy) * a;   // y 正 = 屏幕向上
        fz += (az - fz) * a;   // 竖标尺：直接显示竖直加速度（+ 向上）

        float m = (float) Math.hypot(fx, fy);
        mag = m;

        long tMs = System.currentTimeMillis();
        if (m >= peakMag || tMs - peakMs > 3000) {
            peakMag = m;
            px = fx;
            py = fy;
            peakMs = tMs;
        } else {
            peakMag *= 0.998f;
            if (peakMag < m) peakMag = m;
        }
        // 一帧只投一次重绘（见 invalPending 注释）：400Hz 的样本挨个投会把 UI 线程淹掉。
        if (!invalPending) {
            invalPending = true;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        invalPending = false;      // 本帧开始画了，允许下一帧再投一次
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float rad = Math.min(w, h) / 2f - 6f;
        if (rad < 12) return;

        // 底圈
        c.drawCircle(cx, cy, rad, ring);
        // 刻度圈 = 满量程的 1/4、2/4、3/4、4/4（与 FULL_SCALE 联动，改量程刻度自动跟着变）
        for (int i = 1; i <= 4; i++) {
            c.drawCircle(cx, cy, rad * (i / 4f) * 0.8f, grid);
        }
        // 十字（前后左右轴）
        c.drawLine(cx - rad, cy, cx + rad, cy, grid);
        c.drawLine(cx, cy - rad, cx, cy + rad, grid);

        // 峰值方向（小圈）
        if (peakMag > 0.02f) {
            float pkx = cx + clamp(px) * rad * 0.8f / FULL_SCALE;
            float pky = cy - clamp(py) * rad * 0.8f / FULL_SCALE;
            trailP.setAlpha(150);
            c.drawCircle(pkx, pky, rad * 0.10f, trailP);
            trailP.setAlpha(255);
        }

        // 实时副点：先画（在主点底下），半透明小点 + 圆心连线（更淡）
        float gdx = clamp(rx) * rad * 0.8f / FULL_SCALE;
        float gdy = clamp(ry) * rad * 0.8f / FULL_SCALE;
        float rpx = cx + gdx, rpy = cy - gdy;
        ghostP.setAlpha(45);
        c.drawLine(cx, cy, rpx, rpy, ghostP);
        ghostP.setAlpha(120);
        c.drawCircle(rpx, rpy, Math.max(rad * 0.045f, 2.5f), ghostP);

        // 主点（平滑值）：圆心 → 圆点连线 + 圆点
        float dx = clamp(fx) * rad * 0.8f / FULL_SCALE;
        float dy = clamp(fy) * rad * 0.8f / FULL_SCALE;
        float px2 = cx + dx, py2 = cy - dy;
        trailP.setAlpha(110);
        c.drawLine(cx, cy, px2, py2, trailP);
        trailP.setAlpha(255);

        float dotR = Math.max(rad * 0.09f, 4f);
        int color = magColor(mag);

        dotP.setColor(color);
        c.drawCircle(px2, py2, dotR, dotP);
        c.drawCircle(px2, py2, dotR * 0.45f, textP);

        // 方向标注（F/B/L/R，贴在内圈，别和中间的数值/峰值打架）
        labelP.setTextSize(Math.max(rad * 0.15f, 8f));
        float ls = labelP.getTextSize();
        c.drawText("F", cx, cy - rad + ls, labelP);
        c.drawText("B", cx, cy + rad - ls * 0.25f, labelP);
        c.drawText("L", cx - rad + ls * 0.8f, cy + ls * 0.35f, labelP);
        c.drawText("R", cx + rad - ls * 0.8f, cy + ls * 0.35f, labelP);

        // 中间数值：半透明大字（不抢圆点和圆环的视觉）
        textP.setTextSize(Math.max(rad * 0.30f, 11f));
        textP.setAlpha(135);
        c.drawText(String.format(java.util.Locale.US, "%.2fG", mag),
                cx, cy + textP.getTextSize() * 0.35f, textP);
        textP.setAlpha(255);

        // 竖直分量：圆盘右侧一根竖标尺（中位 = 0，向上为 +）+ 顶部数值。
        // 水平那两路（前后/左右）静止时应该是 0；竖直这一路平地骑行才会看到颠簸的上下抖动。
        float gx = cx + rad * 0.62f;
        grid.setStrokeWidth(2.5f);
        c.drawLine(gx, cy - rad * 0.60f, gx, cy + rad * 0.60f, grid);
        c.drawLine(gx - rad * 0.07f, cy, gx + rad * 0.07f, cy, grid);     // 中位刻
        grid.setStrokeWidth(1.5f);

        // 实时竖直分量（副刻度，淡）
        float zr = clamp(rz);
        ghostP.setAlpha(110);
        c.drawLine(gx - rad * 0.08f, cy - zr * rad * 0.60f / FULL_SCALE,
                gx + rad * 0.08f, cy - zr * rad * 0.60f / FULL_SCALE, ghostP);

        float zc = clamp(fz);
        float zy = cy - zc * rad * 0.60f / FULL_SCALE;
        trailP.setStrokeWidth(Math.max(rad * 0.055f, 2.5f));
        trailP.setColor(magColor(zc));
        c.drawLine(gx - rad * 0.11f, zy, gx + rad * 0.11f, zy, trailP);
        trailP.setStrokeWidth(2f);
        trailP.setColor(UiTheme.c(UiTheme.ACCENT3));

        textP.setTextSize(Math.max(rad * 0.16f, 8f));
        textP.setColor(UiTheme.muted());
        c.drawText(String.format(java.util.Locale.US, "Z %+.2fG", fz),
                cx, cy - rad * 0.60f, textP);
        textP.setColor(UiTheme.text());

        // 峰值：放到数值**上面**，不压底下的圆环
        textP.setTextSize(Math.max(rad * 0.16f, 8f));
        textP.setColor(UiTheme.muted());
        c.drawText(String.format(java.util.Locale.US, "PEAK %.2fG", peakMag),
                cx, cy - rad * 0.32f, textP);
        textP.setColor(UiTheme.text());
    }

    /** 按大小上色：静 → 灰、轻 → 主色、中 → 警告、大 → 警示红 */
    private int magColor(float v) {
        float a = Math.abs(v);
        if (a < 0.05f) return UiTheme.muted();
        if (a < 0.25f) return UiTheme.c(UiTheme.ACCENT);
        if (a < 0.5f) return getResources().getColor(R.color.warn, null);
        return getResources().getColor(R.color.err, null);
    }

    private static float clamp(float v) {
        if (v > FULL_SCALE) return FULL_SCALE;
        if (v < -FULL_SCALE) return -FULL_SCALE;
        return v;
    }
}
