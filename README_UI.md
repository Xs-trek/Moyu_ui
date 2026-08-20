# moyu UI 交付说明

`moyu-ui` 是可直接构建并嵌入 Android WebView 的离线界面。运行时使用 Vanilla HTML、CSS 与 ES Modules，不包含 React、Vue、Tailwind 或其他前端框架。HTML 只渲染 Android Host 提供的 view model、收集用户输入并发送 intent；网络、认证、EasyTier、SQLite、Keystore、同步与后台生命周期全部由 Android 原生层负责。

当前固化版本为 `0.0.3`。Console 只保留活动会话摘要与入口；完整时间线和 Composer 只出现在完整会话页。会话顶部默认使用单行摘要，工作目录、Profile、请求模型、本轮实际模型与上下文信息按需展开。

Claude 会话只公开 `Plan`、`Auto`、`Accept Edits` 三种原生模式，并且只允许用户在回合空闲时主动切换。默认与 Auto 不可用时的降级状态均为 `Accept Edits`；降级不会自动重发失败输入。模型也在 Composer 上方手动切换，只改变后续轮次的原生 `--model` 参数。兼容端回报的实际模型（例如自定义 Base URL 后的模型名）显示在回答元数据中，不覆盖用户选择。

时间线把用户和助手正文作为主内容常显；思考过程、已完成工具、已处理审批、真实 system 记录和孤立用量默认折叠。审批与失败只保留结构化卡片，历史版本生成的 `[approval:*]` / `[error:*]` system 副本由 Android 同步清理。页面路由优先使用 WebView 支持的 same-document View Transition，旧 WebView 与低动画偏好自动回退到无依赖 CSS 切换。

## 项目结构

- `index.html`：正式 WebView 入口，不加载 Mock Host。
- `preview.html`：桌面预览入口，带 fixture、主题、revision gap 与 intent 日志面板。
- `phone.html`：常用手机/平板画幅预览壳，仅用于本地开发。
- `src/main.js`：启动、主题监听和交互渲染门控。
- `src/bridge.js`：唯一 Bridge、requestId、1 MiB 上限、revision 与 intent result。
- `src/state.js`：纯内存状态与安全 JSON Pointer patch。
- `src/render.js`：页面、组件、弹层和安全 DOM 渲染。
- `src/mock-data.js` / `src/mock-host.js`：覆盖离线、流式、工具、审批、错误、Diff、多节点等状态的内存 Mock。
- `src/styles/`：设计 token、基础样式、布局、组件、页面和主题。
- `contract/moyu-ui-contract.d.ts`：UI 与 Android glue 的完整版本化契约。
- `scripts/check-ui.mjs`：安全边界、入口隔离、fixtures、资源和 dist 检查。
- `dist/`：构建后的离线交付目录，可直接同步进 Android assets。
- `android/`：参考且可构建的 Android Host 集成。

## 开发、预览与构建

```powershell
npm ci
npm run dev
```

在开发服务器中打开 `/preview.html`。顶部开发者面板可切换全部 fixture、功能页面、`light` / `dark` / `system`，并模拟 revision gap。`/phone.html` 提供 360×800、390×844、412×915、430×932 与 768×1024 的一键画幅壳；也可用 `/phone.html?viewport=360&fixture=approval-pending&route=conversation&theme=dark` 直接分享特定预览状态。Mock Host 只保存在当前页面内存，刷新后清空；这些开发入口不会进入正式 APK。

生成交付产物并执行自动验收：

```powershell
npm run build
npm run check
npm run android:package-test
```

`android:package-test` 会先校验固定 SHA-256 的 EasyTier 原生库，随后重建并检查 UI，运行 Android 单测、lint 与 release 构建，最后用标准 Android 调试证书生成根目录 `app-arm64-release.apk`。该文件只用于测试交付；生产发布必须使用项目方自己的受控签名密钥。

Android 构建使用 JDK 11、Android SDK 34 与仓库中的 Gradle wrapper：

```powershell
cd android
.\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Android WebView 集成入口

Gradle 的 `syncMoyuUi` 会把 `dist/` 同步到 `android/app/src/main/assets/ui/`，并排除 `preview.html`、`phone.html` 及各自专用 chunk。参考 Host 从以下私有同源地址加载正式入口：

```text
https://appassets.androidplatform.net/assets/ui/index.html
```

WebView 禁用 DOM storage、数据库、文件访问、content 访问、混合内容与任意网络导航；资源请求只允许应用自身的 `/assets/ui/` 和受控缓存图片路径。

## Bridge 收发方式

UI 只调用一个 JavaScriptInterface 方法发送 JSON：

```js
window.MoyuHost.postMessage(JSON.stringify(intent));
```

Android 也只通过一个事件投递 `view.full`、`view.patch` 或 `intent.result`：

```js
window.dispatchEvent(new CustomEvent('moyu:view', { detail: envelope }));
```

Host 应在收到 `app.ready` 后发送首个 `view.full`。revision 必须单调递增；UI 发现 patch 跳号或 patch 非法时会发送 `view.reload`。所有 intent 都带 requestId，提交中的同一操作必须禁用重复触发。

`external.open` 不能由 HTML 直接导航。Android 只接受固定官方域名列表中的 HTTPS URL，并在打开系统浏览器前显示原生确认。

## 主题机制

三种偏好为 `system`、`light`、`dark`。UI 发送 `appearance.set`，但不在浏览器中持久化；Android 保存偏好并通过 `appearance.theme` / `appearance.resolvedTheme` 返回最终状态。system 模式监听 `prefers-color-scheme`，但 Host view model 始终是权威状态。

## 资源路径与 CSP

Vite 设置 `base: './'`，所有运行时资源必须随包提供并使用相对路径。禁止 CDN、远程字体、远程图标、远程图片和绝对资源路径。

正式入口与完整预览入口使用以下策略边界；手机画幅壳只额外允许同源 `frame-src 'self'` 以嵌入 `preview.html`：

```text
default-src 'self'; connect-src 'none'; img-src 'self' data:;
font-src 'self'; style-src 'self'; script-src 'self';
object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none';
```

动态 Host 文本只可使用 `textContent` 或等价安全 DOM API，禁止 `innerHTML`、`eval`、内联事件和动态脚本。

## HTML 不承担的职责

不要在 HTML 中实现或保存：后端 URL/端口、REST/WS、AI provider 调用、认证与 token、API key、OAuth token、EasyTier network secret、文件系统访问、SQLite/Room、Keystore、同步/重连、后台生命周期、网络延迟测量或离线发送 outbox。断线输入只保存草稿，恢复在线后仍须用户重新确认发送。

## 修改设计

- 颜色、间距、字体、边框、硬投影、圆角、动效与安全区：`src/styles/tokens.css`
- 通用组件：`src/styles/components.css`
- 手机/平板布局：`src/styles/layout.css`
- 页面样式：`src/styles/pages.css`
- 深色主题：`src/styles/themes.css`

替换图标或 logo 时，把自有或经项目方确认用于交付的文件放入 `src/assets/icons/`、`src/assets/logo/` 或 `public/assets/brands/`，只以相对路径引用；信息性 SVG 必须提供可访问名称，装饰性图形使用 `aria-hidden="true"`。Claude Code 与 Codex CLI 当前分别使用随包分发的 Anthropic/OpenAI 官方品牌图形，仅用于识别对应平台；来源和商标声明见 `THIRD_PARTY_NOTICES.md`。替换时必须同步更新声明。
