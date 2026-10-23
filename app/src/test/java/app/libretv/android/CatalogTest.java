package app.libretv.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CatalogTest {
    @Test public void categoryParentsResolveShortsAndCyclesWithoutInventedIds() throws Exception {
        JSONObject json = new JSONObject("{\"class\":[{\"type_id\":10,\"type_name\":\"短剧\"},{\"type_id\":11,\"type_pid\":10,\"type_name\":\"甜宠\"},{\"type_id\":12,\"type_pid\":10,\"type_name\":\"都市\"},{\"type_id\":13,\"type_pid\":10,\"type_name\":\"理论片\"},{\"type_id\":20,\"type_pid\":21,\"type_name\":\"甲\"},{\"type_id\":21,\"type_pid\":20,\"type_name\":\"乙\"},{\"type_id\":11,\"type_name\":\"重复\"},{\"type_id\":\"x\",\"type_name\":\"无效\"}]}");
        List<Catalog.Category> all = Catalog.parseCategories(json, false); assertEquals(6, all.size());
        assertEquals("短剧", all.get(1).kind); assertEquals("短剧", all.get(2).kind); assertEquals(MediaFilters.ETHICS, all.get(3).kind);
        assertEquals(3, Catalog.pages(all, "短剧", "").size()); assertEquals(1, Catalog.pages(all, "短剧", "11").size());
        assertEquals(5, Catalog.parseCategories(json, true).size());
    }
    @Test(expected = java.io.IOException.class) public void unsupportedCategoriesAreErrors() throws Exception { Catalog.parseCategories(new JSONObject("{\"list\":[]}"), true); }
    @Test public void sourceQueryReplacesConflictsButKeepsCredentialsAndEncoding() {
        Library.Source s = new Library.Source("源", "https://example.com/api?token=a%2Bb&ac=list&pg=99&wd=x&ids=7&t=55#frag");
        assertEquals("https://example.com/api?token=a%2Bb&ac=videolist&pg=2&t=31", Library.requestUrl(s, "videolist", 2, "t", "31"));
        String search = Library.apiUrl(s, "测试&ids=9", 1, null);
        assertFalse(search.contains("&t=")); assertFalse(search.contains("&ids=")); assertTrue(search.contains("%26ids%3D9"));
    }
    @Test public void categoryFailuresKeepTheirPageWhileSuccessfulCategoriesAdvance() throws Exception {
        ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        AtomicInteger failedCalls = new AtomicInteger();
        Thread responder = new Thread(() -> {
            while (!server.isClosed()) try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String query = reader.readLine(), line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                boolean failing = query.contains("t=2") && failedCalls.getAndIncrement() == 0;
                byte[] body = (failing ? "unavailable" : "{\"pagecount\":2,\"list\":[{\"vod_id\":1,\"vod_name\":\"影片\",\"type_name\":\"电影\"}]}").getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 " + (failing ? "503 Unavailable" : "200 OK") + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
            } catch (Exception e) { if (!server.isClosed()) throw new RuntimeException(e); }
        });
        responder.start();
        try {
            Library.Source source = new Library.Source("测试", "http://127.0.0.1:" + server.getLocalPort() + "/api");
            LinkedHashMap<String, Integer> pages = new LinkedHashMap<>(); pages.put("1", 1); pages.put("2", 1);
            Catalog.Batch first = Catalog.fetch(source, pages, true);
            assertEquals(1, first.failures.size()); assertEquals(Integer.valueOf(2), first.next.get("1")); assertEquals(Integer.valueOf(1), first.next.get("2")); assertEquals(1, first.videos.size());
            Catalog.Batch retry = Catalog.fetch(source, first.next, true);
            assertTrue(retry.failures.isEmpty()); assertFalse(retry.next.containsKey("1")); assertEquals(Integer.valueOf(2), retry.next.get("2"));
            assertTrue(Catalog.fetch(source, retry.next, true).next.isEmpty());
        } finally { server.close(); responder.join(2000); }
    }
    @Test public void adultFilterRemainsOnByDefaultAndOnlyExplicitlyTurnsOff() throws Exception {
        JSONObject response = new JSONObject().put("list", new JSONArray().put(new JSONObject().put("vod_id", 1).put("vod_name", "来源条目").put("type_name", "理论片")));
        Library.Source source = new Library.Source("源", "https://example.com/api");
        assertTrue(Library.parsePage(response, source, 1).videos.isEmpty()); assertEquals(1, Library.parsePage(response, source, 1, false).videos.size());
    }
}
