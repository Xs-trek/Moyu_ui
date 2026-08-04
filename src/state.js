const listeners = new Set();

const initial = {
  revision: 0,
  view: null,
  route: 'console',
  modal: null,
  filters: { query: '', node: 'all', kind: 'all', state: 'all' },
  createDraft: { nodeId: '', kind: 'claude', cwd: '', title: '', profileId: '', model: '' },
  pairDraft: { displayName: '', relayNode: '', pairString: '' },
  fileNodes: [],
  expandedItems: new Set(),
  submitting: new Map(),
  notice: ''
};

let state = initial;

export function getState() { return state; }

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function update(mutator) {
  const next = { ...state };
  mutator(next);
  state = next;
  listeners.forEach((listener) => listener(state));
}

export function replaceView(view, revision) {
  update((next) => {
    next.view = view;
    next.revision = revision;
    next.route = view.route || next.route;
    if (next.modal?.type !== 'pair') next.pairDraft = { ...next.pairDraft, ...(view.pairDraft || {}) };
  });
}

function decodePointerPart(value) {
  return value.replace(/~1/g, '/').replace(/~0/g, '~');
}

function cloneValue(value) {
  if (typeof structuredClone === 'function') return structuredClone(value);
  return JSON.parse(JSON.stringify(value));
}

export function applyViewPatch(patch, revision) {
  if (!state.view) return false;
  const root = cloneValue(state.view);
  for (const operation of patch) {
    if (!operation || !['set', 'remove'].includes(operation.op) || typeof operation.path !== 'string' || !operation.path.startsWith('/')) return false;
    const parts = operation.path.slice(1).split('/').map(decodePointerPart);
    if (parts.some((part) => part === '__proto__' || part === 'prototype' || part === 'constructor')) return false;
    const key = parts.pop();
    let cursor = root;
    for (const part of parts) {
      if (cursor === null || typeof cursor !== 'object' || !(part in cursor)) return false;
      cursor = cursor[part];
    }
    if (cursor === null || typeof cursor !== 'object') return false;
    if (operation.op === 'remove') {
      if (Array.isArray(cursor)) {
        const index = Number(key);
        if (!Number.isInteger(index) || index < 0 || index >= cursor.length) return false;
        cursor.splice(index, 1);
      } else {
        delete cursor[key];
      }
    } else if (Array.isArray(cursor)) {
      const index = key === '-' ? cursor.length : Number(key);
      if (!Number.isInteger(index) || index < 0 || index > cursor.length) return false;
      cursor[index] = cloneValue(operation.value);
    } else {
      cursor[key] = cloneValue(operation.value);
    }
  }
  replaceView(root, revision);
  return true;
}

export function setModal(modal) { update((next) => { next.modal = modal; }); }
export function setNotice(notice) { update((next) => { next.notice = notice; }); }
export function setFilter(name, value) { update((next) => { next.filters = { ...next.filters, [name]: value }; }); }
export function cachePairDraft(draft) { state = { ...state, pairDraft: { ...state.pairDraft, ...draft } }; }
export function toggleExpanded(id) {
  update((next) => {
    next.expandedItems = new Set(next.expandedItems);
    next.expandedItems.has(id) ? next.expandedItems.delete(id) : next.expandedItems.add(id);
  });
}
