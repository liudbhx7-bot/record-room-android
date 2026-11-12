# 官方音乐 App 播放联动

本层使用 Android 公开的 `MediaSessionManager` / `MediaController` 控制用户明确选择的活动媒体会话。它不登录音乐账号、不获得曲库 API、不解析歌曲地址、不接管音频解码，也不读取或保存通知正文。会员、可播放范围、曲库、队列和音频输出继续由对应官方 App 管理。

## 来源登记

| ID | 名称 | 唯一允许包名 | 核对来源 |
|---|---|---|---|
| `netease_music` | 网易云音乐 | `com.netease.cloudmusic` | [应用包可见性声明](../app/src/main/AndroidManifest.xml) |
| `qq_music` | QQ音乐 | `com.tencent.qqmusic` | [腾讯应用宝官方分发页](https://sj.qq.com/appdetail/com.tencent.qqmusic) |
| `qishui_music` | 汽水音乐 | `com.luna.music` | [腾讯应用宝官方分发页](https://sj.qq.com/appdetail/com.luna.music)、[小米应用商店开发者与包名](https://r.app.xiaomi.com/details?id=com.luna.music) |
| `apple_music` | Apple Music | `com.apple.android.music` | [Apple 发布的 Google Play 页面](https://play.google.com/store/apps/details?id=com.apple.android.music) |

精确匹配包名，不接受前缀、域名、任意 URL、电视版或其他变体。包名登记只表示限定目标 App，并不证明该版本在某台设备上必然提供所有媒体能力。

## 系统依据

- [`MediaSessionManager.getActiveSessions`](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))：普通 App 以获用户启用的通知监听服务组件身份访问活动媒体会话。没有申请系统级 `MEDIA_CONTENT_CONTROL` 权限。
- [`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService)：Manifest 绑定权限、连接/断开回调及 API 31 的通知类别过滤；实现只使用连接状态，未调用通知列表、通知内容或通知操作接口。
- [`MediaController`](https://developer.android.com/reference/android/media/session/MediaController)：媒体元数据、播放状态、指定会话的控制及成对注册/注销回调。由于注销后仍可能有已投递事件，本层同时检查会话身份与绑定版本。
- [`PlaybackState`](https://developer.android.com/reference/android/media/session/PlaybackState)：使用提供方声明的动作位、媒体位置、速度和 elapsed realtime 更新时间。进度仅在播放/快进/倒退时推算，并限制在有效时间范围；没有时间戳时不推算。

## 生命周期与接口

在主线程创建 `new ExternalMusicBridge(context, state -> ...)`。Activity 前台 `start()`，离开 `stop()`，销毁 `close()`。`stop()` 保留明确选择的来源 ID，解除会话/系统/权限监听并清掉当前曲目信息；`close()` 进一步释放 UI 回调。桥接层不持久化来源，MainActivity 负责其自己的偏好记录。

`selectProvider(id)` 仅接受表中的 ID；`null`/空字符串清除外部选择。无效 ID 返回 false，保留旧选择。停止、权限撤销、另一个音乐 App 开始播放，都不会把所选来源替换为其他 App。同一 App 存在多个会话时，保留当前 token；它消失后才采用该 App 的另一活动会话。

`State` 提供 `selectedProviderId`、`selectedProvider`、`status`、`statusMessage`、`accessGranted`、`connected`、`providers`，以及真实会话的 `title`、`artist`、`album`、`mediaId`、`artwork`、`artworkUri`、`positionMs`、`durationMs`、`actions`、`playbackState`、`playing`、`canPlay`、`canPause`、`canPrevious`、`canNext`、`canSeek`。`ProviderState` 包含 `provider`、`installed`、`active`、`status`、`statusMessage`；`Provider` 包含 `id`、`name`、`packageName`。

缺失文本为空字符串，缺失封面为 null，未知时长或进度为 -1。封面 Bitmap 只读，不得回收。`artworkUri` 只透传对方提供的元数据地址，本层没有发起网络或文件读取；不能将它当歌曲地址使用。未选中的音乐 App 不读取其曲目信息。

`play()` / `pause()` / `previous()` / `next()` / `seekTo(ms)` / `togglePlayPause()` 都返回是否派发。每次操作重新检查权限和最新动作位；不支持则返回 false。返回 true 不表示对方已经播放成功，UI 仍以实际状态回调为准。只有声明 seek 动作并提供有效时长/位置，才启用拖针定位；只声明切换动作的会话使用指定 controller 的媒体键事件，不发系统全局按键。

`notificationAccessSettingsIntent()` 仅返回系统设置入口，不自动授权。`getLaunchIntent(id)` 仅返回指定官方 App 的正常启动 Intent，不自动打开或播放。

## Manifest 配置

在 manifest 级声明三项 `<queries><package android:name="..." /></queries>`，确保 Android 11+ 安装状态检查准确。application 级配置：

```xml
<service android:name=".ExternalMusicNotificationListener"
    android:label="唱片机播放联动"
    android:exported="false"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
    <meta-data android:name="android.service.notification.default_filter_types" android:value="" />
    <meta-data android:name="android.service.notification.disabled_filter_types" android:value="1,2,4,8" />
</service>
```

API 31+ 默认不接收任何通知类别，并禁用这些类别；早期系统忽略此配置，但服务仍未实现通知采集。系统的授权界面可能描述比本功能更广的“通知使用权”，来源页应明确实际用途。服务由系统绑定，MainActivity 不调用 `startService`。

## 验证范围

`ExternalMusicBridgeTest` 使用可控的会话/系统适配器验证限定来源、明确选择、会话销毁、过期回调、权限撤销、动作位、进度边界与生命周期。它不模拟真实厂商 App 成功播放。真机仍需逐个安装官方 App、用户授权后验证歌曲/封面/控制可用性，并确认系统或厂商后台限制；缺失能力应保持禁用状态。
