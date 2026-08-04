import { activateDialog, safeStringify } from './accessibility.js';
import { sendIntent } from './bridge.js';
import { openRoute } from './router.js';
import { cachePairDraft, getState, setFilter, setModal, setNotice, toggleExpanded, update } from './state.js';

const labels = {
  console: ['控制台', '▣'], sessions: ['会话', '☷'], nodes: ['节点', '◆'],
  accounts: ['账号', '♟'], settings: ['设置', '⚙'], diagnostics: ['诊断', '⌁']
};
const connectionLabels = { offline: '离线', overlayStarting: '组网启动中', backendConnecting: '后端连接中', syncing: '同步中', online: '在线', degraded: '连接降级', error: '连接错误' };
const statusClass = (state) => ['online'].includes(state) ? 'online' : ['syncing', 'overlayStarting', 'backendConnecting', 'running'].includes(state) ? 'running' : state === 'degraded' ? 'degraded' : ['error', 'failed', 'offline'].includes(state) ? 'error' : '';

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([key, value]) => {
    if (value === undefined || value === null || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'dataset') Object.entries(value).forEach(([name, entry]) => { node.dataset[name] = String(entry); });
    else if (key === 'checked' || key === 'disabled' || key === 'selected') node[key] = Boolean(value);
    else if (key === 'value') node.value = value;
    else node.setAttribute(key, value === true ? '' : String(value));
  });
  children.flat(Infinity).forEach((child) => {
    if (child === null || child === undefined || child === false) return;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  });
  return node;
}

/* Android 11 can ship an older System WebView without Element.replaceChildren.
 * Keep rendering compatible without requiring users to update a system component. */
function replaceChildren(node, ...children) {
  while (node.firstChild) node.removeChild(node.firstChild);
  children.forEach((child) => {
    if (child === null || child === undefined || child === false) return;
    node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  });
}

function button(text, action, options = {}) {
  return h('button', { type: 'button', class: `btn ${options.class || ''}`, dataset: { action, ...(options.data || {}) }, disabled: options.disabled, 'aria-label': options.label }, text);
}

function badge(text, state) {
  return h('span', { class: 'badge' }, h('span', { class: `status-dot status-${statusClass(state)}`, 'aria-hidden': 'true' }), text);
}

function brandMark(kind, label = true) {
  const claude = kind === 'claude';
  return h('span', { class: `provider-mark provider-${claude ? 'anthropic' : 'openai'}`, title: claude ? 'Anthropic · Claude Code' : 'OpenAI · Codex' },
    h('img', { src: `./assets/brands/${claude ? 'anthropic' : 'openai'}.svg`, alt: '' }),
    label ? h('span', { text: claude ? 'Anthropic' : 'OpenAI' }) : null);
}

function fmtTokens(value) {
  const number = Number(value || 0);
  return number >= 1000000 ? `${(number / 1000000).toFixed(1)}m` : number >= 1000 ? `${(number / 1000).toFixed(1)}k` : String(number);
}

