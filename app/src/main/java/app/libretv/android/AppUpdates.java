package app.libretv.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

final class AppUpdates {
    static final String PREFS = "app_updates";
    static final class Release {
        final int code, minSdk;
        final String name, url, notes, packageName;
        Release(JSONObject data) throws Exception {
            code = positiveInt(data, "versionCode"); minSdk = positiveInt(data, "minSdk");
            name = data.getString("versionName").trim(); url = data.getString("apkUrl").trim();
            packageName = data.getString("applicationId"); notes = data.optString("notes");
            if (name.isEmpty() || name.length() > 80 || notes.length() > 16000 || packageName.isEmpty() || !validUrl(url))
                throw new IOException("更新信息不完整或下载地址无效");
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("versionCode", code).put("versionName", name).put("minSdk", minSdk)
                .put("applicationId", packageName).put("apkUrl", url).put("notes", notes);
        }
    }
    private static int positiveInt(JSONObject data, String key) throws Exception {
        Object value = data.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue() || ((Number) value).intValue() <= 0)
            throw new IOException("无效的更新版本信息：" + key);
        return ((Number) value).intValue();
    }
    static boolean validUrl(String text) {
        try {
            URI uri = new URI(text);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            if ("https".equals(uri.getScheme())) return true;
            if (!"http".equals(uri.getScheme())) return false;
            // Plain HTTP is only for an explicitly configured local update server.
            String[] parts = uri.getHost().split("\\."); if (parts.length != 4) return false;
            int[] ip = new int[4];
            for (int i = 0; i < 4; i++) { if (!parts[i].matches("[0-9]{1,3}")) return false; ip[i] = Integer.parseInt(parts[i]); if (ip[i] > 255) return false; }
            return ip[0] == 10 || ip[0] == 127 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31;
        } catch (Exception e) { return false; }
    }
    static Release fetch(String address) throws Exception {
        if (!validUrl(address)) throw new IOException("请使用 HTTPS 更新地址，或同一 Wi-Fi 内的 IP 地址");
        boolean secure = address.startsWith("https:");
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (!validUrl(address) || secure && !address.startsWith("https:")) throw new IOException("更新地址发生了不安全的跳转");
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "LibreTV-Android-Updater");
            connection.setUseCaches(false);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("更新地址没有提供跳转目标");
                    address = new URL(new URL(address), location).toString(); continue;
                }
                if (code != 200) throw new IOException("更新服务器返回 " + code + "，请稍后重试");
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n; long deadline = System.nanoTime() + 20000000000L;
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("检查更新超时");
                        if (out.size() + n > 256 * 1024) throw new IOException("更新信息过大");
                        out.write(buffer, 0, n);
                    }
                    return new Release(new JSONObject(out.toString(StandardCharsets.UTF_8.name())));
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("更新地址跳转次数过多");
    }
    interface Progress { void update(long received, long total) throws IOException; }
    static void download(Release release, File destination, Progress progress) throws Exception {
        String address = release.url; boolean secure = address.startsWith("https:");
        File partial = File.createTempFile("update-", ".part", destination.getParentFile());
        try {
            for (int redirect = 0; redirect <= 5; redirect++) {
                if (!validUrl(address) || secure && !address.startsWith("https:")) throw new IOException("下载地址发生了不安全的跳转");
                HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "LibreTV-Android-Updater"); connection.setRequestProperty("Accept-Encoding", "identity");
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location"); if (location == null) throw new IOException("下载地址没有提供跳转目标");
                        address = new URL(new URL(address), location).toString(); continue;
                    }
                    if (status != 200) throw new IOException("下载服务器返回 " + status);
                    long total = connection.getContentLengthLong(), received = 0, deadline = System.nanoTime() + 900000000000L;
                    if (total > 256L * 1024 * 1024) throw new IOException("安装包过大");
                    try (InputStream in = connection.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(partial)) {
                        byte[] buffer = new byte[32768]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                            if (System.nanoTime() > deadline) throw new IOException("下载超时，请重试");
                            received += n; if (received > 256L * 1024 * 1024) throw new IOException("安装包过大");
                            out.write(buffer, 0, n); progress.update(received, total);
                        }
                        if (received == 0 || total >= 0 && received != total) throw new IOException("安装包未下载完整，请重试");
                    }
                    if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                    if (!partial.renameTo(destination)) throw new IOException("无法保存安装包");
                    return;
                } finally { connection.disconnect(); }
            }
            throw new IOException("下载地址跳转次数过多");
        } finally { partial.delete(); }
    }
    static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlags());
    }
    private static int signingFlags() { return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES; }
    private static Signature[] signers(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
    }
    static File file(Context context, Release release) { return new File(context.getExternalFilesDir(null), "updates/LibreTV-" + release.code + ".apk"); }
    static void verify(Context context, Release release, File file) throws Exception {
        PackageInfo current = installed(context), apk = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signingFlags());
        if (apk == null) throw new IOException("下载的文件不是完整的 APK，请重新下载");
        if (!context.getPackageName().equals(release.packageName) || !context.getPackageName().equals(apk.packageName)) throw new IOException("安装包不属于这个应用");
        if (versionCode(apk) != release.code || versionCode(apk) <= versionCode(current)) throw new IOException("安装包版本与更新信息不符，或已经安装过此版本");
        if (apk.applicationInfo == null || apk.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("此更新不支持当前系统版本");
        Signature[] existing = signers(current), incoming = signers(apk);
        if (existing == null || incoming == null || existing.length == 0 || !new HashSet<>(Arrays.asList(existing)).equals(new HashSet<>(Arrays.asList(incoming))))
            throw new IOException("安装包签名不一致，不能覆盖安装");
    }
}
