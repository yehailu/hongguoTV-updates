# 2026-10-24 维护版（4 个包）

从本版起只维护 4 个安装包，其余历史版本停止更新。均为 **debug 签名**，包名统一
`com.hongguotv.nativeapp`，**互相覆盖安装**。

## 给谁装

| 设备 | 文件 | 系统 | 体积 |
|---|---|---|---|
| 电视 / 车机（含无 HEVC 解码器设备） | `hongguotv-0.24.2-sw-tv-debug.apk` | Android 8.1+ | 24.7 MB |
| Android 7 的电视 / 车机 | `hongguotv-0.24.2-sw-android7-debug.apk` | Android 7.0+ | 25.6 MB |
| 手机 / 平板（可横竖屏） | `hongguotv-0.24.2-hw-phone-android8-debug.apk` | Android 8.0+ | 9.6 MB |
| Android 5–6 老设备 | `hongguotv-0.22.3-android5-debug.apk` | Android 5.0+ | 10.8 MB |

## 本版修复

1. **搜索不可用**：站点搜索页改成了 React SPA，旧的 `_ROUTER_DATA` 抓取失效；改用站点 JSON 接口
   `GET /api/search/result/?query=<关键词>&page=N`，保留漫剧(205)/短剧(203) 过滤与分页。
2. **证书校验失败**：`java.security.cert.CertPathValidatorException: Trust anchor for certification path not found`。
   站方、播放 API、封面 CDN 均挂在 DigiCert Global Root **G2**，旧设备证书库没有该根。
   现已内置 G2 / G3 / Global Root CA，先走系统证书库、失败再用内置根；不做 trust-all、不跳过主机名校验。

## 解码说明

- 平台片源为 **100% HEVC**（hvc1 / Main / 8bit / level 6.2），1080P 到 360P 全是 HEVC，
  **降低清晰度不能解决解码问题**；
- `sw` 包内置 FFmpeg 软解（HEVC/H.264/VP9/AV1），硬解优先、缺失或失败时自动切软解；
- 软解 1080P 在低端车机上可能掉帧，**建议清晰度设 720P**；
- `hw` 包不含 FFmpeg（9.6MB），手机/硬解设备用；
- Android 5–6 只能用 `0.22.3-android5`（Media3 1.11 最低要求 API 24，该支线为 Media3 1.8.0），无软解。

## 构建命令

```bash
./gradlew :app:assembleSwDebug                                            # 1 电视/车机软解
./gradlew :app:assembleSwDebug -PminSdk=24                                # 2 Android 7 软解
./gradlew :app:assembleHwDebug -PminSdk=26 -PscreenOrientation=unspecified # 3 手机硬解
cd D:/Hermes/hg/legacy-android5/kotlin-tv && ./gradlew :app:assembleDebug -PminSdk=21  # 4 Android 5
```

校验和见 `SHA256SUMS.txt`。
