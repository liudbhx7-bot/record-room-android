# 在 iPhone 或 Mac 上使用唱片机

## iPhone 主屏幕播放器

1. 用 Safari 打开[唱片机](https://liudbhx7-bot.github.io/record-room-android/)。
2. 点 Safari 的分享按钮，选择「添加到主屏幕」。如果显示「作为网页 App 打开」，保持开启，再点「添加」。
3. 回到主屏幕，点唱片机图标。首次联网时会缓存唱臂、封面和内置音乐。在「音乐来源」导入自己的音频文件，或先播放内置专辑。

网页 App 使用浏览器的音频解码能力，文件和播放位置保存在当前设备的站点存储中。清理网站数据会清掉已导入的副本。安装步骤也可参考 [Apple 的 iPhone 使用手册](https://support.apple.com/guide/iphone/turn-a-website-into-an-app-iph42ab2f3a7/ios)。

## Mac 原生版

[下载 macOS 磁盘映像](https://github.com/liudbhx7-bot/record-room-android/releases/download/v0.8.2/Record-Room-0.8.2-macOS.dmg)，双击挂载，把 RecordRoom 拖入「应用程序」。打开后先选一张内置唱片，或从「⋯ → 资料库」导入音乐。支持 macOS 14 及以上，内含 Apple 芯片和 Intel 两种架构。

此预览版使用本地签名，尚未经 Developer ID 公证。macOS 可能提示无法验证开发者；如果安装受到组织策略限制，可用下文的 Xcode 工程自行构建，或先使用网页播放器。

## 用 Xcode 安装 iPhone 原生版

原生工程共用 SwiftUI 界面与 AVFoundation 音频引擎。需要 iOS 17 及以上和支持目标设备系统版本的 Xcode。工程在 Xcode 27.2 验证过。

1. 下载 [Apple 原生工程与所需资源](https://github.com/liudbhx7-bot/record-room-android/releases/download/v0.8.2/Record-Room-0.8.2-Apple-Project.zip)。解压后保留目录结构，打开 `apple/RecordRoom.xcodeproj`。
2. 在 Xcode 设置中登录自己的 Apple Account。用数据线连接 iPhone 并信任这台 Mac，在设备上按 Xcode 提示打开开发者模式。
3. 在 **RecordRoom → Signing & Capabilities** 打开自动签名，选择自己的 Team。如果标识符已被其他团队使用，将 Bundle Identifier 改为自己的唯一标识符。
4. 选择连接的 iPhone，按 **⌘R** 构建和安装。日后覆盖安装时继续使用原来的 Team 和标识符。

真机安装需要与该设备匹配的签名和描述文件。当前提供源码安装路径和可直接添加到主屏幕的网页版。App Store 与 TestFlight 暂无此版本。[Apple 的设备运行说明](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices)

## 当前功能

| 功能 | iPhone / Mac 原生 | iPhone 主屏幕网页 |
| --- | --- | --- |
| 播放与保存 | 导入设备可解码的音频，读取标签与封面，保存独立副本、资料库和进度 | 本机文件存储于浏览器，支持内置专辑、离线播放与进度恢复 |
| 唱机与来源 | 原石墨色唱针、封面、唱片底灯、来源 A 封面目录 | 原石墨色唱针、封面目录与播放控制 |
| 搜索与音乐人 | 本地搜索、专辑曲目、三页音乐人视图、竖放专辑和抽盘 | 本地搜索与专辑浏览 |
| 歌词 | 导入 LRC / TXT，可读取文件中可用的歌词 | 当前以播放与资料库为主 |
| 在线服务 | Apple 目录搜索可进入官方曲目页；iPhone 真机可选择系统音乐资料库中可读取的音频。网易云、QQ 和汽水提供官方入口 | 使用本机文件与内置音频 |

Android 版已有的第三方账号直连、跨应用媒体会话控制、扩展解码器和桌面小组件未随此 Apple 预览版移植。受保护的订阅歌曲由官方播放器处理。iPhone Duo 专属布局正在独立审稿。
