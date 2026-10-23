package app.libretv.android;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UpdateSmokeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @org.junit.Before public void requiresExplicitUpdateFixture() {
        org.junit.Assume.assumeTrue("Run the documented local APK update fixture explicitly", "true".equals(InstrumentationRegistry.getArguments().getString("updateFixture")));
        assertTrue(context.getPackageName().endsWith(".debug"));
        android.content.SharedPreferences prefs = context.getSharedPreferences(AppUpdates.PREFS, 0);
        context.stopService(new Intent(context, UpdateDownloadService.class));
        prefs.edit().remove("downloadId").remove("pending").apply();
        assertTrue("Prepare the candidate APK as documented in TESTING.md", fixture("candidate.apk").isFile());
    }
    private File fixture(String name) { return new File(context.getExternalFilesDir(null), "update-test/" + name); }
    private JSONObject metadata(String base, int code) throws Exception {
        return new JSONObject().put("applicationId", context.getPackageName()).put("versionCode", code).put("versionName", "1.3.1-test").put("minSdk", 26)
            .put("apkUrl", base + "/candidate.apk").put("notes", "本地更新验证");
    }
    @Test public void rejectsWrongSignerPackageVersionAndDamagedFiles() throws Exception {
        AppUpdates.Release release = new AppUpdates.Release(metadata("http://127.0.0.1", 6));
        AppUpdates.verify(context, release, fixture("candidate.apk"));
        assertThrows(Exception.class, () -> AppUpdates.verify(context, release, fixture("wrong-signer.apk")));
        assertThrows(Exception.class, () -> AppUpdates.verify(context, release, fixture("other-package.apk")));
        assertThrows(Exception.class, () -> AppUpdates.verify(context, new AppUpdates.Release(metadata("http://127.0.0.1", 7)), fixture("candidate.apk")));
        File damaged = fixture("damaged.apk"); try (FileOutputStream out = new FileOutputStream(damaged)) { out.write(new byte[]{1,2,3}); }
        assertThrows(Exception.class, () -> AppUpdates.verify(context, release, damaged));
        Log.i("LibreTVUpdateTest", "Rejected wrong signer, wrong package, incorrect version and damaged APK");
    }
    @Test public void checkDownloadReopenCancelLatestAndNetworkFailure() throws Exception {
        try (Server server = new Server()) {
            context.getSharedPreferences(AppUpdates.PREFS, 0).edit().putString("channel", server.base() + "/update.json").apply();
            try (ActivityScenario<UpdateActivity> scenario = ActivityScenario.launch(UpdateActivity.class)) {
                await(() -> value(scenario, "release") != null && !(boolean) value(scenario, "busy"));
                scenario.onActivity(a -> { call(a, "startDownload"); set(a, "installAfterDownload", false); });
                await(() -> { if ((boolean) value(scenario, "failed")) fail(status(scenario)); return (boolean) value(scenario, "ready"); });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 3000);
                android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "v13-update-ready.png"))) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { screenshot.recycle(); }
                long id = (long) value(scenario, "downloadId"); assertTrue(id >= 0);
                scenario.recreate(); await(() -> { if ((boolean) value(scenario, "failed")) fail(status(scenario)); return (boolean) value(scenario, "ready"); }); assertEquals(id, value(scenario, "downloadId"));
                scenario.onActivity(a -> { assertTrue(((android.view.View) field(a, "action")).isShown()); call(a, "clearDownload"); });
                assertFalse(AppUpdates.file(context, new AppUpdates.Release(metadata(server.base(), 6))).exists());
                context.getSharedPreferences(AppUpdates.PREFS, 0).edit().putString("channel", server.base() + "/current.json").apply();
                scenario.onActivity(a -> call(a, "checkUpdate")); await(() -> !(boolean) value(scenario, "busy"));
                assertNull(value(scenario, "release")); assertEquals("已是最新版本", status(scenario));
                context.getSharedPreferences(AppUpdates.PREFS, 0).edit().putString("channel", server.base() + "/fail.json").apply();
                scenario.onActivity(a -> call(a, "checkUpdate")); await(() -> !(boolean) value(scenario, "busy"));
                assertTrue(status(scenario).contains("503")); assertNull(value(scenario, "release"));
            }
        }
    }
    @Test public void installUpdateFixture() throws Exception {
        Store store = new Store(context); Library.Video video = savedVideo();
        if (!store.favorite(video)) store.toggleFavorite(video); store.save(video, "测试线路", 2, "第3集", 42000);
        try (Server server = new Server(); ActivityScenario<UpdateActivity> scenario = start(server)) {
            await(() -> value(scenario, "release") != null && !(boolean) value(scenario, "busy"));
            scenario.onActivity(a -> { call(a, "startDownload"); set(a, "installAfterDownload", false); });
            await(() -> { if ((boolean) value(scenario, "failed")) fail(status(scenario)); return (boolean) value(scenario, "ready"); });
            scenario.onActivity(a -> call(a, "install"));
            AtomicReference<android.view.accessibility.AccessibilityNodeInfo> confirm = new AtomicReference<>();
            await(() -> {
                android.view.accessibility.AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
                if (root == null) return false;
                for (String label : new String[]{"Update", "Install", "更新", "安装"}) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
                    if (node.isClickable() && label.contentEquals(node.getText() == null ? "" : node.getText())) { confirm.set(node); return true; }
                return false;
            });
            Log.i("LibreTVUpdateTest", "SYSTEM_INSTALL_CONFIRMATION_ACCEPTED");
            assertTrue(confirm.get().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            Thread.sleep(5000); // Replacing the target package ends this instrumentation process.
        }
    }
    @Test public void verifyDataAfterSystemUpgrade() throws Exception {
        assertEquals(6, AppUpdates.versionCode(AppUpdates.installed(context)));
        Store store = new Store(context); assertTrue(store.favorite(savedVideo())); assertEquals(42000, store.progress(savedVideo()).optLong("position")); assertEquals(2, store.progress(savedVideo()).optInt("episode"));
        Log.i("LibreTVUpdateTest", "UPGRADE_VERSION_6_PRESERVED_FAVORITE_AND_PROGRESS");
    }
    private ActivityScenario<UpdateActivity> start(Server server) {
        context.getSharedPreferences(AppUpdates.PREFS, 0).edit().putString("channel", server.base() + "/update.json").apply();
        return ActivityScenario.launch(new Intent(context, UpdateActivity.class));
    }
    private Library.Video savedVideo() throws Exception { return new Library.Video(new Library.Source("更新测试", "https://example.com/api"), new JSONObject().put("vod_id", "update-preserve").put("vod_name", "更新保留验证")); }
    private String status(ActivityScenario<UpdateActivity> scenario) { AtomicReference<String> s = new AtomicReference<>(); scenario.onActivity(a -> s.set(((android.widget.TextView) field(a,"status")).getText().toString())); return s.get(); }
    private Object value(ActivityScenario<UpdateActivity> scenario, String name) { AtomicReference<Object> result = new AtomicReference<>(); scenario.onActivity(a -> result.set(field(a,name))); return result.get(); }
    private static Object field(Object target, String name) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(Exception e) { throw new AssertionError(e); } }
    private static void set(Object target, String name, Object value) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); } catch(Exception e) { throw new AssertionError(e); } }
    private static void call(Object target, String name) { try { Method m=target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch(Exception e) { throw new AssertionError(e); } }
    private static void await(Callable<Boolean> condition) throws Exception { long end=System.currentTimeMillis()+30000; while(System.currentTimeMillis()<end) { if(condition.call()) return; Thread.sleep(100); } fail("Timed out waiting for updater"); }
    private final class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")); final ExecutorService pool = Executors.newCachedThreadPool(); volatile boolean closed;
        Server() throws Exception { pool.execute(() -> { while(!closed) try { Socket client=socket.accept(); pool.execute(() -> serve(client)); } catch(IOException ignored) {} }); }
        String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        void serve(Socket client) {
            try(client) {
                BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII)); String first=reader.readLine(); if(first==null)return; String route=first.split(" ")[1], line;
                while((line=reader.readLine())!=null&&!line.isEmpty()) {}
                boolean apk=route.equals("/candidate.apk"); int status=route.equals("/fail.json")?503:200;
                byte[] data=apk?null:metadata(base(),route.equals("/current.json")?5:6).toString().getBytes(StandardCharsets.UTF_8); long length=apk?fixture("candidate.apk").length():data.length;
                OutputStream out=client.getOutputStream(); out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: "+(apk?"application/vnd.android.package-archive":"application/json")+"\r\nContent-Length: "+length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if(apk) try(InputStream in=new FileInputStream(fixture("candidate.apk"))) { byte[] b=new byte[16384]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); } else out.write(data); out.flush();
            } catch(Exception ignored) {}
        }
        public void close() throws IOException { closed=true; socket.close(); pool.shutdownNow(); }
    }
}
