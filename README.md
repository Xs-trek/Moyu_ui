# moyu UI

moyu UI 是一个面向 Android WebView 的离线 Vanilla HTML/CSS/JavaScript 界面。它只负责呈现 Host 提供的 view model、收集输入并发送 UI intent；没有第三方运行时框架，也不直接连接后端。

## 项目结构

- `index.html`：Android WebView 正式入口，仅加载 UI，不注入 Mock Host。
- `preview.html`：桌面浏览器预览入口，包含 fixture 面板与内存 Mock Host。
- `src/main.js`：应用启动与系统主题监听。
- `src/bridge.js`：唯一 Host 桥接、revision 检查、1 MiB 上限与 intent result 处理。
- `src/state.js`：内存状态与安全 JSON Pointer patch。
- `src/render.js`：安全 DOM 渲染、页面和交互。
- `src/mock-data.js` / `src/mock-host.js`：30 组预览状态与模拟流式行为。
- `src/styles/`：token、基础样式、布局、组件、页面和主题。
- `contract/moyu-ui-contract.d.ts`：Android glue 与 UI 的完整数据契约。
- `scripts/check-ui.mjs`：安全边界、入口隔离、资源与构建产物检查。
- `dist/`：可直接复制到 Android assets 的构建产物。

## 开发和构建

```bash
npm install
npm run dev
npm run build
npm run check
```

开发服务器中打开 `/preview.html` 可使用 Mock Host；`/index.html` 会显示等待 Android Host 的骨架状态，这是正式入口的预期行为。

## Android WebView 集成

将 `dist/` 整体复制到应用 assets，并以 `dist/index.html`（或复制后的对应相对位置）为入口。WebView 需要允许 JavaScript 和本地相对资源，禁止给页面注入第二套接口。

UI 只通过以下对象发送 JSON 字符串：

```js
window.MoyuHost.postMessage(JSON.stringify(intent));
```

Android 只通过以下事件投递完整状态、patch 或 intent 结果：

```js
window.dispatchEvent(new CustomEvent('moyu:view', { detail: envelope }));
```

Host 必须保证 revision 单调递增。UI 发现 patch 跳号会发送 `view.reload`；`view.full` 可随时替换全部状态。建议 Android 在 WebView 加载完成后等待 `app.ready` 再投递首个 `view.full`。

## Mock Host

`preview.html` 顶部可切换 30 个 fixture、日间/夜间/跟随系统主题，也可主动制造 revision gap。Mock Host 会记录 intent，模拟 pending/success/error、text/thinking/tool 流、审批状态、连接和同步变化。状态仅存在于当前页面内存，刷新即清空。

## 主题与设计修改

主题偏好不在 HTML 中持久化。UI 发送 `appearance.set`，最终以 Host view model 的 `appearance` 为准；system 模式同时监听系统配色变化。

- 颜色、间距、字体、边框、硬投影：修改 `src/styles/tokens.css`。
- 组件视觉：修改 `src/styles/components.css`。
- 移动/平板布局：修改 `src/styles/layout.css`。
- 页面专属样式：修改 `src/styles/pages.css`。
- 深色覆盖：修改 `src/styles/themes.css`。

当前状态图标使用无外部依赖的文字/CSS 形状。若替换为图标或 logo，把自有或允许再分发的本地文件放入 `src/assets/icons/` 或 `src/assets/logo/`，通过相对路径引用，并为信息性图标提供可访问名称。

新建会话弹层依据 `ServerView.adapters[].capabilities` 决定是否显示 Profile、Model、Sandbox 和 Reviewer。Profile 来自对应账号状态；Model 有后端候选列表时使用选择器，否则允许输入 CLI 支持的可选 model，留空即继承 CLI 默认值。Sandbox/Reviewer 只展示本次继承的安全设置，不扩展或绕过 `CreateSessionDraft` 契约。Diff 继续消费 Host 提供的纯文本 patch，仅在安全 DOM 中拆分成 hunk、添加行和删除行进行着色。

## 安全与资源约束

两个入口都设置 CSP，关闭网络连接、对象、frame、表单提交和远程资源。所有脚本、样式、字体、图标与图片必须随包提供并使用相对路径。动态 Host 文本只通过 `textContent` 或安全 DOM API 渲染。

HTML 不负责后端网络、认证、EasyTier、Room/SQLite、Keystore、同步、重连、延迟测量、后台生命周期或敏感配置。不得在 UI 中保存 token、API key、OAuth token、network secret，不得知道真实后端 URL/端口，不得实现自动离线 outbox，不得直接调用 AI 服务。
