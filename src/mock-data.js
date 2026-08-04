const now = '2026-08-01T09:30:00.000Z';
const ago = (minutes) => new Date(new Date(now).getTime() - minutes * 60000).toISOString();

const nodeA = {
  nodeId: 'node-studio', displayName: '工作室 PC', relayNode: 'relay.demo.invalid:11010', configured: true, active: true,
  overlayState: 'online', backendState: 'online', syncState: 'current', relayLatencyMs: 42, relayLatencyReliable: true,
  lastConnectedAt: ago(4), secretState: { token: true, networkSecret: true }
};
const nodeB = {
  nodeId: 'node-home', displayName: '家中主机', relayNode: 'relay-backup.demo.invalid:11010', configured: true, active: false,
  overlayState: 'offline', backendState: 'offline', syncState: 'idle', relayLatencyMs: 91, relayLatencyReliable: false,
  lastConnectedAt: ago(180), secretState: { token: true, networkSecret: true }
};
const nodeMissing = {
  nodeId: 'node-new', displayName: '新笔记本', relayNode: '尚未设置', configured: false, active: false,
  overlayState: 'idle', backendState: 'unknown', syncState: 'idle', secretState: { token: false, networkSecret: false }
};

const baseSessions = [
  { localSessionId: 'session-claude', remoteSessionId: 'remote-a', nodeId: 'node-studio', kind: 'claude', title: '修复移动端登录流程', updatedAt: ago(2), profileId: 'claude-work', model: 'claude-sonnet', effort: 'high', state: 'running', unread: 2, lastSeq: 12, preview: '我已经定位到 token 刷新竞态…' },
  { localSessionId: 'session-codex', remoteSessionId: 'remote-b', nodeId: 'node-studio', kind: 'codex', title: '重构缓存模块', updatedAt: ago(35), profileId: 'codex-main', model: 'gpt-5.2-codex', effort: 'medium', state: 'completed', unread: 0, lastSeq: 38, preview: '全部测试已通过，缓存命中率保持不变。' },
  { localSessionId: 'session-local', nodeId: 'node-home', kind: 'claude', title: '离线需求草稿', updatedAt: ago(180), state: 'localOnly', unread: 0, lastSeq: 4, preview: '等待节点恢复在线后继续。' },
  { localSessionId: 'session-old', nodeId: 'node-studio', kind: 'codex', title: '诊断构建失败', updatedAt: ago(1440), profileId: 'native-codex', state: 'failed', unread: 1, lastSeq: 19, preview: '队列已满，任务没有自动重试。' }
];

const baseMessages = [
  { localSeq: 1, kind: 'message', role: 'system', text: '会话已从 Android 本地历史恢复。', createdAt: ago(18) },
  { localSeq: 2, kind: 'message', role: 'user', text: '请检查移动端登录时偶发的重复刷新，并保持现有 API 兼容。', createdAt: ago(17) },
  { localSeq: 3, kind: 'thinking', text: '我先梳理认证状态机和刷新锁的生命周期，再查看相关测试。', streaming: false, createdAt: ago(16) },
  { localSeq: 4, kind: 'tool', toolCallId: 'tool-1', tool: 'rg', input: { pattern: 'refreshToken', path: 'src/auth' }, output: 'src/auth/session.ts:48: refreshToken\nsrc/auth/guard.ts:91: refreshToken', state: 'done', createdAt: ago(15) },
  { localSeq: 5, kind: 'message', role: 'assistant', text: '问题来自两个页面恢复事件同时进入刷新分支。当前互斥锁只包裹请求，没有覆盖响应写回，因此较旧响应会覆盖新 token。', createdAt: ago(12) },
  { localSeq: 6, kind: 'tool', toolCallId: 'tool-2', tool: 'apply_patch', input: { file: 'src/auth/session.ts', change: '将请求与写回纳入同一版本门控' }, output: 'Done!', state: 'done', createdAt: ago(9) },
  { localSeq: 7, kind: 'usage', model: 'claude-sonnet', effort: 'high', usage: { inputTokens: 3821, outputTokens: 906, cacheReadTokens: 1400, cacheWriteTokens: 320 }, createdAt: ago(8) },
  { localSeq: 8, kind: 'message', role: 'assistant', text: '修复完成。我增加了单飞请求和响应版本检查，并补了页面同时恢复的回归测试。', createdAt: ago(5) }
];

