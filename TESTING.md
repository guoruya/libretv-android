# 1.3.0 验证（2026-10-23）

- 原生 Android 平板模拟器：全屏播放后 3 秒隐藏 Media3 控制条及顶部菜单；点击画面恢复控件且持续播放；暂停超过 3 秒仍显示；锁屏解锁按钮自动隐藏并可轻点唤出。选集、重建、返回退出及画中画继续播放通过。
- 网页 1280×800：网页全屏占满视口，播放后顶部/底部控件均隐藏；轻点画面不暂停，暂停后持续显示。嵌入式浏览器的系统全屏会被宿主退出，提供「更多 → 网页全屏」用于稳定测试。已保存播放及暂停截图。
- 更新流程：检查、下载、重新打开更新页、取消、最新版提示、网络 503 失败提示通过；包名不符、签名不符、版本不符、损坏 APK 均拒绝。
- 完整系统升级：Debug 包 versionCode 5 通过应用下载 versionCode 6 测试包，操作系统安装确认后覆盖成功；升级后的另一次测试确认收藏、集数和 42 秒进度保留。覆盖时被测进程会被系统替换，原安装测试日志出现 `Process crashed`，成功依据是安装后 versionCode 6 和独立的数据保留测试通过。测试包不作为正式版交付。
- 系统 DownloadManager 在模拟器未通过互联网探测的网络上一直等待 CONNECTIVITY；已改用应用前台服务下载，实际本地下载与系统安装测试通过，不需要 Google 服务。
- 本机 Node 测试覆盖预览访问隔离、更新文件白名单、Range 下载、最新版本文件切换；保留旧 APK 地址供尚未完成的下载使用。
- 最终构建：13 个 JVM 单元测试通过，Lint 0 errors / 22 warnings（API/依赖版本、国际化等提示）；APK 签名验证通过，正式包 versionCode 5。
- 已安装的正式版从首页点击「检查更新」，通过预置局域网地址连接 Mac，显示「已是最新版本」。Mac 服务由 `com.libretv.updates` LaunchAgent 在登录后启动；日志位于 `~/Library/Logs/LibreTV/`。
- 未连接用户 Huawei MatePad 11 实机；平板上的安装权限页面、同 Wi-Fi 连通性及华为后台限制仍需实机确认。公网更新未发布，此段记录的是 1.3.0 的 Mac 同 Wi-Fi 更新验证；1.3.1 改为公网地址。

测试输出保存在本次开发机器 `/tmp/libretv-v13-*.log`；截图位于 `dist/v13-*.png` / `.jpg`。测试 HLS 不进入正式 APK。

更新安装的专项测试需显式传 `-e updateFixture true`，默认测试会跳过，防止运行普通测试时替换被测应用。准备步骤：安装 Debug versionCode 5，将同包名同签名的 versionCode 6 APK 放入 Debug 外部文件目录 `update-test/candidate.apk`；不同签名的副本放入 `wrong-signer.apk`，其他包名 APK 放入 `other-package.apk`。单独运行 `UpdateSmokeTest#installUpdateFixture`，确认安装后再单独运行 `#verifyDataAfterSystemUpgrade`。只操作 `.debug` 包，保留正式版数据。

