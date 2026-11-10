# Android原生回归

0.7.7发行包开发版本完整回归470项通过、76个测试类、163张原生渲染。验证边界见 [0.7.7记录](../../docs/VERIFICATION.md)。公开源码重跑需要先构建当前资源，不复用旧报告。

Robolectric 4.17 / API34，逐类使用Native或Legacy模式。音频测试使用Media3及本地/合成资源，不能代替实际扬声器、私人账号或厂商后台行为。

从仓库根目录，完成 [构建准备](../../docs/BUILDING.md) 后：

```sh
mvn -f tools/native-qa/pom.xml -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.7.0:copy-dependencies -DoutputDirectory="$PWD/tools/native-qa/lib" -DincludeScope=test
python3 tools/native-qa/run_render.py --project . --deps tools/native-qa/lib --out work/native-qa
```

可用 `--test-class com.recordroom.vinyl.Media3PlaybackTest` 跑相关测试，或 `--tests main` / `--tests components` 选择页面或组件。全量自动发现 `tests/com/recordroom/vinyl/*Test.java`。报告包含源文件摘要、资源APK摘要、测试类、退出码与PNG列表。

使用JDK17、Android SDK36和Build Tools36。首次下载测试依赖可能较大；本仓库不包含SDK/JAR/AAR缓存，也不支持把API35当作当前Media3的编译SDK。
