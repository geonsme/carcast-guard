package cn.luohui.mpguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 持久化定时任务：JobScheduler 的任务由框架在开机时自行恢复，
 * 不依赖被 ColorOS 拦掉的 BOOT_COMPLETED 广播。
 */
public class RearmJobService extends JobService {
    private static final String TAG = "MpGuard";
    public static final int JOB_ID = 4321;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "定时任务触发，执行自愈");
        if (Rearm.userDisabled(this)) {
            Log.i(TAG, "用户已停用，跳过自愈");
            schedule(this);
            return false;
        }
        int n = Rearm.rearm(this);
        Log.i(TAG, "自愈结果: " + n);
        schedule(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    public static boolean isScheduled(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return false;
            java.util.List<JobInfo> jobs = js.getAllPendingJobs();
            if (jobs == null) return false;
            for (JobInfo j : jobs) if (j.getId() == JOB_ID) return true;
            return false;
        } catch (Exception e) { return false; }
    }

    public static void cancel(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) {}
    }

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RearmJobService.class))
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiresCharging(false)
                    .build();
            int r = js.schedule(job);
            Log.i(TAG, "schedule 返回 " + r);
        } catch (Exception e) {
            Log.w(TAG, "schedule 失败: " + e);
        }
    }
}
