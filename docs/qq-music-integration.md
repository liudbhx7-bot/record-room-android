# QQ 音乐账号接入

此实现是用户在 QQ 音乐官方网页授权后使用其账号会话的客户端协议适配，**不是 QQ 音乐官方公共 SDK，也没有取得超出该账号的播放权**。它参考 [Mineradio-paused](https://github.com/XxHuberrr/Mineradio-paused/tree/d43de565acabfdc1a9c9820a27e81a98ccbebcef) 的 QQ 登录、歌单、搜索和 vkey 请求。源仓库为 GPL-3.0-only；参考版权及修改说明保留在 `QQProvider.java`，完整许可证随资产保存。官方服务仍可变更协议、限制请求或撤销授权。

## 集成边界

- `new QQProvider(ProviderSession)`，应用使用独立 `new QQSessionStore(context)`；也提供 `new QQProvider(context)`。
- Provider id 为 `qq`。可选 MediaSession 联动 id `qq_music` 是另一条能力，不相互冒充。
- `officialLoginUrl()` 返回 `https://y.qq.com/n/ryqq/profile`。登录界面仅提供官方网页登录，不创建自有 QQ 密码表单。QQ/微信登录流程由官方页面执行。
- `acceptOfficialWebCookies(String)` 在后台线程验证当前账号的“我的喜欢”权限及个人资料后才持久化，返回 `MusicProvider.Account`。只有 `p_skey` 或 `skey` 不算完成音乐授权；需要正常登录取得的 `qm_keyst`、`qqmusic_key`、`music_key` 或 `wxskey`。
- 可取消登录使用 `acceptOfficialWebCookies(String, QQProvider.LoginAttempt)`；页面退出时立即调用 `attempt.cancel()`。取消与最终提交共用同一个锁，取消之后返回的验证结果不能写入凭据。单参数便捷方法仍保留给调用方已确认的流程。
- `Account.vipType == -1` 表示会员信息未知。界面不要将它显示为“普通会员”“已取得会员权限”。实际播放请求是否获准由服务端资源结果决定。
- 网络方法全部在后台线程运行；不存在模拟成功状态。失败通过不含凭据或签名播放链接的 `ProviderException` 返回。

## 已实现请求

| 能力 | 官方服务请求 | 行为 |
| --- | --- | --- |
| 当前账号 | `c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg`，并先验证本人喜欢列表 | 自身主页使用官方 `userid=0` 语义；QQ/微信身份区分；确实不一致仍拒绝 |
| 自建歌单 | `fcg_user_created_diss` | 每页 200；完整分页，有重复页/截断保护 |
| 收藏歌单 | `fcg_get_profile_order_asset.fcg` | 每页 200；与自建/喜欢独立建模 |
| 我的喜欢 | `music.srfDissInfo.DissInfo/CgiGetDiss`，dirid 201 | 每页 100；只读当前授权账号 |
| 歌单歌曲 | `fcg_ucc_getcdinfo_byids_cp.fcg` | 核对歌单身份、完整分页；不静默保存半份列表 |
| 全曲库歌曲/专辑搜索 | `music.search.SearchCgiService/DoSearchForQQMusicMobile`，type 0/2 | `searchTracks` / `searchAlbums`，limit 1–30，offset 按 limit 对齐；有授权时带该账号会话，无授权时匿名请求；负数 `meta.is_filter` 明确显示受限 |
| 完整专辑 | `music.musichallAlbum.AlbumInfoServer/GetAlbumDetail` + `music.musichallAlbum.AlbumSongList/GetAlbumSongList` | 先取得正式 albumMid，再完整分页读取曲目；验证身份、页位置、稳定总数和重复页，不返回收藏子集 |
| 歌手全部专辑 | `music.musichallAlbum.AlbumListServer/GetAlbumList` | `artistAlbums(artistId, offset, limit)`，limit 1–100；以服务端 total 为准逐页读取，主页初始返回 20 张 |
| 歌手简介与推荐歌曲 | `music.musichallSinger.SingerInfoInter/GetSingerDetail` + `musichall.song_list_server/GetSingerSongList` | 真实简介/头像，以及服务端 order=1 的首 20 首；无简介就为空，不编造个人推荐或完整排名 |
| 标准 MP3 播放 | 歌曲详情 + `vkey.GetVkeyServer/CgiGetVkey` | 仅请求该录音的 M500 文件；不自动找替代录音，不伪造票据，不绕过会员/购买/地区限制 |

不支持的能力明确报错：自建二维码登录、官方写入收藏/歌单、无损格式/DRM 格式、会员级别探测。搜索范围是 QQ 服务端曲库，不限于本地收藏；可用结果仍受账号、地区和官方服务策略影响。

## 播放确认

Mineradio 参考链路只探测 HTTP/音频头后即标记 `trial: false`（参考 `server.js` 3661–3695）。本实现没有继承这个结论：

1. 用实时歌曲详情核对歌曲 id 和整曲时长。
2. 只接受当前账号 vkey 响应中与指定 `file.media_mid`、M500 文件名相符的资源；检查显式试听字段、资源结果及歌曲身份。
3. 只访问 HTTPS QQ Music 音频域名，最多两个服务端返回的 CDN 地址。不转发账号 Cookie，不跟随重定向。
4. 读取最多 256 KiB 的 MP3 头/帧，使用 Xing/Info/VBRI 时长或验证过的恒定码率与完整 Content-Length 推导时长。音频明显短于整曲返回 `PREVIEW_ONLY`；未知时长、混合码率无法确认或超长资源返回不可用。
5. `PlayerService` 仍须保留实际解码后时长检查。只有响应头和探测通过不等于真实账号播放已验收。

时长允许误差为整曲的 1%，最少 1 秒、最多 4 秒，用于 MP3 帧取整/编码填充。临时签名链接只进入短期 `PlaybackResource`，不写入歌曲资料库或持久队列。

## 会话隔离

`QQSessionStore` 使用 Android Keystore AES-256-GCM。QQ 专用偏好名、密钥 alias 和 AAD 均与网易云/汽水不同；没有明文回退。QQ/微信账号选择采用当前微信登录字段优先规则（`wxopenid`、`login_type=2` 或 `tmeLoginType=2`，优先 `wxuin`），不用旧 QQ uin 覆盖当前微信账号。网页登录只取一个官方域的完整 Cookie 快照，不拼接另一子域的旧身份/密钥。自身资料通过 `userid=0` 取得；隐藏数值 `0` 和不可解析的公开主页地址不作为数字账号比较；真实正整数归属或双方均提供的 `encrypt_uin` 不一致仍拒绝。登录候选凭据验证失败不覆盖已有授权。

所有已登录请求、音频探测和候选登录在返回前检查 session generation。退出/切换账号后的旧响应不能复活旧登录；只有明确服务端授权失效才清除同代会话，网络失败不删除授权。接口返回的新 Set-Cookie 暂不自动续期，过期时重新走官方网页授权。

## 专辑身份与旧资料

歌曲的专辑身份依次取专辑 mid、旧结构 albummid、最后才是数字 albumID。封面的 `pmid` 可能包含 `_1` / `_5` 等图片版本，不能直接作专辑ID；它只用于封面URL。完整专辑请求接受 mid 或数字ID，并以返回的正式 mid 统一每首歌曲的专辑归属。为兼容历史误存值，仅对 `14位字母数字 + _数字` 格式还原 mid，仍需服务端返回身份匹配；不会随意删除其他后缀。

## 官方网页协议依据

2026-11-09 公开读取 QQ 音乐当前页面加载的原始脚本（未执行登录脚本、未提供任何账号凭据）：

- [官方主页](https://y.qq.com/n/ryqq/profile)及[common bundle](https://y.qq.com/ryqq/js/common.chunk.80d50705ce8f47a01eb5.js)：自身 profile 使用 `userid=0`、`loginUin` 为当前音乐账号；公开主页链接使用 `creator.encrypt_uin`。
- [官方 singer bundle](https://y.qq.com/ryqq/js/singer.chunk.1cd3b577c281da6cb3ed.js)：歌手详情、简介、推荐歌曲及专辑分页的上述模块/方法与字段。
- [官方 albumDetail bundle](https://y.qq.com/ryqq/js/albumDetail.chunk.bbd493c654e3fd809ca4.js)：专辑详情和 AlbumSongList 分页；封面 pmid 与正式 albumMid 是不同字段。
- [官方 search bundle](https://y.qq.com/ryqq/js/search.chunk.49e865cec27709fe1f82.js)：歌曲 type=0、专辑 type=2；移动端签名/请求结构继续参考上述已标注 GPL 版权的 Mineradio 实现。

这是当前客户端协议观察，不是官方开放平台稳定性承诺。公开样本存于 `work/qq-research/`。

## 验证记录与限制

- 2026-11-09：无账号 Cookie 的官方 `musics.fcg` 歌曲搜索返回 HTTP 200、顶层及请求 code 0；查询“陈奕迅”，3 首真实歌曲，第一首为“葡萄成熟时”，mid `0029Zemv0kR1ur`，时长 277 秒。结果结构为 `req.data.body.item_song` 和 `req.data.meta`。原始公开数据及摘要位于 `work/qq-public-probe/`。
- 本次先在旧代码建立微信隐藏身份的真实 Provider 路径回归：14 项测试中该项失败，调用链为 `acceptOfficialWebCookies → verifyAccount → likedPage → verifyOwner`，报告 `tools/native-qa/outputs-qq-login-red/render-report.json`。这是离线协议样本复现，不等于读取了用户失败会话。
- `QQProviderTest` 23/23 通过，新增覆盖隐藏身份正常授权、真实数值/公开主页地址不匹配拒绝、子域会话不混合、授权搜索、搜索受限、203 首完整专辑分页、重复页/更改总数/错误身份拒绝、旧封面版本ID、歌手分页和真实字段映射；原取消、撤销、音频权益与加密测试保留。报告为 `tools/native-qa/outputs-qq-update/render-report.json`，无真实用户凭据。
- 当前公开匿名搜索再次探测返回 `is_filter=-9`（移动端）或 `-2`（网页端），即使 HTTP 200 / code 0 也不能作为搜索成功。应用将其明确报告为受限，不展示成没有结果。
- 当前生产 Java `QQProvider` + `QQHttp` 的无凭据公开联网探测已通过：陈奕迅真实简介、20 首推荐歌曲、100+41 两页共 141 张专辑且没有重复；专辑《What’s Going On...?》正式 mid `004Z85XP1c25b7`，实际返回完整 10 首且每首归属相同 mid。独立探测位于 `work/qq-focused-qa/tests/com/recordroom/vinyl/QQPublicCatalogProbeTest.java`，报告为 `work/qq-public-java-probe/render-report.json`；不作为要求网络的常规离线单元测试。此数据仅验证公开目录，不验证私人曲库或播放权益。
- 本次没有真实 QQ/微信账号授权、私人歌单同步或会员歌曲播放的实机验收；这些只能在用户于官方页面完成登录后验证，不能由公开搜索或离线测试推断。



## 2026-11-09 读取失败专项修复

本轮先对旧实现建立失败回归，再调整真实请求；没有使用测试数据兜底成功，也没有读取用户的账号凭据。

- 官方网页的 c.y.qq.com 请求会同时传当前音乐 `uin`、`g_tk`、`g_tk_new_20200303`。旧实现始终发送 `g_tk=5381` 且未发新的校验字段。本轮按 [当前官方 vendor bundle](https://y.qq.com/ryqq/js/vendor.chunk.8a93aba30e8daf947f8f.js) 中的 Cookie 选择顺序和哈希算法生成这两个字段；没有对应 Cookie 的匿名值仍为 5381。这个实际协议缺陷可导致已保存授权下的读取被拒绝，但不能在没有用户失败响应的情况下断言它就是该手机的唯一原因。
- [当前官方 common bundle](https://y.qq.com/ryqq/js/common.chunk.80d50705ce8f47a01eb5.js) 的自建歌单总数为 `totoal`（官方字段原本如此），收藏歌单总数为 `totaldiss`。现在使用真实返回条数推进分页，即使服务端每页少于请求数也继续读取，不因少量返回而把列表误判为完整。
- HTTP 状态、JSON 服务码和当前操作阶段分别保留为不含凭据的诊断。例如连接超时、授权失效、官方验证、请求限流和数据格式异常分别显示不同提示；HTTP 200 并不自动等于读取成功。只有明确的授权失效会清除同代会话，未知拒绝和网络失败保留原会话供重试。
- 原生曲库页提供具体原因、重试和适用时的官方重新登录。账号资料已验证而歌单读取失败时，仍保留账号信息及“喜欢的歌曲”入口，显示“歌单尚未读取”，不显示成“暂无歌单”。没有实际登录的账号不会显示已连接。

### 本轮验证范围

- 修复前隔离快照运行 33 项，4 项失败：双校验字段、服务端缩短页面后的分页、失效原因/重新登录入口、歌单失败后保留已验证账号和喜欢入口。报告：`work/qq-read-diagnosis/red/render-report.json`。
- 修复后真实共享源码运行 `QQProviderTest` 与 `ProviderLibraryPageTest`，36/36 通过。报告：`work/qq-read-diagnosis/green/render-report.json`。新增测试包含 HTTP/网页/空响应不泄露响应内容、服务码不误删会话、匿名热门不调用账号接口，以及只有显式点击才打开网站。
- 公开无凭据联网探测：账号资料 HTTP 200 / code 1000；自建歌单 code 2000；收藏歌单 code -1 / subcode -2；喜欢列表 request code 10004 / data code -100008。对照的公开专辑读取 HTTP 200、顶层与请求 code 均为 0。记录只保存状态码、字段名与数量，未保存私人响应、Cookie 或签名音频地址：`work/qq-read-diagnosis/public-status-report.json`。
- 上述联网结果证明官方服务可达且会区分访问权限；它不证明私人曲库已可用。仍需用户在真实手机重新打开 QQ 来源或完成官方登录，确认其账号的资料、歌单、喜欢列表与实际歌曲播放。若继续失败，新界面可明确显示失败阶段与安全的数字状态码，以便进一步定位。


## 0.7.3：已登录但歌单显示“返回数据不完整”

本轮没有用户私人账号响应，不能确认用户手机的唯一根因。已将截图的“账号正常、随后歌单读取失败”路径最小化为离线 Provider 回归：账号验证通过、喜欢列表通过，然后解析官方已确认的歌单结构。旧实现 30 项测试中新增 3 项全部失败，其中两个结构变体均在 `playlistPages` 抛出相同的泛化“不完整”提示。

按证据排序核验：

1. 收藏歌单名称 `dissname` 未被旧实现读取。官方正常化函数把它作为首选字段；同时补入官方 `imgurl` / `pic` / `albumPic` 封面与 `song_num` 数量字段，以及协议相对的 HTTPS 图片地址。
2. 自建列表包含空系统目录时，旧实现先要求普通歌单编号。官方会先排除无歌曲的 201–209 系统目录。现在按原始返回行数推进分页，过滤目录不会造成跳页或停在首批。
3. 其他未知响应仍可能导致解析失败。新增的解析诊断包含“本人歌单 / 收藏歌单 / 喜欢列表摘要”、页/条目位置和缺失的固定字段名；不展示账号、歌单标题、响应正文或凭据，也不把坏数据兜底为成功空库。

205（Qzone 背景音乐）与 206（本地上传）使用专门的官方服务。对于非空且未提供普通歌单编号的这两种目录，保留实际目录名称/数量的可见条目，点击时明确说明当前接入不支持其中曲目，不能把目录编号当作普通 `dissid` 请求，更不能伪造可播放内容。其他普通歌单继续读取；未知非系统条目缺少必要字段仍然报错。

### 当前官方来源链与验证

2026-11-09 重新读取[官方 profile 页面](https://y.qq.com/n/ryqq/profile)，它当前加载 [runtime~Page.51935d80796062ee34b8.js](https://y.qq.com/ryqq/js/runtime~Page.51935d80796062ee34b8.js)，其中指向 [common.chunk.5b378f933e2203b7da48.js](https://y.qq.com/ryqq/js/common.chunk.5b378f933e2203b7da48.js)。新脚本仍直接包含上述字段映射、系统目录过滤规则及独立 Qzone/upload 读取服务。仅下载和阅读公开文本，未执行远程 JavaScript、未登录、未发送私人凭据。新 common SHA-256 为 `c6c5e7c652a9133779bec192a3fd79515c4ab5462e8d9d6f6d5e8cc75a470f5d`；页面和来源链存于 `work/qq-playlist-diagnosis/page-runtime-chain.json`。

公开匿名联网再次确认：自建歌单接口 HTTP 200 / code 2000，收藏歌单 HTTP 200 / code -1、subcode -2；对照公开专辑 HTTP 200 / request code 0，返回真实 basicInfo。匿名失败没有被解释成私人曲库为空或账号已接通。摘要：`work/qq-playlist-diagnosis/anonymous-endpoint-report.json`。用户仍须在新版本中验证自己的歌单；若仍失败，应反馈新的完整阶段提示，不需要提供 Cookie 或密钥。

最终定向回归：最新共享源码 `QQProviderTest` 32 项与 `ProviderLibraryPageTest` 8 项，共 **40/40 通过**，报告 `work/qq-playlist-diagnosis/green/render-report.json`。新增覆盖 `dissname`、空系统目录跨页、非空特殊目录明确限制、缺数组不冒充空库及安全解析阶段提示；原有账号隔离、撤销、会员/试听检查仍保留。没有进行私人账号曲库或真实会员播放的实机验收。
