package app.libretv.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DiscoveryTest {
    @Test public void curatedListsRequestTheCorrectSortAndSortReturnedRatings() throws Exception {
        assertEquals(14, Discovery.RANKS.length);
        assertTrue(Discovery.curatedUrl(6).contains("type=movie")); assertTrue(Discovery.curatedUrl(6).contains("sort=rank"));
        assertTrue(Discovery.curatedUrl(10).contains("type=tv")); assertTrue(Discovery.curatedUrl(13).contains("sort=recommend"));
        JSONObject json = new JSONObject("{\"subjects\":[{\"title\":\"无评分\",\"rate\":0},{\"title\":\"八分\",\"rate\":8},{\"title\":\"九分\",\"rate\":9}]}");
        assertEquals("九分", Discovery.subjects(json, true).get(0).title); assertEquals("无评分", Discovery.subjects(json, true).get(2).title);
        assertEquals("无评分", Discovery.subjects(json, false).get(0).title);
    }
    @Test public void ranksPreserveActualRatingAndNeverUseBaiduHeatAsStars() throws Exception {
        JSONObject response = new JSONObject("{\"code\":200,\"data\":[{\"title\":\"电影\",\"rating\":7.3,\"score\":95000,\"rank\":2,\"cover\":\"https://example.com/poster.jpg\"}]}");
        Discovery.Item movie = Discovery.ranks(response, false).get(0);
        assertEquals("7.3", movie.rating); assertEquals(2, movie.rank);
        assertEquals("", Discovery.ranks(response, true).get(0).rating);
        assertEquals("", Discovery.rating("0")); assertEquals("", Discovery.rating("NaN")); assertEquals("", Discovery.rating("95000"));
    }
    @Test(expected = java.io.IOException.class) public void failedApiCannotMasqueradeAsEmptyRankings() throws Exception {
        Discovery.ranks(new JSONObject("{\"code\":500,\"data\":[]}"), false);
    }
    @Test public void calendarChoosesWeekdayAndChineseTitleWithMissingArtwork() throws Exception {
        JSONArray data = new JSONArray("[{\"weekday\":{\"id\":1},\"items\":[{\"name\":\"Original\",\"name_cn\":\"中文片名\",\"rating\":{\"score\":8.1}}]},{\"weekday\":{\"id\":2},\"items\":[]}]");
        assertEquals("中文片名", Discovery.calendar(data, 1).get(0).title);
        assertEquals("", Discovery.calendar(data, 1).get(0).poster);
        assertTrue(Discovery.calendar(data, 2).isEmpty());
    }
    @Test public void mergesSameFilmAcrossSourcesButKeepsRemakesAndCommentarySeparate() throws Exception {
        Library.Source a = new Library.Source("A", "https://a.example/api"), b = new Library.Source("B", "https://b.example/api");
        Library.Video one = video(a, "影片：续集", "2026"), two = video(b, "影片:续集", "2026");
        List<List<Library.Video>> groups = Discovery.groups(Arrays.asList(one, two, one, video(b, "影片：续集[电影解说]", "2026"), video(a, "影片：续集", "1999")));
        assertEquals(3, groups.size()); assertEquals(2, groups.get(0).size());
        assertEquals(2, Discovery.sourceCount(Arrays.asList(one, two, video(a, "另一版本", "2026"))));
    }
    @Test public void oldSavedVideosStillReadAndNewDetailsRoundTrip() throws Exception {
        Library.Video old = Library.Video.fromJson(new JSONObject("{\"source\":{\"name\":\"旧源\",\"url\":\"https://example.com/api\"},\"vod_id\":\"1\",\"vod_name\":\"影片\"}"));
        assertEquals("", old.actors);
        Library.Video detailed = new Library.Video(old.source, new JSONObject().put("vod_id", "2").put("vod_actor", "演员").put("vod_director", "导演").put("vod_area", "中国"));
        Library.Video restored = Library.Video.fromJson(detailed.json());
        assertEquals("演员", restored.actors); assertEquals("导演", restored.director); assertEquals("中国", restored.area);
    }
    private Library.Video video(Library.Source source, String title, String year) throws Exception {
        return new Library.Video(source, new JSONObject().put("vod_id", title + year).put("vod_name", title).put("vod_year", year));
    }
}
