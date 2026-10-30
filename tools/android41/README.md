# Android 4.1+ 独立兼容构建（API 16）

此目录提供构建模板；生成器为 [build_android41.py](<../build_android41.py>)。它将当前 Kotlin 工程复制到独立、被 Git 忽略的 staging，再进行转换。**不会修改现代版的依赖，也不会直接改动现代版 MainActivity 或 Library。** 主线修改完成后需重新运行生成器。

## 构建

需 Python 3、JDK 17、Android SDK 36、Build Tools 36.1.0、Gradle 8.14.3。Python 生成阶段仅依赖标准库。

```powershell
python tools/build_android41.py --check
$env:JAVA_HOME='D:\DevTools\jdk-17'
$env:ANDROID_HOME='D:\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME='D:\Android\.gradle'
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
# Wrapper 分发下载正常时：
.\build\android41\kotlin-tv\gradlew.bat -p build\android41\kotlin-tv :app:assembleHwDebug :app:lintHwDebug :app:testHwDebugUnitTest -PvariantId=a41
```

若 wrapper 已有缓存，可以直接调用缓存分发的 gradle.bat 并传入同样的 `-p` 和任务参数。不要启动第二个 wrapper 下载器争抢同一 ZIP 锁。

生成 APK 位于 `build/android41/kotlin-tv/app/build/outputs/apk/hw/debug/app-hw-debug.apk`。默认同主线包名及版本号；发布前通过 `HONGGUOTV_VERSION_CODE` / `HONGGUOTV_VERSION_NAME` 显式指定。后续覆盖安装要求同包名、相同签名和更高版本号。Debug 产物不是正式发布签名。

## 与现代版分离的更改

- 固定 `minSdk=16`、`targetSdk=28`，variant `a41`，只保留 `hw`。
- Media3 → ExoPlayer **2.18.7**（API16 最低支持版本线）；删除 nextlib / FFmpeg 软件解码依赖。
- OkHttp **3.12.13**（最终支持 Android 4.1 的版本线）和其 Okio 1.x 依赖；通过独立源码适配保留主线属性/扩展调用习惯。
- AndroidX Core 1.8.0、RecyclerView 1.2.1、Media 1.6.0、ZXing 3.3.3、Multidex 2.0.1；BouncyCastle 改为同版本 `jdk15to18:1.79`。
- Java 17 字节码继续由 Android 工具链 desugar；标准 desugar JDK 库 2.1.5。`MultiDexApplication` 保证旧 Dalvik 能加载多个 DEX。
- API16 使用 Holo 主题及 PNG 图标；API21+ 保留 Material 和 vector 资源。
- staging MainActivity：旧系统静态 Codec 枚举、销毁标记、View ID / 附着状态、字体 Context 守卫；替换 API21 NetworkCallback 字段为生命周期绑定的广播监听，不仅靠 `runCatching` 隐藏错误；Immersive flag 守卫。
- 内存/图片计数 API19 守卫，数字键盘旧系统 state drawable；MediaSessionCompat 保留媒体键/状态接口。

## 网络安全

主线 [TlsSupport.kt](<../../kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/TlsSupport.kt>) 对 API16–19 的原生 SSL socket 显式开启其支持的 TLS1.2+。不启用 TLS1.0/1.1，不使用 trust-all、不替换 hostname verifier、不关闭 SNI；依旧必须由系统或随包公共根证书验证。若设备 SSL provider 根本不支持 TLS1.2，则安全失败，而不是降级。

[TlsSupportTest.kt](<TlsSupportTest.kt>) 测试组合 TrustManager 的失败关闭、可信备选管理器路径及 TLS1.2+ 协议集合。这些 JVM 单元测试**不是**旧系统真实服务端证书、SNI或TLS套件握手测试。

