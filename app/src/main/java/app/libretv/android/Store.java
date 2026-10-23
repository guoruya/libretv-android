package app.libretv.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Store {
    private final Context context;
    private final SharedPreferences preferences;
    Store(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("libretv", Context.MODE_PRIVATE);
    }
    List<Library.Source> sources(boolean enabledOnly) {
        List<Library.Source> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> disabled = preferences.getStringSet("disabled", new HashSet<>());
        try (InputStream stream = context.getAssets().open("sources.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray builtin = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("sources");
            JSONArray custom = new JSONArray(preferences.getString("custom", "[]"));
            for (JSONArray array : new JSONArray[]{builtin, custom}) for (int i = 0; i < array.length(); i++) {
                JSONObject j = array.getJSONObject(i);
                String url = j.optString("url");
                if (Library.validUrl(url) && !j.optBoolean("isAdult") && seen.add(url) && (!enabledOnly || !disabled.contains(url)))
                    result.add(new Library.Source(j.optString("name", "自定义源"), url));
            }
        } catch (Exception e) { android.util.Log.e("LibreTV", "Cannot read sources", e); }
        return result;
    }
    void setEnabled(String url, boolean enabled) {
        Set<String> disabled = new HashSet<>(preferences.getStringSet("disabled", new HashSet<>()));
        if (enabled) disabled.remove(url); else disabled.add(url);
        preferences.edit().putStringSet("disabled", disabled).apply();
    }
    boolean enabled(String url) { return !preferences.getStringSet("disabled", new HashSet<>()).contains(url); }
    void addSource(String name, String url) throws Exception {
        if (!Library.validUrl(url) || name.trim().isEmpty()) throw new IllegalArgumentException("请填写名称和有效的 HTTP / HTTPS 接口地址");
        for (Library.Source s : sources(false)) if (s.url.equals(url)) throw new IllegalArgumentException("这个视频源已经存在");
        JSONArray array = new JSONArray(preferences.getString("custom", "[]"));
        array.put(new Library.Source(name, url).json());
        preferences.edit().putString("custom", array.toString()).apply();
    }
    JSONArray history() {
        try { return new JSONArray(preferences.getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    JSONObject progress(Library.Video video) {
        JSONArray list = history();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && video.key().equals(item.optString("key"))) return item;
        }
        return new JSONObject();
    }
    void save(Library.Video video, String line, int episode, String episodeName, long position) {
        try {
            JSONObject current = video.json().put("key", video.key()).put("line", line).put("episode", episode)
                .put("episodeName", episodeName).put("position", Math.max(0, position));
            JSONArray result = new JSONArray().put(current);
            JSONArray old = history();
            for (int i = 0; i < old.length() && result.length() < 40; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !video.key().equals(item.optString("key"))) result.put(item);
            }
            preferences.edit().putString("history", result.toString()).apply();
        } catch (Exception e) { android.util.Log.e("LibreTV", "Cannot save progress", e); }
    }
    private JSONArray array(String key) {
        try { return new JSONArray(preferences.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    JSONArray favorites() { return array("favorites"); }
    boolean favorite(Library.Video video) {
        JSONArray list = favorites();
        for (int i = 0; i < list.length(); i++) if (sameFavorite(list.optJSONObject(i), video)) return true;
        return false;
    }
    private boolean sameFavorite(JSONObject item, Library.Video video) {
        return item != null && (video.key().equals(item.optString("key")) ||
            (Discovery.titleKey(video.title).equals(Discovery.titleKey(item.optString("vod_name"))) && video.year.equals(item.optString("vod_year"))));
    }
    void toggleFavorite(Library.Video video) {
        try {
            JSONArray old = favorites(), result = new JSONArray(); boolean exists = favorite(video);
            if (!exists) result.put(video.json().put("key", video.key()));
            for (int i = 0; i < old.length(); i++) if (!sameFavorite(old.getJSONObject(i), video)) result.put(old.getJSONObject(i));
            preferences.edit().putString("favorites", result.toString()).apply();
        } catch (Exception e) { android.util.Log.e("LibreTV", "Cannot save favorite", e); }
    }
    JSONArray searches() { return array("searches"); }
    void rememberSearch(String text) {
        JSONArray result = new JSONArray().put(text), old = searches();
        for (int i = 0; i < old.length() && result.length() < 12; i++) if (!text.equals(old.optString(i))) result.put(old.optString(i));
        preferences.edit().putString("searches", result.toString()).apply();
    }
    void clearSearches() { preferences.edit().remove("searches").apply(); }
    boolean autoNext() { return preferences.getBoolean("autoNext", true); }
    void autoNext(boolean value) { preferences.edit().putBoolean("autoNext", value).apply(); }
    int skip(Library.Video video, String end) { return preferences.getInt("skip:" + Discovery.titleKey(video.title) + ":" + end, 0); }
    void skip(Library.Video video, int intro, int outro) {
        preferences.edit().putInt("skip:" + Discovery.titleKey(video.title) + ":intro", intro)
            .putInt("skip:" + Discovery.titleKey(video.title) + ":outro", outro).apply();
    }
    void cache(String key, List<Discovery.Item> items) {
        try {
            JSONArray list = new JSONArray(); for (Discovery.Item item : items) list.put(item.json());
            preferences.edit().putString("discovery:" + key, list.toString()).putLong("discoveryTime:" + key, System.currentTimeMillis()).apply();
        } catch (Exception e) { android.util.Log.e("LibreTV", "Cannot cache recommendations", e); }
    }
    long cacheTime(String key) { return preferences.getLong("discoveryTime:" + key, 0); }
    List<Discovery.Item> cached(String key) {
        List<Discovery.Item> items = new ArrayList<>(); JSONArray list = array("discovery:" + key);
        for (int i = 0; i < list.length(); i++) {
            JSONObject j = list.optJSONObject(i); if (j != null) items.add(new Discovery.Item(j.optString("title"), j.optString("poster"), j.optString("rating"), j.optString("subtitle"), j.optInt("rank")));
        }
        return items;
    }
    void clearHistory() { preferences.edit().remove("history").apply(); }
}
