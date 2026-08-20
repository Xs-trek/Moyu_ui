import { fixtures, makeFixture } from './mock-data.js';
import { setModal, update } from './state.js';

let revision = 0;
const previewParams = new URLSearchParams(window.location.search);
const requestedFixture = previewParams.get('fixture');
const requestedTheme = previewParams.get('theme');
const requestedRoute = previewParams.get('route');
let fixtureId = fixtures.some(([id]) => id === requestedFixture) ? requestedFixture : 'claude-session';
let theme = ['light', 'dark', 'system'].includes(requestedTheme) ? requestedTheme : 'light';
let view = makeFixture(fixtureId, theme);
if (['console', 'conversation', 'sessions', 'nodes', 'accounts', 'settings', 'diagnostics'].includes(requestedRoute)) view.route = requestedRoute;
const intentLog = [];
const sessionDetails = new Map();

function clone(value) { return JSON.parse(JSON.stringify(value)); }

function rememberActiveSession() {
  if (view.activeSession?.localSessionId) sessionDetails.set(view.activeSession.localSessionId, clone(view.activeSession));
}

function emit(envelope) {
  window.dispatchEvent(new CustomEvent('moyu:view', { detail: envelope }));
}

function full(nextView = view) {
  view = nextView;
  revision += 1;
  emit({ version: 1, type: 'view.full', revision, view });
  renderPanel();
  window.setTimeout(applyFixtureModal, 20);
}

function patch(operations) {
  revision += 1;
  emit({ version: 1, type: 'view.patch', revision, patch: operations });
}

function result(requestId, ok = true, data) {
  window.setTimeout(() => emit(ok ? { version: 1, type: 'intent.result', requestId, ok: true, data } : { version: 1, type: 'intent.result', requestId, ok: false, error: data }), 380);
}

function applyFixtureModal() {
  const previewModal = view.ui?.previewModal;
  if (!previewModal) return;
  delete view.ui.previewModal;
  if (previewModal === 'diff') setModal({ type: 'diff' });
  if (previewModal === 'danger') setModal({ type: 'confirm', title: '启用 danger-full-access？', message: '该模式允许 CLI 绕过工作区沙箱。', warning: '仅在完全信任当前任务时启用。', action: 'confirm-config', data: { key: 'sandbox', value: 'danger-full-access' } });
  if (previewModal === 'allow-session') setModal({ type: 'allow-session', data: { localSessionId: view.activeLocalSessionId, approvalId: 'approval-42', decision: 'allow_session' } });
}

function syncActiveSessionControls() {
  const session = view.activeSession;
  if (!session) return;
  const connected = view.connection.state === 'online' && view.connection.nodeId === session.nodeId;
  session.canSend = connected && session.state !== 'running';
  session.canInterrupt = connected && session.state === 'running';
}

