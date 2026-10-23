package app.libretv.android;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateDownloadService extends Service {
    private static final String CHANNEL = "app-update";
    private static final int NOTIFICATION = 42;
    private static volatile UpdateDownloadService active;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private SharedPreferences prefs;
    private NotificationManager notifications;
    static boolean running() { return active != null; }
    @Override public void onCreate() {
        super.onCreate(); active = this; prefs = getSharedPreferences(AppUpdates.PREFS, MODE_PRIVATE);
        File folder = new File(getExternalFilesDir(null), "updates");
        File[] leftovers = folder.listFiles((dir, name) -> name.startsWith("update-") && name.endsWith(".part"));
        if (leftovers != null) for (File file : leftovers) file.delete();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text, int percent, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, UpdateActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LibreTV 应用更新").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing);
        if (ongoing) builder.setProgress(100, Math.max(0, percent), percent < 0);
        return builder.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION, notification("正在下载更新…", -1, true));
        String pending = prefs.getString("pending", "");
        prefs.edit().putString("state", "downloading").putLong("received", 0).putLong("total", -1).remove("error").apply();
        worker.execute(() -> {
            boolean success = false; String message;
            try {
                AppUpdates.Release release = new AppUpdates.Release(new JSONObject(pending));
                File file = AppUpdates.file(this, release); final long[] last = {0};
                AppUpdates.download(release, file, (received, total) -> {
                    if (cancelled) throw new IOException("下载已取消");
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - last[0] >= 300 || received == total) {
                        last[0] = now; prefs.edit().putLong("received", received).putLong("total", total).apply();
                        notifications.notify(NOTIFICATION, notification("正在下载更新", total > 0 ? (int) (received * 100 / total) : -1, true));
                    }
                });
                if (cancelled) return;
                AppUpdates.verify(this, release, file); success = true; message = "下载完成，点击安装更新";
            } catch (Exception e) { message = "下载失败：" + e.getMessage(); }
            if (cancelled) return;
            prefs.edit().putString("state", success ? "ready" : "failed").putString("error", success ? "" : message).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            notifications.notify(NOTIFICATION, notification(message, 0, false)); stopSelf(startId);
        });
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int fgsType) {
        prefs.edit().putString("state", "failed").putString("error", "系统已暂停后台下载，请返回应用重试").apply(); stopSelf();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        cancelled = true; worker.shutdownNow(); if (active == this) active = null; super.onDestroy();
    }
}