function fmtTime(value) {
  if (!value) return '暂无';
  try { return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
  catch { return value; }
}

function relTime(value, nowValue) {
  if (!value) return '暂无';
  const timestamp = new Date(value).getTime();
  const now = new Date(nowValue || Date.now()).getTime();
  if (!Number.isFinite(timestamp) || !Number.isFinite(now)) return fmtTime(value);
  const minutes = Math.max(0, Math.round((now - timestamp) / 60000));
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.round(hours / 24);
  return days < 7 ? `${days} 天前` : fmtTime(value);
}

function metric(value, unit = 'ms', empty = '不可测') { return Number.isFinite(value) && value > 0 ? `${Math.round(value)} ${unit}` : empty; }

function header(view, route = 'console') {
  const connection = view?.connection || { state: 'offline', summary: '等待 Host 状态' };
  const nextTheme = view?.appearance?.theme === 'light' ? 'dark' : view?.appearance?.theme === 'dark' ? 'system' : 'light';
  const currentThemeLabel = view?.appearance?.theme === 'dark' ? '夜间' : view?.appearance?.theme === 'system' ? '跟随系统' : '日间';
  return h('header', { class: 'app-header' },
    h('div', { class: 'brand', 'aria-label': 'moyu' }, h('span', { class: 'brand-mark', 'aria-hidden': 'true' }, '划'), h('span', { text: 'moyu' })),
    view ? h('strong', { class: 'header-route-title', text: labels[route]?.[0] || '控制台' }) : null,
    h('div', { class: 'header-status' },
      badge(connectionLabels[connection.state] || connection.state, connection.state),
      view ? button(view.appearance.theme === 'dark' ? '☾' : view.appearance.theme === 'system' ? '◐' : '☀', 'header-theme', {
        class: 'btn-icon btn-quiet header-theme',
        label: `切换主题，当前${currentThemeLabel}`,
        data: { theme: nextTheme }
      }) : null)
  );
}

function nav(route) {
  return h('nav', { class: 'bottom-nav', 'aria-label': '主导航' }, Object.entries(labels).map(([key, [name, icon]]) =>
    h('button', { type: 'button', class: 'nav-item', dataset: { action: 'route', route: key }, 'aria-current': route === key ? 'page' : undefined, 'aria-label': name },
      h('span', { class: 'nav-icon', 'aria-hidden': 'true' }, icon), h('span', {}, name))));
}

function globalBanner(view) {
  const banner = view?.ui?.globalBanner;
  if (!banner) return null;
  return h('aside', { class: `banner banner-${banner.level}`, role: banner.level === 'error' ? 'alert' : 'status' },
    h('span', { 'aria-hidden': 'true' }, banner.level === 'error' ? '!' : 'i'), h('span', { class: 'grow', text: banner.text }),
    banner.actionLabel ? button(banner.actionLabel, 'banner-action', { class: 'btn-small btn-quiet' }) : null);
}

function pageHeading(title, subtitle, actions = []) {
  return h('div', { class: 'page-heading' }, h('div', {}, h('h1', {}, title), subtitle ? h('p', { class: 'muted', text: subtitle }) : null), h('div', { class: 'row' }, actions));
}

function connectionStrip(view) {
  const c = view.connection;
  return h('section', { class: 'connection-strip', 'aria-label': '连接状态' },
    h('span', { class: `status-dot status-${statusClass(c.state)}`, 'aria-hidden': 'true' }),
    h('div', { class: 'grow' }, h('strong', { text: connectionLabels[c.state] || c.state }), h('span', { class: 'muted', text: c.summary })),
    c.phoneBackendRttMs ? h('span', { class: 'badge mono', text: metric(c.phoneBackendRttMs) }) : null);
}

function timelineItem(item, session, state) {
  const time = h('time', { datetime: item.createdAt, text: fmtTime(item.createdAt) });
  if (item.kind === 'message') {
    const turn = item.turnMeta || {};
    const usage = turn.usage || {};
    const replyFooter = item.role === 'assistant' ? h('footer', { class: 'reply-meta', 'aria-label': '本轮模型与用量' },
      h('span', { title: '模型', text: turn.model || session.model || 'CLI 默认模型' }),
      h('span', { title: '推理深度', text: `深度 ${turn.effort || session.effort || '默认'}` }),
      h('span', { title: '输入 token', text: `入 ${fmtTokens(usage.inputTokens)}` }),
      h('span', { title: '输出 token', text: `出 ${fmtTokens(usage.outputTokens)}` }),
      h('span', { title: '缓存读取 token', text: `缓存读 ${fmtTokens(usage.cacheReadTokens)}` }),
      h('span', { title: '缓存写入 token', text: `缓存写 ${fmtTokens(usage.cacheWriteTokens)}` })) : null;
    return h('article', { class: 'timeline-item message-card', dataset: { role: item.role } },
      h('div', { class: 'timeline-head' }, item.role === 'assistant' ? brandMark(session.kind) : h('span', {}, item.role === 'user' ? '你' : '系统'), time),
      h('p', { class: 'timeline-text', text: item.text }), replyFooter);
  }
  if (item.kind === 'thinking') return h('article', { class: `timeline-item thinking ${item.streaming ? 'streaming' : ''}`, 'aria-live': item.streaming ? 'polite' : undefined },
    h('div', { class: 'timeline-head' }, h('span', {}, item.streaming ? '正在思考' : '思考'), time), h('p', { class: 'timeline-text', text: item.text }));
  if (item.kind === 'tool') {
    const output = item.output || (item.state === 'running' ? '等待工具输出…' : '无输出');
    const expanded = state.expandedItems.has(`tool-${item.localSeq}`);
    return h('article', { class: 'timeline-item tool-card', dataset: { state: item.state } },
      h('div', { class: 'tool-head' }, h('strong', { text: `工具 · ${item.tool}` }), badge(item.state === 'running' ? '运行中' : item.state === 'done' ? '已完成' : '失败', item.state)),
      item.input !== undefined ? h('pre', { class: 'tool-output', text: safeStringify(item.input) }) : null,
      h('pre', { class: `tool-output ${expanded ? 'expanded' : ''}`, text: output }),
      output.length > 500 ? button(expanded ? '收起' : '展开完整输出', 'toggle-output', { class: 'btn-small btn-quiet', data: { id: `tool-${item.localSeq}` } }) : null);
  }
  if (item.kind === 'approval') return approvalCard(item.approval, session);
  if (item.kind === 'usage') return null;
  if (item.kind === 'error') return h('article', { class: 'timeline-item error-item', role: 'alert' },
    h('div', { class: 'timeline-head' }, h('strong', {}, '本轮失败'), time), h('p', { text: item.error.summary }), h('code', { text: item.error.code }));
  return null;
}

function conversationItems(messages = []) {
  const result = [];
  let lastAssistant = -1;
  messages.forEach((item) => {
    if (item.kind === 'usage') {
      if (lastAssistant >= 0) result[lastAssistant] = { ...result[lastAssistant], turnMeta: item };
      return;
    }
    result.push(item);
    if (item.kind === 'message' && item.role === 'assistant') lastAssistant = result.length - 1;
  });
  return result;
}

function latestTurnMeta(messages = []) {
  for (let index = messages.length - 1; index >= 0; index -= 1) if (messages[index]?.kind === 'usage') return messages[index];
  return null;
}

function approvalCard(approval, session) {
  const online = getState().view?.connection?.state === 'online';
  const submitting = approval.state === 'submitting' || getState().submitting.has(`approval-${approval.approvalId}`);
  const expired = approval.state === 'expired';
  const card = h('article', { class: 'timeline-item approval-card', dataset: { state: approval.state }, 'aria-live': 'assertive' },
    h('div', { class: 'timeline-head' }, h('strong', {}, expired ? '审批已过期' : '需要你的审批'), badge(approval.state, approval.state === 'pending' ? 'running' : approval.state)),
    h('h3', { text: approval.summary }), approval.tool ? h('p', {}, '工具：', h('code', { text: approval.tool })) : null,
    approval.input !== undefined ? h('pre', { class: 'tool-output', text: safeStringify(approval.input) }) : null);
  if (expired) card.append(h('p', {}, '审批已过期，后端可能已自动拒绝。'));
  if (approval.state === 'pending' || submitting) card.append(h('div', { class: 'row' }, approval.choices.map((choice) =>
    button({ allow: '允许一次', allow_session: '本会话允许', deny: '拒绝', cancel: '取消' }[choice] || choice, 'approval', {
      class: choice === 'deny' ? 'btn-danger' : choice === 'allow_session' ? 'btn-cyan' : 'btn-primary',
      disabled: submitting || !online,
      data: { approvalId: approval.approvalId, sessionId: session.localSessionId, decision: choice }
    }))));
  if (!online && approval.state === 'pending') card.append(h('p', { class: 'muted', text: '离线时不能提交审批。' }));
  return card;
}

function consolePage(view, state) {
  const session = view.activeSession;
  const sessionMessages = session?.messages?.filter((item) => item.kind === 'message') || [];
  const page = h('main', { id: 'main-content', class: 'page console-page', tabindex: '-1' }, connectionStrip(view));
  page.append(pageHeading('控制台', '连接状态不阻塞本地内容', [button('＋ 新建会话', 'open-create', { class: 'btn-primary' })]));
  if (session) page.append(h('section', { class: 'card active-session-card' },
    h('div', { class: 'row space-between' }, brandMark(session.kind), badge(session.state, session.state)),
    h('h2', { text: session.title }), h('p', { class: 'muted', text: sessionMessages.length ? sessionMessages[sessionMessages.length - 1].text : '本地会话记录可用' }),
    h('div', { class: 'row space-between' }, h('span', { class: 'mono muted', text: session.model || 'CLI 默认模型' }), button('进入全屏会话', 'open-session', { class: 'btn-cyan', data: { sessionId: session.localSessionId } }))));
  else page.append(h('section', { class: 'empty-state' }, h('div', { class: 'empty-icon', 'aria-hidden': 'true' }, '⌁'), h('h2', {}, view.nodes.length ? '还没有打开会话' : '先保留你的思路'), h('p', {}, view.nodes.length ? '从本地会话列表打开，或创建一个新会话。' : '尚未配置节点。本地历史仍可查看，节点配置不会阻塞控制台。'), view.nodes.length ? button('查看会话', 'route', { class: 'btn-primary', data: { route: 'sessions' } }) : button('前往节点', 'route', { class: 'btn-cyan', data: { route: 'nodes' } })));
  page.append(h('section', { class: 'dashboard-grid' },
    h('article', { class: 'card' }, h('h2', {}, '本地会话'), h('strong', { class: 'dashboard-number', text: String(view.sessions.length) }), h('p', { class: 'muted', text: '离线仍可查看' })),
    h('article', { class: 'card' }, h('h2', {}, '节点'), h('strong', { class: 'dashboard-number', text: String(view.nodes.length) }), h('p', { class: 'muted', text: view.connection.summary }))));
  return page;
}

function conversationPage(view, state) {
  const session = view.activeSession;
  if (!session) return h('main', { id: 'main-content', class: 'conversation-page empty-state', tabindex: '-1' }, h('h1', {}, '会话不可用'), h('p', {}, '本地记录可能已删除。'), button('返回会话列表', 'route', { class: 'btn-primary', data: { route: 'sessions' } }));
  const turn = latestTurnMeta(session.messages);
  const usage = turn?.usage || {};
  const contextInput = Number(usage.inputTokens || 0) + Number(usage.cacheReadTokens || 0) + Number(usage.cacheWriteTokens || 0);
  const contextLimit = Number(turn?.contextWindowTokens || 0);
  const contextPercent = contextLimit > 0 ? Math.min(100, Math.round(contextInput / contextLimit * 100)) : null;
  return h('main', { id: 'main-content', class: 'conversation-page', tabindex: '-1' },
    h('header', { class: 'conversation-header' },
      button('←', 'route', { class: 'btn-icon btn-quiet conversation-back', label: '返回会话列表', data: { route: 'sessions' } }),
      brandMark(session.kind, false),
      h('div', { class: 'conversation-title grow' }, h('strong', { text: session.title }), h('span', { class: 'muted', text: `${session.model || 'CLI 默认模型'} · 深度 ${session.effort || '默认'}` })),
      badge(session.state, session.state),
      button('Diff', 'open-diff', { class: 'btn-small btn-cyan', disabled: !session.diff && (view.connection.state !== 'online' || !session.remoteSessionId), data: { sessionId: session.localSessionId } })),
    h('section', { class: 'context-strip', 'aria-label': '上下文窗口用量' },
      h('div', { class: 'row space-between' }, h('strong', { text: `上下文输入 ${fmtTokens(contextInput)}` }), h('span', { class: 'muted', text: contextPercent == null ? '窗口上限由 CLI 管理' : `${fmtTokens(contextLimit)} · ${contextPercent}%` })),
      contextPercent == null ? null : h('div', { class: 'context-meter' }, h('span', { style: `width:${contextPercent}%` }))),
    h('section', { class: 'conversation-scroll' },
      session.hasOlderLocalMessages ? button('加载更早本地记录', 'load-older', { class: 'btn-quiet', data: { sessionId: session.localSessionId } }) : null,
      h('div', { class: 'timeline conversation-timeline', 'aria-label': '会话记录', 'aria-live': 'polite' }, conversationItems(session.messages).map((item) => timelineItem(item, session, state)))),
    composer(session, view.connection.state, state));
}

let draftTimer = 0;
let pairDraftTimer = 0;
function composer(session, connectionState, state) {
  const offline = connectionState !== 'online';
  const sending = state.submitting.has(`send-${session.localSessionId}`);
  return h('section', { class: 'composer-dock', 'aria-label': '消息输入区' }, h('div', { class: 'composer' },
    offline ? h('div', { class: 'banner banner-warning' }, '当前离线：草稿会保存，但不会进入发送队列。恢复在线后请重新确认发送。') : null,
    h('div', { id: 'slash-palette', class: 'slash-palette', role: 'listbox', 'aria-label': '斜杠命令', hidden: true }),
    h('textarea', { id: 'composer-input', rows: '2', placeholder: '输入消息，或输入 / 查看命令', 'aria-label': '消息', 'aria-controls': 'slash-palette', value: session.composerDraft || '' }),
    h('div', { class: 'composer-actions' },
      h('span', { class: 'muted grow', text: '草稿自动保存' }),
      session.canInterrupt ? button('中断', 'interrupt', { class: 'btn-danger', data: { sessionId: session.localSessionId } }) : null,
      button(sending ? '发送中…' : '发送', 'send', { class: 'btn-primary', disabled: (!session.canSend && !offline) || sending, data: { sessionId: session.localSessionId, offline } }))));
}

function sessionsPage(view, state) {
  const f = state.filters;
  const sessions = view.sessions.filter((s) => (f.query === '' || `${s.title} ${s.preview || ''}`.toLowerCase().includes(f.query.toLowerCase())) && (f.node === 'all' || s.nodeId === f.node) && (f.kind === 'all' || s.kind === f.kind) && (f.state === 'all' || s.state === f.state));
  const loading = view.connection.state === 'syncing' && view.sessions.length === 0;
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('本地会话', '离线也能查看的工作记录', [button('＋ 新建', 'open-create', { class: 'btn-primary' })]),
    h('section', { class: 'filter-grid', 'aria-label': '会话筛选' },
      h('input', { class: 'search', type: 'search', value: f.query, placeholder: '搜索标题或内容', 'aria-label': '搜索会话', dataset: { filter: 'query' } }),
      selectField('node', '节点', [['all', '全部节点'], ...view.nodes.map((n) => [n.nodeId, n.displayName])], f.node),
      selectField('kind', '平台', [['all', '全部平台'], ['claude', 'Claude'], ['codex', 'Codex']], f.kind),
      selectField('state', '状态', [['all', '全部状态'], ['running', '运行中'], ['idle', '空闲'], ['completed', '已完成'], ['localOnly', '仅本地']], f.state)),
    loading ? sessionSkeletons() : sessions.length ? h('section', { class: 'session-list' }, sessions.map((session) => sessionCard(session, view.now))) : h('section', { class: 'empty-state' }, h('div', { class: 'empty-icon' }, '☷'), h('h2', {}, '没有匹配的会话'), h('p', {}, '试试调整筛选条件，或创建一个新会话。')));
}

