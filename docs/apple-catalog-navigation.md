# Apple Music 完整专辑与艺人资料

本次使用 Apple Music REST 的目录、资料库关系与分页；完整音频仍交给已有的官方 MusicKit SDK。没有将 previews URL 转作整曲，也没有更改授权或订阅边界。

## 行为

- `AppleMusicProvider.artist(id)` 返回官方 `editorialNotes` 简介、艺人图片、当前地区的热门歌曲（最多 10 首）及首批 25 张专辑。简介转为纯文本；平台未提供的简介和图片保持为空，不编造背景资料。热门歌曲来自 Apple 的 `top-songs` 视图，并非个性化推荐。
- `artistAlbums(id,offset,limit)` 使用 `/v1/catalog/{storefront}/artists/{id}/albums`，从响应 `next` 读取真正的下一 offset，不用本页数量猜分页。页面可以分批加载全部目录专辑。最多请求 100 张一页；异源、跨资源、循环或接口无法表示的 offset 会明确失败。
- 打开资料库专辑时，先读取 `catalog` 关系找到目录版本，再完整分页读取目录 tracks。资料库里的 `trackCount` 可能只计算已收藏歌曲，因此绝不把它当整张专辑。没有目录映射的导入专辑明确提示无法读取完整专辑，原有资料库歌曲仍能单独使用 MusicKit 的 library ID 播放。
- 完整专辑最终曲目数与目录 `trackCount`（如果提供）校验；少曲目时不显示为已完整载入。歌曲顺序与重复项保持服务端顺序。含音乐视频的专辑仍按现有音频界面边界明确拒绝，不静默遗漏视频后宣称完整。
- library songs 与 playlist tracks 显式请求 albums、artists、catalog 关系。若返回目录关联，导航 ID 优先使用目录专辑/艺人 ID；实际歌曲 ID 和 `applemusic://library-songs/i.xxx` 保持不变。后续分页保留 include 参数。
- Library artist ID 通过已授权的 `/v1/me/library/artists/{id}/catalog` 转换为目录 ID。返回 `Artist.id` 仍对应调用时的 ID，便于页面核对请求身份；之后 `artistAlbums` 可继续接受相同 ID。

## 官方依据

- [资源、扩展属性、关系及按资源类型指定 include](https://developer.apple.com/documentation/applemusicapi/handling-resource-representation-and-relationships)
- [分页中的 next 子路径](https://developer.apple.com/documentation/applemusicapi/fetching-resources-by-page)
- [目录艺人关系读取](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-5akdm)
- [艺人热门歌曲等关系视图](https://developer.apple.com/documentation/applemusicapi/fetch-a-view-on-this-resource-by-name-4kow5)
- [艺人官方简介与图片属性](https://developer.apple.com/documentation/applemusicapi/artists/attributes-data.dictionary)
- [资料库专辑 catalog 关系](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-165fz)
- [资料库歌曲关系](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-9xvg6)
- [资料库艺人 catalog 关系](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-9dsoc)

## 验证边界

`AppleMusicCatalogTest` 使用官方结构的合成响应，覆盖完整专辑映射、分页、不完整曲目拒绝、library ID 保留、艺人元数据、空简介、无目录映射、分页异常与关联信息跨页保留。与已有 API/session 集成测试一起定向运行。因为其他代理正在修改艺人原生页，首次验证使用只包含相关真实源文件的隔离项目，复用已有 APK 资源和依赖，没有模拟替代生产 API 或 MusicKit 实现。

本轮仍没有正式开发者 JWT 或用户账号请求结果。上述通过表示协议处理与账户隔离的 fixtures 通过，不代表线上账号、地区版权或实体设备 DRM 播放已验收。
