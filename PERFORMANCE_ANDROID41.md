# 选集提速、可调预缓存与 Android 4.1 兼容适配

## 实施范围

本轮在原项目上实现性能优化，并提供独立的 **Android 4.1–4.4（API 16–19）兼容构建**。现代 Media3 / FFmpeg 版本保留，不通过简单修改 minSdk 冒充旧系统兼容。

后续测试发布为 [0.27.0-test.1 / code54](RELEASE_0.27.0_TEST.md)，不设为latest。本文的设备耗时与日志来自此前0.26.9本机优化验证，不冒充新版本的严格A/B测量。文末 `outputs/` 链接为本机留存证据、不会随Git源码推送；公开安装资源请走测试Release。

**边界：不包含 Android 4.0 / API 14–15；兼容版本仅硬件解码，不保证旧设备能解码 HEVC/AV1。** 如果片源只有 HEVC，而设备没有对应解码器，降低清晰度也不能使该编码变成 H.264。

## 1. 进入选集页面提速

### 原因与接口对照

原 `detail()` 在拿到基础资料及分集 ID 后，仍同步等待相关推荐 plan 请求、同名剧搜索及其重试，UI 才显示集数。可选请求最多多次轮换服务器，网络异常时会拖累选集。

对照当前工作区的兄弟 TV 项目和 PC 后端：TV 项目已把基础详情与推荐拆开；PC 仍使用官网基础详情。未找到需要盲目切换的新端点，因此沿用已验证的 App 基础详情接口，官网作兜底。

### 修改

- [ContentRepository](kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt) 新增 `detailFast()`：仅加载剧名、简介、分集列表；官网响应已内嵌的推荐可顺便解析，但不额外请求。
- 新增 `detailRecommendations()`：在独立、可取消的后台任务中补全推荐和系列季，不重复读取基础详情。最多 2 次 plan 请求和 1 次官网搜索，每次调用最长 4 秒。
- [MainActivity](kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt) 先显示选集网格，再原地追加推荐/季选择条，不整页重建、不抢遥控焦点。
- 返回列表、开播、缓冲、暂停或进入后台时取消可选请求；播放期间须重新稳定 8 秒且有足够缓冲才补加载。
- 弹幕请求改为独立、单任务、可取消的线程，不再占满负责播放地址和选集的 IO 线程。
- 增加 `Episode page interactive`、`Playback ready`、`Playback first frame` 日志，供同设备、同片源的复测使用。
- 完整测试另发现并修复既存漫剧分类分页问题：第 2 页起正确传递 `page` 参数，避免重复首屏；测试保留第 2 页和最后页停止的断言。

基础接口或注册本身仍需要网络响应；本轮不宣称在任意网络下固定几百毫秒，也不把单元测试的模拟延迟当作实机耗时。

## 2. 预缓存可行性与实现

现有 [RemoteVideo](kotlin-tv/core/src/main/kotlin/com/hongguotv/core/RemoteVideo.kt) 支持 HTTPS Range 读取和 MP4 索引准备，播放器能直接消费预先保留的字节，**开头预缓存可行**。不是保存整集视频，也不是离线下载。

### 用户设置

入口：**设置 → 播放偏好 → 后续集预缓存**；播放中：**播放设置 → 后续集预缓存**。

- 可选关闭、1、2、3 集；默认 1 集。
- 选择自动保存、重启保留，并纳入手机备份与恢复。
- 可服务手动换集，不依赖“自动连播”开关。
- 内存等级不超过 128 MiB 的设备，实际最多保留 1 集；界面会说明限额。

### 缓存时机和预算

策略见 [PrefetchPolicy](kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PrefetchPolicy.kt) 和 [PreparedEpisodes](kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PreparedEpisodes.kt)。