function sessionSkeletons() {
  return h('section', { class: 'session-list', 'aria-label': '正在同步会话', 'aria-busy': 'true' }, Array.from({ length: 4 }, (_, index) =>
    h('article', { class: 'card session-card', 'aria-hidden': 'true', dataset: { skeleton: index } },
      h('div', { class: 'skeleton skeleton-title' }), h('div', { class: 'skeleton' }), h('div', { class: 'skeleton skeleton-short' }))));
}

function selectField(name, label, options, value) {
  return choiceField(label, name, options, value, 'filter', { class: 'filter-choice', hiddenLabel: true });
}

function sessionCard(session, nowValue) {
  const remoteHot = Boolean(session.remoteSessionId) && session.state === 'running';
  return h('article', { class: `card session-card ${session.unread ? 'unread' : ''}` },
    h('div', { class: 'row space-between' }, brandMark(session.kind), h('div', { class: 'row' }, session.state === 'localOnly' ? h('span', { class: 'badge accent-yellow', text: '仅本地' }) : null, remoteHot ? h('span', { class: 'badge accent-lime', text: '远端热会话' }) : null, session.unread ? h('span', { class: 'badge accent-pink', text: `${session.unread} 未读` }) : null, badge(session.state, session.state))),
    h('div', {}, h('h2', { text: session.title }), h('p', { class: 'muted', text: session.preview || '暂无预览' })),
    h('div', { class: 'row space-between' }, h('time', { class: 'muted', datetime: session.updatedAt, title: fmtTime(session.updatedAt), text: relTime(session.updatedAt, nowValue) }), h('div', { class: 'row' }, button('打开', 'open-session', { class: 'btn-small btn-cyan', data: { sessionId: session.localSessionId } }), button('删除', 'confirm-delete-session', { class: 'btn-small btn-danger', data: { sessionId: session.localSessionId, title: session.title } }))));
}

