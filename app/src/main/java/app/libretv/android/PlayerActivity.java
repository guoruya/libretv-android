package app.libretv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
    private Store store;
    private Library.Video video;
    private Library.Detail detail;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView playbackStatus;
    private BaseAdapter episodesAdapter;
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lineIndex, episodeIndex, detailGeneration, progressTicks;
    private boolean offline;
    private final List<Library.Video> alternatives = new ArrayList<>();
    private long position;
    private String savedLine = "", savedEpisode = "";
    private String stateMessage = "正在连接视频…";
    private float speed = 1f;
    private boolean started, fullScreen, resumePlaying = true;
    private boolean screenLocked, enteringPip;
    private Button unlockButton;
    private final Runnable hideUnlock = () -> { if (unlockButton != null) unlockButton.setVisibility(View.INVISIBLE); };
    private int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private final Runnable saveProgress = new Runnable() {
        public void run() {
            if (++progressTicks % 5 == 0) save();
            if (player != null && player.isPlaying() && detail != null) {
                long duration = player.getDuration(); int outro = store.skip(video, "outro");
                if (outro > 0 && duration > (outro + store.skip(video, "intro") + 10) * 1000L && player.getCurrentPosition() >= duration - outro * 1000L) player.seekTo(duration);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new Store(this);
        try {
            video = Library.Video.fromJson(new JSONObject(saved == null ? getIntent().getStringExtra("video") : saved.getString("video", getIntent().getStringExtra("video"))));
            JSONArray choices = new JSONArray(getIntent().getStringExtra("alternatives") == null ? "[]" : getIntent().getStringExtra("alternatives"));
            for (int i = 0; i < choices.length(); i++) alternatives.add(Library.Video.fromJson(choices.getJSONObject(i)));
        }
        catch (Exception e) { finish(); return; }
        JSONObject progress = store.progress(video);
        savedLine = progress.optString("line"); savedEpisode = progress.optString("episodeName");
        episodeIndex = progress.optInt("episode"); position = progress.optLong("position");
        if (saved != null) {
            savedLine = saved.getString("line", savedLine); savedEpisode = saved.getString("episodeName", savedEpisode);
            episodeIndex = saved.getInt("episode", episodeIndex); position = saved.getLong("position", position);
            fullScreen = saved.getBoolean("fullScreen"); resumePlaying = saved.getBoolean("playing", true); speed = saved.getFloat("speed", 1f);
            screenLocked = saved.getBoolean("screenLocked"); resizeMode = saved.getInt("resizeMode", AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        offline = getIntent().hasExtra("offlineUrl");
        if (offline) {
            String url = getIntent().getStringExtra("offlineUrl"), name = getIntent().getStringExtra("offlineEpisode");
            if (!Library.validUrl(url)) { finish(); return; }
            if (!savedEpisode.equals(name)) position = 0;
            detail = new Library.Detail(video, java.util.Collections.singletonList(new Library.Line("离线下载", java.util.Collections.singletonList(new Library.Episode(name, url)))));
            lineIndex = episodeIndex = 0; buildUi();
        } else fetchDetail();
    }
    private void fetchDetail() {
        int token = ++detailGeneration; Library.Video requested = video;
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); root.setGravity(Gravity.CENTER); Ui.pad(root, 24);
        root.addView(Ui.button(this, "返回", this::finish)); Ui.gap(root, 26);
        root.addView(Ui.title(this, video.title)); Ui.gap(root, 16);
        TextView message = Ui.text(this, "正在从「" + video.source.name + "」读取选集…", 16, Ui.MUTED); root.addView(message);
        ProgressBar progress = new ProgressBar(this); root.addView(progress);
        Ui.insets(this, root);
        worker.execute(() -> {
            try {
                Library.Detail result = Library.detail(requested);
                runOnUiThread(() -> {
                    if (isDestroyed() || token != detailGeneration) return;
                    detail = result; video = result.video; lineIndex = 0;
                    for (int i = 0; i < detail.lines.size(); i++) if (detail.lines.get(i).name.equals(savedLine)) lineIndex = i;
                    List<Library.Episode> episodes = currentLine().episodes;
                    episodeIndex = Math.min(Math.max(0, episodeIndex), episodes.size() - 1);
                    for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).name.equals(savedEpisode)) { episodeIndex = i; break; }
                    buildUi(); if (started) createPlayer();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || token != detailGeneration) return;
                    progress.setVisibility(View.GONE); message.setText("读取失败：" + e.getMessage());
                    Ui.gap(root, 20); root.addView(Ui.button(this, "重试", this::fetchDetail)); Ui.gap(root, 12);
                    root.addView(Ui.button(this, "换个来源", this::switchSource));
                });
            }
        });
    }
    private Library.Line currentLine() { return detail.lines.get(lineIndex); }
    private Library.Episode currentEpisode() { return currentLine().episodes.get(episodeIndex); }
    private void buildUi() {
        if (detail == null) return;
        if (playerView != null) playerView.setPlayer(null);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        playerView = new PlayerView(this); playerView.setPlayer(player); playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setShowSubtitleButton(true); playerView.setShowNextButton(false); playerView.setShowPreviousButton(false);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(false);
        playerView.setControllerAnimationEnabled(false);
        playerView.setResizeMode(resizeMode);
        playerView.setFullscreenButtonClickListener(full -> { if (full != fullScreen) toggleFullScreen(); });
        playerView.setFullscreenButtonState(fullScreen);
        FrameLayout stage = buildStage();
        if (fullScreen || isInPictureInPictureMode()) {
            root.addView(stage, new LinearLayout.LayoutParams(-1, -1));
            playbackStatus = null;
        } else {
            LinearLayout top = Ui.row(this); Ui.pad(top, 12);
            top.addView(Ui.button(this, "‹ 返回", this::finish));
            TextView title = Ui.text(this, video.title, 19, Ui.TEXT); title.setMaxLines(2); title.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (!offline) top.addView(Ui.button(this, "切换资源", this::switchSource)); root.addView(top);
            boolean wide = getResources().getConfiguration().screenWidthDp >= 850 && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            boolean compact = getResources().getConfiguration().screenHeightDp < 500;
            LinearLayout content = wide ? Ui.row(this) : Ui.column(this);
            root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout viewing = Ui.column(this); viewing.setBackgroundColor(0xff0a1420);
            if (wide || compact) viewing.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
            else {
                int height = Math.min(getResources().getConfiguration().screenWidthDp * 9 / 16, getResources().getConfiguration().screenHeightDp / 2);
                viewing.addView(stage, new LinearLayout.LayoutParams(-1, Ui.dp(this, height)));
            }
            LinearLayout tools = Ui.row(this); Ui.pad(tools, 10);
            Button previous = Ui.button(this, "上一集", () -> selectEpisode(episodeIndex - 1));
            Button next = Ui.button(this, "下一集", () -> selectEpisode(episodeIndex + 1));
            Button speedButton = Ui.button(this, "倍速", this::chooseSpeed);
            Button fullscreenButton = Ui.button(this, "全屏", this::toggleFullScreen);
            for (Button button : new Button[]{previous, next, speedButton, fullscreenButton}) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1); p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0); tools.addView(button, p);
            }
            previous.setEnabled(episodeIndex > 0); next.setEnabled(episodeIndex + 1 < currentLine().episodes.size());
            previous.setAlpha(previous.isEnabled() ? 1f : .4f); next.setAlpha(next.isEnabled() ? 1f : .4f);
            viewing.addView(tools);
            HorizontalScrollView extraScroll = new HorizontalScrollView(this); extraScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout extra = Ui.row(this); Ui.pad(extra, 10);
            CheckBox auto = new CheckBox(this); auto.setText("自动连播"); auto.setTextColor(Ui.TEXT); auto.setChecked(store.autoNext()); auto.setMinHeight(Ui.dp(this, 48)); auto.setOnCheckedChangeListener((v, checked) -> store.autoNext(checked)); extra.addView(auto);
            extra.addView(Ui.button(this, "跳过片头片尾", this::skipSettings));
            if (!offline) extra.addView(Ui.button(this, "下载本集", this::downloadEpisode));
            Button favorite = Ui.button(this, store.favorite(video) ? "已收藏" : "收藏", () -> { });
            favorite.setOnClickListener(v -> { store.toggleFavorite(video); favorite.setText(store.favorite(video) ? "已收藏" : "收藏"); }); extra.addView(favorite);
            extraScroll.addView(extra); viewing.addView(extraScroll);
            playbackStatus = Ui.text(this, stateMessage, 12, Ui.MUTED); playbackStatus.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
            playbackStatus.setOnClickListener(v -> { if (player != null && player.getPlayerError() != null) { player.prepare(); player.play(); } });
            viewing.addView(playbackStatus);
            if (compact) { tools.setVisibility(View.GONE); extraScroll.setVisibility(View.GONE); playbackStatus.setVisibility(View.GONE); }
            content.addView(viewing, wide ? new LinearLayout.LayoutParams(0, -1, 1) : compact ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, -2));
            LinearLayout panel = Ui.column(this); Ui.pad(panel, 18); panel.setBackgroundColor(Ui.PANEL);
            panel.addView(Ui.text(this, video.source.name + " · " + currentLine().episodes.size() + " 集", 14, Ui.ACCENT)); Ui.gap(panel, 8);
            Spinner lines = new Spinner(this);
            List<String> names = new ArrayList<>(); for (Library.Line line : detail.lines) names.add(line.name);
            ArrayAdapter<String> lineAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
            lines.setAdapter(lineAdapter); lines.setSelection(lineIndex);
            lines.setContentDescription("播放线路"); panel.addView(lines, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
            lines.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> parent) { }
                public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                    selectLine(index);
                }
            });
            Ui.gap(panel, 8);
            GridView episodes = new GridView(this); episodes.setNumColumns(wide ? 2 : Math.max(3, getResources().getConfiguration().screenWidthDp / 130));
            episodes.setHorizontalSpacing(Ui.dp(this, 8)); episodes.setVerticalSpacing(Ui.dp(this, 8));
            episodesAdapter = new BaseAdapter() {
                public int getCount() { return currentLine().episodes.size(); }
                public Object getItem(int index) { return currentLine().episodes.get(index); }
                public long getItemId(int index) { return index; }
                public View getView(int index, View recycled, ViewGroup parent) {
                    TextView cell = Ui.text(PlayerActivity.this, currentLine().episodes.get(index).name, 14, index == episodeIndex ? Ui.BG : Ui.TEXT);
                    cell.setGravity(Gravity.CENTER); cell.setMaxLines(2); cell.setMinHeight(Ui.dp(PlayerActivity.this, 52)); Ui.pad(cell, 8);
                    cell.setBackground(Ui.shape(index == episodeIndex ? Ui.ACCENT : Ui.TILE, 10, PlayerActivity.this)); return cell;
                }
            };
            episodes.setAdapter(episodesAdapter); episodes.setOnItemClickListener((p, v, index, id) -> selectEpisode(index));
            panel.addView(episodes, new LinearLayout.LayoutParams(-1, 0, 1));
            if (!compact || wide) content.addView(panel, wide ? new LinearLayout.LayoutParams(Ui.dp(this, 300), -1) : new LinearLayout.LayoutParams(-1, 0, 1));
        }
        if (isInPictureInPictureMode()) setContentView(root); else { Ui.insets(this, root); setSystemBars(); }
    }
    private FrameLayout buildStage() {
        handler.removeCallbacks(hideUnlock); unlockButton = null;
        FrameLayout stage = new FrameLayout(this); stage.setBackgroundColor(0xff000000);
        stage.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playerView.setUseController(!screenLocked && !isInPictureInPictureMode());
        if (isInPictureInPictureMode()) return stage;
        if (screenLocked) {
            View blocker = new View(this); blocker.setTag("lock-touch"); blocker.setOnClickListener(v -> revealUnlock());
            stage.addView(blocker, new FrameLayout.LayoutParams(-1, -1));
            Button unlock = Ui.button(this, "解锁屏幕", this::toggleLock); unlock.setContentDescription("解锁屏幕");
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, Ui.dp(this, 48), Gravity.CENTER_VERTICAL | Gravity.START); p.leftMargin = Ui.dp(this, 16);
            stage.addView(unlock, p); unlockButton = unlock; revealUnlock(); return stage;
        }
        LinearLayout overlay = Ui.row(this); Ui.pad(overlay, 8); overlay.setBackgroundColor(0x66000000);
        if (fullScreen) overlay.addView(Ui.button(this, "退出全屏", this::toggleFullScreen));
        TextView label = Ui.text(this, fullScreen ? currentEpisode().name : "", 14, Ui.TEXT); label.setMaxLines(1);
        overlay.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        overlay.addView(Ui.button(this, "选集", this::chooseEpisode));
        overlay.addView(Ui.button(this, "更多", this::playerMenu));
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        overlay.setTag("player-menu");
        // All video overlays follow Media3's tap, timeout and pause behavior together.
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) overlay::setVisibility);
        playerView.showController();
        return stage;
    }
    private void revealUnlock() {
        handler.removeCallbacks(hideUnlock);
        if (unlockButton == null) return;
        unlockButton.setVisibility(View.VISIBLE);
        if (player != null && player.getPlayWhenReady()) handler.postDelayed(hideUnlock, 3000);
    }
    private void chooseEpisode() {
        String[] names = new String[currentLine().episodes.size()];
        for (int i = 0; i < names.length; i++) names[i] = currentLine().episodes.get(i).name;
        new AlertDialog.Builder(this).setTitle("选集 · " + video.title).setSingleChoiceItems(names, episodeIndex, (d, i) -> { d.dismiss(); selectEpisode(i); }).setNegativeButton("关闭", null).show();
    }
    private void selectLine(int index) {
        if (index == lineIndex) return;
        save(); String name = currentEpisode().name; lineIndex = index;
        episodeIndex = Math.min(episodeIndex, currentLine().episodes.size() - 1);
        for (int i = 0; i < currentLine().episodes.size(); i++) if (currentLine().episodes.get(i).name.equals(name)) { episodeIndex = i; break; }
        playCurrent(); buildUi();
    }
    private void playerMenu() {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        labels.add("播放速度 · " + speed + " 倍"); actions.add(this::chooseSpeed);
        labels.add("画面比例"); actions.add(this::chooseResizeMode);
        labels.add("锁定屏幕 · 防止误触"); actions.add(this::toggleLock);
        labels.add("小窗播放"); actions.add(this::enterPip);
        labels.add("上一集"); actions.add(() -> selectEpisode(episodeIndex - 1));
        labels.add("下一集"); actions.add(() -> selectEpisode(episodeIndex + 1));
        labels.add("播放线路 · " + currentLine().name); actions.add(() -> {
            String[] names = new String[detail.lines.size()]; for (int i = 0; i < names.length; i++) names[i] = detail.lines.get(i).name;
            new AlertDialog.Builder(this).setTitle("切换播放线路").setSingleChoiceItems(names, lineIndex, (d, i) -> { d.dismiss(); selectLine(i); }).setNegativeButton("关闭", null).show();
        });
        labels.add("跳过片头片尾"); actions.add(this::skipSettings);
        labels.add("自动连播 · " + (store.autoNext() ? "已开启" : "已关闭")); actions.add(() -> { store.autoNext(!store.autoNext()); buildUi(); });
        if (!offline) {
            labels.add("切换资源"); actions.add(this::switchSource);
            labels.add("下载本集"); actions.add(this::downloadEpisode);
        }
        new AlertDialog.Builder(this).setTitle("播放设置").setItems(labels.toArray(new String[0]), (d, i) -> actions.get(i).run()).setNegativeButton("关闭", null).show();
    }
    private void chooseResizeMode() {
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL};
        int selected = 0; for (int i = 0; i < modes.length; i++) if (modes[i] == resizeMode) selected = i;
        new AlertDialog.Builder(this).setTitle("画面比例").setSingleChoiceItems(new String[]{"适应屏幕 · 保留完整画面", "铺满屏幕 · 裁剪边缘", "拉伸填满"}, selected,
            (d, i) -> { resizeMode = modes[i]; playerView.setResizeMode(resizeMode); d.dismiss(); }).setNegativeButton("关闭", null).show();
    }
    private void toggleLock() {
        screenLocked = !screenLocked;
        if (screenLocked && !fullScreen) toggleFullScreen(); else buildUi();
    }
    private void enterPip() {
        if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) { Toast.makeText(this, "这台设备未提供系统小窗功能", Toast.LENGTH_LONG).show(); return; }
        if (player == null || player.getVideoSize().width == 0) { Toast.makeText(this, "视频开始播放后可开启小窗", Toast.LENGTH_SHORT).show(); return; }
        VideoSize size = player.getVideoSize(); Rational ratio = new Rational(size.width, size.height);
        if (ratio.floatValue() < 0.42f || ratio.floatValue() > 2.39f) ratio = new Rational(16, 9);
        try {
            enteringPip = true;
            if (!enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio).build())) { enteringPip = false; Toast.makeText(this, "请在系统设置中允许 LibreTV 使用画中画", Toast.LENGTH_LONG).show(); }
        } catch (IllegalStateException | IllegalArgumentException e) { enteringPip = false; Toast.makeText(this, "系统暂时无法开启小窗，请检查画中画权限", Toast.LENGTH_LONG).show(); }
    }
    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config); enteringPip = false; screenLocked = false;
        if (detail != null) buildUi();
    }
    private void createPlayer() {
        if (player != null || detail == null) return;
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(Downloads.playback(this, offline)))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true); player.setPlaybackSpeed(speed);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) message("正在缓冲 · " + currentEpisode().name);
                if (state == Player.STATE_READY) showQuality();
                if (state == Player.STATE_ENDED && store.autoNext() && episodeIndex + 1 < currentLine().episodes.size()) selectEpisode(episodeIndex + 1);
            }
            @Override public void onVideoSizeChanged(VideoSize size) { showQuality(); }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) { if (screenLocked) revealUnlock(); }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            @Override public void onPlayerError(PlaybackException error) {
                message("播放失败（" + error.getErrorCodeName() + "）· 点此重试，或点击「换源」");
            }
        });
        playerView.setPlayer(player); playCurrent();
        handler.removeCallbacks(saveProgress); handler.postDelayed(saveProgress, 1000);
    }
    private void playCurrent() {
        if (player == null) return;
        message("正在连接 · " + currentEpisode().name);
        if (position == 0) position = store.skip(video, "intro") * 1000L;
        player.setMediaItem(MediaItem.fromUri(currentEpisode().url), position);
        player.prepare(); player.setPlayWhenReady(resumePlaying);
        if (episodesAdapter != null) episodesAdapter.notifyDataSetChanged();
    }
    private void selectEpisode(int index) {
        if (index < 0 || index >= currentLine().episodes.size()) { Toast.makeText(this, "已经是" + (index < 0 ? "第一集" : "最后一集"), Toast.LENGTH_SHORT).show(); return; }
        save(); episodeIndex = index; position = 0; resumePlaying = true; playCurrent(); buildUi();
    }
    private void showQuality() {
        if (player == null) return;
        VideoSize size = player.getVideoSize();
        message(currentEpisode().name + (size.width > 0 ? " · " + size.width + " × " + size.height : "") + " · " + speed + " 倍速");
    }
    private void message(String text) { stateMessage = text; if (playbackStatus != null) playbackStatus.setText(text); }
    private void chooseSpeed() {
        String[] labels = {"0.75 倍", "1.0 倍", "1.25 倍", "1.5 倍", "2.0 倍"}; float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
        new AlertDialog.Builder(this).setTitle("播放速度").setItems(labels, (d, i) -> { speed = values[i]; if (player != null) player.setPlaybackSpeed(speed); showQuality(); }).show();
    }
    private void switchSource() {
        if (alternatives.size() < 2) { searchAlternatives(); return; }
        String[] labels = new String[alternatives.size() + 1];
        for (int i = 0; i < alternatives.size(); i++) labels[i] = alternatives.get(i).source.name + (alternatives.get(i).key().equals(video.key()) ? " · 当前" : " · " + alternatives.get(i).remarks);
        labels[labels.length - 1] = "搜索更多来源";
        new AlertDialog.Builder(this).setTitle("切换来源 · 保留当前集数和进度").setItems(labels, (d, i) -> { if (i == alternatives.size()) searchAlternatives(); else changeSource(alternatives.get(i)); }).setNegativeButton("取消", null).show();
    }
    private void searchAlternatives() {
        List<Library.Source> sources = store.sources(true); List<Library.Video> found = new ArrayList<>(); List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        LinearLayout body = Ui.column(this); Ui.pad(body, 20); TextView status = Ui.text(this, "正在搜索同名影片…", 14, Ui.MUTED); body.addView(status);
        ListView list = new ListView(this); ArrayAdapter<String> labels = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>()); list.setAdapter(labels); body.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(this, 300)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("其他来源").setView(body).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(d -> { for (java.util.concurrent.Future<?> job : jobs) job.cancel(true); });
        list.setOnItemClickListener((p, v, index, id) -> { alternatives.clear(); alternatives.addAll(found); Library.Video selected = found.get(index); dialog.dismiss(); changeSource(selected); }); dialog.show();
        final int[] done = {0}; String title = video.title, year = video.year;
        if (sources.isEmpty()) status.setText("没有启用的视频源，请返回首页设置。");
        for (Library.Source source : sources) jobs.add(worker.submit(() -> {
            List<Library.Video> matches = new ArrayList<>();
            try { for (Library.Video item : Library.search(source, title, 1, store.filterAdult()).videos) if (Discovery.titleKey(item.title).equals(Discovery.titleKey(title)) && (year.isEmpty() || item.year.isEmpty() || item.year.equals(year))) matches.add(item); } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                done[0]++; for (Library.Video item : matches) { found.add(item); labels.add(item.source.name + " · " + item.remarks); }
                status.setText("已搜索 " + done[0] + "/" + sources.size() + " 个来源 · " + found.size() + " 个结果" + (done[0] == sources.size() && found.isEmpty() ? "，可返回首页缩短片名重试" : ""));
            });
        }));
    }
    private void changeSource(Library.Video selected) {
        if (selected.key().equals(video.key()) && detail != null) return;
        save(); if (detail != null) { savedEpisode = currentEpisode().name; savedLine = ""; }
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        video = selected; detail = null; handler.removeCallbacks(saveProgress); fetchDetail();
    }
    private void downloadEpisode() {
        new AlertDialog.Builder(this).setTitle("下载「" + currentEpisode().name + "」？")
            .setMessage("下载完成后可在首页「下载」中离线观看。会使用当前网络，建议连接 Wi-Fi。")
            .setPositiveButton("下载", (d, w) -> {
                try {
                    Downloads.add(this, video, currentEpisode()); Toast.makeText(this, "已加入下载，可在「下载」栏目查看进度", Toast.LENGTH_LONG).show();
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 40);
                } catch (Exception e) { Toast.makeText(this, "无法开始下载：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("取消", null).show();
    }
    private void skipSettings() {
        LinearLayout form = Ui.column(this); Ui.pad(form, 20);
        form.addView(Ui.text(this, "为这部影片设置跳过时长，0 表示不跳过。", 14, Ui.MUTED)); Ui.gap(form, 12);
        EditText intro = Ui.input(this, "片头秒数"), outro = Ui.input(this, "片尾秒数");
        intro.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); outro.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        intro.setText(String.valueOf(store.skip(video, "intro"))); outro.setText(String.valueOf(store.skip(video, "outro")));
        form.addView(Ui.text(this, "片头（秒）", 13, Ui.MUTED)); form.addView(intro); Ui.gap(form, 12); form.addView(Ui.text(this, "片尾（秒）", 13, Ui.MUTED)); form.addView(outro);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("跳过片头片尾").setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int start = Integer.parseInt(intro.getText().toString()), end = Integer.parseInt(outro.getText().toString());
                if (start < 0 || end < 0 || start > 600 || end > 600) throw new NumberFormatException();
                store.skip(video, start, end); dialog.dismiss();
                if (player != null && player.getCurrentPosition() < start * 1000L) player.seekTo(start * 1000L);
            } catch (NumberFormatException e) { intro.setError("请输入 0～600 秒"); }
        })); dialog.show();
    }
    private void toggleFullScreen() {
        fullScreen = !fullScreen;
        if (!fullScreen) screenLocked = false;
        setRequestedOrientation(fullScreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        buildUi();
    }
    private void setSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (fullScreen) { getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); getWindow().getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
            else getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
        } else getWindow().getDecorView().setSystemUiVisibility(fullScreen ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
    }
    private void save() {
        if (player == null || detail == null) return;
        position = Math.max(0, player.getCurrentPosition());
        store.save(video, currentLine().name, episodeIndex, currentEpisode().name, position);
    }
    @Override protected void onStart() { super.onStart(); started = true; if (detail != null) createPlayer(); }
    @Override protected void onPause() {
        if (player != null) { resumePlaying = player.getPlayWhenReady(); save(); if (!isInPictureInPictureMode() && !enteringPip) player.pause(); }
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (player != null) player.setPlayWhenReady(resumePlaying); }
    @Override protected void onStop() {
        started = false; handler.removeCallbacks(saveProgress); save();
        if (player != null) { playerView.setPlayer(null); player.release(); player = null; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        save(); super.onSaveInstanceState(out);
        try { out.putString("video", video.json().toString()); } catch (Exception ignored) { }
        if (detail != null) { out.putString("line", currentLine().name); out.putString("episodeName", currentEpisode().name); }
        out.putInt("episode", episodeIndex); out.putLong("position", position); out.putBoolean("fullScreen", fullScreen); out.putBoolean("playing", resumePlaying); out.putFloat("speed", speed);
        out.putBoolean("screenLocked", screenLocked); out.putInt("resizeMode", resizeMode);
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); if (detail != null) buildUi(); }
    @Override public void onBackPressed() { if (screenLocked) toggleLock(); else if (fullScreen) toggleFullScreen(); else super.onBackPressed(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
