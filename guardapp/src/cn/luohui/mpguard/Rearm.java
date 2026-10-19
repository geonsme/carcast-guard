package cn.luohui.mpguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/** 自愈逻辑：把被系统清掉的无障碍开关与条目写回。 */
public class Rearm {
    private static final String TAG = "MpGuard";

    /** 需要保证处于启用状态的无障碍服务（含车机投屏宝自己的那条）。 */
    public static final String[] WANTED = {
            "com.sven.mycar/com.sven.mycar.phone.control.MyCarControlService",
            "cn.luohui.mpguard/cn.luohui.mpguard.GuardService"
    };
    public static final String SELF = WANTED[1];

    public static boolean hasWritePerm(Context ctx) {
        return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String readList(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception e) { return null; }
    }

    public static boolean masterOn(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Exception e) { return false; }
    }

    public static boolean contains(String list, String item) {
        if (TextUtils.isEmpty(list)) return false;
        String want = norm(item);
        for (String s : list.split(":")) if (norm(s).equals(want)) return true;
        return false;
    }

    /** 用户是否主动停用了守护。所有自愈入口都必须先看这个标记。 */
    public static boolean userDisabled(Context ctx) {
        return ctx.getSharedPreferences("guard", Context.MODE_PRIVATE).getBoolean("off", false);
    }

    public static void setUserDisabled(Context ctx, boolean v) {
        ctx.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().putBoolean("off", v).apply();
    }

    /** 归一化组件串：把 "pkg/.Cls" 与 "pkg/pkg.Cls" 统一成后者，用于去重。 */
    public static String norm(String comp) {
        if (comp == null) return "";
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp);
        if (cn == null) return comp.trim().toLowerCase(java.util.Locale.ROOT);
        return (cn.getPackageName() + "/" + cn.getClassName()).toLowerCase(java.util.Locale.ROOT);
    }

    /** @return 补写项数；-1 表示没有 WRITE_SECURE_SETTINGS；-2 表示用户已停用。 */
    public static int rearm(Context ctx) {
        if (userDisabled(ctx)) {
            Log.i(TAG, "用户已停用守护，跳过自愈");
            return -2;
        }
        if (!hasWritePerm(ctx)) {
            Log.w(TAG, "无 WRITE_SECURE_SETTINGS，无法自愈");
            return -1;
        }
        String cur = readList(ctx);
        // 按归一化键去重：系统可能写缩写形式，避免同一服务在列表里出现两遍
        java.util.LinkedHashMap<String, String> uniq =
                new java.util.LinkedHashMap<String, String>();
        if (!TextUtils.isEmpty(cur)) {
            String[] parts = cur.split(":");
            for (int i = 0; i < parts.length; i++) {
                String s = parts[i];
                if (s.length() == 0) continue;
                android.content.ComponentName cn =
                        android.content.ComponentName.unflattenFromString(s);
                String canon = cn == null ? s : cn.getPackageName() + "/" + cn.getClassName();
                uniq.put(norm(s), canon);
            }
        }
        int added = 0;
        for (String w : WANTED) {
            if (!uniq.containsKey(norm(w))) { uniq.put(norm(w), w); added++; }
        }
        StringBuilder sb = new StringBuilder();
        java.util.Collection<String> vals = uniq.values();
        for (String v : vals) { if (sb.length() > 0) sb.append(":"); sb.append(v); }
        String merged = sb.toString();
        if (!merged.equals(cur == null ? "" : cur)) {
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
            if (added == 0) added = 1;   // 纯去重/归一也算一次改动
        }
        // Settings provider 对相同值不发通知，所以先置 0 再置 1 强制触发重绑定
        boolean wasOn = masterOn(ctx);
        Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        if (added > 0 || !wasOn) added = Math.max(added, 1);
        Log.i(TAG, "自愈完成，补写 " + added + " 项");
        return added;
    }

    /** 停用：取消定时任务 + 把自己从无障碍列表里摘掉（保留车机投屏宝那条）。 */
    public static boolean disable(Context ctx) {
        if (!hasWritePerm(ctx)) return false;
        setUserDisabled(ctx, true);
        RearmJobService.cancel(ctx);
        String cur = readList(ctx);
        if (!TextUtils.isEmpty(cur)) {
            StringBuilder sb = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty() || s.equalsIgnoreCase(SELF)) continue;
                if (sb.length() > 0) sb.append(":");
                sb.append(s);
            }
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
        }
        Log.i(TAG, "已停用守护");
        return true;
    }

    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
