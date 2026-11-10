# MusicKit 原生播放适配

依据用户下载的 `Android-MusicKit-SDK-1.1.4`，核对了本地 README、`Android MediaPlayback Usage.md`、官方示例和 **AAR 实际类签名**。播放 AAR 是 `MediaPlayback-release-1.1.4.aar`，认证 AAR 是 `MusicKitAuth-release-1.1.5.aar`。代码调用官方原生控制器，不把 Apple URI、试听地址或 DRM 音频送给 Media3。

## 接口

`AppleMusicPlayback` 实现 `ApplePlaybackEngine`。由既有播放 Service 持有，控制方法及状态回调在主线程运行：

```java
ApplePlaybackEngine apple = new AppleMusicPlayback(context, sessionStore, listener);
apple.prepare(Collections.singletonList(track), 0, false);
// 等待 state.ready，按 state.canSeek 跳到恢复位置，再 resume()。
```

- `prepare(List<Track>, index, autoPlay)`；`play(list,index)` 是自动播放便捷入口。
- `resume/pause/next/previous/seek/stop` 返回命令是否被接受；真实播放状态只读取 SDK，不能把返回 true 显示成“已经播放”。
- `state()` 返回不可变快照；`refresh()` 从 SDK 更新进度。
- `tokensChanged()` 停止旧引擎并清除旧 SDK 会话；以 `AppleMusicSessionStore` 构造时自动观察 generation。
- `clearStoredSession(context)` 供已切到其他播放来源后的退出/账号切换使用，在 generation 推进后调用。它保留清理标记；活动会话先停止，并用全空 TokenProvider 清 SDK 授权值，但不会宣称 SDK 持久队列立即原子清空。此进程曾创建过 SDK 引擎时，下一次 Apple 播放明确要求彻底关闭并重新打开应用。新进程在创建任何 SDK 控制器之前清 SDK 缓存和队列，成功后才撤销标记；清理失败继续阻止初始化，不回滚已清除的本机授权。首次配置且从未播放的用户不受此限制。
- `close()` 移除会话观察者和 SDK listener，停止并释放控制器；迟到回调不再生效。
- 同包的 `Factory` 注入接口供协议/接线测试使用，生产实现仍为 `MediaPlayerControllerFactory.createLocalController(context, mainHandler, tokenProvider)`。

状态含真实 SDK metadata `currentTrack`、明确请求的 `queue`、`queueIndex`、`sdkQueueCount`、`positionMs/durationMs`、`playing/buffering/preparing/ready`、操作能力、错误类别和简洁提示。`ended` 仅在 `onItemEnded` 对应通知中为 true，并以单调 `endEventId` 去重。订阅、用户令牌、DRM、队列、播放会话和网络错误分别保留，不将异常当作自然播放结束。

## 歌曲身份与准备完成

- `applemusic://songs/{id}` 使用 `CatalogPlaybackQueueItemProvider.Builder.items(SONG, ids)`。
- `applemusic://library-songs/{id}` 使用真实的 `LibraryPlaybackQueueItemProvider`，保留 `i.…` 库内身份，不擅自换成目录 ID，也不拿预览替代。
- 一次 SDK 队列只接受同来源的歌曲，保留顺序和重复项。跨本地/QQ/网易云/Apple 的队列由 `PlayerService` 继续管理，每次把当前 Apple 曲目作为单项队列交给 SDK。
- 在新队列/current-item 回调到达、匹配所选内容、SDK 时长可用、非缓冲且状态为 PAUSED/PLAYING 前，`ready` 保持 false。初始 STOPPED 或“没有缓冲”不代表已经准备好。
- 恢复播放先 `prepare(..., false)`，等 ready，再 seek，最后 resume，避免先从零播放一下再跳转。
- SDK repeat/shuffle 设置关闭；Service 决定顺序、循环、随机、睡眠和跨来源切歌。

## 与本地播放器协作

Service 继续拥有 foreground 通知、系统 MediaSession、应用队列和生命周期；切换来源时先暂停/释放另一引擎。

**实际 AAR 的 `MediaPlayerController` 没有 `setVolume`，也没有关闭自动音频焦点的方法。** 本地 HTML 文档中出现了若干该 AAR 不含的新版方法，不能据此调用。字节码及真实接口确认 SDK 自行申请/放弃 AudioFocus 并处理焦点回调。Apple 模式下 Service 不应再申请第二个音频焦点；不能虚构音量 duck 接口或用系统全局音量代替。

## 构建要求

- SDK 最低 API 23；仅提供 `arm64-v8a`、`armeabi-v7a` 原生库，没有 x86 模拟器库。
- 懒初始化按官方示例加载 `c++_shared` 后 `appleMusicSDK`。未配置开发者令牌或未授权用户时不会初始化原生引擎。ABI/链接失败明确显示组件不可用。
- 打包 AAR 的资源和 JNI，合并两个 ReportingService、认证 Activity/回调 scheme/package queries，以及 INTERNET、WAKE_LOCK、ACCESS_NETWORK_STATE。仅复制 `classes.jar` 不够。
- 实际依赖包含 AndroidX core/activity/fragment/appcompat/collection、DataBinding runtime/base-adapters、Gson、Kotlin 1.9 系列、Kotlin coroutines。示例的 Picasso 只用于示例图片 UI，不是本适配层依赖。
- MediaPlayback 已内含 JavaCPP 类，不应额外重复打包另一份 javacpp。
- JNI 16 KB 页对齐、实际 target SDK、DRM/焦点及实体 ARM 设备播放需由最终构建和设备验证，Java 接口测试无法证明。

## 验证边界

`AppleMusicPlaybackTest` 使用 SDK 真正的 Catalog/Library builder 和接口；只替换需要原生 ARM/账号的控制器边界。覆盖缺凭据不初始化、非自动播放准备、seek 后 resume、来源身份、能力限制、错误、自然结束、账号切换和释放。

`PlayerServiceApplePlaybackTest` 使用真实 Service 与适配层，验证 Apple 不进入 Media3、跨来源路由、暂停准备与迟到回调、恢复进度、应用循环模式、退出仅移除该来源，以及订阅失败不回退到试听/其他引擎。本地音频路径使用实际 Media3 的测试运行环境。

2026-11-09 两类合计 **16 项定向验证**，含 SDK 焦点暂停后的单击恢复、通知动作、自动恢复意图，以及旧进程有待清缓存时阻止创建控制器的门槛。最终结果见 `work/apple-playback-qa/render-report.json`；报告记录了对应产品与测试源码哈希。测试运行于 Robolectric API 34，不是实体 Android 设备，也不证明 Apple 网络授权或 DRM 解码已经成功。

用户尚未提供已签发的开发者 JWT；没有将代码/离线测试标成真实账号授权、私人曲库同步或 DRM 完整歌曲播放成功。