function nodesPage(view) {
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('节点', '管理你的 PC 连接', [button('配对节点', 'open-pair', { class: 'btn-primary' })]),
    !view.nodes.length ? h('section', { class: 'empty-state' }, h('div', { class: 'empty-icon' }, '◆'), h('h2', {}, '还没有节点'), h('p', {}, '配对信息会交给 Android 原生层处理，敏感凭据不会进入此页面。'), button('开始配对', 'open-pair', { class: 'btn-primary' }), button('原生手动配置', 'manual-setup', { class: 'btn-quiet' })) :
      h('section', { class: 'node-list' }, view.nodes.map((node) => h('article', { class: `card node-card ${node.active ? 'active' : ''}` },
        h('div', { class: 'row space-between' }, h('h2', { text: node.displayName }), node.active ? h('span', { class: 'badge accent-cyan', text: '当前节点' }) : null),
        h('p', { class: 'mono muted', text: node.relayNode }),
        h('div', { class: 'row' }, badge(`Overlay ${node.overlayState}`, node.overlayState), badge(`后端 ${node.backendState}`, node.backendState), badge(`同步 ${node.syncState}`, node.syncState)),
        h('p', {}, `凭据：${node.secretState.token && node.secretState.networkSecret ? '已配置' : '缺少配置'} · Relay ${metric(node.relayLatencyMs, 'ms', '暂无数据')}${node.relayLatencyReliable === false ? '（参考值）' : ''}`),
        h('div', { class: 'row' }, node.backendState === 'online' ? button('断开', 'node-disconnect', { data: { nodeId: node.nodeId } }) : button('连接', 'node-connect', { class: 'btn-primary', disabled: !node.configured, data: { nodeId: node.nodeId } }), button('诊断', 'node-diagnose', { class: 'btn-cyan', data: { nodeId: node.nodeId } }), button('原生编辑', 'edit-node', { data: { displayName: node.displayName, relayNode: node.relayNode } }), button('删除', 'confirm-delete-node', { class: 'btn-danger', data: { nodeId: node.nodeId, title: node.displayName } }))))));
}

function accountsPage(view) {
  const accounts = view.accounts;
  if (!accounts) return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('平台与账号', '凭据状态由 Android 安全提供'), h('section', { class: 'empty-state' }, h('h2', {}, '暂无账号状态')));
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('平台与账号', '切换只对下次新建会话生效'), h('div', { class: 'banner banner-warning' }, '已有会话不会中途切换 Profile；新建会话时会冻结平台、Profile 与 Model。'),
    h('section', { class: 'account-list' }, accounts.adapters.map((adapter) => h('article', { class: `card account-group ${adapter.adapter}` },
      h('div', { class: 'row space-between' }, h('h2', {}, adapter.displayName), badge(adapter.available ? '可用' : '不可用', adapter.available ? 'online' : 'error')),
      h('p', { class: 'muted', text: adapter.capabilities.description || '原生 CLI 账号环境' }),
      adapter.profiles.map((profile) => h('div', { class: 'account-profile' }, h('div', {}, h('strong', { text: profile.displayName }), h('div', { class: 'row' }, profile.nativeDefault ? h('span', { class: 'badge', text: 'native default' }) : null, h('span', { class: 'badge', text: profile.hasCredentials ? '已有凭据' : '无凭据' }), profile.active ? h('span', { class: 'badge accent-lime', text: '当前使用' }) : null)), profile.active ? null : button('激活', 'activate-account', { class: 'btn-small btn-cyan', disabled: !profile.hasCredentials, data: { nodeId: accounts.nodeId, adapter: adapter.adapter, profileId: profile.profileId } })))))));
}

function settingsPage(view) {
  const config = view.config || {};
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('设置', '偏好由 Android glue 持久化'),
    h('section', { class: 'card settings-section' }, h('h2', {}, '外观'), h('div', { class: 'segmented', role: 'group', 'aria-label': '主题' }, [['system', '跟随系统'], ['light', '日间'], ['dark', '夜间']].map(([key, text]) => h('button', { type: 'button', dataset: { action: 'theme', theme: key }, 'aria-pressed': view.appearance.theme === key }, text)))),
    h('section', { class: 'card settings-section' }, h('h2', {}, '新会话默认值'),
      settingSelect('默认平台', 'defaultAdapter', [['claude', 'Claude Code'], ['codex', 'Codex CLI']], config.defaultAdapter),
      settingSelect('默认 Model', 'model', (config.availableModels || ['default']).map((x) => [x, x]), config.model),
      settingSelect('Approval Policy', 'approvalPolicy', [['ask', '每次询问'], ['deny', '默认拒绝']], config.approvalPolicy),
      settingSelect('Sandbox', 'sandbox', [['workspace-write', 'workspace-write'], ['read-only', 'read-only'], ['danger-full-access', 'danger-full-access']], config.sandbox),
      settingSelect('Approvals Reviewer', 'approvalsReviewer', [['auto_review', 'auto_review'], ['user', 'user']], config.approvalsReviewer)),
    h('section', { class: 'card settings-section' }, h('h2', {}, '关于'), h('div', { class: 'setting-row' }, h('span', {}, 'UI 版本'), h('code', {}, '0.0.2')), h('div', { class: 'setting-row' }, h('span', {}, '协议版本'), h('code', {}, '1'))));
}

function settingSelect(label, key, options, value) {
  return h('div', { class: 'setting-row' }, h('span', { text: label }), choiceField(label, key, options, value, 'config', { class: 'setting-choice', hiddenLabel: true }));
}

function diagnosticsPage(view) {
  const d = view.diagnostics || { protocolVersion: 1, notes: [] };
  const t = d.transport || {};
  const activeNode = view.nodes.find((node) => node.nodeId === view.activeNodeId);
  const values = [
    ['手机↔后端 RTT', metric(t.phoneBackendRttMs)], ['后端排队', metric(t.backendCliQueueMs)], ['后端→CLI 派发', metric(t.backendCliDispatchMs)],
    ['CLI 首事件（聚合）', metric(t.cliFirstEventMs)], ['PC↔relay TCP', metric(t.relayLatencyMs, 'ms', '暂无数据')]
  ];
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('诊断', '只展示端到端可观测指标', [activeNode ? button('重新诊断', 'node-diagnose', { class: 'btn-cyan', data: { nodeId: activeNode.nodeId } }) : null]), connectionStrip(view),
    h('section', { class: 'metric-grid' }, values.map(([name, value]) => h('article', { class: 'metric' }, h('span', { text: name }), h('strong', { text: value })))),
    activeNode?.relayLatencyReliable === false ? h('div', { class: 'banner banner-warning' }, 'Relay TCP 延迟为参考值，当前采样不可靠。') : null,
    h('section', { class: 'card stack' }, h('h2', {}, '运行状态'), h('div', { class: 'meta-grid' },
      meta('Connection', connectionLabels[view.connection.state] || view.connection.state), meta('Overlay', activeNode?.overlayState || '暂无'), meta('Sync', activeNode?.syncState || '暂无'), meta('后端版本', d.backendVersion || '暂无'), meta('协议版本', String(d.protocolVersion)), meta('最近同步', fmtTime(d.lastSyncAt))),
      d.net ? h('div', {}, h('h3', {}, 'Clash / TUN 节点'), h('pre', { class: 'tool-output', text: safeStringify(d.net) })) : null,
      h('div', {}, h('h3', {}, '限制说明'), h('ul', {}, [...d.notes, '无法测量 CLI 到 AI 服务端的单向延迟；不会以 0 代替缺失值。'].map((note) => h('li', { text: note }))))));
}

