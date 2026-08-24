const registry = Object.freeze({
  claude: Object.freeze({ displayName: 'Claude Code', shortLabel: 'Claude', icon: './assets/brands/anthropic.svg', tone: 'pink', actionClass: 'btn-primary', iconShape: 'wide' }),
  codex: Object.freeze({ displayName: 'Codex CLI', shortLabel: 'Codex', icon: './assets/brands/openai.svg', tone: 'cyan', actionClass: 'btn-cyan', iconShape: 'square' }),
  opencode: Object.freeze({ displayName: 'OpenCode', shortLabel: 'OpenCode', icon: './assets/brands/opencode.svg', tone: 'lime', actionClass: 'btn-cyan', iconShape: 'square' })
});

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value || {}, key);

export function platformMeta(kind, displayName) {
  const id = typeof kind === 'string' && kind.trim() ? kind.trim() : 'unknown';
  const known = registry[id];
  if (known) return { id, ...known, displayName: displayName || known.displayName };
  const fallbackName = typeof displayName === 'string' && displayName.trim()
    ? displayName.trim()
    : id === 'unknown' ? '未知平台' : id;
  return { id, displayName: fallbackName, shortLabel: fallbackName, icon: null, tone: 'purple', actionClass: 'btn-cyan', iconShape: 'generic' };
}

export function adapterStatus(view, kind) {
  return (view?.server?.adapters || []).find((entry) => entry?.adapter === kind);
}

export function adapterCapabilities(view, kind) {
  return adapterStatus(view, kind)?.capabilities || {};
}

export function adapterList(view, options = {}) {
  const result = [];
  const seen = new Set();
  const append = (adapter) => {
    const id = adapter?.adapter;
    if (typeof id !== 'string' || !id || seen.has(id)) return;
    if (options.availableOnly && adapter.available !== true) return;
    seen.add(id);
    result.push(adapter);
  };
  (view?.server?.adapters || []).forEach(append);
  if (options.includeObserved) {
    (view?.sessions || []).forEach((session) => append({
      adapter: session.kind,
      displayName: platformMeta(session.kind).displayName,
      available: false,
      capabilities: {}
    }));
  }
  return result;
}

export function capabilityList(capabilities, key, legacy = []) {
  if (hasOwn(capabilities, key)) return Array.isArray(capabilities[key]) ? capabilities[key] : [];
  return Array.isArray(legacy) ? legacy : [];
}

export function canSelectModel(capabilities) {
  return capabilities?.models === true && capabilities?.modelSelection !== 'none';
}

export function attachmentCapability(capabilities) {
  const declared = capabilities?.attachments;
  if (declared && typeof declared === 'object') {
    return {
      images: declared.images === true,
      maxCount: Number.isSafeInteger(declared.maxCount) && declared.maxCount > 0 ? declared.maxCount : 0
    };
  }
  const legacyImages = capabilities?.images === true || declared === true;
  return { images: legacyImages, maxCount: legacyImages ? 4 : 0 };
}

export function historyCapability(capabilities) {
  const declared = capabilities?.history;
  if (declared && typeof declared === 'object') {
    return { list: declared.list === true, messages: declared.messages === true, resume: declared.resume === true };
  }
  return {
    list: declared === true,
    messages: declared === true,
    resume: capabilities?.resume === true
  };
}
