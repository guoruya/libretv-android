package app.libretv.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.PlatformScheduler;
import androidx.media3.exoplayer.scheduler.Scheduler;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class OfflineService extends DownloadService {
    public OfflineService() { super(1001, 1000, "downloads", R.string.download_channel, 0); }
    @Override protected DownloadManager getDownloadManager() { return Downloads.manager(this); }
    @Override protected Scheduler getScheduler() { return new PlatformScheduler(this, 1002); }
    @Override protected Notification getForegroundNotification(List<Download> downloads, int notMetRequirements) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DownloadsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new DownloadNotificationHelper(this, "downloads").buildProgressNotification(this, android.R.drawable.stat_sys_download, open, "LibreTV · 离线下载", downloads, notMetRequirements);
    }
}
