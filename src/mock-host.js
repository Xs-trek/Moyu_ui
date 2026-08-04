import { fixtures, makeFixture } from './mock-data.js';
import { setModal, update } from './state.js';

let revision = 0;
let fixtureId = 'claude-session';
let theme = 'light';
let view = makeFixture(fixtureId, theme);
const intentLog = [];

function emit(envelope) {
  window.dispatchEvent(new CustomEvent('moyu:view', { detail: envelope }));
}

function full(nextView = view) {
  view = nextView;
  revision += 1;
  emit({ version: 1, type: 'view.full', revision, view });
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
  if (view.ui.previewModal === 'diff') setModal({ type: 'diff' });
  if (view.ui.previewModal === 'danger') setModal({ type: 'confirm', title: '启用 danger-full-access？', message: '该模式允许 CLI 绕过工作区沙箱。', warning: '仅在完全信任当前任务时启用。', action: 'confirm-config', data: { key: 'sandbox', value: 'danger-full-access' } });
  if (view.ui.previewModal === 'allow-session') setModal({ type: 'allow-session', data: { localSessionId: view.activeLocalSessionId, approvalId: 'approval-42', decision: 'allow_session' } });
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
      view.activeLocalSessionId = selected.localSessionId;
      view.activeSession = { ...view.activeSession, ...selected, messages: view.activeSession?.messages || [], composerDraft: '', canSend: view.connection.state === 'online', canInterrupt: selected.state === 'running' };
      view.route = 'console'; full({ ...view }); result(intent.requestId);
    } else result(intent.requestId, false, { code: 'session_not_found', summary: 'Session not found', retryable: false, category: 'not-found' });
    return;
  }
  if (intent.type === 'session.create') {
    const id = `session-mock-${Date.now()}`;
    const summary = { localSessionId: id, nodeId: intent.payload.nodeId, kind: intent.payload.kind, title: intent.payload.title || '新的远程任务', updatedAt: new Date().toISOString(), state: 'idle', unread: 0, lastSeq: 0, preview: '新会话已在 Mock Host 中创建。', profileId: intent.payload.profileId || undefined, model: intent.payload.model || undefined };
    view.sessions = [summary, ...view.sessions];
    view.activeLocalSessionId = id; view.activeSession = { ...summary, cwd: intent.payload.cwd, messages: [], hasOlderLocalMessages: false, composerDraft: '', canSend: true, canInterrupt: false };
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'session.saveDraft') {
    if (view.activeSession && (!intent.payload.localSessionId || intent.payload.localSessionId === view.activeSession.localSessionId)) view.activeSession.composerDraft = intent.payload.text;
    result(intent.requestId); return;
  }
  if (intent.type === 'session.send') { simulateTurn(intent); return; }
  if (intent.type === 'session.interrupt') {
    if (view.activeSession) { view.activeSession.canInterrupt = false; view.activeSession.canSend = true; view.activeSession.state = 'idle'; view.activeSession.messages.push({ localSeq: view.activeSession.messages.length + 1, kind: 'message', role: 'system', text: '已请求中断当前运行。', createdAt: new Date().toISOString() }); }
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
    result(intent.requestId, true, { fileNodes: [
      { nodeId: `${base}/src`, name: 'src', path: `${base}/src`, kind: 'directory' },
      { nodeId: `${base}/tests`, name: 'tests', path: `${base}/tests`, kind: 'directory' },
      { nodeId: `${base}/package.json`, name: 'package.json', path: `${base}/package.json`, kind: 'file' }
    ] }); return;
  }
  if (intent.type === 'node.connect') {
    const node = view.nodes.find((n) => n.nodeId === intent.payload.nodeId);
    if (node) { node.backendState = 'online'; node.overlayState = 'online'; node.syncState = 'current'; node.active = true; view.activeNodeId = node.nodeId; view.connection = { state: 'online', nodeId: node.nodeId, summary: `${node.displayName} 已连接`, phoneBackendRttMs: 84 }; full({ ...view }); }
    result(intent.requestId); return;
  }
  if (intent.type === 'node.disconnect') { view.connection = { state: 'offline', summary: '已由用户断开连接' }; full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'node.delete') { view.nodes = view.nodes.filter((n) => n.nodeId !== intent.payload.nodeId); full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'node.pairDraft.save') { view.pairDraft = { ...intent.payload }; full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'session.deleteLocal') { view.sessions = view.sessions.filter((s) => s.localSessionId !== intent.payload.localSessionId); full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'accounts.activate') {
    const adapter = view.accounts.adapters.find((a) => a.adapter === intent.payload.adapter);
    adapter?.profiles.forEach((p) => { p.active = p.profileId === intent.payload.profileId; });
    full({ ...view }); result(intent.requestId); return;
  }
  if (intent.type === 'config.patch') { view.config = { ...view.config, ...intent.payload.patch }; full({ ...view }); result(intent.requestId); return; }
  if (intent.type === 'node.pair') {
    view.nodes.push({ nodeId: `node-${Date.now()}`, displayName: intent.payload.displayName, relayNode: intent.payload.relayNode, configured: true, active: false, overlayState: 'idle', backendState: 'unknown', syncState: 'idle', secretState: { token: true, networkSecret: true } });
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
  view.activeSession.composerDraft = ''; view.activeSession.canSend = false; view.activeSession.canInterrupt = true; view.activeSession.state = 'running';
  full({ ...view }); result(intent.requestId);
  window.setTimeout(() => patch([{ op: 'set', path: `/activeSession/messages/${messages.length - 1}/text`, value: '正在检查相关文件与状态边界…' }]), 650);
  window.setTimeout(() => {
    view.activeSession.messages[view.activeSession.messages.length - 1].streaming = false;
    view.activeSession.messages.push({ localSeq: seq + 3, kind: 'tool', toolCallId: `tool-${seq + 3}`, tool: 'mock.check', input: { scope: 'workspace' }, output: '检查中…', state: 'running', createdAt: new Date().toISOString() });
    full({ ...view });
  }, 1200);
  window.setTimeout(() => {
    const tool = view.activeSession.messages[view.activeSession.messages.length - 1]; tool.state = 'done'; tool.output = '检查完成：未发现新的边界问题。';
    view.activeSession.messages.push({ localSeq: seq + 4, kind: 'message', role: 'assistant', text: 'Mock 流式回合已完成。真实环境中所有内容均由 Android Host 投递。', createdAt: new Date().toISOString() });
    view.activeSession.canSend = true; view.activeSession.canInterrupt = false; view.activeSession.state = 'idle'; full({ ...view });
  }, 2100);
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
  select.addEventListener('change', () => { fixtureId = select.value; full(makeFixture(fixtureId, theme)); });
  const themeSelect = document.createElement('select'); themeSelect.setAttribute('aria-label', '预览主题');
  [['light', '日间'], ['dark', '夜间'], ['system', '跟随系统']].forEach(([id, label]) => { const option = document.createElement('option'); option.value = id; option.textContent = label; option.selected = id === theme; themeSelect.append(option); });
  themeSelect.addEventListener('change', () => { theme = themeSelect.value; full(makeFixture(fixtureId, theme)); });
  const gap = document.createElement('button'); gap.className = 'btn btn-small'; gap.type = 'button'; gap.textContent = '模拟 revision 跳号';
  gap.addEventListener('click', () => { revision += 2; emit({ version: 1, type: 'view.patch', revision, patch: [{ op: 'set', path: '/connection/summary', value: '这条 patch 会触发 view.reload' }] }); });
  const log = document.createElement('button'); log.className = 'btn btn-small'; log.type = 'button'; log.textContent = `Intents ${intentLog.length}`;
  log.addEventListener('click', () => setModal({ type: 'confirm', title: 'Intent 记录', message: intentLog.length ? intentLog.slice(0, 12).map((x) => `${x.at} · ${x.type}`).join('\n') : '尚无 Intent', confirmText: '关闭', action: 'close-modal' }));
  root.replaceChildren(select, themeSelect, gap, log);
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', renderPanel, { once: true });
else renderPanel();
