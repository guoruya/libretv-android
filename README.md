# LibreTV Android 1.3.2

面向 Android 手机和平板的独立客户端，最低 Android 8.0。安装后直接连接视频源，不需要 Mac、Node.js 或自建服务器，也不依赖 Google Play 服务。

## 安装与升级

下载 [LibreTV-1.3.2.apk](https://github.com/guoruya/libretv-android/releases/download/v1.3.2/LibreTV-1.3.2.apk)，直接覆盖旧版，不要卸载。与旧版使用同一包名和签名，保留观看记录、收藏、下载、自定义源和启用状态。

全屏播放时，进度条、顶部选集/菜单和全屏按钮会在 3 秒无操作后一起隐藏；轻点画面临时唤出，不会暂停；暂停后持续显示。锁屏模式的解锁按钮也会自动隐藏，轻点画面可唤出，返回键仍可解锁。

从「设置 → 应用更新 → 检查更新」进入：查看当前版本与新版说明 → 点击「确认更新」→ 自动下载 → 按安卓系统提示确认安装。单独检查版本不会下载 APK。首次需要允许 LibreTV 安装应用，随后可直接在应用内更新，不用再手动传 APK。下载使用自己的前台服务，支持退到后台后继续；若系统终止任务，会提示重新下载。下载完成后检查包名、版本、系统要求和签名，系统安装器再次校验。不能静默安装。

默认使用 GitHub 公网更新，不需要电脑开机：[下载最新版](https://github.com/guoruya/libretv-android/releases/latest)。更新地址为 `https://github.com/guoruya/libretv-android/releases/latest/download/update.json`。影片播放直接连接视频源；GitHub 只提供应用与配套源码，不保存观看记录。

已有 1.3.1 的用户可从首页「检查更新」直接升级到 1.3.2，之后使用「设置」里的更新入口。已有 1.3.0 的用户可以在「检查更新 → 更新地址」填写上述地址，直接下载升级；更早版本首次需手动覆盖安装。1.3.1 及以上默认使用公网地址；手动配置过的其他更新地址会保留。设备所在网络需要能访问 GitHub 及其附件下载域名。

后续发布：增加 `app/build.gradle` 的版本号，编辑 `updates/release-notes.txt`，运行 `./build-apk.sh`，生成 APK、源码 ZIP 和 `dist/update.json`。版本信息指向具体版本的 APK，避免新版本发布时旧下载地址发生变化。提交源码、创建相应版本标签，在 GitHub Release 上传三个文件并设为最新正式发布。签名密钥只保存在构建机器，仓库、APK 及源码 ZIP 均不包含私钥或访问令牌。

`updates/server.mjs` 保留为可选的同 Wi-Fi 更新服务（4175 端口），只提供更新文件，不代理影片或网页接口。使用公网更新无需运行此服务。

网页试用：在项目目录运行 `node preview/server.mjs`，用本机浏览器打开 <http://localhost:4174>。可播放随源码提供的测试片，验证全屏、倍速、选集、连播、锁屏和画面比例；榜单及真实来源搜索复用本机 8080 端口的 LibreTV 服务。该预览只监听本机，不是平板安装版；网页历史/收藏独立存储，离线下载与安卓生命周期须在 APK 中测试。

1.1.1 同步 Mac 新增的猫眼资源，内置源由 12 个增加至 13 个。更新后猫眼默认启用；若以前手动添加过同一接口，不会重复显示，之前的停用设置也会保留。平板独立保存来源配置，修改 Mac 的源列表不会自动推送到平板。

猫眼已在 Android 模拟器上通过《鬼灭之刃立志篇》搜索、选集和 1080P 起播验证；同一集跳转至 45 秒时持续缓冲，尚未确定原因。若遇到这一情况，可切换其他来源。完整记录见 `TESTING.md`。

Huawei MatePad 11 上仍需系统支持 Android APK。HarmonyOS 2/3/4 的 Android 应用兼容层是此包的目标使用环境；HarmonyOS 5 及更高版本若通过其他兼容工具运行，未做实机验证。具体以设备现有系统及安装结果为准。

## 新版功能

- **榜单**：电影周榜、国产剧周榜、海外剧周榜、国内综艺、海外综艺、百度热播剧。海报、评分和排名读取真实接口；百度热度不冒充评分。点击海报搜索可播放来源。
- **电影**：热门、最新、经典、豆瓣高分、冷门佳片、华语、欧美、韩国、日本、动画。
- **剧集**：热门、美剧、英剧、韩剧、日剧、国产剧、港剧、日本动画、综艺、纪录片。
- **新番**：热门动漫推荐，以及周一到周日的 Bangumi 放送日历。Bangumi 在部分直连网络不可达，日历失败时会提示切换「热门动漫」，不会把推荐伪装成当天放送。
- **片库**：浏览所选视频源的最新更新，支持切换来源和分页。
- **搜索**：同时查询启用的视频源，结果陆续显示；相同片名和年份合并为一张卡片，解说、不同年份及有区别的版本保留独立。支持最近搜索。详情显示简介、主演、导演、地区、更新状态，可收藏和选择来源。
- **收藏 / 历史**：收藏想看的影片；保留最近 40 部影片的集数和播放进度，点击历史继续播放。
- **播放**：横屏分栏选集、全屏、锁定屏幕、画面比例、系统画中画小窗、快进快退、倍速、播放失败重试、直接换源并保留集数及进度、自动连播开关、按影片设置片头/片尾跳过秒数。小窗需要系统支持并允许画中画。
- **下载**：下载当前一集，管理暂停、继续、重试、删除；完整下载后从「下载」进入离线播放，不再请求影片详情接口。下载存在应用私有目录，卸载会删除。系统可能限制长时间后台下载；中断或失败后可从下载页重试续传。

内置 13 个视频源（含猫眼资源），清单在 `app/src/main/assets/sources.json`。「视频源」可启用、停用或添加 Apple CMS JSON 接口。推荐内容缓存在本机，刷新失败时会明确标注缓存时间。

此版聚焦截图中的榜单、搜索、选集和播放流程；直播列表、投屏、视频截图和亮色主题尚未实现。网站解析页、加密会员平台与 DRM 内容不支持。推荐榜单不保证每部影片都有可用视频源。海报、画质、媒体链接和可用性由第三方决定，首次搜索、推荐和在线播放需要联网。

## 构建

使用 JDK 17 或 21、Android SDK 36 / Build Tools 35.0.0。Gradle Wrapper 使用 Gradle 8.13，Android Studio 可直接打开本目录。

1. 在 `local.properties` 设置 `sdk.dir=/你的/Android/sdk`，或设置 `ANDROID_HOME`。
2. 运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`，得到 `app/build/outputs/apk/debug/app-debug.apk`。Debug 使用 `.debug` 包名后缀，测试不会覆盖正式版的记录。
3. 自用正式包需要自己的签名密钥。使用 JDK 的 `keytool -genkeypair` 创建密钥，然后建立不提交到 Git 的 `signing.properties`：

```properties
storeFile=/绝对路径/libretv-release.jks
storePassword=你的密钥库密码
keyAlias=libretv
keyPassword=你的密钥密码
```

4. 执行 `./build-apk.sh`，得到 `dist/LibreTV-1.3.2.apk`。Windows 可使用 `gradlew.bat assembleRelease`。

后续更新应保留同一签名密钥并增加 `versionCode`。更换签名无法直接覆盖旧版。密钥、密码和机器专用配置均不包含在源码压缩包中。

## 验证

`testDebugUnitTest` 验证 CMS 解析、分页、直接媒体 URL、无效协议、参数编码、推荐数据解析、评分真实性、同片合并和旧格式兼容。`lintDebug` 检查 Android API 使用。

`./gradlew connectedDebugAndroidTest` 包含需要联网的真实榜单、聚合搜索及内置源 HLS 播放/跳转/后台恢复/Activity 重建测试。另使用仓库中自动生成的彩条 HLS 样片，通过本机测试服务器验证下载、暂停、续传、删除，以及关闭服务器后的离线播放。样片只打包进测试 APK，不进入正式 APK。

实际结果和限制见 `TESTING.md`。联网测试会受第三方接口状态影响。

## 项目与许可

这是围绕 LibreTV 使用方式制作的非官方独立原生 Android 客户端，没有将 Next.js 服务器嵌入 APK，也未改动 Mac 上的部署。视频源配置沿用本机配置，CMS、推荐接口和榜单分类参考 LibreTV；按 AGPL-3.0-or-later 提供，见 `LICENSE`。源码和 APK 一同交付。

播放器和下载使用 AndroidX Media3 1.11.1（Apache-2.0），构建使用 Android Gradle Plugin 8.13.2。参考：[Media3 播放器](https://developer.android.com/media/media3/exoplayer/hello-world)、[Media3 离线下载](https://developer.android.com/media/media3/exoplayer/downloading-media)。推荐来自豆瓣、60s 榜单接口及 Bangumi；应用不内置虚构影片或评分。

权限包含联网、网络状态、播放器唤醒锁、下载前台服务、下载通知、下载调度恢复和请求安装应用。安装权限仅用于用户主动更新自己的应用。下载通知在用户开始下载时按系统要求请求权限。不使用 WebView，不执行源站脚本，Android 应用不包含监听服务；可选的局域网更新文件服务单独运行在电脑上。HTTP/HTTPS 来源和重定向经过协议校验，HTTPS 使用系统证书校验。记录、收藏、配置和下载均保存在应用私有目录。
