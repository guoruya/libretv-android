package app.libretv.android;

import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LibraryTest {
    private final Library.Source source = new Library.Source("测试", "https://example.com/api.php/provide/vod");
    @Test public void ignoresWebsiteLinesAndRetainsEpisodeNamesAndSignedQuery() {
        List<Library.Line> lines = Library.parseLines("网页$$$HLS", "正片$https://example.com/share/1$$$第一集$https://cdn.example.com/a.m3u8?token=a$b#第二集$https://cdn.example.com/b.MP4");
        assertEquals(1, lines.size()); assertEquals("HLS", lines.get(0).name);
        assertEquals(2, lines.get(0).episodes.size());
        assertEquals("第一集", lines.get(0).episodes.get(0).name);
        assertEquals("https://cdn.example.com/a.m3u8?token=a$b", lines.get(0).episodes.get(0).url);
    }
    @Test public void rejectsExecutableAndLocalFileUrls() {
        assertFalse(Library.validUrl("javascript:alert(1)"));
        assertFalse(Library.validUrl("file:///sdcard/private"));
        assertFalse(Library.validUrl("https://user:secret@example.com/x"));
        assertFalse(Library.validUrl("https:///broken"));
        assertTrue(Library.parseLines("", "bad$javascript:alert(1)#bad$file:///x.mp4").isEmpty());
    }
    @Test public void parsesNullListsAndFiltersAdultCategories() throws Exception {
        assertTrue(Library.parsePage(new JSONObject("{\"list\":null}"), source, 1).videos.isEmpty());
        JSONObject response = new JSONObject("{\"pagecount\":2,\"list\":[{\"vod_id\":1,\"vod_name\":\"影片\",\"type_name\":\"电影\"},{\"vod_id\":2,\"vod_name\":\"过滤\",\"type_name\":\"伦理片\"}]}");
        Library.Page page = Library.parsePage(response, source, 1);
        assertEquals(1, page.videos.size()); assertTrue(page.more);
        assertEquals("1", page.videos.get(0).id);
        assertFalse(Library.parsePage(response, source, 2).more);
    }
    @Test(expected = org.json.JSONException.class) public void invalidListIsAnErrorNotAnEmptySearch() throws Exception {
        Library.parsePage(new JSONObject("{\"list\":\"denied\"}"), source, 1);
    }
    @Test public void queryCannotInjectMoreParameters() {
        String url = Library.apiUrl(new Library.Source("带参数", "https://example.com/api?token=ok"), "动画&ids=123", 2, null);
        assertTrue(url.contains("token=ok&ac=videolist&pg=2&wd="));
        assertTrue(url.contains("%26ids%3D123")); assertFalse(url.contains("&ids=123"));
    }
    @Test public void savedVideoKeepsItsSourceIdentity() throws Exception {
        Library.Video video = new Library.Video(source, new JSONObject("{\"vod_id\":15,\"vod_name\":\"测试片\"}"));
        Library.Video restored = Library.Video.fromJson(video.json());
        assertEquals(video.key(), restored.key()); assertEquals("测试片", restored.title);
    }
}
