package app.libretv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadService;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class DownloadsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private List<Download> downloads = new ArrayList<>();
    private DownloadManager manager;
    private BaseAdapter adapter;
    private TextView empty;
    private boolean active, refreshing;
    private final Runnable refresh = new Runnable() { public void run() { reload(); if (active) handler.postDelayed(this, 1000); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); manager = Downloads.manager(this); buildUi();
        DownloadService.sendResumeDownloads(this, OfflineService.class, false);
    }
    private void buildUi() {
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); LinearLayout body = Ui.column(this); Ui.pad(body, 20); root.addView(body, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout top = Ui.row(this); top.addView(Ui.button(this, "‹ 返回", this::finish)); TextView title = Ui.title(this, "  离线下载"); top.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(top); Ui.gap(body, 12);
        body.addView(Ui.text(this, "下载完成后无需联网即可播放。文件保存在本应用中，卸载应用会删除下载。", 14, Ui.MUTED)); Ui.gap(body, 18);
        empty = Ui.text(this, "还没有下载\n播放影片时点击「下载本集」，可在这里管理。", 16, Ui.MUTED); body.addView(empty); Ui.gap(body, 12);
        ListView list = new ListView(this); list.setDividerHeight(Ui.dp(this, 12)); list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.BG));
        adapter = new BaseAdapter() {
            public int getCount() { return downloads.size(); }
            public Object getItem(int position) { return downloads.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View recycled, ViewGroup parent) {
                Download download = downloads.get(position); LinearLayout row = Ui.column(DownloadsActivity.this); Ui.pad(row, 18); row.setBackground(Ui.shape(Ui.PANEL, 12, DownloadsActivity.this));
                String label = "下载影片";
                try { JSONObject metadata = Downloads.metadata(download); label = metadata.getJSONObject("video").optString("vod_name") + " · " + metadata.optString("episode"); } catch (Exception ignored) { }
                row.addView(Ui.text(DownloadsActivity.this, label, 18, Ui.TEXT)); Ui.gap(row, 10);
                float percent = download.getPercentDownloaded();
                row.addView(Ui.text(DownloadsActivity.this, Downloads.status(download) + "  ·  " + String.format(java.util.Locale.ROOT, "%.1f MB", download.getBytesDownloaded() / 1048576d) + (percent >= 0 ? "  ·  " + (int) percent + "%" : ""), 13, Ui.ACCENT));
                if (download.state == Download.STATE_DOWNLOADING) { Ui.gap(row, 10); ProgressBar progress = new ProgressBar(DownloadsActivity.this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(percent < 0); progress.setProgress((int) percent); row.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(DownloadsActivity.this, 6))); }
                Ui.gap(row, 8); row.addView(Ui.text(DownloadsActivity.this, "点击管理：播放 / 暂停 / 继续 / 删除", 12, Ui.MUTED)); return row;
            }
        };
        list.setAdapter(adapter); list.setOnItemClickListener((p, v, position, id) -> actions(downloads.get(position))); body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); Ui.insets(this, root);
    }
    private void reload() {
        if (refreshing) return; refreshing = true;
        List<Download> current = new ArrayList<>(manager.getCurrentDownloads());
        reader.execute(() -> {
            try {
                List<Download> result = Downloads.list(manager);
                for (int i = 0; i < result.size(); i++) for (Download item : current) if (result.get(i).request.id.equals(item.request.id)) result.set(i, item);
                runOnUiThread(() -> { refreshing = false; if (isDestroyed()) return; downloads = result; empty.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE); adapter.notifyDataSetChanged(); });
            } catch (Exception e) { runOnUiThread(() -> { refreshing = false; if (!isDestroyed()) { empty.setText("读取下载失败：" + e.getMessage()); empty.setVisibility(View.VISIBLE); } }); }
        });
    }
    private void actions(Download item) {
        boolean complete = item.state == Download.STATE_COMPLETED, paused = item.state == Download.STATE_STOPPED || item.state == Download.STATE_FAILED;
        String[] labels = {complete ? "离线播放" : paused ? "继续 / 重试" : "暂停下载", "删除下载"};
        new AlertDialog.Builder(this).setTitle("管理下载").setItems(labels, (dialog, which) -> {
            if (which == 1) {
                new AlertDialog.Builder(this).setTitle("删除这集下载？").setMessage("会删除已下载的文件，可随时重新下载。").setPositiveButton("删除", (d, w) -> DownloadService.sendRemoveDownload(this, OfflineService.class, item.request.id, false)).setNegativeButton("取消", null).show(); return;
            }
            if (complete) {
                try { JSONObject metadata = Downloads.metadata(item); startActivity(new Intent(this, PlayerActivity.class).putExtra("video", metadata.getJSONObject("video").toString()).putExtra("offlineUrl", item.request.uri.toString()).putExtra("offlineEpisode", metadata.optString("episode"))); }
                catch (Exception e) { Toast.makeText(this, "下载信息无法读取", Toast.LENGTH_LONG).show(); }
            } else if (item.state == Download.STATE_FAILED) DownloadService.sendAddDownload(this, OfflineService.class, item.request, false);
            else DownloadService.sendSetStopReason(this, OfflineService.class, item.request.id, paused ? 0 : 1, false);
        }).setNegativeButton("关闭", null).show();
    }
    @Override protected void onStart() { super.onStart(); active = true; handler.post(refresh); }
    @Override protected void onStop() { active = false; handler.removeCallbacks(refresh); super.onStop(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); buildUi(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); reader.shutdownNow(); super.onDestroy(); }
}
