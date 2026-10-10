package com.nz.xdsdisplay;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * 中/英界面切换。
 *
 * 用 AppCompat 的 per-app locale：调一次 {@link #setLocale} 之后它自己会重建 Activity，
 * 而且 Android 13 以下是库自己存 SharedPreferences（manifest 里那个
 * AppLocalesMetadataHolderService + autoStoreLocales 就是干这个的），重启 App 也记得。
 */
public final class Lang {

    private Lang() {}

    /** 当前界面是不是中文 */
    public static boolean isChinese(Context ctx) {
        LocaleListCompat l = AppCompatDelegate.getApplicationLocales();
        String tag = l.isEmpty() ? null : l.toLanguageTags();
        if (tag == null || tag.isEmpty()) {
            // 没手动设过 → 跟系统语言走
            return "zh".equals(java.util.Locale.getDefault().getLanguage());
        }
        return tag.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }

    public static void setLocale(Context ctx, String languageTag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag));
    }
}
