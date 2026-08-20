import { activateDialog, safeStringify } from './accessibility.js';
import { askUserDecision, askUserQuestions } from './approval-input.js';
import { sendIntent } from './bridge.js';
import { conversationSegments } from './conversation-outline.js';
import { modelDisplay, modelSuggestions } from './model-options.js';
import { openRoute } from './router.js';
import { captureScrollAnchor, restoreScrollAnchor } from './scroll-anchor.js';
import { cacheConversationOutline, cachePairDraft, dismissNotice, getState, setFilter, setFocusPanel, setModal, setNotice, toggleExpanded, update } from './state.js';
import { turnPerformanceLabels } from './turn-performance.js';

const labels = {
  console: ['控制台', '▣'], sessions: ['会话', '☷'], nodes: ['节点', '◆'],
  accounts: ['账号', '♟'], settings: ['设置', '⚙'], diagnostics: ['诊断', '⌁']
};
const connectionLabels = { offline: '离线', overlayStarting: '组网启动中', backendConnecting: '后端连接中', syncing: '同步中', online: '在线', degraded: '连接降级', error: '连接错误' };
const statusClass = (state) => ['online'].includes(state) ? 'online' : ['syncing', 'overlayStarting', 'backendConnecting', 'running'].includes(state) ? 'running' : state === 'degraded' ? 'degraded' : ['error', 'failed', 'offline'].includes(state) ? 'error' : '';
const sessionStateLabels = { localOnly: '仅本地', idle: '空闲', running: '运行中', completed: '已完成', failed: '失败', ended: '已结束' };
const approvalStateLabels = { pending: '等待审批', submitting: '提交中', allowed: '已允许', denied: '已拒绝', expired: '已过期' };
const permissionModeLabels = { plan: 'Plan', auto: 'Auto', acceptEdits: 'Accept Edits' };
const overlayStateLabels = { idle: '空闲', starting: '启动中', online: '在线', offline: '离线', error: '错误', unknown: '未知' };
const backendStateLabels = { unknown: '未知', offline: '离线', online: '在线' };
const syncStateLabels = { idle: '空闲', syncing: '同步中', current: '已同步', error: '错误' };
const brandGlyphs = Object.freeze(['划', '摸', '摆', '爽', '寄', '困', '饿', '累', '麻']);

/* Selected once when this HTML/JS process loads. Re-renders keep the same glyph. */
function chooseBrandGlyph() {
  try {
    const value = new Uint32Array(1);
    globalThis.crypto.getRandomValues(value);
    return brandGlyphs[value[0] % brandGlyphs.length];
  } catch {
    return brandGlyphs[Date.now() % brandGlyphs.length];
  }
}

const brandGlyph = chooseBrandGlyph();
const approvalAnswerDrafts = new Map();
const copyValues = new WeakMap();

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([key, value]) => {
    if (value === undefined || value === null || value === false) return;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'dataset') Object.entries(value).forEach(([name, entry]) => { if (entry !== undefined && entry !== null) node.dataset[name] = String(entry); });
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
  return h('button', { type: 'button', class: `btn ${options.class || ''}`, dataset: { action, ...(options.data || {}) }, disabled: options.disabled, 'aria-label': options.label, 'aria-expanded': options.expanded, 'aria-controls': options.controls }, text);
}

function copyButton(value, label = '复制', options = {}) {
  const control = button(label, 'copy-text', { class: options.class || 'btn-inline', label: options.ariaLabel || label });
  copyValues.set(control, String(value ?? ''));
  return control;
}

function focusButton(item, part = 'text') {
  return button('专注', 'focus-content', { class: 'btn-inline', label: '在专注阅读层查看', data: { localSeq: item.localSeq, part } });
}

function badge(text, state) {
  return h('span', { class: 'badge' }, h('span', { class: `status-dot status-${statusClass(state)}`, 'aria-hidden': 'true' }), text);
}

function brandMark(kind, label = true) {
  const claude = kind === 'claude';
  const name = claude ? 'Claude Code' : 'Codex CLI';
  const icon = claude ? './assets/brands/anthropic.svg' : './assets/brands/openai.svg';
  return h('span', { class: `provider-mark provider-${claude ? 'claude' : 'codex'}`, role: 'img', 'aria-label': name, title: name },
    h('span', { class: 'provider-glyph', 'aria-hidden': 'true' }, h('img', { src: icon, alt: '' })),
    label ? h('span', { text: name }) : null);
}

function providerLabel(kind) { return kind === 'claude' ? 'Claude Code' : 'Codex CLI'; }
function sessionStateLabel(state) { return sessionStateLabels[state] || state || '未知'; }
function isPending(state, key) { return Boolean(key && state?.submitting?.has(key)); }
function fmtCost(value) { return Number.isFinite(value) && value >= 0 ? `$${value.toFixed(value < 0.01 ? 4 : 2)}` : null; }

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
  const appearancePending = view ? isPending(getState(), 'appearance') : false;
  const nextTheme = view?.appearance?.theme === 'light' ? 'dark' : view?.appearance?.theme === 'dark' ? 'system' : 'light';
  const currentThemeLabel = view?.appearance?.theme === 'dark' ? '夜间' : view?.appearance?.theme === 'system' ? '跟随系统' : '日间';
  return h('header', { class: 'app-header' },
    h('div', { class: 'brand', 'aria-label': 'Moyu Remote' },
      h('span', { class: 'brand-mark', 'aria-hidden': 'true', text: brandGlyph }),
      h('img', { class: 'brand-icon', src: './assets/brands/moyu.svg', alt: '' }),
      h('span', { text: 'moyu' })),
    view ? h('strong', { class: 'header-route-title', text: labels[route]?.[0] || '控制台' }) : null,
    h('div', { class: 'header-status' },
      badge(connectionLabels[connection.state] || connection.state, connection.state),
      view ? button(appearancePending ? '…' : view.appearance.theme === 'dark' ? '☾' : view.appearance.theme === 'system' ? '◐' : '☀', 'header-theme', {
        class: 'btn-icon btn-quiet header-theme',
        label: `切换主题，当前${currentThemeLabel}`,
        disabled: appearancePending,
        data: { theme: nextTheme }
      }) : null)
  );
}

function nav(route) {
  return h('nav', { class: 'bottom-nav', 'aria-label': '主导航' }, Object.entries(labels).map(([key, [name, icon]]) =>
    h('button', { type: 'button', class: 'nav-item', dataset: { action: 'route', route: key }, 'aria-current': route === key ? 'page' : undefined, 'aria-label': name },
      h('span', { class: 'nav-icon', 'aria-hidden': 'true' }, icon), h('span', {}, name))));
}

