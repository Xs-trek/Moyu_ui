# moyu UI 验收清单

## 页面与辅助界面

- [x] Console：离线本地历史、平台/Profile/Model 元数据、完整时间线、composer、interrupt、Diff、文件目录。
- [x] Sessions：搜索，按节点/平台/状态筛选，unread，本地/远端状态，二次删除确认，空状态，加载更早。
- [x] Nodes：多节点、激活状态、relay、配置布尔状态、overlay/backend/sync、连接/断开/诊断/配对/原生配置、二次删除确认。
- [x] Accounts：Claude env 与 Codex CODEX_HOME Profile、native default、凭据布尔值、激活与冻结说明。
- [x] Settings：三主题、默认平台/Model/审批/Sandbox/Reviewer、两类高风险确认、版本信息。
- [x] Diagnostics：五类可测指标、不可测语义、连接/Overlay/Sync/版本/TUN/可靠性/限制说明。
- [x] Dialog：新建会话、Diff、文件浏览、配对、破坏性确认、allow_session、danger-full-access、离线发送。
- [x] 新建会话根据 capabilities 显示 Profile/Model，并明确 Sandbox/Reviewer 的继承值。
- [x] Diff 对 hunk、添加行、删除行提供独立视觉语义，不将 patch 作为 HTML 注入。
- [x] 会话列表明确区分仅本地与远端热会话，并在同步空列表时展示骨架屏。
- [x] 节点编辑通过带预填显示名/relay 的 `node.manualSetup.open` 交给原生层。
- [x] 状态组件：全局 banner、toast、skeleton、empty、网络/鉴权/限流错误、审批过期。

## Fixture 与主题矩阵

以下 30 组均需在 `light` 和 `dark` 下检查；`system` 额外验证系统切换：

1. 无节点
2. 离线本地历史
3. Overlay 启动中
4. 后端连接中
5. 同步中
6. 在线无会话
7. Claude 活跃会话
8. Codex 活跃会话
9. Thinking 流
10. 文本流
11. 工具运行中
12. 长工具输出
13. 等待审批
14. 审批提交中
15. 审批已过期
16. allow_session 风险确认
17. 鉴权错误
18. 限流错误
19. 网络错误
20. Event gap 恢复
21. staged/unstaged/untracked Diff
22. 非 Git 目录
23. 多节点
24. 多 Claude Profile
25. 多 Codex Profile
26. Relay 延迟不可靠
27. 所有耗时不可测
28. 连接降级
29. danger-full-access 确认
30. 低动画模式

| 尺寸 | 导航 | Console | 溢出 | 安全区 |
|---|---|---|---|---|
| 360×800 | 底部 | 单栏、固定 composer | 页面无横向溢出 | 顶部/底部 |
| 390×844 | 底部 | 单栏 | 页面无横向溢出 | 顶部/底部 |
| 412×915 | 底部 | 单栏 | 页面无横向溢出 | 顶部/底部 |
| 430×932 | 底部 | 单栏 | 页面无横向溢出 | 顶部/底部 |
| 768×1024 | 侧栏 | 时间线 + 动作双栏 | Diff/code 自身滚动 | 顶部/底部 |

## 离线与审批

- [x] Console 不被全屏连接页阻塞；旧消息保留。
- [x] 离线输入会 debounce 保存草稿，不形成发送 outbox，不自动重放。
- [x] 在线恢复后必须由用户再次点击发送。
- [x] Approval 只渲染 `choices`，断线/提交中不可重复提交。
- [x] `allow_session` 二次确认；过期状态明确；`approval_not_pending` 触发刷新。
- [x] 不乐观显示“已允许”。

## 无障碍

- [x] 语义化 main/nav/header/article/section 与表单 label。
- [x] 最小 44×44px 触控区、清晰 `focus-visible`、跳转链接。
- [x] Dialog focus trap，Escape 关闭非强制弹层，焦点返回。
- [x] approval/error/streaming 使用 live region。
- [x] 键盘可操作；Enter 发送，Shift+Enter 换行。
- [x] `prefers-reduced-motion` 与 `prefers-contrast`。
- [x] 状态均有文字，不只依赖颜色或图标。

## 安全

- [x] 严格 CSP 与 `connect-src 'none'`。
- [x] 无网络 API、浏览器持久化 API、动态代码执行或内联事件。
- [x] 动态内容使用 `textContent`/安全 DOM；tool input 安全 stringify。
- [x] 正式入口不加载 Mock Host；所有资源相对且离线。
- [x] UI 不接收或呈现密钥；账号只展示 `hasCredentials` 布尔状态。
- [x] 1 MiB intent 上限、requestId、revision 与安全 patch。

## Android glue 对接

- [x] 注入唯一 `window.MoyuHost.postMessage`。
- [x] 在 `app.ready` 后发送首个 `view.full`。
- [x] revision 严格单调；收到 `view.reload` 返回最新 `view.full`。
- [x] 所有合法 intent 返回对应 requestId 的 `intent.result`。
- [x] Android 持久化主题、草稿、会话和节点；HTML 不承担持久化。
- [x] 原生页面处理敏感手动配置；WebView 只发 `node.manualSetup.open`。
- [x] Android 把后台/网络/同步状态转换成契约中的 view model。
- [ ] Android 对 `external.open` 做 URL allowlist 与原生确认。

运行 `npm run build && npm run check` 后，自动扫描项必须全部通过。
