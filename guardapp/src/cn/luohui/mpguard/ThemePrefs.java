package cn.luohui.mpguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** 应用内明暗主题：默认夜间，偏好与运行记录共用 guard_ui。 */
final class ThemePrefs {

    private static final String PREFS = "guard_ui";
    private static final String KEY_NIGHT = "night_mode";

    private ThemePrefs() {}

    static boolean isNight(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NIGHT, true);
    }

    static void setNight(Context ctx, boolean night) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_NIGHT, night).apply();
    }

    static Context wrap(Context base) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int mode = isNight(base)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        return base.createConfigurationContext(cfg);
    }
}
