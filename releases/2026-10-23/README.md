# 2026-10-23 版本包

本目录收录 2026-10-22 ~ 10-23 产出的全部安装包（3 个版本号 / 9 个包）。
均为 **debug 签名**，可侧载；除随机包名版本外，包名统一为 `com.hongguotv.nativeapp`，**不能共存**。

## 给谁装

| 设备 | 文件 | 系统要求 | 体积 |
|---|---|---|---|
| 电视（能正常播放） | `hongguotv-0.24.0-sw-tv-debug.apk` | Android 8.1+ | 24.7 MB |
| 车机（提示无法解码） | `hongguotv-0.24.0-sw-tv-debug.apk` | Android 8.1+ | 24.7 MB |
| Android 7 设备 | `hongguotv-0.24.0-softdecode-android7-debug.apk` | Android 7.0+ | 25.6 MB |
| 手机（可横竖屏） | `hongguotv-0.24.0-hw-phone-android8-debug.apk` | Android 8.0+ | 9.6 MB |
| 保守/仅诊断 | `hongguotv-0.23.1-debug.apk` | Android 8.1+ | 9.2 MB |
| 同机共存对比 | `hongguotv-0.22.2-r6r77u3-debug.apk`<br>`hongguotv-0.22.2-android7-r7h34m-debug.apk` | 8.0+ / 7.0+ | 9.2 / 10.0 MB |
| Android 5–6 老设备 | `hongguotv-0.22.2-android5-debug.apk` | Android 5.0+ | 10.3 MB |

## 全部文件

| 文件 | 版本 | 包名 | minSdk | 软解 | 体积 |
|---|---|---|---|---|---|
| `hongguotv-0.22.2-r6r77u3-debug.apk` | 0.22.2 | `…nativeapp.r6r77u3` | 26 | 否 | 9.2 MB |
| `hongguotv-0.22.2-android7-debug.apk` | 0.22.2 | `…nativeapp` | 24 | 否 | 10.0 MB |
| `hongguotv-0.22.2-android7-r7h34m-debug.apk` | 0.22.2 | `…nativeapp.r7h34m` | 24 | 否 | 10.0 MB |
| `hongguotv-0.22.2-android5-debug.apk` | 0.22.2 | `…nativeapp` | 21 | 否 | 10.3 MB |
| `hongguotv-0.23.1-debug.apk` | 0.23.1 | `…nativeapp` | 27 | 否 | 9.2 MB |
| `hongguotv-0.24.0-softdecode-debug.apk` | 0.24.0 | `…nativeapp` | 27 | 是 | 24.7 MB |
| `hongguotv-0.24.0-softdecode-android7-debug.apk` | 0.24.0 | `…nativeapp` | 24 | 是 | 25.6 MB |
| `hongguotv-0.24.0-hw-phone-android8-debug.apk` | 0.24.0 | `…nativeapp` | 26 | 否 | 9.6 MB |
| `hongguotv-0.24.0-sw-tv-debug.apk` | 0.24.0 | `…nativeapp` | 27 | 是 | 24.7 MB |

## 版本要点

- **0.22.2**：功能基线（界面重做、去自更新、无缝切集、字号七档、头部随滚动收起、搜索合并、热门发现剧场随机 12 部）。本目录 4 个变体用于兼容与共存。
- **0.23.1**：新增「设置 → 播放偏好 → 解码信息」诊断，可看本机 HEVC/H.264/AV1/VP9 解码器及等级。
- **0.24.0**：接入 FFmpeg 软解（HEVC/H.264/VP9/AV1），Media3 1.11.1；硬解优先、失败自动软解重试；`hw` 为纯硬解小包，`sw` 含软解。

## 已知限制

- 该平台片源实测为 **100% HEVC**（9 部 27 集，1080P–360P 全是 hevc，hvc1/Main/8bit/level 6.2），因此“降低清晰度”不能解决解码问题。
- 软解 1080P 在车机上可能掉帧，建议清晰度设为 **720P**。
- 软解版最低 **Android 7.0**；Android 5–6 只能用 0.22.2-android5（无软解）。

校验和见 `SHA256SUMS.txt`。
