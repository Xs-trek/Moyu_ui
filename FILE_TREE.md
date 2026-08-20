# Moyu UI 交付文件树

以下为可交付源码、资源、文档与构建产物；不列出 `node_modules/`、`.git/`、Gradle `build/`/`.gradle/`、本机测试截图与既有后端二进制。

```text
moyu-ui/
├─ app-arm64-release.apk
├─ index.html
├─ preview.html
├─ phone.html
├─ package.json
├─ package-lock.json
├─ vite.config.js
├─ LICENSE
├─ README.md
├─ README_UI.md
├─ UI_ACCEPTANCE.md
├─ V0.0.3_REQUIREMENTS.md
├─ ANDROID_INTEGRATION.md
├─ LOCAL_INTEGRATION.md
├─ THIRD_PARTY_LICENSES.md
├─ THIRD_PARTY_NOTICES.md
├─ FILE_TREE.md
├─ moyu_icon.png
├─ moyu_notify.png
├─ contract/
│  └─ moyu-ui-contract.d.ts
├─ public/
│  └─ assets/brands/
│     ├─ anthropic.svg
│     ├─ moyu.svg
│     └─ openai.svg
├─ src/
│  ├─ accessibility.js
│  ├─ approval-input.js
│  ├─ bridge.js
│  ├─ conversation-outline.js
│  ├─ main.js
│  ├─ mock-data.js
│  ├─ mock-host.js
│  ├─ model-options.js
│  ├─ phone-preview.js
│  ├─ render-gate.js
│  ├─ render.js
│  ├─ router.js
│  ├─ scroll-anchor.js
│  ├─ state.js
│  ├─ turn-performance.js
│  ├─ assets/
│  │  ├─ icons/README.md
│  │  └─ logo/README.md
│  └─ styles/
│     ├─ tokens.css
│     ├─ base.css
│     ├─ layout.css
│     ├─ components.css
│     ├─ pages.css
│     ├─ themes.css
│     └─ phone-preview.css
├─ scripts/
│  ├─ check-ui.mjs
│  ├─ package-test-release.ps1
│  ├─ verify-native-libs.ps1
│  ├─ generate-brand-assets.ps1
│  ├─ serve-dist.py
│  ├─ run-local-integration.ps1
│  ├─ capture-local-integration.ps1
│  └─ dump-webview.mjs
├─ dist/
│  ├─ index.html
│  ├─ preview.html
│  ├─ phone.html
│  └─ assets/
│     ├─ brands/{anthropic.svg,moyu.svg,openai.svg}
│     ├─ main-DPZ4TfJG.js
│     ├─ main-Czuz5Vdp.css
│     ├─ preview-DiXieeTt.js
│     ├─ phone-DNdXNNuf.js
│     └─ phone-BDCaU-hj.css
└─ android/
   ├─ build.gradle
   ├─ settings.gradle
   ├─ gradle.properties
   ├─ lint.xml
   ├─ gradlew
   ├─ gradlew.bat
   ├─ gradle/wrapper/
   │  ├─ gradle-wrapper.jar
   │  └─ gradle-wrapper.properties
   └─ app/
      ├─ build.gradle
      ├─ proguard-rules.pro
      └─ src/
         ├─ main/
         │  ├─ AndroidManifest.xml
         │  ├─ assets/
         │  │  ├─ licenses/
         │  │  │  ├─ Apache-2.0.txt
         │  │  │  ├─ EasyTier-LGPL-3.0.txt
         │  │  │  ├─ GPL-3.0.txt
         │  │  │  └─ THIRD_PARTY_NOTICES.txt
         │  │  └─ ui/
         │  │     ├─ index.html
         │  │     └─ assets/
         │  │        ├─ brands/{anthropic.svg,moyu.svg,openai.svg}
         │  │        ├─ main-DPZ4TfJG.js
         │  │        └─ main-Czuz5Vdp.css
         │  ├─ cpp/easytier_jni.c
         │  ├─ jniLibs/arm64-v8a/
         │  │  ├─ libeasytier_android_jni.so
         │  │  └─ libeasytier_ffi.so
         │  ├─ java/com/easytier/jni/EasyTierJNI.java
         │  ├─ java/com/moyu/remote/
         │  │  ├─ AppCoordinator.java
         │  │  ├─ ArtifactCache.java
         │  │  ├─ BackendClient.java
         │  │  ├─ ControlViewMapper.java
         │  │  ├─ EasyTierLinkInfo.java
         │  │  ├─ ExternalLinkPolicy.java
         │  │  ├─ LatestOnlySlot.java
         │  │  ├─ MainActivity.java
         │  │  ├─ MoyuApplication.java
         │  │  ├─ MoyuDatabase.java
         │  │  ├─ NativeHistoryIdentity.java
         │  │  ├─ NativeHistoryKeys.java
         │  │  ├─ NativeHistoryPaging.java
         │  │  ├─ NativeHistoryRetention.java
         │  │  ├─ OverlayService.java
         │  │  ├─ SecretStore.java
         │  │  ├─ ViewEmissionPolicy.java
         │  │  ├─ ViewEmissionThrottle.java
         │  │  └─ WebViewInteractionPolicy.java
         │  └─ res/
         │     ├─ drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
         │     │  ├─ ic_launcher_foreground.png
         │     │  └─ ic_notification.png
         │     ├─ mipmap-anydpi/ic_launcher.xml
         │     ├─ mipmap-anydpi-v26/ic_launcher.xml
         │     ├─ values/{colors.xml,strings.xml,styles.xml}
         │     ├─ values-night/styles.xml
         │     └─ xml/network_security_config.xml
         └─ test/java/com/moyu/remote/
            ├─ AppCoordinatorBackNavigationTest.java
            ├─ AppCoordinatorReadyGateTest.java
            ├─ AppCoordinatorStreamProjectionTest.java
            ├─ ApprovalDecisionTest.java
            ├─ ArtifactCacheTest.java
            ├─ BackendClientTest.java
            ├─ ControlViewMapperTest.java
            ├─ EasyTierLinkInfoTest.java
            ├─ ExternalLinkPolicyTest.java
            ├─ LatestOnlySlotTest.java
            ├─ MoyuDatabaseApprovalStateTest.java
            ├─ MoyuDatabaseMergeTest.java
            ├─ MoyuDatabaseMigrationTest.java
            ├─ MoyuDatabasePreviewTest.java
            ├─ NativeHistoryIdentityTest.java
            ├─ NativeHistoryKeysTest.java
            ├─ NativeHistoryPagingTest.java
            ├─ NativeHistoryRetentionTest.java
            ├─ ViewEmissionPolicyTest.java
            ├─ ViewEmissionThrottleTest.java
            └─ WebViewInteractionPolicyTest.java
```
