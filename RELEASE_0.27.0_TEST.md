# v0.27.0-test.1 测试版

> **测试版，不设为 latest，不触发现有稳定客户端自动更新。** 稳定入口继续保持 v0.26.9；Android 5 稳定包仍为 0.22.15 / code53。本轮测试构建为 0.27.0-test.1 / code54。

## 安装前必须阅读

本机没有原发布私钥，测试版使用新的 Debug 签名。原 CNB 版本与测试版不能直接互相覆盖安装。

- 原发布证书 SHA-256：`916a5d274a41d7e536bceb3f14a1195cb6979f9ddbf771fd7fe7ddd3d091c6a0`
- 测试版证书 SHA-256：`a79eaa62168e8171efc6f7b9760514f2cb8da4a3f68d62ec10506aec197a7552`

**先通过应用的手机备份功能导出收藏与进度并确认备份可用，再卸载原版、手动安装测试版并恢复记录。不要为了绕过签名错误直接卸载而丢失数据。** 已安装前轮本机同签名优化验证包的设备，可在签名匹配时覆盖安装本测试版。恢复到原稳定版同样需注意签名和数据备份。

测试版不是生产更新，也不包含旧 Android 的系统安全补丁。仅在愿意承担测试风险的设备上安装。

## 本次改动

- 选集基础资料和分集列表先返回，相关推荐/同名季在独立后台任务补齐，不阻塞选集页。
- 选集网格与季选择/推荐区分别更新，保留遥控焦点；旧推荐请求、弹幕请求均可取消。
- 后续集预缓存可关闭或选择 1、2、3 集，默认 1 集。低内存设备实际最多 1 集。
- 当前播放连续稳定 8 秒、缓冲充足、距片尾不超过 90 秒且为非计费网络时，串行准备后续集的开头；换集直接领取匹配缓存。
- 每集最多保留 1 MiB 开头数据，结果 120 秒失效，索引和估算内存有预算；失败、过期或超预算回退普通播放。
- 修复快速连续换集的旧媒体资源释放责任及漫剧分类第 2 页漏传页码的问题。
- 独立 Android 4.1+ 构建：ExoPlayer2.18.7、旧平台 API/主题/多DEX/媒体按键与更新安装适配，保留现代版依赖。
- 旧系统显式启用 TLS1.2，加入经官方指纹验证的 ISRG Root X1；不信任全部证书、不跳过域名验证。

## 测试包选择

| 变体 | 系统 | 解码/方向 |
|---|---|---|
| `sw-tv` | Android 8.1+ / API27 | 平台优先，FFmpeg软件回退，横屏 |
| `sw-a7` | Android 7.0+ / API24 | 平台优先，FFmpeg软件回退，横屏 |
| `hw-phone` | Android 8.0+ / API26 | 平台解码，可转屏 |
| `a41` | Android 4.1+ / API16 | 平台解码，横屏，无额外FFmpeg |

**不含 Android 4.0。** 旧设备没有 HEVC 解码器时仍无法播放 HEVC-only 片源，降低清晰度不能把编码变成 H.264。`a41` 不是无条件的全片源解码兼容。

[测试版发布入口](https://cnb.cool/lzyr9010/hongguoTV-updates/-/releases/tag/v0.27.0-test.1) · [稳定版入口](https://cnb.cool/lzyr9010/hongguoTV-updates/-/releases/latest)

## 验证范围

前轮性能实现已通过现代/兼容 core183测试及兼容TLS5测试。API36真实70集剧目选集日志约797/883ms，缓存命中换集首帧253/345ms；是单场景、不同集数测量，非严格A/B对照。相关说明见 [性能与兼容记录](PERFORMANCE_ANDROID41.md)。

API16已验证安装、启动、首页和HTTPS，以及不可信证书/错误域名拒绝。旧镜像对系统Settings与应用的截图同样纯黑，因此没有把UI树成功当成视觉验收通过；实体电视的画面/解码和完整在线更新仍需复测。

发布前对上述四个测试包重新执行构建、lint、APK真实版本/minSdk与证书/哈希检查，记录随Release附件提供。附件中的 `update.json` 仅描述测试包，不能被挂到稳定latest入口。

## 重复构建与发布约束

[构建器](tools/build_test_release.py) 使用已有 Python/JDK17/Gradle/SDK36.1.0，只构建，不发布，不改全局配置：

```powershell
python tools/build_test_release.py --version 0.27.0-test.1 --code 54
```

[CNB MCP检查工具](tools/cnb_mcp_preflight.py) 从 Windows 用户环境读取 CNB_API_TOKEN，仅在子进程内映射给现有MCP0.6.7，不输出或持久化令牌。MCP40工具不含源码提交/Release附件上传，因此经用户授权配合Git与 [CNB上传器](tools/cnb_release_upload.py)。

测试发布必须带 `--prerelease --no-latest --target-commit <源码提交SHA>`。全部附件验证成功前不得提升发布状态，绝不能覆盖原稳定Release或擅自设置latest。
