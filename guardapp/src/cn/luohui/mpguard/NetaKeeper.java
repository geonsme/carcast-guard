package cn.luohui.mpguard;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * 哪吒汽车保活通道：
 * 1) 用 BIND_IMPORTANT 常驻绑定 DMqttService，为哪吒主进程提供外部绑定锚；
 * 2) 断开即重绑，另以屏幕点亮与心跳广播兜底唤醒；
 * 3) 目标被系统回收后，BIND_AUTO_CREATE 会重新拉起它；
 * 4) RearmAlarm 还保留一条直连哪吒心跳接收器的闹钟，守护进程失效时仍能戳一下目标。
 */
public final class NetaKeeper {
    private static final String TAG = "MpGuard";

    public static final String PKG = "com.hezhong.nezha";
    private static final String SERVICE = "com.digital.mqttlib.service.DMqttService";
    private static final String SERVICE_ACTION = "com.fuxi.iovcloud.agent.action.MqttService.START";
    private static final String RECEIVER = "com.ingeek.vck.alive.receiver.BootBroadcastReceiver";
    static final String HEARTBEAT_ACTION = "com.ingeek.vck.alive.service.ACTION_SDK_HEARTBEAT";

    private static final String USER_PRESENT_ACTION = "com.ingeek.vck.alive.service.USER_PRESENT";

    private static final long WATCHDOG_MS = 30_000L;
    private static final long HEARTBEAT_MS = 2 * 60_000L;
    private static final long REBIND_MS = 2_500L;

    private static NetaKeeper sInstance;

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean stopped;
    private boolean bound;
    private boolean binding;
    private boolean receiverOn;
    private int bindCount;
    private long lastBoundAt;
    private long lastHeartbeatAt;
    private String lastError = "";

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            if (!bound && !binding) bind();
            if (lastHeartbeatAt == 0 || System.currentTimeMillis() - lastHeartbeatAt >= HEARTBEAT_MS) {
                heartbeat();
            }
            h.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable rebind = new Runnable() {
        @Override public void run() {
            if (!stopped) bind();
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (stopped) return;
            String act = intent == null ? "" : intent.getAction();
            Log.i(TAG, "系统事件 " + act + "，唤醒哪吒通道");
            if (!bound && !binding) bind();
            heartbeat();
            if (Intent.ACTION_USER_PRESENT.equals(act)) userPresentNudge();
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (stopped) { unbindQuietly(); return; }
            bound = true;
            binding = false;
            bindCount++;
            lastBoundAt = System.currentTimeMillis();
            lastError = "";
            Log.i(TAG, "哪吒 MQTT 服务已绑定");
            heartbeat();
            GuardWidgetProvider.refresh(app);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            binding = false;
            Log.w(TAG, "哪吒 MQTT 服务断开，准备重绑");
            scheduleRebind();
            GuardWidgetProvider.refresh(app);
        }

        @Override public void onBindingDied(ComponentName name) {
            bound = false;
            binding = false;
            Log.w(TAG, "哪吒 MQTT 绑定失效，准备重绑");
            unbindQuietly();
            scheduleRebind();
            GuardWidgetProvider.refresh(app);
        }

        @Override public void onNullBinding(ComponentName name) {
            bound = false;
            binding = false;
            lastError = "目标服务返回空绑定";
            Log.w(TAG, lastError);
            h.removeCallbacks(rebind);
            h.postDelayed(rebind, WATCHDOG_MS);
            GuardWidgetProvider.refresh(app);
        }
    };

    private NetaKeeper(Context context) {
        app = context;
    }

    public static synchronized void start(Context ctx) {
        if (ctx == null) return;
        if (Rearm.userDisabled(ctx)) return;
        if (!isInstalled(ctx)) return;
        Context base = ctx.getApplicationContext();
        if (base == null) base = ctx;
        if (sInstance == null) {
            sInstance = new NetaKeeper(base);
            sInstance.begin();
        } else if (sInstance.stopped) {
            sInstance.begin();
        }
    }

    public static synchronized void stop(Context ctx) {
        if (sInstance == null) return;
        sInstance.shutdown();
        sInstance = null;
        Log.i(TAG, "哪吒通道已停止");
    }