function meta(label, value) { return h('div', { class: 'meta-item' }, h('span', { text: label }), h('strong', { text: value })); }

function renderDiffPatch(patchText) {
  const text = patchText || '';
  const lines = text.split('\n');
  const additions = lines.filter((line) => line.startsWith('+') && !line.startsWith('+++')).length;
  const deletions = lines.filter((line) => line.startsWith('-') && !line.startsWith('---')).length;
  const content = h('div', { class: 'diff-lines', role: 'region', 'aria-label': 'Diff 内容' });
  lines.forEach((line) => {
    const kind = line.startsWith('@@') ? 'hunk' : line.startsWith('+') && !line.startsWith('+++') ? 'add' : line.startsWith('-') && !line.startsWith('---') ? 'del' : 'context';
    content.append(h('div', { class: `diff-line diff-line-${kind}`, text: line || ' ' }));
  });
  return { content, additions, deletions };
}

function diffFile(file) {
  const rendered = renderDiffPatch(file.patch);
  const statusLabels = { staged: '已暂存', unstaged: '未暂存', untracked: '未跟踪' };
  return h('article', { class: 'diff-file' },
    h('div', { class: 'diff-title' },
      h('span', { class: `badge diff-status diff-status-${file.status}`, text: statusLabels[file.status] || file.status }),
      h('strong', { class: 'grow mono', text: file.path }),
      h('span', { class: 'diff-count mono', text: `+${rendered.additions} −${rendered.deletions}` })),
    rendered.content);
}

function renderModal(state) {
  const root = document.querySelector('#dialog-root');
  replaceChildren(root);
  if (!state.modal) return;
  const content = modalContent(state.modal, state);
  const mandatory = state.modal.mandatory === true;
  const backdrop = h('div', { class: 'dialog-backdrop', dataset: { action: mandatory ? '' : 'close-modal' } },
    h('section', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'dialog-title', tabindex: '-1' }, content));
  root.append(backdrop);
  const dialog = backdrop.querySelector('.dialog');
  activateDialog(dialog, { mandatory, onClose: () => { if (getState().modal?.type === 'pair') persistPairDraft(); setModal(null); } });
}