1. 前台实际播放，处于 READY 状态，连续稳定至少 **8 秒**。
2. 当前已缓冲内容折算为实际播放时间至少 **10 秒**；靠近片尾时可用全部已缓冲片尾代替。按倍速折算，不以“媒体时间”误判。
3. 距片尾的实际播放时间不超过 **90 秒**。短剧可较早启动，长视频不会在开头就取出随后很快过期的签名地址。
4. 仅联网且系统判定为 **非计费网络** 时启动；串行准备后续集，优先最近的一集。
5. 每集最多保留 **1 MiB 开头数据**，预缓存索引最多 1 MiB，按索引对象开销估算单集总保留预算最多 **4 MiB**；超预算回退正常播放。普通播放仍沿用原索引上限。
6. 队列最多 3 个结果，低内存实际最多 1 个；结果有效期 **120 秒**。整次预缓存最多 15 秒，每个键最多 3 次尝试，尝试间隔至少 15 秒。
7. 换集仅领取匹配集 ID、清晰度、解码能力的缓存；跳集后保留仍有用的未来集。失效、失败或尚未完成的缓存不会阻断正常加载。
8. 当前集开始缓冲/暂停时立刻取消在途预缓存；后台、退出、网络切换、内存紧张时释放缓存。修改缓存数也清除旧结果。
9. 预缓存使用独立传输连接，并继承当前已注册设备会话，不为每集重复注册。被取消的旧任务不能污染同键的新任务。

另修复快速连续换集时旧媒体源和连接失去释放责任的问题：替换完成前保留明确的退休资源集合，失败/退出时统一释放。

## 3. Android 4.1–4.4 兼容构建

转换入口：[build_android41.py](tools/build_android41.py)，兼容模板在 [LegacyPlatform](tools/android41/LegacyPlatform.kt)、[LegacyApkInstaller](tools/android41/LegacyApkInstaller.kt) 等文件中。

