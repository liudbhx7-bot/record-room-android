# API 34 音频服务兼容

查阅及验证：2026-11-09。只作用于 QA 的 Robolectric 4.17 / Android API 34，未放入应用源集或 APK。

Robolectric 的 [ShadowPlayerBase](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowPlayerBase.java) 使用 `ReflectionHelpers.createNullProxy(IAudioService.class)`。JDK 动态代理初始化会加载服务接口全部方法涉及的类型，继而初始化 BluetoothDevice。4.17 的 [ShadowBluetoothDevice](https://github.com/robolectric/robolectric/blob/robolectric-4.17/shadows/framework/src/main/java/org/robolectric/shadows/ShadowBluetoothDevice.java) 含旧 SDK 不存在的 `BluetoothDevice.BluetoothAddress` 方法签名；反射获取方法阶段先于 minSdk 过滤，导致 API 34 启动 SoundPool 时失败。

`Api34AudioServiceShadow` 仅覆写 API 34 的 PlayerBase.getService，返回同一 Android API 34 jar 中生成的 `IAudioService.Default`。与原空代理保持默认返回值和无系统服务副作用的语义，避免不必要的动态代理反射。没有提供蓝牙替身，没有覆盖 SoundPool，没有关闭应用落针音效。

`run_render.py` 将兼容源和 robolectric.properties 复制到临时 QA 类路径，并在报告中记录其 SHA-256。其它 SDK 不启用这个 shim。升级 Robolectric 后应先删除兼容项重跑验证，确认上游问题修复后再移除它。

`AudioServiceCompatibilityTest` 读取安装包里的落针采样，检查响度与余量，并用原装 ShadowSoundPool 验证实际 NeedleSound 的加载、音量、取消及释放行为。MainRenderTest 和 InteractionTimingTest 保持原有音效路径、页面构造和动画断言。这里验证的是 Android 测试环境行为，不构成真机音频输出验收。

补充：Media3 Spatializer 也经 AudioManager.getService 触发同一代理问题，Api34AudioManagerShadow 继承上游 ShadowAudioManager，仅将 getService 返回相同 API34 IAudioService.Default。真实 Media3 解码、seek/pause、退出保留内置播放的 21 项回归已通过。此 shim 不进入 APK，不改变实际蓝牙或空间音频行为。