function globalBanner(view, state) {
  const banner = view?.ui?.globalBanner;
  if (!banner) return null;
  const pendingKey = banner.actionIntent?.type ? `banner-${banner.actionIntent.type}` : '';
  const pending = isPending(state, pendingKey);
  return h('aside', { class: `banner banner-${banner.level}`, role: banner.level === 'error' ? 'alert' : 'status' },
    h('span', { 'aria-hidden': 'true' }, banner.level === 'error' ? '!' : 'i'), h('span', { class: 'grow', text: banner.text }),
    banner.actionLabel ? button(pending ? '处理中…' : banner.actionLabel, 'banner-action', { class: 'btn-small btn-quiet', disabled: pending }) : null);
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

const artifactIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function validArtifact(artifact) {
  return artifact && artifactIdPattern.test(artifact.artifactId || '')
    && artifact.localUrl === `/assets/artifacts/${artifact.artifactId}`;
}

function artifactGallery(artifacts = []) {
  const safe = artifacts.filter(validArtifact);
  if (!safe.length) return null;
  return h('div', { class: 'artifact-gallery', 'aria-label': '图片附件' }, safe.map((artifact) =>
    h('button', { type: 'button', class: 'artifact-thumb', dataset: { action: 'open-artifact', artifactId: artifact.artifactId }, 'aria-label': `查看图片 ${artifact.name || ''}` },
      h('img', { src: artifact.localUrl, alt: artifact.name || '会话图片', loading: 'lazy' }))));
}

function timelineItem(item, session, state) {
  const time = h('time', { datetime: item.createdAt, text: fmtTime(item.createdAt) });
  if (item.kind === 'message') {
    const turn = item.turnMeta || {};
    const usage = turn.usage || {};
    const performance = turnPerformanceLabels(turn.performance, usage);
    const replyFooter = item.role === 'assistant' ? h('footer', { class: 'reply-meta', 'aria-label': '本轮模型与用量' },
      h('span', { title: '模型', text: turn.model || session.model || 'CLI 默认模型' }),
      h('span', { title: '推理深度', text: `深度 ${turn.effort || session.effort || '默认'}` }),
      h('span', { title: '输入 token', text: `入 ${fmtTokens(usage.inputTokens)}` }),
      h('span', { title: '输出 token', text: `出 ${fmtTokens(usage.outputTokens)}` }),
      h('span', { title: '缓存读取 token', text: `缓存读 ${fmtTokens(usage.cacheReadTokens)}` }),
      h('span', { title: '缓存写入 token', text: `缓存写 ${fmtTokens(usage.cacheWriteTokens)}` }),
      performance.duration ? h('span', { class: 'performance-chip', title: 'PC 后端从回合开始到完成的端到端观测耗时', text: `耗时 ${performance.duration}` }) : null,
      performance.speed ? h('span', { class: 'performance-chip', title: '回合平均输出速度；包含排队、CLI 启动、网络、工具和审批等待', text: `回合均速 ${performance.speed}` }) : null) : null;
    const cost = fmtCost(turn.costUsd);
    if (cost && replyFooter) replyFooter.append(h('span', { title: '本轮费用（Host 提供）', text: `费用 ${cost}` }));
    const systemKey = `system-${item.localSeq}`;
    const systemExpanded = item.role !== 'system' || state.expandedItems.has(systemKey);
    return h('article', { class: `timeline-item message-card ${item.streaming ? 'streaming' : ''} ${item.role === 'system' ? 'operational-item' : ''}`, dataset: { role: item.role, localSeq: item.localSeq }, 'aria-live': item.streaming ? 'polite' : undefined, 'aria-busy': item.streaming ? 'true' : undefined },
      h('div', { class: 'timeline-head' }, item.role === 'assistant' ? brandMark(session.kind) : h('span', {}, item.role === 'user' ? '你' : '系统'), h('div', { class: 'row' }, item.streaming ? h('span', { class: 'streaming-label', text: '正在回复' }) : null, time, item.text ? copyButton(item.text, '复制', { ariaLabel: '复制消息' }) : null, item.text ? focusButton(item) : null)),
      item.role === 'system' ? button(systemExpanded ? '收起系统记录' : '展开系统记录', 'toggle-output', { class: 'btn-small btn-quiet fold-toggle', data: { id: systemKey } }) : null,
      item.text && systemExpanded ? richText(item.text) : null, systemExpanded ? artifactGallery(item.artifacts) : null, replyFooter);
  }
  if (item.kind === 'thinking') {
    const key = `thinking-${item.localSeq}`;
    const expanded = item.streaming || state.expandedItems.has(key);
    return h('article', { class: `timeline-item thinking operational-item ${item.streaming ? 'streaming' : ''}`, dataset: { localSeq: item.localSeq }, 'aria-live': item.streaming ? 'polite' : undefined },
      h('div', { class: 'timeline-head' }, h('span', {}, item.streaming ? '正在思考' : '思考过程'), h('div', { class: 'row' }, time, expanded ? copyButton(item.text, '复制') : null, expanded ? focusButton(item) : null)),
      item.streaming ? null : button(expanded ? '收起过程' : '展开过程', 'toggle-output', { class: 'btn-small btn-quiet fold-toggle', data: { id: key } }),
      expanded ? h('p', { class: 'timeline-text', dataset: { selectionScope: 'thinking' }, text: item.text }) : null);
  }
  if (item.kind === 'tool') {
    const output = item.output || (item.state === 'running' ? '等待工具输出…' : '无输出');
    const expanded = item.state === 'running' || item.state === 'error' || state.expandedItems.has(`tool-${item.localSeq}`);
    return h('article', { class: 'timeline-item tool-card', dataset: { state: item.state, localSeq: item.localSeq }, 'aria-live': item.state === 'running' ? 'polite' : undefined, 'aria-busy': item.state === 'running' ? 'true' : undefined },
      h('div', { class: 'tool-head' }, h('strong', { text: `工具 · ${item.tool}` }), h('div', { class: 'row' }, badge(item.state === 'running' ? '运行中' : item.state === 'done' ? '已完成' : '失败', item.state), copyButton(output, '复制输出'), focusButton(item, 'output'))),
      expanded && item.input !== undefined ? h('div', { class: 'copy-block' }, h('div', { class: 'copy-block-head' }, h('small', { text: '输入' }), copyButton(safeStringify(item.input), '复制输入')), h('pre', { class: 'tool-output', dataset: { selectionScope: 'tool-input' }, text: safeStringify(item.input) })) : null,
      expanded ? h('pre', { class: 'tool-output expanded', dataset: { selectionScope: 'tool-output' }, text: output }) : null,
      expanded ? artifactGallery(item.artifacts) : null,
      item.state === 'running' || item.state === 'error' ? null : button(expanded ? '收起工具详情' : '展开工具详情', 'toggle-output', { class: 'btn-small btn-quiet fold-toggle', data: { id: `tool-${item.localSeq}` } }));
  }
  if (item.kind === 'approval') return approvalCard(item, session);
  if (item.kind === 'usage') {
    const cost = fmtCost(item.costUsd);
    const key = `usage-${item.localSeq}`;
    const expanded = state.expandedItems.has(key);
    return h('article', { class: 'timeline-item usage-item operational-item', dataset: { localSeq: item.localSeq } },
      h('div', { class: 'timeline-head' }, h('strong', {}, '本轮用量'), time),
      button(expanded ? '收起用量' : '展开用量', 'toggle-output', { class: 'btn-small btn-quiet fold-toggle', data: { id: key } }),
      expanded ? h('div', { class: 'row usage-summary' },
        h('span', { text: `输入 ${fmtTokens(item.usage?.inputTokens)}` }),
        h('span', { text: `输出 ${fmtTokens(item.usage?.outputTokens)}` }),
        h('span', { text: `缓存读 ${fmtTokens(item.usage?.cacheReadTokens)}` }),
        h('span', { text: `缓存写 ${fmtTokens(item.usage?.cacheWriteTokens)}` }),
        cost ? h('strong', { text: `费用 ${cost}` }) : null) : null);
  }
  if (item.kind === 'error') {
    const errorText = `${item.error.summary || '本轮失败'}\n${item.error.code || ''}`.trim();
    return h('article', { class: 'timeline-item error-item', dataset: { localSeq: item.localSeq }, role: 'alert' },
      h('div', { class: 'timeline-head' }, h('strong', {}, '本轮失败'), h('div', { class: 'row' }, time, copyButton(errorText), focusButton(item, 'error'))), h('div', { dataset: { selectionScope: 'error' } }, h('p', { text: item.error.summary }), h('code', { text: item.error.code })));
  }
  return null;
}

function inlineText(value) {
  const fragment = document.createDocumentFragment();
  String(value || '').split(/(`[^`\n]+`)/g).forEach((part) => {
    if (part.startsWith('`') && part.endsWith('`')) fragment.append(h('code', { text: part.slice(1, -1) }));
    else fragment.append(document.createTextNode(part));
  });
  return fragment;
}

/* A deliberately small, local-only Markdown reader. Raw HTML and active links are
 * always rendered as text; this adds mobile readability without adding a parser or
 * a navigation/security surface. */
function richText(value) {
  const root = h('div', { class: 'timeline-text rich-text', dataset: { selectionScope: 'message' } });
  const lines = String(value || '').split('\n');
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (line.startsWith('```')) {
      const language = line.slice(3).trim();
      const code = [];
      index += 1;
      while (index < lines.length && !lines[index].startsWith('```')) code.push(lines[index++]);
      root.append(h('div', { class: 'code-block' }, h('div', { class: 'code-block-head' }, h('span', { text: language || '代码' }), copyButton(code.join('\n'))), h('pre', { dataset: { selectionScope: 'code' } }, h('code', { text: code.join('\n') }))));
    } else if (/^#{1,3}\s/.test(line)) {
      const level = Math.min(4, line.match(/^#+/)[0].length + 1);
      root.append(h(`h${level}`, {}, inlineText(line.replace(/^#{1,3}\s+/, ''))));
    } else if (/^>\s?/.test(line)) root.append(h('blockquote', {}, inlineText(line.replace(/^>\s?/, ''))));
    else if (/^(?:[-*]|\d+\.)\s+/.test(line)) {
      const ordered = /^\d+\./.test(line);
      const list = h(ordered ? 'ol' : 'ul');
      while (index < lines.length && (ordered ? /^\d+\.\s+/.test(lines[index]) : /^(?:[-*])\s+/.test(lines[index]))) {
        list.append(h('li', {}, inlineText(lines[index].replace(/^(?:[-*]|\d+\.)\s+/, ''))));
        index += 1;
      }
      root.append(list);
      continue;
    } else if (line.trim()) root.append(h('p', {}, inlineText(line)));
    index += 1;
  }
  return root;
}

function conversationItems(messages = []) {
  const result = [];
  let lastAssistant = -1;
  messages.forEach((item) => {
    if (item.kind === 'usage') {
      if (lastAssistant >= 0) result[lastAssistant] = { ...result[lastAssistant], turnMeta: item };
      else result.push(item);
      return;
    }
    result.push(item);
    if (item.kind === 'message' && item.role === 'user') lastAssistant = -1;
    if (item.kind === 'message' && item.role === 'assistant') lastAssistant = result.length - 1;
  });
  return result;
}

function latestTurnMeta(messages = []) {
  for (let index = messages.length - 1; index >= 0; index -= 1) if (messages[index]?.kind === 'usage') return messages[index];
  return null;
}

function approvalChoiceLabel(approval, choice) {
  if (approval.tool === 'ExitPlanMode') return { allow: '确认退出计划模式', deny: '拒绝退出', cancel: '取消' }[choice] || choice;
  return { allow: '允许一次', allow_session: '本会话始终允许此工具类型', deny: '拒绝', cancel: '取消' }[choice] || choice;
}

function askUserQuestionForm(approval, questions, session, submitting, online) {
  const draft = approvalAnswerDrafts.get(approval.approvalId) || {};
  const fields = questions.map((question, index) => {
    const name = `answer-${index}`;
    const selected = draft[index] || [];
    const controls = question.options.length ? question.options.map((option) => h('label', { class: 'approval-option' },
      h('input', { class: 'approval-option-input', type: question.multiSelect ? 'checkbox' : 'radio', name, value: option.label, checked: selected.includes(option.label), disabled: submitting || !online, dataset: { approvalAnswer: true, questionIndex: index } }),
      h('span', { class: 'approval-option-mark', 'aria-hidden': 'true' }),
      h('span', { class: 'approval-option-copy' }, h('strong', { text: option.label }), option.description ? h('small', { text: option.description }) : null)))
      : [h('label', { class: 'field approval-free-answer' }, h('span', { text: '回答' }), h('input', { name, value: selected[0] || '', maxlength: '2000', placeholder: '输入回答', disabled: submitting || !online, dataset: { approvalAnswer: true, questionIndex: index } }))];
    return h('fieldset', { class: 'approval-question' },
      h('legend', {}, question.header ? h('span', { class: 'approval-question-header', text: question.header }) : null, h('strong', { text: question.question }), question.multiSelect ? h('small', { text: '可多选' }) : null),
      h('div', { class: 'approval-options' }, controls));
  });
  const secondary = (approval.choices || []).filter((choice) => choice === 'deny' || choice === 'cancel').map((choice) =>
    button(approvalChoiceLabel(approval, choice), 'approval', { class: choice === 'deny' ? 'btn-danger' : 'btn-quiet', disabled: submitting || !online,
      data: { approvalId: approval.approvalId, sessionId: session.localSessionId, decision: choice, tool: approval.tool } }));
  return h('form', { class: 'approval-question-form', dataset: { form: 'approval-questions', approvalId: approval.approvalId, sessionId: session.localSessionId } },
    h('div', { class: 'approval-question-list' }, fields),
    h('div', { class: 'dialog-actions' }, secondary, h('button', { type: 'submit', class: 'btn btn-primary', disabled: submitting || !online, text: submitting ? '提交中…' : '提交回答' })));
}

function approvalCard(item, session) {
  const approval = item.approval;
  const foldKey = `approval-${item.localSeq}`;
  const resolved = !['pending', 'submitting'].includes(approval.state);
  const expanded = !resolved || getState().expandedItems.has(foldKey);
  const online = getState().view?.connection?.state === 'online';
  const submitting = approval.state === 'submitting' || getState().submitting.has(`approval-${approval.approvalId}`);
  const expired = approval.state === 'expired';
  const askTool = approval.kind === 'userInput' && approval.tool === 'AskUserQuestion';
  const questions = askTool ? askUserQuestions(approval) : null;
  const card = h('article', { class: 'timeline-item approval-card', dataset: { state: approval.state, localSeq: item.localSeq }, 'aria-live': 'assertive' },
    h('div', { class: 'timeline-head' }, h('strong', {}, expired ? '审批已过期' : '需要你的审批'), badge(approvalStateLabels[approval.state] || approval.state, approval.state === 'pending' ? 'running' : approval.state)),
    h('h3', { text: approval.summary }),
    resolved ? button(expanded ? '收起审批详情' : '展开审批详情', 'toggle-output', { class: 'btn-small btn-quiet fold-toggle', data: { id: foldKey } }) : null,
    expanded && approval.tool ? h('p', {}, '工具：', h('code', { text: approval.tool })) : null,
    expanded && approval.input !== undefined && !questions ? h('div', { class: 'copy-block' },
      h('div', { class: 'copy-block-head' }, h('small', { text: '审批输入' }), copyButton(safeStringify(approval.input)), focusButton(item, 'approval')),
      h('pre', { class: 'tool-output', dataset: { selectionScope: 'approval' }, text: safeStringify(approval.input) })) : null);
  if (expired && expanded) card.append(h('p', {}, '审批已过期，后端可能已自动拒绝。'));
  if (askTool && !questions) card.append(h('div', { class: 'banner banner-error', text: '问题结构无效。为避免提交错误回答，只能拒绝或取消。' }));
  if (approval.state === 'pending' || submitting) {
    if (questions) card.append(askUserQuestionForm(approval, questions, session, submitting, online));
    else card.append(h('div', { class: 'row' }, (approval.choices || []).filter((choice) => !askTool || choice === 'deny' || choice === 'cancel').map((choice) =>
      button(approvalChoiceLabel(approval, choice), 'approval', {
      class: choice === 'deny' ? 'btn-danger' : choice === 'allow_session' ? 'btn-cyan' : 'btn-primary',
      disabled: submitting || !online,
        data: { approvalId: approval.approvalId, sessionId: session.localSessionId, decision: choice, tool: approval.tool || '此工具类型' }
      }))));
  } else approvalAnswerDrafts.delete(approval.approvalId);
  if (!online && approval.state === 'pending') card.append(h('p', { class: 'muted', text: '离线时不能提交审批。' }));
  return card;
}

function cacheApprovalQuestionAnswers(form) {
  const draft = {};
  form.querySelectorAll('[data-approval-answer]').forEach((control) => {
    if ((control.type === 'checkbox' || control.type === 'radio') && !control.checked) return;
    const index = Number(control.dataset.questionIndex);
    if (!Number.isInteger(index)) return;
    const value = String(control.value || '');
    if (value.trim()) (draft[index] ||= []).push(value);
  });
  approvalAnswerDrafts.set(form.dataset.approvalId, draft);
  return draft;
}

function profileLabel(view, kind, profileId) {
  const profiles = view.accounts?.adapters?.find((adapter) => adapter.adapter === kind)?.profiles || [];
  const profile = profiles.find((item) => item.profileId === profileId) || profiles.find((item) => item.active);
  return profile?.displayName || profileId || '原生默认';
}

function nodeLabel(view, nodeId) {
  return view.nodes.find((node) => node.nodeId === nodeId)?.displayName || nodeId || '暂无节点';
}

function quickAdapterCard(view, state, adapter) {
  const profiles = view.accounts?.adapters?.find((item) => item.adapter === adapter.adapter)?.profiles || [];
  const profile = profiles.find((item) => item.active) || profiles[0];
  const model = adapter.effectiveModel || profile?.effectiveModel || (view.config?.defaultAdapter === adapter.adapter ? view.config?.effectiveModel || view.config?.model : '') || 'CLI 默认模型';
  const disabled = view.connection.state !== 'online' || !view.nodes.some((node) => node.configured) || isPending(state, 'create-session');
  return h('article', { class: `console-adapter-card ${adapter.adapter}` },
    h('div', { class: 'row space-between' }, brandMark(adapter.adapter), badge(adapter.available ? '可用' : '不可用', adapter.available ? 'online' : 'error')),
    h('div', { class: 'console-adapter-meta' },
      h('span', { text: `Profile · ${profile?.displayName || '原生默认'}` }),
      h('span', { text: `Model · ${model}` })),
    button(isPending(state, 'create-session') ? '创建中…' : `使用 ${providerLabel(adapter.adapter)} 新建`, 'open-create', {
      class: adapter.adapter === 'claude' ? 'btn-primary' : 'btn-cyan', disabled: disabled || !adapter.available, data: { kind: adapter.adapter }
    }));
}

function consolePage(view, state) {
  const session = view.activeSession;
  const sessionMessages = session?.messages?.filter((item) => item.kind === 'message') || [];
  const activeNode = view.nodes.find((node) => node.nodeId === view.activeNodeId);
  const adapters = (view.server?.adapters || []).filter((adapter) => ['claude', 'codex'].includes(adapter.adapter));
  const page = h('main', { id: 'main-content', class: 'page console-page', tabindex: '-1' }, connectionStrip(view));
  page.append(pageHeading('控制台', '连接状态不阻塞本地内容', [button(isPending(state, 'create-session') ? '创建中…' : '＋ 新建会话', 'open-create', { class: 'btn-primary', disabled: isPending(state, 'create-session') })]));
  const primary = h('div', { class: 'timeline-panel' });
  if (session) {
    primary.append(h('section', { class: 'card active-session-card' },
      h('div', { class: 'row space-between' }, brandMark(session.kind), badge(sessionStateLabel(session.state), session.state)),
      h('h2', { text: session.title }), h('p', { class: 'muted', text: sessionMessages.length ? sessionMessages[sessionMessages.length - 1].text : '本地会话记录可用' }),
      h('div', { class: 'meta-grid active-session-meta' },
        meta('节点', nodeLabel(view, session.nodeId)), meta('平台', providerLabel(session.kind)),
        meta('Profile', profileLabel(view, session.kind, session.profileId)), meta('Model', session.model || 'CLI 默认模型'),
        meta('工作目录', session.cwd || 'CLI 默认目录'), meta('状态', sessionStateLabel(session.state))),
      h('div', { class: 'row space-between active-session-actions' },
        h('span', { class: 'muted', text: session.remoteSessionId ? '远端热会话' : 'Android 本地历史' }),
        h('div', { class: 'row' },
          button(isPending(state, `diff-${session.localSessionId}`) ? '读取中…' : 'Diff', 'open-diff', { class: 'btn-quiet', disabled: isPending(state, `diff-${session.localSessionId}`) || (!session.diff && (view.connection.state !== 'online' || !session.remoteSessionId)), data: { sessionId: session.localSessionId } }),
          button(isPending(state, `open-${session.localSessionId}`) ? '打开中…' : '专注会话', 'open-session', { class: 'btn-cyan', disabled: isPending(state, `open-${session.localSessionId}`), data: { sessionId: session.localSessionId } })))));
  }
  else primary.append(h('section', { class: 'empty-state' }, h('div', { class: 'empty-icon', 'aria-hidden': 'true' }, '⌁'), h('h2', {}, view.nodes.length ? '还没有打开会话' : '先保留你的思路'), h('p', {}, view.nodes.length ? '从本地会话列表打开，或创建一个新会话。' : '尚未配置节点。本地历史仍可查看，节点配置不会阻塞控制台。'), view.nodes.length ? button('查看会话', 'route', { class: 'btn-primary', data: { route: 'sessions' } }) : button('前往节点', 'route', { class: 'btn-cyan', data: { route: 'nodes' } })));
  const sidebar = h('aside', { class: 'console-sidebar stack', 'aria-label': '会话与节点快捷操作' },
    h('section', { class: 'card console-node-card' }, h('h2', {}, '当前节点'), h('strong', { text: activeNode?.displayName || '未选择节点' }), h('p', { class: 'mono muted', text: activeNode?.relayNode || '本地历史模式' }), badge(connectionLabels[view.connection.state] || view.connection.state, view.connection.state)),
    h('section', { class: 'console-adapters', 'aria-label': '平台、Profile 与 Model' }, adapters.length ? adapters.map((adapter) => quickAdapterCard(view, state, adapter)) : h('p', { class: 'muted', text: '等待 Host 提供平台能力' })),
    h('section', { class: 'dashboard-grid' },
      h('article', { class: 'card' }, h('h2', {}, '本地会话'), h('strong', { class: 'dashboard-number', text: String(view.sessions.length) }), h('p', { class: 'muted', text: '离线仍可查看' })),
      h('article', { class: 'card' }, h('h2', {}, '节点'), h('strong', { class: 'dashboard-number', text: String(view.nodes.length) }), h('p', { class: 'muted', text: view.connection.summary }))));
  page.append(h('section', { class: 'console-grid' }, primary, sidebar));
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
  const segments = conversationSegments(conversationItems(session.messages));
  const sessionInfoId = `session-info-${session.localSessionId}`;
  const sessionInfoOpen = state.expandedItems.has(sessionInfoId);
  return h('main', { id: 'main-content', class: 'conversation-page', tabindex: '-1' },
    h('header', { class: 'conversation-header' },
      button('←', 'route', { class: 'btn-icon btn-quiet conversation-back', label: '返回会话列表', data: { route: 'sessions' } }),
      h('div', { class: 'conversation-title grow' }, h('strong', { text: session.title })),
      button('目录', 'toggle-outline', { class: 'btn-small btn-quiet conversation-outline-toggle', label: '打开会话分段目录', data: { open: String(!state.conversationOutlineOpen) } }),
      button(isPending(state, `diff-${session.localSessionId}`) ? '读取中' : 'Diff', 'open-diff', { class: 'btn-small btn-cyan conversation-diff', disabled: isPending(state, `diff-${session.localSessionId}`) || (!session.diff && (view.connection.state !== 'online' || !session.remoteSessionId)), data: { sessionId: session.localSessionId } })),
    h('section', { class: `session-summary ${sessionInfoOpen ? 'expanded' : ''}`, 'aria-label': '当前会话摘要' },
      h('div', { class: 'session-summary-row' },
        brandMark(session.kind, false),
        badge(connectionLabels[view.connection.state] || view.connection.state, view.connection.state),
        h('span', { class: 'session-summary-node', text: nodeLabel(view, session.nodeId), title: nodeLabel(view, session.nodeId) }),
        button(sessionInfoOpen ? '收起信息' : '会话信息', 'toggle-session-info', { class: 'btn-small btn-quiet session-info-toggle', data: { id: sessionInfoId }, label: `${sessionInfoOpen ? '收起' : '展开'}当前会话详细信息`, expanded: String(sessionInfoOpen), controls: sessionInfoId })),
      h('div', { id: sessionInfoId, class: 'session-info-panel', hidden: !sessionInfoOpen },
        h('div', { class: 'session-info-grid' },
          meta('状态', sessionStateLabel(session.state)), meta('工作目录', session.cwd || 'CLI 默认目录'),
          meta('Profile', profileLabel(view, session.kind, session.profileId)), meta('请求模型', session.model || 'CLI 默认模型'),
          session.kind === 'claude' ? meta('模式', permissionModeLabels[session.permissionMode] || 'Accept Edits') : null,
          turn?.model && turn.model !== session.model ? meta('本轮实际模型', turn.model) : null),
        h('section', { class: 'context-strip', 'aria-label': '上下文窗口用量' },
          h('div', { class: 'row space-between' }, h('strong', { text: `上下文输入 ${fmtTokens(contextInput)}` }), h('span', { class: 'muted', text: contextPercent == null ? '窗口上限由 CLI 管理' : `${fmtTokens(contextLimit)} · ${contextPercent}%` })),
          contextPercent == null ? null : h('progress', { class: 'context-meter', max: '100', value: String(contextPercent), 'aria-label': `上下文窗口已使用 ${contextPercent}%` })))),
    h('section', { class: 'conversation-scroll' },
      session.nativeSessionId && !session.remoteSessionId && !session.nativeCacheComplete ? h('aside', { class: 'banner banner-warning native-cache-note' },
        view.connection.state === 'online' ? '正在读取并缓存这份原生 CLI 历史。' : `当前离线：本机仅缓存 ${session.nativeCachedMessages || 0}/${session.nativeMessageCount || 0} 条；连接该节点后打开会话可补全。`) : null,
      session.hasOlderLocalMessages ? button(isPending(state, `older-${session.localSessionId}`) ? '加载中…' : '加载更早本地记录', 'load-older', { class: 'btn-quiet', disabled: isPending(state, `older-${session.localSessionId}`), data: { sessionId: session.localSessionId } }) : null,
    h('div', { class: 'conversation-timeline', 'aria-label': '会话记录' }, segments.map((segment) =>
        h('section', { id: segment.id, class: 'conversation-segment', dataset: { segmentId: segment.id } },
          h('div', { class: 'timeline segment-timeline' }, segment.items.map((item) => timelineItem(item, session, state))))))),
    conversationOutline(view, segments, state),
    button('↓ 最新', 'jump-latest', { class: 'jump-to-latest', label: '快速回到会话底部' }),
    composer(session, view.connection.state, state));
}

function sessionGroups(sessions = [], nowValue) {
  const now = new Date(nowValue || Date.now());
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const groups = [['今天', []], ['昨天', []], ['近 7 天', []], ['更早', []]];
  sessions.forEach((session) => {
    const updated = new Date(session.updatedAt || 0);
    const updatedDay = new Date(updated.getFullYear(), updated.getMonth(), updated.getDate()).getTime();
    const days = Math.max(0, Math.floor((start - updatedDay) / 86400000));
    const index = days === 0 ? 0 : days === 1 ? 1 : days < 7 ? 2 : 3;
    groups[index][1].push(session);
  });
  return groups.filter(([, items]) => items.length);
}

function conversationOutline(view, segments, state) {
  const open = state.conversationOutlineOpen;
  const tab = state.conversationSidebarTab || 'outline';
  return h('div', { class: `conversation-outline-shell ${open ? 'open' : ''}` },
    h('button', { type: 'button', class: 'outline-scrim', dataset: { action: 'toggle-outline', open: 'false' }, 'aria-label': '关闭会话目录', tabindex: open ? '0' : '-1' }),
    h('aside', { class: 'conversation-outline', 'aria-label': '会话分段目录', 'aria-hidden': String(!open) },
      h('div', { class: 'outline-heading' }, h('div', {}, h('strong', {}, '快速导航'), h('span', { class: 'muted', text: tab === 'outline' ? `${segments.length} 段` : `${view.sessions.length} 个会话` })), button('×', 'toggle-outline', { class: 'btn-icon btn-quiet', label: '关闭会话目录', data: { open: 'false' } })),
      h('div', { class: 'outline-tabs', role: 'tablist', 'aria-label': '快速导航类型' },
        h('button', { type: 'button', role: 'tab', 'aria-selected': String(tab === 'outline'), dataset: { action: 'sidebar-tab', tab: 'outline' } }, '本页目录'),
        h('button', { type: 'button', role: 'tab', 'aria-selected': String(tab === 'sessions'), dataset: { action: 'sidebar-tab', tab: 'sessions' } }, '会话')),
      tab === 'outline' ? h('nav', { class: 'outline-list', 'aria-label': '按会话摘要跳转' }, segments.map((segment) =>
        h('button', { type: 'button', class: 'outline-item', dataset: { action: 'jump-segment', segmentId: segment.id } },
          h('span', { class: 'outline-index', text: String(segment.ordinal).padStart(2, '0') }),
          h('span', { class: 'outline-copy' }, h('strong', { text: segment.title }), h('small', { text: `${segment.items.length} 项 · ${fmtTime(segment.createdAt)}` })))))
        : h('div', { class: 'sidebar-session-groups' }, sessionGroups(view.sessions, view.now).map(([label, sessions]) =>
          h('section', { class: 'sidebar-session-group' }, h('h3', { text: label }), sessions.map((session) =>
            h('button', { type: 'button', class: `sidebar-session-item ${session.localSessionId === view.activeLocalSessionId ? 'active' : ''}`, dataset: { action: 'open-session', sessionId: session.localSessionId } },
              brandMark(session.kind, false), h('span', { class: 'outline-copy' }, h('strong', { text: session.title }), h('small', { text: session.preview || modelDisplay(session.model) })))))))));
}

function setConversationOutlineOpen(open) {
  const scrollTop = window.scrollY || document.scrollingElement?.scrollTop || conversationScroller().scrollTop;
  cacheConversationOutline(open, scrollTop);
  const shell = document.querySelector('.conversation-outline-shell');
  shell?.classList.toggle('open', open);
  shell?.querySelector('.conversation-outline')?.setAttribute('aria-hidden', String(!open));
  const scrim = shell?.querySelector('.outline-scrim');
  if (scrim) scrim.tabIndex = open ? 0 : -1;
  const headerToggle = document.querySelector('.conversation-header [data-action="toggle-outline"]');
  if (headerToggle) headerToggle.dataset.open = String(!open);
  document.dispatchEvent(new Event('moyu:interaction-surface-change'));
}

let draftTimer = 0;
let pairDraftTimer = 0;
function composer(session, connectionState, state) {
  const offline = connectionState !== 'online';
  const configurationLocked = offline || session.state === 'running';
  const sending = state.submitting.has(`send-${session.localSessionId}`);
  const interrupting = state.submitting.has(`interrupt-${session.localSessionId}`);
  const pickingAttachment = state.submitting.has(`attachment-${session.localSessionId}`);
  const attachments = (session.composerAttachments || []).filter(validArtifact);
  return h('section', { class: 'composer-dock', 'aria-label': '消息输入区' }, h('div', { class: 'composer' },
    offline ? h('div', { class: 'banner banner-warning' }, '当前离线：草稿会保存，但不会进入发送队列。恢复在线后请重新确认发送。') : null,
    h('div', { id: 'slash-palette', class: 'slash-palette', role: 'listbox', 'aria-label': '斜杠命令', hidden: true }),
    attachments.length ? h('div', { class: 'composer-attachments', 'aria-label': '待发送图片' }, attachments.map((artifact) =>
      h('span', { class: 'composer-attachment' }, h('img', { src: artifact.localUrl, alt: '' }), h('span', { text: artifact.name || '图片' }),
        button(isPending(state, `remove-attachment-${artifact.artifactId}`) ? '…' : '×', 'remove-attachment', { class: 'btn-icon btn-quiet', label: `移除 ${artifact.name || '图片'}`, disabled: isPending(state, `remove-attachment-${artifact.artifactId}`), data: { sessionId: session.localSessionId, artifactId: artifact.artifactId } })))) : null,
    h('div', { class: 'composer-configuration', 'aria-label': '当前会话配置' },
      (session.permissionModes || []).length ? choiceField('模式', 'permissionMode', session.permissionModes.map((mode) => [mode, permissionModeLabels[mode] || mode, configurationLocked]), session.permissionMode || 'acceptEdits', 'session', { class: 'composer-choice', hiddenLabel: true }) : null,
      (session.permissionModes || []).length ? button(`模型 · ${modelDisplay(session.model)}`, 'open-session-model', { class: 'btn-small btn-quiet composer-model', disabled: configurationLocked || isPending(state, `model-${session.localSessionId}`), label: '切换当前会话模型' }) : null),
    h('textarea', { id: 'composer-input', rows: '2', placeholder: '输入消息，或输入 / 查看命令', 'aria-label': '消息', 'aria-controls': 'slash-palette', value: session.composerDraft || '' }),
    h('div', { class: 'composer-actions' },
      button(pickingAttachment ? '选择中…' : '＋ 图片', 'pick-attachment', { class: 'btn-quiet', disabled: offline || attachments.length >= 4 || pickingAttachment, data: { sessionId: session.localSessionId } }),
      button('专注编辑', 'focus-draft', { class: 'btn-quiet', data: { sessionId: session.localSessionId } }),
      h('span', { class: 'muted composer-status', text: attachments.length ? `${attachments.length}/4 张` : '草稿自动保存' }),
      session.canInterrupt ? button(interrupting ? '中断中…' : '中断', 'interrupt', { class: 'btn-danger', disabled: interrupting, data: { sessionId: session.localSessionId } }) : null,
      button(sending ? '发送中…' : '发送', 'send', { class: 'btn-primary', disabled: (!session.canSend && !offline) || sending, data: { sessionId: session.localSessionId, offline } }))));
}

function sessionsPage(view, state) {
  const f = state.filters;
  const sessions = view.sessions.filter((s) => (f.query === '' || `${s.title} ${s.preview || ''}`.toLowerCase().includes(f.query.toLowerCase())) && (f.node === 'all' || s.nodeId === f.node) && (f.kind === 'all' || s.kind === f.kind) && (f.state === 'all' || s.state === f.state));
  const loading = view.connection.state === 'syncing' && view.sessions.length === 0;
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('本地会话', '离线也能查看的工作记录', [button(isPending(state, 'create-session') ? '创建中…' : '＋ 新建', 'open-create', { class: 'btn-primary', disabled: isPending(state, 'create-session') })]),
    h('section', { class: 'filter-grid', 'aria-label': '会话筛选' },
      h('input', { class: 'search', type: 'search', value: f.query, placeholder: '搜索标题或内容', 'aria-label': '搜索会话', dataset: { filter: 'query' } }),
      selectField('node', '节点', [['all', '全部节点'], ...view.nodes.map((n) => [n.nodeId, n.displayName])], f.node),
      selectField('kind', '平台', [['all', '全部平台'], ['claude', 'Claude'], ['codex', 'Codex']], f.kind),
      selectField('state', '状态', [['all', '全部状态'], ['running', '运行中'], ['idle', '空闲'], ['completed', '已完成'], ['failed', '失败'], ['ended', '已结束'], ['localOnly', '仅本地']], f.state)),
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
  const state = getState();
  const remoteHot = Boolean(session.remoteSessionId) && session.state === 'running';
  const opening = isPending(state, `open-${session.localSessionId}`);
  const deleting = isPending(state, `delete-${session.localSessionId}`);
  return h('article', { class: `card session-card ${session.unread ? 'unread' : ''}` },
    h('div', { class: 'row space-between' }, brandMark(session.kind), h('div', { class: 'row' }, session.state === 'localOnly' ? h('span', { class: 'badge accent-yellow', text: '仅本地' }) : null, remoteHot ? h('span', { class: 'badge accent-lime', text: '远端热会话' }) : null, session.unread ? h('span', { class: 'badge accent-pink', text: `${session.unread} 未读` }) : null, badge(sessionStateLabel(session.state), session.state))),
    h('div', {}, h('h2', { text: session.title }), h('p', { class: 'muted', text: session.preview || '暂无预览' })),
    h('div', { class: 'row space-between' }, h('time', { class: 'muted', datetime: session.updatedAt, title: fmtTime(session.updatedAt), text: relTime(session.updatedAt, nowValue) }), h('div', { class: 'row' }, button(opening ? '打开中…' : '打开', 'open-session', { class: 'btn-small btn-cyan', disabled: opening, data: { sessionId: session.localSessionId } }), button(deleting ? '删除中…' : '删除', 'confirm-delete-session', { class: 'btn-small btn-danger', disabled: deleting, data: { sessionId: session.localSessionId, title: session.title } }))));
}

function nodesPage(view, state) {
  const pairing = isPending(state, 'pair-node');
  const openingManualSetup = isPending(state, 'manual-setup');
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('节点', '管理你的 PC 连接', [button(pairing ? '配对中…' : '配对节点', 'open-pair', { class: 'btn-primary', disabled: pairing })]),
    !view.nodes.length ? h('section', { class: 'empty-state' }, h('div', { class: 'empty-icon' }, '◆'), h('h2', {}, '还没有节点'), h('p', {}, '配对信息会交给 Android 原生层处理，敏感凭据不会进入此页面。'), button(pairing ? '配对中…' : '开始配对', 'open-pair', { class: 'btn-primary', disabled: pairing }), button(openingManualSetup ? '打开中…' : '原生手动配置', 'manual-setup', { class: 'btn-quiet', disabled: openingManualSetup })) :
      h('section', { class: 'node-list' }, view.nodes.map((node) => h('article', { class: `card node-card ${node.active ? 'active' : ''}` },
        h('div', { class: 'row space-between' }, h('h2', { text: node.displayName }), node.active ? h('span', { class: 'badge accent-cyan', text: '当前节点' }) : null),
        h('p', { class: 'mono muted', text: node.relayNode }),
        h('div', { class: 'row' }, badge(`组网 ${overlayStateLabels[node.overlayState] || node.overlayState}`, node.overlayState), badge(`后端 ${backendStateLabels[node.backendState] || node.backendState}`, node.backendState), badge(`同步 ${syncStateLabels[node.syncState] || node.syncState}`, node.syncState)),
        h('div', { class: 'row' },
          badge(node.peerConnected ? 'PC 组网节点已连接' : 'PC 组网节点未确认', node.peerConnected ? 'online' : 'unknown'),
          badge(`链路 ${node.linkMode === 'p2p' ? 'P2P' : node.linkMode === 'relay' ? 'Relay' : '未知'}`, node.linkMode === 'p2p' ? 'online' : node.linkMode === 'relay' ? 'degraded' : 'unknown')),
        h('p', {}, `凭据：${node.secretState.token && node.secretState.networkSecret ? '已配置' : '缺少配置'} · 链路观测 ${fmtTime(node.linkObservedAt)} · Relay ${metric(node.relayLatencyMs, 'ms', '暂无数据')}${node.relayLatencyReliable === false ? '（参考值）' : ''}`),
        h('div', { class: 'row' }, node.backendState === 'online'
          ? button(isPending(state, `node-${node.nodeId}`) ? '断开中…' : '断开', 'node-disconnect', { disabled: isPending(state, `node-${node.nodeId}`), data: { nodeId: node.nodeId } })
          : button(isPending(state, `node-${node.nodeId}`) ? '连接中…' : '连接', 'node-connect', { class: 'btn-primary', disabled: !node.configured || isPending(state, `node-${node.nodeId}`), data: { nodeId: node.nodeId } }),
        button(isPending(state, `diagnose-${node.nodeId}`) ? '诊断中…' : '诊断', 'node-diagnose', { class: 'btn-cyan', disabled: isPending(state, `diagnose-${node.nodeId}`), data: { nodeId: node.nodeId } }),
        button(isPending(state, `edit-node-${node.nodeId}`) ? '打开中…' : '原生编辑', 'edit-node', { disabled: isPending(state, `edit-node-${node.nodeId}`), data: { nodeId: node.nodeId } }),
        button(isPending(state, `delete-node-${node.nodeId}`) ? '删除中…' : '删除', 'confirm-delete-node', { class: 'btn-danger', disabled: isPending(state, `delete-node-${node.nodeId}`), data: { nodeId: node.nodeId, title: node.displayName } }))))));
}

function accountsPage(view, state) {
  const accounts = view.accounts;
  if (!accounts) return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('平台与账号', '凭据状态由 Android 安全提供'), h('section', { class: 'empty-state' }, h('h2', {}, '暂无账号状态')));
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('平台与账号', '切换只对下次新建会话生效'), h('div', { class: 'banner banner-warning' }, '已有会话不会中途切换 Profile；新建会话时会冻结平台、Profile 与 Model。'),
    h('section', { class: 'account-list' }, accounts.adapters.map((adapter) => h('article', { class: `card account-group ${adapter.adapter}` },
      h('div', { class: 'row space-between' }, h('h2', {}, adapter.displayName), badge(adapter.available ? '可用' : '不可用', adapter.available ? 'online' : 'error')),
      h('p', { class: 'muted', text: adapter.capabilities.description || '原生 CLI 账号环境' }),
       adapter.profiles.map((profile) => h('div', { class: 'account-profile' }, h('div', {}, h('strong', { text: profile.displayName }), h('div', { class: 'row' }, profile.nativeDefault ? h('span', { class: 'badge', text: '原生默认' }) : null, h('span', { class: 'badge', text: profile.hasCredentials ? '已有凭据' : '无凭据' }), profile.active ? h('span', { class: 'badge accent-lime', text: '当前使用' }) : null)), profile.active ? null : button(isPending(state, `account-${adapter.adapter}`) ? '激活中…' : '激活', 'activate-account', { class: 'btn-small btn-cyan', disabled: !profile.hasCredentials || isPending(state, `account-${adapter.adapter}`), data: { nodeId: accounts.nodeId, adapter: adapter.adapter, profileId: profile.profileId } })))))));
}

function settingsPage(view, state) {
  const config = view.config || {};
  const adapter = view.server?.adapters?.find((item) => item.adapter === config.defaultAdapter);
  const caps = adapter?.capabilities || {};
  const approvalLabels = { untrusted: '仅不受信任操作询问', 'on-failure': '失败后询问', 'on-request': '按需询问', never: '从不询问', ask: '每次询问', deny: '默认拒绝' };
  const sandboxLabels = { 'workspace-write': '工作区可写', 'read-only': '只读', 'danger-full-access': '完全访问（高风险）' };
  const reviewerLabels = { auto_review: '自动复核', user: '用户复核', guardian_subagent: '守护子代理复核' };
  const approvalOptions = (caps.approvalPolicies || ['on-request', 'never']).map((value) => [value, approvalLabels[value] || value]);
  const sandboxOptions = (caps.sandboxModes || (caps.sandbox ? ['workspace-write', 'read-only', 'danger-full-access'] : [])).map((value) => [value, sandboxLabels[value] || value]);
  const reviewerOptions = (caps.reviewers || (caps.approvalsReviewer ? ['auto_review', 'user'] : [])).map((value) => [value, reviewerLabels[value] || value]);
  const adapters = (view.server?.adapters || []).map((item) => [item.adapter, item.displayName, !item.available]);
  const offline = view.connection.state !== 'online';
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('设置', '偏好由 Android glue 持久化'),
    h('section', { class: 'card settings-section' }, h('h2', {}, '外观'), h('div', { class: 'segmented', role: 'group', 'aria-label': '主题' }, [['system', '跟随系统'], ['light', '日间'], ['dark', '夜间']].map(([key, text]) => h('button', { type: 'button', disabled: isPending(state, 'appearance'), dataset: { action: 'theme', theme: key }, 'aria-pressed': view.appearance.theme === key }, text)))),
    h('section', { class: 'card settings-section' }, h('h2', {}, '新会话默认值'),
      settingSelect('默认平台', 'defaultAdapter', adapters.length ? adapters : [['claude', 'Claude Code'], ['codex', 'Codex CLI']], config.defaultAdapter),
      h('div', { class: 'setting-row model-setting-row' }, h('div', {}, h('span', {}, '默认模型'), h('small', { class: 'muted', text: config.explicitModel === false ? '继承所选 Profile 的 CLI 配置' : '仅影响之后新建的会话' })),
        button(modelDisplay(config.explicitModel === false ? '' : config.model, config.effectiveModel), 'open-model-config', { class: 'setting-value-button', disabled: offline })),
      approvalOptions.length ? settingSelect('审批策略', 'approvalPolicy', approvalOptions, config.approvalPolicy) : null,
      sandboxOptions.length ? settingSelect('沙箱', 'sandbox', sandboxOptions, config.sandbox) : null,
      reviewerOptions.length ? settingSelect('审批复核', 'approvalsReviewer', reviewerOptions, config.approvalsReviewer) : null,
      h('div', { class: 'risk-note' }, h('strong', {}, '“本会话始终允许”风险'), h('p', {}, '只有审批卡明确提供该选项时才可使用；确认后，同名工具在当前会话后续调用将不再逐次询问。新会话不会继承。')),
      offline ? h('div', { class: 'banner banner-warning', text: '当前可离线查看设置；连接节点后才能修改后端配置。' }) : null),
    h('section', { class: 'card settings-section' }, h('h2', {}, '关于'), h('div', { class: 'setting-row' }, h('span', {}, 'UI 版本'), h('code', {}, '0.0.3')), h('div', { class: 'setting-row' }, h('span', {}, '协议版本'), h('code', {}, '1'))));
}

function settingSelect(label, key, options, value) {
  const state = getState();
  const online = state.view?.connection?.state === 'online';
  const gated = options.map(([optionValue, text, disabled]) => [optionValue, text, disabled || !online || isPending(state, `config-${key}`)]);
  return h('div', { class: 'setting-row' }, h('span', { text: label }), choiceField(label, key, gated, value, 'config', { class: 'setting-choice', hiddenLabel: true }));
}

function diagnosticsPage(view, state) {
  const d = view.diagnostics || { protocolVersion: 1, notes: [] };
  const t = d.transport || {};
  const activeNode = view.nodes.find((node) => node.nodeId === view.activeNodeId);
  const values = [
    ['手机↔后端 RTT', metric(t.phoneBackendRttMs)], ['后端排队', metric(t.backendCliQueueMs)], ['后端→CLI 派发', metric(t.backendCliDispatchMs)],
    ['CLI 首事件（聚合）', metric(t.cliFirstEventMs)], ['PC↔relay TCP', metric(t.relayLatencyMs, 'ms', '暂无数据')]
  ];
  return h('main', { id: 'main-content', class: 'page', tabindex: '-1' }, pageHeading('诊断', '只展示端到端可观测指标', [activeNode ? button(isPending(state, `diagnose-${activeNode.nodeId}`) ? '诊断中…' : '重新诊断', 'node-diagnose', { class: 'btn-cyan', disabled: isPending(state, `diagnose-${activeNode.nodeId}`), data: { nodeId: activeNode.nodeId } }) : null]), connectionStrip(view),
    h('section', { class: 'metric-grid' }, values.map(([name, value]) => h('article', { class: 'metric' }, h('span', { text: name }), h('strong', { text: value })))),
    activeNode?.relayLatencyReliable === false ? h('div', { class: 'banner banner-warning' }, 'Relay TCP 延迟为参考值，当前采样不可靠。') : null,
    h('section', { class: 'card stack' }, h('h2', {}, '运行状态'), h('div', { class: 'meta-grid' },
      meta('连接状态', connectionLabels[view.connection.state] || view.connection.state), meta('组网状态', overlayStateLabels[activeNode?.overlayState] || activeNode?.overlayState || '暂无'), meta('PC 组网节点', activeNode?.peerConnected ? '已连接' : '未确认'), meta('链路模式', activeNode?.linkMode === 'p2p' ? 'P2P' : activeNode?.linkMode === 'relay' ? 'Relay' : '未知'), meta('链路观测', fmtTime(activeNode?.linkObservedAt)), meta('同步状态', syncStateLabels[activeNode?.syncState] || activeNode?.syncState || '暂无'), meta('后端版本', d.backendVersion || '暂无'), meta('协议版本', String(d.protocolVersion)), meta('最近同步', fmtTime(d.lastSyncAt))),
      d.net ? h('div', {}, h('h3', {}, 'Clash / TUN 节点'), h('pre', { class: 'tool-output', dataset: { selectionScope: 'diagnostics' }, text: safeStringify(d.net) })) : null,
      h('div', {}, h('h3', {}, '限制说明'), h('ul', {}, [...d.notes, '无法测量 CLI 到 AI 服务端的单向延迟；不会以 0 代替缺失值。'].map((note) => h('li', { text: note }))))));
}

function meta(label, value) { return h('div', { class: 'meta-item' }, h('span', { text: label }), h('strong', { text: value })); }

function renderDiffPatch(patchText) {
  const text = patchText || '';
  const lines = text.split('\n');
  const additions = lines.filter((line) => line.startsWith('+') && !line.startsWith('+++')).length;
  const deletions = lines.filter((line) => line.startsWith('-') && !line.startsWith('---')).length;
  const content = h('div', { class: 'diff-lines', role: 'region', 'aria-label': 'Diff 内容', dataset: { selectionScope: 'diff' } });
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

let renderedModalSignature = '';
let modalCleanup = null;
let modalReturnElement = null;
function modalSignature(state) {
  const modal = state.modal;
  if (!modal) return '';
  if (modal.type === 'diff') return `diff:${safeStringify(state.view?.activeSession?.diff || null)}`;
  if (modal.type === 'files') return `files:${safeStringify(state.fileBrowser)}:${safeStringify(state.fileNodes)}`;
  if (modal.type === 'create') return `create:${safeStringify(state.createDraft)}`;
  return `${modal.type}:${safeStringify(modal)}`;
}

function renderModal(state) {
  const root = document.querySelector('#dialog-root');
  const signature = modalSignature(state);
  if (signature && signature === renderedModalSignature && root.firstChild) return;
  renderedModalSignature = signature;
  modalCleanup?.({ restoreFocus: false });
  modalCleanup = null;
  replaceChildren(root);
  if (!state.modal) {
    document.querySelector('#app')?.removeAttribute('aria-hidden');
    if (modalReturnElement?.isConnected) modalReturnElement.focus?.({ preventScroll: true });
    modalReturnElement = null;
    return;
  }
  if (!modalReturnElement?.isConnected) modalReturnElement = document.activeElement;
  const content = modalContent(state.modal, state);
  const mandatory = state.modal.mandatory === true;
  const backdrop = h('div', { class: 'dialog-backdrop', dataset: { action: mandatory ? '' : 'close-modal' } },
    h('section', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'dialog-title', tabindex: '-1' }, content));
  root.append(backdrop);
  const dialog = backdrop.querySelector('.dialog');
  document.querySelector('#app')?.setAttribute('aria-hidden', 'true');
  modalCleanup = activateDialog(dialog, { mandatory, returnFocus: modalReturnElement, onClose: () => { if (getState().modal?.type === 'pair') persistPairDraft(); setModal(null); } });
}

let focusReturnElement;
let focusWasOpen = false;
let focusTrapCleanup = null;
function focusSnapshot(item, part = 'text') {
  if (!item) return null;
  if (part === 'approval') return { title: `审批 · ${item.approval?.tool || '详情'}`, text: safeStringify(item.approval?.input), streaming: false };
  if (part === 'output') return { title: `工具 · ${item.tool || '输出'}`, text: item.output || (item.state === 'running' ? '等待工具输出…' : '无输出'), streaming: item.state === 'running' };
  if (part === 'input') return { title: `工具输入 · ${item.tool || '工具'}`, text: safeStringify(item.input), streaming: false };
  if (part === 'error') return { title: '本轮失败', text: `${item.error?.summary || '执行失败'}\n${item.error?.code || ''}`.trim(), streaming: false };
  if (item.kind === 'thinking') return { title: item.streaming ? '正在思考 · 当前快照' : '思考', text: item.text || '', streaming: Boolean(item.streaming) };
  if (item.kind === 'approval') return { title: '审批详情', text: safeStringify(item.approval), streaming: false };
  return { title: item.role === 'user' ? '你的消息' : '回复', text: item.text || '', streaming: Boolean(item.streaming) };
}

function currentFocusItem(source) {
  const items = getState().view?.activeSession?.messages || [];
  return items.find((item) => String(item.localSeq) === String(source?.localSeq));
}

function saveFocusDraft(close = false) {
  const panel = getState().focusPanel;
  if (panel?.type !== 'draft') return '';
  const text = document.querySelector('[data-focus-draft]')?.value ?? panel.text ?? '';
  const composerInput = document.querySelector('#composer-input');
  if (composerInput) composerInput.value = text;
  sendIntent('session.saveDraft', { localSessionId: panel.sessionId, text });
  if (close) setFocusPanel(null);
  return text;
}

export function renderFocusPanel(state) {
  const root = document.querySelector('#focus-root');
  if (!root) return;
  focusTrapCleanup?.({ restoreFocus: false });
  focusTrapCleanup = null;
  replaceChildren(root);
  const panel = state.focusPanel;
  if (!panel) {
    document.body.classList.remove('focus-open');
    if (!state.modal) document.querySelector('#app')?.removeAttribute('aria-hidden');
    if (focusWasOpen) {
      focusWasOpen = false;
      document.dispatchEvent(new Event('moyu:interaction-surface-change'));
      focusReturnElement?.focus?.({ preventScroll: true });
      focusReturnElement = null;
    }
    return;
  }
  document.body.classList.add('focus-open');
  document.querySelector('#app')?.setAttribute('aria-hidden', 'true');
  focusWasOpen = true;
  const reader = panel.type === 'reader';
  const layer = h('div', { class: 'focus-layer' },
    h('section', { class: `focus-panel focus-panel-${panel.type}`, role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'focus-title' },
      h('header', { class: 'focus-header' },
        h('div', { class: 'grow' }, h('small', { text: reader ? '专注阅读' : '专注编辑' }), h('h2', { id: 'focus-title', text: panel.title })),
        button('×', 'close-focus', { class: 'btn-icon btn-quiet', label: '关闭专注层' })),
      h('div', { class: 'focus-body' },
        panel.streaming ? h('div', { class: 'banner banner-warning', text: '生成中 · 此处保持当前快照，点击“更新快照”才会改变内容。' }) : null,
        reader
          ? h('pre', { class: 'focus-reader-copy', tabindex: '0', dataset: { selectionScope: 'focus-reader' }, text: panel.text })
          : h('label', { class: 'focus-editor-field' }, h('span', { class: 'sr-only', text: '消息草稿' }), h('textarea', { class: 'focus-editor', rows: '12', value: panel.text || '', dataset: { focusDraft: 'true' }, placeholder: '在这里专心编辑要发送的内容' }))),
      h('footer', { class: 'focus-actions' },
        reader && panel.source ? button('更新快照', 'refresh-focus', { class: 'btn-quiet' }) : null,
        reader ? copyButton(panel.text, '复制全文', { class: 'btn-cyan' }) : null,
        button(reader ? '关闭' : '完成并返回', 'close-focus', { class: 'btn-primary' }),
        !reader && panel.online ? button(isPending(state, `send-${panel.sessionId}`) ? '发送中…' : '直接发送', 'focus-send', { class: 'btn-cyan', disabled: isPending(state, `send-${panel.sessionId}`) }) : null)));
  root.append(layer);
  const focusDialog = layer.querySelector('.focus-panel');
  focusTrapCleanup = activateDialog(focusDialog, {
    returnFocus: focusReturnElement,
    onClose: () => {
      if (getState().focusPanel?.type === 'draft') saveFocusDraft(true);
      else setFocusPanel(null);
    }
  });
  requestAnimationFrame(() => {
    const target = reader ? layer.querySelector('[data-action="close-focus"]') : layer.querySelector('[data-focus-draft]');
    target?.focus?.({ preventScroll: true });
    if (!reader && target?.setSelectionRange) target.setSelectionRange(target.value.length, target.value.length);
  });
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
    const models = modelSuggestions(view, adapter?.adapter);
    const profile = profiles.find((item) => item.profileId === draft.profileId) || profiles.find((item) => item.active);
    return h('form', { dataset: { form: 'create' } }, h('h2', { id: 'dialog-title' }, '新建会话'), h('p', { class: 'muted' }, '平台与 Profile 在创建时确定；Claude 模式和支持的模型可在空闲时由你手动切换。'),
      createSelect('节点', 'nodeId', view.nodes.filter((n) => n.configured).map((n) => [n.nodeId, n.displayName]), draft.nodeId),
      createSelect('平台', 'kind', adapters.map((a) => [a.adapter, a.displayName]), adapter?.adapter),
      caps.profiles ? createSelect('Profile（创建后冻结）', 'profileId', profiles.map((profile) => [profile.profileId, `${profile.displayName}${profile.hasCredentials ? '' : '（缺少凭据）'}`, !profile.hasCredentials]), draft.profileId) : null,
      caps.models ? modelInput('初始模型', 'model', draft.model, models, profile?.effectiveModel || adapter?.effectiveModel) : null,
      caps.effortLevels?.length ? createSelect('推理深度（创建后可调整）', 'effort', [['', 'CLI 默认'], ...caps.effortLevels.map((level) => [level, level])], draft.effort || '') : null,
      caps.permissionModes?.length ? createSelect('初始模式', 'permissionMode', caps.permissionModes.map((mode) => [mode, permissionModeLabels[mode] || mode]), draft.permissionMode || 'acceptEdits') : null,
      (caps.sandbox || caps.approvalsReviewer) ? h('section', { class: 'capability-summary', 'aria-label': '会话能力' },
        h('strong', {}, '本次会话能力'),
        h('div', { class: 'row' },
          caps.sandbox ? h('span', { class: 'badge accent-cyan', text: `Sandbox · ${view.config?.sandbox || '由 Host 决定'}` }) : null,
          caps.approvalsReviewer ? h('span', { class: 'badge accent-purple', text: `Reviewer · ${view.config?.approvalsReviewer || '由 Host 决定'}` }) : null),
        h('p', { class: 'muted', text: '这些值继承当前设置；CreateSessionDraft 不携带敏感配置。' })) : null,
      fieldInput('标题（可选）', 'title', '例如：修复登录流程', draft.title),
      h('div', { class: 'directory-field' }, fieldInput('工作目录（可选）', 'cwd', '留空使用默认目录', draft.cwd), button('浏览 PC', 'open-files', { class: 'btn-quiet directory-browse' })),
      actions(button('取消', 'close-modal'), button(isPending(state, 'create-session') ? '创建中…' : '创建', 'submit-create', { class: 'btn-primary', disabled: isPending(state, 'create-session') || view.connection.state !== 'online' || !view.nodes.some((n) => n.configured) })));
  }
  if (modal.type === 'model-config') {
    const kind = view.config?.defaultAdapter || 'claude';
    const value = view.config?.explicitModel === false ? '' : (view.config?.model || '');
    return h('form', { dataset: { form: 'model-config' } },
      h('h2', { id: 'dialog-title' }, '默认模型'),
      h('p', { class: 'muted' }, '只影响之后新建的会话。留空会继承 PC 上所选 Profile 的原生 CLI 配置；不会向 AI 服务探测模型目录。'),
      modelInput('模型标识', 'model', value, modelSuggestions(view, kind), view.config?.effectiveModel),
      actions(button('取消', 'close-modal'), button(isPending(state, 'config-model') ? '保存中…' : '保存默认值', 'submit-model-config', { class: 'btn-primary', disabled: isPending(state, 'config-model') || view.connection.state !== 'online' })));
  }
  if (modal.type === 'session-model') {
    const session = view.activeSession;
    return h('form', { dataset: { form: 'session-model' } },
      h('h2', { id: 'dialog-title' }, '当前会话模型'),
      h('p', { class: 'muted' }, '只修改后续轮次的原生 CLI --model 参数，不探测服务端模型目录，也不会改写消息内容。运行中的回合不能切换。'),
      modelInput('模型标识', 'model', session?.model || '', modelSuggestions(view, session?.kind), ''),
      actions(button('取消', 'close-modal'), button(isPending(state, `model-${session?.localSessionId}`) ? '应用中…' : '应用到后续轮次', 'submit-session-model', { class: 'btn-primary', disabled: !session || session.state === 'running' || view.connection.state !== 'online' || isPending(state, `model-${session?.localSessionId}`) })));
  }
  if (modal.type === 'confirm') return h('div', {}, h('h2', { id: 'dialog-title', text: modal.title }), h('p', { text: modal.message }), modal.warning ? h('div', { class: 'banner banner-error', text: modal.warning }) : null, actions(button('返回', 'close-modal'), button(modal.confirmText || '确认', modal.action, { class: 'btn-danger', data: modal.data })));
  if (modal.type === 'pair') {
    const draft = state.pairDraft;
    return h('form', { dataset: { form: 'pair' } }, h('h2', { id: 'dialog-title' }, '配对新节点'),
      h('p', {}, '填写内容会由 Android 加密暂存；配对成功后自动清除。'),
      pairInput('显示名称', 'displayName', '例如：工作室 PC', draft.displayName, true),
      pairInput('Relay 节点', 'relayNode', '例如：relay.example:11010', draft.relayNode, true),
      pairInput('配对字符串', 'pairString', '从 PC 端复制', draft.pairString, true),
      actions(button('取消', 'close-modal'), button(isPending(state, 'pair-node') ? '配对中…' : '配对', 'submit-pair', { class: 'btn-primary', disabled: isPending(state, 'pair-node') })),
      h('hr'), button(isPending(state, 'manual-setup') ? '打开中…' : '改用原生手动配置', 'manual-setup', { class: 'btn-quiet', disabled: isPending(state, 'manual-setup') }));
  }
  if (modal.type === 'diff') {
    const diff = view.activeSession?.diff;
    const patchText = (diff?.files || []).map((file) => `${file.path}\n${file.patch || ''}`).join('\n\n');
    return h('div', {}, h('div', { class: 'row space-between' }, h('h2', { id: 'dialog-title' }, '工作区 Diff'), patchText ? copyButton(patchText, '复制全部 Diff') : null), !diff ? h('div', { class: 'empty-state' }, h('div', { class: 'skeleton' }), h('p', {}, '正在从后端读取 Diff；读取失败时会显示提示。')) : diff.isGitRepo === false ? h('div', { class: 'empty-state' }, h('h3', {}, '当前目录不是 Git 仓库'), h('p', {}, '仍可继续会话，但没有可展示的 Git Diff。')) : (diff.files?.length ? diff.files.map(diffFile) : h('div', { class: 'empty-state' }, h('h3', {}, '工作区没有更改'), h('p', {}, '当前没有 staged、unstaged 或 untracked 内容。'))), actions(button('关闭', 'close-modal')));
  }
  if (modal.type === 'image' && validArtifact(modal.artifact)) return h('div', { class: 'artifact-preview' },
    h('h2', { id: 'dialog-title', text: modal.artifact.name || '图片' }),
    h('img', { src: modal.artifact.localUrl, alt: modal.artifact.name || '会话图片' }),
    actions(button('关闭', 'close-modal', { class: 'btn-primary' })));
  if (modal.type === 'files') {
    const browser = state.fileBrowser;
    const body = browser.status === 'loading'
      ? h('div', { class: 'file-tree', 'aria-busy': 'true', 'aria-label': '正在读取目录' }, h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' }))
      : browser.status === 'error'
        ? h('div', { class: 'empty-state file-empty' }, h('h3', {}, '目录读取失败'), h('p', { text: browser.error || '请稍后重试' }), button('重试', 'retry-files', { class: 'btn-cyan' }))
        : state.fileNodes.length
          ? h('div', { class: 'file-tree' }, state.fileNodes.map((node) => button(`${node.kind === 'directory' ? '▸' : '·'} ${node.name}`, 'fs-node', { class: 'file-node', disabled: node.kind !== 'directory', data: { nodeId: node.nodeId, path: node.path } })))
          : h('div', { class: 'empty-state file-empty' }, h('h3', {}, '这个目录是空的'), h('p', {}, '可以直接选择当前目录。'));
    return h('div', {}, h('h2', { id: 'dialog-title' }, '选择工作目录'), h('p', { class: 'muted', text: `节点：${nodeLabel(view, browser.nodeId)}` }), h('p', { class: 'mono muted', text: browser.path || '/' }), body, actions(button('关闭', 'close-modal'), button('选择此目录', 'choose-directory', { class: 'btn-primary', disabled: browser.status !== 'ready', data: { path: browser.path || '/' } })));
  }
  if (modal.type === 'offline-send') return h('div', {}, h('h2', { id: 'dialog-title' }, '当前仍处于离线状态'), h('p', {}, '消息不会进入网络发送队列，也不会在恢复连接后自动重放。我们已保存草稿，请在线后再次点击发送。'), actions(button('我知道了', 'close-modal', { class: 'btn-primary' })));
  if (modal.type === 'allow-session') return h('div', {}, h('h2', { id: 'dialog-title' }, '本会话始终允许此工具类型？'), h('div', { class: 'banner banner-warning' }, `该会话后续所有同名工具调用（如所有 Bash）不再询问。当前工具：${modal.data?.tool || '此工具类型'}。`), h('p', {}, '请确认你理解该工具类型后续每次调用的影响。'), actions(button('返回', 'close-modal'), button('确认始终允许此工具类型', 'approval-final', { class: 'btn-danger', data: modal.data })));
  return h('div', {}, h('h2', { id: 'dialog-title' }, '提示'), actions(button('关闭', 'close-modal')));
}

function fieldInput(label, name, placeholder, value = '') { return h('label', { class: 'field' }, h('span', { text: label }), h('input', { name, placeholder, value })); }
function modelInput(label, name, value, suggestions = [], effectiveModel = '') {
  return h('div', { class: 'field model-input-field' },
    h('span', { class: 'field-label', text: label }),
    h('input', { name, value: value || '', maxlength: '128', placeholder: effectiveModel ? `留空继承 ${effectiveModel}` : '留空继承 CLI 默认模型', autocomplete: 'off', spellcheck: 'false', dataset: { modelInput: name } }),
    h('small', { class: 'muted', text: effectiveModel ? `当前 CLI 解析值：${effectiveModel}` : '可输入兼容端或自定义中继支持的精确模型标识' }),
    h('div', { class: 'model-suggestions', 'aria-label': '本地模型建议' },
      button('CLI 默认', 'set-model-input', { class: 'model-chip accent-yellow', data: { value: '', name } }),
      suggestions.map((model) => button(model, 'set-model-input', { class: 'model-chip', data: { value: model, name } }))));
}
function pairInput(label, name, placeholder, value = '', required = false) {
  return h('label', { class: 'field' }, h('span', { text: label }), h('input', { name, placeholder, value, required, autocomplete: 'off', dataset: { pairField: name } }));
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
      disabled: !selected || options.every(([, , disabled]) => disabled)
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

let renderedNoticeSignature = null;
const noticeTimers = new Map();
function noticeDuration(level) { return level === 'error' ? 6000 : level === 'warning' ? 4500 : 3200; }
export function renderNotice(notices = []) {
  const root = document.querySelector('#toast-root');
  const queue = Array.isArray(notices) ? notices.slice(-3) : [];
  const signature = queue.map((notice) => `${notice.id}:${notice.level}:${notice.text}`).join('|');
  if (renderedNoticeSignature === signature && root.childElementCount === queue.length) return;
  renderedNoticeSignature = signature;
  const activeIds = new Set(queue.map((notice) => notice.id));
  noticeTimers.forEach((timer, id) => {
    if (activeIds.has(id)) return;
    window.clearTimeout(timer);
    noticeTimers.delete(id);
  });
  replaceChildren(root);
  queue.forEach((notice) => {
    root.append(h('div', { class: `toast toast-${notice.level}`, role: notice.level === 'error' ? 'alert' : 'status', dataset: { noticeId: notice.id } },
      h('span', { class: 'toast-message', text: notice.text }),
      button('×', 'dismiss-notice', { class: 'btn-icon btn-quiet toast-dismiss', label: '关闭通知', data: { noticeId: notice.id } })));
    if (!noticeTimers.has(notice.id)) noticeTimers.set(notice.id, window.setTimeout(() => dismissNotice(notice.id), noticeDuration(notice.level)));
  });
}

function initialCreateDraft(view, requestedKind) {
  const configuredNodes = view.nodes.filter((node) => node.configured);
  const adapters = view.server?.adapters?.filter((adapter) => adapter.available) || [];
  const kind = requestedKind || view.config?.defaultAdapter || adapters[0]?.adapter || 'claude';
  const adapter = adapters.find((item) => item.adapter === kind);
  const profiles = view.accounts?.adapters?.find((item) => item.adapter === kind)?.profiles || [];
  const profile = profiles.find((item) => item.active && item.hasCredentials) || profiles.find((item) => item.hasCredentials);
  const model = view.config?.defaultAdapter === kind && view.config?.explicitModel !== false ? (view.config?.model || '') : '';
  return {
    nodeId: configuredNodes.some((node) => node.nodeId === view.activeNodeId) ? view.activeNodeId : configuredNodes[0]?.nodeId || '',
    kind,
    cwd: '',
    title: '',
    profileId: profile?.profileId || '',
    model,
    effort: '',
    permissionMode: kind === 'claude' ? 'acceptEdits' : ''
  };
}

let renderedRoute = '';

function conversationScroller() {
  const local = document.querySelector('.conversation-scroll');
  if (local && local.scrollHeight > local.clientHeight) return local;
  return document.scrollingElement || document.documentElement;
}

export function render(state) {
  const app = document.querySelector('#app');
  const view = state.view;
  if (!view) {
    replaceChildren(app, header(null), h('main', { class: 'page stack' }, h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' }), h('div', { class: 'skeleton' })));
    return;
  }
  document.documentElement.dataset.theme = view.appearance.resolvedTheme;
  document.documentElement.classList.toggle('reduce-motion-fixture', view.ui?.reducedMotion === true);
  const pages = { console: consolePage, conversation: conversationPage, sessions: sessionsPage, nodes: nodesPage, accounts: accountsPage, settings: settingsPage, diagnostics: diagnosticsPage };
  const outlineScrollTop = state.route === 'conversation' && state.conversationOutlineOpen ? state.conversationScrollTop : null;
  const scrollAnchor = state.route === 'conversation' && renderedRoute === 'conversation' && !state.conversationOutlineOpen ? captureScrollAnchor(conversationScroller()) : null;
  const page = (pages[state.route] || consolePage)(view, state);
  const routeChanged = Boolean(renderedRoute && state.route !== renderedRoute);
  const nativeTransition = routeChanged && typeof document.startViewTransition === 'function' && !reducedMotion();
  if (routeChanged && state.route !== 'conversation' && !nativeTransition) page.classList.add('page-enter');
  const conversation = state.route === 'conversation';
  const commit = () => {
    app.classList.toggle('conversation-active', conversation);
    replaceChildren(app, ...[conversation ? null : header(view, state.route), globalBanner(view, state), h('div', { class: conversation ? 'conversation-layout' : 'main-layout' }, page), nav(conversation ? 'sessions' : state.route)].filter(Boolean));
    if (conversation) {
      restoreScrollAnchor(conversationScroller(), scrollAnchor);
      if (Number.isFinite(outlineScrollTop)) {
        conversationScroller().scrollTop = outlineScrollTop;
        window.scrollTo(0, outlineScrollTop);
        requestAnimationFrame(() => { conversationScroller().scrollTop = outlineScrollTop; window.scrollTo(0, outlineScrollTop); });
      }
      updateSlashPalette(view.activeSession?.composerDraft || '');
      queueConversationChromeSync();
    }
    renderedRoute = state.route;
    app.setAttribute('aria-busy', 'false');
    renderModal(state);
    renderFocusPanel(state);
    renderNotice(state.notices);
  };
  if (nativeTransition) document.startViewTransition(commit);
  else commit();
}

function getComposerText() { return document.querySelector('#composer-input')?.value || ''; }

function currentArtifact(artifactId) {
  const session = getState().view?.activeSession;
  const all = [...(session?.composerAttachments || []), ...(session?.messages || []).flatMap((item) => item.artifacts || [])];
  return all.find((artifact) => artifact.artifactId === artifactId && validArtifact(artifact));
}

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

function openFileDirectory(nodeId, path) {
  if (!nodeId) { setNotice('请先选择要浏览的节点', { level: 'warning' }); return; }
  const targetPath = path || '.';
  update((next) => {
    next.fileNodes = [];
    next.fileBrowser = { status: 'loading', nodeId, path: targetPath, error: '' };
    next.modal = { type: 'files' };
  });
  const requestId = sendIntent('fs.list', { nodeId, path: targetPath }, { pendingKey: 'fs-list' });
  if (!requestId) update((next) => {
    next.fileBrowser = { ...next.fileBrowser, status: 'error', error: '无法向 Host 请求目录' };
  });
}

function closeChoiceMenus(exceptId = '') {
  let changed = false;
  document.querySelectorAll('.choice-menu:not([hidden])').forEach((menu) => {
    if (menu.id === exceptId) return;
    setChoiceMenuOpen(menu, false);
    changed = true;
  });
  return changed;
}

function setChoiceMenuOpen(menu, open) {
  if (!menu || menu.hidden === !open) return;
  menu.hidden = !open;
  const trigger = document.querySelector(`[aria-controls="${menu.id}"]`);
  trigger?.setAttribute('aria-expanded', String(open));
  document.dispatchEvent(new Event('moyu:choice-menu-change'));
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
    } else sendIntent('config.patch', { nodeId: getState().view.activeNodeId, patch: { [name]: value } }, { pendingKey: `config-${name}` });
    return;
  }
  if (scope === 'session' && name === 'permissionMode') {
    const session = getState().view?.activeSession;
    if (!session || !['plan', 'auto', 'acceptEdits'].includes(value)) return;
    sendIntent('session.permissionMode.set', { localSessionId: session.localSessionId, permissionMode: value }, { pendingKey: `mode-${session.localSessionId}` });
    setNotice(`正在切换为 ${permissionModeLabels[value] || value}`);
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
  if (!matches.length) { palette.hidden = true; replaceChildren(palette); return; }
  replaceChildren(palette, ...matches.map((item, index) => h('button', {
    type: 'button', class: `slash-option ${index === 0 ? 'active' : ''}`, role: 'option', dataset: { action: 'slash-command', command: item.command }, 'aria-selected': index === 0 ? 'true' : 'false'
  }, h('strong', { text: `/${item.command} · ${item.title}` }), h('span', { class: 'muted', text: item.hint }))));
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
      setChoiceMenuOpen(menu, opening);
      return;
    }
    if (action === 'choose-choice') { event.preventDefault(); chooseValue(target); return; }
    closeChoiceMenus();
    if (action === 'dismiss-notice') dismissNotice(target.dataset.noticeId);
    else if (action === 'route') openRoute(target.dataset.route);
    else if (action === 'open-create') update((next) => { next.createDraft = initialCreateDraft(next.view, target.dataset.kind); next.modal = { type: 'create' }; });
    else if (action === 'close-modal') { if (event.target === target || target.tagName === 'BUTTON') { if (getState().modal?.type === 'pair') persistPairDraft(); setModal(null); } }
    else if (action === 'toggle-output' || action === 'toggle-session-info') toggleExpanded(target.dataset.id);
    else if (action === 'open-session') {
      setConversationOutlineOpen(false);
      sendIntent('session.open', { localSessionId: target.dataset.sessionId }, { pendingKey: `open-${target.dataset.sessionId}` });
    }
    else if (action === 'confirm-delete-session') setModal({ type: 'confirm', title: '删除本地会话？', message: `“${target.dataset.title}”的 Android 本地记录将被删除，远端会话不受影响。`, confirmText: '删除本地记录', action: 'delete-session', data: { sessionId: target.dataset.sessionId } });
    else if (action === 'delete-session') { sendIntent('session.deleteLocal', { localSessionId: target.dataset.sessionId }, { pendingKey: `delete-${target.dataset.sessionId}` }); setModal(null); }
    else if (action === 'confirm-delete-node') setModal({ type: 'confirm', title: '删除节点？', message: `确认删除“${target.dataset.title}”的本地节点记录。`, warning: '此操作不会清除 PC 端数据。', action: 'delete-node', data: { nodeId: target.dataset.nodeId } });
    else if (action === 'delete-node') { sendIntent('node.delete', { nodeId: target.dataset.nodeId }, { pendingKey: `delete-node-${target.dataset.nodeId}` }); setModal(null); }
    else if (action === 'node-connect') sendIntent('node.connect', { nodeId: target.dataset.nodeId }, { pendingKey: `node-${target.dataset.nodeId}` });
    else if (action === 'node-disconnect') sendIntent('node.disconnect', { nodeId: target.dataset.nodeId }, { pendingKey: `node-${target.dataset.nodeId}` });
    else if (action === 'node-diagnose') sendIntent('node.diagnose', { nodeId: target.dataset.nodeId }, { pendingKey: `diagnose-${target.dataset.nodeId}` });
    else if (action === 'open-pair') update((next) => { next.pairDraft = { ...next.pairDraft, ...(next.view.pairDraft || {}) }; next.modal = { type: 'pair' }; });
    else if (action === 'manual-setup') { if (getState().modal?.type === 'pair') persistPairDraft(); sendIntent('node.manualSetup.open', {}, { pendingKey: 'manual-setup' }); setModal(null); }
    else if (action === 'edit-node') sendIntent('node.manualSetup.open', { nodeId: target.dataset.nodeId }, { pendingKey: `edit-node-${target.dataset.nodeId}` });
    else if (action === 'activate-account') sendIntent('accounts.activate', { nodeId: target.dataset.nodeId, adapter: target.dataset.adapter, profileId: target.dataset.profileId }, { pendingKey: `account-${target.dataset.adapter}` });
    else if (action === 'theme') sendIntent('appearance.set', { theme: target.dataset.theme }, { pendingKey: 'appearance' });
    else if (action === 'header-theme') sendIntent('appearance.set', { theme: target.dataset.theme }, { pendingKey: 'appearance' });
    else if (action === 'open-diff') { setModal({ type: 'diff' }); if (getState().view.connection.state === 'online') sendIntent('diff.open', { localSessionId: target.dataset.sessionId }, { pendingKey: `diff-${target.dataset.sessionId}` }); }
    else if (action === 'toggle-outline') {
      event.preventDefault();
      target.blur();
      const open = target.dataset.open === 'true';
      setConversationOutlineOpen(open);
    }
    else if (action === 'sidebar-tab') update((next) => { next.conversationSidebarTab = target.dataset.tab === 'sessions' ? 'sessions' : 'outline'; }, { scope: 'interaction' });
    else if (action === 'jump-segment') {
      const segmentId = target.dataset.segmentId;
      setConversationOutlineOpen(false);
      requestAnimationFrame(() => document.getElementById(segmentId)?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' }));
    }
    else if (action === 'jump-latest') scrollConversationToLatest();
    else if (action === 'focus-content') {
      const source = { localSeq: target.dataset.localSeq, part: target.dataset.part || 'text' };
      const snapshot = focusSnapshot(currentFocusItem(source), source.part);
      if (snapshot) {
        focusReturnElement = target;
        setFocusPanel({ type: 'reader', ...snapshot, source });
      }
    }
    else if (action === 'focus-draft') {
      const session = getState().view?.activeSession;
      if (session) {
        focusReturnElement = target;
        setFocusPanel({ type: 'draft', title: '编辑待发送内容', text: getComposerText(), sessionId: session.localSessionId, online: getState().view.connection.state === 'online' });
      }
    }
    else if (action === 'refresh-focus') {
      const panel = getState().focusPanel;
      const snapshot = focusSnapshot(currentFocusItem(panel?.source), panel?.source?.part);
      if (panel?.type === 'reader' && snapshot) setFocusPanel({ ...panel, ...snapshot });
    }
    else if (action === 'close-focus') {
      if (getState().focusPanel?.type === 'draft') saveFocusDraft(true);
      else setFocusPanel(null);
    }
    else if (action === 'focus-send') {
      const panel = getState().focusPanel;
      const text = saveFocusDraft(false).trim();
      const hasAttachments = (getState().view?.activeSession?.composerAttachments || []).length > 0;
      if (panel?.type === 'draft' && (text || hasAttachments)) {
        setFocusPanel(null);
        sendIntent('session.send', { localSessionId: panel.sessionId, text }, { pendingKey: `send-${panel.sessionId}` });
      }
    }
    else if (action === 'open-model-config') setModal({ type: 'model-config' });
    else if (action === 'open-session-model') setModal({ type: 'session-model' });
    else if (action === 'set-model-input') {
      const input = document.querySelector(`[data-model-input="${target.dataset.name}"]`);
      if (input) { input.value = target.dataset.value || ''; input.focus(); }
    }
    else if (action === 'copy-text') copyText(copyValues.get(target) ?? target.dataset.text ?? '');
    else if (action === 'open-files') {
      const form = target.closest('form');
      if (form) update((next) => { next.createDraft = { ...next.createDraft, ...Object.fromEntries(new FormData(form)) }; });
      const draft = getState().createDraft;
      openFileDirectory(draft.nodeId, draft.cwd || '.');
    }
    else if (action === 'fs-node') openFileDirectory(getState().fileBrowser.nodeId, target.dataset.path);
    else if (action === 'retry-files') openFileDirectory(getState().fileBrowser.nodeId, getState().fileBrowser.path);
    else if (action === 'choose-directory') {
      update((next) => { next.createDraft = { ...next.createDraft, cwd: target.dataset.path }; next.modal = { type: 'create' }; });
      setNotice(`已选择目录：${target.dataset.path}`);
    }
    else if (action === 'load-older') sendIntent('session.loadOlder', { localSessionId: target.dataset.sessionId }, { pendingKey: `older-${target.dataset.sessionId}` });
    else if (action === 'pick-attachment') sendIntent('attachment.pick', { localSessionId: target.dataset.sessionId }, { pendingKey: `attachment-${target.dataset.sessionId}` });
    else if (action === 'remove-attachment') sendIntent('attachment.remove', { localSessionId: target.dataset.sessionId, artifactId: target.dataset.artifactId }, { pendingKey: `remove-attachment-${target.dataset.artifactId}` });
    else if (action === 'open-artifact') { const artifact = currentArtifact(target.dataset.artifactId); if (artifact) setModal({ type: 'image', artifact }); }
    else if (action === 'interrupt') sendIntent('session.interrupt', { localSessionId: target.dataset.sessionId }, { pendingKey: `interrupt-${target.dataset.sessionId}` });
    else if (action === 'send') {
      const text = getComposerText().trim();
      const hasAttachments = (getState().view?.activeSession?.composerAttachments || []).length > 0;
      if (!text && !hasAttachments) return;
      document.querySelector('#composer-input')?.blur();
      if (target.dataset.offline === 'true') { sendIntent('session.saveDraft', { localSessionId: target.dataset.sessionId, text }); setModal({ type: 'offline-send' }); }
      else sendIntent('session.send', { localSessionId: target.dataset.sessionId, text }, { pendingKey: `send-${target.dataset.sessionId}` });
    }
    else if (action === 'slash-command') {
      const session = getState().view?.activeSession;
      if (!session) return;
      if (target.dataset.command === 'effort') { const input = document.querySelector('#composer-input'); if (input) input.value = '/effort'; updateSlashPalette('/effort'); }
      else if (target.dataset.command === 'diff') { clearComposerDraft(session.localSessionId); setModal({ type: 'diff' }); if (getState().view.connection.state === 'online') sendIntent('diff.open', { localSessionId: session.localSessionId }, { pendingKey: `diff-${session.localSessionId}` }); }
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
      const data = { localSessionId: target.dataset.sessionId, approvalId: target.dataset.approvalId, decision: target.dataset.decision, tool: target.dataset.tool };
      if (data.decision === 'allow_session') setModal({ type: 'allow-session', data });
      else sendIntent('approval.decide', data, { pendingKey: `approval-${data.approvalId}` });
    }
    else if (action === 'approval-final') { sendIntent('approval.decide', { localSessionId: target.dataset.sessionId, approvalId: target.dataset.approvalId, decision: target.dataset.decision }, { pendingKey: `approval-${target.dataset.approvalId}` }); setModal(null); }
    else if (action === 'banner-action') { const intent = getState().view.ui.globalBanner?.actionIntent; if (intent) sendIntent(intent.type, intent.payload, { pendingKey: `banner-${intent.type}` }); }
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
    if (event.target.matches('[data-focus-draft]')) {
      window.clearTimeout(draftTimer);
      const panel = getState().focusPanel;
      const text = event.target.value;
      draftTimer = window.setTimeout(() => {
        if (panel?.type === 'draft') sendIntent('session.saveDraft', { localSessionId: panel.sessionId, text });
      }, 450);
    }
    const approvalForm = event.target.closest('form[data-form="approval-questions"]');
    if (approvalForm) cacheApprovalQuestionAnswers(approvalForm);
  });
  document.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a') {
      const scope = event.target.closest?.('[data-selection-scope]') || document.querySelector('#focus-root [data-selection-scope]');
      if (scope) {
        event.preventDefault();
        const range = document.createRange();
        range.selectNodeContents(scope);
        const selection = document.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        return;
      }
    }
    if (event.key === 'Escape' && handleUiBack()) { event.preventDefault(); return; }
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
        setChoiceMenuOpen(menu, false);
        const owner = document.querySelector(`[aria-controls="${menu.id}"]`);
        owner?.focus();
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
      const slashOption = palette?.querySelector('.slash-option.active, .slash-option');
      if (slashOption) { slashOption.click(); return; }
      document.querySelector('[data-action="send"]')?.click();
    }
  });
  document.addEventListener('submit', (event) => {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));
    if (form.dataset.form === 'create') {
      const payload = Object.fromEntries(Object.entries(data).filter(([, value]) => value !== ''));
      if (!payload.nodeId || !['claude', 'codex'].includes(payload.kind)) { setNotice('请选择节点和平台', { level: 'warning' }); return; }
      sendIntent('session.create', payload, { pendingKey: 'create-session' }); setModal(null);
    }
    if (form.dataset.form === 'model-config') {
      const model = String(data.model || '').trim();
      sendIntent('config.patch', { nodeId: getState().view.activeNodeId, patch: { model } }, { pendingKey: 'config-model' });
      setModal(null);
    }
    if (form.dataset.form === 'session-model') {
      const session = getState().view?.activeSession;
      if (!session) return;
      const model = String(data.model || '').trim();
      sendIntent('session.model.set', { localSessionId: session.localSessionId, model: model || undefined }, { pendingKey: `model-${session.localSessionId}` });
      setModal(null);
    }
    if (form.dataset.form === 'pair') {
      const pair = Object.fromEntries(Object.entries(data).map(([key, value]) => [key, String(value).trim()]));
      if (!pair.displayName || !pair.relayNode || !pair.pairString) { setNotice('请完整填写显示名称、Relay 节点和配对字符串', { level: 'warning' }); return; }
      cachePairDraft(pair); persistPairDraft(); sendIntent('node.pair', pair, { pendingKey: 'pair-node' }); setModal(null);
    }
    if (form.dataset.form === 'approval-questions') {
      const approval = getState().view?.activeSession?.pendingApproval;
      const questions = approval?.approvalId === form.dataset.approvalId ? askUserQuestions(approval) : null;
      if (!questions) { setNotice('问题已失效，请刷新会话', { level: 'warning' }); return; }
      const draft = cacheApprovalQuestionAnswers(form);
      try {
        const decision = askUserDecision(questions, questions.map((_, index) => draft[index] || []));
        sendIntent('approval.decide', { localSessionId: form.dataset.sessionId, approvalId: approval.approvalId, decision }, { pendingKey: `approval-${approval.approvalId}` });
      } catch (error) { setNotice(error instanceof Error ? error.message : '请完成回答', { level: 'warning' }); }
    }
  });
  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-action="submit-create"], [data-action="submit-pair"], [data-action="submit-model-config"], [data-action="submit-session-model"], [data-action="confirm-config"]');
    if (!target) return;
    if (target.dataset.action === 'submit-create' || target.dataset.action === 'submit-pair' || target.dataset.action === 'submit-model-config' || target.dataset.action === 'submit-session-model') target.closest('form')?.requestSubmit();
    else { sendIntent('config.patch', { nodeId: getState().view.activeNodeId, patch: { [target.dataset.key]: target.dataset.value } }, { pendingKey: `config-${target.dataset.key}` }); setModal(null); }
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && getState().modal?.type === 'pair') persistPairDraft();
  });
  document.addEventListener('scroll', syncConversationChrome, { passive: true });
  window.addEventListener('resize', queueConversationChromeSync, { passive: true });
}

