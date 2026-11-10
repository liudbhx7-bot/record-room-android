# Runtime libraries

The app uses Media3 ExoPlayer 1.11.1 with HLS, ZXing core 3.5.4, and the official Apple Android MusicKit SDK distribution 1.1.4. That distribution contains **MediaPlayback 1.1.4 and MusicKitAuth 1.1.5**; the different module versions are intentional. The Maven roots and Android variants are pinned in `pom.xml`. The actual resolved artifacts, including both unmodified Apple AARs, are recorded with SHA-256 hashes in `lib/dependencies.json`; `apple-sdk.json` independently pins the downloaded Apple binaries.

Resolve or reproduce the dependencies with a JDK 17+ and Maven 3.9+:

```text
python3 tools/resolve_dependencies.py --maven PATH_TO_MAVEN --repository PATH_TO_LOCAL_MAVEN_CACHE --apple-sdk PATH_TO_UNPACKED_ANDROID_MUSICKIT_SDK_1_1_4
```

Download the SDK from [Apple MusicKit](https://developer.apple.com/musickit/) and supply the unpacked directory containing the two AARs. If the verified AARs are already present under `lib/vendor/apple/`, `--apple-sdk` can be omitted. Rebuilding from a complete verified `lib/` directory requires no Maven download and no Apple private key.

Maven 3.9.16 and dependency plugin 3.8.1 were used. The resolver uses Google's Android repository and Google's Maven Central mirror. It stages resolution and records only the current runtime graph. The build verifies every inventoried artifact; stale files outside the inventory never enter the APK. The large `repository/` Maven cache and temporary `runtime-resolve-*` directories are ignored and must be excluded from source packages.

The official sample's dependencies are selected as Android AARs rather than nonexistent JAR variants. Lifecycle is aligned to 2.6.2, core/core-ktx to 1.13.0, Emoji to 1.3.0, Kotlin to 1.9.23, and Android annotation JVM to 1.7.1. These overrides prevent Maven's strict-version conflicts and class duplication. AppCompat 1.7.0, Material 1.6.1, Gson 2.8.6 and coroutines 1.6.0 remain the sample's versions. DataBinding/ViewBinding 8.3.1 supplies the runtime normally injected by the sample's Android Gradle Plugin. Picasso is used only by the sample application and is not referenced by either SDK AAR.

App Startup is explicitly pinned to **1.1.1**, the version requested by Lifecycle Process 2.6.2. Maven otherwise picks Emoji's shorter dependency path to Startup 1.0.0. That old version initializes entries while still discovering manifest metadata, so Emoji's dependency can trigger ProcessLifecycleInitializer before it is marked eager and crash before the launcher Activity. Version 1.1.1 fixes that discovery behavior; see the [official release note](https://developer.android.com/jetpack/androidx/releases/startup#1.1.1) (checked 2026-11-09). `NativeStartupProviderTest` boots the real merged-manifest provider before MainActivity and tests declaration-order variants. Activity-only Robolectric tests do not exercise this Android process-start path; an installed-APK launch check is also required for release acceptance.

Build using Android SDK platform 36 and official SDK build tools:

```text
python3 tools/build.py --sdk PATH_TO_ANDROID_SDK --platform-jar PATH_TO_PLATFORM_36/android.jar
```

Use JDK 17, Android platform 36 and Build Tools 36. Media3 requires compile SDK 36; minimum API remains 26 and target API 36. The build unpacks actual AAR classes, resources, assets and native libraries, generates each library's R class, and packages bytecode with D8. The DataBinding aggregate generated under `build-manual/gen` delegates to the SDK's real generated binding mappers; it does not replace SDK behavior. Merged manifests retain Apple authorization activities, official-app package visibility, playback reporting services and the combined AndroidX startup initializers. App manifest attributes retain precedence.

The original package name is retained, but the original signing key and all dependency binaries are excluded from the public repository. Create your own ignored `tools/development.keystore` with the `baseline` alias using [the public build guide](../../docs/BUILDING.md). A locally generated key cannot update an installation signed with the original Release key. Login sessions, developer signing secrets and playback URLs do not belong in this directory. Runtime Apple account authorization and subscription checks are separate from building the APK.

MusicKitAuth 1.1.5 contains debug statements that include the music-user-token. `tools/apple_auth_logging.py` processes **only that AAR's Java classes** using official Android R8, removes `Log.d/i/v` calls, and disables tree shaking and name minification. It verifies all 26 classes and every field/method signature and access flag remain identical, and rejects surviving debug-log references. The original AAR stays untouched. `build-manual/apple-auth-logging.json` records the input path/hash, processed hash, R8/rules hashes and checks. Both final D8 packaging and Robolectric use the processed JAR through `runtime-dependencies.json`.

Apple ships `arm64-v8a` and `armeabi-v7a` native libraries. There are no x86/x86_64 Apple binaries, so SDK playback requires an ARM device. Both official arm64 libraries have 16,384-byte ELF LOAD alignment. The 32-bit variants have 4,096-byte alignment. The build fails for an inadequately aligned 64-bit library, stores native libraries uncompressed, runs `zipalign -P 16` before signing and verification, and checks each final ZIP data offset. `build-manual/native-alignment.json` records the ELF hashes/alignment and signed-APK offsets. These are package checks; they do not establish real-device playback or 16-KB device acceptance. See [Android's 16-KB guidance](https://developer.android.com/guide/practices/page-sizes) and [zipalign](https://developer.android.com/tools/zipalign) (checked 2026-11-09).

The original third-party license texts and all 75 Maven artifact attributions are in `app/src/main/assets/licenses/dependencies.txt`. `license-artifacts.json` records the actual artifact/POM hashes and declared licenses. `license-sources.json` records upstream notice URLs and hashes. `app/src/main/assets/licenses/apple-musickit.txt` separately records Apple SDK provenance, the narrowly scoped debug-log processing, and the embedded JavaCPP/LLVM upstream notices. It does not relicense Apple's binaries or claim an undisclosed embedded JavaCPP version.

The Robolectric runner uses the same prepared runtime classpath and merged resource APK. It supports `--test-class com.recordroom.vinyl.Media3PlaybackTest` for an isolated engine run. `NativeQaMain` initializes the host JCE verifier before Robolectric installs Conscrypt, avoiding an Oracle JDK 21 framework-signature initialization failure while retaining real AES/GCM operations and all authentication assertions.