const activeSession = {
  ...baseSessions[0], cwd: 'D:/projects/mobile-app', messages: baseMessages, effortLevels: ['low', 'medium', 'high', 'xhigh', 'max'], hasOlderLocalMessages: true, composerDraft: '', canSend: true, canInterrupt: true,
  transport: { phoneBackendRttMs: 78, backendCliQueueMs: 18, backendCliDispatchMs: 11, cliFirstEventMs: 624, relayLatencyMs: 42, observedAt: ago(1) },
  diff: { isGitRepo: true, summary: { staged: 1, unstaged: 1, untracked: 1 }, files: [
    { path: 'src/auth/session.ts', status: 'unstaged', patch: '@@ -48,6 +48,9 @@\n- return refresh()\n+ const version = ++refreshVersion\n+ const result = await refresh()\n+ if (version === refreshVersion) commit(result)' },
    { path: 'src/auth/session.test.ts', status: 'staged', patch: '@@ -20,0 +21,5 @@\n+ it("keeps the latest refresh result", async () => {\n+   await restoreTwoPages()\n+   expect(token()).toBe("latest")\n+ })' },
    { path: 'notes/repro.md', status: 'untracked', patch: '+ Two visibility events within one frame.' }
  ] }
};

const adapters = [
  { adapter: 'claude', displayName: 'Claude Code', available: true, capabilities: { profiles: true, models: true, effortLevels: ['low', 'medium', 'high', 'xhigh', 'max'], sandbox: false, approvalsReviewer: false, approvalChoices: ['allow', 'allow_session', 'deny'], description: '支持 env profile、思考流与会话内授权' }, supportedModels: ['claude-sonnet', 'claude-opus'] },
  { adapter: 'codex', displayName: 'Codex CLI', available: true, capabilities: { profiles: true, models: true, effortLevels: ['minimal', 'low', 'medium', 'high', 'xhigh'], sandbox: true, approvalsReviewer: true, approvalChoices: ['allow', 'deny', 'cancel'], description: '支持 CODEX_HOME、sandbox 与 approvals reviewer' }, supportedModels: ['gpt-5.2-codex', 'gpt-5.1-codex-mini'] }
];

export function baseView() {
  return {
    route: 'conversation', now, appearance: { theme: 'system', resolvedTheme: 'light' }, activeNodeId: 'node-studio', activeLocalSessionId: 'session-claude',
    pairDraft: { displayName: '', relayNode: '', pairString: '' },
    connection: { state: 'online', nodeId: 'node-studio', summary: '工作室 PC 已连接并同步', phoneBackendRttMs: 78, lastOnlineAt: ago(0) },
    nodes: [nodeA], sessions: baseSessions, activeSession,
    server: { version: 'v0.0.2', protocolVersion: 1, adapters, maxMessageBytes: 1048576, features: { diff: true, resume: true, eventGapSync: true } },
    accounts: { nodeId: 'node-studio', adapters: [
      { ...adapters[0], profiles: [
        { profileId: 'native-claude', displayName: 'Native default', nativeDefault: true, hasCredentials: true, active: false },
        { profileId: 'claude-work', displayName: '工作账号（env）', nativeDefault: false, hasCredentials: true, active: true },
        { profileId: 'claude-lab', displayName: '实验环境（env）', nativeDefault: false, hasCredentials: true, active: false }
      ] },
      { ...adapters[1], profiles: [
        { profileId: 'native-codex', displayName: 'Native default', nativeDefault: true, hasCredentials: true, active: false },
        { profileId: 'codex-main', displayName: '主 CODEX_HOME', nativeDefault: false, hasCredentials: true, active: true },
        { profileId: 'codex-review', displayName: '审查 CODEX_HOME', nativeDefault: false, hasCredentials: false, active: false }
      ] }
    ] },
    config: { defaultAdapter: 'claude', model: 'claude-sonnet', availableModels: ['claude-sonnet', 'claude-opus', 'gpt-5.2-codex'], approvalPolicy: 'ask', sandbox: 'workspace-write', approvalsReviewer: 'auto_review' },
    diagnostics: { net: { mode: 'TUN', selectedNode: 'SG · Relay', tunnel: 'active' }, transport: activeSession.transport, lastSyncAt: ago(1), backendVersion: 'v0.0.2', protocolVersion: 1, notes: ['耗时均为 Android glue 与后端提供的聚合观测。', 'Relay TCP 延迟不代表 Provider 网络延迟。'] },
    ui: { pendingRequestIds: [] }
  };
}

