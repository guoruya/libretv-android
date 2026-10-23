package app.libretv.android;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MimeTypes;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class Downloads {
    private static DownloadManager manager;
    private static SimpleCache cache;
    static synchronized DownloadManager manager(Context context) {
        if (manager == null) {
            Context app = context.getApplicationContext(); StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(app);
            cache = new SimpleCache(new File(app.getFilesDir(), "downloads"), new NoOpCacheEvictor(), database);
            manager = new DownloadManager(app, database, cache, http(), Runnable::run);
            manager.setMaxParallelDownloads(2);
            // A reachable source can work even when Android's Internet probe is blocked. Let the actual request decide.
            manager.setRequirements(new androidx.media3.exoplayer.scheduler.Requirements(androidx.media3.exoplayer.scheduler.Requirements.DEVICE_STORAGE_NOT_LOW));
        }
        return manager;
    }
    static DefaultHttpDataSource.Factory http() {
        return new DefaultHttpDataSource.Factory().setUserAgent(Library.USER_AGENT).setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true);
    }
    static CacheDataSource.Factory playback(Context context, boolean offline) {
        manager(context);
        // Offline mode has no upstream: missing files must report an error rather than silently use mobile data.
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(offline ? null : http()).setCacheWriteDataSinkFactory(null);
    }
    static void add(Context context, Library.Video video, Library.Episode episode) throws Exception {
        if (!Library.validUrl(episode.url)) throw new IllegalArgumentException("无效的视频地址");
        JSONObject metadata = new JSONObject().put("video", video.json()).put("episode", episode.name);
        DownloadRequest request = new DownloadRequest.Builder(episode.url, Uri.parse(episode.url))
            .setMimeType(Uri.parse(episode.url).getPath().endsWith(".m3u8") ? MimeTypes.APPLICATION_M3U8 : MimeTypes.VIDEO_MP4)
            .setData(metadata.toString().getBytes(StandardCharsets.UTF_8)).build();
        DownloadService.sendAddDownload(context, OfflineService.class, request, false);
        DownloadService.sendResumeDownloads(context, OfflineService.class, false);
    }
    static List<Download> list(DownloadManager manager) throws Exception {
        List<Download> result = new ArrayList<>();
        try (DownloadCursor cursor = manager.getDownloadIndex().getDownloads()) { while (cursor.moveToNext()) result.add(cursor.getDownload()); }
        return result;
    }
    static JSONObject metadata(Download download) throws Exception { return new JSONObject(new String(download.request.data, StandardCharsets.UTF_8)); }
    static String status(Download download) {
        switch (download.state) {
            case Download.STATE_COMPLETED: return "已完成 · 可离线观看";
            case Download.STATE_DOWNLOADING: return "正在下载";
            case Download.STATE_STOPPED: return "已暂停";
            case Download.STATE_FAILED: return "下载失败 · 点击重试或换源";
            case Download.STATE_REMOVING: return "正在删除";
            default: return "等待下载队列或可用空间";
        }
    }
}
