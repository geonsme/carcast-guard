package cn.luohui.mpguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 进程被系统回收后的兜底闹钟：
 * 9001 叫醒守护自己，补绑定并补心跳；
 * 9002 直接叫醒哪吒的导出的心跳接收器，守护进程没被拉起时仍有一次外部唤醒。
 * JobScheduler 可能被延迟到很久以后，allow-while-idle 闹钟能在 Doze 中唤醒目标。
 */
public final class RearmAlarm {
    private static final String TAG = "MpGuard";
    public static final String ACTION = "cn.luohui.mpguard.REARM_ALARM";
    private static final int REQUEST_GUARD = 9001;
    private static final int REQUEST_NETA = 9002;
    private static final long INTERVAL_MS = 10 * 60_000L;
    private static final long NETA_OFFSET_MS = 30_000L;
    private static volatile long lastScheduledAt;

    private RearmAlarm() {}

    public static boolean isArmed() {
        return lastScheduledAt > 0;
    }

    public static void schedule(Context ctx) {
        if (ctx == null || Rearm.userDisabled(ctx)) {
            lastScheduledAt = 0;
            return;
        }
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long now = System.currentTimeMillis();
            scheduleAt(am, now + INTERVAL_MS, pending(ctx), "兜底闹钟（+10 分钟）");
            lastScheduledAt = now;
            if (NetaKeeper.isInstalled(ctx)) {
                scheduleAt(am, now + INTERVAL_MS + NETA_OFFSET_MS,
                        pendingNeta(ctx), "哪吒直连心跳闹钟（+10 分 30 秒）");
            }
        } catch (Exception e) {
            lastScheduledAt = 0;
            Log.w(TAG, "排定兜底闹钟失败: " + e);
        }
    }

    private static void scheduleAt(AlarmManager am, long at, PendingIntent pi, String label) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Log.i(TAG, label + "已排定（非精确）");
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            Log.i(TAG, label + "已排定（精确）");
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            Log.i(TAG, label + "已排定（精确）");
        }
    }

    public static void cancel(Context ctx) {
        lastScheduledAt = 0;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(pending(ctx));
            if (NetaKeeper.isInstalled(ctx)) am.cancel(pendingNeta(ctx));
        } catch (Exception ignored) {}
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, RearmAlarmReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(ctx, REQUEST_GUARD, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent pendingNeta(Context ctx) {
        Intent i = NetaKeeper.receiverIntent(NetaKeeper.HEARTBEAT_ACTION);
        return PendingIntent.getBroadcast(ctx, REQUEST_NETA, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
