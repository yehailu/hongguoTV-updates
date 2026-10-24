# 维护矩阵与发版流程

只维护以下 **4 个包**，其余历史版本（0.17.x–0.24.1、0.22.2 的随机包名变体等）**停止更新**，仅存档。

| # | 包（当前版本） | 变体 ID | 适用 | 解码 | minSdk | 方向 |
|---|---|---|---|---|---|---|
| 1 | `hongguotv-0.25.0-sw-tv-debug.apk` | `sw-tv` | 电视 / 车机（含无 HEVC 解码器设备） | 硬解优先 + FFmpeg 软解 | 27（8.1） | 横屏 |
| 2 | `hongguotv-0.25.0-sw-a7-debug.apk` | `sw-a7` | Android 7 的电视 / 车机 | 同上 | 24（7.0） | 横屏 |
| 3 | `hongguotv-0.25.0-hw-phone-debug.apk` | `hw-phone` | 手机 / 平板 | 仅硬解（无 FFmpeg） | 26（8.0） | 可横竖屏 |
| 4 | `hongguotv-0.22.4-android5-debug.apk` | `a5` | Android 5.0–6.0 老设备 | 仅硬解 | 21（5.0） | 横屏 |

四个包同包名 `com.hongguotv.nativeapp`，**互相覆盖安装**。

## 应用内更新

1. 客户端请求 `/-/releases/latest`（307 跳转）拿到最新 tag；
2. 下载该 Release 的 `update.json`，按自己的 `VARIANT_ID` 取条目，比较 `versionCode`；
3. 有新版本则流式下载 APK，边下载边校验大小与 SHA-256；
4. 校验通过后交给系统安装器（首次需允许「安装未知应用」）。

**限制**：普通应用无法静默安装，最后一步必须用户点确认；没有 FCM 通道，所谓"推送"是每日一次轮询。

设置里有「检查更新」与「自动检查更新」两个开关（自动检查每日最多一次，播放中不弹）。

## 一键发版

```powershell
# 主线 0.25.1 / code 33，安卓5 支线 0.22.5 / code 34
.\tools\release.ps1 -MainVersion 0.25.1 -MainCode 33 -A5Version 0.22.5 -A5Code 34

# 只发主线（安卓5 不动）
.\tools\release.ps1 -MainVersion 0.25.1 -MainCode 33 -SkipLegacy
```

脚本会：编译 4 个包 → 复制到 `releases\v<版本>\` → 生成 `update.json`（不含 BOM，含各变体 sha256/size）
→ 生成 `SHA256SUMS.txt` → 打印发布步骤。

之后在 CNB 新建 `v<版本>` 的 Release，把 `releases\v<版本>\` 里的 6 个文件全部作为附件上传即可。
发布后确认 `/-/releases/latest` 已指向新 tag，客户端就能收到更新。

> 注意：0.24.2 及更早的包没有更新器，需要手动安装一次 0.25.0 才会进入自动更新循环。

## 安卓 5 支线单独构建

主线播放库（Media3 1.11）最低要求 **API 24**，Android 5/6 必须用 `legacy/a5` 分支（Media3 1.8.0、无软解）：

```bash
cd D:/Hermes/hg/legacy-android5/kotlin-tv
JAVA_HOME=D:/Hermes/hg/jdk/jdk-17.0.20.1+1 ./gradlew :app:assembleDebug -PminSdk=21
```

该分支已同步主线：站点 JSON 搜索接口、内置根证书、应用内更新。

## 已知限制

- 平台片源为 100% HEVC（hvc1 / Main / 8bit / level 6.2），降清晰度不能解决解码问题；
- 软解 1080P 在低端车机上可能掉帧，建议清晰度设 720P；
- Android 5 支线无软解，遇到无 HEVC 硬解的设备仍无法播放；
- 所有包为 debug 签名。
