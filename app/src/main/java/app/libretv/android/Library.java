package app.libretv.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Apple CMS JSON catalog and direct media URLs. No website scripts are executed. */
public final class Library {
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36";

    public static final class Source {
        public final String name, url;
        public Source(String name, String url) { this.name = name; this.url = url; }
        JSONObject json() throws JSONException { return new JSONObject().put("name", name).put("url", url); }
    }

    public static final class Video {
        public final Source source;
        public final String id, title, poster, type, year, remarks, description, actors, director, area;
        public Video(Source source, JSONObject j) {
            this.source = source;
            id = j.optString("vod_id", "");
            title = j.optString("vod_name", "");
            poster = j.optString("vod_pic", "");
            type = j.optString("type_name", "");
            year = j.optString("vod_year", "");
            remarks = j.optString("vod_remarks", "");
            description = j.optString("vod_content", "");
            actors = j.optString("vod_actor", ""); director = j.optString("vod_director", ""); area = j.optString("vod_area", "");
        }
        public String key() { return source.url + "\n" + id; }
        public JSONObject json() throws JSONException {
            return new JSONObject().put("source", source.json()).put("vod_id", id)
                .put("vod_name", title).put("vod_pic", poster).put("type_name", type)
                .put("vod_year", year).put("vod_remarks", remarks).put("vod_content", description)
                .put("vod_actor", actors).put("vod_director", director).put("vod_area", area);
        }
        public static Video fromJson(JSONObject j) throws JSONException {
            JSONObject s = j.getJSONObject("source");
            return new Video(new Source(s.getString("name"), s.getString("url")), j);
        }
    }

    public static final class Episode {
        public final String name, url;
        public Episode(String name, String url) { this.name = name; this.url = url; }
    }
    public static final class Line {
        public final String name;
        public final List<Episode> episodes;
        Line(String name, List<Episode> episodes) { this.name = name; this.episodes = episodes; }
    }
    public static final class Detail {
        public final Video video;
        public final List<Line> lines;
        Detail(Video video, List<Line> lines) { this.video = video; this.lines = lines; }
    }
    public static final class Page {
        public final List<Video> videos;
        public final boolean more;
        Page(List<Video> videos, boolean more) { this.videos = videos; this.more = more; }
    }

    public static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }

    public static String apiUrl(Source s, String query, int page, String id) {
        if (!validUrl(s.url)) throw new IllegalArgumentException("视频源地址必须是 HTTP 或 HTTPS 地址");
        String base = s.url.split("#", 2)[0];
        return base + (base.contains("?") ? "&" : "?") + "ac=videolist&pg=" + page
            + (id == null ? "&wd=" + encode(query) : "&ids=" + encode(id));
    }
    static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    public static Page search(Source s, String query, int page) throws IOException, JSONException {
        return parsePage(new JSONObject(new String(download(apiUrl(s, query, page, null), 8 * 1024 * 1024), StandardCharsets.UTF_8)), s, page);
    }
    public static Page parsePage(JSONObject json, Source source, int page) throws JSONException {
        Object raw = json.opt("list");
        if (raw != null && raw != JSONObject.NULL && !(raw instanceof JSONArray)) throw new JSONException("视频源没有返回有效列表");
        JSONArray list = json.optJSONArray("list");
        List<Video> result = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            Video video = new Video(source, item);
            if (!video.id.isEmpty() && !video.title.isEmpty() && !adultType(video.type)) result.add(video);
        }
        return new Page(result, page < json.optInt("pagecount", 1));
    }
    public static Detail detail(Video video) throws IOException, JSONException {
        JSONObject json = new JSONObject(new String(download(apiUrl(video.source, "", 1, video.id), 8 * 1024 * 1024), StandardCharsets.UTF_8));
        JSONArray list = json.optJSONArray("list");
        if (list == null || list.length() == 0) throw new IOException("这个来源没有返回影片详情，请换一个来源");
        JSONObject item = list.getJSONObject(0);
        List<Line> lines = parseLines(item.optString("vod_play_from"), item.optString("vod_play_url"));
        if (lines.isEmpty()) throw new IOException("这个来源没有提供可直接播放的视频，请换一个来源");
        return new Detail(new Video(video.source, item), lines);
    }
    public static List<Line> parseLines(String names, String urls) {
        String[] lineNames = names.split("\\$\\$\\$", -1);
        String[] groups = urls.split("\\$\\$\\$", -1);
        List<Line> result = new ArrayList<>();
        for (int i = 0; i < groups.length; i++) {
            List<Episode> episodes = new ArrayList<>();
            for (String entry : groups[i].split("#")) {
                int separator = entry.indexOf('$');
                String url = (separator >= 0 ? entry.substring(separator + 1) : entry).trim();
                if (!validUrl(url)) continue;
                String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
                // shortcut: only direct HLS/MP4 streams; add a provider parser if a source needs a website player.
                if (!path.endsWith(".m3u8") && !path.endsWith(".mp4") && !path.endsWith(".m4v")) continue;
                String name = separator > 0 ? entry.substring(0, separator).trim() : "第 " + (episodes.size() + 1) + " 集";
                episodes.add(new Episode(name, url));
            }
            if (!episodes.isEmpty()) result.add(new Line(i < lineNames.length && !lineNames[i].isEmpty() ? lineNames[i] : "线路 " + (i + 1), episodes));
        }
        return result;
    }

    private static boolean adultType(String type) {
        String value = type.toLowerCase(Locale.ROOT);
        String[] keywords = {"伦理", "倫理", "福利", "里番", "色情", "无码", "有码", "传媒", "萝莉", "制服诱惑", "网红主播", "擦边", "swag", "cosplay", "门事件", "黑丝诱惑", "同性片", "91视频", "国产自拍"};
        for (String keyword : keywords) if (value.contains(keyword)) return true;
        return false;
    }

    public static byte[] download(String address, int limit) throws IOException {
        if (!validUrl(address)) throw new IOException("不支持的网络地址");
        String target = address;
        for (int redirect = 0; redirect < 6; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
            HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            String host = URI.create(target).getHost();
            if (host.endsWith("bgm.tv")) connection.setRequestProperty("User-Agent", "LibreTV-Android/1.1 (+https://github.com/LibreSpark/LibreTV)");
            if (host.endsWith("douban.com") || host.endsWith("doubanio.com")) connection.setRequestProperty("Referer", "https://movie.douban.com/");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("视频源重定向无效");
                    target = URI.create(target).resolve(location).toString();
                    if (!validUrl(target)) throw new IOException("视频源重定向到不支持的地址");
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                try (InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                        ? new GZIPInputStream(connection.getInputStream()) : connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    long deadline = System.nanoTime() + 20_000_000_000L;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("请求超时");
                        if (output.size() + count > limit) throw new IOException("返回内容过大");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("视频源重定向次数过多");
    }
}
