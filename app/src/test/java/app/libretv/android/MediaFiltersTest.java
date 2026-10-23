package app.libretv.android;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class MediaFiltersTest {
    private Library.Video video(String source, int id, String type, String area, String year, String time) throws Exception {
        return new Library.Video(new Library.Source(source, "https://" + source + ".example/api"), new JSONObject().put("vod_id", id).put("vod_name", "同名影片").put("type_name", type).put("vod_area", area).put("vod_year", year).put("vod_time", time));
    }
    @Test public void classificationUsesSourceLabelsAndKeepsShortsAndSpecialsSeparate() {
        String[][] cases = {{"日本动漫", "动漫"}, {"动画片", "动漫"}, {"AI漫剧", "短剧"}, {"古装仙侠", "短剧"}, {"纪录片", "纪录片"}, {"海外剧", "电视剧"}, {"电影解说", "其他"}, {"电影预告", "其他"}, {"真人秀", "综艺"}, {"理论片", MediaFilters.ETHICS}, {"未知", "其他"}};
        for (String[] c : cases) assertEquals(c[0], c[1], MediaFilters.kind(c[0]));
    }
    @Test public void filtersOneRecordBeforeMergingSources() throws Exception {
        Library.Video a = video("a", 1, "日本剧", "日本", "2025", ""), b = video("b", 2, "国产剧", "大陆", "2025", "");
        MediaFilters f = new MediaFilters(); f.type = "日本剧"; f.area = "中国大陆";
        assertTrue(f.groups(Arrays.asList(a, b)).isEmpty());
        f.type = ""; assertEquals(1, f.groups(Arrays.asList(a, b)).get(0).size());
        assertEquals(b.key(), f.groups(Arrays.asList(a, b)).get(0).get(0).key());
        f.area = ""; assertEquals(2, f.groups(Arrays.asList(a, b)).get(0).size());
    }
    @Test public void normalizesRegionsWithoutGuessingBroadLabels() throws Exception {
        assertEquals(Arrays.asList("中国大陆", "中国香港", "中国台湾", "日本"), MediaFilters.areas(video("a", 1, "电影", "内地 / 香港,台灣；日本 & 中国", "", "")));
        assertEquals(Arrays.asList("日本"), MediaFilters.areas(video("a", 1, "日本剧", "未知", "", "")));
        assertEquals(Arrays.asList(MediaFilters.UNKNOWN), MediaFilters.areas(video("a", 1, "日韩动漫", "", "", "")));
    }
    @Test public void unknownFacetsAndSelectedValuesStaySelectable() throws Exception {
        List<Library.Video> videos = Arrays.asList(video("a", 1, "", "", "0", ""), video("a", 2, "喜剧片", "日本", "2026年", ""));
        MediaFilters f = new MediaFilters(); f.year = "2024";
        assertEquals(Arrays.asList("2026", "2024", MediaFilters.UNKNOWN), f.choices(videos, "year"));
        assertTrue(f.choices(videos, "type").contains(MediaFilters.UNKNOWN));
        assertTrue(MediaFilters.genres(video("a", 3, "现代言情", "", "", "")).contains("爱情"));
        f.type = MediaFilters.UNKNOWN; f.year = ""; assertEquals(1, f.apply(videos).size());
    }
    @Test public void releaseAndUpdateSortAreIndependentAndUnknownsStayLast() throws Exception {
        Library.Video recentRelease = video("a", 1, "电影", "", "2026", "1700000000"), recentUpdate = video("a", 2, "电影", "", "2020", "1800000000000"), missing = video("a", 3, "电影", "", "0", "");
        List<Library.Video> input = Arrays.asList(missing, recentRelease, recentUpdate); MediaFilters f = new MediaFilters();
        for (String sort : new String[]{"year-desc", "year-asc", "updated-desc", "updated-asc"}) {
            f.sort = sort; List<List<Library.Video>> groups = f.groups(input);
            assertEquals(missing.key(), groups.get(2).get(0).key());
            assertEquals((sort.equals("year-desc") || sort.equals("updated-asc") ? recentRelease : recentUpdate).key(), groups.get(0).get(0).key());
        }
        assertEquals(missing.key(), input.get(0).key());
        f.set("sort", "bad-input"); assertEquals(missing.key(), f.groups(input).get(0).get(0).key());
    }
    @Test public void groupSortUsesNewestMatchingSourceAndTiesAreStable() throws Exception {
        Library.Video old = video("a", 1, "电影", "", "2020", "1700000000"), fresh = video("b", 1, "电影", "", "2020", "1800000000"), other = video("a", 2, "电影", "", "2021", "1750000000");
        MediaFilters f = new MediaFilters(); f.sort = "updated-desc";
        assertEquals(2, f.groups(Arrays.asList(other, old, fresh)).get(0).size());
        f.type = "电影"; f.area = MediaFilters.UNKNOWN;
        assertEquals(f.json().toString(), MediaFilters.fromJson(f.json()).json().toString());
    }
    @Test public void metadataSurvivesSavingAndOldRecordsStillLoad() throws Exception {
        JSONObject j = new JSONObject().put("vod_id", 7).put("vod_name", "测试").put("type_id", 21).put("vod_class", "爱情,悬疑").put("vod_year", 2026).put("vod_time", "2026-10-22T12:00:00+08:00");
        Library.Video v = new Library.Video(new Library.Source("a", "https://a.example/api"), j), saved = Library.Video.fromJson(v.json());
        assertEquals("2026", saved.year); assertEquals("21", saved.typeId); assertEquals("爱情,悬疑", saved.genres); assertEquals(1792641600000L, saved.updatedAt);
        assertEquals(saved.updatedAt, Library.updateTime("2026-10-22T04:00:00Z"));
        for (String s : new String[]{"", "null", "0", "2026", "bad", "-2"}) assertEquals(0, Library.updateTime(s));
        assertEquals(1700000000000L, Library.updateTime("1700000000"));
        j.remove("vod_time"); j.remove("vod_class"); assertEquals(0, new Library.Video(v.source, j).updatedAt);
    }
}
