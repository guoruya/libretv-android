package app.libretv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private AppUpdates.Release release;
    private long downloadId = -1;
    private boolean busy, ready, resumed, failed, installAfterDownload;
    private TextView status, details, channel;
    private ProgressBar progress;
    private Button action, check, cancel, address;
    private final Runnable poll = this::pollDownload;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = getSharedPreferences(AppUpdates.PREFS, MODE_PRIVATE);
        downloadId = prefs.getLong("downloadId", -1);
        try {
            if (downloadId >= 0) {
                release = new AppUpdates.Release(new JSONObject(prefs.getString("pending", "")));
                if (release.code <= AppUpdates.versionCode(AppUpdates.installed(this))) clearDownload();
            }
        } catch (Exception e) { clearDownload(); }
        buildUi(); if (downloadId < 0) checkUpdate();
    }
    private void buildUi() {
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        ScrollView scroll = new ScrollView(this); LinearLayout body = Ui.column(this); Ui.pad(body, 24); scroll.addView(body); root.addView(scroll);
        body.addView(Ui.button(this, "‹ 返回", this::finish)); Ui.gap(body, 24); body.addView(Ui.title(this, "应用更新")); Ui.gap(body, 12);
        String version = ""; try { version = AppUpdates.installed(this).versionName; } catch (Exception ignored) { }
        body.addView(Ui.text(this, "当前版本  " + version, 17, Ui.TEXT)); Ui.gap(body, 20);
        status = Ui.text(this, "点击检查更新", 17, Ui.ACCENT); body.addView(status); Ui.gap(body, 12);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); body.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 8))); progress.setVisibility(View.GONE);
        details = Ui.text(this, "", 15, Ui.TEXT); Ui.gap(body, 16); body.addView(details); Ui.gap(body, 16);
        action = Ui.chip(this, "下载并安装", true, () -> { if (ready) install(); else startDownload(); }); body.addView(action);
        check = Ui.button(this, "检查更新", this::checkUpdate); body.addView(check);
        cancel = Ui.button(this, "取消下载", () -> { clearDownload(); status.setText("已取消，可重新检查更新"); render(); }); body.addView(cancel);
        Ui.gap(body, 22); body.addView(Ui.text(this, "更新会保留收藏、观看记录和视频源。下载可在后台继续；安装时按系统提示确认，无需卸载旧版。", 14, Ui.MUTED));
        Ui.gap(body, 20); channel = Ui.text(this, "", 12, Ui.MUTED); body.addView(channel);
        address = Ui.button(this, "更新地址", this::editAddress); body.addView(address);
        Ui.insets(this, root); render();
    }
    private String endpoint() { return prefs.getString("channel", getString(R.string.update_channel_url)).trim(); }
    private void render() {
        channel.setText(endpoint().isEmpty() ? "尚未设置更新地址" : "更新地址：" + endpoint());
        details.setText(release == null ? "" : "版本 " + release.name + "\n\n" + release.notes);
        action.setVisibility(release != null && (downloadId < 0 || ready || failed) ? View.VISIBLE : View.GONE);
        action.setText(ready ? "安装更新" : failed ? "重新下载" : "下载并安装"); action.setEnabled(!busy);
        check.setEnabled(!busy && downloadId < 0); address.setEnabled(!busy && downloadId < 0);
        cancel.setVisibility(downloadId >= 0 ? View.VISIBLE : View.GONE); cancel.setEnabled(!busy);
        cancel.setText(ready ? "删除安装包" : "取消下载");
    }
    private void checkUpdate() {
        if (busy || downloadId >= 0) return;
        if (endpoint().isEmpty()) { status.setText("请先设置更新地址"); render(); return; }
        busy = true; release = null; ready = failed = false; progress.setVisibility(View.VISIBLE); progress.setIndeterminate(true); status.setText("正在检查更新…"); render();
        String url = endpoint();
        worker.execute(() -> {
            AppUpdates.Release result = null; String message;
            try {
                result = AppUpdates.fetch(url);
                if (!getPackageName().equals(result.packageName)) throw new Exception("更新地址对应其他应用，请检查地址");
                if (result.code <= AppUpdates.versionCode(AppUpdates.installed(this))) { result = null; message = "已是最新版本"; }
                else if (result.minSdk > android.os.Build.VERSION.SDK_INT) { result = null; message = "新版暂不支持当前系统，请继续使用现有版本"; }
                else message = "发现新版本 " + result.name;
            } catch (Exception e) { result = null; message = "检查失败：" + e.getMessage(); }
            AppUpdates.Release found = result; String text = message;
            runOnUiThread(() -> { if (isDestroyed()) return; busy = false; release = found; status.setText(text); progress.setVisibility(View.GONE); render(); });
        });
    }
    private void startDownload() {
        if (release == null || busy) return;
        try {
            AppUpdates.Release selected = release; clearDownload(); release = selected;
            File file = AppUpdates.file(this, release);
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new Exception("无法创建更新目录");
            if (file.exists() && !file.delete()) throw new Exception("无法清理上次的安装包");
            downloadId = release.code;
            prefs.edit().putLong("downloadId", downloadId).putString("pending", release.json().toString()).putString("state", "starting").putLong("startedAt", System.currentTimeMillis()).apply();
            startForegroundService(new Intent(this, UpdateDownloadService.class));
            installAfterDownload = true; status.setText("正在下载更新…"); render(); pollDownload();
        } catch (Exception e) { status.setText("无法开始下载：" + e.getMessage()); render(); }
    }
    private void pollDownload() {
        handler.removeCallbacks(poll);
        if (downloadId < 0 || ready || busy || !resumed) return;
        try {
            String state = prefs.getString("state", "failed");
            if (state.equals("ready")) { verifyDownload(); return; }
            if (state.equals("failed")) throw new Exception(prefs.getString("error", "下载中断，请重试"));
            if (state.equals("downloading") && !UpdateDownloadService.running()) throw new Exception("后台下载已被系统中断，请重新下载");
            if (state.equals("starting") && !UpdateDownloadService.running() && System.currentTimeMillis() - prefs.getLong("startedAt", 0) > 10000) throw new Exception("后台下载未能启动，请重新下载");
            long done = prefs.getLong("received", 0), total = prefs.getLong("total", -1);
            progress.setVisibility(View.VISIBLE); progress.setIndeterminate(total <= 0); if (total > 0) progress.setProgress((int) (done * 100 / total));
            status.setText("正在下载 " + String.format(Locale.ROOT, "%.1f", done / 1048576.0) + " MB" + (total > 0 ? " · " + done * 100 / total + "%" : ""));
            handler.postDelayed(poll, 500);
        } catch (Exception e) { failed = true; progress.setVisibility(View.GONE); status.setText(e.getMessage()); render(); }
    }
    private void verifyDownload() {
        busy = true; status.setText("正在验证安装包…"); progress.setVisibility(View.VISIBLE); progress.setIndeterminate(true); render();
        worker.execute(() -> {
            String error = null; try { AppUpdates.verify(this, release, AppUpdates.file(this, release)); } catch (Exception e) { error = e.getMessage(); }
            String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false; ready = failure == null; failed = !ready; progress.setVisibility(View.GONE);
                status.setText(ready ? "下载完成，可以安装更新" : "无法安装：" + failure); render();
                if (ready && installAfterDownload && resumed) { installAfterDownload = false; install(); }
            });
        });
    }
    private void install() {
        if (!ready || release == null || busy) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("允许 LibreTV 安装更新")
                .setMessage("首次更新需要在下一页允许此应用安装软件，然后返回继续安装。")
                .setPositiveButton("去设置", (d, w) -> {
                    try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), 701); }
                    catch (Exception e) { status.setText("请在系统设置中允许 LibreTV 安装应用，再点击「安装更新」"); }
                }).setNegativeButton("稍后", null).show(); return;
        }
        busy = true; render();
        worker.execute(() -> {
            String error = null;
            try { AppUpdates.verify(this, release, AppUpdates.file(this, release)); } catch (Exception e) { error = e.getMessage(); }
            String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return; busy = false; render();
                if (failure != null) { ready = false; failed = true; status.setText("无法安装：" + failure); render(); return; }
                try {
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", AppUpdates.file(this, release));
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    status.setText("请在系统页面确认安装；若取消，可再次点击「安装更新」");
                } catch (Exception e) { status.setText("无法打开系统安装器：" + e.getMessage()); }
            });
        });
    }
    private void clearDownload() {
        handler.removeCallbacks(poll);
        prefs.edit().remove("downloadId").remove("pending").remove("state").remove("error").remove("received").remove("total").remove("startedAt").apply();
        stopService(new Intent(this, UpdateDownloadService.class));
        if (release != null) AppUpdates.file(this, release).delete();
        downloadId = -1; release = null; ready = failed = installAfterDownload = false;
        if (progress != null) progress.setVisibility(View.GONE);
    }
    private void editAddress() {
        EditText input = Ui.input(this, "https://…/update.json"); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); input.setText(endpoint());
        LinearLayout body = Ui.column(this); Ui.pad(body, 20); body.addView(input); Ui.gap(body, 12);
        body.addView(Ui.text(this, "公网使用 HTTPS 地址。同一 Wi-Fi 更新可填写电脑的局域网 IP 地址。", 13, Ui.MUTED));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("更新地址").setView(body).setPositiveButton("保存并检查", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!AppUpdates.validUrl(url)) { input.setError("请填写有效的 HTTPS 地址或局域网 HTTP 地址"); return; }
            prefs.edit().putString("channel", url).apply(); dialog.dismiss(); checkUpdate();
        })); dialog.show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 701) { if (getPackageManager().canRequestPackageInstalls()) install(); else status.setText("尚未允许安装，可再次点击「安装更新」重试"); }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; pollDownload(); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); CharSequence text = status.getText(); buildUi(); status.setText(text); pollDownload(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
