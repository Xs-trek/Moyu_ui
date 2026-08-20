# Third-Party Licenses

## WebView UI 运行时

构建后的 HTML/CSS/JavaScript 没有第三方前端运行时依赖，不加载远程字体、图片、图标、CSS 或 JavaScript。UI 使用系统字体、原创 Moyu 图形、通用 Unicode 状态符号，以及随包分发的 Anthropic/OpenAI 官方品牌 SVG 来标识 Claude Code 与 Codex CLI。品牌图形不是软件运行时依赖，不表示相关品牌对 Moyu 的认可或背书。

## Android 运行时

- **EasyTier 2.6.4** — GNU Lesser General Public License v3.0。应用把未修改的 `libeasytier_ffi.so` 作为独立、可替换的动态库打包；对应源码为 EasyTier `v2.6.4` / `8428a89d2dabc94c97d370ec607c6ca142473626`。APK 内附 `assets/licenses/EasyTier-LGPL-3.0.txt`。
- **OkHttp 3.14.9** — Apache License 2.0。
- **Okio**（OkHttp 的传递依赖）— Apache License 2.0。

完整归属、替换说明和对应源码位置见 `THIRD_PARTY_NOTICES.md`。Moyu 自身使用根目录 `LICENSE` 中的 Apache License 2.0。

## 构建与测试依赖

- **Vite 7**、**Rollup**、**esbuild** — MIT License，仅用于前端开发和构建。
- **Android Gradle Plugin / Gradle** — 仅用于 Android 构建，依各自随附许可证。
- **JUnit 4.13.2** — Eclipse Public License 1.0，仅用于测试。
- **Robolectric 4.10.3** 及其测试传递依赖 — MIT/Apache-2.0 等随附许可证，仅用于测试。

`package-lock.json` 与 Gradle 依赖声明固定实际版本；安装缓存中每个包的 `LICENSE`/`NOTICE` 是其完整许可证文本。

## 本地资产

`public/assets/brands/moyu.svg`、应用图标、通知图标、色块、状态标记和导航符号为本项目原创组合或通用字符。`public/assets/brands/anthropic.svg` 与 `public/assets/brands/openai.svg` 分别用于 Claude Code 与 Codex CLI 的兼容平台识别；相应名称和图形的权利归各自权利人所有。后续替换 `src/assets/icons/`、`src/assets/logo/` 或 `public/assets/brands/` 时，集成方必须同步更新本文件与 `THIRD_PARTY_NOTICES.md`。