可在 Debug staging 中通过 `adb -s emulator-5580 shell am start -n com.hongguotv.nativeapp/.MainActivity --ez android41Smoke true` 开启一次后台诊断，[LegacyDiagnostics.kt](<LegacyDiagnostics.kt>) 会将实际 decoder MIME、CNB/example HTTPS握手、self-signed及wrong-host拒绝结果记录到 `HGTVCompat` logcat。正常启动不会启用这些额外请求。连接超时不能计作证书拒绝证明。

兼容版仅将 `ExpiredTargetSdkVersion`（Google Play上架目标SDK要求）降为 Warning，因为此 APK 为侧载包；**不关闭 NewApi lint，不添加 baseline 隐藏错误**。旧系统 SecureRandom/PRNG 的已知平台风险仍由 lint 警告，不能将此兼容 APK 描述为已修复旧 Android 全部密码安全缺陷。

主线更新器仅从 HTTPS 下载 metadata/APK，并禁 HTTPS→HTTP 重定向。安装前已有大小/SHA256校验。API24+ 使用 FileProvider + 读取授权；API16–18 为兼容旧 PackageInstaller，仅将已校验 APK 复制到公共 `Downloads/HongguoTV-verified-updates`；API19–23 仍复制到 app-specific external cache，再交给系统 file URI 安装器。复制后再核对完整大小/SHA256，不放宽 StrictMode、不创建 world-readable 私有数据。API16–18 声明并由旧系统安装时授予 external storage 权限；API19+ 写 app-specific external cache 不需此权限。外部存储未挂载或不可用时安全返回失败。

另补充官方 ISRG Root X1 公共根以支持 Let's Encrypt 站点。[官方 PEM](<https://letsencrypt.org/certs/isrgrootx1.pem>) 经 X509解析 SHA256与[官方 DER](<https://letsencrypt.org/certs/isrgrootx1.der>) 的原始 SHA256均为 `96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`；第五条安全单测固定验证其 fingerprint、CA 属性、有效期和自签名。不是额外接受任意自签服务器证书。

API16–23 的复制、两次摘要与 `fd.sync()` 均在单次后台线程完成，AtomicBoolean 防重复，并仅捕获 applicationContext；完成后 Handler 主线程打开 Installer 或显示失败 Toast，避免慢SD卡阻塞 UI。此分支 `install()==true` 仅表示准备请求已接收，**不表示安装完成**；外部存储未挂载或基本文件验证失败仍立即返回 false。API24+ 保持直接 FileProvider handoff。所有系统仍需用户确认。

**旧系统外部存储本身不提供私有文件级防篡改保证**；其他获存储权限应用可能在交给安装器后改动文件。覆盖更新仍由系统 APK 签名/包名校验保护。不得据此宣称可静默安装或在所有定制 ROM 上安装成功。

## 真实支持边界

1. 仅 **Android 4.1–4.4 / API16–19 及以上**；不含 Android 4.0 / API14–15。
2. **构建可安装不等于片源可播放。** 此包只有平台硬解；4.1–4.4 设备通常没有 HEVC decoder，HEVC-only 片源将不可播放。已有 codec 过滤/错误提示可以解释此限制，但不会把 HEVC 变成 AVC。没有捆绑未经验证的 API16 FFmpeg binary。
3. ExoPlayer2 和 OkHttp3.12 是停止正常维护的兼容版本线；旧 TLS provider/密码套件/系统安全漏洞不能靠应用修复。无法支持 TLS1.2 的 ROM 不采用不安全降级。
4. variant `a41` 必须单独发布到更新 manifest 的 `variants.a41`。若不存在此条目，旧版将不会收到主线 a7/a5 的不兼容包；不要把此 APK登记为 a5 或现代 hw-phone。
5. 本轮新增了官方 API16/default/x86 rev7 镜像与独立 `HGTV_API16_Compat` AVD，未改变已有 AVD；API16 安装、Activity 启动/保持前台、多DEX加载、真实首页数据的UI树及 TLS 已实测。**未测试 API17–19 实机/ROM、硬件 HEVC、媒体按键或完整更新安装。** 现代 emulator 37.2.12 的旧镜像截屏出现全黑；连 stock Settings 对照也相同黑图，因此不把 Activity/UI树通过称为视觉渲染验证通过。
6. 首轮 installer 的独立 file-URI 烟测遇到 Parse error；环境检查显示初始 AVD 未配置外部 SD（`/mnt/sdcard` 为 `d--------- system system`，无 `/storage/sdcard0`、无 vfat/fuse mount）。后续仅在本任务 build 目录创建 **64MiB mksdcard image** 并为该临时 AVD 挂载，vold 明确进入 **4 (Mounted)**；公共 Downloads file-URI 能显示 **Replace app? / previous user data will be saved** 确认提示，已按返回取消，未自动确认安装。**完整更新下载→应用内异步复制校验→用户确认安装流程仍需目标ROM回归**，不能以 adb install 或独立URI提示代替。
7. 旧 Android5 维护文档引用的外部 `D:/Hermes/hg/legacy-android5` 目录在当前机器不存在；该主线也没有可用的 legacy/a5 分支。此生成器是本 checkout 内可重复的替代方案，而不是仅改 minSdk。