function handleIntent(intent) {
  intentLog.unshift({ at: new Date().toLocaleTimeString('zh-CN', { hour12: false }), type: intent.type, requestId: intent.requestId, payload: intent.payload });
  if (intentLog.length > 40) intentLog.length = 40;
  renderPanel();
  if (intent.type === 'app.ready' || intent.type === 'view.reload') { full(view); result(intent.requestId); return; }
  if (intent.type === 'nav.open') { view.route = intent.payload.route; full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'appearance.set') {
    theme = intent.payload.theme;
    const resolvedTheme = theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : theme;
    view.appearance = { theme, resolvedTheme };
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'session.open') {
    const selected = view.sessions.find((s) => s.localSessionId === intent.payload.localSessionId);
    if (selected) {
      rememberActiveSession();
      const saved = sessionDetails.get(selected.localSessionId);
      const messages = saved?.messages || [
        { localSeq: 1, kind: 'message', role: 'system', text: '此会话从 Android 本地摘要恢复。', createdAt: selected.updatedAt },
        { localSeq: 2, kind: 'message', role: 'assistant', text: selected.preview || '暂无更早的本地内容。', createdAt: selected.updatedAt }
      ];
      view.activeLocalSessionId = selected.localSessionId;
      view.activeSession = { ...saved, ...selected, messages, cwd: saved?.cwd || '', hasOlderLocalMessages: Boolean(saved?.hasOlderLocalMessages), composerDraft: saved?.composerDraft || '', composerAttachments: saved?.composerAttachments || [], canSend: view.connection.state === 'online' && view.connection.nodeId === selected.nodeId && selected.state !== 'running', canInterrupt: selected.state === 'running' };
      view.route = 'conversation'; full({ ...view }); result(intent.requestId);
    } else result(intent.requestId, false, { code: 'session_not_found', summary: 'Session not found', retryable: false, category: 'not-found' });
    return;
  }
  if (intent.type === 'session.create') {
    const id = `session-mock-${Date.now()}`;
    const summary = { localSessionId: id, nodeId: intent.payload.nodeId, kind: intent.payload.kind, title: intent.payload.title || '新的远程任务', updatedAt: new Date().toISOString(), state: 'idle', unread: 0, lastSeq: 0, preview: '新会话已在 Mock Host 中创建。', profileId: intent.payload.profileId || undefined, model: intent.payload.model || undefined, permissionMode: intent.payload.kind === 'claude' ? (intent.payload.permissionMode || 'acceptEdits') : undefined };
    view.sessions = [summary, ...view.sessions];
    view.activeLocalSessionId = id; view.activeSession = { ...summary, cwd: intent.payload.cwd, messages: [], effortLevels: intent.payload.kind === 'claude' ? ['low', 'medium', 'high', 'xhigh', 'max'] : [], permissionModes: intent.payload.kind === 'claude' ? ['plan', 'auto', 'acceptEdits'] : [], hasOlderLocalMessages: false, composerDraft: '', composerAttachments: [], canSend: false, canInterrupt: false };
    syncActiveSessionControls();
    view.route = 'conversation';
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'session.saveDraft') {
    if (view.activeSession && (!intent.payload.localSessionId || intent.payload.localSessionId === view.activeSession.localSessionId)) view.activeSession.composerDraft = intent.payload.text;
    result(intent.requestId); return;
  }
  if (intent.type === 'session.send') { simulateTurn(intent); return; }
  if (intent.type === 'session.effort.set' || intent.type === 'session.model.set' || intent.type === 'session.permissionMode.set') {
    if (view.activeSession?.state === 'running') { result(intent.requestId, false, { code: 'session_busy', summary: '当前回合运行中', retryable: false }); return; }
    if (intent.type === 'session.effort.set') view.activeSession.effort = intent.payload.effort || undefined;
    if (intent.type === 'session.model.set') view.activeSession.model = intent.payload.model || undefined;
    if (intent.type === 'session.permissionMode.set') view.activeSession.permissionMode = intent.payload.permissionMode;
    const summary = view.sessions.find((item) => item.localSessionId === view.activeSession.localSessionId);
    if (summary) Object.assign(summary, { effort: view.activeSession.effort, model: view.activeSession.model, permissionMode: view.activeSession.permissionMode });
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'attachment.pick') { result(intent.requestId); return; }
  if (intent.type === 'attachment.remove') {
    if (view.activeSession) view.activeSession.composerAttachments = (view.activeSession.composerAttachments || []).filter((item) => item.artifactId !== intent.payload.artifactId);
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'session.interrupt') {
    if (view.activeSession) { view.activeSession.state = 'idle'; view.activeSession.messages.push({ localSeq: view.activeSession.messages.length + 1, kind: 'message', role: 'system', text: '已请求中断当前运行。', createdAt: new Date().toISOString() }); syncActiveSessionControls(); }
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'approval.decide') {
    const approval = view.activeSession?.pendingApproval;
    if (!approval || approval.state !== 'pending') { result(intent.requestId, false, { code: 'approval_not_pending', summary: 'Approval is no longer pending', retryable: false }); return; }
    approval.state = 'submitting'; full({ ...view });
    window.setTimeout(() => { approval.state = intent.payload.decision === 'deny' ? 'denied' : 'allowed'; view.activeSession.pendingApproval = undefined; full({ ...view }); result(intent.requestId); }, 700);
    return;
  }
  if (intent.type === 'fs.list') {
    const base = intent.payload.path === '/' ? '/workspace' : intent.payload.path;
    const fileNodes = String(base).endsWith('/empty') ? [] : [
      { nodeId: `${base}/src`, name: 'src', path: `${base}/src`, kind: 'directory' },
      { nodeId: `${base}/tests`, name: 'tests', path: `${base}/tests`, kind: 'directory' },
      { nodeId: `${base}/empty`, name: 'empty', path: `${base}/empty`, kind: 'directory' },
      { nodeId: `${base}/package.json`, name: 'package.json', path: `${base}/package.json`, kind: 'file' }
    ];
    result(intent.requestId, true, { fileNodes }); return;
  }
  if (intent.type === 'session.loadOlder') {
    if (view.activeSession?.localSessionId === intent.payload.localSessionId) {
      const first = Math.min(0, ...view.activeSession.messages.map((item) => item.localSeq || 0));
      view.activeSession.messages.unshift({ localSeq: first - 1, kind: 'message', role: 'system', text: '这是 Mock Host 补载的更早本地记录。', createdAt: new Date(Date.now() - 86400000).toISOString() });
      view.activeSession.hasOlderLocalMessages = false;
      full({ ...view });
    }
    result(intent.requestId); return;
  }
  if (intent.type === 'diff.open') {
    if (view.activeSession && !view.activeSession.diff) view.activeSession.diff = { isGitRepo: true, summary: { staged: 0, unstaged: 0, untracked: 0 }, files: [] };
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'node.connect') {
    const node = view.nodes.find((n) => n.nodeId === intent.payload.nodeId);
    if (node) { view.nodes.forEach((item) => { item.active = false; }); node.backendState = 'online'; node.overlayState = 'online'; node.syncState = 'current'; node.peerConnected = true; node.linkMode = 'p2p'; node.linkObservedAt = new Date().toISOString(); node.active = true; view.activeNodeId = node.nodeId; view.connection = { state: 'online', nodeId: node.nodeId, summary: `${node.displayName} 已连接`, phoneBackendRttMs: 84 }; syncActiveSessionControls(); full({ ...view }); }
    result(intent.requestId); return;
  }
  if (intent.type === 'node.disconnect') {
    const node = view.nodes.find((item) => item.nodeId === intent.payload.nodeId);
    if (!node || view.activeNodeId !== node.nodeId || view.connection.nodeId !== node.nodeId) {
      result(intent.requestId, false, { code: 'wrong_node', summary: 'Requested node is not the active connection', retryable: false }); return;
    }
    node.backendState = 'offline'; node.overlayState = 'offline'; node.syncState = 'idle'; node.peerConnected = false; node.linkMode = 'unknown';
    view.connection = { state: 'offline', nodeId: intent.payload.nodeId, summary: '已由用户断开连接' }; syncActiveSessionControls(); full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'node.delete') {
    view.nodes = view.nodes.filter((n) => n.nodeId !== intent.payload.nodeId);
    if (view.activeNodeId === intent.payload.nodeId) { view.activeNodeId = undefined; view.connection = { state: 'offline', summary: '当前节点已从本地删除' }; }
    syncActiveSessionControls();
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'node.diagnose') {
    const node = view.nodes.find((item) => item.nodeId === intent.payload.nodeId);
    if (node) { node.relayLatencyMs = Math.max(1, Number(node.relayLatencyMs || 40)); node.linkObservedAt = new Date().toISOString(); }
    view.diagnostics.lastSyncAt = new Date().toISOString(); full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'node.pairDraft.save') { view.pairDraft = { ...intent.payload }; full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'session.deleteLocal') {
    view.sessions = view.sessions.filter((s) => s.localSessionId !== intent.payload.localSessionId);
    sessionDetails.delete(intent.payload.localSessionId);
    if (view.activeLocalSessionId === intent.payload.localSessionId) { view.activeLocalSessionId = undefined; view.activeSession = undefined; view.route = 'sessions'; }
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'accounts.activate') {
    const adapter = view.accounts.adapters.find((a) => a.adapter === intent.payload.adapter);
    adapter?.profiles.forEach((p) => { p.active = p.profileId === intent.payload.profileId; });
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'config.patch') {
    view.config = { ...view.config, ...intent.payload.patch };
    const caps = view.server?.adapters?.find((item) => item.adapter === view.config.defaultAdapter)?.capabilities || {};
    view.config.modelSelection = caps.modelSelection || 'none';
    view.config.sandboxModes = [...(caps.sandboxModes || [])];
    view.config.reviewers = [...(caps.reviewers || [])];
    view.config.approvalPolicies = [...(caps.approvalPolicies || [])];
    if (view.config.sandboxModes.length) {
      if (!view.config.sandboxModes.includes(view.config.sandbox)) view.config.sandbox = view.config.sandboxModes.includes('workspace-write') ? 'workspace-write' : view.config.sandboxModes[0];
    } else delete view.config.sandbox;
    if (view.config.reviewers.length) {
      if (!view.config.reviewers.includes(view.config.approvalsReviewer)) view.config.approvalsReviewer = view.config.reviewers[0];
    } else delete view.config.approvalsReviewer;
    if (!view.config.approvalPolicies.includes(view.config.approvalPolicy)) view.config.approvalPolicy = view.config.approvalPolicies[0];
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'node.pair') {
    view.nodes.push({ nodeId: `node-${Date.now()}`, displayName: intent.payload.displayName, relayNode: intent.payload.relayNode, configured: true, active: false, overlayState: 'idle', backendState: 'unknown', syncState: 'idle', peerConnected: false, linkMode: 'unknown', secretState: { token: true, networkSecret: true } });
    view.pairDraft = { displayName: '', relayNode: '', pairString: '' };
    full({ ...view }); result(intent.requestId); return;
  }
  result(intent.requestId);
}

