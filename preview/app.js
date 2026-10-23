'use strict';
const $ = id => document.getElementById(id);
const video = $('video'), stage = $('stage'), dialog = $('dialog');
const sections = ['播放测试', '榜单', '电影', '剧集', '新番', '收藏', '历史'];
const rankNames = ['电影周榜', '国产剧周榜', '海外剧周榜', '国内综艺', '海外综艺', '百度热播剧'];
const rankIds = ['douban_movie_weekly', 'douban_tv_chinese', 'douban_tv_global', 'douban_show_chinese', 'douban_show_global', 'baidu_teleplay'];
const movieTags = ['热门', '最新', '经典', '高分', '华语', '欧美', '韩国', '日本', '动作', '科幻'];
const tvTags = ['热门', '国产剧', '美剧', '英剧', '韩剧', '日剧', '综艺', '纪录片', '日本动画', '国产动画'];
let current, hls, sources = [], section = '播放测试', tab = 0, episode = 0, rate = 1, idleTimer, noticeTimer, toastTimer;
let catalogRequest, detailRequest, mediaEvents, savedAt = 0, expanded = false, locked = false, resumeAfterLoad = false, mediaReady = false;
const demoItem = { demo: true, name: '播放器操作演示', sourceKey: 'demo', sourceName: '本地测试片', vodId: 'demo', year: '', remarks: '18 秒 · 两集操作测试' };
function element(tag, text, className) { const el = document.createElement(tag); if (text !== undefined) el.textContent = text; if (className) el.className = className; return el; }
function button(label, action, className) { const el = element('button', label, className); el.type = 'button'; el.onclick = action; return el; }
function read(key, fallback) { try { return JSON.parse(localStorage.getItem('libretv-preview-' + key)) ?? fallback; } catch { return fallback; } }
function write(key, value) { try { localStorage.setItem('libretv-preview-' + key, JSON.stringify(value)); } catch { toast('浏览器未允许保存记录，本次播放仍可继续。'); } }
function key(item) { return `${item.sourceKey}:${item.vodId}`; }
function titleKey(item) { return String(item.name).replace(/[\s：:·]/g, '').toLowerCase() + '|' + (item.year || ''); }
function toast(text) { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 5000); }
function notice(text, persistent = false) { $('player-notice').textContent = text; clearTimeout(noticeTimer); if (!persistent) noticeTimer = setTimeout(() => { $('player-notice').textContent = ''; }, 2400); }
function time(seconds) { if (!Number.isFinite(seconds) || seconds < 0) return '00:00'; const s = Math.floor(seconds); return (s >= 3600 ? Math.floor(s / 3600) + ':' : '') + String(Math.floor(s / 60) % 60).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0'); }
async function api(url, options = {}) { const response = await fetch(url, options); const data = await response.json(); if (!response.ok) throw new Error(data.error || `请求失败 (${response.status})`); return data; }
function showDialog(title, body) { const parent = $('watch').hidden ? document.body : stage; if (dialog.parentElement !== parent) parent.append(dialog); $('dialog-title').textContent = title; $('dialog-body').replaceChildren(body); if (!dialog.open) dialog.showModal(); }
function menu(title, items) { const body = element('div', undefined, 'menu-list'); items.forEach(([label, action]) => body.append(button(label, () => { dialog.close(); action(); }))); showDialog(title, body); }
$('dialog-close').onclick = () => dialog.close();
dialog.addEventListener('click', event => { if (event.target === dialog) { const r = dialog.getBoundingClientRect(); if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close(); } });
dialog.addEventListener('close', revealControls);
function updateNav() { $('nav').replaceChildren(...sections.map(name => button(name, () => navigate(name), name === section ? 'active' : ''))); }
function showWatch() { section = '播放测试'; $('watch').hidden = false; $('catalog').hidden = true; updateNav(); }
async function navigate(name) {
  detailRequest?.abort(); catalogRequest?.abort(); section = name; tab = 0; updateNav();
  if (name === '播放测试') { showWatch(); return; }
  saveProgress(); resumeAfterLoad = false; video.pause(); $('watch').hidden = true; $('catalog').hidden = false; await loadCatalog();
}
async function loadCatalog() {
  catalogRequest?.abort(); const request = new AbortController(); catalogRequest = request;
  const selected = section; $('catalog-title').textContent = selected; $('cards').replaceChildren();
  const tags = selected === '榜单' ? rankNames : selected === '电影' ? movieTags : selected === '剧集' ? tvTags : selected === '新番' ? ['热门动漫', '日本动画', '国产动画'] : [];
  $('tabs').replaceChildren(...tags.map((tag, index) => button(tag, () => { tab = index; loadCatalog(); }, index === tab ? 'active' : '')));
  if (selected === '收藏' || selected === '历史') {
    const items = read(selected === '收藏' ? 'favorites' : 'history', []);
    $('catalog-status').textContent = items.length ? `${items.length} 部影片 · 仅此浏览器的记录` : `还没有${selected}，可以先试播或搜索影片。`;
    renderCards(items.map(item => ({ ...item, subtitle: item.position ? `第 ${item.episode + 1} 集 · ${time(item.position)}` : item.remarks })), item => openItem(item)); return;
  }
  $('catalog-status').textContent = '正在加载…';
  const url = selected === '榜单' ? '/api/hot-list?id=' + rankIds[tab] : '/api/douban?' + new URLSearchParams({ type: selected === '电影' ? 'movie' : 'tv', tag: selected === '新番' && tab === 0 ? '日本动画' : tags[tab], pageSize: '50' });
  try {
    const data = await api(url, { signal: request.signal });
    $('catalog-status').textContent = `${tags[tab]} · ${data.items.length} 部 · 点击海报搜索可用资源`;
    renderCards(data.items.map(item => ({ ...item, name: item.title, pic: item.cover })), item => search(item.name));
  } catch (error) { if (!request.signal.aborted) $('catalog-status').textContent = '加载失败：' + error.message + '；点击刷新重试，或先试播演示片。'; }
}
function renderCards(items, select) {
  const fragment = document.createDocumentFragment();
  items.forEach(item => {
    const card = button('', () => select(item), 'poster-card'); card.setAttribute('aria-label', item.name);
    if (/^https?:\/\//i.test(item.pic || '')) {
      const img = element('img'); img.alt = ''; img.loading = 'lazy'; img.referrerPolicy = 'no-referrer'; img.src = '/api/proxy?url=' + encodeURIComponent(item.pic);
      img.onerror = () => img.replaceWith(element('div', item.name.slice(0, 8), 'poster-placeholder')); card.append(img);
    } else card.append(element('div', item.demo ? '▶' : item.name.slice(0, 8), 'poster-placeholder'));
    const caption = element('div', undefined, 'poster-caption'); caption.append(element('b', item.name), element('small', item.subtitle || item.remarks || '点击查找播放资源')); card.append(caption);
    const rating = Number(item.rating); if (rating > 0 && rating <= 10) card.append(element('span', '★ ' + rating, 'rating')); fragment.append(card);
  }); $('cards').replaceChildren(fragment);
}
async function search(query) {
  query = query.trim(); if (!query) return;
  $('search').value = query; detailRequest?.abort(); catalogRequest?.abort(); const request = new AbortController(); catalogRequest = request;
  saveProgress(); resumeAfterLoad = false; video.pause(); section = '搜索'; updateNav(); $('watch').hidden = true; $('catalog').hidden = false; $('catalog-title').textContent = `“${query}”的搜索结果`;
  $('tabs').replaceChildren(); $('cards').replaceChildren(); $('catalog-status').textContent = '正在搜索已内置的视频源…';
  try {
    if (!sources.length) throw new Error('视频源未读取完成，请稍后重试');
    const data = await api('/api/search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ wd: query, sources, filterAdult: true }), signal: request.signal });
    const groups = new Map();
    for (const item of data.list) { const id = titleKey(item); if (!groups.has(id)) groups.set(id, []); const group = groups.get(id); if (!group.some(other => key(other) === key(item))) group.push(item); }
    const items = [...groups.values()].map(choices => ({ ...choices[0], choices, subtitle: `${choices[0].year || '年份未知'} · ${new Set(choices.map(item => item.sourceKey)).size} 个来源` }));
    $('catalog-status').textContent = `${items.length} 部影片 · ${data.list.length} 条资源` + (data.failures?.length ? ` · ${data.failures.length} 个源未响应` : '') + (items.length ? ' · 点击选择来源' : '，试试缩短片名。');
    renderCards(items, item => chooseItemSource(item.choices));
  } catch (error) { if (!request.signal.aborted) $('catalog-status').textContent = '搜索失败：' + error.message; }
}
function chooseItemSource(choices, keepPosition = false) {
  menu(keepPosition ? '切换资源 · 保留集数与进度' : choices[0].name, choices.map(item => [`${item.sourceName} · ${item.remarks || item.typeName || '打开播放'}`, () => openItem(item, choices, keepPosition)]));
}
async function openItem(item, choices = [], keepPosition = false) {
  if (item.demo) { loadDemo(true, item); return; }
  detailRequest?.abort(); const request = new AbortController(); detailRequest = request;
  notice('正在读取选集…', true); toast('正在读取「' + item.sourceName + '」的播放地址…');
  const previousPosition = keepPosition ? video.currentTime : undefined, previousEpisode = episode, wasPlaying = !video.paused;
  try {
    const source = sources.find(s => s.key === item.sourceKey) || { key: item.sourceKey, name: item.sourceName, url: item.sourceUrl };
    const detail = await api('/api/detail?' + new URLSearchParams({ id: item.vodId, source: JSON.stringify(source) }), { signal: request.signal });
    const episodes = detail.episodes.filter(url => { try { const u = new URL(url); return ['https:', 'http:'].includes(u.protocol) && /\.(m3u8|mp4|m4v)$/i.test(u.pathname); } catch { return false; } });
    if (!episodes.length) throw new Error('该来源没有可直接播放的视频，请换一个来源');
    saveProgress(); current = { ...item, name: detail.videoInfo.title || item.name, episodes, choices };
    const history = read('history', []).find(entry => key(entry) === key(item));
    episode = Math.min(episodes.length - 1, Math.max(0, keepPosition ? previousEpisode : history?.episode || 0));
    showWatch(); renderPlayer(); loadMedia(previousPosition ?? history?.position ?? 0, keepPosition ? wasPlaying : true);
  } catch (error) { if (!request.signal.aborted) { notice(error.message, true); toast('读取失败：' + error.message); } }
}
function loadDemo(autoplay = false, history) {
  detailRequest?.abort(); catalogRequest?.abort(); saveProgress(); current = { ...demoItem, episodes: ['/demo/sample.m3u8', '/demo/sample.m3u8'], choices: [] };
  episode = history?.episode || 0; showWatch(); renderPlayer(); loadMedia(history?.position || 0, autoplay);
}
function renderPlayer() {
  $('film-title').textContent = current.name; $('film-meta').textContent = `${current.sourceName} · 第 ${episode + 1} / ${current.episodes.length} 集`;
  $('on-video-title').textContent = current.name + ` · 第 ${episode + 1} 集`; $('source-label').textContent = current.sourceName;
  $('episode-count').textContent = `（${current.episodes.length}）`; $('demo-note').hidden = !current.demo;
  $('episodes').replaceChildren(...current.episodes.map((_, i) => button(`第 ${i + 1} 集`, () => selectEpisode(i), i === episode ? 'active' : '')));
  $('prev-episode').disabled = episode === 0; $('next-episode').disabled = episode + 1 >= current.episodes.length;
  $('favorite').textContent = read('favorites', []).some(item => titleKey(item) === titleKey(current)) ? '已收藏' : '收藏';
  $('autonext').checked = read('autonext', true);
}
function skipValues() { return read('skip', {})[titleKey(current)] || { intro: 0, outro: 0 }; }
function loadMedia(position = 0, autoplay = true) {
  mediaReady = false;
  mediaEvents?.abort(); mediaEvents = new AbortController(); const { signal } = mediaEvents;
  hls?.destroy(); hls = undefined; video.pause(); resumeAfterLoad = autoplay; notice('正在连接视频…', true); $('quality').textContent = '正在加载…'; $('time').textContent = '00:00 / 00:00'; $('seek').value = 0;
  const url = current.episodes[episode], playbackUrl = current.demo ? url : '/api/proxy?url=' + encodeURIComponent(url);
  video.addEventListener('loadedmetadata', () => {
    mediaReady = true;
    const start = position || skipValues().intro;
    video.currentTime = Math.min(Math.max(0, start), Number.isFinite(video.duration) ? Math.max(0, video.duration - 0.1) : start);
    video.playbackRate = rate; updateTime(); $('quality').textContent = `${video.videoWidth} × ${video.videoHeight} · ${rate} 倍速`;
    notice(''); if (resumeAfterLoad) play();
  }, { once: true, signal });
  if (/\.m3u8(?:\?|$)/i.test(url) && window.Hls?.isSupported()) {
    hls = new Hls({ maxBufferLength: 30 }); hls.loadSource(playbackUrl); hls.attachMedia(video);
    hls.on(Hls.Events.ERROR, (_, data) => { if (data.fatal) { notice('视频加载失败，请在「更多」中重试或换源。', true); $('quality').textContent = `加载失败 · ${data.details}`; } });
  } else if (/\.m3u8(?:\?|$)/i.test(url) && !video.canPlayType('application/vnd.apple.mpegurl')) { notice('此浏览器不支持 HLS 播放，请用 Safari、Chrome 或 Edge。', true); }
  else { video.src = playbackUrl; video.load(); }
  revealControls();
}
async function play() { try { await video.play(); } catch (error) { if (error.name !== 'AbortError') notice(error.name === 'NotAllowedError' ? '点击播放按钮开始播放' : '视频暂时无法播放，可在「更多」中重试或换源。', true); } }
function togglePlay() { if (locked) return; if (video.paused || video.ended) play(); else video.pause(); }
function selectEpisode(index) { if (index < 0 || index >= current.episodes.length) { notice(index < 0 ? '已经是第一集' : '已经是最后一集'); return; } saveProgress(); episode = index; renderPlayer(); loadMedia(0, true); }
function saveProgress() {
  if (!current || !mediaReady || !Number.isFinite(video.duration) || video.currentTime < 0.1) return;
  const { episodes, choices, ...item } = current;
  const history = read('history', []).filter(entry => key(entry) !== key(item));
  history.unshift({ ...item, episode, position: video.currentTime, updated: Date.now() }); write('history', history.slice(0, 40));
}
function updateTime() { $('time').textContent = `${time(video.currentTime)} / ${time(video.duration)}`; $('seek').max = Number.isFinite(video.duration) ? video.duration : 100; $('seek').value = video.currentTime || 0; }
video.addEventListener('timeupdate', () => {
  updateTime(); if (!current) return;
  const { intro, outro } = skipValues();
  if (!video.paused && outro > 0 && video.duration > intro + outro + 10 && video.currentTime >= video.duration - outro && video.currentTime < video.duration - .1) video.currentTime = video.duration;
  if (Date.now() - savedAt > 5000) { savedAt = Date.now(); saveProgress(); }
});
video.addEventListener('ended', () => { saveProgress(); if (read('autonext', true) && episode + 1 < current.episodes.length) selectEpisode(episode + 1); });
video.addEventListener('play', () => { $('play').textContent = 'Ⅱ'; $('play').setAttribute('aria-label', '暂停'); $('center-play').hidden = true; notice(''); revealControls(); });
video.addEventListener('pause', () => { $('play').textContent = '▶'; $('play').setAttribute('aria-label', '播放'); $('center-play').hidden = false; revealControls(); });
video.addEventListener('error', () => { if (!hls && video.error) { $('quality').textContent = '视频加载失败'; notice('播放失败，请在「更多」中重试或换源。', true); } });
video.addEventListener('volumechange', () => { $('mute').textContent = video.muted ? '静音' : '声音'; $('mute').setAttribute('aria-label', video.muted ? '取消静音' : '静音'); $('volume').value = video.muted ? 0 : video.volume; });
video.addEventListener('click', revealControls);
video.addEventListener('dblclick', () => { if (!locked) toggleFullscreen(); });
function seekBy(seconds) { if (!locked && Number.isFinite(video.duration)) { video.currentTime = Math.min(video.duration, Math.max(0, video.currentTime + seconds)); notice((seconds > 0 ? '快进 ' : '后退 ') + Math.abs(seconds) + ' 秒'); } }
function chooseSpeed() { menu('播放速度', [.5, .75, 1, 1.25, 1.5, 2].map(value => [`${value} 倍${rate === value ? ' · 当前' : ''}`, () => { rate = value; video.playbackRate = value; $('speed').textContent = value + '×'; $('quality').textContent = `${video.videoWidth} × ${video.videoHeight} · ${rate} 倍速`; }])); }
function chooseFit() { menu('画面比例', [['适应屏幕 · 保留完整画面', 'contain'], ['铺满屏幕 · 裁剪边缘', 'cover'], ['拉伸填满', 'fill']].map(([label, fit]) => [label, () => { video.style.objectFit = fit; notice(label); }])); }
function chooseEpisode() { menu('选集 · ' + current.name, current.episodes.map((_, i) => [`第 ${i + 1} 集${i === episode ? ' · 正在播放' : ''}`, () => selectEpisode(i)])); }
async function chooseSource() {
  if (current.demo) { menu('演示视频使用本地测试片', [['重新播放演示片', () => loadMedia(0, true)], ['搜索真实影片测试换源', () => { navigate('榜单'); $('search').focus(); }]]); return; }
  if (current.choices.length > 1) { chooseItemSource(current.choices, true); return; }
  const item = current; notice('正在查找同名影片的其他来源…', true);
  try { const data = await api('/api/search', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ wd: item.name, sources, filterAdult: true }) }); if (current !== item || $('watch').hidden) return;
    const choices = data.list.filter(result => titleKey(result) === titleKey(item)); notice('');
    if (choices.length) { current.choices = choices; chooseItemSource(choices, true); } else notice('暂时没有找到其他来源，可返回榜单重新搜索。');
  } catch (error) { notice(error.message, true); }
}
function skipSettings() {
  const values = skipValues(), form = element('form', undefined, 'dialog-form'); form.style.display = 'block';
  form.append(element('p', '按影片保存，0 表示不跳过。'));
  const inputs = ['片头（秒）', '片尾（秒）'].map((text, index) => { const label = element('label', text), input = element('input'); input.type = 'number'; input.min = '0'; input.max = '600'; input.step = '1'; input.required = true; input.value = index === 0 ? values.intro : values.outro; label.append(input); form.append(label); return input; });
  const submit = button('保存', null, 'primary'); submit.type = 'submit'; form.append(submit);
  form.onsubmit = event => { event.preventDefault(); const [intro, outro] = inputs.map(input => Number(input.value)); if (![intro, outro].every(n => Number.isInteger(n) && n >= 0 && n <= 600)) return; const settings = read('skip', {}); settings[titleKey(current)] = { intro, outro }; write('skip', settings); if (video.currentTime < intro && Number.isFinite(video.duration)) video.currentTime = Math.min(intro, video.duration); dialog.close(); notice('已保存跳过时长'); }; showDialog('跳过片头片尾', form);
}
function isFullscreen() { return !!(document.fullscreenElement || document.webkitFullscreenElement || expanded); }
let nativeWasFullscreen = false;
function expandPage(value) { expanded = value; stage.classList.toggle('expanded', value); document.body.style.overflow = value ? 'hidden' : ''; }
function syncFullscreen() {
  const native = !!(document.fullscreenElement || document.webkitFullscreenElement);
  if (native) nativeWasFullscreen = true;
  else if (nativeWasFullscreen) { nativeWasFullscreen = false; expandPage(false); }
  const full = isFullscreen(); $('exit-full').hidden = !full; $('fullscreen').textContent = full ? '⛶ 退出' : '⛶ 全屏';
  $('fullscreen').setAttribute('aria-label', full ? '退出全屏' : '全屏播放'); if (!full && locked) setLocked(false);
}
function toggleFullscreen() {
  if (locked) return;
  const exit = isFullscreen(); expandPage(!exit);
  // Embedded browsers may leave the native fullscreen request pending; page expansion is immediate.
  try {
    let request;
    if (exit && document.fullscreenElement) request = document.exitFullscreen();
    else if (exit && document.webkitFullscreenElement) request = document.webkitExitFullscreen();
    else if (!exit && stage.requestFullscreen) request = stage.requestFullscreen();
    else if (!exit && stage.webkitRequestFullscreen) request = stage.webkitRequestFullscreen();
    request?.catch(() => { if (expanded) notice('已铺满页面；此浏览器未允许系统全屏。'); });
  } catch { if (expanded) notice('已铺满页面；此浏览器未允许系统全屏。'); }
  syncFullscreen(); revealControls();
}
document.addEventListener('fullscreenchange', syncFullscreen); document.addEventListener('webkitfullscreenchange', syncFullscreen);
function setLocked(value) { locked = value; stage.classList.toggle('locked', locked); $('lock-shield').hidden = !locked; if (locked) $('unlock').focus(); else notice('已解锁'); revealControls(); }
async function lockScreen() { if (!isFullscreen()) await toggleFullscreen(); setLocked(true); notice('已锁定，轻点画面可显示解锁按钮'); }
async function pip() {
  if (video.readyState < 2) { notice('视频开始播放后可开启小窗'); return; }
  try {
    if (document.pictureInPictureElement) await document.exitPictureInPicture();
    else if (document.pictureInPictureEnabled && video.requestPictureInPicture) await video.requestPictureInPicture();
    else if (video.webkitSupportsPresentationMode?.('picture-in-picture')) video.webkitSetPresentationMode('picture-in-picture');
    else notice('此浏览器未开放小窗接口；可在新版 APK 中测试系统小窗。', true);
  } catch { notice('浏览器未允许开启小窗，请先播放视频再试。'); }
}
function more() { menu('播放设置', [['网页全屏 · 铺满页面', () => { expandPage(true); syncFullscreen(); revealControls(); }], ['播放速度 · ' + rate + ' 倍', chooseSpeed], ['画面比例', chooseFit], ['锁定屏幕 · 防止误触', lockScreen], ['小窗播放', pip], ['上一集', () => selectEpisode(episode - 1)], ['下一集', () => selectEpisode(episode + 1)], ['静音 / 恢复声音', () => { video.muted = !video.muted; }], ['跳过片头片尾', skipSettings], ['自动连播 · ' + (read('autonext', true) ? '已开启' : '已关闭'), () => { write('autonext', !read('autonext', true)); renderPlayer(); }], ['切换资源', chooseSource], ['重新连接视频', () => loadMedia(video.currentTime, true)]]); }
let adjustingControl = false;
function revealControls() {
  stage.classList.remove('idle'); clearTimeout(idleTimer);
  if (!video.paused && !dialog.open && !adjustingControl) idleTimer = setTimeout(() => {
    if (video.paused || dialog.open || adjustingControl) return;
    if (stage.contains(document.activeElement) && document.activeElement !== stage) stage.focus({ preventScroll: true });
    stage.classList.add('idle');
  }, 3000);
}
stage.addEventListener('pointermove', revealControls);
stage.addEventListener('pointerdown', event => { adjustingControl = event.target.matches('input[type=range]'); revealControls(); });
window.addEventListener('pointerup', () => { if (adjustingControl) { adjustingControl = false; revealControls(); } });
window.addEventListener('pointercancel', () => { adjustingControl = false; revealControls(); });
stage.addEventListener('keydown', revealControls);
stage.addEventListener('focusin', event => { if (event.target !== stage) revealControls(); });
$('search-form').onsubmit = event => { event.preventDefault(); search($('search').value); };
$('reload').onclick = () => section === '搜索' ? search($('search').value) : loadCatalog();
$('back').onclick = () => navigate('榜单'); $('demo').onclick = () => loadDemo();
$('source').onclick = chooseSource; $('choose-episode').onclick = chooseEpisode; $('more').onclick = more;
$('play').onclick = togglePlay; $('center-play').onclick = togglePlay;
$('rewind').onclick = () => seekBy(-10); $('forward').onclick = () => seekBy(10);
$('seek').oninput = () => { if (!locked && Number.isFinite(video.duration)) video.currentTime = Number($('seek').value); };
$('mute').onclick = () => { video.muted = !video.muted; }; $('volume').oninput = () => { video.volume = Number($('volume').value); video.muted = false; };
$('speed').onclick = chooseSpeed; $('pip').onclick = pip;
$('fullscreen').onclick = toggleFullscreen; $('exit-full').onclick = toggleFullscreen;
$('prev-episode').onclick = () => selectEpisode(episode - 1); $('next-episode').onclick = () => selectEpisode(episode + 1);
$('fit').onclick = chooseFit; $('lock').onclick = lockScreen; $('unlock').onclick = () => setLocked(false); $('skip').onclick = skipSettings;
$('autonext').onchange = () => write('autonext', $('autonext').checked);
$('favorite').onclick = () => { const favorites = read('favorites', []), index = favorites.findIndex(item => titleKey(item) === titleKey(current)); if (index >= 0) favorites.splice(index, 1); else { const { episodes, choices, ...item } = current; favorites.unshift(item); } write('favorites', favorites); renderPlayer(); };
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') { if (locked) { event.preventDefault(); setLocked(false); } else if (expanded && !dialog.open) toggleFullscreen(); return; }
  if (locked || dialog.open || $('watch').hidden || /INPUT|TEXTAREA|SELECT|BUTTON/.test(event.target.tagName)) return;
  if (event.key === ' ') { event.preventDefault(); togglePlay(); } else if (event.key === 'ArrowLeft') { event.preventDefault(); seekBy(-10); } else if (event.key === 'ArrowRight') { event.preventDefault(); seekBy(10); } else if (event.key.toLowerCase() === 'f') { event.preventDefault(); toggleFullscreen(); }
});
window.addEventListener('pagehide', saveProgress);
fetch('/sources.json').then(response => response.json()).then(data => { sources = data.sources.filter(source => !source.isAdult).map((source, index) => ({ ...source, key: 'preview_' + index })); }).catch(() => toast('内置视频源读取失败，请刷新页面。'));
loadDemo();
