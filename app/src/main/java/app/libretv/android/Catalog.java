package app.libretv.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Real CMS categories and separate cursors; a failed category never skips a page. */
final class Catalog {
    static final int DISPLAY_BATCH = 24, SCAN_BATCHES = 30;
    static final class Category {
        final String id, name, parent;
        String kind;
        Category(String id, String name, String parent) { this.id = id; this.name = name; this.parent = parent; kind = MediaFilters.kind(name); }
    }
    static List<Category> categories(Library.Source source, boolean filterAdult) throws Exception {
        return parseCategories(Library.readJson(Library.requestUrl(source, "list", 1, null, null)), filterAdult);
    }
    static List<Category> parseCategories(JSONObject json, boolean filterAdult) throws Exception {
        JSONArray array = json.optJSONArray("class");
        if (array == null || json.optInt("code", 1) == 0) throw new IOException("这个来源没有提供分类，请换一个来源，或使用全源搜索");
        Map<String, Category> categories = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.optJSONObject(i); if (j == null) continue;
            String id = j.optString("type_id"), name = j.optString("type_name").trim();
            if (id.matches("[1-9]\\d{0,8}") && !name.isEmpty() && !categories.containsKey(id)) categories.put(id, new Category(id, name, j.optString("type_pid", "0")));
        }
        for (Category category : categories.values()) resolve(category, categories, new HashSet<>());
        List<Category> result = new ArrayList<>();
        for (Category category : categories.values()) if (!filterAdult || (!Library.adultType(category.name) && !category.kind.equals(MediaFilters.ETHICS))) result.add(category);
        return result;
    }
    private static String resolve(Category category, Map<String, Category> all, Set<String> seen) {
        if (!seen.add(category.id)) return category.kind;
        Category parent = all.get(category.parent);
        if (parent != null && !category.kind.equals(MediaFilters.ETHICS) && !MediaFilters.special(category.name)) {
            String parentKind = resolve(parent, all, seen);
            if (!parentKind.equals("其他") && (category.kind.equals("其他") || parentKind.equals("短剧"))) category.kind = parentKind;
        }
        return category.kind;
    }
    static LinkedHashMap<String, Integer> pages(List<Category> categories, String kind, String type) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Category c : categories) if (c.kind.equals(kind) && (type.isEmpty() || type.equals(c.id))) result.put(c.id, 1);
        return result;
    }
    static final class Batch {
        final List<Library.Video> videos = new ArrayList<>();
        final LinkedHashMap<String, Integer> next = new LinkedHashMap<>();
        final List<String> failures = new ArrayList<>();
    }
    static Batch fetch(Library.Source source, Map<String, Integer> pages, boolean filterAdult) throws Exception {
        Batch result = new Batch(); result.next.putAll(pages);
        if (pages.isEmpty()) return result;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, pages.size()));
        Map<String, Future<Library.Page>> requests = new LinkedHashMap<>();
        try {
            // Four categories at a time keeps a broad channel from flooding a source or blocking the UI.
            for (Map.Entry<String, Integer> entry : pages.entrySet()) {
                if (requests.size() == 4) break;
                String id = entry.getKey(); int page = entry.getValue();
                requests.put(id, pool.submit(() -> {
                    JSONObject json = Library.readJson(Library.requestUrl(source, "videolist", page, "t", id));
                    if (!(json.opt("list") instanceof JSONArray)) throw new IOException("没有返回有效影片列表");
                    return Library.parsePage(json, source, page, filterAdult);
                }));
            }
            for (Map.Entry<String, Future<Library.Page>> entry : requests.entrySet()) {
                String id = entry.getKey(); int page = pages.get(id);
                try {
                    Library.Page response = entry.getValue().get(); result.videos.addAll(response.videos);
                    result.next.remove(id); if (response.more) result.next.put(id, page + 1);
                } catch (java.util.concurrent.ExecutionException e) {
                    result.failures.add("分类 " + id + " 第 " + page + " 页：" + (e.getCause().getMessage() == null ? "连接失败" : e.getCause().getMessage()));
                }
            }
            return result;
        } finally { for (Future<?> request : requests.values()) request.cancel(true); pool.shutdownNow(); }
    }
}
