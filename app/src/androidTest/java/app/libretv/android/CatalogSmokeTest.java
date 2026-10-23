package app.libretv.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CatalogSmokeTest {
    @Test public void filtersScanMorePagesRememberStateAndSearchEveryEnabledSource() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("libretv", Context.MODE_PRIVATE); Map<String, ?> before = preferences.getAll();
        try (Server server = new Server()) {
            Store store = new Store(context); Set<String> disabled = new HashSet<>(); for (Library.Source s : store.sources(false)) disabled.add(s.url);
            JSONArray sources = new JSONArray().put(new Library.Source("测试片库 A", server.url("a")).json()).put(new Library.Source("测试片库 B", server.url("b")).json());
            preferences.edit().putString("custom", sources.toString()).putStringSet("disabled", disabled).putBoolean("filterAdult", true)
                .putString("browsing", new JSONObject().put("tab", "片库").put("source", server.url("a")).put("catalogKind", "电影").toString()).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                settled(scenario); scenario.onActivity(a -> { assertEquals(String.valueOf(field(a, "notice")) + field(a, "failures"), 24, groups(a).size()); assertEquals("电影", field(a, "catalogKind")); assertTrue(button(a.getWindow().getDecorView(), "喜剧片") != null); });
                clickMain(scenario, "年份 · 全部 ▾"); capture("v14-filter-dialog.png"); clickDialog("2024");
                await(() -> { AtomicBoolean selected = new AtomicBoolean(); scenario.onActivity(a -> selected.set("2024".equals(((MediaFilters) field(a, "catalogFilters")).year))); return selected.get(); }, 5000); settled(scenario);
                assertTrue("Filtering must keep scanning beyond the original loaded pages", server.maxPage.get() >= 3);
                scenario.onActivity(a -> { assertEquals(24, groups(a).size()); for (List<Library.Video> g : groups(a)) assertEquals("2024", g.get(0).year); });
                clickMain(scenario, "默认排序 ▾"); clickDialog("更新时间：旧到新");
                scenario.onActivity(a -> { assertEquals("updated-asc", ((MediaFilters) field(a, "catalogFilters")).sort); assertEquals("2024", ((MediaFilters) field(a, "catalogFilters")).year); });
                capture("v14-catalog-filtered.png");
                clickMain(scenario, "加载更多"); settled(scenario);
                scenario.onActivity(a -> assertEquals(32, groups(a).size()));
                scenario.recreate(); settled(scenario);
                scenario.onActivity(a -> { assertEquals("2024", ((MediaFilters) field(a, "catalogFilters")).year); assertEquals("updated-asc", ((MediaFilters) field(a, "catalogFilters")).sort); assertEquals(server.url("a"), ((Library.Source) field(a, "browseSource")).url); });
                scenario.onActivity(a -> ((EditText) field(a, "search")).setText("搜索测试")); clickMain(scenario, "全源搜索"); settled(scenario);
                assertTrue(server.searchA.get() > 0); assertTrue(server.searchB.get() > 0);
                scenario.onActivity(a -> { assertEquals(1, groups(a).size()); assertEquals(2, groups(a).get(0).size()); });
                clickMain(scenario, "地区 · 全部 ▾"); capture("v14-search-region-dialog.png"); clickDialog("日本");
                scenario.onActivity(a -> { assertEquals(1, groups(a).get(0).size()); assertEquals("测试片库 B", groups(a).get(0).get(0).source.name); });
                capture("v14-search-filtered.png");
                scenario.onActivity(MainActivity::onBackPressed); settled(scenario);
                scenario.onActivity(a -> { assertEquals("片库", field(a, "tab")); assertEquals("2024", ((MediaFilters) field(a, "catalogFilters")).year); });
                scenario.onActivity(a -> { assertEquals("2024", ((MediaFilters) field(a, "catalogFilters")).year); a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); });
                await(() -> { AtomicBoolean portrait = new AtomicBoolean(); scenario.onActivity(a -> portrait.set(a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)); return portrait.get(); }, 7000);
                scenario.onActivity(a -> { assertEquals("2024", ((MediaFilters) field(a, "catalogFilters")).year); assertEquals(24, groups(a).size()); });
                capture("v14-catalog-portrait.png");
                scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                await(() -> { AtomicBoolean landscape = new AtomicBoolean(); scenario.onActivity(a -> landscape.set(a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)); return landscape.get(); }, 7000);

                clickMain(scenario, "短剧"); settled(scenario);
                scenario.onActivity(a -> { assertNotNull(button(a.getWindow().getDecorView(), "甜宠")); assertEquals("短剧", field(a, "catalogKind")); });
                clickMain(scenario, "甜宠"); settled(scenario);
                scenario.onActivity(a -> { assertEquals("5", field(a, "catalogType")); a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); });
            }
        } finally { restore(preferences, before); }
    }
    @Test public void publicCatalogMetadataAndCuratedLists() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveCatalog")));
        Store store = new Store(InstrumentationRegistry.getInstrumentation().getTargetContext()); int available = 0;
        for (Library.Source source : store.sources(true)) if (source.name.contains("360") || source.name.contains("猫眼") || source.name.contains("魔都")) {
            List<Catalog.Category> types = Catalog.categories(source, true); assertFalse(types.isEmpty());
            Catalog.Batch batch = Catalog.fetch(source, Catalog.pages(types, "电影", ""), true); assertFalse(source.name, batch.videos.isEmpty());
            long dates = 0, genres = 0; for (Library.Video v : batch.videos) { if (v.updatedAt > 0) dates++; if (!v.genres.isEmpty()) genres++; }
            System.out.println("LIVE " + source.name + ": categories=" + types.size() + " videos=" + batch.videos.size() + " updateTimes=" + dates + " genres=" + genres); available++;
        }
        assertTrue(available > 0);
        for (int index = 6; index < Discovery.RANKS.length; index++) { List<Discovery.Item> list = Discovery.fetch("榜单", index); assertFalse(Discovery.RANKS[index], list.isEmpty()); System.out.println("LIVE " + Discovery.RANKS[index] + ": " + list.size()); }
    }
    @Test public void liveTabletScreens() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveCatalog")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); SharedPreferences prefs = context.getSharedPreferences("libretv", Context.MODE_PRIVATE);
        String previous = prefs.getString("browsing", null);
        try {
            prefs.edit().putString("browsing", new JSONObject().put("tab", "榜单").put("filter", 6).toString()).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                settled(scenario); await(() -> { AtomicInteger images = new AtomicInteger(); scenario.onActivity(a -> images.set(images(a.getWindow().getDecorView()))); return images.get() >= 4; }, 20000); capture("v14-live-curated.png");
                clickMain(scenario, "片库"); settled(scenario);
                await(() -> { AtomicInteger images = new AtomicInteger(); scenario.onActivity(a -> { assertFalse(groups(a).isEmpty()); images.set(images(a.getWindow().getDecorView())); }); return images.get() >= 4; }, 20000); capture("v14-live-catalog.png");
            }
        } finally { SharedPreferences.Editor edit = prefs.edit(); if (previous == null) edit.remove("browsing"); else edit.putString("browsing", previous); edit.commit(); }
    }
    private static int images(View view) {
        int count = view instanceof android.widget.ImageView && ((android.widget.ImageView) view).getDrawable() != null ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += images(((ViewGroup) view).getChildAt(i)); return count;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs, Map<String, ?> before) {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : new String[]{"custom", "disabled", "browsing", "filterAdult", "searches"}) {
            edit.remove(key); Object value = before.get(key);
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        }
        edit.commit();
    }
    @SuppressWarnings("unchecked") private static List<List<Library.Video>> groups(MainActivity a) { return (List<List<Library.Video>>) field(a, "groups"); }
    private static Object field(Object target, String name) { try { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (Exception e) { throw new AssertionError(e); } }
    private static Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Button b = button(((ViewGroup) view).getChildAt(i), text); if (b != null) return b; }
        return null;
    }
    private static void clickMain(ActivityScenario<MainActivity> scenario, String text) { scenario.onActivity(a -> { Button b = button(a.getWindow().getDecorView(), text); assertNotNull(text, b); assertTrue(b.performClick()); }); }
    private static void clickDialog(String text) throws Exception {
        AtomicInteger clickedWindow = new AtomicInteger(-1);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(100, 2000);
        await(() -> {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(); if (root == null) return false;
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) if (text.contentEquals(n.getText()) && n.isVisibleToUser()) {
                clickedWindow.set(n.getWindowId()); android.graphics.Rect bounds = new android.graphics.Rect(); n.getBoundsInScreen(bounds);
                long now = android.os.SystemClock.uptimeMillis(); android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                boolean clicked = ui.injectInputEvent(down, true) && ui.injectInputEvent(up, true); down.recycle(); up.recycle(); return clicked;
            }
            return false;
        }, 5000);
        await(() -> { AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(); return root != null && root.getWindowId() != clickedWindow.get(); }, 5000);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(100, 2000);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private interface Ready { boolean get() throws Exception; }
    private static void await(Ready ready, long timeout) throws Exception { long deadline = System.currentTimeMillis() + timeout; while (System.currentTimeMillis() < deadline) { if (ready.get()) return; Thread.sleep(100); } fail("Timed out after " + timeout); }
    private static void settled(ActivityScenario<MainActivity> scenario) throws Exception { await(() -> { AtomicBoolean done = new AtomicBoolean(); scenario.onActivity(a -> done.set(!(boolean) field(a, "loading"))); return done.get(); }, 15000); }
    private static void capture(String file) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.graphics.Bitmap b = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (OutputStream out = new FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), file))) { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } b.recycle();
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket server; final Thread thread;
        final AtomicInteger maxPage = new AtomicInteger(), searchA = new AtomicInteger(), searchB = new AtomicInteger();
        Server() throws IOException {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String request = reader.readLine(), line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    URI uri = URI.create(request.split(" ")[1]); Map<String, String> query = new HashMap<>();
                    for (String p : uri.getRawQuery().split("&")) { String[] pair = p.split("=", 2); query.put(pair[0], pair.length == 2 ? pair[1] : ""); }
                    JSONObject response;
                    if ("list".equals(query.get("ac"))) response = new JSONObject("{\"class\":[{\"type_id\":1,\"type_name\":\"电影\"},{\"type_id\":2,\"type_pid\":1,\"type_name\":\"喜剧片\"},{\"type_id\":4,\"type_name\":\"短剧\"},{\"type_id\":5,\"type_pid\":4,\"type_name\":\"甜宠\"}]}");
                    else if (query.containsKey("wd")) {
                        boolean b = uri.getPath().equals("/b"); (b ? searchB : searchA).incrementAndGet();
                        response = new JSONObject().put("pagecount", 1).put("list", new JSONArray().put(new JSONObject().put("vod_id", 7).put("vod_name", "搜索测试").put("vod_year", "2026").put("type_name", b ? "日本剧" : "国产剧").put("vod_area", b ? "日本" : "大陆")));
                    } else {
                        int page = Integer.parseInt(query.getOrDefault("pg", "1")); maxPage.set(Math.max(maxPage.get(), page)); JSONArray items = new JSONArray();
                        for (int i = 0; i < (page == 3 ? 30 : 20); i++) items.put(new JSONObject().put("vod_id", page * 100 + i).put("vod_name", "片库测试 " + page + "-" + i).put("type_name", "喜剧片").put("vod_area", i % 2 == 0 ? "大陆" : "日本").put("vod_class", "喜剧,家庭").put("vod_year", i == 0 || page == 3 ? "2024" : "2025").put("vod_time", 1700000000L + page * 100 + i));
                        response = new JSONObject().put("pagecount", 3).put("list", items);
                    }
                    byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body);
                } catch (Exception e) { if (!server.isClosed()) throw new RuntimeException(e); }
            }); thread.start();
        }
        String url(String source) { return "http://127.0.0.1:" + server.getLocalPort() + "/" + source; }
        public void close() throws Exception { server.close(); thread.join(2000); }
    }
}
