# 从公开源码构建

这是 Android 原生 Java 工程，使用项目脚本调用 Android SDK 工具构建，不需要 Gradle。以下命令在仓库根目录执行，面向 macOS / Linux 的 Bash 或 Zsh；将示例路径换成本机实际路径。

公开仓库不包含原发布签名私钥、Apple SDK AAR、Maven 二进制依赖或账号凭据。自行构建使用自己的开发签名，不能覆盖安装 GitHub Release 中使用原签名的 APK。测试自签版本请使用单独的模拟器或设备，不要为了替换签名而卸载有个人资料的应用。

## 1. 准备工具

需要 Python 3、JDK 17、Maven 3.9 或更新版本，以及 Android SDK Command-line Tools。编译平台为 API 36，Build Tools 为 36.0.0；最低运行版本为 Android 8 / API 26。API 35 不能替代本工程当前的编译平台。

```sh
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_SDK_ROOT="/path/to/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

java -version
python3 --version
mvn -version
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358"
```

SDK 工具提示许可协议时，在本机阅读并完成其交互流程。构建脚本本身不下载 Android SDK 或自动接受协议。

## 2. 下载官方 Apple MusicKit SDK 并解析依赖

从 [Apple MusicKit 官方入口](https://developer.apple.com/musickit/) 获取 **Android-MusicKit-SDK-1.1.4**，必要时使用自己的 Apple 开发者账号登录。该发行包内的两个模块版本不同：

- `MediaPlayback-release-1.1.4.aar`
- `MusicKitAuth-release-1.1.5.aar`

解压后的目录必须直接包含这两个 AAR。项目通过 [apple-sdk.json](../tools/dependencies/apple-sdk.json) 验证它们的 SHA-256；不要给其他版本改名冒充。若官方改发新版本，需要另行更新依赖与适配，不能只跳过校验。

```sh
mkdir -p private/apple-sdk
unzip "/path/to/AndroidMusicKitSDK1.1.4.zip" -d private/apple-sdk

python3 tools/resolve_dependencies.py \
  --maven "$(command -v mvn)" \
  --repository tools/dependencies/repository \
  --apple-sdk private/apple-sdk/Android-MusicKit-SDK-1.1.4
```

解析器根据 [pom.xml](../tools/dependencies/pom.xml) 下载固定版本依赖，并在 `tools/dependencies/lib/dependencies.json` 记录实际文件摘要。`private/`、依赖 AAR/JAR 和 Maven 缓存已被 Git 忽略。公开仓库只保留清单和来源，不包含这些二进制文件。

Apple 开发者 JWT、Music-User-Token、`.p8` 私钥都不是编译所需文件。运行时授权按 [Apple Music 接入说明](apple-music-integration.md) 在本机配置，不能将凭据写入仓库。

## 3. 生成自己的开发签名

当前脚本约定读取 `tools/development.keystore`，别名为 `baseline`。下面仅在文件不存在时生成本地开发签名；已生成的文件应自行妥善保存。

```sh
if [ ! -e tools/development.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore tools/development.keystore \
    -storetype JKS \
    -alias baseline \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Record Room Local Development"
fi
```

这里的 `android` 是本地自签开发用途的公开约定密码，不是原发布签名的私钥。生成相同的别名和密码也不会得到相同的签名身份。不要提交 keystore；不要用这组公开密码管理正式发行密钥。

## 4. 构建本地解码器和 APK

在 macOS 或 Linux 上先安装 make、tar 和 xz。以下脚本校验 FFmpeg 8.1.3 官方源码摘要，使用 NDK 28.2 分别生成 ARM64 和 ARMv7 的本地读取器。下载结果与中间文件保存在忽略提交的 `work/audio-codecs`。离线构建可使用本版 Release 随附的 `ffmpeg-8.1.3.tar.xz`，通过 `--archive /path/to/ffmpeg-8.1.3.tar.xz` 指定。

```sh
python3 tools/audio/build_codecs.py --ndk "$ANDROID_SDK_ROOT/ndk/28.2.13676358"
```

```sh
python3 tools/build.py \
  --sdk "$ANDROID_SDK_ROOT" \
  --platform-jar "$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
```

当前版本产物为 `dist/vinyl-baseline-v0.8.0.apk`（版本代码 21），摘要位于 `dist/SHA256SUMS.txt`。版本和文件名从 AndroidManifest.xml 读取。构建会核对依赖摘要、合并真实 SDK 资源与声明、清除 Apple 授权模块的敏感调试日志，并验证 APK 签名及 16 KB 对齐。

自签构建与 Release APK 不会有相同 SHA-256。公开导出还省略了已经停用的汽水直连网页资源，清单见 [excluded-legacy-assets.json](excluded-legacy-assets.json)；官方 App 媒体联动不使用这些资源。原 0.7.7 Release APK 保留其已验证的原始字节与签名。

## 5. 运行自动测试与原生界面渲染

先完成上一步，测试需要新生成的 `build-manual/base.apk`、`R.java` 和运行时依赖。测试依赖根据 [QA pom.xml](../tools/native-qa/pom.xml) 单独解析，和应用运行时依赖是两个目录。

```sh
record_room_qa_dir="$PWD/tools/native-qa/lib"

mvn -B -ntp \
  -s tools/dependencies/settings.xml \
  -f tools/native-qa/pom.xml \
  org.apache.maven.plugins:maven-dependency-plugin:3.7.0:copy-dependencies \
  "-DoutputDirectory=$record_room_qa_dir" \
  -DincludeScope=test

python3 tools/native-qa/run_render.py \
  --project . \
  --deps "$record_room_qa_dir" \
  --out work/native-qa
```

测试使用 Robolectric 4.17 / Android API 34；这不改变应用使用 API 36 编译的要求。首次解析会下载数百 MB 的依赖。终端会显示测试结果，`work/native-qa/render-report.json` 记录测试类、源码摘要、资源摘要和 PNG；失败会返回非零退出码。

可用 `--test-class com.recordroom.vinyl.FoldBackgroundPlaybackTest` 定向执行一个类。完整验证应使用默认完整测试集。原生渲染、JVM 音频状态、安装后启动、实体设备声音和真实平台账号权益分别需要对应验证，不能互相替代。

更多范围与结果说明见 [QA 目录说明](../tools/native-qa/README.md)。公开源码使用 JDK 17、API 36 编译和自有开发签名，测试结论以本次实际运行结果为准。
