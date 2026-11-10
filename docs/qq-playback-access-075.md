# 0.7.5 QQ 播放要求标记

这次修复的是搜索结果缺少播放要求提示。QQ 的歌曲元数据原先丢弃 `pay` 和 `action`，因此会员曲、单独付费曲与普通曲在结果里无法区分。此修改没有证明用户私人 QQ 账号已成功播放会员歌曲；会员身份、购买、版权、地区和实际播放地址仍由原来的授权解析流程决定。

## 数据合同

`Track.PlaybackAccess` 为 `UNKNOWN / STANDARD / VIP / PURCHASE`；`playbackAccess` 是不可变字段，`withPlaybackAccess` 复制元数据时保留歌曲身份及 URI。`accessLabel()` 分别返回空、空、`需 VIP`、`需单独购买`。全部旧构造器默认 `UNKNOWN`；JSON 使用 `playbackAccess` 名称字段，旧记录、非法或未来枚举值保持 `UNKNOWN`。QQ 完整专辑重建歌曲时保留该字段。

`STANDARD` 只表示这一响应明确没有播放付费要求，不保证版权、地区、登录或地址可用。该字段不表示当前账号已经具备权益。会员与付费曲仍可点击，仍进入既有授权解析、完整时长与试听检测；不会跳过它们或自动降级为试听。

## 公开依据与判定

2026-11-10，无账号、无 Cookie，对 QQ 公开搜索请求 `music.search.SearchCgiService/DoSearchForQQMusicMobile` 查询四组关键词，HTTP 200、40 条歌曲。又读取 QQ 当前官网 JavaScript 以及它调用的 `music.vip.PayAlertSvr/GetPayAlertH5` 的 `yqq_p / yqq_p_u` 播放提示。只读取公开响应，没有执行远程脚本。

- [QQ 搜索页面](https://y.qq.com/n/ryqq/search?w=%E9%99%88%E5%A5%95%E8%BF%85&t=song) 当前 runtime 为 `runtime~Page.51935d80796062ee34b8.js`。
- [QQ 官方 common bundle](https://y.qq.com/ryqq/js/common.chunk.5b378f933e2203b7da48.js) 对新旧字段作映射：`pay_play/payplay`、`pay_month/paytrackmouth`、`price_album/payalbumprice/payalbum`。SHA-256：`c6c5e7c652a9133779bec192a3fd79515c4ab5462e8d9d6f6d5e8cc75a470f5d`。
- 官方 bundle 通过 [QQ 公开请求入口](https://u.y.qq.com/cgi-bin/musicu.fcg) 的 `music.vip.PayAlertSvr/GetPayAlertH5` 获取播放提示。具体请求与响应节选保存在 `work/qq-playback-access/official-alert-evidence.json`。

| 输入条件 | 标记 |
| --- | --- |
| 明确 `pay_play=0`（含旧 `payplay`） | STANDARD；下载收费、曲目售价或专辑售价不改变它 |
| `pay_play=1`，且 `action.alert` 为官方已核实的会员播放提示 | VIP |
| `pay_play=1`，且 `action.alert` 为官方已核实的单独购买提示 | PURCHASE |
| 旧响应无 alert/明确 0，`pay_play=1`、`pay_month=1`、专辑价明确为 0 | VIP |
| 缺少、非法、新的权限值或无法确认的提示码 | UNKNOWN |

已核实的会员提示码为 2、21、22、31、32、33、40–45、66、69、70、205、214、88888888；其中超级会员提示属于 VIP 这一类别，标签不承诺普通会员已具备权益。单独购买提示码为 6、7、17、28、63、64、65、68；预购/官方数字唱片的限制仍由原播放流程处理。39 明确提示不能在线播放，即使购买也只能下载后播放，保持 UNKNOWN，不承诺购买就可在线听。新提示码不套用未知规则。

真实反例：

- 《天空之城》`001hskFn1KsULf` 的 `pay_play=0`，但 `pay_down=1`、`pay_month=1`、`price_track=200`，不能显示“需 VIP”。
- 《最伟大的作品》`003w2xz20QlUZt` 的 `price_album=3000`，但 `action.alert=41`，官方播放提示要求会员，不能仅凭专辑价显示“需单独购买”。
- 《葡萄成熟时》`0029Zemv0kR1ur` 的 `pay_play=1`、`action.alert=21`，官方提示要求会员。

这 40 首公开样本包含会员及非播放收费歌曲，没有遇到真实单独购买歌曲；单独购买分支由官网当前公开提示表及对应离线 fixture 验证，不能称为私人账号购买/播放实测。运行时只读取搜索/专辑响应已有字段，**每次搜索不会增加权益查询网络请求**。

## 验证

- 先运行真实 `QQProvider.track → Track.toJson/fromJson` 路径：会员字段预期 `VIP`，实际为空，红测 1/1；`work/qq-playback-access/red/render-report.json`。
- 修复后 QQ 元数据与后端定向测试 **41/41**；`work/qq-playback-access/green/render-report.json`。覆盖新旧字段、下载收费、正专辑价会员曲、单独购买、非法/未知值、旧 JSON、完整专辑复制、VIP/PURCHASE 继续调用实际 resolver 的授权 fixture。
- 公开联网字段记录：`work/qq-playback-access/public-field-report.json`；不包含用户凭据或音乐文件。
- 未使用用户账户，不能替代用户手机上的会员全曲播放验证。原先登录后无法播放的具体权益原因，不能由搜索元数据推定。
