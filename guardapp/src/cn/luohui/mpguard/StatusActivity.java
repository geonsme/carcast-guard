package cn.luohui.mpguard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 车控守护仪表盘：守护指数、双目标遥测、系统条件与一键操作。 */
public class StatusActivity extends Activity implements View.OnClickListener {

    private static final String UI_PREFS = "guard_ui";

    private int C_OK;
    private int C_WARN;
    private int C_ERR;
    private int C_MUTED;
    private int C_TEXT;
    private int C_TEXT2;

    private View headerPill;
    private View headerDot;
    private TextView headerStatus;
    private TextView heroHeadline;
    private TextView heroSub;
    private TextView pillAccess;
    private TextView pillBind;
    private TextView pillHeal;
    private ProtectionGaugeView gauge;
    private LinearLayout targetsContainer;
    private LinearLayout conditionsContainer;
    private TextView lastAction;
    private TextView lastActionTime;
    private TextView btnRearm;
    private TextView btnCheck;
    private TextView btnToggle;
    private ImageButton btnJump;
    private TextView updateVersion;
    private TextView updateHint;
    private TextView btnUpdate;

    private TargetCard svenCard;
    private TargetCard netaCard;
    private final List<CondRow> condRows = new ArrayList<CondRow>();

    private UpdateChecker.ReleaseInfo availableRelease;
    private File pendingUpdateApk;
    private boolean updateBusy;
    private boolean installLaunched;
    private boolean awaitingInstallPermission;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean busy;
    private ObjectAnimator pulse;
    private Refresher refresher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status);

        C_OK = getColor(R.color.accent);
        C_WARN = getColor(R.color.warn);
        C_ERR = getColor(R.color.err);
        C_MUTED = getColor(R.color.text_muted);
        C_TEXT = getColor(R.color.text_primary);
        C_TEXT2 = getColor(R.color.text_secondary);

        bindViews();
        setupTargets();
        setupConditions();
        setupClicks();
        updateVersion.setText(getString(R.string.update_current, UpdateChecker.currentVersion(this)));
        loadLastAction();
        startPulse();

        if (Rearm.userDisabled(this)) {
            NetaKeeper.stop(this);
        } else {
            RearmJobService.schedule(this);
            RearmAlarm.schedule(this);
            autoRepairIfNeeded();
            NetaKeeper.start(this);
            GuardWidgetProvider.refresh(this);
        }

        render(true);
        playEntrance();
    }

    private void bindViews() {
        headerPill = findViewById(R.id.headerPill);
        headerDot = findViewById(R.id.headerDot);
        headerStatus = (TextView) findViewById(R.id.headerStatus);
        heroHeadline = (TextView) findViewById(R.id.heroHeadline);
        heroSub = (TextView) findViewById(R.id.heroSub);
        pillAccess = (TextView) findViewById(R.id.pillAccess);
        pillBind = (TextView) findViewById(R.id.pillBind);
        pillHeal = (TextView) findViewById(R.id.pillHeal);
        gauge = (ProtectionGaugeView) findViewById(R.id.gauge);
        targetsContainer = (LinearLayout) findViewById(R.id.targetsContainer);
        conditionsContainer = (LinearLayout) findViewById(R.id.conditionsCard);
        lastAction = (TextView) findViewById(R.id.lastAction);
        lastActionTime = (TextView) findViewById(R.id.lastActionTime);
        btnRearm = (TextView) findViewById(R.id.btnRearm);
        btnCheck = (TextView) findViewById(R.id.btnCheck);
        btnToggle = (TextView) findViewById(R.id.btnToggle);
        btnJump = (ImageButton) findViewById(R.id.btnJump);
        updateVersion = (TextView) findViewById(R.id.updateVersion);
        updateHint = (TextView) findViewById(R.id.updateHint);
        btnUpdate = (TextView) findViewById(R.id.btnUpdate);
    }

    private void setupTargets() {
        LayoutInflater inf = LayoutInflater.from(this);

        View svenView = inf.inflate(R.layout.item_target, targetsContainer, false);
        svenCard = new TargetCard(svenView);
        svenCard.icon.setImageResource(R.drawable.ic_cast);
        svenCard.name.setText(R.string.target_sven);
        svenCard.pkg.setText("com.sven.mycar");
        svenCard.action.setVisibility(View.GONE);
        targetsContainer.addView(svenView);

        View netaView = inf.inflate(R.layout.item_target, targetsContainer, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        netaCard = new TargetCard(netaView);
        netaCard.icon.setImageResource(R.drawable.ic_bolt);
        netaCard.name.setText(R.string.target_neta);
        netaCard.pkg.setText(NetaKeeper.PKG);
        netaCard.action.setVisibility(View.VISIBLE);
        netaCard.action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rebindNeta(); }
        });
        targetsContainer.addView(netaView, lp);
    }

    private void setupConditions() {
        String[] titles = {"无障碍总开关", "自愈权限", "持久体检", "电池优化豁免"};
        String[] subs = {"ACCESSIBILITY MASTER", "WRITE_SECURE_SETTINGS",
                "DUAL ALARM 10 MIN · JOB 15 MIN", "DOZE WHITELIST"};
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < titles.length; i++) {
            View v = inf.inflate(R.layout.item_condition, conditionsContainer, false);
            CondRow row = new CondRow(v);
            row.title.setText(titles[i]);
            row.sub.setText(subs[i]);
            conditionsContainer.addView(v);
            condRows.add(row);
        }

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);

        CondRow permRow = condRows.get(1);
        permRow.root.setClickable(true);
        permRow.root.setBackgroundResource(tv.resourceId);
        permRow.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Rearm.hasWritePerm(StatusActivity.this)) {
                    toast("自愈权限已授予");
                } else {
                    showPermDialog();
                }
            }
        });

        CondRow batteryRow = condRows.get(3);
        batteryRow.root.setClickable(true);
        batteryRow.root.setBackgroundResource(tv.resourceId);
        batteryRow.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    toast("无法打开电池优化设置");
                }
            }
        });
    }

    private void setupClicks() {
        btnRearm.setOnClickListener(this);
        btnCheck.setOnClickListener(this);
        btnJump.setOnClickListener(this);
        btnToggle.setOnClickListener(this);
        btnUpdate.setOnClickListener(this);
    }

    private void autoRepairIfNeeded() {
        if (!Rearm.hasWritePerm(this)) return;
        String list = Rearm.readList(this);
        if (!Rearm.masterOn(this)
                || !Rearm.contains(list, Rearm.SELF)
                || !Rearm.contains(list, Rearm.SVEN)) {
            try { Rearm.rearm(this); } catch (Exception ignored) {}
        }
    }

    // ---------- 操作 ----------

    @Override
    public void onClick(View v) {
        if (v == btnRearm) {
            if (Rearm.userDisabled(this)) enableGuard();
            else healGuard();
        } else if (v == btnCheck) {
            checkNow();
        } else if (v == btnJump) {
            startActivity(Rearm.settingsIntent());
        } else if (v == btnToggle) {
            if (Rearm.userDisabled(this)) enableGuard();
            else showStopDialog();
        } else if (v == btnUpdate) {
            onUpdateClicked();
        }
    }

    private void healGuard() {
        if (busy) return;
        setBusy(true);
        String msg;
        try {
            int n = Rearm.rearm(this);
            RearmJobService.schedule(this);
            RearmAlarm.schedule(this);
            if (n == -2) {
                msg = "守护已停用 · 未执行自愈";
            } else if (n < 0) {
                msg = "自愈失败 · 缺少 WRITE_SECURE_SETTINGS";
                showPermDialog();
            } else {
                NetaKeeper.kick(this);
                msg = n == 0 ? "自愈完成 · 授权本来就完好" : "自愈完成 · 补写 " + n + " 项授权";
            }
        } catch (Exception e) {
            msg = "自愈异常 · " + e.getClass().getSimpleName();
        } finally {
            setBusy(false);
        }
        record(msg);
        toast(msg);
        GuardWidgetProvider.refresh(this);
        render(true);
    }

    private void enableGuard() {
        if (busy) return;
        setBusy(true);
        String msg;
        try {
            Rearm.setUserDisabled(this, false);
            int n = Rearm.rearm(this);
            RearmJobService.schedule(this);
            RearmAlarm.schedule(this);
            NetaKeeper.start(this);
            NetaKeeper.kick(this);
            if (n < 0) {
                msg = "启用失败 · 缺少 WRITE_SECURE_SETTINGS";
                showPermDialog();
            } else {
                msg = "守护已恢复 · 双通道启动";
            }
        } catch (Exception e) {
            msg = "启用异常 · " + e.getClass().getSimpleName();
        } finally {
            setBusy(false);
        }
        record(msg);
        toast(msg);
        GuardWidgetProvider.refresh(this);
        render(true);
    }

    private void disableGuard() {
        if (busy) return;
        boolean ok = Rearm.disable(this);
        NetaKeeper.stop(this);
        String msg = ok ? "守护已停用 · 定时任务与哪吒绑定已停止"
                : "停用失败 · 缺少 WRITE_SECURE_SETTINGS";
        record(msg);
        toast(msg);
        GuardWidgetProvider.refresh(this);
        render(false);
    }

    private void checkNow() {
        if (busy) return;
        if (!Rearm.userDisabled(this)) {
            RearmJobService.schedule(this);
            RearmAlarm.schedule(this);
            NetaKeeper.kick(this);
        }
        String msg = Rearm.userDisabled(this) ? "状态刷新 · 守护当前已停用"
                : "状态刷新 · 哪吒通道已唤醒";
        record(msg);
        toast("状态已刷新");
        GuardWidgetProvider.refresh(this);
        render(false);
    }

    private void rebindNeta() {
        if (Rearm.userDisabled(this)) {
            toast("守护已停用，请先恢复守护");
            return;
        }
        if (!NetaKeeper.isInstalled(this)) {
            toast("未检测到哪吒汽车 App");
            return;
        }
        NetaKeeper.requestRebind(this);
        GuardWidgetProvider.refresh(this);
        record("人工重连 · 哪吒 MQTT 通道");
        toast("正在重新绑定哪吒通道");
        h.postDelayed(new Runnable() {
            @Override public void run() { render(false); }
        }, 1400);
    }

    // ---------- 应用更新 ----------

    private void onUpdateClicked() {
        if (updateBusy) return;
        if (pendingUpdateApk != null && pendingUpdateApk.isFile()) {
            if (UpdateChecker.canInstall(this)) {
                installLaunched = false;
                launchInstaller();
            } else {
                showInstallPermDialog();
            }
            return;
        }
        if (availableRelease != null) {
            showUpdateDialog(availableRelease);
            return;
        }
        checkUpdate();
    }

    private void checkUpdate() {
        updateBusy = true;
        btnUpdate.setEnabled(false);
        btnUpdate.setText(R.string.btn_checking);
        updateHint.setText(R.string.update_checking);
        UpdateChecker.check(this, new UpdateChecker.CheckCallback() {
            @Override public void onResult(UpdateChecker.ReleaseInfo release, boolean newer) {
                if (updateFinished()) return;
                updateBusy = false;
                btnUpdate.setEnabled(true);
                if (newer) {
                    availableRelease = release;
                    updateHint.setText(getString(R.string.update_available, release.version));
                    btnUpdate.setText(R.string.btn_update_now);
                    showUpdateDialog(release);
                } else {
                    availableRelease = null;
                    updateHint.setText(R.string.update_latest);
                    btnUpdate.setText(R.string.btn_update);
                    toast(getString(R.string.update_latest_toast, release.version));
                }
            }

            @Override public void onError(String message) {
                if (updateFinished()) return;
                updateBusy = false;
                btnUpdate.setEnabled(true);
                btnUpdate.setText(R.string.btn_update);
                updateHint.setText(R.string.update_failed);
                toast(message);
            }
        });
    }

    private void showUpdateDialog(final UpdateChecker.ReleaseInfo release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.isEmpty()) {
            notes = getString(R.string.dialog_update_empty);
        } else if (notes.length() > 900) {
            notes = notes.substring(0, 900) + "…";
        }
        String size = release.size > 0 ? UpdateChecker.humanBytes(release.size) : "未知大小";
        String message = getString(R.string.dialog_update_msg,
                UpdateChecker.currentVersion(this), release.version, size, notes);
        final Dialog d = buildDialog(R.string.dialog_update_title, message);
        TextView ok = (TextView) d.findViewById(R.id.dlgOk);
        ok.setText(R.string.dialog_update_now);
        ok.setBackgroundResource(R.drawable.bg_btn_primary);
        ok.setTextColor(getColor(R.color.ink));
        d.findViewById(R.id.dlgCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                startUpdateDownload(release);
            }
        });
        present(d);
    }

    private void startUpdateDownload(final UpdateChecker.ReleaseInfo release) {
        updateBusy = true;
        btnUpdate.setEnabled(false);
        btnUpdate.setText(R.string.btn_downloading);
        updateHint.setText(getString(R.string.update_downloading_unknown, "0 B"));
        UpdateChecker.download(this, release, new UpdateChecker.DownloadCallback() {
            @Override public void onProgress(int pct, long done, long total) {
                if (updateFinished()) return;
                if (pct >= 0) {
                    updateHint.setText(getString(R.string.update_downloading,
                            pct, UpdateChecker.humanBytes(done)));
                } else {
                    updateHint.setText(getString(R.string.update_downloading_unknown,
                            UpdateChecker.humanBytes(done)));
                }
            }

            @Override public void onReady(File apk) {
                if (updateFinished()) return;
                updateBusy = false;
                pendingUpdateApk = apk;
                availableRelease = null;
                btnUpdate.setEnabled(true);
                btnUpdate.setText(R.string.btn_install_update);
                updateHint.setText(R.string.update_install_ready);
                installLaunched = false;
                if (UpdateChecker.canInstall(StatusActivity.this)) {
                    launchInstaller();
                } else {
                    showInstallPermDialog();
                }
            }

            @Override public void onError(String message) {
                if (updateFinished()) return;
                updateBusy = false;
                btnUpdate.setEnabled(true);
                btnUpdate.setText(R.string.btn_retry_update);
                updateHint.setText(R.string.update_failed);
                toast(message);
            }
        });
    }

    private void launchInstaller() {
        final File apk = pendingUpdateApk;
        if (apk == null || !apk.isFile()) return;
        installLaunched = true;
        btnUpdate.setEnabled(false);
        btnUpdate.setText(R.string.btn_waiting_install);
        updateHint.setText(R.string.update_waiting_install);
        try {
            UpdateChecker.install(this, apk);
        } catch (Exception e) {
            installLaunched = false;
            btnUpdate.setEnabled(true);
            btnUpdate.setText(R.string.btn_install_update);
            updateHint.setText(R.string.update_install_ready);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            toast("无法启动安装 · " + detail);
        }
    }

    private void showInstallPermDialog() {
        final Dialog d = buildDialog(R.string.dialog_install_title,
                getString(R.string.dialog_install_msg));
        TextView ok = (TextView) d.findViewById(R.id.dlgOk);
        ok.setText(R.string.dialog_install_go);
        ok.setBackgroundResource(R.drawable.bg_btn_primary);
        ok.setTextColor(getColor(R.color.ink));
        d.findViewById(R.id.dlgCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                awaitingInstallPermission = true;
                try {
                    startActivity(UpdateChecker.installPermissionIntent(StatusActivity.this));
                } catch (Exception e) {
                    awaitingInstallPermission = false;
                    toast("无法打开安装权限设置");
                }
            }
        });
        present(d);
    }

    private boolean updateFinished() {
        return isFinishing() || isDestroyed();
    }

    private void setBusy(boolean v) {
        busy = v;
        btnRearm.setEnabled(!v);
        btnCheck.setEnabled(!v);
        btnToggle.setEnabled(!v);
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("adb", text));
        } catch (Exception ignored) {}
    }

    // ---------- 弹窗 ----------

    private Dialog buildDialog(int titleRes, String message) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_confirm);
        ((TextView) d.findViewById(R.id.dlgTitle)).setText(titleRes);
        ((TextView) d.findViewById(R.id.dlgMessage)).setText(message);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.66f;
            w.setAttributes(lp);
        }
        return d;
    }

    private void present(Dialog d) {
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showStopDialog() {
        final Dialog d = buildDialog(R.string.dialog_stop_title, getString(R.string.dialog_stop_msg));
        TextView ok = (TextView) d.findViewById(R.id.dlgOk);
        ok.setText(R.string.dialog_stop_confirm);
        ok.setBackgroundResource(R.drawable.bg_btn_danger);
        ok.setTextColor(C_ERR);
        d.findViewById(R.id.dlgCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                disableGuard();
            }
        });
        present(d);
    }

    private void showPermDialog() {
        final String cmd = getString(R.string.perm_command, getPackageName());
        final Dialog d = buildDialog(R.string.dialog_perm_title, getString(R.string.dialog_perm_msg));
        TextView command = (TextView) d.findViewById(R.id.dlgCommand);
        command.setVisibility(View.VISIBLE);
        command.setText(cmd);

        TextView ok = (TextView) d.findViewById(R.id.dlgOk);
        ok.setText(R.string.dialog_perm_copy);
        ok.setBackgroundResource(R.drawable.bg_btn_primary);
        ok.setTextColor(getColor(R.color.ink));
        d.findViewById(R.id.dlgCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyToClipboard(cmd);
                d.dismiss();
                toast("命令已复制，粘贴到电脑终端执行");
            }
        });
        present(d);
    }

    // ---------- 渲染 ----------

    private boolean wantOff() { return Rearm.userDisabled(this); }

    private void render(boolean animate) {
        if (busy) return;

        boolean off = wantOff();
        boolean perm = Rearm.hasWritePerm(this);
        String list = Rearm.readList(this);
        boolean master = Rearm.masterOn(this);
        boolean selfIn = Rearm.contains(list, Rearm.SELF);
        boolean svenIn = Rearm.contains(list, Rearm.SVEN);
        HashSet<String> loaded = loadedIds();
        boolean selfLoaded = master && selfIn
                && (GuardService.sConnected || loaded.contains(norm(Rearm.SELF)));
        boolean svenLoaded = master && svenIn && loaded.contains(norm(Rearm.SVEN));
        boolean job = RearmJobService.isScheduled(this);
        boolean netaInstalled = NetaKeeper.isInstalled(this);
        boolean netaBound = NetaKeeper.isBound();
        boolean netaRunning = NetaKeeper.isRunning();
        boolean svenInstalled = installed("com.sven.mycar");

        int score = 0;
        if (selfIn) score += 10;
        if (selfLoaded) score += 15;
        if (svenIn) score += 10;
        if (svenLoaded) score += 15;
        if (job) score += 15;
        if (perm) score += 15;
        if (netaBound) score += 20;
        if (off) score = 0;
        int scoreColor = off ? C_WARN : (score >= 90 ? C_OK : (score >= 60 ? C_WARN : C_ERR));
        gauge.setScore(score, scoreColor, animate);

        String head;
        int headColor;
        if (off) { head = "已停用"; headColor = C_MUTED; }
        else if (!perm) { head = "待授权"; headColor = C_ERR; }
        else if (!master || !selfIn || !selfLoaded) { head = "待激活"; headColor = C_ERR; }
        else if (!netaInstalled) { head = "仅投屏"; headColor = C_WARN; }
        else if (!netaBound) { head = "重连中"; headColor = C_WARN; }
        else if (!svenLoaded || !job) { head = "需关注"; headColor = C_WARN; }
        else { head = "守护中"; headColor = C_OK; }
        headerStatus.setText(head);
        headerStatus.setTextColor(headColor == C_MUTED ? C_TEXT2 : headColor);
        headerPill.setBackgroundResource(pillBg(headColor));
        headerDot.setBackgroundTintList(ColorStateList.valueOf(headColor));

        String headline;
        String sub;
        if (off) {
            headline = "守护已暂停";
            sub = "重新启用后恢复自愈与哪吒绑定";
        } else if (!perm) {
            headline = "缺少自愈权限";
            sub = "连接电脑授予一次，即可自动修复";
        } else if (!master || !selfIn || !selfLoaded) {
            headline = "无障碍通道未加载";
            sub = "在系统设置中重新启用本服务";
        } else if (!netaInstalled) {
            headline = "未检测到哪吒汽车";
            sub = "安装并登录后即可自动接入守护";
        } else if (!netaBound) {
            headline = "哪吒通道正在重连";
            sub = "保持 MQTT 服务绑定 · 自动恢复进程优先级";
        } else if (!svenInstalled) {
            headline = "未检测到车机投屏宝";
            sub = "安装后本应用会自动接管投屏授权";
        } else if (!svenLoaded) {
            headline = "投屏宝通道未就绪";
            sub = "检查无障碍列表中的投屏宝服务";
        } else if (!job) {
            headline = "定时体检未登记";
            sub = "点击立即自愈重新登记持久任务";
        } else {
            headline = "双通道守护在线";
            sub = "投屏宝 · 哪吒汽车 · 本机离线守护";
        }
        heroHeadline.setText(headline);
        heroSub.setText(sub);

        String accessText;
        int accessColor;
        if (off) { accessText = "无障碍 暂停"; accessColor = C_MUTED; }
        else if (!master) { accessText = "无障碍 关闭"; accessColor = C_ERR; }
        else if (selfLoaded) { accessText = "无障碍 正常"; accessColor = C_OK; }
        else if (selfIn) { accessText = "无障碍 待载"; accessColor = C_WARN; }
        else { accessText = "无障碍 未载"; accessColor = C_ERR; }
        applyPill(pillAccess, accessText, accessColor);

        String bindText;
        int bindColor;
        if (off) { bindText = "绑定 停止"; bindColor = C_MUTED; }
        else if (!netaInstalled) { bindText = "绑定 未装"; bindColor = C_ERR; }
        else if (netaBound) { bindText = "绑定 在线"; bindColor = C_OK; }
        else { bindText = "绑定 重连"; bindColor = C_WARN; }
        applyPill(pillBind, bindText, bindColor);

        String healText;
        int healColor;
        if (off) { healText = "自愈 停止"; healColor = C_MUTED; }
        else if (!perm) { healText = "自愈 缺权"; healColor = C_ERR; }
        else if (!job) { healText = "自愈 未登记"; healColor = C_WARN; }
        else if (!selfLoaded) { healText = "自愈 待唤醒"; healColor = C_WARN; }
        else { healText = "自愈 就绪"; healColor = C_OK; }
        applyPill(pillHeal, healText, healColor);

        // 投屏宝
        if (!svenInstalled) {
            applyPill(svenCard.pill, "未安装", C_ERR);
            svenCard.setRow(0, "应用状态", "未安装", C_ERR);
            svenCard.setRow(1, "无障碍通道", "—", C_MUTED);
            svenCard.setRow(2, "投屏弹窗", "—", C_MUTED);
        } else {
            svenCard.setRow(0, "无障碍通道",
                    svenIn ? (svenLoaded ? "已加载" : "待系统加载") : "未启用",
                    svenLoaded ? C_OK : (svenIn ? C_WARN : C_ERR));
            svenCard.setRow(1, "投屏弹窗",
                    selfLoaded ? "自动放行" : "未接管",
                    selfLoaded ? C_OK : C_MUTED);
            int clicks = GuardService.clickedCount();
            svenCard.setRow(2, "本次运行", clicks + " 次放行", clicks > 0 ? C_OK : C_MUTED);
            String pt;
            int pc;
            if (svenLoaded) { pt = "守护中"; pc = C_OK; }
            else if (svenIn && master) { pt = "待加载"; pc = C_WARN; }
            else if (off) { pt = "已停用"; pc = C_MUTED; }
            else { pt = "未启用"; pc = C_ERR; }
            applyPill(svenCard.pill, pt, pc);
        }

        // 哪吒
        if (!netaInstalled) {
            applyPill(netaCard.pill, "未安装", C_ERR);
            netaCard.setRow(0, "MQTT 绑定", "—", C_MUTED);
            netaCard.setRow(1, "心跳唤醒", "—", C_MUTED);
            netaCard.setRow(2, "双路闹钟", "—", C_MUTED);
            netaCard.action.setVisibility(View.GONE);
        } else {
            netaCard.action.setVisibility(off ? View.GONE : View.VISIBLE);
            String pt;
            int pc;
            if (off) { pt = "已暂停"; pc = C_MUTED; }
            else if (netaBound) { pt = "守护中"; pc = C_OK; }
            else if (netaRunning) { pt = "重连中"; pc = C_WARN; }
            else { pt = "待激活"; pc = C_WARN; }
            applyPill(netaCard.pill, pt, pc);

            netaCard.setRow(0, "MQTT 绑定",
                    off ? "已暂停" : (netaBound ? "已连接 · 自动重连"
                            : (netaRunning ? "绑定中 · 稍候重试" : "未启动")),
                    off ? C_MUTED : (netaBound ? C_OK : C_WARN));

            long hb = NetaKeeper.lastHeartbeatAt();
            String hbText;
            int hbColor;
            if (off) { hbText = "已暂停"; hbColor = C_MUTED; }
            else if (hb <= 0) { hbText = "待发送 · 每 2 分钟"; hbColor = C_WARN; }
            else {
                hbText = ago(hb) + " · 每 2 分钟";
                hbColor = System.currentTimeMillis() - hb <= 3 * 60_000L ? C_OK : C_WARN;
            }
            netaCard.setRow(1, "心跳唤醒", hbText, hbColor);

            boolean armed = RearmAlarm.isArmed();
            netaCard.setRow(2, "双路闹钟",
                    off ? "已暂停" : (armed ? "已布防 · 每 10 分钟" : "未布防"),
                    off ? C_MUTED : (armed ? C_OK : C_WARN));
        }

        // 系统条件
        String accessValue;
        int accessState;
        if (!master) { accessValue = "未开启"; accessState = 2; }
        else if (!selfIn) { accessValue = "缺本应用"; accessState = 2; }
        else if (!selfLoaded) { accessValue = "待加载"; accessState = 1; }
        else { accessValue = "已开启"; accessState = 0; }
        setCond(0, accessValue, accessState);
        setCond(1, perm ? "已授予" : "需 ADB", perm ? 0 : 2);
        setCond(2, job ? "10 分钟双路兜底" : "未登记", job ? 0 : 1);

        int dozeTotal = 0;
        int dozeOk = 0;
        String selfPkg = getPackageName();
        if (installed(selfPkg)) { dozeTotal++; if (ignoring(selfPkg)) dozeOk++; }
        if (svenInstalled) { dozeTotal++; if (ignoring("com.sven.mycar")) dozeOk++; }
        if (netaInstalled) { dozeTotal++; if (ignoring(NetaKeeper.PKG)) dozeOk++; }
        String dozeValue;
        int dozeState;
        if (dozeTotal == 0) { dozeValue = "—"; dozeState = 2; }
        else if (dozeOk == dozeTotal) { dozeValue = "全部豁免"; dozeState = 0; }
        else if (dozeOk == 0) { dozeValue = "未豁免"; dozeState = 2; }
        else { dozeValue = "部分豁免"; dozeState = 1; }
        setCond(3, dozeValue, dozeState);

        btnRearm.setText(off ? R.string.btn_restore : R.string.btn_heal);
        btnToggle.setText(off ? R.string.btn_enable : R.string.btn_disable);
        btnToggle.setTextColor(off ? C_OK : C_TEXT2);
    }

    private void applyPill(TextView tv, String text, int color) {
        tv.setText(text);
        tv.setTextColor(color == C_MUTED ? C_TEXT2 : color);
        tv.setBackgroundResource(pillBg(color));
    }

    private int pillBg(int color) {
        if (color == C_OK) return R.drawable.bg_pill_ok;
        if (color == C_WARN) return R.drawable.bg_pill_warn;
        if (color == C_ERR) return R.drawable.bg_pill_err;
        return R.drawable.bg_pill_neutral;
    }

    private void setCond(int index, String value, int state) {
        if (index < 0 || index >= condRows.size()) return;
        CondRow row = condRows.get(index);
        row.value.setText(value);
        int icon;
        int color;
        if (state == 0) {
            icon = R.drawable.ic_check_circle;
            color = C_OK;
        } else if (state == 1) {
            icon = R.drawable.ic_alert;
            color = C_WARN;
        } else {
            icon = R.drawable.ic_close_circle;
            color = C_ERR;
        }
        row.icon.setImageResource(icon);
        row.icon.setColorFilter(color);
        row.value.setTextColor(state == 0 ? C_TEXT : color);
    }

    // ---------- 小工具 ----------

    private String ago(long at) {
        long d = System.currentTimeMillis() - at;
        if (d < 45_000L) return "刚刚";
        if (d < 3_600_000L) return Math.max(1, d / 60_000L) + " 分钟前";
        if (d < 86_400_000L) return Math.max(1, d / 3_600_000L) + " 小时前";
        return Math.max(1, d / 86_400_000L) + " 天前";
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ignoring(String pkg) {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String norm(String comp) {
        if (comp == null) return "";
        android.content.ComponentName cn =
                android.content.ComponentName.unflattenFromString(comp);
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
                    for (AccessibilityServiceInfo i : l) {
                        if (i.getId() != null) s.add(norm(i.getId()));
                    }
                }
            }
        } catch (Exception ignored) {}
        return s;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------- 运行记录 ----------

    private void loadLastAction() {
        SharedPreferences sp = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        updateLog(sp.getString("last", getString(R.string.log_waiting)), sp.getLong("last_at", 0));
    }

    private void record(String msg) {
        long now = System.currentTimeMillis();
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putString("last", msg)
                .putLong("last_at", now)
                .apply();
        updateLog(msg, now);
    }

    private void updateLog(String msg, long at) {
        lastAction.setText(msg);
        if (at <= 0) {
            lastActionTime.setText(R.string.log_auto);
            return;
        }
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(at));
        lastActionTime.setText(time + "  ·  手动操作");
    }

    // ---------- 动效 ----------

    private void startPulse() {
        pulse = ObjectAnimator.ofFloat(headerDot, "alpha", 1f, 0.35f);
        pulse.setDuration(1250);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private void playEntrance() {
        View[] views = {
                findViewById(R.id.header),
                findViewById(R.id.heroCard),
                findViewById(R.id.sectionTargets),
                targetsContainer,
                findViewById(R.id.sectionConditions),
                conditionsContainer,
                findViewById(R.id.sectionUpdate),
                findViewById(R.id.updateCard),
                findViewById(R.id.sectionLog),
                findViewById(R.id.logCard),
                findViewById(R.id.footer)
        };
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(40L + i * 55L)
                    .setDuration(430)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        }
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (Rearm.userDisabled(this)) NetaKeeper.stop(this);
        else NetaKeeper.start(this);
        render(false);
        GuardWidgetProvider.refresh(this);
        if (refresher == null) refresher = new Refresher(this);
        h.removeCallbacks(refresher);
        h.postDelayed(refresher, 3000);

        if (installLaunched) {
            installLaunched = false;
            if (pendingUpdateApk != null) {
                btnUpdate.setEnabled(true);
                btnUpdate.setText(R.string.btn_install_update);
                updateHint.setText(R.string.update_install_ready);
            }
        }
        if (awaitingInstallPermission && pendingUpdateApk != null
                && pendingUpdateApk.isFile() && UpdateChecker.canInstall(this)) {
            awaitingInstallPermission = false;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!updateFinished() && pendingUpdateApk != null && !installLaunched) {
                        launchInstaller();
                    }
                }
            }, 350);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (refresher != null) h.removeCallbacks(refresher);
    }

    @Override
    protected void onDestroy() {
        if (pulse != null) pulse.cancel();
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static class Refresher implements Runnable {
        private final StatusActivity a;
        Refresher(StatusActivity a) { this.a = a; }
        @Override public void run() {
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            if (!a.busy) a.render(false);
            a.h.postDelayed(this, 3000);
        }
    }

    private static class TargetCard {
        final ImageView icon;
        final TextView name;
        final TextView pkg;
        final TextView pill;
        final TextView action;
        final TextView[] labels = new TextView[3];
        final TextView[] values = new TextView[3];
        final View[] dots = new View[3];

        TargetCard(View root) {
            icon = (ImageView) root.findViewById(R.id.targetIcon);
            name = (TextView) root.findViewById(R.id.targetName);
            pkg = (TextView) root.findViewById(R.id.targetPkg);
            pill = (TextView) root.findViewById(R.id.targetPill);
            action = (TextView) root.findViewById(R.id.targetAction);
            labels[0] = (TextView) root.findViewById(R.id.row1Label);
            values[0] = (TextView) root.findViewById(R.id.row1Value);
            dots[0] = root.findViewById(R.id.row1Dot);
            labels[1] = (TextView) root.findViewById(R.id.row2Label);
            values[1] = (TextView) root.findViewById(R.id.row2Value);
            dots[1] = root.findViewById(R.id.row2Dot);
            labels[2] = (TextView) root.findViewById(R.id.row3Label);
            values[2] = (TextView) root.findViewById(R.id.row3Value);
            dots[2] = root.findViewById(R.id.row3Dot);
        }

        void setRow(int i, String label, String value, int color) {
            labels[i].setText(label);
            values[i].setText(value);
            dots[i].setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    private static class CondRow {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView sub;
        final TextView value;

        CondRow(View root) {
            this.root = root;
            icon = (ImageView) root.findViewById(R.id.condIcon);
            title = (TextView) root.findViewById(R.id.condTitle);
            sub = (TextView) root.findViewById(R.id.condSub);
            value = (TextView) root.findViewById(R.id.condValue);
        }
    }
}
