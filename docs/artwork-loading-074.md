# 0.7.4 搜索封面纯色方块修复

本轮检查基于 `7a7eb10`，仅修改通用 `RemoteArtworkCache` 与 `CoverView`；没有修改搜索排序、账号、歌词、播放权限或提供者返回的曲目资料。

## 已确认原因

1. **QQ 官方图片在下载前被自己拒绝。** `QQProvider.albumCover()` 生成 `https://y.qq.com/music/photo_new/...jpg`，下载白名单却没有该地址。`LocalMusicImporter.loadArtwork()` 捕获该异常并返回 null，界面留下纯色背景。现在只允许该官方主机的图片目录与已知图片后缀；没有开放任意 QQ 页面、其他主机或带账号信息的地址。
2. **暂时移出窗口的封面不恢复。** `onDetachedFromWindow()` 会让未完成的回调失效，但重新附着没有重启请求。现在保留 URI/尺寸并在重新附着时恢复；重绑到另一首、清空或关闭后，旧回调仍不能覆盖新状态。相同 URI 正在读取时不会因列表重复绑定而重新排队，队列内也复用已解码的封面。

修复前先跑实际 `QQProvider.searchTracks → Track.artworkUri → CoverView → LocalMusicImporter → RemoteArtworkCache` 回归，两个测试均失败：QQ 图片最终为 null bitmap；detach/reattach 后仍无图。常规回归用确定的缓存图片字节隔离网络波动；它不是私人账号验证或真实音乐播放测试。

## 验证

- 原始红报告：`work/artwork-diagnosis/red/render-report.json`，2 项失败。
- 修复后封面管线、提供者边界和聚合搜索页：14/14 通过，`work/artwork-diagnosis/green/render-report.json`。额外覆盖旧请求不覆盖新图、clear 后不重新加载、拒绝伪造/越界 QQ 图片地址。
- 2026-11-10，无账号或 Cookie，**真实生产 QQ 与网易云 Provider** 分别搜索“刘德华”，均得到 3 首公开结果；取首条封面实际下载、解码及绑定 `CoverView`：
  - QQ《暗里着迷》：47,258 bytes，解码宽 250px，采样得到 164 种颜色，实际可显示。[官方封面](https://y.qq.com/music/photo_new/T002R500x500M000000JGIuD2BotLz_4.jpg)
  - 网易云《练习》：35,896 bytes，解码宽 200px，采样得到 26 种颜色，实际可显示。[官方封面](https://p1.music.126.net/pY26KwTrUoOUPfNgglbXjA==/109951166779957502.jpg)
  - 独立 QQ 公开专辑对照《裙下之臣》封面同样成功，76,642 bytes、解码宽 250px。
- 真实生产源公开联网探针 1/1 通过：`work/artwork-diagnosis/live/render-report.json`，细节 `public-artwork-report.json`，原生结果 `public-artwork-native.png`。探针位于 `work/artwork-diagnosis/live-qa/`，不纳入要求离线的日常测试。

网易云的本次实际搜索已经返回有效图片 URL，不需要编造专辑图片或改写网易云字段。以上证明该版本在测试环境能显示公开真实封面；不代表用户手机上的全部网络、私人账号或播放权益均已验收。仍需安装后复核同一搜索和滚动场景。
