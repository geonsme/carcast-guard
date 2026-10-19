package cn.luohui.mpguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 投屏授权守护：系统出现「允许投屏/录屏」对话框时，立刻自动点掉允许。
 * 无障碍服务由 system_server 在开机后按 secure settings 里的开关自动绑定，
 * 因此本方案不依赖 root、不依赖电脑、也不依赖 adb 的 shell 进程。
 */
public class GuardService extends AccessibilityService implements Runnable {

    private static final String TAG = "MpGuard";

    /** 只点这些「精确等于」的文案。绝不使用子串匹配：「不允许」包含「允许」，会点错。 */
    private static final String[] ALLOW_EXACT = {
            "立即开始", "开始投射", "开始投屏", "开始录制", "开始共享",
            "允许", "确定", "同意", "好的",
            "Start now", "Start casting", "Start recording", "Start share",
            "Allow", "OK", "Allow while installing apps"
    };

    /** 任何命中这些片段的文案一律视为危险，永不点击。 */
    private static final String[] DENY_HINTS = {
            "不允许", "禁止", "拒绝", "取消", "稍后", "关闭", "不允", "Cancel", "Deny", "Don't allow", "Do not allow"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int attempt = 0;
    private static int totalClicked = 0;
    /** 本无障碍服务是否真的被系统连上（同进程共享，比猜 API 返回准）。 */
    public static volatile boolean sConnected = false;
    public static int clickedCount() { return totalClicked; }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence cn = event.getClassName();
        if (cn == null) return;
        String cls = cn.toString().toLowerCase();

        // 只认投屏/录屏授权框，避免误点其它对话框
        boolean isTarget = cls.contains("mediaprojection")
                || cls.contains("screenrecord")
                || cls.contains("screencast")
                || cls.contains("screenshare")
                || cls.contains("projection");
        if (!isTarget) return;

        Log.i(TAG, "检测到投屏授权框: " + event.getPackageName() + " / " + cn);
        attempt = 0;
        handler.removeCallbacks(this);
        handler.postDelayed(this, 120);
    }

    @Override
    public void run() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) { retry(); return; }
            if (clickAllow(root)) {
                totalClicked++;
                Log.i(TAG, "已自动放行，累计 " + totalClicked + " 次");
                attempt = 0;
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "tick 异常: " + e);
        } finally {
            if (root != null) try { root.recycle(); } catch (Exception ignored) {}
        }
        retry();
    }

    private void retry() {
        if (++attempt > 16) { Log.w(TAG, "16 次未命中，放弃本轮"); attempt = 0; return; }
        handler.postDelayed(this, 200);
    }

    /** 按 ALLOW_EXACT 顺序逐个尝试，命中即点。 */
    private boolean clickAllow(AccessibilityNodeInfo root) {
        for (String want : ALLOW_EXACT) {
            if (clickExact(root, want)) return true;
        }
        return false;
    }

    private boolean clickExact(AccessibilityNodeInfo node, String want) {
        if (node == null) return false;
        if (matches(node.getText(), want) || matches(node.getContentDescription(), want)) {
            AccessibilityNodeInfo target = node.isClickable() ? node : clickableAncestor(node);
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            boolean ok = clickExact(c, want);
            if (ok) return true;
        }
        return false;
    }

    /** 精确匹配（trim + 全等），并排除任何含危险片段的文案。 */
    private boolean matches(CharSequence s, String want) {
        if (s == null) return false;
        String t = s.toString().trim();
        if (!t.equals(want)) return false;
        for (String d : DENY_HINTS) if (t.contains(d)) return false;
        return true;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        int depth = 0;
        while (p != null && depth++ < 6) {
            if (p.isClickable()) return p;
            p = p.getParent();
        }
        return null;
    }

    @Override public void onInterrupt() { handler.removeCallbacks(this); }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sConnected = true;
        Log.i(TAG, "守护服务已连接");
        try { RearmJobService.schedule(this); } catch (Exception ignored) {}
        try { RearmAlarm.schedule(this); } catch (Exception ignored) {}
        try { NetaKeeper.start(this); } catch (Exception e) { Log.w(TAG, "哪吒通道启动失败: " + e); }
        GuardWidgetProvider.refresh(this);
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                info.flags = 0;
                info.notificationTimeout = 80;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sConnected = false;
        handler.removeCallbacks(this);
        try { NetaKeeper.stop(this); } catch (Exception ignored) {}
        GuardWidgetProvider.refresh(this);
        return super.onUnbind(intent);
    }
}
