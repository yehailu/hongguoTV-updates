# 维护矩阵（2026-10-24 起）

只维护以下 **4 个包**，其余历史版本（0.17.x / 0.18.x / 0.19.x / 0.20.x / 0.21.x / 0.23.1 / 0.24.0 / 0.24.1 /
0.22.2 的 r6r77u3、android7、android7-r7h34m 变体）**停止更新**，仅存档。

| # | 包名 | 适用 | 解码 | minSdk | 方向 | 构建命令 |
|---|---|---|---|---|---|---|
| 1 | `hongguotv-0.24.2-sw-tv-debug.apk` | 电视 / 车机（含无 HEVC 解码器设备） | 硬解优先 + FFmpeg 软解 | 27（Android 8.1） | 横屏 | `./gradlew :app:assembleSwDebug` |
| 2 | `hongguotv-0.24.2-sw-android7-debug.apk` | Android 7 的电视 / 车机 | 同上 | 24（Android 7.0） | 横屏 | `./gradlew :app:assembleSwDebug -PminSdk=24` |
| 3 | `hongguotv-0.24.2-hw-phone-android8-debug.apk` | 手机 / 平板 | 仅硬解（无 FFmpeg，9.6MB） | 26（Android 8.0） | 可横竖屏 | `./gradlew :app:assembleHwDebug -PminSdk=26 -PscreenOrientation=unspecified` |
| 4 | `hongguotv-0.22.3-android5-debug.apk` | Android 5.0–6.0 老设备 | 仅硬解 | 21（Android 5.0） | 横屏 | 见下方「安卓 5 支线」 |

## 为什么安卓 5 单独一条线

主线的播放库（Media3 1.11）最低要求 **API 24**，无法在 API 21 上运行，所以 Android 5/6 必须用
`legacy/a5` 分支（Media3 1.8.0、无软解扩展）：

```bash
cd D:/Hermes/hg/legacy-android5/kotlin-tv
JAVA_HOME=D:/Hermes/hg/jdk/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug -PminSdk=21
```

该分支已同步主线两项修复：

- 搜索改走站点新 JSON 接口 `/api/search/result/?query=…&page=N`；
- 内置 DigiCert G2/G3/Global Root CA 根证书（旧设备系统证书库缺 G2）。

## 版本号规则

- 主线：0.24.x（versionCode 30 起）
- 安卓 5 支线：0.22.x（versionCode 31 起，独立编号避免覆盖冲突）

## 已知限制

- 平台片源为 100% HEVC（hvc1 / Main / 8bit / level 6.2），降清晰度不能解决解码问题；
- 软解 1080P 在低端车机上可能掉帧，建议清晰度设 720P；
- Android 5 支线无软解，遇到无 HEVC 硬解的设备仍无法播放；
- 所有包为 debug 签名，包名统一 `com.hongguotv.nativeapp`，**互相覆盖安装**。