function clone(view) { return JSON.parse(JSON.stringify(view)); }
function sessionWith(messages, overrides = {}) { return { ...clone(activeSession), messages, ...overrides }; }
const pendingApproval = { approvalId: 'approval-42', kind: 'command', tool: 'shell', summary: '运行数据库迁移命令', input: { command: 'npm run migrate', cwd: 'D:/projects/mobile-app' }, choices: ['allow', 'allow_session', 'deny'], state: 'pending' };

export const fixtures = [
  ['no-node', '01 · 无节点', (v) => Object.assign(v, { route: 'console', nodes: [], sessions: [], activeSession: undefined, activeNodeId: undefined, activeLocalSessionId: undefined, connection: { state: 'offline', summary: '尚未配置节点' } })],
  ['offline-history', '02 · 离线本地历史', (v) => Object.assign(v, { nodes: [nodeB], activeNodeId: 'node-home', connection: { state: 'offline', nodeId: 'node-home', summary: '完全离线，本地历史仍可用', lastOnlineAt: ago(180) }, activeSession: sessionWith(baseMessages, { canSend: false, canInterrupt: false, state: 'localOnly' }) })],
  ['overlay-starting', '03 · Overlay 启动中', (v) => { v.connection = { state: 'overlayStarting', summary: '正在启动 EasyTier overlay' }; v.nodes[0].overlayState = 'starting'; }],
  ['backend-connecting', '04 · 后端连接中', (v) => { v.connection = { state: 'backendConnecting', summary: 'Overlay 已就绪，正在连接后端' }; }],
  ['syncing', '05 · 同步中', (v) => { v.connection = { state: 'syncing', summary: '正在合并本地历史与事件缺口' }; v.nodes[0].syncState = 'syncing'; }],
  ['online-empty', '06 · 在线无会话', (v) => Object.assign(v, { route: 'console', sessions: [], activeSession: undefined, activeLocalSessionId: undefined })],
  ['claude-session', '07 · Claude 活跃会话', () => {}],
  ['codex-session', '08 · Codex 活跃会话', (v) => { v.activeSession = sessionWith(baseMessages, { ...baseSessions[1], effortLevels: ['minimal', 'low', 'medium', 'high', 'xhigh'], cwd: 'D:/projects/cache-service', canInterrupt: false }); v.activeLocalSessionId = 'session-codex'; }],
  ['thinking-stream', '09 · Thinking 流', (v) => { v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'thinking', text: '正在分析多个可能的竞态路径…', streaming: true, createdAt: now }]); }],
  ['text-stream', '10 · 文本流', (v) => { v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'message', role: 'assistant', text: '我正在逐步验证修复，当前已经确认状态机不会在', createdAt: now }]); }],
  ['tool-running', '11 · 工具运行中', (v) => { v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'tool', toolCallId: 't9', tool: 'npm test', input: { suite: 'auth' }, output: 'RUN auth/session.test.ts\n ✓ prevents duplicated refresh\n … waiting', state: 'running', createdAt: now }]); }],
  ['long-output', '12 · 超长工具输出', (v) => { v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'tool', toolCallId: 't9', tool: 'test runner', output: Array.from({ length: 60 }, (_, i) => `${String(i + 1).padStart(2, '0')}  PASS auth case ${i + 1}`).join('\n'), state: 'done', createdAt: now }]); }],
  ['approval-pending', '13 · 等待审批', (v) => { v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'approval', approval: pendingApproval, createdAt: now }], { pendingApproval }); }],
  ['approval-submitting', '14 · 审批提交中', (v) => { const a = { ...pendingApproval, state: 'submitting' }; v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'approval', approval: a, createdAt: now }], { pendingApproval: a }); }],
  ['approval-expired', '15 · 审批已过期', (v) => { const a = { ...pendingApproval, state: 'expired' }; v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'approval', approval: a, createdAt: now }], { pendingApproval: a }); }],
  ['allow-session', '16 · allow_session 风险', (v) => { v.activeSession = sessionWith([...baseMessages, { localSeq: 9, kind: 'approval', approval: pendingApproval, createdAt: now }], { pendingApproval }); v.ui.previewModal = 'allow-session'; }],
  ['auth-error', '17 · 鉴权错误', (v) => { v.connection = { state: 'error', summary: '身份凭据已失效', error: { code: 'unauthorized', summary: 'Unauthorized', retryable: false, category: 'auth' } }; v.ui.globalBanner = { level: 'error', text: '身份凭据已失效，请在 Android 原生设置中处理。' }; }],
  ['rate-limit', '18 · 限流错误', (v) => { v.ui.globalBanner = { level: 'warning', text: '请求频率受限，请稍后手动重试。' }; v.activeSession.messages.push({ localSeq: 9, kind: 'error', error: { code: 'queue_full', summary: '后端队列已满', retryable: true, category: 'rate-limit' }, createdAt: now }); }],
  ['network-error', '19 · 网络错误', (v) => { v.connection = { state: 'error', summary: '连接已中断', error: { code: 'network_unreachable', summary: '无法连接后端', retryable: true, category: 'network' } }; }],
  ['event-gap', '20 · Event gap 恢复', (v) => { v.connection = { state: 'syncing', summary: '检测到事件缺口，保留本地旧消息并恢复同步' }; v.ui.globalBanner = { level: 'info', text: '正在恢复事件缺口；已有本地消息不会被删除。' }; }],
  ['diff-all', '21 · 三类 Diff', (v) => { v.ui.previewModal = 'diff'; }],
  ['not-git', '22 · 非 Git 目录', (v) => { v.activeSession.diff = { isGitRepo: false, files: [] }; v.ui.previewModal = 'diff'; }],
  ['multi-node', '23 · 多节点', (v) => { v.nodes = [nodeA, nodeB, nodeMissing]; v.route = 'nodes'; }],
  ['multi-claude', '24 · 多 Claude Profile', (v) => { v.route = 'accounts'; }],
  ['multi-codex', '25 · 多 Codex Profile', (v) => { v.route = 'accounts'; }],
  ['relay-unreliable', '26 · Relay 延迟不可靠', (v) => { v.nodes[0].relayLatencyReliable = false; v.diagnostics.transport.relayLatencyMs = 113; v.route = 'diagnostics'; }],
  ['metrics-empty', '27 · 耗时均不可测', (v) => { v.diagnostics.transport = {}; v.activeSession.transport = {}; v.route = 'diagnostics'; }],
  ['degraded', '28 · 连接降级', (v) => { v.connection = { state: 'degraded', summary: '可读取本地历史，实时事件可能延迟', phoneBackendRttMs: 820 }; v.ui.globalBanner = { level: 'warning', text: '连接质量较差；AI 输入不会在断线后自动重放。' }; }],
  ['danger-access', '29 · danger-full-access 确认', (v) => { v.route = 'settings'; v.ui.previewModal = 'danger'; }],
  ['reduced-motion', '30 · 低动画模式', (v) => { v.ui.globalBanner = { level: 'info', text: '低动画 fixture：系统 prefers-reduced-motion 时会禁用过渡与循环动画。' }; }]
];

export function makeFixture(id, theme = 'light') {
  const view = baseView();
  const fixture = fixtures.find(([key]) => key === id) || fixtures[6];
  fixture[2](view);
  view.appearance = { theme, resolvedTheme: theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : theme };
  return view;
}
