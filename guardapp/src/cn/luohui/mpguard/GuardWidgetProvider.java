package cn.luohui.mpguard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** 2×1 桌面状态磁贴：只显示总守护状态，点击进入控制台。 */
public class GuardWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        refresh(context);
    }

    public static void refresh(Context ctx) {
        if (ctx == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, GuardWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            mgr.updateAppWidget(ids, build(ctx));
        } catch (Exception ignored) {}
    }

    private static RemoteViews build(Context ctx) {
        boolean off = Rearm.userDisabled(ctx);
        boolean perm = Rearm.hasWritePerm(ctx);
        boolean master = Rearm.masterOn(ctx);
        boolean selfIn = Rearm.contains(Rearm.readList(ctx), Rearm.SELF);
        boolean selfLoaded = GuardService.sConnected;
        boolean netaInstalled = NetaKeeper.isInstalled(ctx);
        boolean netaBound = NetaKeeper.isBound();

        String text;
        int color;
        if (off) { text = "已停用"; color = ctx.getColor(R.color.widget_muted); }
        else if (!perm) { text = "待授权"; color = ctx.getColor(R.color.widget_err); }
        else if (!master || !selfIn || !selfLoaded) { text = "待激活"; color = ctx.getColor(R.color.widget_err); }
        else if (!netaInstalled) { text = "仅投屏"; color = ctx.getColor(R.color.widget_warn); }
        else if (!netaBound) { text = "重连中"; color = ctx.getColor(R.color.widget_warn); }
        else { text = "守护中"; color = ctx.getColor(R.color.widget_accent); }

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_guard);
        rv.setTextViewText(R.id.widgetStatus, text);
        rv.setTextColor(R.id.widgetStatus, color);
        rv.setInt(R.id.widgetIcon, "setColorFilter", color);
        rv.setContentDescription(R.id.widgetRoot, "车控守护，" + text);

        Intent open = new Intent(ctx, StatusActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widgetRoot, pi);
        rv.setOnClickPendingIntent(R.id.widgetStatus, pi);
        return rv;
    }
}