function modalContent(modal, state) {
  const view = state.view;
  if (modal.type === 'create') {
    const adapters = view.server?.adapters?.filter((a) => a.available) || [{ adapter: 'claude', displayName: 'Claude Code', capabilities: {} }, { adapter: 'codex', displayName: 'Codex CLI', capabilities: {} }];
    const draft = state.createDraft;
    const adapter = adapters.find((item) => item.adapter === draft.kind) || adapters[0];
    const caps = adapter?.capabilities || {};
    const accountAdapter = view.accounts?.adapters?.find((item) => item.adapter === adapter?.adapter);
    const profiles = accountAdapter?.profiles || [];
    const models = adapter?.supportedModels || [];
    return h('form', { dataset: { form: 'create' } }, h('h2', { id: 'dialog-title' }, '新建会话'), h('p', { class: 'muted' }, '平台、Profile 与 Model 创建后冻结，不会在已有会话中热切换。'),
      createSelect('节点', 'nodeId', view.nodes.filter((n) => n.configured).map((n) => [n.nodeId, n.displayName]), draft.nodeId),
      createSelect('平台', 'kind', adapters.map((a) => [a.adapter, a.displayName]), adapter?.adapter),
      caps.profiles ? createSelect('Profile（创建后冻结）', 'profileId', profiles.map((profile) => [profile.profileId, `${profile.displayName}${profile.hasCredentials ? '' : '（缺少凭据）'}`, !profile.hasCredentials]), draft.profileId) : null,
      caps.models ? (models.length
        ? createSelect('Model（创建后冻结）', 'model', models.map((model) => [model, model]), draft.model)
        : fieldInput('Model（可选，创建后冻结）', 'model', '留空使用 CLI 默认值', draft.model)) : null,
      (caps.sandbox || caps.approvalsReviewer) ? h('section', { class: 'capability-summary', 'aria-label': '会话能力' },
        h('strong', {}, '本次会话能力'),
        h('div', { class: 'row' },
          caps.sandbox ? h('span', { class: 'badge accent-cyan', text: `Sandbox · ${view.config?.sandbox || '由 Host 决定'}` }) : null,
          caps.approvalsReviewer ? h('span', { class: 'badge accent-purple', text: `Reviewer · ${view.config?.approvalsReviewer || '由 Host 决定'}` }) : null),
        h('p', { class: 'muted', text: '这些值继承当前设置；CreateSessionDraft 不携带敏感配置。' })) : null,
      fieldInput('标题（可选）', 'title', '例如：修复登录流程', draft.title), fieldInput('工作目录（可选）', 'cwd', '由 Host 验证路径', draft.cwd),
      actions(button('取消', 'close-modal'), button('创建', 'submit-create', { class: 'btn-primary', disabled: !view.nodes.some((n) => n.configured) })));
  }
  if (modal.type === 'confirm') return h('div', {}, h('h2', { id: 'dialog-title', text: modal.title }), h('p', { text: modal.message }), modal.warning ? h('div', { class: 'banner banner-error', text: modal.warning }) : null, actions(button('返回', 'close-modal'), button(modal.confirmText || '确认', modal.action, { class: 'btn-danger', data: modal.data })));
  if (modal.type === 'pair') {
    const draft = state.pairDraft;
    return h('form', { dataset: { form: 'pair' } }, h('h2', { id: 'dialog-title' }, '配对新节点'),
      h('p', {}, '填写内容会由 Android 加密暂存；配对成功后自动清除。'),
      pairInput('显示名称', 'displayName', '例如：工作室 PC', draft.displayName),
      pairInput('Relay 节点', 'relayNode', '例如：relay.example:11010', draft.relayNode),
      pairInput('配对字符串', 'pairString', '从 PC 端复制', draft.pairString),
      actions(button('取消', 'close-modal'), button('配对', 'submit-pair', { class: 'btn-primary' })),
      h('hr'), button('改用原生手动配置', 'manual-setup', { class: 'btn-quiet' }));
  }
  if (modal.type === 'diff') {
    const diff = view.activeSession?.diff;
    return h('div', {}, h('h2', { id: 'dialog-title' }, '工作区 Diff'), !diff ? h('div', { class: 'empty-state' }, h('div', { class: 'skeleton' }), h('p', {}, '正在从后端读取 Diff；读取失败时会显示提示。')) : diff.isGitRepo === false ? h('div', { class: 'empty-state' }, h('h3', {}, '当前目录不是 Git 仓库'), h('p', {}, '仍可继续会话，但没有可展示的 Git Diff。')) : (diff.files?.length ? diff.files.map(diffFile) : h('div', { class: 'empty-state' }, h('h3', {}, '工作区没有更改'), h('p', {}, '当前没有 staged、unstaged 或 untracked 内容。'))), actions(button('关闭', 'close-modal')));
  }
  if (modal.type === 'files') return h('div', {}, h('h2', { id: 'dialog-title' }, '选择工作目录'), h('p', { class: 'mono muted', text: modal.path || '/' }), h('div', { class: 'file-tree' }, state.fileNodes.length ? state.fileNodes.map((node) => button(`${node.kind === 'directory' ? '▸' : '·'} ${node.name}`, 'fs-node', { class: 'file-node', disabled: node.kind !== 'directory', data: { nodeId: node.nodeId, path: node.path } })) : [h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' })]), actions(button('关闭', 'close-modal'), button('选择此目录', 'choose-directory', { class: 'btn-primary', data: { path: modal.path || '/' } })));
  if (modal.type === 'offline-send') return h('div', {}, h('h2', { id: 'dialog-title' }, '当前仍处于离线状态'), h('p', {}, '消息不会进入网络发送队列，也不会在恢复连接后自动重放。我们已保存草稿，请在线后再次点击发送。'), actions(button('我知道了', 'close-modal', { class: 'btn-primary' })));
  if (modal.type === 'allow-session') return h('div', {}, h('h2', { id: 'dialog-title' }, '允许本会话后续操作？'), h('div', { class: 'banner banner-warning' }, '此选择会降低后续审批频率，同一会话内的相似操作可能无需再次确认。'), h('p', {}, '请确认你理解当前工具与命令的影响。'), actions(button('返回', 'close-modal'), button('确认本会话允许', 'approval-final', { class: 'btn-danger', data: modal.data })));
  return h('div', {}, h('h2', { id: 'dialog-title' }, '提示'), actions(button('关闭', 'close-modal')));
}

function fieldInput(label, name, placeholder, value = '') { return h('label', { class: 'field' }, h('span', { text: label }), h('input', { name, placeholder, value })); }
function pairInput(label, name, placeholder, value = '') {
  return h('label', { class: 'field' }, h('span', { text: label }), h('input', { name, placeholder, value, autocomplete: 'off', dataset: { pairField: name } }));
}
function createSelect(label, name, options, value) {
  return choiceField(label, name, options, value, 'create');
}

function choiceField(label, name, options, value, scope, settings = {}) {
  const id = `choice-${scope}-${name}`;
  const selected = options.find(([key]) => key === value) || options.find(([, , disabled]) => !disabled);
  const selectedValue = selected?.[0] || '';
  const selectedText = selected?.[1] || '暂无可选项';
  return h('div', { class: `field choice-field ${settings.class || ''}` },
    h('span', { id: `${id}-label`, class: settings.hiddenLabel ? 'sr-only' : 'field-label', text: label }),
    scope === 'create' ? h('input', { type: 'hidden', name, value: selectedValue }) : null,
    h('button', {
      type: 'button',
      class: 'choice-trigger',
      dataset: { action: 'toggle-choice', choiceId: id },
      'aria-labelledby': `${id}-label ${id}-value`,
      'aria-controls': `${id}-menu`,
      'aria-haspopup': 'listbox',
      'aria-expanded': 'false',
      disabled: !selected
    }, h('span', { id: `${id}-value`, class: 'choice-value', text: selectedText }), h('span', { class: 'choice-caret', 'aria-hidden': 'true' }, '▾')),
    h('div', { id: `${id}-menu`, class: 'choice-menu', role: 'listbox', 'aria-labelledby': `${id}-label`, hidden: true },
      options.map(([optionValue, text, disabled]) => h('button', {
        type: 'button',
        class: 'choice-option',
        role: 'option',
        disabled,
        'aria-selected': optionValue === selectedValue ? 'true' : 'false',
        dataset: { action: 'choose-choice', scope, name, value: optionValue }
      }, h('span', { text }), h('span', { class: 'choice-check', 'aria-hidden': 'true' }, optionValue === selectedValue ? '✓' : '')))));
}
function actions(...nodes) { return h('div', { class: 'dialog-actions' }, nodes); }

function renderToast(notice) {
  const root = document.querySelector('#toast-root');
  replaceChildren(root);
  if (!notice) return;
  root.append(h('div', { class: 'toast', role: 'status', text: notice }));
  window.setTimeout(() => { if (getState().notice === notice) setNotice(''); }, 3200);
}

function initialCreateDraft(view, requestedKind) {
  const configuredNodes = view.nodes.filter((node) => node.configured);
  const adapters = view.server?.adapters?.filter((adapter) => adapter.available) || [];
  const kind = requestedKind || view.config?.defaultAdapter || adapters[0]?.adapter || 'claude';
  const adapter = adapters.find((item) => item.adapter === kind);
  const profiles = view.accounts?.adapters?.find((item) => item.adapter === kind)?.profiles || [];
  const profile = profiles.find((item) => item.active && item.hasCredentials) || profiles.find((item) => item.hasCredentials);
  const models = adapter?.supportedModels || [];
  const model = models.includes(view.config?.model) ? view.config.model : models[0] || '';
  return {
    nodeId: configuredNodes.some((node) => node.nodeId === view.activeNodeId) ? view.activeNodeId : configuredNodes[0]?.nodeId || '',
    kind,
    cwd: '',
    title: '',
    profileId: profile?.profileId || '',
    model
  };
}

let renderedRoute = '';

export function render(state) {
  const app = document.querySelector('#app');
  const view = state.view;
  if (!view) {
    replaceChildren(app, header(null), h('main', { class: 'page stack' }, h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' })));
    return;
  }
  document.documentElement.dataset.theme = view.appearance.resolvedTheme;
  const pages = { console: consolePage, conversation: conversationPage, sessions: sessionsPage, nodes: nodesPage, accounts: accountsPage, settings: settingsPage, diagnostics: diagnosticsPage };
  const page = (pages[state.route] || consolePage)(view, state);
  if (state.route !== renderedRoute) page.classList.add('page-enter');
  const conversation = state.route === 'conversation';
  app.classList.toggle('conversation-active', conversation);
  replaceChildren(app, ...[conversation ? null : header(view, state.route), globalBanner(view), h('div', { class: conversation ? 'conversation-layout' : 'main-layout' }, page), conversation ? null : nav(state.route)].filter(Boolean));
  if (conversation) updateSlashPalette(view.activeSession?.composerDraft || '');
  renderedRoute = state.route;
  app.setAttribute('aria-busy', 'false');
  renderModal(state);
  renderToast(state.notice);
}

function getComposerText() { return document.querySelector('#composer-input')?.value || ''; }

function currentPairDraft() {
  const form = document.querySelector('form[data-form="pair"]');
  return form ? Object.fromEntries(new FormData(form)) : { ...getState().pairDraft };
}

function persistPairDraft() {
  window.clearTimeout(pairDraftTimer);
  const draft = currentPairDraft();
  cachePairDraft(draft);
  sendIntent('node.pairDraft.save', draft);
}

function closeChoiceMenus(exceptId = '') {
  document.querySelectorAll('.choice-menu:not([hidden])').forEach((menu) => {
    if (menu.id === exceptId) return;
    menu.hidden = true;
    const trigger = document.querySelector(`[aria-controls="${menu.id}"]`);
    trigger?.setAttribute('aria-expanded', 'false');
  });
}

function chooseValue(target) {
  const { scope, name, value } = target.dataset;
  closeChoiceMenus();
  if (scope === 'filter') {
    setFilter(name, value);
    return;
  }
  if (scope === 'config') {
    if (name === 'sandbox' && value === 'danger-full-access') {
      setModal({ type: 'confirm', title: '启用 danger-full-access？', message: '该模式允许 CLI 绕过工作区沙箱，可能读写更广泛的系统文件。', warning: '仅在你完全信任当前任务时启用。', action: 'confirm-config', data: { key: name, value } });
    } else sendIntent('config.patch', { nodeId: getState().view.activeNodeId, patch: { [name]: value } });
    return;
  }
  if (scope === 'create') {
    const form = target.closest('form');
    const values = form ? Object.fromEntries(new FormData(form)) : {};
    values[name] = value;
    update((next) => {
      if (name === 'kind') {
        const defaults = initialCreateDraft(next.view, value);
        next.createDraft = { ...next.createDraft, ...values, ...defaults, nodeId: values.nodeId || defaults.nodeId || next.createDraft.nodeId, title: values.title || '', cwd: values.cwd || '', kind: value };
      } else next.createDraft = { ...next.createDraft, ...values };
    });
  }
}

const slashCommands = [
  { command: 'effort', title: '推理深度', hint: '选择后续轮次的原生 CLI effort' },
  { command: 'diff', title: '查看 Diff', hint: '打开当前工作区变更' },
  { command: 'new', title: '新建会话', hint: '创建并进入新的全屏会话' },
  { command: 'help', title: '命令帮助', hint: '查看本地可用的斜杠命令' }
];

function updateSlashPalette(value) {
  const palette = document.querySelector('#slash-palette');
  if (!palette) return;
  const text = String(value || '').trimStart();
  if (!text.startsWith('/') || text.includes('\n')) { palette.hidden = true; replaceChildren(palette); return; }
  const query = text.slice(1).trim().toLowerCase();
  const session = getState().view?.activeSession;
  if (query === 'effort' || query.startsWith('effort ')) {
    const levels = session?.effortLevels || [];
    const options = [['', 'CLI 默认'], ...levels.map((level) => [level, level])];
    replaceChildren(palette, h('div', { class: 'slash-heading', text: '选择推理深度 · 对后续轮次生效' }), ...options.map(([effort, label], index) => h('button', {
      type: 'button', class: `slash-option ${index === 0 ? 'active' : ''}`, role: 'option', dataset: { action: 'set-effort', effort }, 'aria-selected': index === 0 ? 'true' : 'false'
    }, h('strong', { text: label }), h('span', { class: 'muted', text: effort === session?.effort || (!effort && !session?.effort) ? '当前' : '选择' }))));
    palette.hidden = false;
    return;
  }
  const matches = slashCommands.filter((item) => !query || item.command.startsWith(query));
  replaceChildren(palette, ...(matches.length ? matches.map((item, index) => h('button', {
    type: 'button', class: `slash-option ${index === 0 ? 'active' : ''}`, role: 'option', dataset: { action: 'slash-command', command: item.command }, 'aria-selected': index === 0 ? 'true' : 'false'
  }, h('strong', { text: `/${item.command} · ${item.title}` }), h('span', { class: 'muted', text: item.hint }))) : [h('div', { class: 'slash-empty', text: '没有匹配的本地命令' })]));
  palette.hidden = false;
}

function clearComposerDraft(sessionId) {
  const input = document.querySelector('#composer-input');
  if (input) input.value = '';
  updateSlashPalette('');
  sendIntent('session.saveDraft', { localSessionId: sessionId, text: '' });
}

function moveSlashSelection(key) {
  const palette = document.querySelector('#slash-palette:not([hidden])');
  if (!palette) return false;
  const options = Array.from(palette.querySelectorAll('.slash-option:not(:disabled)'));
  if (!options.length) return true;
  const current = Math.max(0, options.findIndex((item) => item.classList.contains('active')));
  const next = key === 'ArrowDown' ? (current + 1) % options.length : (current - 1 + options.length) % options.length;
  options.forEach((item, index) => { item.classList.toggle('active', index === next); item.setAttribute('aria-selected', String(index === next)); });
  options[next].scrollIntoView({ block: 'nearest' });
  return true;
}

export function bindInteractions() {
  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-action]');
    if (!target) { closeChoiceMenus(); return; }
    if (target.disabled) return;
    const action = target.dataset.action;
    if (!action) return;
    if (action === 'toggle-choice') {
      event.preventDefault();
      const menu = document.getElementById(`${target.dataset.choiceId}-menu`);
      if (!menu) return;
      const opening = menu.hidden;
      closeChoiceMenus(opening ? menu.id : '');
      menu.hidden = !opening;
      target.setAttribute('aria-expanded', String(opening));
      return;
    }
    if (action === 'choose-choice') { event.preventDefault(); chooseValue(target); return; }
    closeChoiceMenus();
    if (action === 'route') openRoute(target.dataset.route);
    else if (action === 'open-create') update((next) => { next.createDraft = initialCreateDraft(next.view); next.modal = { type: 'create' }; });
    else if (action === 'close-modal') { if (event.target === target || target.tagName === 'BUTTON') { if (getState().modal?.type === 'pair') persistPairDraft(); setModal(null); } }
    else if (action === 'toggle-output') toggleExpanded(target.dataset.id);
    else if (action === 'open-session') { openRoute('conversation', { notifyHost: false }); sendIntent('session.open', { localSessionId: target.dataset.sessionId }); }
    else if (action === 'confirm-delete-session') setModal({ type: 'confirm', title: '删除本地会话？', message: `“${target.dataset.title}”的 Android 本地记录将被删除，远端会话不受影响。`, confirmText: '删除本地记录', action: 'delete-session', data: { sessionId: target.dataset.sessionId } });
    else if (action === 'delete-session') { sendIntent('session.deleteLocal', { localSessionId: target.dataset.sessionId }, { pendingKey: `delete-${target.dataset.sessionId}` }); setModal(null); }
    else if (action === 'confirm-delete-node') setModal({ type: 'confirm', title: '删除节点？', message: `确认删除“${target.dataset.title}”的本地节点记录。`, warning: '此操作不会清除 PC 端数据。', action: 'delete-node', data: { nodeId: target.dataset.nodeId } });
    else if (action === 'delete-node') { sendIntent('node.delete', { nodeId: target.dataset.nodeId }); setModal(null); }
    else if (action === 'node-connect') sendIntent('node.connect', { nodeId: target.dataset.nodeId }, { pendingKey: `node-${target.dataset.nodeId}` });
    else if (action === 'node-disconnect') sendIntent('node.disconnect', { nodeId: target.dataset.nodeId }, { pendingKey: `node-${target.dataset.nodeId}` });
    else if (action === 'node-diagnose') sendIntent('node.diagnose', { nodeId: target.dataset.nodeId }, { pendingKey: `diagnose-${target.dataset.nodeId}` });
    else if (action === 'open-pair') update((next) => { next.pairDraft = { ...next.pairDraft, ...(next.view.pairDraft || {}) }; next.modal = { type: 'pair' }; });
    else if (action === 'manual-setup') { if (getState().modal?.type === 'pair') persistPairDraft(); sendIntent('node.manualSetup.open', {}); setModal(null); }
    else if (action === 'edit-node') sendIntent('node.manualSetup.open', { displayName: target.dataset.displayName, relayNode: target.dataset.relayNode });
    else if (action === 'activate-account') sendIntent('accounts.activate', { nodeId: target.dataset.nodeId, adapter: target.dataset.adapter, profileId: target.dataset.profileId }, { pendingKey: `account-${target.dataset.adapter}` });
    else if (action === 'theme') sendIntent('appearance.set', { theme: target.dataset.theme }, { pendingKey: 'appearance' });
    else if (action === 'header-theme') sendIntent('appearance.set', { theme: target.dataset.theme }, { pendingKey: 'appearance' });
    else if (action === 'open-diff') { setModal({ type: 'diff' }); if (getState().view.connection.state === 'online') sendIntent('diff.open', { localSessionId: target.dataset.sessionId }, { pendingKey: `diff-${target.dataset.sessionId}` }); }
    else if (action === 'open-files') { update((next) => { next.fileNodes = []; next.modal = { type: 'files', path: '/' }; }); sendIntent('fs.list', { nodeId: getState().view.activeNodeId, path: '/' }); }
    else if (action === 'fs-node') { update((next) => { next.fileNodes = []; next.modal = { type: 'files', path: target.dataset.path }; }); sendIntent('fs.list', { nodeId: getState().view.activeNodeId, path: target.dataset.path }); }
    else if (action === 'choose-directory') { setNotice(`已选择目录：${target.dataset.path}`); setModal(null); }
    else if (action === 'load-older') sendIntent('session.loadOlder', { localSessionId: target.dataset.sessionId });
    else if (action === 'interrupt') sendIntent('session.interrupt', { localSessionId: target.dataset.sessionId }, { pendingKey: `interrupt-${target.dataset.sessionId}` });
    else if (action === 'send') {
      const text = getComposerText().trim();
      if (!text) return;
      document.querySelector('#composer-input')?.blur();
      if (target.dataset.offline === 'true') { sendIntent('session.saveDraft', { localSessionId: target.dataset.sessionId, text }); setModal({ type: 'offline-send' }); }
      else sendIntent('session.send', { localSessionId: target.dataset.sessionId, text }, { pendingKey: `send-${target.dataset.sessionId}` });
    }
    else if (action === 'slash-command') {
      const session = getState().view?.activeSession;
      if (!session) return;
      if (target.dataset.command === 'effort') { const input = document.querySelector('#composer-input'); if (input) input.value = '/effort'; updateSlashPalette('/effort'); }
      else if (target.dataset.command === 'diff') { clearComposerDraft(session.localSessionId); setModal({ type: 'diff' }); if (getState().view.connection.state === 'online') sendIntent('diff.open', { localSessionId: session.localSessionId }); }
      else if (target.dataset.command === 'new') { clearComposerDraft(session.localSessionId); update((next) => { next.createDraft = initialCreateDraft(next.view); next.modal = { type: 'create' }; }); }
      else { clearComposerDraft(session.localSessionId); setNotice('可用命令：/effort、/diff、/new、/help'); }
    }
    else if (action === 'set-effort') {
      const session = getState().view?.activeSession;
      if (!session) return;
      const effort = target.dataset.effort || null;
      clearComposerDraft(session.localSessionId);
      sendIntent('session.effort.set', { localSessionId: session.localSessionId, effort }, { pendingKey: `effort-${session.localSessionId}` });
      setNotice(`正在应用推理深度：${effort || 'CLI 默认'}`);
    }
    else if (action === 'approval') {
      const data = { localSessionId: target.dataset.sessionId, approvalId: target.dataset.approvalId, decision: target.dataset.decision };
      if (data.decision === 'allow_session') setModal({ type: 'allow-session', data });
      else sendIntent('approval.decide', data, { pendingKey: `approval-${data.approvalId}` });
    }
    else if (action === 'approval-final') { sendIntent('approval.decide', { localSessionId: target.dataset.sessionId, approvalId: target.dataset.approvalId, decision: target.dataset.decision }, { pendingKey: `approval-${target.dataset.approvalId}` }); setModal(null); }
    else if (action === 'banner-action') { const intent = getState().view.ui.globalBanner?.actionIntent; if (intent) sendIntent(intent.type, intent.payload); }
  });

  document.addEventListener('input', (event) => {
    if (event.target.matches('[data-filter]')) setFilter(event.target.dataset.filter, event.target.value);
    if (event.target.matches('[data-pair-field]')) {
      const draft = currentPairDraft();
      cachePairDraft(draft);
      window.clearTimeout(pairDraftTimer);
      pairDraftTimer = window.setTimeout(persistPairDraft, 350);
    }
    if (event.target.id === 'composer-input') {
      window.clearTimeout(draftTimer);
      const text = event.target.value;
      const localSessionId = getState().view?.activeSession?.localSessionId;
      draftTimer = window.setTimeout(() => sendIntent('session.saveDraft', { localSessionId, text }), 450);
      updateSlashPalette(text);
    }
  });
  document.addEventListener('keydown', (event) => {
    const trigger = event.target.closest('.choice-trigger');
    if (trigger && ['ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault();
      trigger.click();
      document.getElementById(`${trigger.dataset.choiceId}-menu`)?.querySelector('.choice-option:not(:disabled)')?.focus();
      return;
    }
    const option = event.target.closest('.choice-option');
    if (option && ['ArrowDown', 'ArrowUp', 'Home', 'End', 'Escape'].includes(event.key)) {
      event.preventDefault();
      event.stopPropagation();
      const menu = option.closest('.choice-menu');
      const items = Array.from(menu.querySelectorAll('.choice-option:not(:disabled)'));
      if (event.key === 'Escape') {
        menu.hidden = true;
        const owner = document.querySelector(`[aria-controls="${menu.id}"]`);
        owner?.setAttribute('aria-expanded', 'false'); owner?.focus();
      } else {
        const current = items.indexOf(option);
        const index = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
        items[index]?.focus();
      }
      return;
    }
    if (event.target.id === 'composer-input' && ['ArrowDown', 'ArrowUp'].includes(event.key) && !document.querySelector('#slash-palette')?.hidden) {
      event.preventDefault(); moveSlashSelection(event.key); return;
    }
    if (event.target.id === 'composer-input' && event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      const palette = document.querySelector('#slash-palette:not([hidden])');
      if (palette) { palette.querySelector('.slash-option.active, .slash-option')?.click(); return; }
      document.querySelector('[data-action="send"]')?.click();
    }
  });
  document.addEventListener('submit', (event) => {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));
    if (form.dataset.form === 'create') {
      const payload = Object.fromEntries(Object.entries(data).filter(([, value]) => value !== ''));
      sendIntent('session.create', payload, { pendingKey: 'create-session' }); setModal(null);
    }
    if (form.dataset.form === 'pair') { cachePairDraft(data); persistPairDraft(); sendIntent('node.pair', data, { pendingKey: 'pair-node' }); setModal(null); }
  });
  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-action="submit-create"], [data-action="submit-pair"], [data-action="confirm-config"]');
    if (!target) return;
    if (target.dataset.action === 'submit-create' || target.dataset.action === 'submit-pair') target.closest('form')?.requestSubmit();
    else { sendIntent('config.patch', { nodeId: getState().view.activeNodeId, patch: { [target.dataset.key]: target.dataset.value } }); setModal(null); }
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && getState().modal?.type === 'pair') persistPairDraft();
  });
}