参考：[Android 安装来源权限](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)、[Media3 PlayerView](https://developer.android.com/reference/androidx/media3/ui/PlayerView)。

---

# 1.2.0 播放器与网页试用验证 · 2026-10-23（台北时间）

- 修复原因：旧版未绑定 Media3 的全屏按钮监听器，仅提供视频下方的独立按钮。现在原生控制条和收起后的备用入口均可用；全屏顶部保留选集与更多设置。
- 构建：11 项单元测试通过，Lint 0 错误、16 警告，正式包 1.2.0 / versionCode 4，minSdk 26，targetSdk 35；APK v2 签名验证通过。
- Android 15 ARM64 模拟器：1280×800 dp 平板和 640×360 dp 小横屏均通过播放器控制测试。实测控制条隐藏/显示时的全屏入口、进入/退出全屏、隐藏系统栏、暂停状态切换全屏后重新出画面、画面比例、锁屏/返回解锁、全屏内选集、Activity 重建、小窗持续播放。最终日志分别为 /tmp/libretv-v12-tablet-final.log（8.816 秒）与 /tmp/libretv-v12-compact-final.log（8.306 秒）。
- 三项设备回归通过：全屏/小窗控制、换源保留进度与片头片尾/自动连播、HLS 暂停/续传/离线播放/删除。记录在 /tmp/libretv-v12-fixtures.log。该批回归后的控制条显示调整又单独通过上述两种尺寸测试。
- 网页：真实电影周榜返回 10 部；搜索“大闹天宫”返回 19 个合并结果、46 条资源。U酷来源实际解码 608×480，播放至 02:07；重新从历史打开后继续至 03:54。采用已有 hls.js 播放代理 HLS，原生浏览器 HLS 作为不支持 MSE 时的回退。
- 网页操作实测：全屏内选集、倍速、锁屏/解锁/退出、自动连播、比例设置、片头片尾设置保存、真实来源选择/失败重试、观看历史恢复、离开播放页暂停。检查了 1280×800、800×1280、360×640 视口；修复窄屏溢出后 360px 页面无横向滚动，全屏按钮右边界 340px、宽 67px。
- 本地预览服务的测试通过：静态页面与 HLS 片段可访问，私密配置路径、未开放 API、外部 Host 和跨站 Origin 请求被拒绝。没有修改原网页版的认证或上游地址校验。
- 截图：dist/v12-tablet-*.png、dist/v12-compact-*.png 和 dist/v12-web-preview.jpg。

网页是操作预览，不能代替安卓设备测试；本机地址仅在 Mac 上可用。安卓 APK 仍独立运行，保留当前 13 个来源（含猫眼）。未连接实体 MatePad，华为系统的小窗权限和系统手势仍需实机确认；部分浏览器不提供画中画接口。直播、投屏、视频截图和亮色主题未加入。原有猫眼跳转缓冲的实测限制保留如下。

参考 API：[Android 画中画](https://developer.android.com/develop/ui/views/picture-in-picture)、[浏览器全屏](https://developer.mozilla.org/en-US/docs/Web/API/Fullscreen_API)、[浏览器画中画](https://developer.mozilla.org/en-US/docs/Web/API/Picture-in-Picture_API)。

# 1.1.1 验证记录 · 2026-10-23（台北时间）

环境：Android 15 ARM64 平板模拟器。实体 MatePad 未连接，未安装到实体平板。

- 内置资源从 12 个增加至 13 个，新增猫眼资源。直接读取正式 APK 的 `assets/sources.json`，确认来源列表与 Mac 导出的 `LibreTV 视频源.json` 完全一致。
- 沿用现有 Store 逻辑：每次读取内置和自定义来源，按 URL 去重；停用列表单独保存。新增源默认启用；已有相同自定义源不重复，已有开关、收藏、观看进度不被重置。
- `./build-apk.sh` 成功；11 项单元测试通过（代码未变化，Gradle 复用通过结果），Lint 无错误、16 项警告；正式包版本 `1.1.1 / 3`。
- 新旧 APK 的 v2 签名验证均通过，签名证书相同；模拟器从已安装的 `1.1.0 / 2` 执行 `adb install -r` 覆盖升级成功。
- 现有收藏/设置/旧进度持久化测试通过；现有全屏按钮、锁定/返回、选集、Activity 重建、画中画继续播放测试通过。
- 猫眼《鬼灭之刃立志篇》通过真实搜索、详情、起播，解码尺寸 1920×1080，首次播放到 2418 ms。跳转至 45 秒的检查连续两次未通过，第二次超时状态为 `STATE_BUFFERING`，当前位置及缓冲位置均为 45000 ms、播放意图为 true、无播放抑制。尚未确定是源片段、播放器兼容性还是模拟器环境所致，不能表述为完整播放回归通过。
- 原有 360 源《大闹天宫》播放、跳转、后台恢复、Activity 重建测试通过：起播 2253 ms，保存 45289 ms，恢复 45737 ms。猫眼跳转问题没有在该样片上重现。

源列表同步及升级已验证；实体设备、三部动漫的所有集数、长时间播放未验证。猫眼发生卡顿时可切换其他来源。

联网播放测试可通过 `sourceName`、`query`、`sampleTitle` 三个 instrumentation 参数指定样片；默认仍是 360 源。猫眼检查的参数为 `猫眼资源`、`鬼灭之刃`、`鬼灭之刃立志篇`，超时会输出实际播放和缓冲状态。

# 1.1.0 验证记录 · 2026-10-22（台北时间）

环境：Android 15 ARM64 平板模拟器，1280×800 dp；另外检查了 360×640 dp 手机竖屏。没有连接用户的实体 MatePad。

- 构建与检查：`testDebugUnitTest` 11 项通过，`lintDebug` 0 错误、14 警告，`assembleRelease` 成功。Lint 警告主要为中文文案未国际化、采用的工具版本及系统属性兼容提示。
- 设备测试分批完成 5 项：真实榜单/分类/搜索、收藏和设置持久化、真实来源在线播放及续播、HLS 暂停/续传/离线播放/删除、换源保留进度与片头片尾/自动连播。
- 六榜单实测返回 10 / 10 / 10 / 6 / 10 / 10 部影片；电影、剧集、热门动漫分别返回 50 部。聚合搜索「大闹天宫」得到 19～20 个合并结果（第三方返回会变化）。
- 在线播放：内置 360 源样片实际播放到 2371 ms，跳转到 45 秒后保存 45023 ms，后台恢复及 Activity 重建后继续到 45351 ms，画面 448×336。
- 离线下载：生成的 18 秒彩条 HLS 样片，下载并验证暂停与继续，完成 1,613,004 字节；关闭测试服务器后以 320×180 播放，Activity 重建后仍前进至 3051 ms；删除后下载索引中的记录消失。测试样片仅打包进测试 APK。
- 本地双集样片：从来源 A 切换到 B 保留至少 7 秒播放位置；片头从 5 秒开始；片尾跳过后自动进入第二集；关闭自动连播后到结尾停止。
- 正式包实际操作：榜单点击搜索、影片详情、收藏、选择 360 源播放、直接切换量子源后显示 1920×880 解码尺寸；横屏播放与选集分栏可用。尺寸不等同于源画质评级。
- 覆盖升级：在已有 1.0.0 正式包上执行 `adb install -r` 成功。旧影片标题、集数和 3 分钟观看进度保留。最后构建的正式包再次覆盖安装并启动成功。
- 签名：APK Signature Scheme v2 验证通过。包名 `app.libretv.android`，版本 `1.1.0 / 2`，最低 SDK 26、目标 SDK 35；没有架构限定的本地库。12 个来源仍在 APK 资产中。

## 实测中处理的边界

- 同源不同版本会保留为多条资源，但「来源数」按实际接口去重。
- 同片不同来源共享收藏状态，不会换源后重复收藏。
- Android 联网探测可能失败，即使视频源实际可连接。用户主动下载按实际网络请求判断连接结果，并保留存储空间约束；网络失败后可手动重试续传。
- Bangumi 日历在当前模拟器直连网络中连接失败；Mac 系统代理环境可访问。这不代表用户平板必然能访问。新番栏目默认提供可直连的热门动漫，日历失败显示明确提示，可切回热门动漫，未使用旧数据伪装当天日历。

未验证：实体 MatePad、Android 8～14 实机、HarmonyOS 新版兼容工具、所有视频源和解码格式、数小时后台下载及系统重启后的调度。第三方服务可能因地区、网络或时间失效。此版未实现直播、投屏、画中画、视频截图或亮色主题。

截图在 `dist/v11-*.png`。测试资产使用以下命令生成（需要 FFmpeg）：

```sh
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 18 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 -c:a aac -b:a 48k -hls_time 2 -hls_list_size 0 -hls_segment_filename app/src/androidTest/assets/offline/part%02d.ts app/src/androidTest/assets/offline/sample.m3u8
```
