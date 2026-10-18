# 红果 TV · 原生客户端与自动更新

[下载最新版 APK](https://cnb.cool/lzyr9010/hongguoTV-updates/-/releases) · Android 8.0+ · Kotlin / Media3 · 单 APK，无需部署服务

非官方、自用的电视端客户端。本仓库为源码与发布镜像：`main` 分支保存可发布的原生源码，Release 页面提供 APK 下载。

## 功能

- 短剧 / 漫剧浏览、搜索、收藏、观看记录、续播、选集
- 全局清晰度与倍速设置
- 分季数据支持：选集按季归类
- 字号缩放，适配不同观看距离
- 前台浏览时每 6 小时检查新版本，下载校验后提示安装（播放时暂停请求）

## 0.22.2 更新要点

- **UI 重设计**：首页、详情页、选集面板、收藏页与弹窗统一为电视端深色视觉规范，弹窗面板独立样式
- **字号缩放**：新增 `FontScale`，可在设置中调整界面字号
- **分季支持**：新增 `Seasons` 数据层与单元测试，选集按季归类
- **主流程重构**：`MainActivity` 交互逻辑扩展约 570 行，首页与选集面板重做
- **更新模块精简**：移除 app 层 `AppUpdater`，更新检查统一由 `core` 模块承担
- 版本号 versionName `0.22.2` / versionCode `25`，最低系统 Android 8.0（API 26）

## 目录结构

```
kotlin-tv/            Android 原生工程（Kotlin + Media3）
  app/                应用模块：UI、播放、更新
  core/               数据层：内容仓库、快照、收藏索引、分季
  gradle/             构建脚本与版本目录
server/               辅助服务（非必需，单 APK 可不部署）
scripts/              构建与发布脚本
.github/workflows/    CI：测试、lint、签名构建与发布
```

## 构建

需要 JDK 17 与 Android SDK 36：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
```

产物位于 `kotlin-tv/app/build/outputs/apk/debug/app-debug.apk`。

正式构建使用 `scripts/build-kotlin.sh`，签名配置见 [kotlin-tv/README.md](kotlin-tv/README.md)。签名密钥不包含在仓库中。

## 安装说明

- 包名 `com.hongguotv.nativeapp`，可与旧版 `com.hongguotv` 并存
- 覆盖安装需签名一致，否则先卸载再装，本机收藏与历史数据不保留
- Android 8 需允许「安装未知来源应用」，更新为提示式安装，非静默安装
- 不含 JNI 库，ARM32 / ARM64 / x86 / x86_64 均可运行，解码能力依赖设备

## 许可

GPL-3.0，详见 [LICENSE](LICENSE)。
