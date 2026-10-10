package com.nz.xdsdisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Locale;

/**
 * 界面配色：允许用 Hex 自定义"背景 / 卡片 / 描边 / 三个主色"。
 *
 * 为什么是静态字段：配色要在**视图构造函数**里就能拿到（ChartView / MapView / GForceView
 * 都是在构造函数里读颜色的），所以先用 {@link #load(Context)} 把值装进静态缓存，
 * 视图直接读 `UiTheme.c(UiTheme.ACCENT)`；改完颜色 update 一下静态值再 recreate() 重建界面。
 *
 * 只存这 6 个角色：文字/标签的灰度由背景亮度**自动推**，免得让人一个个调完还互相看不见。
 */
public final class UiTheme {

    public static final String PREFS = "xdsdisplay";

    public static final int BG = 0, CARD = 1, STROKE = 2, ACCENT = 3, ACCENT2 = 4, ACCENT3 = 5;
    public static final int N = 6;

    public static final String[] KEYS = {
            "ui_bg", "ui_card", "ui_stroke", "ui_accent", "ui_accent2", "ui_accent3"
    };
    /**
     * 默认值 = res/values/colors.xml 里的原配。
     * ACCENT3（速度色）从 #4D9CFF 抬到 #80B9FF：蓝色的视亮度天然比青绿色（ACCENT）低一截，
     * 同样的字号看着就是更糊 —— 抬到和 ACCENT 同一感知亮度，见 liftAccent3()。
     */
    public static final int[] DEFAULTS = {
            0xFF0B0E13, 0xFF151A22, 0xFF2A313C, 0xFF00E5C0, 0xFFFF7A45, 0xFF80B9FF
    };

    private static final int[] cur = DEFAULTS.clone();

    private UiTheme() {}

    /** 进界面之前调一次；之后视图构造函数就能读到正确颜色 */
    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int i = 0; i < N; i++) {
            cur[i] = p.getInt(KEYS[i], DEFAULTS[i]);
        }
        liftAccent3(p);
    }

    /**
     * 速度色（ACCENT3）的**感知亮度**抬到跟功率色（ACCENT）一致。
     *
     * 蓝色要看着和青绿色一样亮，得往白里兑一点（只兑白 = 提亮，色相不变；不会压暗任何东西）。
     * 这是用户提的：太阳底下速度读数比功率读数糊。存在偏好里的旧配色也照样算一遍，
     * 算完写回偏好 —— 否则配色对话框里显示的 Hex 会和屏幕上看到的不一样。
     */
    private static void liftAccent3(SharedPreferences p) {
        double l3 = luminance(cur[ACCENT3]);
        double l1 = luminance(cur[ACCENT]);
        if (l3 >= l1 - 0.005) return;                 // 已经够亮（或用户自己挑的就是亮色）：不动
        double t = (l1 - l3) / (1.0 - l3);
        int c = cur[ACCENT3];
        int r = clamp255((int) Math.round(Color.red(c) + (255 - Color.red(c)) * t));
        int g = clamp255((int) Math.round(Color.green(c) + (255 - Color.green(c)) * t));
        int b = clamp255((int) Math.round(Color.blue(c) + (255 - Color.blue(c)) * t));
        cur[ACCENT3] = Color.rgb(r, g, b);
        p.edit().putInt(KEYS[ACCENT3], cur[ACCENT3]).apply();
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    public static int c(int role) {
        return cur[role];
    }

    public static void set(Context ctx, int role, int argb) {
        cur[role] = argb;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEYS[role], argb).apply();
    }

    public static void reset(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int i = 0; i < N; i++) {
            cur[i] = DEFAULTS[i];
            e.remove(KEYS[i]);
        }
        e.apply();
    }

    public static boolean isDefault() {
        for (int i = 0; i < N; i++) if (cur[i] != DEFAULTS[i]) return false;
        return true;
    }

    /** "#RRGGBB"（不会返回带 alpha 的写法） */
    public static String hex(int argb) {
        return String.format(Locale.US, "#%06X", argb & 0xFFFFFF);
    }

    /** 解析 "#RRGGBB" / "RRGGBB" / "#RGB"，无效返回 null */
    public static Integer parse(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.US);
        if (t.startsWith("#")) t = t.substring(1);
        if (t.startsWith("0X")) t = t.substring(2);
        try {
            if (t.length() == 3) {
                int r = Integer.parseInt(t.substring(0, 1), 16);
                int g = Integer.parseInt(t.substring(1, 2), 16);
                int b = Integer.parseInt(t.substring(2, 3), 16);
                return Color.rgb(r * 17, g * 17, b * 17);
            }
            if (t.length() == 6) return Color.rgb(
                    Integer.parseInt(t.substring(0, 2), 16),
                    Integer.parseInt(t.substring(2, 4), 16),
                    Integer.parseInt(t.substring(4, 6), 16));
            if (t.length() == 8) {      // 带 alpha 的写法也认，alpha 一律按不透明处理
                return Color.rgb(
                        Integer.parseInt(t.substring(2, 4), 16),
                        Integer.parseInt(t.substring(4, 6), 16),
                        Integer.parseInt(t.substring(6, 8), 16));
            }
        } catch (NumberFormatException ignore) {
            // 落下去返回 null
        }
        return null;
    }

    // ---- 由背景亮度推出来的辅助色（标签灰度 / 按钮面） ----

    /** 背景是不是浅色 */
    public static boolean light() {
        return luminance(cur[BG]) > 0.55;
    }

    private static double luminance(int argb) {
        return (0.2126 * Color.red(argb) + 0.7152 * Color.green(argb) + 0.0722 * Color.blue(argb)) / 255.0;
    }

    /** 卡片标签用的灰：背景浅了就用深灰，否则用原来的浅灰 */
    public static int muted() {
        return light() ? 0xFF5A6472 : 0xFF8A94A6;
    }

    /** 数字/正文颜色：背景浅了自动变深，不然白字看不见 */
    public static int text() {
        return light() ? 0xFF12161C : 0xFFE8EDF2;
    }

    /** 按钮底色：比卡片略亮/略暗一点，保证和卡片分得开 */
    public static int buttonFill() {
        return light() ? 0xFFE3E7EE : 0xFF1E242E;
    }
}
