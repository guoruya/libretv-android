package app.libretv.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Public recommendation metadata. A recommendation opens a search, never an invented play URL. */
final class Discovery {
    static final String[] RANKS = {"电影周榜", "国产剧周榜", "海外剧周榜", "国内综艺", "海外综艺", "百度热播剧"};
    static final String[] PATHS = {"movie", "tv_chinese", "tv_global", "show_chinese", "show_global"};
    static final String[] MOVIES = {"热门", "最新", "经典", "豆瓣高分", "冷门佳片", "华语", "欧美", "韩国", "日本", "动画"};
    static final String[] TV = {"热门", "美剧", "英剧", "韩剧", "日剧", "国产剧", "港剧", "日本动画", "综艺", "纪录片"};
    static final String[] DAYS = {"热门动漫", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    static final class Item {
        final String title, poster, rating, subtitle;
        final int rank;
        Item(String title, String poster, String rating, String subtitle, int rank) {
            this.title = title; this.poster = poster; this.rating = rating; this.subtitle = subtitle; this.rank = rank;
        }
        JSONObject json() throws Exception { return new JSONObject().put("title", title).put("poster", poster).put("rating", rating).put("subtitle", subtitle).put("rank", rank); }
    }
    static List<Item> fetch(String section, int index) throws Exception {
        if (section.equals("榜单")) {
            String path = index == 5 ? "/v2/baidu/teleplay" : "/v2/douban/weekly/" + PATHS[index];
            Exception failure = null;
            for (String host : new String[]{"https://60s.crystelf.top", "https://60s.viki.moe"}) {
                try { return ranks(new JSONObject(get(host + path)), index == 5); }
                catch (Exception e) { failure = e; }
            }
            throw failure;
        }
        if (section.equals("新番")) {
            if (index > 0) return calendar(new JSONArray(get("https://api.bgm.tv/calendar")), index);
            return subjects(new JSONObject(get("https://movie.douban.com/j/search_subjects?type=tv&tag=" + Library.encode("日本动画") + "&sort=recommend&page_limit=50&page_start=0")));
        }
        String type = section.equals("电影") ? "movie" : "tv";
        String tag = section.equals("电影") ? MOVIES[index] : TV[index];
        return subjects(new JSONObject(get("https://movie.douban.com/j/search_subjects?type=" + type + "&tag=" + Library.encode(tag) + "&sort=recommend&page_limit=50&page_start=0")));
    }
    private static String get(String url) throws Exception { return new String(Library.download(url, 4 * 1024 * 1024), StandardCharsets.UTF_8); }
    static String rating(Object raw) {
        try { double n = Double.parseDouble(String.valueOf(raw)); return n > 0 && n <= 10 ? String.format(Locale.ROOT, "%.1f", n) : ""; }
        catch (Exception e) { return ""; }
    }
    static List<Item> ranks(JSONObject json, boolean baidu) throws Exception {
        if (json.optInt("code", 200) != 200) throw new java.io.IOException("榜单服务暂不可用");
        JSONArray data = json.getJSONArray("data"); List<Item> list = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject j = data.getJSONObject(i);
            String cover = j.optString("cover"); if (!Library.validUrl(cover)) cover = j.optString("cover_proxy");
            String subtitle = baidu ? j.optString("desc") : j.optString("card_subtitle");
            if (!j.optString("title").isEmpty()) list.add(new Item(j.getString("title"), cover, baidu ? "" : rating(j.opt("rating")), subtitle, j.optInt("rank", i + 1)));
        }
        return list;
    }
    static List<Item> subjects(JSONObject json) throws Exception {
        List<Item> list = new ArrayList<>(); JSONArray subjects = json.getJSONArray("subjects");
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject j = subjects.getJSONObject(i);
            if (!j.optString("title").isEmpty()) list.add(new Item(j.getString("title"), j.optString("cover"), rating(j.opt("rate")), j.optBoolean("is_new") ? "新上榜" : "豆瓣推荐", 0));
        }
        return list;
    }
    static List<Item> calendar(JSONArray json, int day) throws Exception {
        List<Item> list = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            JSONObject group = json.getJSONObject(i);
            if (group.getJSONObject("weekday").optInt("id") != day) continue;
            JSONArray items = group.getJSONArray("items");
            for (int n = 0; n < items.length(); n++) {
                JSONObject j = items.getJSONObject(n); String title = j.optString("name_cn");
                if (title.isEmpty()) title = j.optString("name");
                JSONObject images = j.optJSONObject("images"), score = j.optJSONObject("rating");
                String cover = images == null ? "" : images.optString("large", images.optString("common", images.optString("medium")));
                if (cover.startsWith("http://lain.bgm.tv/")) cover = cover.replace("http://", "https://");
                if (!title.isEmpty()) list.add(new Item(title, cover, score == null ? "" : rating(score.opt("score")), j.optString("air_date"), 0));
            }
        }
        return list;
    }
    static String titleKey(String title) {
        return title.replaceAll("[\\s\\u3000：:·・]", "").toLowerCase(Locale.ROOT);
    }
    static int sourceCount(List<Library.Video> videos) {
        java.util.Set<String> urls = new java.util.HashSet<>();
        for (Library.Video video : videos) urls.add(video.source.url);
        return urls.size();
    }
    static List<List<Library.Video>> groups(List<Library.Video> videos) {
        LinkedHashMap<String, List<Library.Video>> groups = new LinkedHashMap<>();
        for (Library.Video video : videos) {
            // Keep different years and labelled commentary/edits separate; fuzzy merging can pick the wrong film.
            String key = titleKey(video.title) + "\n" + video.year;
            List<Library.Video> group = groups.computeIfAbsent(key, k -> new ArrayList<>());
            boolean duplicate = false;
            for (Library.Video existing : group) if (existing.key().equals(video.key())) duplicate = true;
            if (!duplicate) group.add(video);
        }
        return new ArrayList<>(groups.values());
    }
}
