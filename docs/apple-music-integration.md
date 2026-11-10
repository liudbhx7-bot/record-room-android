# Apple Music 原生接入说明

更新：2026-11-10。用户尚未生成开发者 JWT；代码已包含配置入口、官方授权接线、真实资料库 API、MusicKit 播放适配及 0.7.7 独立音乐人搜索和目录导航；**没有真实账户登录、私人曲库同步或完整歌曲播放成功的验收结论**。

## 当前实现

| 项目 | 当前行为 |
| --- | --- |
| 配置入口 | 音乐来源 → Apple Music → 配置并授权。支持从本机文档导入已签名开发者 JWT，或填写 HTTPS 令牌服务地址；未配置时明确显示未连接。 |
| 官方账户授权 | `AppleMusicLoginActivity` 使用真实 `AuthenticationFactory` / `AuthIntentBuilder`。Apple Music 官方 App 负责 Apple ID 登录、订阅检查与 MUT 生成；应用不收集 Apple ID 密码。 |
| 登录成功条件 | SDK 返回成功后，再用候选 MUT 读取 `/v1/me/storefront`。地区一致、会话未改变且授权未取消时才加密保存。仅打开官方页或收到未核验的回调不显示已连接。 |
| 资料库 | 读取真实用户歌单、歌单曲目、全部资料库歌曲及资料库专辑；保留分页、歌曲顺序、重复项、library ID 与 catalog ID 的区别。缺失数量使用未知状态，不能显示成 0 首。 |
| 目录 | 按已授权账户地区搜索歌曲/专辑，读取目录专辑及曲目。封面使用 Apple 提供的 HTTPS 图片。 |
| 播放 | `MusicProvider.resolve()` 明确返回 SDK_REQUIRED；由独立官方 MusicKit 引擎消费 Apple URI，不将试听或 DRM 地址交给 Media3。详见 [播放适配说明](apple-playback-adapter.md)。 |
| 音乐人目录 | 0.7.7 已实现独立歌手搜索、真实 artist ID 与头像，以及音乐人主页和完整专辑目录导航。开发者令牌尚未配置，真实授权联网与账号曲库尚未验收；实现完成不代表已经联通。 |
| 不支持的功能 | Apple 喜欢列表尚未实现；喜欢入口隐藏，资料库歌曲不会冒称为“我喜欢”。含音乐视频的曲目集合明确提示，未静默遗漏视频。 |
| 账户资料 | Apple Music API 未提供本应用所需的公开用户 ID/会员等级；只显示已核验的地区，使用本机随机会话标识隔离数据。会员等级为未知，播放权由 SDK 决定。 |

## 接入配置

`AppleMusicSessionStore` 同时实现 REST `AppleMusicApi.Tokens` 与官方 SDK `TokenProvider`。SDK 的三个 getter 只读取本机状态，不联网；需要获取/更新开发者 JWT 的操作放在工作线程。

可选择以下一种方式：

1. **本机导入**：纯文本 `.jwt`，或仅含 `developerToken` 字段的 JSON。仅导入签发好的 ES256 JWT，不能导入 `.p8` 私钥。应用先检查格式、Team ID / Key ID、签名段长度、签发/过期时间；这不是密码学签名验证，真正有效性仍由 Apple 服务确认。
2. **签发服务**：本项目明确约定 `GET https://你的服务/路径` 返回 `{"developerToken":"已签名JWT"}`。这是本应用的服务协议，并非 Apple 提供的签发 endpoint。服务端保管 MusicKit 私钥。请求不携带 MUT，不接受重定向或 URL 内的用户名、口令、查询凭据。现阶段没有预置服务地址或 token。

保存不同的服务地址会清除旧开发者令牌和用户会话，下次授权从新地址获取。相同签发身份续期 JWT 可保留用户授权；切换 Team ID/Key ID 会清除旧授权。即将过期或已过期的 JWT 不交给 SDK。后端尚未部署时可使用本机文件导入。

可选资源 `providers/apple-music.json` 只允许非秘密 `mediaId`、`storefront`、`tokenServiceUrl`，参考 [空配置示例](apple-music-config.example.json)。这里的 `mediaId` 仅记录开发者配置，实际授权使用已签名 JWT；用户地区以 `/v1/me/storefront` 为准。白名单拒绝在配置资源里打包 token 或私钥。

## 存储、取消与退出

