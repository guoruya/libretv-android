package app.libretv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private Store store;
    private final ExecutorService requests = Executors.newFixedThreadPool(6);
    private final List<Future<?>> pending = new ArrayList<>();
    private final List<Library.Video> videos = new ArrayList<>();
    private List<List<Library.Video>> groups = new ArrayList<>();
    private final List<Discovery.Item> recommendations = new ArrayList<>();
    private final List<Library.Source> nextSources = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private Posters posters;
    private EditText search;
    private TextView heading, status, empty;
    private Button more, sourceButton;
    private LinearLayout filters, navigation;
    private ProgressBar spinner;
    private GridView grid;
    private CatalogAdapter adapter;
    private String query = "", tab = "榜单", notice = "";
    private Library.Source browseSource;
    private int generation, page = 1, completed, expected, filterIndex, coverHeight;
    private boolean loading, tablet;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new Store(this); posters = new Posters();
        if (saved != null) { query = saved.getString("query", ""); tab = saved.getString("tab", "榜单"); filterIndex = saved.getInt("filter"); }
        buildUi(); load(false);
    }
    private boolean isDiscovery() { return tab.equals("榜单") || tab.equals("电影") || tab.equals("剧集") || tab.equals("新番"); }
    private void buildUi() {
        String draft = search == null ? query : search.getText().toString();
        tablet = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        LinearLayout frame = tablet ? Ui.row(this) : Ui.column(this); root.addView(frame, new LinearLayout.LayoutParams(-1, -1));
        navigation = tablet ? Ui.column(this) : Ui.row(this); Ui.pad(navigation, tablet ? 16 : 6); navigation.setBackgroundColor(Ui.PANEL);
        if (tablet) frame.addView(navigation, new LinearLayout.LayoutParams(Ui.dp(this, 156), -1));
        else { HorizontalScrollView navScroll = new HorizontalScrollView(this); navScroll.setHorizontalScrollBarEnabled(false); navScroll.addView(navigation); frame.addView(navScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 60))); }
        LinearLayout body = Ui.column(this); Ui.pad(body, tablet ? 24 : 12);
        frame.addView(body, tablet ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout searchRow = Ui.row(this);
        search = Ui.input(this, "输入片名，搜索所有已启用的视频源"); search.setText(draft); search.setId(View.generateViewId());
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_UP)) { search(); return true; } return false;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        Button go = Ui.chip(this, "搜索", true, this::search); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 52)); p.leftMargin = Ui.dp(this, 8); searchRow.addView(go, p);
        if (tablet) { Button recent = Ui.button(this, "最近搜索", this::recentSearches); LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 52)); rp.leftMargin = Ui.dp(this, 8); searchRow.addView(recent, rp); }
        body.addView(searchRow); Ui.gap(body, tablet ? 26 : 16);
        LinearLayout titleRow = Ui.row(this); heading = Ui.title(this, "热播榜单"); heading.setTextSize(tablet ? 27 : 22); heading.setMaxLines(2);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        sourceButton = Ui.button(this, "刷新", () -> { if (tab.equals("片库")) chooseBrowseSource(); else if (tab.equals("历史")) confirmClearHistory(); else load(false); });
        titleRow.addView(sourceButton); body.addView(titleRow); Ui.gap(body, 10);
        HorizontalScrollView filterScroll = new HorizontalScrollView(this); filterScroll.setHorizontalScrollBarEnabled(false); filters = Ui.row(this); filterScroll.addView(filters); body.addView(filterScroll);
        LinearLayout statusRow = Ui.row(this); Ui.gap(body, 10);
        spinner = new ProgressBar(this); statusRow.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        status = Ui.text(this, "", 13, Ui.MUTED); status.setPadding(Ui.dp(this, 6), 0, 0, 0); status.setMinHeight(Ui.dp(this, 36)); status.setGravity(Gravity.CENTER_VERTICAL);
        status.setOnClickListener(v -> { if (!failures.isEmpty()) new AlertDialog.Builder(this).setTitle("连接情况").setMessage(TextUtils.join("\n\n", failures)).setPositiveButton("重试", (d, w) -> load(false)).setNegativeButton("关闭", null).show(); });
        statusRow.addView(status); body.addView(statusRow); Ui.gap(body, 8);
        FrameLayout results = new FrameLayout(this); body.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        grid = new GridView(this); grid.setHorizontalSpacing(Ui.dp(this, 14)); grid.setVerticalSpacing(Ui.dp(this, 18)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setClipToPadding(false); grid.setPadding(0, 0, 0, Ui.dp(this, 12));
        adapter = new CatalogAdapter(); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (isDiscovery()) searchTitle(recommendations.get(position).title);
            else if (tab.equals("历史")) open(groups.get(position).get(0), groups.get(position));
            else details(groups.get(position));
        });
        results.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(this, "", 16, Ui.MUTED); empty.setGravity(Gravity.CENTER); Ui.pad(empty, 24); results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        more = Ui.button(this, "加载更多", () -> load(true)); body.addView(more, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        Ui.insets(this, root); buildNavigation(); buildFilters(); configureGrid(); updateUi();
    }
    private void buildNavigation() {
        navigation.removeAllViews();
        if (tablet) { navigation.addView(Ui.brand(this, 27)); Ui.gap(navigation, 4); navigation.addView(Ui.text(this, "自由观影 · 随身放映", 11, Ui.MUTED)); Ui.gap(navigation, 28); }
        for (String label : new String[]{"榜单", "电影", "剧集", "新番", "片库", "收藏", "历史", "下载"}) {
            Button button = Ui.chip(this, label, tab.equals(label), () -> {
                if (label.equals("下载")) { startActivity(new Intent(this, DownloadsActivity.class)); return; }
                tab = label; filterIndex = 0;
                query = ""; search.setText(""); load(false);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tablet ? -1 : -2, Ui.dp(this, 48));
            if (tablet) p.bottomMargin = Ui.dp(this, 5); else p.rightMargin = Ui.dp(this, 5); navigation.addView(button, p);
        }
        if (tablet) navigation.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        navigation.addView(Ui.button(this, "视频源", this::showSources));
        navigation.addView(Ui.button(this, "设置", this::showSettings));
        if (tablet) { Ui.gap(navigation, 8); navigation.addView(Ui.button(this, "关于", this::showAbout)); }
        else navigation.addView(Ui.button(this, "最近搜索", this::recentSearches));
    }
    private void buildFilters() {
        filters.removeAllViews();
        String[] labels = tab.equals("榜单") ? Discovery.RANKS : tab.equals("电影") ? Discovery.MOVIES : tab.equals("剧集") ? Discovery.TV : tab.equals("新番") ? Discovery.DAYS : new String[0];
        if (filterIndex >= labels.length) filterIndex = 0;
        for (int i = 0; i < labels.length; i++) {
            final int index = i; Button chip = Ui.chip(this, labels[i], i == filterIndex, () -> { filterIndex = index; load(false); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)); p.rightMargin = Ui.dp(this, 8); filters.addView(chip, p);
        }
    }
    private void configureGrid() {
        int width = getResources().getConfiguration().screenWidthDp - (tablet ? 204 : 24);
        boolean wideCards = tab.equals("搜索"); int count = wideCards ? Math.max(1, width / 310) : Math.max(2, width / 155);
        grid.setNumColumns(count); coverHeight = Ui.dp(this, ((width - (count - 1) * 14) / count) * 1.42f);
        grid.setAdapter(adapter);
    }
    private void search() {
        String text = search.getText().toString().trim(); if (text.isEmpty()) { recentSearches(); return; } searchTitle(text);
    }
    private void searchTitle(String title) {
        query = Ui.plain(title); tab = "搜索"; filterIndex = 0; search.setText(query); store.rememberSearch(query);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0); search.clearFocus(); load(false);
    }
    private void recentSearches() {
        JSONArray array = store.searches(); String[] items = new String[array.length()]; for (int i = 0; i < items.length; i++) items[i] = array.optString(i);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("最近搜索").setNegativeButton("关闭", null);
        if (items.length == 0) dialog.setMessage("搜索过的片名会显示在这里。");
        else dialog.setItems(items, (d, index) -> searchTitle(items[index])).setNeutralButton("清空", (d, w) -> store.clearSearches()); dialog.show();
    }
    private void cancelRequests() { generation++; for (Future<?> task : pending) task.cancel(true); pending.clear(); }
    private void load(boolean append) {
        if (append && loading) return;
        cancelRequests(); int token = generation; failures.clear(); notice = "";
        if (!append) { videos.clear(); groups.clear(); recommendations.clear(); nextSources.clear(); page = 0; buildNavigation(); buildFilters(); configureGrid(); grid.setSelection(0); }
        if (isDiscovery()) { loadDiscovery(token); return; }
        if (tab.equals("历史") || tab.equals("收藏")) {
            loading = false; JSONArray items = tab.equals("历史") ? store.history() : store.favorites();
            for (int i = 0; i < items.length(); i++) try { videos.add(Library.Video.fromJson(items.getJSONObject(i))); } catch (Exception ignored) { }
            regroup(); return;
        }
        List<Library.Source> selected = append ? new ArrayList<>(nextSources) : store.sources(true);
        if (!append && tab.equals("片库") && !selected.isEmpty()) {
            if (browseSource == null || !store.enabled(browseSource.url)) {
                browseSource = selected.get(0); for (Library.Source source : selected) if (source.name.contains("360")) browseSource = source;
            }
            selected = new ArrayList<>(); selected.add(browseSource);
        }
        page++; nextSources.clear(); completed = 0; expected = selected.size(); loading = expected > 0; updateUi();
        if (selected.isEmpty()) { empty.setText("还没有启用视频源\n请到「视频源」选择至少一个来源。"); return; }
        String keyword = tab.equals("片库") ? "" : query; int requestedPage = page;
        for (Library.Source source : selected) pending.add(requests.submit(() -> {
            Library.Page result = null; String error = null;
            try { result = Library.search(source, keyword, requestedPage); } catch (Exception e) { error = e.getMessage(); }
            Library.Page outcome = result; String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed() || token != generation) return; completed++;
                if (outcome != null) {
                    java.util.Set<String> known = new java.util.HashSet<>(); for (Library.Video item : videos) known.add(item.key());
                    for (Library.Video item : outcome.videos) if (known.add(item.key())) videos.add(item);
                    if (outcome.more) nextSources.add(source);
                }
                else failures.add(source.name + "：" + (failure == null ? "连接失败" : failure));
                loading = completed < expected; regroup();
            });
        }));
    }
    private void loadDiscovery(int token) {
        String section = tab; int index = filterIndex; String cacheKey = section + index;
        recommendations.addAll(store.cached(cacheKey)); loading = true; updateUi();
        pending.add(requests.submit(() -> {
            List<Discovery.Item> data = null; String error = null;
            try { data = Discovery.fetch(section, index); } catch (Exception e) { error = e.getMessage(); }
            List<Discovery.Item> result = data; String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed() || token != generation) return; loading = false;
                if (result != null) { recommendations.clear(); recommendations.addAll(result); store.cache(cacheKey, result); }
                else {
                    failures.add("推荐数据：" + failure);
                    notice = recommendations.isEmpty() ? "加载失败 · 点此查看或点击刷新重试" : "暂时无法刷新 · 显示 " + android.text.format.DateFormat.format("MM-dd HH:mm", store.cacheTime(cacheKey)) + " 的缓存";
                }
                updateUi();
            });
        }));
    }
    private void regroup() { groups = Discovery.groups(videos); updateUi(); }
    private void updateUi() {
        if (heading == null) return;
        heading.setText(tab.equals("榜单") ? "热播榜单" : tab.equals("电影") ? "发现好电影" : tab.equals("剧集") ? "追剧指南" : tab.equals("新番") ? "动漫与新番" : tab.equals("搜索") ? "“" + query + "”" : tab.equals("历史") ? "继续观看" : tab.equals("收藏") ? "我的收藏" : "片库更新");
        sourceButton.setText(tab.equals("片库") ? (browseSource == null ? "切换来源" : browseSource.name + " ▾") : tab.equals("历史") ? "清空记录" : "刷新");
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        int count = isDiscovery() ? recommendations.size() : groups.size();
        if (!notice.isEmpty()) status.setText(notice);
        else if (isDiscovery()) status.setText((tab.equals("新番") ? (filterIndex == 0 ? "豆瓣动画推荐" : "Bangumi 放送日历") : tab.equals("榜单") && filterIndex == 5 ? "百度热播剧" : "豆瓣推荐") + " · " + count + " 部影片 · 点击海报搜索片源" + (loading ? " · 正在更新" : ""));
        else if (tab.equals("历史")) status.setText(count + " 部影片 · 点击继续播放 · 进度保存在本机");
        else if (tab.equals("收藏")) status.setText(count + " 部收藏 · 在影片详情中添加或取消收藏");
        else status.setText(count + " 部影片 · " + videos.size() + " 条结果" + (loading ? " · 已搜索 " + completed + "/" + expected + " 源" : failures.isEmpty() ? " · 同片多源已合并" : " · " + failures.size() + " 源连接失败，点此查看"));
        empty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        empty.setText(loading ? "正在加载…\n结果会陆续显示" : tab.equals("历史") ? "还没有观看记录\n播放影片后，可在这里继续观看。" : tab.equals("收藏") ? "收藏下一部想看的影片\n搜索影片，打开详情，点击「收藏」。" : tab.equals("新番") && filterIndex > 0 ? "日历暂时无法加载\n请刷新重试，或切换「热门动漫」继续浏览。" : isDiscovery() ? "暂时没有内容\n点击刷新重试，或切换其他栏目。" : "没有找到影片\n试试更短的片名，或切换视频源。");
        more.setVisibility(!loading && !nextSources.isEmpty() && !isDiscovery() && !tab.equals("历史") && !tab.equals("收藏") ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }
    private void details(List<Library.Video> candidates) {
        Library.Video video = candidates.get(0); LinearLayout panel = Ui.column(this); Ui.pad(panel, 20);
        LinearLayout intro = Ui.row(this); intro.setGravity(Gravity.TOP);
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); posters.load(image, video.poster); intro.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 156)));
        LinearLayout info = Ui.column(this); info.setPadding(Ui.dp(this, 16), 0, 0, 0); intro.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(Ui.text(this, video.title, 22, Ui.TEXT)); Ui.gap(info, 10); info.addView(Ui.text(this, video.year + "  " + video.type + "  " + video.area, 14, Ui.ACCENT)); Ui.gap(info, 8); info.addView(Ui.text(this, video.remarks, 14, Ui.MUTED));
        Button favorite = Ui.button(this, store.favorite(video) ? "已收藏 · 取消" : "＋ 收藏", () -> { });
        favorite.setOnClickListener(v -> { store.toggleFavorite(video); favorite.setText(store.favorite(video) ? "已收藏 · 取消" : "＋ 收藏"); if (tab.equals("收藏")) load(false); }); Ui.gap(info, 10); info.addView(favorite); panel.addView(intro);
        if (!video.director.isEmpty()) { Ui.gap(panel, 14); panel.addView(Ui.text(this, "导演  " + video.director, 14, Ui.MUTED)); }
        if (!video.actors.isEmpty()) { Ui.gap(panel, 8); TextView actors = Ui.text(this, "主演  " + video.actors, 14, Ui.MUTED); actors.setMaxLines(3); panel.addView(actors); }
        Ui.gap(panel, 16); TextView description = Ui.text(this, video.description.isEmpty() ? "这个来源暂未提供简介。" : video.description, 15, Ui.TEXT); description.setMaxLines(12); description.setEllipsize(TextUtils.TruncateAt.END); panel.addView(description);
        Ui.gap(panel, 20); panel.addView(Ui.text(this, "选择资源 · " + Discovery.sourceCount(candidates) + " 个来源 / " + candidates.size() + " 条资源", 17, Ui.TEXT)); Ui.gap(panel, 8);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).setNegativeButton("关闭", null).create();
        for (Library.Video item : candidates) { Button source = Ui.button(this, item.source.name + "   ·   " + (item.remarks.isEmpty() ? item.type : item.remarks) + "   ▶", () -> { dialog.dismiss(); open(item, candidates); }); panel.addView(source); Ui.gap(panel, 6); }
        dialog.show(); dialog.getWindow().setLayout(Ui.dp(this, Math.min(680, getResources().getConfiguration().screenWidthDp - 28)), -2);
    }
    private void open(Library.Video video, List<Library.Video> candidates) {
        try { JSONArray alternatives = new JSONArray(); for (Library.Video item : candidates) alternatives.put(item.json());
            startActivityForResult(new Intent(this, PlayerActivity.class).putExtra("video", video.json().toString()).putExtra("alternatives", alternatives.toString()), 100);
        } catch (Exception e) { Toast.makeText(this, "无法打开这部影片", Toast.LENGTH_SHORT).show(); }
    }
    private void chooseBrowseSource() {
        List<Library.Source> sources = store.sources(true); if (sources.isEmpty()) { showSources(); return; }
        String[] names = new String[sources.size()]; for (int i = 0; i < names.length; i++) names[i] = sources.get(i).name;
        new AlertDialog.Builder(this).setTitle("浏览来源的最新更新").setItems(names, (dialog, index) -> { browseSource = sources.get(index); tab = "片库"; load(false); }).setNegativeButton("取消", null).show();
    }
    private void showSettings() {
        LinearLayout body = Ui.column(this); Ui.pad(body, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("设置").setView(scroll).setNegativeButton("关闭", null).create();
        body.addView(Ui.text(this, "应用更新", 18, Ui.TEXT)); Ui.gap(body, 8);
        String version = ""; try { version = AppUpdates.installed(this).versionName; } catch (Exception ignored) { }
        body.addView(Ui.text(this, "当前版本  " + version, 14, Ui.MUTED)); Ui.gap(body, 12);
        body.addView(Ui.chip(this, "检查更新", true, () -> { dialog.dismiss(); startActivity(new Intent(this, UpdateActivity.class)); }));
        Ui.gap(body, 12); body.addView(Ui.text(this, "发现新版后点击「确认更新」，自动下载并进入系统安装页面。更新会保留收藏、观看记录和视频源。", 13, Ui.MUTED));
        Ui.gap(body, 24); body.addView(Ui.button(this, "视频源", () -> { dialog.dismiss(); showSources(); }));
        Ui.gap(body, 8); body.addView(Ui.button(this, "关于 LibreTV", () -> { dialog.dismiss(); showAbout(); }));
        dialog.show();
    }
    private void showSources() {
        List<Library.Source> sources = store.sources(false); String[] names = new String[sources.size()]; boolean[] checks = new boolean[sources.size()];
        for (int i = 0; i < names.length; i++) { names[i] = sources.get(i).name; checks[i] = store.enabled(sources.get(i).url); }
        new AlertDialog.Builder(this).setTitle("视频源 · " + sources.size() + " 个")
            .setMultiChoiceItems(names, checks, (dialog, which, checked) -> checks[which] = checked)
            .setPositiveButton("保存", (dialog, which) -> { for (int i = 0; i < checks.length; i++) store.setEnabled(sources.get(i).url, checks[i]); if (tab.equals("搜索") || tab.equals("片库")) load(false); })
            .setNeutralButton("添加来源", (dialog, which) -> addSource()).setNegativeButton("取消", null).show();
    }
    private void addSource() {
        LinearLayout form = Ui.column(this); Ui.pad(form, 24); EditText name = Ui.input(this, "来源名称"), address = Ui.input(this, "https://…/api.php/provide/vod"); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name); Ui.gap(form, 12); form.addView(address); Ui.gap(form, 12); form.addView(Ui.text(this, "支持 Apple CMS JSON 接口。保存后自动启用。", 13, Ui.MUTED));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加视频源").setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { store.addSource(name.getText().toString().trim(), address.getText().toString().trim()); dialog.dismiss(); showSources(); } catch (Exception e) { address.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void confirmClearHistory() { new AlertDialog.Builder(this).setTitle("清空观看记录？").setMessage("收藏和下载不受影响。").setPositiveButton("清空", (d, w) -> { store.clearHistory(); load(false); }).setNegativeButton("取消", null).show(); }
    private void showAbout() {
        String version = ""; try { version = AppUpdates.installed(this).versionName; } catch (Exception ignored) { }
        new AlertDialog.Builder(this).setTitle("LibreTV " + version)
            .setMessage("独立运行的 Android 平板版\n\n榜单、电影、剧集、新番、片库、收藏、历史与离线下载。影片及推荐来自第三方，实际可用性取决于来源。\n\nLibreTV · AGPL-3.0\nMedia3 · Apache-2.0")
            .setPositiveButton("关闭", null).setNeutralButton("开源许可", (d, w) -> {
                try (java.io.InputStream input = getAssets().open("LICENSE.txt")) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    new AlertDialog.Builder(this).setTitle("AGPL-3.0").setMessage(bytes.toString("UTF-8")).setPositiveButton("关闭", null).show();
                } catch (Exception e) { Toast.makeText(this, "无法读取许可", Toast.LENGTH_SHORT).show(); }
            }).show();
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); buildUi(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == 100 && result == RESULT_OK && data != null && data.getStringExtra("searchTitle") != null) searchTitle(data.getStringExtra("searchTitle")); }
    @Override protected void onResume() { super.onResume(); if (store != null && (tab.equals("历史") || tab.equals("收藏"))) load(false); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("query", query); out.putString("tab", tab); out.putInt("filter", filterIndex); }
    @Override protected void onDestroy() { cancelRequests(); requests.shutdownNow(); posters.close(); super.onDestroy(); }

    private final class CatalogAdapter extends BaseAdapter {
        public int getCount() { return isDiscovery() ? recommendations.size() : groups.size(); }
        public Object getItem(int position) { return position; }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            boolean recommendation = isDiscovery(), wide = tab.equals("搜索");
            Library.Video video = recommendation ? null : groups.get(position).get(0); Discovery.Item item = recommendation ? recommendations.get(position) : null;
            String title = recommendation ? item.title : video.title, posterUrl = recommendation ? item.poster : video.poster;
            LinearLayout card = wide ? Ui.row(MainActivity.this) : Ui.column(MainActivity.this); card.setGravity(Gravity.TOP); card.setBackground(Ui.shape(Ui.PANEL, 10, MainActivity.this)); card.setClipToOutline(true);
            FrameLayout poster = new FrameLayout(MainActivity.this); poster.setBackgroundColor(Ui.TILE);
            TextView placeholder = Ui.text(MainActivity.this, "▶", 27, Ui.MUTED); placeholder.setGravity(Gravity.CENTER); poster.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(MainActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); poster.addView(image, new FrameLayout.LayoutParams(-1, -1)); posters.load(image, posterUrl);
            if (recommendation && item.rank > 0) badge(poster, String.format(java.util.Locale.ROOT, "%02d", item.rank), Gravity.TOP | Gravity.START, item.rank <= 3 ? Ui.ACCENT : Ui.TEXT);
            if (recommendation && !item.rating.isEmpty()) badge(poster, "★ " + item.rating, Gravity.TOP | Gravity.END, Ui.GOLD);
            if (!recommendation && Discovery.sourceCount(groups.get(position)) > 1) badge(poster, Discovery.sourceCount(groups.get(position)) + " 源", Gravity.TOP | Gravity.START, Ui.ACCENT);
            card.addView(poster, new LinearLayout.LayoutParams(wide ? Ui.dp(MainActivity.this, 112) : -1, wide ? Ui.dp(MainActivity.this, 174) : coverHeight));
            LinearLayout text = Ui.column(MainActivity.this); Ui.pad(text, 10); card.addView(text, wide ? new LinearLayout.LayoutParams(0, Ui.dp(MainActivity.this, 174), 1) : new LinearLayout.LayoutParams(-1, -2));
            TextView name = Ui.text(MainActivity.this, title, wide ? 17 : 15, Ui.TEXT); name.setMaxLines(2); name.setMinLines(2); name.setEllipsize(TextUtils.TruncateAt.END); text.addView(name);
            Ui.gap(text, 6); String meta = recommendation ? item.subtitle : video.type + "  " + video.year;
            TextView caption = Ui.text(MainActivity.this, meta, 12, Ui.MUTED); caption.setMaxLines(wide ? 2 : 1); caption.setEllipsize(TextUtils.TruncateAt.END); text.addView(caption);
            if (!recommendation) {
                if (wide) text.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(1, 0, 1)); else Ui.gap(text, 6);
                String label = Discovery.sourceCount(groups.get(position)) > 1 ? Discovery.sourceCount(groups.get(position)) + " 个来源 · 选择播放" : video.source.name + " · " + video.remarks;
                if (tab.equals("历史")) { JSONObject progress = store.progress(video); label = progress.optString("episodeName") + " · 已看 " + progress.optLong("position") / 60000 + " 分钟"; }
                TextView source = Ui.text(MainActivity.this, label, 12, Ui.ACCENT); source.setSingleLine(); source.setEllipsize(TextUtils.TruncateAt.END); text.addView(source);
            }
            card.setContentDescription(Ui.plain(title) + (recommendation ? "，搜索片源" : "，" + Discovery.sourceCount(groups.get(position)) + " 个来源")); return card;
        }
        private void badge(FrameLayout parent, String label, int gravity, int color) {
            TextView badge = Ui.text(MainActivity.this, label, 13, color); badge.setTypeface(null, android.graphics.Typeface.BOLD); badge.setPadding(Ui.dp(MainActivity.this, 7), Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 7), Ui.dp(MainActivity.this, 4)); badge.setBackground(Ui.shape(0xd90a1420, 8, MainActivity.this));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity); p.setMargins(Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 6), Ui.dp(MainActivity.this, 6), 0); parent.addView(badge, p);
        }
    }
}
