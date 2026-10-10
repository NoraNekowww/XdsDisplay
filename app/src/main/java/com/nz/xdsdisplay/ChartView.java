package com.nz.xdsdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * 功率 / 速度折线图。
 *
 * 功率画成填充面积（左轴），速度画成折线（右轴），各自按窗口内的最大值自适应量程，
 * 纵轴刻度取整，这样曲线不会因为量程一直变而乱跳。
 * 数据由界面每秒钟 push 一次，默认保留最近 3 分钟。
 */
public class ChartView extends View {

    /** 保留的采样点数（1 秒 1 点 → 3 分钟） */
    private static final int CAP = 180;

    private final float[] powerArr = new float[CAP];
    private final float[] speedArr = new float[CAP];
    private final float[] cadArr = new float[CAP];
    private int count;
    private int head;              // 下一个写入位置

    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPowLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpdLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCadLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path pathPow = new Path();
    private final Path pathFill = new Path();
    private final Path pathSpd = new Path();
    private final Path pathCad = new Path();
    private final RectF box = new RectF();

    private float powerMax = 100f;
    private float speedMax = 10f;
    private float cadMax = 120f;

    public ChartView(Context c) {
        this(c, null);
    }

    public ChartView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;

        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(Math.max(1f, d * 0.6f));
        pGrid.setColor(0x33FFFFFF);

        pPowLine.setStyle(Paint.Style.STROKE);
        pPowLine.setStrokeWidth(Math.max(2f, d * 1.4f));
        pPowLine.setColor(UiTheme.c(UiTheme.ACCENT));
        pPowLine.setStrokeJoin(Paint.Join.ROUND);
        pPowLine.setStrokeCap(Paint.Cap.ROUND);

        pPowFill.setStyle(Paint.Style.FILL);
        pPowFill.setColor(UiTheme.c(UiTheme.ACCENT));
        pPowFill.setAlpha(56);

        pSpdLine.setStyle(Paint.Style.STROKE);
        pSpdLine.setStrokeWidth(Math.max(2f, d * 1.4f));
        pSpdLine.setColor(UiTheme.c(UiTheme.ACCENT3));
        pSpdLine.setStrokeJoin(Paint.Join.ROUND);
        pSpdLine.setStrokeCap(Paint.Cap.ROUND);

        pCadLine.setStyle(Paint.Style.STROKE);
        pCadLine.setStrokeWidth(Math.max(2f, d * 1.4f));
        pCadLine.setColor(UiTheme.c(UiTheme.ACCENT2));
        pCadLine.setStrokeJoin(Paint.Join.ROUND);
        pCadLine.setStrokeCap(Paint.Cap.ROUND);

        pText.setColor(UiTheme.text());
        pText.setTextSize(d * 14f);
        pText.setFakeBoldText(true);
        pText.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /** 每秒钟喂一个点 */
    public void push(float powerW, float speedKmh, float cadenceRpm, boolean hasData) {
        if (!hasData) return;
        powerArr[head] = Math.max(0f, powerW);
        speedArr[head] = Math.max(0f, speedKmh);
        cadArr[head] = Math.max(0f, cadenceRpm);
        head = (head + 1) % CAP;
        if (count < CAP) count++;
        recalcScale();
        postInvalidateOnAnimation();
    }

    public void clear() {
        count = 0;
        head = 0;
        powerMax = 100f;
        speedMax = 10f;
        cadMax = 120f;
        recalcScale();
        postInvalidateOnAnimation();
    }

