# 0.7.6 QQ 播放错误诊断

本次确认并修复的是错误提示混用。旧版把缺匹配资源、没有播放地址、播放请求被拒绝、无法确认时长、返回音频过长，全部归为“当前 QQ 音乐账号未取得完整播放或数据权限”。因此用户截图不能证明会员不足，也无法证明一定是服务端权益问题。

## 实际触发链和复现

歌曲点击进入 `QQProvider.resolve`：先读取当前加密会话，再获取歌曲详情及标准 MP3 的 `media_mid`，携带该会话请求 `vkey.GetVkeyServer/CgiGetVkey`；匹配返回的文件、检查地址及试听标志后，`QQAudioProbe` 最多读取 256 KiB，确认 MP3 完整时长，再允许播放器使用该资源。

先用当前源码运行 QQ 后端和权限元数据测试，41/41 通过。随后在独立工作目录构建合成响应矩阵，5/5 通过，复现同一旧提示可由互不相同的原因引发。正式修复前新增断言“这些错误不得声称账号没有权限”：3 项测试中 2 项失败，准确复现错误文案，而不是以编译失败充当红测。修复后 QQ 定向 46/46 通过。

| 边界 | 现在显示的原因 |
| --- | --- |
| 没有匹配请求文件的记录 | 资源不匹配，显示安全的返回记录数 |
| 返回非零资源 result | 请求未获批准，只显示数字 code |
| 匹配记录的播放地址为空 | 当前接入未获得播放地址，并明确仅凭此响应无法确认是否为会员限制 |
| 不能确认完整时长 | 音频时长验证失败 |
| 返回时长明显大于歌曲标注时长 | 版本/时长不一致，显示两项秒数 |
| 音频 HTTP 失败 | 音频请求阶段及 HTTP 数字状态 |
| 返回网页/非音频内容 | 非音频响应 |
| 音频连接超时、DNS 或 TLS 异常 | 验证播放音频失败及对应网络原因 |
| 明确试听或音频明显短于整曲 | 继续阻止试听冒充全曲 |
| 匹配授权地址且完整时长通过 | 继续正常返回播放资源 |

没有增加第三方音源、尝试其他歌曲、放宽音频域名、改登录协议、改音质、跳过试听检测或触发购买。错误消息不输出 Cookie、key、完整响应、签名 URL 或服务端任意字符串；测试用固定伪敏感值验证这些内容不出现在提示里。新增 NETWORK 分类只针对音频网络异常，未将它解释为会员问题。

## 凭据核对与限制

- 登录候选会话先经本人喜欢列表与资料页验证，然后写入设备密钥保护的 QQ 独立存储。播放从同一个在线来源实例读取该存储。
- QQ/微信两类合成会话都已验证：相应音乐 UIN、`comm.authst` 及允许的 Cookie 到达官方 API；微信的身份字段没有丢失。音频 CDN 不接收 Cookie 或授权头。
- 这些验证证明传递路径，不能证明某个真实 key 未失效、当前会话拥有会员、官方网页授权拥有同手机 App 一样的播放能力，或该歌曲在当前地区可全曲播放。
- 2026-11-10 重新只读下载 [QQ 官方 common bundle](https://y.qq.com/ryqq/js/common.chunk.5b378f933e2203b7da48.js)、[vendor bundle](https://y.qq.com/ryqq/js/vendor.chunk.d8b4489cbdb338cb0470.js)、页面与官方基础 SDK。当前官网明确区分微信 UIN 与普通 UIN，其方向与项目相符。common 里存在 `qqmusic_key` 优先于 `qm_keyst` 的工具函数，但本次未取得它直接驱动当前播放请求的完整调用证据，不能据此断言项目选 key 的顺序是本次根因，更没有猜测修改协议。来源 URL、HTTP 状态和 SHA-256 在 `work/qq-playback-diagnosis/official-source-evidence.json`。

**没有读取真实凭据，没有登录用户账号，没有声称私人 VIP 歌曲播放已修复。** 判断用户那次失败仍需具体歌曲/版本，以及官方 App 相同账号对同一版本能否完整播放；新版分阶段错误可进一步确定请求停在哪一步。音频格式不受当前完整时长检查支持时仍会保守拒绝播放，但不再据此说用户没有会员。

## 可运行证据

- 修复前精确错误文案回归：`work/qq-playback-diagnosis/red/render-report.json`。
- 修复后定向回归：`work/qq-playback-diagnosis/green/render-report.json`，46/46。
- 当前安全矩阵：`work/qq-playback-diagnosis/diagnostic-matrix.json`，由本轮正式测试输出复制，包含 15 项边界；`private_account_used=false`、`real_audio_playback_verified=false`。
- 独立只读诊断原型：`work/qq-playback-diagnosis/qa/tests/com/recordroom/vinyl/QQPlaybackDiagnosticTest.java` 与 `matrix/render-report.json`。这是修复前证据，保留历史行为，不作为修复后通过断言。
- 正式回归类：`tools/native-qa/tests/com/recordroom/vinyl/QQPlaybackDiagnosticsTest.java`。全包构建、完整测试及实际 Android 安装由主流程统一执行，本报告不代替用户真机验证。