async function copyText(value) {
  try {
    await navigator.clipboard.writeText(String(value));
    setNotice('已复制');
  } catch {
    const selection = document.getSelection?.();
    const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange()) : [];
    const focused = document.activeElement;
    const area = h('textarea', { value: String(value), 'aria-hidden': 'true' });
    area.style.position = 'fixed'; area.style.opacity = '0';
    document.body.append(area); area.select();
    const copied = document.execCommand?.('copy'); area.remove();
    if (selection && ranges.length) {
      selection.removeAllRanges();
      ranges.forEach((range) => selection.addRange(range));
    }
    focused?.focus?.({ preventScroll: true });
    setNotice(copied ? '已复制' : '复制失败，请长按选择文本', { level: copied ? 'info' : 'error' });
  }
}

export function handleUiBack() {
  if (closeChoiceMenus()) return true;
  const selection = document.getSelection?.();
  if (selection && !selection.isCollapsed) {
    selection.removeAllRanges();
    return true;
  }
  if (getState().focusPanel) {
    if (getState().focusPanel.type === 'draft') saveFocusDraft(true);
    else setFocusPanel(null);
    return true;
  }
  if (getState().modal) {
    if (getState().modal.mandatory === true) return true;
    if (getState().modal.type === 'pair') persistPairDraft();
    setModal(null);
    return true;
  }
  if (getState().conversationOutlineOpen) {
    setConversationOutlineOpen(false);
    requestAnimationFrame(() => document.querySelector('[data-action="toggle-outline"]')?.focus({ preventScroll: true }));
    return true;
  }
  const slash = document.querySelector('#slash-palette:not([hidden])');
  if (slash) { slash.hidden = true; return true; }
  if (isEditableElement(document.activeElement)) {
    document.activeElement.blur();
    return true;
  }
  return false;
}