- 令牌与用户地区放在 Android Keystore 绑定的 AES-256-GCM 密文中；无明文回退。导入的用户文件仍由用户自行管理，应用不复制私钥。
- 多个 Store 实例使用同一进程锁和 generation。更换账户、清除凭据及迟到 HTTP 响应提交在同一锁内核验；授权取消与最终写入共享 Attempt gate。已返回、已取消或已销毁的页面不能迟到提交凭据，也不能再次弹出官方登录。
- 旋转恢复官方授权等待状态时禁用新授权操作；`RESULT_CANCELED` 即使带数据也不交给 SDK 解析。
- 退出仅清除本应用的 Apple Music 用户会话，可保留开发者配置；“清除本机授权与令牌”同时移除开发者 JWT。它们不退出 Apple Music 官方 App 的 Apple ID。
- 退出、切换签发身份、切换服务和替换用户同时调用 `AppleMusicPlayback.clearStoredSession()`：清除 SDK token 缓存及持久队列，即使此前已切回本地播放器也执行。没有使用过 SDK 的机器不因此加载 JNI；清理失败保留待处理标记，下次 SDK 初始化前必须完成。
- 官方 Auth 1.1.5 原始包的回调包含将 MUT 写入 `Log.d` 的代码；构建链已仅针对 Auth JAR 移除调试日志，保留其 26 个类及公共接口。真实 SDK 回调使用固定假 token 的日志回归已通过，应用自身也不打印凭据。变换记录在 `build-manual/apple-auth-logging.json`，源 AAR 保持原样。

`AppleMusicHttp` 只向 `https://api.music.apple.com` 发送 API 凭据，不跟随重定向。目录请求只含 developer token；用户请求额外含 Music-User-Token。图片和音频缓存不接收这些 header。分页拒绝跨域、路径穿越、重复页面、错误资源类型和无进展循环，并在账户变化后丢弃迟到结果。

## 已使用的官方 SDK

实际 SDK 位于本地 `Android-MusicKit-SDK-1.1.4`，包含 `MusicKitAuth-release-1.1.5.aar` 与 `MediaPlayback-release-1.1.4.aar`。实现依据同包 `Android MusicKitAuth Usage.md`、`Android MediaPlayback Usage.md`、官方示例及 AAR 实际类签名；并未用旧网页缓存推断接口。

构建必须合并认证 Activity/回调 scheme、Apple Music package query、AndroidX/Material/DataBinding 依赖、库资源与 ARM JNI。不能只加入 classes.jar。当前 SDK 没有 x86 JNI；真实 DRM、订阅、后台焦点、库内上传歌曲及设备兼容性仍需要 ARM 真机和已授权账户验证。构建与 ABI 详情见依赖记录及播放适配说明。

官方来源：

- [MusicKit for Android](https://developer.apple.com/musickit/)：Android 原生授权与播放 SDK 的官方入口。
- [生成开发者令牌](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens)、[Media ID 与私钥配置](https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key)：ES256 JWT 的签发要求。
- [MusicKit 用户授权](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit)：用户授权 token 的用途。
- [用户 Storefront](https://developer.apple.com/documentation/applemusicapi/get-a-user%27s-storefront)、[资料库歌曲](https://developer.apple.com/documentation/applemusicapi/get-all-library-songs)、[资料库歌单](https://developer.apple.com/documentation/applemusicapi/get-all-library-playlists)、[资料库专辑](https://developer.apple.com/documentation/applemusicapi/get-a-library-album)：本批所用真实 API。

## 验证与下一步

三组离线测试共 25 项通过（`AppleMusicApiTest`、`AppleMusicIntegrationTest`、`AppleMusicLoginLifecycleTest`，Robolectric/API 34，报告 `tools/native-qa/outputs-apple-sdk/render-report.json`）。覆盖开发者 JWT 格式与过期、加密存储、多实例退出、取消和迟到响应、错误地区、无权限、不伪造已连接状态、服务地址变更、资料库/目录身份与专辑路由、未知数量、完整分页以及 SDK_REQUIRED。真实官方授权页已通过合并资源环境下的初始化与界面渲染，未点击登录、安装或任何付费入口。配置页也以原生 View 渲染，并验证可取消和旋转恢复。

用户生成开发者 JWT 后，在配置页导入或配置签发服务，再在 ARM Android 设备完成 Apple 官方授权。验收应分别确认账户授权、实际歌单与歌曲读取、目录歌曲和 library-only 歌曲播放、取消/退出、恢复进度及后台媒体控制；不能将离线 fixture、构建成功或官方登录页面截图视为上述成功。
