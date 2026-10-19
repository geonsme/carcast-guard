package cn.luohui.mpguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自愈：把被系统清掉的无障碍开关重新写回去。 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "MpGuard";
    private static final String CH = "mpguard";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String act = intent == null ? "?" : intent.getAction();
        Log.i(TAG, "收到广播 " + act);
        int n = Rearm.rearm(ctx);
        RearmJobService.schedule(ctx);
        if (n < 0) {
            notify(ctx, "投屏守护需要一次授权",
                    "请在电脑上执行: adb shell pm grant " + ctx.getPackageName()
                            + " android.permission.WRITE_SECURE_SETTINGS");
        } else {
            notify(ctx, "投屏守护已自愈", "开机后已重新写回 " + n + " 项无障碍授权");
        }
    }

    static void notify(Context ctx, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(CH, "投屏授权守护",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(c);
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, Rearm.settingsIntent(),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = (android.os.Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, CH) : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
             .setContentTitle(title)
             .setContentText(text)
             .setContentIntent(pi)
             .setAutoCancel(true);
            nm.notify(777, b.build());
        } catch (Exception e) {
            Log.w(TAG, "通知失败: " + e);
        }
    }
}