    private void recalcScale() {
        float pm = 0f, sm = 0f, cm = 0f;
        for (int i = 0; i < count; i++) {
            int idx = (head - count + i + CAP * 2) % CAP;
            if (powerArr[idx] > pm) pm = powerArr[idx];
            if (speedArr[idx] > sm) sm = speedArr[idx];
            if (cadArr[idx] > cm) cm = cadArr[idx];
        }
        // 纵轴取整：功率 50W、速度 5km/h、踏频 10rpm 向上取整，避免量程抖动
        powerMax = Math.max(100f, (float) (Math.ceil(pm / 50f) * 50f));
        speedMax = Math.max(10f, (float) (Math.ceil(sm / 5f) * 5f));
        cadMax = Math.max(120f, (float) (Math.ceil(cm / 10f) * 10f));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = getResources().getDisplayMetrics().density * 4f;
        float padR = getResources().getDisplayMetrics().density * 4f;
        float padTop = getResources().getDisplayMetrics().density * 18f;   // 给标题留一行
        float padBottom = getResources().getDisplayMetrics().density * 12f;

        box.set(padL, padTop, w - padR, h - padBottom);
        float plotW = box.width();
        float plotH = box.height();
        if (plotW <= 4 || plotH <= 4) return;

        // 网格：横向 3 条
        for (int i = 0; i <= 3; i++) {
            float y = box.top + plotH * i / 3f;
            c.drawLine(box.left, y, box.right, y, pGrid);
        }

        if (count >= 2) {
            pathPow.reset();
            pathSpd.reset();
            pathCad.reset();
            float step = plotW / Math.max(1, count - 1);
            for (int i = 0; i < count; i++) {
                int idx = (head - count + i + CAP * 2) % CAP;
                float x = box.left + step * i;
                float yp = box.bottom - plotH * Math.min(1f, powerArr[idx] / powerMax);
                float ys = box.bottom - plotH * Math.min(1f, speedArr[idx] / speedMax);
                float yc = box.bottom - plotH * Math.min(1f, cadArr[idx] / cadMax);
                if (i == 0) {
                    pathPow.moveTo(x, yp);
                    pathSpd.moveTo(x, ys);
                    pathCad.moveTo(x, yc);
                } else {
                    pathPow.lineTo(x, yp);
                    pathSpd.lineTo(x, ys);
                    pathCad.lineTo(x, yc);
                }
            }
            // 功率填充
            pathFill.set(pathPow);
            pathFill.lineTo(box.left + step * (count - 1), box.bottom);
            pathFill.lineTo(box.left, box.bottom);
            pathFill.close();
            c.drawPath(pathFill, pPowFill);
            c.drawPath(pathPow, pPowLine);
            c.drawPath(pathCad, pCadLine);
            c.drawPath(pathSpd, pSpdLine);
        }

        // 图例：三组 = 曲线名 + 满量程，颜色跟三条曲线的画笔一致（功率青 / 踏频橙 / 速度蓝）。
        // 当前值不在这里写了 —— 左边卡片的大数字上已经有了，写两遍反而和图上的读数打架。
        // 满量程就是纵轴当前的上限（recalcScale 取整后的值）：曲线高度得靠它才读得出来。
        // 窄屏放不下带单位的写法就退成只留数字（按画布宽度自适应降级）。
        String[] withUnit = {
                getContext().getString(R.string.chart_pwr_fmt, Math.round(powerMax)),
                getContext().getString(R.string.chart_cad_fmt, Math.round(cadMax)),
                getContext().getString(R.string.chart_spd_fmt, Math.round(speedMax))};
        String[] noUnit = {
                getContext().getString(R.string.chart_fs_short_fmt, Math.round(powerMax)),
                getContext().getString(R.string.chart_fs_short_fmt, Math.round(cadMax)),
                getContext().getString(R.string.chart_fs_short_fmt, Math.round(speedMax))};

        float d2 = getResources().getDisplayMetrics().density;
        float yTxt = d2 * 13f;
        float gap = d2 * 10f;
        float avail = box.width() - d2 * 12f;

        String[] txt = withUnit;
        for (int mode = 1; mode >= 0; mode--) {
            txt = (mode == 1) ? withUnit : noUnit;
            if (legendWidth(txt, gap) <= avail || mode == 0) break;
        }

        float x = box.left + d2 * 6f;
        x = drawLegendGroup(c, x, yTxt, UiTheme.c(UiTheme.ACCENT), txt[0]);
        x = drawLegendGroup(c, x + gap, yTxt, UiTheme.c(UiTheme.ACCENT2), txt[1]);
        drawLegendGroup(c, box.right - d2 * 6f - pText.measureText(txt[2]), yTxt,
                UiTheme.c(UiTheme.ACCENT3), txt[2]);
    }

    /** 一行三组图例加起来要多宽（组间留 gap） */
    private float legendWidth(String[] txt, float gap) {
        return pText.measureText(txt[0]) + gap + pText.measureText(txt[1]) + gap
                + pText.measureText(txt[2]);
    }

    /** 画一组图例（曲线名 + 满量程），返回画完后的右端 x */
    private float drawLegendGroup(Canvas c, float x, float y, int color, String txt) {
        pText.setColor(color);
        c.drawText(txt, x, y, pText);
        return x + pText.measureText(txt);
    }
}
