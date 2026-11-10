# 真实音乐人搜索：QQ / 网易云 / Apple Music

三个来源现在实现 `supportsArtistSearch()` 和 `searchArtists(query, offset, limit)`，返回各平台自己的音乐人资源。歌曲结果中的歌手名称不再被这条接口当作音乐人搜索结果。返回 ID 可直接交给相同来源的 `artist(id)`；简介、头像没有提供时保持空，热门曲目与专辑留待打开主页后读取。

## 当前公开来源与数据

2026-11-10 对 QQ、网易云的匿名公开接口作了真实请求，随后用当前 Java `QQProvider / NeteaseProvider` 各连续读取两页，验证不同歌手 ID 和头像下载/解码。没有使用私人账号、Cookie 或音乐文件。

| 来源 | 实际接口/结果 | 分页与身份 |
| --- | --- | --- |
| QQ | `music.search.SearchCgiService/DoSearchForQQMusicMobile`，`search_type=1`，`data.body.singer` | `singerMID` / `singerName` / `singerPic`；page_num 与 limit 对齐，使用 meta.sum 和实际翻页位置 |
| 网易云 | `/api/cloudsearch/pc`，`type=100`，`result.artists` | 数字 `id` / `name`；优先 `img1v1Url`，无则 `picUrl`；使用 artistCount / offset / hasMore |
| Apple Music | `/v1/catalog/{storefront}/search?types=artists` | 资源必须是 artists，使用目录 ID / attributes.name / 可选 artwork；下一 offset 来自响应 next |

QQ 的两页最初由公开服务器验证，而不是从“1 通常代表歌手”猜测。官网当前桌面搜索不提供独立歌手分类入口，但当前公开移动搜索响应确实返回歌手资源。实际 Java 结果中刘德华为 `003aQYLo2x8izP`，返回头像 150×150 成功解码。网易云官网当前搜索脚本明确把 100 配置为歌手分类；实际 Java 结果中刘德华为 `3691`，官方头像 1999×1999 成功解码。搜索数量是动态响应，不能将某次总数视为固定目录大小。

- [QQ 当前搜索页面](https://y.qq.com/n/ryqq/search?w=%E5%88%98%E5%BE%B7%E5%8D%8E&t=song)
- [网易云当前搜索脚本](https://s3.music.126.net/web/s/pt_search_index_2458ebaab747512ef431d6239b2cb38c.js?2458ebaab747512ef431d6239b2cb38c)
- [Apple 官方按资源类型搜索](https://developer.apple.com/documentation/applemusicapi/search-for-catalog-resources-(by-type))：支持 artists、limit、offset。
- [Apple 音乐人属性](https://developer.apple.com/documentation/applemusicapi/artists/attributes-data.dictionary)：name 必需，artwork 和 editorialNotes 可选。

头像只使用来源返回的歌手头像地址，不拿专辑封面冒充真人头像。QQ/网易地址升级 HTTPS 并限定可信图片来源，Apple 沿用现有官方 artwork 校验。缺头像仍保留音乐人条目。重复身份、缺少必要字段、不能前进的分页与账号切换后的迟到结果明确失败，不假装空搜索成功。三家结果不跨来源复用 ID。

Apple Music 依既有开发者令牌/地区配置访问正式 API。未配置时返回明确的配置/授权错误，不显示假成功。由于没有开发者凭据，本次没有进行 Apple 私人账户或真实开发者令牌联网验证；其请求类型、响应结构、分页与取消由官方文档和离线边界测试验证。

## 测试证据

- 新增三家接口的首次红测：3/3 均触发原默认“当前来源尚未提供音乐人搜索”，`work/artist-search-077/red/render-report.json`。
- 修复后新增 6 项加既有 provider 测试共 **61/61**：`work/artist-search-077/green/render-report.json`。覆盖真实资源类型、分页、头像、空结果、重复/无效身份、受限响应、错误资源、未配置与会话切换。
- 真实 Java Provider + HTTP + 头像缓存联网验证 **1/1**，记录在 `work/artist-search-077/live/public-artists-report.json` 和 `live/render-report.json`。两家各两页三位歌手，跨页 ID 不重复。
- 官方来源索引与 SHA-256：`work/artist-search-077/sources.json`。

这些测试证明歌手搜索、分页和公开头像读取；不证明私人会员曲库权益或手机上的全曲播放。UI、最终 APK 与真实手机验收由主流程继续验证。
