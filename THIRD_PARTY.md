# 第三方来源与许可

本仓库保留原项目的版权与许可说明。根目录 [COPYING](COPYING) 为 GNU GPL v3 全文；以下第三方组件仍按各自的许可或提供条款使用，项目的 GPL 声明不会重新许可 Apple SDK 或其他第三方资源。

## 本地音频读取

Media3 ExoPlayer 管理播放时钟、队列与 Android 音频输出。新增的本地文件读取器使用 FFmpeg 8.1.3 的 libavformat、libavcodec 和 libavutil。它只打开用户选定的文件描述符，包含 APE、WavPack、AIFF 和无加密 ALAC 的相关解封装与解码部分。该构建关闭网络协议、GPL 和 nonfree 功能。FFmpeg 版权所有归 FFmpeg developers，采用 LGPL-2.1-or-later。[FFmpeg 上游](https://github.com/FFmpeg/FFmpeg) · [Media3 上游](https://github.com/androidx/media) · [随包许可](app/src/main/assets/licenses/FFmpeg-LGPL-2.1.txt)。

发行版中的 ARMv7 / ARM64 动态库均可用公开代码和同版源码重新生成或替换。完整 FFmpeg 源码随发行附件提供。[源码归档](https://github.com/liudbhx7-bot/record-room-android/releases/download/v0.8.0/ffmpeg-8.1.3.tar.xz) · [本地读取器](app/src/main/cpp/local_formats.c) · [构建步骤](docs/BUILDING.md)。

## Mineradio 协议适配

QQ 音乐与保留的汽水协议 Java 代码参考、移植自：

- [XxHuberrr/Mineradio-paused](https://github.com/XxHuberrr/Mineradio-paused)，提交 `d43de565acabfdc1a9c9820a27e81a98ccbebcef`；Copyright 2026 XxHuberrr，GPL-3.0-only。
- 汽水 Passport 授权桥上游 [Wx2yZx/Mineradio-Qishui-QR-Login](https://github.com/Wx2yZx/Mineradio-Qishui-QR-Login)，提交 `aaadaab7d011714f94fbe45b382ba8dcc7cf17b9`，GPL-3.0-only。

本项目的改动包括 Android 原生界面、隔离的账号存储、分页和响应校验、试听与权益边界、Media3 播放、真实音乐人检索及专辑交互。原归属通知与完整许可分别保留在 [mineradio-notice.txt](app/src/main/assets/licenses/mineradio-notice.txt) 和 [mineradio-GPL-3.0.txt](app/src/main/assets/licenses/mineradio-GPL-3.0.txt)。本应用不是上述音乐平台或上游项目的官方产品。

## 网易云协议参考

网易云协议参考 Binaryify 的 NeteaseCloudMusicApi。Copyright (c) 2013–2022 Binaryify，MIT License。完整版权和许可保留在 [netease-protocol.txt](app/src/main/assets/licenses/netease-protocol.txt) 及 [MIT 文本副本](docs/licenses/NeteaseCloudMusicApi-MIT.txt)。

## Android 与 Java 依赖

项目使用 AndroidX Media3、AndroidX / Material、ZXing、Gson、Kotlin 等库。实际版本与文件摘要见 [dependencies.json](tools/dependencies/lib/dependencies.json)，各 artifact 的声明许可和 POM 摘要见 [license-artifacts.json](tools/dependencies/license-artifacts.json)。

完整许可和归属文本保留在 [dependencies.txt](app/src/main/assets/licenses/dependencies.txt)，上游来源与文本摘要见 [license-sources.json](tools/dependencies/license-sources.json)。公开仓库不附这些库的 AAR/JAR，构建时由固定依赖描述解析到本机。

## Apple MusicKit

本项目对接 Apple 官方 Android-MusicKit-SDK-1.1.4，其中包含 MediaPlayback 1.1.4 和 MusicKitAuth 1.1.5。SDK 从 [Apple 官方入口](https://developer.apple.com/musickit/) 取得，受下载时适用的 Apple 条款约束，不作为本项目的开源代码重新许可。

公开仓库不分发 Apple 原始 AAR；构建者需自行取得 SDK，并通过 [apple-sdk.json](tools/dependencies/apple-sdk.json) 校验版本与摘要。SDK 来源、嵌入组件通知及构建时移除敏感调试日志的说明保留在 [apple-musickit.txt](app/src/main/assets/licenses/apple-musickit.txt)。该通知不替代或扩张 Apple 的提供条款。

## 已停用的汽水直连网页资源

当前汽水功能仅使用官方 App 的 Android MediaSession 联动；旧直连授权 Activity 未注册，在线播放入口也拒绝旧汽水直连曲目。公开源码省略 `app/src/main/assets/qishui-auth/`，其中包括旧 host、ByteDance 网页安全运行时，以及 React / ReactDOM 副本。

这些资源曾由上面的 Mineradio 提交引入；厂商安全运行时保留原权属，React / ReactDOM 保留其原通知，不被项目 GPL 声明重新许可。省略文件及原始 SHA-256 记录在 [excluded-legacy-assets.json](docs/excluded-legacy-assets.json)，保留的 Java 协议和隔离测试用于来源追溯与边界回归，不代表启用了账号直连。

现有 0.7.7 Release APK 按原始已验证文件发布；公开源码自签构建因签名不同且不再打包这些停用资源，不承诺与该 APK 字节相同。构建步骤见 [BUILDING.md](docs/BUILDING.md)。
