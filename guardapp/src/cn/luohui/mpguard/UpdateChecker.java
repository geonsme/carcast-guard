package cn.luohui.mpguard;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** GitHub Release 检查、下载与自更新安装。 */
final class UpdateChecker {

    private static final String TAG = "MpGuardUpdate";
    private static final String API_URL =
            "https://api.github.com/repos/geonsme/carcast-guard/releases/latest";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface CheckCallback {
        void onResult(ReleaseInfo release, boolean newer);
        void onError(String message);
    }

    interface DownloadCallback {
        void onProgress(int pct, long done, long total);
        void onReady(File apk);
        void onError(String message);
    }

    static final class ReleaseInfo {
        final String version;
        final String notes;
        final String url;
        final long size;

        ReleaseInfo(String version, String notes, String url, long size) {
            this.version = version;
            this.notes = notes;
            this.url = url;
            this.size = size;
        }
    }

    private UpdateChecker() {}

    static String currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    static void check(Context ctx, CheckCallback cb) {
        final String current = currentVersion(ctx);
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = open(API_URL);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    int code = conn.getResponseCode();
                    if (code == 404) {
                        fail(cb, "仓库尚未发布 Release");
                        return;
                    }
                    if (code == 403) {
                        fail(cb, "GitHub API 访问受限，请稍后再试");
                        return;
                    }
                    if (code != 200) {
                        fail(cb, "GitHub 返回 HTTP " + code);
                        return;
                    }
                    JSONObject root = new JSONObject(readAll(conn.getInputStream()));
                    String version = VersionUtil.normalize(root.optString("tag_name", ""));
                    if (version.isEmpty()) {
                        fail(cb, "Release 缺少版本号");
                        return;
                    }
                    if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
                        fail(cb, "最新 Release 尚未正式发布");
                        return;
                    }
                    String url = null;
                    long size = 0;
                    JSONArray assets = root.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) continue;
                            String name = asset.optString("name", "");
                            if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                                url = asset.optString("browser_download_url", "");
                                size = asset.optLong("size", 0);
                                break;
                            }
                        }
                    }
                    if (url == null || !url.startsWith("https://")) {
                        fail(cb, "Release 中没有可下载的 APK");
                        return;
                    }
                    final ReleaseInfo info = new ReleaseInfo(
                            version, root.optString("body", ""), url, size);
                    final boolean newer = VersionUtil.compare(version, current) > 0;
                    MAIN.post(new Runnable() {
                        @Override public void run() { cb.onResult(info, newer); }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "check failed", e);
                    fail(cb, friendly(e));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "guard-update-check").start();
    }

    static void download(Context ctx, ReleaseInfo release, DownloadCallback cb) {
        final File dir = new File(ctx.getCacheDir(), "updates");
        final File out = new File(dir, "mp-guard-" + safe(release.version) + ".apk");
        new Thread(new Runnable() {
            @Override public void run() {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    fail(cb, "无法创建更新缓存目录");
                    return;
                }
                if (out.isFile() && out.length() > 0
                        && (release.size <= 0 || out.length() == release.size)
                        && verify(ctx, out) == null) {
                    MAIN.post(new Runnable() {
                        @Override public void run() { cb.onReady(out); }
                    });
                    return;
                }
                if (out.exists()) out.delete();
                final File part = new File(dir, out.getName() + ".part");
                if (part.exists()) part.delete();
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(release.url);
                    if (!"https".equalsIgnoreCase(u.getProtocol())) {
                        fail(cb, "更新地址不是 HTTPS");
                        return;
                    }
                    conn = open(u.toString());
                    conn.setRequestProperty("Accept", "application/octet-stream");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        fail(cb, "下载返回 HTTP " + code);
                        return;
                    }
                    long total = release.size > 0 ? release.size : conn.getContentLength();
                    InputStream in = conn.getInputStream();
                    OutputStream os = new FileOutputStream(part);
                    byte[] buf = new byte[16 * 1024];
                    long done = 0;
                    int lastPct = Integer.MIN_VALUE;
                    long lastAt = 0;
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                            long now = System.currentTimeMillis();
                            if (pct != lastPct && (pct < 0 || now - lastAt >= 180 || pct == 100)) {
                                lastPct = pct;
                                lastAt = now;
                                final int p = pct;
                                final long d = done;
                                final long t = total;
                                MAIN.post(new Runnable() {
                                    @Override public void run() { cb.onProgress(p, d, t); }
                                });
                            }
                        }
                    } finally {
                        try { os.close(); } catch (Exception ignored) {}
                        try { in.close(); } catch (Exception ignored) {}
                    }
                    if (total > 0 && done != total) {
                        fail(cb, "下载不完整，请重试");
                        return;
                    }
                    if (out.exists()) out.delete();
                    if (!part.renameTo(out)) {
                        fail(cb, "无法保存安装包");
                        return;
                    }
                    String problem = verify(ctx, out);
                    if (problem != null) {
                        out.delete();
                        fail(cb, problem);
                        return;
                    }
                    MAIN.post(new Runnable() {
                        @Override public void run() { cb.onReady(out); }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    part.delete();
                    fail(cb, friendly(e));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "guard-update-download").start();
    }

    static boolean canInstall(Context ctx) {
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    static Intent installPermissionIntent(Context ctx) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName()));
    }

    static void install(Context ctx, File apk) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {
            throw new SecurityException("未获得安装未知应用权限");
        }
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            InputStream in = new FileInputStream(apk);
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            byte[] buf = new byte[16 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
            Intent result = new Intent(ctx, InstallResultReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, result, flags);
            session.commit(pending.getIntentSender());
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "MpGuard-Updater");
        return conn;
    }

    /** 包名、版本与签名都必须与当前安装包一致，避免安装被替换的 APK。 */
    private static String verify(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo current = pm.getPackageInfo(ctx.getPackageName(), flags);
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null) return "安装包无法解析";
            if (!ctx.getPackageName().equals(archive.packageName)) return "安装包包名不匹配";
            if (versionCode(archive) <= versionCode(current)) return "安装包版本不高于当前版本";
            byte[] currentCert = firstCert(current);
            byte[] archiveCert = firstCert(archive);
            if (currentCert == null || archiveCert == null) return "无法读取安装包签名";
            if (!MessageDigest.isEqual(currentCert, archiveCert)) {
                return "安装包签名不一致，已拒绝安装";
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "verify failed", e);
            return "安装包校验失败";
        }
    }

    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static byte[] firstCert(PackageInfo info) {
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signing = info.signingInfo;
            if (signing != null) {
                signatures = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
            }
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) return null;
        return signatures[0].toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String safe(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void fail(final CheckCallback cb, final String message) {
        MAIN.post(new Runnable() {
            @Override public void run() { cb.onError(message); }
        });
    }

    private static void fail(final DownloadCallback cb, final String message) {
        MAIN.post(new Runnable() {
            @Override public void run() { cb.onError(message); }
        });
    }

    private static String friendly(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "网络不可用，无法连接 GitHub";
        if (e instanceof java.net.SocketTimeoutException) return "连接 GitHub 超时，请重试";
        if (e instanceof javax.net.ssl.SSLException) return "GitHub TLS 连接失败";
        if (e instanceof org.json.JSONException) return "Release 数据解析失败";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "更新失败 · " + e.getClass().getSimpleName();
        }
        return message;
    }
}