function simulateTurn(intent) {
  if (!view.activeSession) { result(intent.requestId, false, { code: 'session_not_found', summary: 'Session missing', retryable: false }); return; }
  const messages = view.activeSession.messages;
  const seq = Math.max(0, ...messages.map((m) => m.localSeq));
  messages.push({ localSeq: seq + 1, kind: 'message', role: 'user', text: intent.payload.text, createdAt: new Date().toISOString() });
  messages.push({ localSeq: seq + 2, kind: 'thinking', text: '正在从 Mock Host 生成流式状态…', streaming: true, createdAt: new Date().toISOString() });
  view.activeSession.composerDraft = ''; view.activeSession.composerAttachments = []; view.activeSession.canSend = false; view.activeSession.canInterrupt = true; view.activeSession.state = 'running';
  full({ ...view }); result(intent.requestId);
  window.setTimeout(() => {
    const thinking = view.activeSession?.messages.find((item) => item.localSeq === seq + 2);
    if (thinking) thinking.text = '正在检查相关文件与状态边界…';
    patch([{ op: 'set', path: `/activeSession/messages/${messages.length - 1}/text`, value: '正在检查相关文件与状态边界…' }]);
  }, 650);
  window.setTimeout(() => {
    view.activeSession.messages[view.activeSession.messages.length - 1].streaming = false;
    view.activeSession.messages.push({ localSeq: seq + 3, kind: 'tool', toolCallId: `tool-${seq + 3}`, tool: 'mock.check', input: { scope: 'workspace' }, output: '检查中…', state: 'running', createdAt: new Date().toISOString() });
    full({ ...view });
  }, 1200);
  window.setTimeout(() => {
    const tool = view.activeSession.messages[view.activeSession.messages.length - 1]; tool.state = 'done'; tool.output = '检查完成：未发现新的边界问题。';
    view.activeSession.messages.push({ localSeq: seq + 4, kind: 'message', role: 'assistant', text: 'Mock 流式回复正在生成', streaming: true, createdAt: new Date().toISOString() });
    full({ ...view });
  }, 2100);
  window.setTimeout(() => {
    const answerIndex = view.activeSession.messages.findIndex((item) => item.localSeq === seq + 4);
    if (answerIndex < 0) return;
    const text = 'Mock 流式回复正在生成；当前已完成安全状态检查。';
    view.activeSession.messages[answerIndex].text = text;
    patch([{ op: 'set', path: `/activeSession/messages/${answerIndex}/text`, value: text }]);
  }, 2600);
  window.setTimeout(() => {
    const answer = view.activeSession.messages.find((item) => item.localSeq === seq + 4);
    if (answer) { answer.text = 'Mock 流式回合已完成。真实环境中所有内容均由 Android Host 投递。'; answer.streaming = false; }
    view.activeSession.messages.push({ localSeq: seq + 5, kind: 'usage', usage: { inputTokens: 420, outputTokens: 96, cacheReadTokens: 120, cacheWriteTokens: 0 }, costUsd: 0.0034, createdAt: new Date().toISOString() });
    view.activeSession.state = 'idle'; syncActiveSessionControls(); full({ ...view });
  }, 3150);
}

