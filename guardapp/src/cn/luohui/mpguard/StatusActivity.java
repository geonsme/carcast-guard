package cn.luohui.mpguard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 状态页：解析出每个无障碍条目对应的真实 App 名称，并给出明确的操作反馈。 */
public class StatusActivity extends Activity implements View.OnClickListener, Runnable {

    private static final int OK = Color.parseColor("#84D87B");
    private static final int WARN = Color.parseColor("#FFB74D");
    private static final int ERR = Color.parseColor("#E57373");
    private static final int FG = Color.parseColor("#E8EAED");
    private static final int BG = Color.parseColor("#141618");

    private TextView overview, detail, result;
    private Button btnCheck, btnRearm, btnToggle, btnJump;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int pending = 0;   // 1=自愈 2=检测 3=停用 4=启用
    private boolean busy = false;
    private Refresher refresher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(48, 56, 48, 48);
        col.setBackgroundColor(BG);

        overview = mkText(22, true);
        col.addView(overview);
        detail = mkText(15, false);
        detail.setPadding(0, 28, 0, 20);
        col.addView(detail);
        result = mkText(15, false);
        result.setPadding(0, 8, 0, 26);
        col.addView(result);

        btnRearm = mkBtn("立即自愈");
        btnCheck = mkBtn("重新检测");
        btnToggle = mkBtn("停用守护");
        btnJump = mkBtn("打开系统无障碍设置");
        col.addView(btnRearm);
        col.addView(btnCheck);
        col.addView(btnToggle);
        col.addView(btnJump);

        btnRearm.setOnClickListener(this);
        btnCheck.setOnClickListener(this);
        btnToggle.setOnClickListener(this);
        btnJump.setOnClickListener(this);

        // 用户主动停用过，就不要自作主张再启用
        if (wantOff()) {
            setLastResult("你已停用守护 —— 不会自愈，重启后也不会自启", WARN);
        } else {
            RearmJobService.schedule(this);
            try { Rearm.rearm(this); } catch (Exception ignored) {}
            setLastResult("进入页面时已自动检查一遍", OK);
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(col);
        setContentView(sv);
        getWindow().getDecorView().setBackgroundColor(BG);
        render();
    }

