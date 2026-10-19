package cn.luohui.mpguard;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GitHub Release 检查、下载与自更新安装。 */
final class UpdateChecker {

    private static final String TAG = "MpGuardUpdate";
    private static final String REPO = "geonsme/carcast-guard";
    private static final String WEB_LATEST =
            "https://github.com/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_BASE =
            "https://github.com/" + REPO + "/releases/download/";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String API_PROXY_URL = "https://gh-proxy.com/" + API_URL;
    // 免费镜像在前、直连兜底；安装前仍会校验包名、版本与签名。
    private static final String[] DOWNLOAD_MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://gh.llkk.cc/"
    };
    private static final String PREFS = "guard_update";
    private static final long CHECK_CACHE_MS = 2 * 60 * 1000L;
    private static final long FAIL_COOLDOWN_MS = 20 * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MIRROR_CONNECT_TIMEOUT_MS = 8_000;
    private static final int MIRROR_READ_TIMEOUT_MS = 20_000;
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

    private static final class RateLimitedException extends Exception {
        RateLimitedException(String message) {
            super(message);
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
        final Context app = ctx.getApplicationContext();
        final String current = currentVersion(app);
        new Thread(new Runnable() {
            @Override public void run() {
                ReleaseInfo cached = loadCache(app, current);
                if (cached != null) {
                    Log.i(TAG, "check cache version=" + cached.version);
                    postResult(cb, cached, current);
                    return;
                }
                long wait = cooldownRemaining(app);
                if (wait > 0) {
                    fail(cb, "检查太频繁，请 " + ((wait + 999) / 1000) + " 秒后再试");
                    return;
                }

                ReleaseInfo info = null;
                Exception webError = null;
                Exception proxyError = null;
                Exception apiError = null;
                try {
                    info = resolveViaWeb();
                    Log.i(TAG, "check source=web version=" + info.version);
                } catch (Exception e) {
                    webError = e;
                    Log.w(TAG, "web check failed", e);
                    try {
                        info = fetchApi(API_PROXY_URL);
                        Log.i(TAG, "check source=proxy-api version=" + info.version);
                    } catch (Exception pe) {
                        proxyError = pe;
                        Log.w(TAG, "proxy api check failed", pe);
                        try {
                            info = fetchApi(API_URL);
                            Log.i(TAG, "check source=api version=" + info.version);
                        } catch (Exception ae) {
                            apiError = ae;
                            Log.w(TAG, "api check failed", ae);
                        }
                    }
                }

                if (info == null) {
                    markFailure(app);
                    fail(cb, describeFailure(apiError, proxyError, webError));
                    return;
                }

                if (info.size <= 0 && VersionUtil.compare(info.version, current) > 0) {
                    try {
                        ReleaseInfo rich = fetchApi(API_PROXY_URL);
                        if (rich.version.equals(info.version)) {
                            info = new ReleaseInfo(rich.version, rich.notes, info.url, rich.size);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "release enrich failed", e);
                    }
                }
                if (info.notes == null || info.notes.trim().isEmpty()) {
                    info = new ReleaseInfo(info.version,
                            "更新说明请查看 GitHub Release 页面。", info.url, info.size);
                }
                saveCache(app, current, info);
                postResult(cb, info, current);
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

                List<String> sources = downloadSources(release.url);
                Exception lastError = null;
                String verifyProblem = null;
                for (int i = 0; i < sources.size(); i++) {
                    String source = sources.get(i);
                    final File part = new File(dir, out.getName() + ".part");
                    if (part.exists()) part.delete();
                    Log.i(TAG, "download attempt " + (i + 1) + "/" + sources.size()
                            + " " + source);
                    try {
                        downloadFrom(source, part, out, release, cb);
                        String problem = verify(ctx, out);
                        if (problem == null) {
                            final File ready = out;
                            MAIN.post(new Runnable() {
                                @Override public void run() { cb.onReady(ready); }
                            });
                            return;
                        }
                        verifyProblem = problem;
                        out.delete();
                        Log.w(TAG, "verify failed from " + source + ": " + problem);
                    } catch (Exception e) {
                        lastError = e;
                        part.delete();
                        if (out.exists()) out.delete();
                        Log.w(TAG, "download failed from " + source, e);
                    }
                }

                if (verifyProblem != null) {
                    fail(cb, verifyProblem);
                } else if (lastError instanceof FileNotFoundException) {
                    fail(cb, "Release 中没有可下载的 APK");
                } else {
                    fail(cb, friendly(lastError));
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

    /** GitHub 的 latest 网页重定向不消耗 API 配额，是默认检测通道。 */
    private static ReleaseInfo resolveViaWeb() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = open(WEB_LATEST, MIRROR_CONNECT_TIMEOUT_MS, MIRROR_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                throw new IOException("GitHub 网页返回 HTTP " + code);
            }
            String location = conn.getHeaderField("Location");
            if (location == null || location.trim().isEmpty()) {
                throw new IOException("GitHub 未返回最新版本地址");
            }
            URL target = new URL(new URL(WEB_LATEST), location);
            String path = target.getPath();
            String marker = "/releases/tag/";
            int idx = path.indexOf(marker);
            if (idx < 0) throw new IOException("无法解析最新版本地址");
            String tag = URLDecoder.decode(path.substring(idx + marker.length()), "UTF-8");
            while (tag.endsWith("/")) tag = tag.substring(0, tag.length() - 1);
            if (!tag.matches("[A-Za-z0-9._-]+")) {
                throw new IOException("最新版本号格式异常");
            }
            String version = VersionUtil.normalize(tag);
            if (version.isEmpty()) throw new IOException("Release 缺少版本号");
            String assetUrl = DOWNLOAD_BASE + tag + "/mp-guard-" + tag + ".apk";
            return new ReleaseInfo(version, "", assetUrl, 0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static ReleaseInfo fetchApi(String apiUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = open(apiUrl, MIRROR_CONNECT_TIMEOUT_MS, MIRROR_READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code == 403 || code == 429) {
                throw rateLimited(conn, code);
            }
            if (code == 404) {
                throw new IOException("仓库尚未发布 Release");
            }
            if (code != 200) {
                throw new IOException("GitHub 返回 HTTP " + code);
            }
            JSONObject root = new JSONObject(readAll(conn.getInputStream()));
            String version = VersionUtil.normalize(root.optString("tag_name", ""));
            if (version.isEmpty()) {
                throw new IOException("Release 缺少版本号");
            }
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
                throw new IOException("最新 Release 尚未正式发布");
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
                throw new IOException("Release 中没有可下载的 APK");
            }
            return new ReleaseInfo(version, root.optString("body", ""), url, size);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static RateLimitedException rateLimited(HttpURLConnection conn, int code) {
        long waitSec;
        long reset = parseLong(conn.getHeaderField("X-RateLimit-Reset"));
        if (reset > 0) {
            waitSec = Math.max(0, reset - System.currentTimeMillis() / 1000);
        } else {
            waitSec = parseLong(conn.getHeaderField("Retry-After"));
        }
        String suffix = waitSec > 0
                ? "，约 " + Math.max(1, (waitSec + 59) / 60) + " 分钟后恢复"
                : "";
        String prefix = code == 429 ? "GitHub 请求过于频繁" : "GitHub 接口限额已用尽";
        return new RateLimitedException(prefix + suffix + "，请稍后重试");
    }

    private static void downloadFrom(String source, File part, File out,
                                     ReleaseInfo release, DownloadCallback cb) throws Exception {
        URL u = new URL(source);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IOException("更新地址不是 HTTPS");
        }
        HttpURLConnection conn = null;
        try {
            conn = open(u.toString(), MIRROR_CONNECT_TIMEOUT_MS, MIRROR_READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/octet-stream");
            int code = conn.getResponseCode();
            if (code == 404) {
                throw new FileNotFoundException("下载返回 HTTP 404");
            }
            if (code != 200) {
                throw new IOException("下载返回 HTTP " + code);
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
                throw new IOException("下载不完整");
            }
            if (out.exists()) out.delete();
            if (!part.renameTo(out)) {
                throw new IOException("无法保存安装包");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<String> downloadSources(String direct) {
        List<String> sources = new ArrayList<>();
        if (direct != null && direct.startsWith("https://github.com/")) {
            for (String mirror : DOWNLOAD_MIRRORS) {
                sources.add(mirror + direct);
            }
        }
        if (direct != null && !direct.isEmpty()) sources.add(direct);
        return sources;
    }

    private static ReleaseInfo loadCache(Context ctx, String current) {
        SharedPreferences p = prefs(ctx);
        long at = p.getLong("checked_at", 0);
        if (at <= 0 || System.currentTimeMillis() - at > CHECK_CACHE_MS) return null;
        if (!current.equals(p.getString("base_version", ""))) return null;
        String version = p.getString("version", "");
        String url = p.getString("url", "");
        if (version.isEmpty() || !url.startsWith("https://")) return null;
        return new ReleaseInfo(version, p.getString("notes", ""), url,
                p.getLong("size", 0));
    }

    private static void saveCache(Context ctx, String current, ReleaseInfo info) {
        prefs(ctx).edit()
                .putLong("checked_at", System.currentTimeMillis())
                .putString("base_version", current)
                .putString("version", info.version)
                .putString("notes", info.notes)
                .putString("url", info.url)
                .putLong("size", info.size)
                .remove("failed_at")
                .apply();
    }

    private static void markFailure(Context ctx) {
        prefs(ctx).edit()
                .putLong("failed_at", System.currentTimeMillis())
                .apply();
    }

    private static long cooldownRemaining(Context ctx) {
        long at = prefs(ctx).getLong("failed_at", 0);
        long elapsed = System.currentTimeMillis() - at;
        if (elapsed < 0 || elapsed >= FAIL_COOLDOWN_MS) return 0;
        return FAIL_COOLDOWN_MS - elapsed;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void postResult(final CheckCallback cb, final ReleaseInfo info,
                                   final String current) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                cb.onResult(info, VersionUtil.compare(info.version, current) > 0);
            }
        });
    }

    private static String describeFailure(Exception apiError, Exception proxyError,
                                          Exception webError) {
        Exception primary = apiError;
        if (proxyError instanceof RateLimitedException) primary = proxyError;
        if (apiError instanceof RateLimitedException) primary = apiError;
        if (primary != null) {
            String message = primary.getMessage();
            if (message != null && !message.trim().isEmpty()) return message;
        }
        if (proxyError != null) {
            String message = proxyError.getMessage();
            if (message != null && !message.trim().isEmpty()) return message;
        }
        return friendly(webError);
    }

    private static long parseLong(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        return open(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static HttpURLConnection open(String url, int connectTimeout, int readTimeout)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
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
        if (e == null) return "更新失败，请稍后重试";
        if (e instanceof java.net.UnknownHostException) return "网络不可用，无法连接更新服务";
        if (e instanceof java.net.SocketTimeoutException) return "连接更新服务超时，请重试";
        if (e instanceof javax.net.ssl.SSLException) return "TLS 连接失败，请检查网络";
        if (e instanceof org.json.JSONException) return "Release 数据解析失败";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "更新失败 · " + e.getClass().getSimpleName();
        }
        return message;
    }
}
