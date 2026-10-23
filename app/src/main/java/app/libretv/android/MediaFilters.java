package app.libretv.android;

import org.json.JSONObject;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source metadata only: titles never decide a film's category, region or genre. */
final class MediaFilters {
    static final String UNKNOWN = "未标注", ETHICS = "伦理/理论片";
    static final String[] KINDS = {"电影", "电视剧", "动漫", "短剧", "综艺", "纪录片", ETHICS, "其他"};
    static final String[] SORTS = {"", "year-desc", "year-asc", "updated-desc", "updated-asc"};
    static final String[] SORT_LABELS = {"默认排序", "上映年份：新到旧", "上映年份：旧到新", "更新时间：新到旧", "更新时间：旧到新"};
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final String GENRES = "剧情 喜剧 动作 爱情 科幻 动画 奇幻 悬疑 惊悚 恐怖 犯罪 冒险 历史 战争 武侠 仙侠 古装 都市 家庭 青春 校园 运动 音乐 真人秀 脱口秀 重生 穿越 逆袭 甜宠 复仇 总裁 萌宝 职场 年代 西部 灾难 短片";
    String kind = "", type = "", area = "", genre = "", year = "", sort = "";

    static boolean has(String text, String expression) { return Pattern.compile(expression).matcher(text).find(); }
    static boolean special(String type) { return has(type, "福利|里番|解说|预告|体育|足球|篮球"); }
    static String kind(String type) {
        if (has(type, "伦理|倫理|理论|理論|理伦|理倫")) return ETHICS;
        if (special(type)) return "其他";
        if (has(type, "纪录|記錄|记录片")) return "纪录片";
        if (has(type, "短剧|短劇|短大全|反转爽|现代都市|脑洞悬疑|年代穿越|古装仙侠|女频恋爱|成长逆袭|重生民国|穿越年代|现代言情|女恋总裁|闪婚离婚|都市脑洞|漫剧")) return "短剧";
        if (has(type, "动漫|动画|動畫|卡通|番剧")) return "动漫";
        if (has(type, "综艺|綜藝|真人秀|脱口秀")) return "综艺";
        if (has(type, "电视剧|连续剧|連續劇|剧集|国产剧|大陆剧|内地剧|陆剧|港剧|香港剧|台剧|台湾剧|港台剧|日剧|日本剧|韩剧|韩国剧|日韩剧|美剧|英剧|欧美剧|海外剧|泰剧|泰国剧|其他剧|其它剧")) return "电视剧";
        if (has(type, "电影|電影|片$|动作|喜剧|爱情|科幻|恐怖|剧情|战争|悬疑|惊悚|犯罪|奇幻|冒险|西部")) return "电影";
        return "其他";
    }
    static String year(String value) { Matcher match = YEAR.matcher(value); return match.find() ? match.group() : UNKNOWN; }
    static List<String> areas(Library.Video video) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : video.area.split("[,，/、;；|&\\s]+")) {
            String s = raw.replaceAll("^[:：]+|[:：]+$", "").trim();
            if (s.isEmpty() || s.matches("未知|暂无|不详|其他|其它|0|-")) continue;
            if (s.matches("大陆|内地|中国内地|中国")) s = "中国大陆";
            else if (s.equals("香港")) s = "中国香港";
            else if (s.matches("台湾|台灣|中国台灣")) s = "中国台湾";
            else if (s.equals("澳门")) s = "中国澳门";
            values.add(s);
        }
        if (values.isEmpty()) {
            String[] names = {"中国大陆", "中国香港", "中国台湾", "日本", "韩国", "泰国"};
            String[] patterns = {"国产|大陆|内地", "香港|港剧", "台湾|台剧", "日本|日剧", "韩国|韩剧", "泰国|泰剧"};
            for (int i = 0; i < names.length; i++) if (has(video.type, patterns[i])) values.add(names[i]);
        }
        if (values.isEmpty()) values.add(UNKNOWN);
        return new ArrayList<>(values);
    }
    static List<String> genres(Library.Video video) {
        String text = video.genres + " " + video.type; List<String> values = new ArrayList<>();
        for (String genre : GENRES.split(" ")) if (text.contains(genre)) values.add(genre);
        if (has(text, "言情|恋爱|女恋") && !values.contains("爱情")) values.add("爱情");
        if (has(text, "爽文|爽剧")) values.add("爽剧");
        if (values.isEmpty()) values.add(UNKNOWN);
        return values;
    }
    boolean active() { return !(kind + type + area + genre + year).isEmpty(); }
    boolean matches(Library.Video video) {
        return (kind.isEmpty() || kind.equals(kind(video.type))) && (type.isEmpty() || type.equals(video.type.isEmpty() ? UNKNOWN : video.type))
            && (area.isEmpty() || areas(video).contains(area)) && (genre.isEmpty() || genres(video).contains(genre))
            && (year.isEmpty() || year.equals(year(video.year)));
    }
    List<Library.Video> apply(List<Library.Video> videos) {
        List<Library.Video> result = new ArrayList<>(); for (Library.Video video : videos) if (matches(video)) result.add(video); return result;
    }
    List<String> choices(List<Library.Video> videos, String field) {
        Set<String> values = new LinkedHashSet<>();
        for (Library.Video v : videos) {
            switch (field) {
                case "kind": values.add(kind(v.type)); break;
                case "type": values.add(v.type.isEmpty() ? UNKNOWN : v.type); break;
                case "area": values.addAll(areas(v)); break;
                case "genre": values.addAll(genres(v)); break;
                case "year": values.add(year(v.year)); break;
                default: throw new IllegalArgumentException(field);
            }
        }
        if (!get(field).isEmpty()) values.add(get(field));
        List<String> result = new ArrayList<>(values); Collator collator = Collator.getInstance(Locale.CHINA);
        result.sort((a, b) -> a.equals(b) ? 0 : a.equals(UNKNOWN) ? 1 : b.equals(UNKNOWN) ? -1 : field.equals("year") ? b.compareTo(a) : collator.compare(a, b));
        return result;
    }
    List<List<Library.Video>> groups(List<Library.Video> videos) {
        // Filter each source record before merging, so one source cannot supply another's matching metadata.
        List<List<Library.Video>> result = Discovery.groups(apply(videos));
        if (!Arrays.asList(SORTS).contains(sort) || sort.isEmpty()) return result;
        result.sort((a, b) -> {
            long av = order(a), bv = order(b);
            if (av == bv) return 0;
            if (av == 0) return 1; if (bv == 0) return -1;
            return sort.endsWith("desc") ? Long.compare(bv, av) : Long.compare(av, bv);
        });
        return result;
    }
    private long order(List<Library.Video> group) {
        if (sort.startsWith("year")) { String year = year(group.get(0).year); return year.equals(UNKNOWN) ? 0 : Long.parseLong(year); }
        long newest = 0; for (Library.Video video : group) newest = Math.max(newest, video.updatedAt); return newest;
    }
    String get(String field) {
        switch (field) { case "kind": return kind; case "type": return type; case "area": return area; case "genre": return genre; case "year": return year; case "sort": return sort; default: throw new IllegalArgumentException(field); }
    }
    void set(String field, String value) {
        switch (field) { case "kind": kind = value; break; case "type": type = value; break; case "area": area = value; break; case "genre": genre = value; break; case "year": year = value; break; case "sort": sort = Arrays.asList(SORTS).contains(value) ? value : ""; break; default: throw new IllegalArgumentException(field); }
    }
    JSONObject json() {
        JSONObject j = new JSONObject(); try { for (String f : new String[]{"kind", "type", "area", "genre", "year", "sort"}) j.put(f, get(f)); } catch (org.json.JSONException ignored) { } return j;
    }
    static MediaFilters fromJson(JSONObject j) {
        MediaFilters f = new MediaFilters(); if (j != null) for (String key : new String[]{"kind", "type", "area", "genre", "year", "sort"}) f.set(key, j.optString(key)); return f;
    }
}