    private TextView mkText(float size, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(bold ? OK : FG);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button mkBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 操作反馈 ----------

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void setLastResult(String msg, int color) {
        result.setText("最近操作  " + now() + "\n" + msg);
        result.setTextColor(color);
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private void startBusy(int mode, Button b, String doing) {
        if (busy) return;
        busy = true;
        pending = mode;
        b.setText(doing);
        b.setEnabled(false);
        h.postDelayed(this, 450);
    }

    @Override
    public void run() {
        Button b = null;
        String label = null;
        boolean selfBefore = guardEnabled();
        switch (pending) {
            case 1: b = btnRearm; label = "立即自愈"; break;
            case 2: b = btnCheck; label = "重新检测"; break;
            case 3: b = btnToggle; label = selfBefore ? "停用守护" : "启用守护"; break;
            case 4: b = btnToggle; label = selfBefore ? "停用守护" : "启用守护"; break;
        }
        int mode = pending;
        pending = 0;
        try {
            if (mode == 1) {
                int n = Rearm.rearm(this);
                String m = n == -2 ? "你已停用守护，未执行自愈"
                        : (n < 0 ? "失败：缺少 WRITE_SECURE_SETTINGS，需用 adb 授予"
                        : (n == 0 ? "无需修复 —— 授权本来就完好" : "已补写 " + n + " 项，并强制系统重新绑定"));
                setLastResult(m, n == -2 ? WARN : (n < 0 ? ERR : OK));
                toast(m);
            } else if (mode == 2) {
                setLastResult("已重新读取系统状态", OK);
                toast("已刷新");
            } else if (mode == 3) {
                boolean ok = Rearm.disable(this);
                setWantOff(true);
                String m = ok ? "已停用：定时任务取消，自身无障碍条目已摘除"
                        : "停用失败：缺少 WRITE_SECURE_SETTINGS";
                setLastResult(m, ok ? WARN : ERR);
                toast(m);
            } else if (mode == 4) {
                setWantOff(false);
                int n = Rearm.rearm(this);
                RearmJobService.schedule(this);
                String m = n < 0 ? "启用失败：缺少 WRITE_SECURE_SETTINGS" : "已重新启用守护";
                setLastResult(m, n < 0 ? ERR : OK);
                toast(m);
            }
        } catch (Exception e) {
            setLastResult("操作异常：" + e, ERR);
        } finally {
            busy = false;
            if (b != null && label != null) { b.setText(label); b.setEnabled(true); }
            render();
        }
    }

    @Override
    public void onClick(View v) {
        if (v == btnRearm) startBusy(1, btnRearm, "正在自愈…");
        else if (v == btnCheck) startBusy(2, btnCheck, "检测中…");
        else if (v == btnJump) startActivity(Rearm.settingsIntent());
        else if (v == btnToggle) {
            if (guardEnabled()) startBusy(3, btnToggle, "正在停用…");
            else startBusy(4, btnToggle, "正在启用…");
        }
    }

    // ---------- 状态渲染 ----------

    private boolean wantOff() { return Rearm.userDisabled(this); }

    private void setWantOff(boolean v) { Rearm.setUserDisabled(this, v); }

    private boolean guardEnabled() {
        return Rearm.contains(Rearm.readList(this), Rearm.SELF);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (refresher == null) refresher = new Refresher(this);
        h.removeCallbacks(refresher);
        h.postDelayed(refresher, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (refresher != null) h.removeCallbacks(refresher);
    }

    /** 命名静态嵌套类（匿名内部类会让本项目的 R8 版本崩溃）。 */
    private static class Refresher implements Runnable {
        private final StatusActivity a;
        Refresher(StatusActivity a) { this.a = a; }
        @Override public void run() {
            if (a == null || a.isFinishing()) return;
            if (!a.busy) a.render();
            a.h.postDelayed(this, 3000);
        }
    }

    /**
     * 归一化组件标识：设置列表里是全写 "pkg/pkg.Cls"，
     * 而 AccessibilityServiceInfo.getId() 常给缩写 "pkg/.Cls"，必须统一后再比对。
     */
    private static String norm(String comp) {
        if (comp == null) return "";
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp);
        if (cn == null) return comp.trim().toLowerCase(Locale.ROOT);
        return (cn.getPackageName() + "/" + cn.getClassName()).toLowerCase(Locale.ROOT);
    }

    private HashSet<String> loadedIds() {
        HashSet<String> s = new HashSet<String>();
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> l =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (l != null) {
                    for (AccessibilityServiceInfo i : l) if (i.getId() != null) s.add(norm(i.getId()));
                }
            }
        } catch (Exception ignored) {}
        return s;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return "（未知应用）"; }
    }

    private void render() {
        String list = Rearm.readList(this);
        boolean master = Rearm.masterOn(this);
        boolean selfOn = guardEnabled();
        boolean jobOn = RearmJobService.isScheduled(this);
        boolean perm = Rearm.hasWritePerm(this);
        HashSet<String> loaded = loadedIds();

        StringBuilder d = new StringBuilder();
        int idx = 1;
        if (TextUtils.isEmpty(list)) {
            d.append("无障碍列表为空\n\n");
        } else {
            String[] parts = list.split(":");
            for (int i = 0; i < parts.length; i++) {
                String comp = parts[i];
                if (comp.length() == 0) continue;
                int slash = comp.indexOf('/');
                String pkg = slash > 0 ? comp.substring(0, slash) : comp;
                String cls = slash > 0 ? comp.substring(slash + 1) : "";
                boolean isSelf = comp.equalsIgnoreCase(Rearm.SELF);
                boolean isLoaded = loaded.contains(norm(comp));
                d.append(idx++).append(".  ").append(appLabel(pkg))
                 .append(isSelf ? "　（本应用）" : "").append("\n")
                 .append("      服务　").append(cls).append("\n")
                 .append("      已启用　是")
                 .append("　　已被系统加载　").append(isLoaded ? "是" : "否").append("\n\n");
            }
        }
        d.append("自愈定时任务　").append(jobOn ? "已登记（约每 15 分钟）" : "未登记 —— 重启后不会自愈").append("\n");
        d.append("WRITE_SECURE_SETTINGS　").append(perm ? "已授予" : "未授予");
        detail.setText(d.toString());
        detail.setTextColor(FG);

        String ov;
        int c;
        boolean selfLoaded = loaded.contains(norm(Rearm.SELF));
        if (!selfOn) { ov = "守护状态：已停用"; c = WARN; }
        else if (master && jobOn && perm && selfLoaded) { ov = "守护状态：正常"; c = OK; }
        else if (!perm) { ov = "守护状态：缺自愈权限"; c = ERR; }
        else if (!selfLoaded) { ov = "守护状态：未被系统加载"; c = ERR; }
        else if (!jobOn) { ov = "守护状态：不完整（缺定时任务）"; c = WARN; }
        else { ov = "守护状态：总开关未打开"; c = WARN; }
        overview.setText(ov);
        overview.setTextColor(c);

        btnToggle.setText(selfOn ? "停用守护" : "启用守护");
    }
}
