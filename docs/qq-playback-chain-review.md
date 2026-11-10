# 当前 QQ 播放链复核

本轮复核没有发现可以据此安全更换播放协议的确证修复。用户暂时无法连接手机，且未确认官方 App 相同账号能否完整播放同一版本，因此不能将“接口未返回地址”直接归因为没有会员，也不能声称已经解决用户的私人 VIP 播放问题。

2026-11-10 重新读取当前 QQ 官网页面加载的脚本，未执行远程脚本、未读取私人凭据：

1. 当前 [播放器脚本](https://y.qq.com/ryqq/js/player.chunk.d0a478ab3d586bf78298.js) 组装音乐 UIN、歌曲 mid/type、guid、loginflag、platform、xcdn，调用 `music.vkey.GetEVkey / GetUrl`。官网依据当前歌曲 action 和 tryPlay 处理试听请求；项目仍禁止把试听当全曲。
2. 当前 [公共请求层](https://y.qq.com/ryqq/js/vendor.chunk.d8b4489cbdb338cb0470.js) 的 `getRequest` 导出指向现用 request 实例；它加入 UIN、两个 CSRF 字段，保留官方 Cookie，并对需要签名的浏览器请求走现用加密签名通道。当前 [runtime](https://y.qq.com/ryqq/js/runtime~Page.51935d80796062ee34b8.js) 可追溯到上述播放器资源。
3. 项目仍使用既有参考中的 `vkey.GetVkeyServer / CgiGetVkey`、明确请求 M500、会话 UIN、authst 与官方域 Cookie。当前官网链路与它不同这一点已证实，但这种差异本身并不能证明真实账号失败的具体原因，也不能证明简单替换方法名就可播放。未猜测更换 key、登录类型、签名或权益逻辑。
4. 前轮已确认的“多个非权益错误复用权限不足文案”继续保持分阶段诊断。缺地址、资源拒绝、资源不匹配、时长验证和网络错误不再互相混淆。

当前来源 URL、HTTP 状态、SHA-256 在 `work/artist-search-077/sources.json` 与 `work/qq-playback-diagnosis/official-source-evidence.json`。调用链片段位于 `work/artist-search-077/qq-playback-chain-excerpts.json`，只作为只读调查材料，不作为运行时远程脚本。

## 可执行验证

- QQ 后端、错误诊断和真实 Media3 引擎的定向组合 **53/53**：`work/artist-search-077/playback/render-report.json`。
- QQ 测试覆盖当前会话凭据转发、官方域隔离、会员/单购元数据不会阻止实际授权请求、匹配的完整 MP3 时间线接受、未知和试听拒绝、退出/切换账号后的迟到结果拒绝。
- Media3 使用内置的非商业演示音频与合成网络资源，验证完整时长、真实解码/播放器状态、seek/pause、到达结尾、地址刷新与试听拒绝。这是 JVM 音频测试，不是手机外放或私人 QQ 歌曲实听。
- 新增真实歌手搜索同时完成 QQ/网易匿名联网、分页与图片解码验证，详见 `docs/artist-search-providers.md`。它不等同于会员音频授权验证。

后续定位私人失败需要具体歌曲/版本、官方 App 同账号完整播放的对照，以及新版准确阶段提示或连接手机取得安全诊断。无需提供 Cookie、token、私钥。当前没有这部分证据，保持问题边界，不通过放宽校验制造“成功”。
