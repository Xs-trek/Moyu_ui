import { initBridge, sendIntent } from './bridge.js';
import { bindInteractions, render, renderFocusPanel, renderNotice } from './render.js';
import { createRenderGate } from './render-gate.js';
import { getState, subscribe } from './state.js';

const media = window.matchMedia('(prefers-color-scheme: dark)');
let releaseTimer = 0;
let editReleaseTimer = 0;
let selectionReleaseTimer = 0;
let activeSelectionScope = null;
let clampingSelection = false;

function syncSystemTheme() {
  const view = getState().view;
  if (view?.appearance?.theme === 'system') document.documentElement.dataset.theme = media.matches ? 'dark' : 'light';
}

function renderCurrent(state = getState()) {
  render(state);
  syncSystemTheme();
}

const renderGate = createRenderGate(
  renderCurrent,
  {
    getState,
    isChoiceMenuOpen: () => Boolean(document.querySelector('.choice-menu:not([hidden])')),
    isInteractionSurfaceOpen: () => Boolean(
      document.querySelector('.conversation-outline-shell.open, #focus-root .focus-layer')
    )
  }
);

subscribe((state, change) => {
  if (change?.scope === 'toast') { renderNotice(state.notices); return; }
  if (change?.scope === 'focus') { renderFocusPanel(state); return; }
  renderGate.request(state, change);
});

function flushQueuedRender() {
  renderGate.flush();
}

function beginPointerInteraction() {
  window.clearTimeout(releaseTimer);
  renderGate.setPointerInteraction(true);
}

function endPointerInteraction() {
  window.clearTimeout(releaseTimer);
  /* Android selection handles can appear after pointerup/contextmenu. Give the
   * selectionchange event time to acquire the render lease before flushing. */
  releaseTimer = window.setTimeout(() => {
    syncSelectionLease();
    renderGate.setPointerInteraction(false);
    flushQueuedRender();
  }, 180);
}

function isEditableControl(node) {
  return node?.matches?.('input:not([type="hidden"]), textarea, [contenteditable="true"]');
}

document.addEventListener('focusin', (event) => {
  if (!isEditableControl(event.target)) return;
  window.clearTimeout(editReleaseTimer);
  renderGate.setEditingControl(true);
}, true);

document.addEventListener('focusout', () => {
  window.clearTimeout(editReleaseTimer);
  editReleaseTimer = window.setTimeout(() => {
    renderGate.setEditingControl(isEditableControl(document.activeElement));
    flushQueuedRender();
  }, 160);
}, true);

function hasTextSelection() {
  const selection = document.getSelection?.();
  return Boolean(selection && selection.rangeCount && !selection.isCollapsed && String(selection).trim());
}

function scopeForNode(node) {
  const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return element?.closest?.('[data-selection-scope]') || null;
}

function keepSelectionInScope() {
  if (clampingSelection || !activeSelectionScope?.isConnected) return;
  const selection = document.getSelection?.();
  if (!selection || selection.isCollapsed || !selection.rangeCount) return;
  if (scopeForNode(selection.anchorNode) === activeSelectionScope && scopeForNode(selection.focusNode) === activeSelectionScope) return;
  const range = document.createRange();
  range.selectNodeContents(activeSelectionScope);
  clampingSelection = true;
  selection.removeAllRanges();
  selection.addRange(range);
  clampingSelection = false;
}

function syncSelectionLease() {
  window.clearTimeout(selectionReleaseTimer);
  const selection = document.getSelection?.();
  if (!activeSelectionScope && selection && !selection.isCollapsed) {
    activeSelectionScope = scopeForNode(selection.anchorNode) || scopeForNode(selection.focusNode);
  }
  keepSelectionInScope();
  const active = hasTextSelection();
  renderGate.setTextSelection(active);
  if (!active) selectionReleaseTimer = window.setTimeout(() => {
    activeSelectionScope = null;
    flushQueuedRender();
  }, 160);
}

document.addEventListener('selectstart', (event) => {
  activeSelectionScope = event.target.closest?.('[data-selection-scope]') || null;
  renderGate.setTextSelection(Boolean(activeSelectionScope));
}, true);
document.addEventListener('selectionchange', syncSelectionLease);
document.addEventListener('contextmenu', () => {
  renderGate.setTextSelection(true);
  window.setTimeout(syncSelectionLease, 0);
}, true);
document.addEventListener('compositionstart', () => renderGate.setComposingText(true), true);
document.addEventListener('compositionend', () => {
  window.setTimeout(() => { renderGate.setComposingText(false); flushQueuedRender(); }, 160);
}, true);

document.addEventListener('moyu:choice-menu-change', () => {
  window.setTimeout(flushQueuedRender, 0);
});
document.addEventListener('moyu:interaction-surface-change', () => {
  window.setTimeout(flushQueuedRender, 0);
});

document.addEventListener('pointerdown', beginPointerInteraction, true);
document.addEventListener('pointerup', endPointerInteraction, true);
document.addEventListener('pointercancel', endPointerInteraction, true);
media.addEventListener?.('change', syncSystemTheme);
bindInteractions();
renderCurrent();
initBridge();

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') sendIntent('app.ready', { uiVersion: '0.0.3' });
});