    /** 主动补一次心跳，并把断开的绑定拉起来。 */
    public static void kick(Context ctx) {
        start(ctx);
        NetaKeeper k = sInstance;
        if (k == null) return;
        if (!k.bound && !k.binding) k.bind();
        k.heartbeat();
    }

    /** 解锁时补一次哪吒自家的 USER_PRESENT，促使数字钥匙重新判断可用性。 */
    public static void userPresent(Context ctx) {
        start(ctx);
        NetaKeeper k = sInstance;
        if (k == null) return;
        if (!k.bound && !k.binding) k.bind();
        k.heartbeat();
        k.userPresentNudge();
    }

    /** 解绑后立即重建，用于界面上的人工重连。 */
    public static void requestRebind(Context ctx) {
        start(ctx);
        NetaKeeper k = sInstance;
        if (k == null) return;
        k.unbindQuietly();
        k.h.removeCallbacks(k.watchdog);
        k.h.removeCallbacks(k.rebind);
        k.h.postDelayed(k.watchdog, 600);
    }

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRunning() { return sInstance != null && !sInstance.stopped; }

    public static boolean isBound() { return sInstance != null && sInstance.bound; }

    public static int bindCount() { return sInstance == null ? 0 : sInstance.bindCount; }

    public static long lastHeartbeatAt() { return sInstance == null ? 0 : sInstance.lastHeartbeatAt; }

    public static long lastBoundAt() { return sInstance == null ? 0 : sInstance.lastBoundAt; }

    public static String lastError() { return sInstance == null ? "" : sInstance.lastError; }

    private void begin() {
        stopped = false;
        if (!receiverOn) {
            try {
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_USER_PRESENT);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(screenOn, f);
                }
                receiverOn = true;
            } catch (Exception e) {
                Log.w(TAG, "注册亮屏广播失败: " + e);
            }
        }
        h.removeCallbacks(watchdog);
        h.post(watchdog);
        Log.i(TAG, "哪吒通道启动");
    }

    private void shutdown() {
        stopped = true;
        h.removeCallbacksAndMessages(null);
        unbindQuietly();
        if (receiverOn) {
            try { app.unregisterReceiver(screenOn); } catch (Exception ignored) {}
            receiverOn = false;
        }
        GuardWidgetProvider.refresh(app);
    }

    private void bind() {
        if (stopped || bound || binding) return;
        binding = true;
        try {
            Intent i = new Intent(SERVICE_ACTION);
            i.setComponent(new ComponentName(PKG, SERVICE));
            boolean ok = app.bindService(i, conn,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            Log.i(TAG, "bindService 返回 " + ok);
            if (!ok) {
                binding = false;
                lastError = "系统拒绝建立绑定";
                scheduleRebind();
            }
        } catch (Exception e) {
            binding = false;
            lastError = e.getClass().getSimpleName();
            Log.w(TAG, "绑定哪吒服务失败: " + e);
            scheduleRebind();
        }
    }

    private void scheduleRebind() {
        h.removeCallbacks(rebind);
        h.postDelayed(rebind, REBIND_MS);
    }

    private void heartbeat() {
        if (stopped) return;
        if (sendAction(HEARTBEAT_ACTION, "心跳唤醒")) {
            lastHeartbeatAt = System.currentTimeMillis();
        }
    }

    private void userPresentNudge() {
        if (stopped) return;
        sendAction(USER_PRESENT_ACTION, "用户在场");
    }

    static Intent receiverIntent(String action) {
        Intent i = new Intent(action);
        i.setComponent(new ComponentName(PKG, RECEIVER));
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return i;
    }

    private boolean sendAction(String action, String label) {
        try {
            app.sendBroadcast(receiverIntent(action));
            Log.i(TAG, "已发送" + label + "广播");
            return true;
        } catch (Exception e) {
            lastError = label + "发送失败";
            Log.w(TAG, label + "发送失败: " + e);
            return false;
        }
    }

    private void unbindQuietly() {
        if (bound || binding) {
            try { app.unbindService(conn); } catch (Exception ignored) {}
        }
        bound = false;
        binding = false;
    }
}
