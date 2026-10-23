package app.libretv.android;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class FeatureSmokeTest {
    @Test public void fullscreenControlsLockResizeAndPipRetainPlayback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(context);
        try (FixtureServer server = new FixtureServer(InstrumentationRegistry.getInstrumentation().getContext())) {
            Library.Video video = new Library.Video(new Library.Source("播放器测试", server.api()), new JSONObject().put("vod_id", "controls").put("vod_name", "全屏控件验证"));
            store.skip(video, 0, 0); store.autoNext(false);
            try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(new Intent(context, PlayerActivity.class).putExtra("video", video.json().toString()))) {
                awaitPlayer(scenario, 1000);
                scenario.onActivity(a -> ((ExoPlayer) field(a, "player")).pause());
                capture("v12-player.png");
                AtomicBoolean fullscreenFrame = new AtomicBoolean();
                scenario.onActivity(a -> {
                    ((ExoPlayer) field(a, "player")).addListener(new androidx.media3.common.Player.Listener() {
                        @Override public void onRenderedFirstFrame() { fullscreenFrame.set(true); }
                    });
                    androidx.media3.ui.PlayerView view = (androidx.media3.ui.PlayerView) field(a, "playerView");
                    view.showController();
                    android.view.View button = view.findViewById(androidx.media3.ui.R.id.exo_fullscreen);
                    assertNotNull(button); assertEquals(android.view.View.VISIBLE, button.getVisibility()); assertTrue(button.performClick());
                });
                await(() -> { AtomicBoolean full = new AtomicBoolean(); scenario.onActivity(a -> full.set((boolean) field(a, "fullScreen"))); return full.get(); }, 10000);
                if (android.os.Build.VERSION.SDK_INT >= 30) await(() -> {
                    AtomicBoolean immersive = new AtomicBoolean(); scenario.onActivity(a -> {
                        android.view.WindowInsets insets = a.getWindow().getDecorView().getRootWindowInsets();
                        immersive.set(insets != null && !insets.isVisible(android.view.WindowInsets.Type.systemBars()));
                    }); return immersive.get();
                }, 5000);
                await(fullscreenFrame::get, 5000);
                capture("v12-fullscreen.png");
                scenario.onActivity(a -> ((ExoPlayer) field(a, "player")).play());
                await(() -> { AtomicBoolean hidden = new AtomicBoolean(); scenario.onActivity(a -> hidden.set(!((androidx.media3.ui.PlayerView) field(a, "playerView")).isControllerFullyVisible() && !a.getWindow().getDecorView().findViewWithTag("player-menu").isShown())); return hidden.get(); }, 6000);
                capture("v13-fullscreen-playing.png");
                scenario.onActivity(a -> {
                    ExoPlayer player = (ExoPlayer) field(a, "player"); assertTrue(player.isPlaying());
                    androidx.media3.ui.PlayerView view = (androidx.media3.ui.PlayerView) field(a, "playerView");
                    view.performClick(); assertTrue(view.isControllerFullyVisible()); assertTrue(a.getWindow().getDecorView().findViewWithTag("player-menu").isShown());
                    assertTrue("Tapping the picture must not pause playback", player.isPlaying()); player.pause();
                });
                Thread.sleep(3400);
                scenario.onActivity(a -> { assertTrue(((androidx.media3.ui.PlayerView) field(a, "playerView")).isControllerFullyVisible()); assertTrue(a.getWindow().getDecorView().findViewWithTag("player-menu").isShown()); });
                capture("v13-fullscreen-paused.png");

                scenario.onActivity(a -> invoke(a, "chooseResizeMode", new Class<?>[]{}));
                AtomicReference<android.view.accessibility.AccessibilityNodeInfo> resizeChoice = new AtomicReference<>();
                await(() -> {
                    android.view.accessibility.AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
                    if (root == null) return false;
                    List<android.view.accessibility.AccessibilityNodeInfo> fill = root.findAccessibilityNodeInfosByText("铺满屏幕");
                    if (fill.isEmpty()) return false; resizeChoice.set(fill.get(0)); return true;
                }, 5000);
                assertTrue(resizeChoice.get().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a -> {
                    ExoPlayer player = (ExoPlayer) field(a, "player"); assertTrue(player.getCurrentPosition() >= 1000);
                    assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, ((androidx.media3.ui.PlayerView) field(a, "playerView")).getResizeMode());
                    invoke(a, "toggleLock", new Class<?>[]{}); assertTrue((boolean) field(a, "screenLocked")); assertFalse(((androidx.media3.ui.PlayerView) field(a, "playerView")).getUseController());
                    player.play();
                });
                await(() -> { AtomicBoolean hidden = new AtomicBoolean(); scenario.onActivity(a -> hidden.set(!((android.view.View) field(a, "unlockButton")).isShown())); return hidden.get(); }, 6000);
                scenario.onActivity(a -> {
                    a.getWindow().getDecorView().findViewWithTag("lock-touch").performClick(); assertTrue(((android.view.View) field(a, "unlockButton")).isShown());
                    assertTrue(((ExoPlayer) field(a, "player")).isPlaying());
                    a.onBackPressed(); assertFalse((boolean) field(a, "screenLocked")); assertTrue((boolean) field(a, "fullScreen"));
                    invoke(a, "selectEpisode", new Class<?>[]{int.class}, 1); assertTrue((boolean) field(a, "fullScreen"));
                });
                awaitPlayer(scenario, 500);
                scenario.recreate(); awaitPlayer(scenario, 1000);
                scenario.onActivity(a -> { assertTrue((boolean) field(a, "fullScreen")); assertEquals(1, field(a, "episodeIndex")); a.onBackPressed(); assertFalse((boolean) field(a, "fullScreen")); });
                if (context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    AtomicReference<PlayerActivity> activity = new AtomicReference<>();
                    scenario.onActivity(a -> { activity.set(a); invoke(a, "enterPip", new Class<?>[]{}); });
                    await(() -> { AtomicBoolean ok = new AtomicBoolean(); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ok.set(activity.get().isInPictureInPictureMode() && ((ExoPlayer) field(activity.get(), "player")).isPlaying())); return ok.get(); }, 10000);
                    AtomicReference<Long> before = new AtomicReference<>(); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> before.set(((ExoPlayer) field(activity.get(), "player")).getCurrentPosition()));
                    await(() -> { AtomicBoolean advanced = new AtomicBoolean(); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> advanced.set(((ExoPlayer) field(activity.get(), "player")).getCurrentPosition() > before.get() + 500)); return advanced.get(); }, 10000);
                }
                Log.i("LibreTVSmoke", "Fullscreen idle/tap/pause, lock/back, episodes, recreation and PiP continued playback passed");
            } finally { store.autoNext(true); }
        }
    }
    private static void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 3000);
        android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }
    @Test public void realRankingsAndTabletNavigation() throws Exception {
        for (int i = 0; i < Discovery.RANKS.length; i++) {
            List<Discovery.Item> items = Discovery.fetch("榜单", i);
            assertFalse(Discovery.RANKS[i] + " empty", items.isEmpty());
            Log.i("LibreTVSmoke", "RANK " + Discovery.RANKS[i] + " count=" + items.size());
        }
        for (String section : new String[]{"电影", "剧集", "新番"}) {
            List<Discovery.Item> items = Discovery.fetch(section, 0);
            if (!section.equals("新番")) assertFalse(section + " empty", items.isEmpty());
            Log.i("LibreTVSmoke", "CATEGORY " + section + " count=" + items.size());
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(() -> { AtomicBoolean loaded = new AtomicBoolean(); scenario.onActivity(a -> loaded.set(!((List<?>) field(a, "recommendations")).isEmpty())); return loaded.get(); }, 45000);
            scenario.onActivity(a -> invoke(a, "searchTitle", new Class<?>[]{String.class}, "大闹天宫"));
            await(() -> { AtomicBoolean loaded = new AtomicBoolean(); scenario.onActivity(a -> loaded.set(!(boolean) field(a, "loading"))); return loaded.get(); }, 60000);
            scenario.onActivity(a -> {
                List<?> groups = (List<?>) field(a, "groups"); assertFalse(groups.isEmpty());
                Log.i("LibreTVSmoke", "SEARCH groups=" + groups.size());
            });
        }
    }
    @Test public void favoritesSettingsAndOldProgressSurviveReopen() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(context);
        Library.Video sample = new Library.Video(new Library.Source("Fixture", "https://example.com/api"), new JSONObject().put("vod_id", "feature-test").put("vod_name", "测试收藏"));
        if (store.favorite(sample)) store.toggleFavorite(sample);
        store.save(sample, "HLS", 2, "第3集", 93000); store.toggleFavorite(sample); store.autoNext(false); store.skip(sample, 12, 20);
        Store reopened = new Store(context); assertTrue(reopened.favorite(sample)); assertEquals(93000, reopened.progress(sample).optLong("position")); assertEquals(2, reopened.progress(sample).optInt("episode")); assertFalse(reopened.autoNext()); assertEquals(12, reopened.skip(sample, "intro"));
        Library.Video otherSource = new Library.Video(new Library.Source("Other", "https://other.example/api"), sample.json());
        assertTrue(reopened.favorite(otherSource)); reopened.toggleFavorite(otherSource); reopened.autoNext(true); reopened.skip(sample, 0, 0);
        assertFalse(new Store(context).favorite(sample));
    }
    @Test public void hlsPauseResumeDownloadAndOfflinePlayback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); Context assets = InstrumentationRegistry.getInstrumentation().getContext();
        try (FixtureServer server = new FixtureServer(assets); ActivityScenario<MainActivity> home = ActivityScenario.launch(MainActivity.class)) {
            String url = server.url(); Library.Video video = new Library.Video(new Library.Source("离线测试", "https://unreachable.invalid/api"), new JSONObject().put("vod_id", "offline-fixture").put("vod_name", "离线播放验证"));
            AtomicReference<DownloadManager> manager = new AtomicReference<>();
            home.onActivity(a -> { manager.set(Downloads.manager(a)); try { Downloads.add(a, video, new Library.Episode("测试集", url)); } catch (Exception e) { throw new AssertionError(e); } });
            await(() -> download(manager.get(), url) != null && download(manager.get(), url).state == Download.STATE_DOWNLOADING, 20000);
            home.onActivity(a -> DownloadService.sendSetStopReason(a, OfflineService.class, url, 1, false));
            await(() -> download(manager.get(), url).state == Download.STATE_STOPPED, 15000);
            home.onActivity(a -> DownloadService.sendSetStopReason(a, OfflineService.class, url, 0, false));
            await(() -> { Download d = download(manager.get(), url); if (d.state == Download.STATE_FAILED) fail("HLS download failed"); return d.state == Download.STATE_COMPLETED; }, 60000);
            Download complete = download(manager.get(), url); assertTrue(complete.getBytesDownloaded() > 10000); Log.i("LibreTVSmoke", "DOWNLOADED bytes=" + complete.getBytesDownloaded());
            server.close();
            Intent intent = new Intent(context, PlayerActivity.class).putExtra("video", video.json().toString()).putExtra("offlineUrl", url).putExtra("offlineEpisode", "测试集");
            try (ActivityScenario<PlayerActivity> player = ActivityScenario.launch(intent)) {
                awaitPlayer(player, 2500); player.recreate(); awaitPlayer(player, 3000);
                player.onActivity(a -> { ExoPlayer p = (ExoPlayer) field(a, "player"); Log.i("LibreTVSmoke", "OFFLINE_PLAYED " + p.getVideoSize().width + "x" + p.getVideoSize().height + " position=" + p.getCurrentPosition()); assertEquals(320, p.getVideoSize().width); p.stop(); DownloadService.sendRemoveDownload(a, OfflineService.class, url, false); });
            }
            await(() -> download(manager.get(), url) == null, 15000);
        }
    }
    @Test public void sourceSwitchSkipAndAutoNextUseTheCurrentEpisode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); Store store = new Store(context);
        try (FixtureServer server = new FixtureServer(InstrumentationRegistry.getInstrumentation().getContext())) {
            Library.Video video = new Library.Video(new Library.Source("A", server.api()), new JSONObject().put("vod_id", "fixture").put("vod_name", "本地播放验证"));
            store.skip(video, 5, 3); store.autoNext(true);
            try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(new Intent(context, PlayerActivity.class).putExtra("video", video.json().toString()))) {
                awaitPlayer(scenario, 5000);
                scenario.onActivity(a -> ((ExoPlayer) field(a, "player")).seekTo(7000)); awaitPlayer(scenario, 7000);
                Library.Video alternate = new Library.Video(new Library.Source("B", server.api() + "?provider=B"), video.json());
                scenario.onActivity(a -> invoke(a, "changeSource", new Class<?>[]{Library.Video.class}, alternate));
                awaitPlayer(scenario, 7000);
                scenario.onActivity(a -> {
                    assertEquals("B", ((Library.Video) field(a, "video")).source.name);
                    assertEquals(0, field(a, "episodeIndex")); ExoPlayer player = (ExoPlayer) field(a, "player"); player.seekTo(player.getDuration() - 2000);
                });
                await(() -> { AtomicBoolean next = new AtomicBoolean(); scenario.onActivity(a -> next.set((int) field(a, "episodeIndex") == 1)); return next.get(); }, 15000);
                awaitPlayer(scenario, 5000); store.autoNext(false);
                scenario.onActivity(a -> { ExoPlayer player = (ExoPlayer) field(a, "player"); player.seekTo(player.getDuration() - 2000); });
                await(() -> { AtomicBoolean ended = new AtomicBoolean(); scenario.onActivity(a -> ended.set(((ExoPlayer) field(a, "player")).getPlaybackState() == androidx.media3.common.Player.STATE_ENDED)); return ended.get(); }, 15000);
                scenario.onActivity(a -> assertEquals(1, field(a, "episodeIndex")));
                Log.i("LibreTVSmoke", "PLAYER source switch retained progress; intro/outro and auto-next passed");
            } finally { store.skip(video, 0, 0); store.autoNext(true); }
        }
    }
    private static Download download(DownloadManager manager, String id) throws Exception { return manager.getDownloadIndex().getDownload(id); }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) { if (condition.ready()) return; Thread.sleep(200); }
        fail("Condition did not complete in " + timeout + " ms");
    }
    private static void awaitPlayer(ActivityScenario<PlayerActivity> scenario, long position) throws Exception {
        await(() -> { AtomicBoolean playing = new AtomicBoolean(); scenario.onActivity(a -> { ExoPlayer p = (ExoPlayer) field(a, "player"); if (p != null && p.getPlayerError() != null) throw new AssertionError(p.getPlayerError()); playing.set(p != null && p.isPlaying() && p.getCurrentPosition() >= position); }); return playing.get(); }, 20000);
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static final class FixtureServer implements AutoCloseable {
        final ServerSocket server; final Thread thread; volatile boolean closed;
        FixtureServer(Context assets) throws IOException {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!closed) try {
                    Socket socket = server.accept();
                    new Thread(() -> {
                        try (Socket client = socket) {
                            client.setSoTimeout(5000); BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)); String request = reader.readLine(); if (request == null) return;
                            String path = request.split(" ")[1].substring(1).split("\\?", 2)[0]; if (!path.equals("api") && !path.matches("(sample\\.m3u8|part[0-9]+\\.ts)")) return;
                            String header; while ((header = reader.readLine()) != null && !header.isEmpty()) { }
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            if (path.equals("api")) {
                                JSONObject item = new JSONObject().put("vod_id", "fixture").put("vod_name", "本地播放验证").put("vod_play_from", "fixture")
                                    .put("vod_play_url", "第1集$" + url() + "#第2集$" + url());
                                bytes.write(new JSONObject().put("list", new org.json.JSONArray().put(item)).toString().getBytes(StandardCharsets.UTF_8));
                            } else try (InputStream input = assets.getAssets().open("offline/" + path)) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n); }
                            byte[] data = bytes.toByteArray(); OutputStream out = client.getOutputStream(); out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + data.length + "\r\nConnection: close\r\nContent-Type: " + (path.endsWith("m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            for (int i = 0; i < data.length && !closed; i += 4096) { out.write(data, i, Math.min(4096, data.length - i)); out.flush(); Thread.sleep(12); }
                        } catch (Exception ignored) { }
                    }).start();
                } catch (IOException e) { if (!closed) throw new RuntimeException(e); }
            }); thread.start();
        }
        String api() { return "http://127.0.0.1:" + server.getLocalPort() + "/api"; }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/sample.m3u8"; }
        @Override public void close() throws IOException { closed = true; server.close(); }
    }
}
