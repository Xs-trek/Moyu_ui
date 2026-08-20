const listeners = new Set();
const noticeLevels = new Set(['info', 'warning', 'error']);
let noticeSequence = 0;

const initial = {
  revision: 0,
  view: null,
  route: 'console',
  modal: null,
  filters: { query: '', node: 'all', kind: 'all', state: 'all' },
  createDraft: { nodeId: '', kind: 'claude', cwd: '', title: '', profileId: '', model: '', effort: '', permissionMode: 'acceptEdits' },
  conversationOutlineOpen: false,
  conversationSidebarTab: 'outline',
  conversationScrollTop: 0,
  focusPanel: null,
  pairDraft: { displayName: '', relayNode: '', pairString: '' },
  fileNodes: [],
  fileBrowser: { status: 'idle', nodeId: '', path: '.', error: '' },
  expandedItems: new Set(),
  submitting: new Map(),
  notices: []
};

let state = initial;

export function getState() { return state; }

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function update(mutator, change = {}) {
  const next = { ...state };
  mutator(next);
  state = next;
  const detail = { source: change.source || 'local', scope: change.scope || 'app', delivery: change.delivery || 'normal' };
  listeners.forEach((listener) => listener(state, detail));
}

export function replaceView(view, revision, delivery = 'normal') {
  update((next) => {
    next.view = view;
    next.revision = revision;
    next.route = view.route || next.route;
    next.submitting = new Map(next.submitting);
    next.submitting.forEach((requestId, key) => {
      if (!key.startsWith('approval-')) return;
      const approvalId = key.slice('approval-'.length);
      const approval = view.activeSession?.pendingApproval;
      if (!approval || approval.approvalId !== approvalId || !['pending', 'submitting'].includes(approval.state)) next.submitting.delete(key);
    });
    if (next.modal?.type !== 'pair') next.pairDraft = { ...next.pairDraft, ...(view.pairDraft || {}) };
  }, { source: 'host', delivery });
}

function decodePointerPart(value) {
  if (/~(?:[^01]|$)/.test(value)) throw new TypeError('Invalid JSON Pointer escape');
  return value.replace(/~1/g, '/').replace(/~0/g, '~');
}

const forbiddenPointerParts = new Set(['__proto__', 'prototype', 'constructor']);
const arrayIndexPattern = /^(?:0|[1-9]\d*)$/;
const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key);

function isJsonValue(value, seen = new Set(), depth = 0) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return true;
  if (typeof value === 'number') return Number.isFinite(value);
  if (typeof value !== 'object' || depth > 64 || seen.has(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  if (!Array.isArray(value) && prototype !== Object.prototype && prototype !== null) return false;
  seen.add(value);
  const entries = Array.isArray(value) ? value.entries() : Object.entries(value);
  for (const [, item] of entries) {
    if (!isJsonValue(item, seen, depth + 1)) {
      seen.delete(value);
      return false;
    }
  }
  seen.delete(value);
  return true;
}

function cloneJson(value) {
  if (!isJsonValue(value)) throw new TypeError('Patch values must be finite JSON data');
  return JSON.parse(JSON.stringify(value));
}

function arrayIndex(part, length, allowAppend) {
  if (allowAppend && part === '-') return length;
  if (!arrayIndexPattern.test(part)) return -1;
  const index = Number(part);
  if (!Number.isSafeInteger(index) || index < 0 || index > length || (!allowAppend && index === length)) return -1;
  return index;
}

export function applyViewPatch(patch, revision) {
  if (!state.view || !Array.isArray(patch) || patch.length > 256
    || !Number.isSafeInteger(revision) || revision !== state.revision + 1) return false;
  try {
    const root = cloneJson(state.view);
    for (const operation of patch) {
      const operationPrototype = operation && typeof operation === 'object' ? Object.getPrototypeOf(operation) : null;
      if (!operation || typeof operation !== 'object' || Array.isArray(operation)
        || (operationPrototype !== Object.prototype && operationPrototype !== null)
        || !hasOwn(operation, 'op') || !hasOwn(operation, 'path')
        || !['set', 'remove'].includes(operation.op)
        || typeof operation.path !== 'string' || operation.path.length > 2048
        || !operation.path.startsWith('/')
        || (operation.op === 'set' ? !hasOwn(operation, 'value') : hasOwn(operation, 'value'))
        || Object.keys(operation).some((key) => !['op', 'path', ...(operation.op === 'set' ? ['value'] : [])].includes(key))) return false;
      const parts = operation.path.slice(1).split('/').map(decodePointerPart);
      if (parts.length > 64 || parts.some((part) => forbiddenPointerParts.has(part))) return false;
      const key = parts.pop();
      let cursor = root;
      for (const part of parts) {
        if (cursor === null || typeof cursor !== 'object') return false;
        if (Array.isArray(cursor)) {
          const index = arrayIndex(part, cursor.length, false);
          if (index < 0) return false;
          cursor = cursor[index];
        } else {
          if (!hasOwn(cursor, part)) return false;
          cursor = cursor[part];
        }
      }
      if (cursor === null || typeof cursor !== 'object') return false;
      if (operation.op === 'remove') {
        if (Array.isArray(cursor)) {
          const index = arrayIndex(key, cursor.length, false);
          if (index < 0) return false;
          cursor.splice(index, 1);
        } else {
          if (!hasOwn(cursor, key)) return false;
          delete cursor[key];
        }
      } else if (Array.isArray(cursor)) {
        const index = arrayIndex(key, cursor.length, true);
        if (index < 0) return false;
        const value = cloneJson(operation.value);
        if (index === cursor.length) cursor.push(value);
        else cursor[index] = value;
      } else {
        cursor[key] = cloneJson(operation.value);
      }
    }
    replaceView(root, revision);
    return true;
  } catch {
    return false;
  }
}

export function setModal(modal) { update((next) => { next.modal = modal; }); }
export function setFocusPanel(focusPanel) {
  update((next) => { next.focusPanel = focusPanel; }, { source: 'local', scope: 'focus' });
}
export function setNotice(notice, options = {}) {
  const text = typeof notice === 'string' ? notice.trim() : String(notice || '').trim();
  if (!text) {
    update((next) => { next.notices = []; }, { source: options.source || 'local', scope: 'toast' });
    return '';
  }
  const level = noticeLevels.has(options.level) ? options.level : 'info';
  const id = `notice-${++noticeSequence}`;
  update((next) => {
    next.notices = [...(next.notices || []), { id, text, level }].slice(-3);
  }, { source: options.source || 'local', scope: 'toast' });
  return id;
}
export function dismissNotice(id, options = {}) {
  if (!id) return;
  update((next) => {
    next.notices = (next.notices || []).filter((notice) => notice.id !== id);
  }, { source: options.source || 'local', scope: 'toast' });
}
export function setFilter(name, value) { update((next) => { next.filters = { ...next.filters, [name]: value }; }); }
export function cachePairDraft(draft) { state = { ...state, pairDraft: { ...state.pairDraft, ...draft } }; }
export function cacheConversationOutline(open, scrollTop = state.conversationScrollTop) {
  state = { ...state, conversationOutlineOpen: Boolean(open), conversationScrollTop: Number(scrollTop) || 0 };
}
export function toggleExpanded(id) {
  update((next) => {
    next.expandedItems = new Set(next.expandedItems);
    next.expandedItems.has(id) ? next.expandedItems.delete(id) : next.expandedItems.add(id);
  });
}