- 在独立的生成目录构建，原现代依赖与源码不降级。
- minSdk 16、targetSdk 28；ExoPlayer 2.18.7、OkHttp 3.12.13、兼容 Okio、AndroidX Multidex / MediaSessionCompat。
- 处理 NetworkCallback、MediaCodecList、新 API 的销毁/附着状态、View ID、字体缩放、图片内存统计、绘制与控件着色等差异。
- 旧系统使用 Holo 主题、PNG 图标，避免运行时加载不支持的 Material/Vector 资源。
- 支持旧系统的更新安装 URI、媒体按键和多 dex 加载。未发布远程更新元数据时，旧设备不会自动收到这一新变体。
- TLS 1.2 显式启用；仍使用系统与内置可信根验证证书、默认域名验证。**没有信任全部证书，没有忽略域名，没有降级为 HTTP 来绕过证书错误。**
- 补入从 [Let's Encrypt 官方 PEM](https://letsencrypt.org/certs/isrgrootx1.pem) / [官方 DER](https://letsencrypt.org/certs/isrgrootx1.der) 双源核对的 [ISRG Root X1](kotlin-tv/app/src/main/assets/certs/isrg_root_x1.pem)，DER SHA-256 固定为 `96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`；未以临时服务器证书冒充可信根。
- 系统时钟错误、网络门户、服务器不提供兼容加密套件等仍可能导致联网失败，需要设备日志定位。
- 不包含现代 FFmpeg 视频软件解码库；按实际硬件能力优先选择编码，并明确提示无法解码的片源。

### 重复构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.1.0、Python 3。首次运行需要下载 Gradle 与依赖；本机只对构建进程使用 IPv4 参数，不改全局代理。

```powershell
# 仓库根目录
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
.\kotlin-tv\gradlew.bat -p kotlin-tv :core:test :app:assembleHwDebug :app:assembleSwDebug :app:lintHwDebug :app:lintSwDebug --console=plain

python tools/build_android41.py --check
.\build\android41\kotlin-tv\gradlew.bat -p build/android41/kotlin-tv :app:assembleHwDebug :app:lintHwDebug :app:testHwDebugUnitTest --console=plain
```

不要把 `-PminSdk=16` 直接套到现代 Media3/FFmpeg 工程上。兼容转换为严格匹配源码的生成器；若以后源码结构变化，它会失败要求复核，而不是静默产出半兼容包。

## 4. 验证记录

已完成下列验证；构建/单测、模拟器行为与实体设备验收分别标注，不混为一谈。

- 未修改 HEAD 的隔离基线：153 个 JVM 测试、16 个既存失败。已更新过时的 API/SSR 测试夹具、隔离匿名注册请求，并修复实际存在的漫剧分类分页参数遗漏；未通过删除实质断言来宣称通过。
- 现代版完整 JVM 单测：**183 个全部通过**。包含详情分离、取消、设备会话继承、预缓存队列、Range 字节复用与设置持久化等 30 个新增用例。
- 现代硬解、软解版：`assembleHwDebug` / `assembleSwDebug` / `lintHwDebug` / `lintSwDebug` **全部通过**；lint 没有错误，仍有弃用 API 等警告。已修正原 DecodeSupport 的 Media3 opt-in 错误。
- API36 模拟器：最终交付硬解 APK 覆盖安装、启动、真实首页内容、选集、遥控设置与返回选集均验证成功；保存 3 集后杀进程/重新安装启动，界面仍显示 3 集。
- 同一真实 70 集剧目的两次选集日志分别为 **883 ms、797 ms**。首次非缓存开播首帧为 **4116 ms**；日志确认后续三集已准备，缓存命中第 2/3 集换集首帧分别为 **345 ms、253 ms**，且 `preloaded=true / reused=true`。
- 上述时间是单个 API36 模拟器、同一剧目不同集数的行为验证，**不是同一集严格 A/B 对照，也不是所有电视的性能保证**。证据：[播放日志](outputs/verification/modern-playback.log)、[缓存设置截图](outputs/verification/modern-prefetch3.png)、[最终 APK 的持久化界面](outputs/verification/modern-final-persisted/ui-nodes.json)。
- Android 4.1 兼容版：**183 个 core 测试、5 个 TLS/证书测试通过**，构建和 lint 通过（无错误，保留旧平台风险等警告）。
- API16 模拟器：安装成功，多 dex 加载成功，Activity 实际恢复前台，UI 树含真实首页剧名。CNB、example、Let's Encrypt ISRG Root X1 验证站均 HTTPS 200、握手成功；self-signed 证书明确被信任链拒绝；wrong.host 明确出现 `SSLPeerUnverifiedException: Hostname ... not verified`，不是把连接超时当作拒绝证明。
- API16 画面验收仍有限制：旧镜像对应用和系统 Settings 的截图均为同一纯黑图，已做系统对照，不能据此声称视觉验收通过。实际旧电视/盒子的画面与解码需另行复测。模拟器仅有 AVC/VP8、没有 HEVC，未把 HEVC-only 片源不可播放掩盖为兼容成功。
- 兼容更新安装：低于 API24 的 APK 复制、摘要校验与同步写盘改为后台准备，避免慢 SD 卡阻塞 UI；只处理 APK，不放宽私有文件权限。缺少已挂载外部存储时安全失败。给任务专属 API16 模拟器挂载 64 MiB 测试 SD 后，系统能解析最终 APK 并显示“替换应用、保留原数据”确认页，已按返回取消、未自动安装。该测试证明 handoff/解析入口，不代替目标 ROM 的完整在线更新回归。证据：[API16 日志](outputs/verification/android41-final.log)、[安装器确认界面](outputs/verification/android41-installer.xml)。

## 交付文件

| 本地验证包 | 系统要求 | 解码 |
|---|---|---|
| [电视 / 车机版本](outputs/optimized-android/hongguotv-0.26.9-optimized-sw-tv-debug.apk) | Android 8.1+ / API27 | 平台解码 + FFmpeg 软件回退 |
| [纯平台解码版本](outputs/optimized-android/hongguotv-0.26.9-optimized-hw-android81-debug.apk) | Android 8.1+ / API27 | 不含额外 FFmpeg |
| [Android 4.1 适配版本](outputs/optimized-android/hongguotv-0.26.9-optimized-android41-debug.apk) | Android 4.1+ / API16 | 不含 FFmpeg，依赖设备解码器 |

[SHA-256 校验和](outputs/optimized-android/SHA256SUMS.txt) · [测试与产物清单](outputs/optimized-android/verification.json) · [兼容构建详细记录](tools/android41/README.md)

三包保留项目现有 0.26.9 / code52 版本标识，文件名增加 optimized 以区分本地改进包；没有擅自建立发布版本或远程更新条目。

## 5. 安装与验收提醒

本轮输出为本机构建的 debug 签名验证包。2026-10-30 该构建已随 v0.27.0-test.1 发布，并改用原发布证书重新签名，因此可覆盖 0.26.x 安装、支持应用内更新。

建议实体设备复测：同一 Wi-Fi / 同一剧、冷进入选集 10 次；缓存关闭/1/3 集分别测第 1 帧时间；连续快切、换清晰度、暂停超过 2 分钟、断网/联网、进入后台与低内存；Android 4.x 额外检查 TLS、遥控媒体键、H.264/HEVC 解码及应用内安装。