function isEditableElement(node) {
  return node?.matches?.('input:not([type="hidden"]), textarea, [contenteditable="true"]');
}

window.MoyuUi = Object.freeze({ handleBack: handleUiBack });

let conversationChromeFrame = 0;
let composerObserver;
let lastComposerHeight = 0;
function reducedMotion() { return document.documentElement.classList.contains('reduce-motion-fixture') || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true; }
function scrollMetrics() {
  const scroller = conversationScroller();
  return { scroller, bottom: Math.max(0, scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop) };
}
function scrollConversationToLatest() {
  const { scroller } = scrollMetrics();
  if (scroller === document.scrollingElement || scroller === document.documentElement) window.scrollTo({ top: scroller.scrollHeight, behavior: reducedMotion() ? 'auto' : 'smooth' });
  else scroller.scrollTo({ top: scroller.scrollHeight, behavior: reducedMotion() ? 'auto' : 'smooth' });
}
function syncConversationChrome() {
  if (getState().route !== 'conversation') return;
  const latest = document.querySelector('.jump-to-latest');
  const { bottom } = scrollMetrics();
  latest?.classList.toggle('visible', bottom > 120);
  latest?.setAttribute('aria-hidden', String(bottom <= 120));
  let active;
  document.querySelectorAll('.conversation-segment').forEach((section) => {
    if (section.getBoundingClientRect().top <= 170) active = section.id;
  });
  document.querySelectorAll('.outline-item').forEach((item) => item.classList.toggle('active', item.dataset.segmentId === active));
  const composer = document.querySelector('.composer-dock');
  if (composer) {
    const height = Math.ceil(composer.getBoundingClientRect().height);
    if (height !== lastComposerHeight) {
      const keepLatest = bottom <= 120;
      lastComposerHeight = height;
      document.documentElement.style.setProperty('--composer-height', `${height}px`);
      if (keepLatest) requestAnimationFrame(() => {
        const target = conversationScroller();
        if (target === document.scrollingElement || target === document.documentElement) window.scrollTo(0, target.scrollHeight);
        else target.scrollTop = target.scrollHeight;
      });
    }
    if (typeof ResizeObserver === 'function' && composerObserver?.target !== composer) {
      composerObserver?.disconnect?.();
      composerObserver = new ResizeObserver(queueConversationChromeSync);
      composerObserver.target = composer;
      composerObserver.observe(composer);
    }
  }
}
function queueConversationChromeSync() {
  cancelAnimationFrame(conversationChromeFrame);
  conversationChromeFrame = requestAnimationFrame(syncConversationChrome);
}
