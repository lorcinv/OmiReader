# Building OmiReader / 构建 OmiReader

## Requirements / 环境

- JDK 17.
- Android SDK Platform 36 and Build-Tools 36.0.0.
- The included Gradle 8.13 wrapper.
- Node.js is only needed to regenerate launcher icons, not to build the Android app.

Set `JAVA_HOME` to your JDK. Create a local, untracked `local.properties` with `sdk.dir` pointing to your Android SDK, or configure `ANDROID_HOME`.

将 `JAVA_HOME` 指向 JDK 17，在不入库的 `local.properties` 中配置 `sdk.dir`，或设置 `ANDROID_HOME`。Android 应用构建不需要 Node.js。

## Debug build / 调试版

```sh
./gradlew :app:assembleAppDebug
```

On Windows / Windows 下：

```powershell
.\gradlew.bat :app:assembleAppDebug
```

The APK is under `app/build/outputs/apk/app/debug/`. Its application ID is `io.github.lorcinv.omireader.debug`; it installs separately from the release app.

调试包位于上述目录，应用标识带 `.debug` 后缀，与正式版分别安装。

## Release signing / 正式版签名

The PowerShell helper accepts a JDK path and creates an independent signing key on the first release build:

```powershell
.\tools\build.ps1 -JavaHome 'C:\path\to\jdk-17' -Task ':app:assembleAppRelease'
```

The APK is under `app/build/outputs/apk/app/release/`. Keep `.android-signing/` private and backed up; reuse the same key for future updates. A build signed with your own key cannot replace the published APK signed by the project's release key.

首次正式构建生成自己的签名。请备份 `.android-signing/`，后续更新沿用该密钥；自己的签名不能直接覆盖项目公开安装包的签名。签名文件、密码与本地开发缓存均不入库。

Without the helper, Gradle accepts `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` as project properties. Supply them through your local environment or private Gradle configuration.

## Android checks / Android 检查

Build and install the matching debug app and instrumentation APK on an emulator or device:

```sh
./gradlew :app:assembleAppDebug :app:assembleAppDebugAndroidTest
```

```powershell
.\tools\test-android.ps1 -Adb 'C:\path\to\adb.exe' -Serial 'emulator-5554'
```

The six Omi test groups cover navigation/themes, local formats, library/reader interactions, source rules, in-book link navigation, and rich documents. Version 1.1.3 passed 36 emulator tests, including native-page taps on EPUB, KF6 and KF8 contents links and anchors. This is not a physical-device test result.

六组检查涵盖导航与主题、本地格式、书架与阅读交互、书源规则、书内链接跳转和富文本文档。1.1.3 的 36 项模拟器检查包括实际点击 EPUB、KF6 和 KF8 原生阅读页中的目录链接与锚点。

## Icons / 图标

```sh
npm install
npm run icons
```

`resources/omireader-icon.svg` is the editable launcher icon. `tools/build-icons.cjs` generates density-specific PNGs, the adaptive foreground and a monochrome variant with two eye cutouts.

The generated Omi interface vectors are already included. `tools/sync-comic-icons.py` is an optional maintainer tool that reads a sibling OmiComic checkout to refresh shared shapes; it is not a build prerequisite.

应用图标以 SVG 为源，保留桌面遮罩安全区。Omi 界面矢量图标已随源码提供，常规构建不需要另行下载 OmiComic。

## Upstream / 上游

The reader and source-rule engine are based on Legado commit `da17bb2bed44f30b12a524c2457e32a20b16fa41`. The original introduction and changelog are preserved in `UPSTREAM_README.md` and `UPSTREAM_CHANGELOG.md` in this directory. This repository retains GPL-3.0 and the original third-party notices.
