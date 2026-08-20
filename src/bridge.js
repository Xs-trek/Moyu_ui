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
  if (options.pendingKey) {
    const existing = getState().submitting.get(options.pendingKey);
    if (existing) return existing;
  }
  const id = requestId();
  const intent = { version: 1, type, requestId: id, payload };
  const message = JSON.stringify(intent);
  if (encoder.encode(message).byteLength > MAX_MESSAGE_BYTES) {
    setNotice('内容过大，未发送', { level: 'error' });
    return null;
  }
  if (!window.MoyuHost || typeof window.MoyuHost.postMessage !== 'function') {
    setNotice('未检测到 Android Host', { level: 'warning' });
    return null;
  }
  if (options.pendingKey) update((next) => {
    next.submitting = new Map(next.submitting);
    next.submitting.set(options.pendingKey, id);
  });
  try {
    window.MoyuHost.postMessage(message);
  } catch {
    if (options.pendingKey) update((next) => {
      if (next.submitting.get(options.pendingKey) !== id) return;
      next.submitting = new Map(next.submitting);
      next.submitting.delete(options.pendingKey);
    });
    setNotice('Android Host 暂时无法接收请求', { level: 'error' });
    return null;
  }
  return id;
}

function reloadView() { sendIntent('view.reload', {}); }

function onEnvelope(event) {
  const envelope = event.detail;
  if (!envelope || envelope.version !== 1 || typeof envelope.type !== 'string') return;
  const current = getState();
  if (envelope.type === 'view.full') {
    if (!Number.isSafeInteger(envelope.revision) || envelope.revision <= current.revision
      || !envelope.view || typeof envelope.view !== 'object' || Array.isArray(envelope.view)) return;
    replaceView(envelope.view, envelope.revision, envelope.delivery || 'normal');
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
    const keepApprovalLock = envelope.ok && matchedKey.startsWith('approval-');
    if (matchedKey && !keepApprovalLock) update((next) => {
      next.submitting = new Map(next.submitting);
      next.submitting.delete(matchedKey);
    });
    if (!envelope.ok) {
      if (matchedKey === 'fs-list') update((next) => {
        next.fileBrowser = { ...next.fileBrowser, status: 'error', error: errorMessage(envelope.error) };
      });
      setNotice(errorMessage(envelope.error), { level: 'error' });
      if (envelope.error?.code === 'approval_not_pending') reloadView();
    } else if (Array.isArray(envelope.data?.fileNodes)) {
      update((next) => {
        next.fileNodes = envelope.data.fileNodes;
        next.fileBrowser = { ...next.fileBrowser, status: 'ready', error: '' };
      });
    }
  }
}

export function errorMessage(error) {
  const messages = {
    unauthorized: '身份凭据已失效，请在原生设置中重新连接',
    queue_full: '后端队列已满，请稍后再试',
    session_limit: '会话数量已达上限',
    approval_not_pending: '审批已不再等待，正在刷新状态',
    session_busy: '当前回合仍在运行，请先等待完成或中断',
    wrong_node: '请求的节点不是当前连接节点',
    adapter_unavailable: '当前平台暂不可用',
    input_too_large: '输入内容过长',
    body_too_large: '消息体过大',
    session_not_found: '会话不存在或已结束',
    pty_not_available: '当前环境无法启动 CLI',
    profile_unavailable: '所选账号配置已失效，请刷新账号后重新选择',
    unsupported_effort: '当前平台不支持所选推理深度',
    invalid_artifact: '图片结构无效，或无法安全清除设备元数据',
    unsupported_artifact_type: '仅支持 PNG、JPEG、GIF 或 WebP 图片',
    artifact_too_large: '图片超过 8 MiB 限制',
    artifact_capacity: 'PC 临时图片空间已满，请重启后端后重试',
    local_security_unavailable: 'PC 无法创建受保护的本地配置；请在 PC 运行 moyu -check',
    approval_guard_unavailable: 'Claude 的本地审批保护不可用；请检查 Claude 策略并在 PC 运行 moyu -check'
  };
  return messages[error?.code] || error?.summary || '发生未知错误';
}

export function initBridge() {
  window.addEventListener('moyu:view', onEnvelope);
  sendIntent('app.ready', { uiVersion: '0.0.3' });
}
