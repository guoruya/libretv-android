package app.libretv.android;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReleaseUpdateSmokeTest {
    @Test public void settingsRequireConfirmationBeforeDownloading() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".debug"));
        android.content.SharedPreferences prefs = context.getSharedPreferences(AppUpdates.PREFS, 0);
        String previous = prefs.getString("channel", null);
        try (UpdateServer server = new UpdateServer(context)) {
            context.stopService(new android.content.Intent(context, UpdateDownloadService.class));
            prefs.edit().clear().putString("channel", server.base() + "/update.json").commit();
            try (androidx.test.core.app.ActivityScenario<MainActivity> activity = androidx.test.core.app.ActivityScenario.launch(MainActivity.class)) {
                assertTrue(awaitText("设置", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
                awaitText("当前版本 " + AppUpdates.installed(context).versionName, false);
                capture("settings-update.png");
                assertTrue(awaitText("检查更新", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
                awaitText("发现新版本 设置更新测试", false);
                AccessibilityNodeInfo confirm = awaitText("确认更新", true);
                assertEquals("Checking alone must not download the APK", 0, server.downloads.get());
                assertFalse(prefs.contains("downloadId"));
                capture("confirm-update.png");
                assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                awaitText("下载失败：下载服务器返回 503", false);
                assertEquals(1, server.downloads.get());
                awaitText("重新下载", true);
                assertTrue(awaitText("取消下载", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
                awaitText("已取消，可重新检查更新", false);
                assertTrue(awaitText("‹ 返回", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
                awaitText("设置", true);
            }
        } finally {
            context.stopService(new android.content.Intent(context, UpdateDownloadService.class));
            android.content.SharedPreferences.Editor edit = prefs.edit().clear();
            if (previous == null) edit.remove("channel"); else edit.putString("channel", previous);
            edit.commit();
        }
    }
    @Test public void installedReleaseChecksTheConfiguredUpdateChannel() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("releaseUpdate")));
        android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        if ("true".equals(InstrumentationRegistry.getArguments().getString("openInstalled")))
            assertTrue(awaitAnyText(new String[]{"Open", "打开"}, true, 60000).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        else shell("am start -W -f 0x10008000 -n app.libretv.android/.MainActivity");
        assertTrue(awaitText("设置", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        AccessibilityNodeInfo check = awaitText("检查更新", true); assertTrue(check.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("已是最新版本", false); ui.waitForIdle(500, 3000);
        android.graphics.Bitmap image = ui.takeScreenshot();
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "release-update.png");
        try(FileOutputStream out = new FileOutputStream(file)) { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); } finally { image.recycle(); }
    }
    @Test public void installedReleaseUpgradesFromPublicChannel() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("publicUpgrade")));
        String version = InstrumentationRegistry.getArguments().getString("publicVersion", "1.4.0");
        String code = InstrumentationRegistry.getArguments().getString("publicVersionCode", "8");
        shell("am start -W -f 0x10008000 -n app.libretv.android/.MainActivity");
        if ("true".equals(InstrumentationRegistry.getArguments().getString("fromSettings")))
            assertTrue(awaitText("设置", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        assertTrue(awaitText("检查更新", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        assertTrue(awaitText("更新地址", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("保存并检查", true);
        AccessibilityNodeInfo input = findInput(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
        assertNotNull(input);
        android.os.Bundle value = new android.os.Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "https://github.com/guoruya/libretv-android/releases/latest/download/update.json");
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value));
        assertTrue(awaitText("保存并检查", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("发现新版本 " + version, false);
        assertTrue(awaitAnyText(new String[]{"确认更新", "下载并安装"}, true, 60000).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        // The explicit opt-in test permits the system installer to replace the release app.
        assertTrue(awaitAnyText(new String[]{"Update", "更新"}, true, 120000).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        long end = System.currentTimeMillis() + 60000;
        while (!shell("dumpsys package app.libretv.android").contains("versionCode=" + code + " ")) {
            if (System.currentTimeMillis() >= end) fail("System installer did not update the release app");
            Thread.sleep(200);
        }
        assertTrue(awaitAnyText(new String[]{"Open", "打开"}, true, 60000).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        assertTrue(awaitText("设置", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        assertTrue(awaitText("检查更新", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("当前版本 " + version, false); awaitText("已是最新版本", false);
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "public-upgrade.png");
        try (FileOutputStream out = new FileOutputStream(file)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { screenshot.recycle(); }
    }
    private static String shell(String command) throws Exception {
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int read;
            while ((read = in.read(bytes)) != -1) out.write(bytes, 0, read);
            return out.toString("UTF-8");
        }
    }
    private void capture(String name) throws Exception {
        android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ui.waitForIdle(200, 2000);
        android.graphics.Bitmap bitmap = ui.takeScreenshot();
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
    }
    private static final class UpdateServer implements AutoCloseable {
        final java.net.ServerSocket socket = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        final java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        final byte[] manifest;
        UpdateServer(android.content.Context context) throws Exception {
            manifest = new org.json.JSONObject().put("applicationId", context.getPackageName())
                .put("versionCode", AppUpdates.versionCode(AppUpdates.installed(context)) + 1).put("versionName", "设置更新测试")
                .put("minSdk", 26).put("apkUrl", base() + "/candidate.apk").put("notes", "确认后才开始下载；验证下载失败时可以重试和取消。")
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            worker.execute(() -> {
                while (!socket.isClosed()) try (java.net.Socket client = socket.accept()) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    String request = input.readLine(); if (request == null) continue;
                    String line; while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    boolean apk = request.startsWith("GET /candidate.apk "); if (apk) downloads.incrementAndGet();
                    byte[] body = apk ? new byte[0] : manifest;
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 " + (apk ? "503 Service Unavailable" : "200 OK") + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.write(body); out.flush();
                } catch (IOException ignored) {}
            });
        }
        String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        public void close() throws IOException { socket.close(); worker.shutdownNow(); }
    }
    private AccessibilityNodeInfo findInput(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = findInput(node.getChild(i)); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo awaitText(String text, boolean clickable) throws Exception {
        return awaitAnyText(new String[]{text}, clickable, 60000);
    }
    private AccessibilityNodeInfo awaitAnyText(String[] texts, boolean clickable, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root != null) for(String text:texts) for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)) if(text.contentEquals(node.getText()==null?"":node.getText()) && node.isVisibleToUser() && node.isEnabled() && (!clickable || node.isClickable())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing: " + java.util.Arrays.toString(texts));
    }
}
