package com.nz.xdsdisplay;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

/**
 * 数字按「固定格距」排布的 Span：解决读数「同字符数换数值就左右跳」。
 *
 * 背景：主题里大数字默认 monospace（本来就等宽，不会跳）；但内置的 SQUARE 数字字体
 * （Orbitron Black）是**比例宽度**的 —— '1' 比 '0'/'2' 窄。定宽只补到「字符数」相同，
 * 串的像素宽度仍随数值变，右对齐的 TextView 于是每次变数都整体平移。
 *
 * 做法：每个数字都占同宽的格子（格宽 = 当前字号下 '0'..'9' 最宽的那个），空格与 '-'
 * 也占一整格（左补空格的定宽才成立、"---" 占位才与数字同宽），'.' / ':' / 字母仍按各自
 * 宽度。于是同一字段（字符数相同）的串**总宽恒定** → 字号与左右位置都不再跳。
 *
 * 小数位：构造时给 smallFrom，该下标起的字符按 0.55 倍画（速度卡的十分位），格子宽度同步
 * 缩到 0.55 —— 外观和原来的 RelativeSizeSpan 一致，但不再有宽度抖动。
 *
 * 注意：autosize 量宽时会忽略 ReplacementSpan，所以这些读数不能用 autosize 定字号，
 * 见 MainActivity.fitNum()：那边用本类的 measure() 量真实宽度自己挑字号。
 */
public class TabularNums extends ReplacementSpan {

    private static final float SMALL = 0.55f;


    private final int smallFrom;      // 小于 0 = 整串同大小
    private final boolean gridSpace;  // 空格是否也占一格（纯数字字段 true，混文字 false）
    private final Rect r = new Rect();

    // 格宽只跟字号有关（单线程 UI，静态缓存够用）
    private static float cell = 1f;
    private static float dotW = 1f;

    public TabularNums(int smallFrom, boolean gridSpace) {
        this.smallFrom = smallFrom;
        this.gridSpace = gridSpace;
    }

    private static void cells(Paint p) {
        // 每次按当前画笔重算：按字号做静态缓存会在「同一字号、不同画笔/字体」时串味
        float max = 1f;
        for (char c = '0'; c <= '9'; c++) max = Math.max(max, p.measureText(String.valueOf(c)));
        cell = max;
        dotW = Math.max(1f, p.measureText("."));
    }

    private boolean gridded(char c) {
        return (c >= '0' && c <= '9') || c == '-' || (c == ' ' && gridSpace);
    }

    private float widthOf(Paint p, char c, boolean small) {
        float w = gridded(c) ? cell : p.measureText(String.valueOf(c));
        return small ? w * SMALL : w;
    }

    /** 串的真实宽度（含格子）—— 给 fitNum 挑字号用 */
    public float width(Paint p, CharSequence s) {
        cells(p);
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += widthOf(p, s.charAt(i), smallFrom >= 0 && i >= smallFrom);
        }
        return w;
    }

    /** 已套本 Span 的串的真实宽度；没套就退回普通量宽 */
    public static float measure(Paint p, CharSequence s) {
        if (s instanceof Spanned) {
            TabularNums[] sp = ((Spanned) s).getSpans(0, s.length(), TabularNums.class);
            if (sp.length > 0) return sp[0].width(p, s);
        }
        return p.measureText(s.toString());
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        cells(paint);
        if (fm != null) {
            Paint.FontMetrics f = paint.getFontMetrics();
            fm.ascent = (int) Math.floor(f.ascent);
            fm.descent = (int) Math.ceil(f.descent);
            fm.top = (int) Math.floor(f.top);
            fm.bottom = (int) Math.ceil(f.bottom);
            fm.leading = 0;
        }
        float w = 0;
        for (int i = start; i < end; i++) {
            w += widthOf(paint, text.charAt(i), smallFrom >= 0 && i >= smallFrom);
        }
        return (int) Math.ceil(w);
    }

    @Override
    public void draw(Canvas c, CharSequence text, int start, int end, float x,
                     int top, int y, int bottom, Paint paint) {
        cells(paint);
        float size = paint.getTextSize();
        float sx = x;
        for (int i = start; i < end; i++) {
            char ch = text.charAt(i);
            boolean small = smallFrom >= 0 && i >= smallFrom;
            float w = widthOf(paint, ch, small);
            if (ch != ' ') {
                // 窄字（'1'，或缩放后的小数位）在格子里居中对齐，像码表的数码管
                paint.setTextSize(small ? size * SMALL : size);
                String one = String.valueOf(ch);
                paint.getTextBounds(one, 0, 1, r);
                c.drawText(one, sx + (w - r.width()) * 0.5f - r.left, y, paint);
                paint.setTextSize(size);
            }
            sx += w;
        }
    }
}