## 本轮验证结果

- 最终兼容依赖下 `:core:test`：**183 tests / 0 failures / 0 errors**；TLS 安全单测：**5 tests / 0 failures / 0 errors**。
- `:app:assembleHwDebug`、`:app:lintHwDebug` 均成功；lint **0 errors / 55 warnings**（保留PRNG、自定义可信根包装、旧依赖、侧载 target 等真实警告）。
- APK：同主线包名、version `0.26.9 (52)`、minSdk **16**、target **28**、无 FFmpeg native payload；`apksigner verify` 显示 **v1=true / v2=true**，debug签名。
- API16/Dalvik实际 `adb install -r` 成功；多DEX加载成功；Activity 保持前台，无 FATAL；UIAutomator 确认导航和多部真实首页剧名。首轮真实测试曾捕获缺 MediaButtonReceiver 的启动崩溃，已补显式组件和 dispatch 并复测通过。
- [最终ISRG网络日志](<../../build/android41/api16-exact-final.log>) 记录：CNB、example.com、官方 valid-isrgrootx1.letsencrypt.org **HTTP200 / handshake=true**；self-signed.badssl.com 明确 **SSLHandshakeException / Trust anchor拒绝**；wrong.host.badssl.com 明确 **SSLPeerUnverifiedException / Hostname not verified**。不是把连接超时当成证书拒绝。
- 实际 decoder MIME 包含 AVC、VP8；**不包含 HEVC**。旧设备播放HEVC-only片源依旧不支持，不宣称能解决这种硬件缺失。
- [系统Settings对照图](<../../build/android41/api16-settings-control.png>) 与 app 图均全黑且相同SHA，而 stock Settings / app UI树及前台窗口正常。现代37.2.12 emulator旧镜像图形链路有限制，**视觉渲染未验证通过**。`-gpu off` 实际被该emulator拒绝后回退auto，并不能称已使用了guest软件GPU证明结果。
- 有任务专属64MiB SD后，[Installer确认UI树](<../../build/android41/api16-final-installer-ui.xml>) 记录 **Replace app? / All your previous user data will be saved**；已取消，未点击 OK 或安装。该证据仅验证公共Download URI解析/用户确认入口，不等同完整在线更新成功。
- 最终独立交付 [Android4.1硬解Debug APK](<../../build/android41/hongguotv-0.26.9-android41-hw-debug.apk>)，大小 **14905838 bytes**，SHA256 **085532164cffed485c9fdcc26d8871a89b6997b89ac2016e204b7446829c2e60**；未发布远程Release或生产更新元数据。

## 转换维护

生成器对关键 MainActivity 片段做存在性校验；如果主线重构网络字段/注册/销毁方法，生成失败是刻意的，需审查并更新转换规则。不要跳过校验强行降低 minSdk。只允许覆盖带 `.android41-generated` 标记的 output，拒绝把 output 指向现代源码目录。验证以 staging 的 lint 报告、实际编译结果、APK minSdk和真实设备结果为准。
