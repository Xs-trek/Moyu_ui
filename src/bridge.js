import { applyViewPatch, getState, replaceView, setNotice, update } from './state.js';

const MAX_MESSAGE_BYTES = 1024 * 1024;
const encoder = new TextEncoder();

function requestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) globalThis.crypto.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function sendIntent(type, payload = {}, options = {}) {
  const id = requestId();
  const intent = { version: 1, type, requestId: id, payload };
  const message = JSON.stringify(intent);
  if (encoder.encode(message).byteLength > MAX_MESSAGE_BYTES) {
    setNotice('内容过大，未发送');
    return null;
  }
  if (!window.MoyuHost || typeof window.MoyuHost.postMessage !== 'function') {
    setNotice('未检测到 Android Host');
    return null;
  }
  if (options.pendingKey) update((next) => {
    next.submitting = new Map(next.submitting);
    next.submitting.set(options.pendingKey, id);
  });
  window.MoyuHost.postMessage(message);
  return id;
}

function reloadView() { sendIntent('view.reload', {}); }

function onEnvelope(event) {
  const envelope = event.detail;
  if (!envelope || envelope.version !== 1 || typeof envelope.type !== 'string') return;
  const current = getState();
  if (envelope.type === 'view.full') {
    if (!Number.isInteger(envelope.revision) || envelope.revision < current.revision) return;
    replaceView(envelope.view, envelope.revision);
    return;
  }
  if (envelope.type === 'view.patch') {
    if (envelope.revision !== current.revision + 1) { reloadView(); return; }
    if (!applyViewPatch(envelope.patch, envelope.revision)) reloadView();
    return;
  }
  if (envelope.type === 'intent.result') {
    let matchedKey = '';
    current.submitting.forEach((value, key) => { if (value === envelope.requestId) matchedKey = key; });
    if (matchedKey) update((next) => {
      next.submitting = new Map(next.submitting);
      next.submitting.delete(matchedKey);
    });
    if (!envelope.ok) {
      setNotice(errorMessage(envelope.error));
      if (envelope.error?.code === 'approval_not_pending') reloadView();
    } else if (Array.isArray(envelope.data?.fileNodes)) {
      update((next) => { next.fileNodes = envelope.data.fileNodes; });
    }
  }
}

export function errorMessage(error) {
  const messages = {
    unauthorized: '身份凭据已失效，请在原生设置中重新连接',
    queue_full: '后端队列已满，请稍后再试',
    session_limit: '会话数量已达上限',
    approval_not_pending: '审批已不再等待，正在刷新状态',
    adapter_unavailable: '当前平台暂不可用',
    input_too_large: '输入内容过长',
    body_too_large: '消息体过大',
    session_not_found: '会话不存在或已结束',
    pty_not_available: '当前环境无法启动 CLI'
  };
  return messages[error?.code] || error?.summary || '发生未知错误';
}

export function initBridge() {
  window.addEventListener('moyu:view', onEnvelope);
  sendIntent('app.ready', { uiVersion: '0.0.2' });
}