window.MoyuHost = {
  postMessage(message) {
    let intent;
    try { intent = JSON.parse(message); }
    catch { return; }
    if (intent?.version === 1 && typeof intent.requestId === 'string') handleIntent(intent);
  }
};

function renderPanel() {
  const root = document.querySelector('#fixture-panel');
  if (!root) return;
  root.className = 'fixture-panel';
  const select = document.createElement('select');
  select.setAttribute('aria-label', '选择 fixture');
  fixtures.forEach(([id, label]) => { const option = document.createElement('option'); option.value = id; option.textContent = label; option.selected = id === fixtureId; select.append(option); });
  select.addEventListener('change', () => { fixtureId = select.value; sessionDetails.clear(); setModal(null); full(makeFixture(fixtureId, theme)); });
  const routeSelect = document.createElement('select'); routeSelect.setAttribute('aria-label', '选择页面');
  [['console', '控制台'], ['conversation', '完整会话'], ['sessions', '会话'], ['nodes', '节点'], ['accounts', '账号'], ['settings', '设置'], ['diagnostics', '诊断']].forEach(([id, label]) => { const option = document.createElement('option'); option.value = id; option.textContent = label; option.selected = view.route === id; routeSelect.append(option); });
  routeSelect.addEventListener('change', () => { setModal(null); view.route = routeSelect.value; full({ ...view }); });
  const themeSelect = document.createElement('select'); themeSelect.setAttribute('aria-label', '预览主题');
  [['light', '日间'], ['dark', '夜间'], ['system', '跟随系统']].forEach(([id, label]) => { const option = document.createElement('option'); option.value = id; option.textContent = label; option.selected = id === theme; themeSelect.append(option); });
  themeSelect.addEventListener('change', () => { theme = themeSelect.value; sessionDetails.clear(); setModal(null); full(makeFixture(fixtureId, theme)); });
  const gap = document.createElement('button'); gap.className = 'btn btn-small'; gap.type = 'button'; gap.textContent = '模拟 revision 跳号';
  gap.addEventListener('click', () => { revision += 2; emit({ version: 1, type: 'view.patch', revision, patch: [{ op: 'set', path: '/connection/summary', value: '这条 patch 会触发 view.reload' }] }); });
  const log = document.createElement('button'); log.className = 'btn btn-small'; log.type = 'button'; log.textContent = `Intents ${intentLog.length}`;
  log.addEventListener('click', () => setModal({ type: 'confirm', title: 'Intent 记录', message: intentLog.length ? intentLog.slice(0, 12).map((x) => `${x.at} · ${x.type}`).join('\n') : '尚无 Intent', confirmText: '关闭', action: 'close-modal' }));
  root.replaceChildren(select, routeSelect, themeSelect, gap, log);
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', renderPanel, { once: true });
else renderPanel();
