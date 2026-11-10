# 网易云适配器：协议来源与验证边界

本阶段实现 `NeteaseProvider`、`NeteaseHttp`、`NeteaseCrypto`，保留既有原生播放器项目。以下客户端端点来自公开互操作源码；**没有取得网易云正式开放 API 授权，网页或客户端端点可访问不等于正式授权**。

## 参考来源与许可证

- [Mineradio-paused](https://github.com/XxHuberrr/Mineradio-paused)，核对 revision `d43de565acabfdc1a9c9820a27e81a98ccbebcef`。`package.json` 声明 `GPL-3.0-only`，`server.js` 引用 `NeteaseCloudMusicApi ^4.32.0`。本项目参考其接口调用关系、歌单完整分页目标和试听识别字段，**没有复制其 GPL server 实现**。
- 实际协议参考是 Mineradio 锁定依赖 [NeteaseCloudMusicApi 4.32.0](https://registry.npmjs.org/NeteaseCloudMusicApi/4.32.0) 的 `util/crypto.js`、`util/request.js`、`util/option.js` 与相应 `module/*.js`，包内许可为 MIT，Copyright (c) 2013–2022 Binaryify。
- 参考包 SHA-256：`4a1a01856703b33292208e51bef8ca780477edd29e7542a29965a248dd95ca4d`。下载地址：`https://registry.npmjs.org/NeteaseCloudMusicApi/-/NeteaseCloudMusicApi-4.32.0.tgz`。原包与抽取参考放在工作区 `work/reference/mineradio`，不作为运行依赖或随 APK 打包。
- Java 请求封装与映射重新实现；协议请求加密的算法与常量保留上述 MIT 出处。许可正文在 `docs/licenses/NeteaseCloudMusicApi-MIT.txt`，亦打入 `app/src/main/assets/licenses/netease-protocol.txt`。
- 不迁移 Mineradio 中的外站替代音源、试听兜底、汽水音频解密等逻辑。

## 已实现接口

所有调用都由调用方放到后台线程。普通请求的 `weapi` 固定 HTTPS 主机为 `music.163.com`，`eapi` 为 `interface.music.163.com`；后续扫码兼容调整仅将两个 QR 端点隔离到 `interfacepc.music.163.com`。账户、曲库与播放资源的普通请求主机保持不变。请求路径不能指定其他主机，禁止跟随重定向。

| 功能 | 协议路径 | 处理 |
|---|---|---|
| 扫码 key / 轮询 | `/api/login/qrcode/unikey`、`/api/login/qrcode/client/login` | type 3；独立候选 cookie，803 且收到新候选 MUSIC_U、真实 account profile 校验通过后才替换持久会话 |
| 账户 | `/api/w/nuser/account/get` | weapi，必须存在有效 profile；无 profile 或 301/401 判失效 |
| 创建/收藏歌单 | `/api/user/playlist` | weapi，200 条分页，保留创建者/收藏分类，重复分页报错 |
| 喜欢歌曲 | `/api/song/like/get` | 完整 IDs，批量补齐歌曲详情 |
| 歌单全部曲目 | `/api/v6/playlist/detail` | 读取 trackIds；数量不足、详情缺失明确报错，不伪装完整列表 |
| 歌曲详情 | `/api/v3/song/detail` | weapi，每批 500；按原始 ID 顺序重排，保留所有请求位置 |
| 专辑搜索/详情 | `/api/cloudsearch/pc`、`/api/v1/album/{id}` | type 10 / weapi 专辑详情，公开总数与下一页偏移 |
| 音乐人 | `/api/artist/head/info/get`、`/api/v1/artist/songs`、`/api/artist/albums/{id}` | 资料、最多 50 首热门曲目、全部专辑分页；热门曲目不标为完整作品集 |
| 播放资源 | `/api/song/enhance/player/url/v1` | standard，由服务端按当前账户决定是否授权，不遍历音质/替代站绕过权益 |
| 歌词（后台已实现，界面待确认） | `/api/song/lyric` | eapi；`id`、`tv/lv/rv/kv=-1`、`_nmclfl=1`；读取原文 LRC 与译文；同一 Java 适配器已对 2 首公开歌曲实测成功，界面仍待确认 |

歌单/音乐人专辑分页设无进展检测及最多 1000 页的异常保护；到达保护边界会报错，不把截断结果当作成功。下载下来的元数据保存 `netease://song/<id>`，不会保存临时签名音频 URL。

## 会话、权限与完整歌曲

- `ProviderSession` 由主工程实现 Android Keystore 加密持久化。适配器不会向日志、Track JSON、分享卡或探测报告输出 cookie。
- Cookie 只用于各流程固定的 HTTPS API 主机；QR 候选凭据与现有账户隔离，封面与音频不带账户凭据。响应 cookie 检查所属域名，处理 `Max-Age=0`、折叠 `Set-Cookie` 和头部换行注入。
- 请求开始记录会话 generation；响应提交 cookie 时在 session monitor 内再次检查。登出后迟到的二维码响应不能重新登录。
- 网络故障保留现有会话；明确登录失效才清除。8821 返回 `LOGIN_CHALLENGE`，停止把二维码当成等待/成功状态；官方网页登录由用户亲自完成，适配器不执行验证码或验证绕过。
- `freeTrialInfo` 非空，或 `freeTrialPrivilege` 的 `resConsumable` / `userConsumable` / `listenable` 为真时，拒绝作为完整资源。全为 false 的权利说明对象本身不等于试听。
- 播放 URL 必须非空、状态允许、HTTPS（已知网易云媒体域返回 HTTP 时改用相同 URL 的 HTTPS）；不支持 NCM 受保护音频。
- 必须同时有目录完整时长与播放资源 `time`；缺失时长不假设完整。短于预期的资源作为试听拒绝；允许的编码时长误差为最多 `max(2 秒, 2%)`。过长、未知、已过期资源同样不投入播放器。
- 上述检查是客户端依据平台返回字段的保守判断；最终声音、拖动、完整播放及系统生命周期仍需 Android 实机验收。

## 早期桌面网络证据（2026-11-08；后续设备验收另记）

使用 `tools/ProviderProbe.java` 直接编译同一组 Java provider 类；没有替换成 Python HTTP 实现、代理 API 服务或 fixture。探测会话只在进程内存中，退出即清理。

1. **二维码**：实际创建成功，轮询先得到 `WAITING_SCAN`，随后得到 `WAITING_CONFIRM`。用户确认后，平台实际返回 **8821**。没有收到 803，没有存入 MUSIC_U，没有读取真实账户与私人歌单，**账户完整链路未通过验收**。
2. 关于 8821 的补充参考来自 [Glimpse 维护者一手记录](https://github.com/Dyse-Sofqi/Glimpse/blob/main/docs/netease-login.md)，不是网易云官方授权/状态文档。本次探测只保留了状态码，未保存原始响应和验证 URL；不能声称本次具体要求某一种验证码。
3. **公开专辑**：真实搜索返回首批 3 张、总数 595；随后专辑详情返回 30 首实际曲目，weapi 与 eapi 两种请求均收到可解析数据。
4. **真实完整资源的范围证据**：某公开曲目目录时长和播放返回 `time` 均为 `1055000 ms`。无账户头请求得到音频开头 2,097,152 bytes 和尾部 262,144 bytes，均 HTTP 206，Content-Range 总长度 16,880,997 bytes。
5. **实际解码**：ffmpeg 识别 MP3、44.1 kHz、双声道；文件头完整时长 `00:17:35.03`。头部前 30 秒解码退出码 0，末端片段 16.37 秒解码退出码 0。片段截取导致文件尺寸/时长提示及尾部帧对齐跳过 84 bytes，未伪装成完整文件下载或完整播放。

音频样本仅位于 `work/private/netease-probe/`，不进 APK、源码压缩包或用户输出目录。没有打印或保存临时音频 URL。以上证明公开目录与真实资源请求/范围/解码可用，**不等同于已验证真实账户全部歌单、会员完整权益、整首听完或 Android 真机发声**。

## 自动化验证

`NeteaseProviderTest` 使用明确标注的离线协议 fixture，验证二维码状态/晚到响应、8821、登录失效与网络异常区分、202 个歌单分页、1201 曲目按 500/500/201 批补齐且顺序不变、缺失曲目拒绝、专辑/音乐人/账户映射、试听和未知时长拒绝、cookie 域/删除/折叠/注入处理，以及请求加密基本结构。具体执行数量和结果以本轮统一 QA 报告为准；fixture 不冒充用户真实数据。

适配器与依赖集成后的中间检查中，7 项 `NeteaseProviderTest` 全部通过（当时完整套件 90 项，35.359 秒，全部通过）；主界面与官方网页登录继续接线后的最终构建需再次依据统一 QA 报告验收。

以上早期桌面探测结束时尚待：官方网页会话经真实账户校验、真实创建/收藏/喜欢歌单完整分页、会员/试听实际样本，以及 Android Media3 前后台、锁屏、地址过期重取、实际完整音频播放验收。后续设备验收结果以当前版本的统一验证文档为准，此处保留当时证据，不作为当前未完成清单。

## 歌词后台增量（2026-11-08，界面待设计确认）

新增 `Lyrics`、`LrcParser` 和 `MusicProvider.lyrics(songId)` 默认方法；网易云适配器实现该方法。旧 provider/测试 fixture 不需要新增实现。此增量没有把歌词显示或歌词请求接入 MainActivity、播放页或媒体服务，也没有逐字高亮。上方早期桌面账户探测记录是历史证据，不能替代后续设备验收结论；当前版本总体验收以统一验证文档为准。

- 协议依据是前述已校验 MIT 4.32.0 包的 `module/lyric.js`，不是新增代理服务或外站歌词库。请求沿用既有固定 HTTPS API 边界和会话失效处理。
- `Lyrics` 绑定 `provider + songId`，区分同步歌词、纯文本、纯音乐、暂无歌词。数据和行集合不可变；本阶段只保存在内存，不把歌词写入歌曲 JSON、日志、图片素材或交付样例。
- LRC 支持分钟/秒及可选小时、1–3 位小数、多时间标签、全局 offset、乱序排序和重复时间去重；同一时刻的不同文本合并保留。时间偏移在解析时应用一次：正值提前、负值延后。此语义核对了 [paroles 作者文档](https://github.com/Clarkkkk/paroles#api)，未复制其实现。
- 元数据不作为歌词，无效时间不会伪造成零时刻；保留有效空白时间行供未来界面处理间奏。增强 LRC 仅去掉字时间标记，仍只提供真实的行时间，不生成逐字进度。
- 译文只按应用各自 offset 后的相同时间点匹配；不按行号硬配。没有匹配时间点的译文仍保留在纯文本字段。纯文本不产生同步时间；未来界面可明确显示“未提供时间信息”。
- `nolyric` 与平台纯音乐标记映射为纯音乐，`uncollected` 或缺少内容映射为暂无歌词；网络/协议错误保持错误状态，不伪装成无歌词成功。单份输入限制 512 Ki 字符、20,000 个时间标签，超限报错而非截断成功。
- `Lyrics.lineIndexAt(positionMs)` 通过实际播放位置二分定位，首行开始前返回 -1；前后拖动无需维护假进度。UI 接入还需在切歌后核对 `matches(provider,songId)`，丢弃旧歌的晚到结果。

### 歌词验证结果与边界

`LyricsTest` 的 10 项测试使用原创短句，覆盖多时间标签/精度/排序/去重、正负 offset、无效时间、译文、纯文本、空白间奏、倒退定位、身份绑定、不可变集合、输入大小限制、端点参数、无歌词/纯音乐及错误分类。统一 QA 已报告 **124/124 全部通过，38.348 秒**，其中 LyricsTest 10 项、NeteaseProviderTest 10 项；最终编译/测试源码哈希无漂移。测试不冒充真实平台歌词。

真实探测工具 `tools/LyricsProbe.java` 使用同一 Java 适配器和临时内存会话，只输出 songId、类型、hasLyrics、行数、译文行数、首末时间；不输出或保存歌词原文。早期沙箱请求曾退出 1 并返回 `probe_failure=NETWORK`，一次升级权限重试被取消；这是历史失败，不作为当前结论。

后续主代理重新编译当前源码后，真实调用同一适配器成功，进程退出码 0：

| songId | 类型 / hasLyrics | 行数 | 译文行数 | 首行 / 末行时间（ms） |
|---|---|---:|---:|---|
| 186016 | SYNCED / true | 62 | 0 | 0 / 262430 |
| 347230 | SYNCED / true | 49 | 0 | 0 / 325284 |

摘要证据为 `verification-v0.4.0/lyrics-live-probe.txt`，无歌词正文、会话或账号信息。以上验证真实端点和实际返回的 LRC 解析，不代表所有歌曲均有歌词/译文，也不代表歌词 UI 已接入。界面设计确认后，还需验证切歌绑定、滚动、拖动同步和设备显示。

## 后续扫码兼容调整（待新 APK 真实扫码验收）

用户报告直接扫码仍返回 8821、官方网页登录可用后，核对了上述 4.32.0 源码及查阅时 [api-enhanced 的 QR 模块](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/main/module/login_qr_check.js)、[请求封装](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/main/util/request.js) 与 [配置](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/main/util/config.json)。该分支 [LICENSE](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/main/LICENSE) 仍为 Binaryify MIT；本工程按协议字段独立实现 Java 请求，不复制其 Node 请求或登录代码，既有 MIT 许可保留。

- 保留 type=3 和 PC codekey 二维码。只有 QR key/check 使用独立 PC EAPI 路径；客户端 header 同时编码到 HTTP Cookie，requestId 使用时间戳加四位十进制，保留本实例稳定 deviceId 与服务端 NMTID。PC UA 的版本与请求 appver 统一；不将普通账户/播放接口全量迁移。
- 扫码候选从空 cookie 开始，不发送旧网页登录 MUSIC_U。801/802 不提交；803 还必须取得候选登录令牌并用候选 cookie 通过原 account 接口的 profile 校验，之后才一次写入加密存储。没有先删除旧有效账户；验证失败、8821、取消及会话代际变化均不以候选结果覆盖旧会话。
- 8821 继续报告平台拒绝本次扫码授权，提供官方网页登录验证，不推断具体拒绝原因、不映射成成功，也不引入跳过验证码或风控的逻辑。
- `NeteaseQrCompatibilityTest` 覆盖请求序列化、header/Cookie 一致性、十进制请求号、候选隔离、旧令牌不能冒充803、profile先于提交、失败保留会话和取消后的迟到结果。对应离线定向回归与完整构建以本轮新 QA 报告为准；**尚未取得兼容调整后本用户直接扫码 803 的证据，不能宣布扫码问题已经解决。**
