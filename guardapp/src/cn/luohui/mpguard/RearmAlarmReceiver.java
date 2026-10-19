package cn.luohui.mpguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RearmAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "MpGuard";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(TAG, "兜底闹钟触发");
        if (Rearm.userDisabled(ctx)) return;
        try { Rearm.rearm(ctx); } catch (Exception e) { Log.w(TAG, "闹钟自愈失败: " + e); }
        RearmJobService.schedule(ctx);
        try { NetaKeeper.kick(ctx); } catch (Exception e) { Log.w(TAG, "闹钟唤醒哪吒失败: " + e); }
        RearmAlarm.schedule(ctx);
    }
}
