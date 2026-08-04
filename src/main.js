import { initBridge, sendIntent } from './bridge.js';
import { bindInteractions, render } from './render.js';
import { getState, subscribe } from './state.js';

const media = window.matchMedia('(prefers-color-scheme: dark)');
let pointerInteraction = false;
let editingControl = false;
let renderQueued = false;
let releaseTimer = 0;
let editReleaseTimer = 0;

function syncSystemTheme() {
  const view = getState().view;
  if (view?.appearance?.theme === 'system') document.documentElement.dataset.theme = media.matches ? 'dark' : 'light';
}

function renderCurrent(state = getState()) {
  render(state);
  syncSystemTheme();
}

subscribe((state) => {
  if (pointerInteraction || editingControl) {
    renderQueued = true;
    return;
  }
  renderCurrent(state);
});

function flushQueuedRender() {
  if (pointerInteraction || editingControl || !renderQueued) return;
  renderQueued = false;
  renderCurrent();
}

function beginPointerInteraction() {
  window.clearTimeout(releaseTimer);
  pointerInteraction = true;
}

function endPointerInteraction() {
  window.clearTimeout(releaseTimer);
  /* Keep the tapped DOM node alive through the synthetic click dispatched after pointerup. */
  releaseTimer = window.setTimeout(() => {
    pointerInteraction = false;
    flushQueuedRender();
  }, 0);
}

function isEditableControl(node) {
  return node?.matches?.('input:not([type="hidden"]), textarea, [contenteditable="true"]');
}

document.addEventListener('focusin', (event) => {
  if (!isEditableControl(event.target)) return;
  window.clearTimeout(editReleaseTimer);
  editingControl = true;
}, true);

document.addEventListener('focusout', () => {
  window.clearTimeout(editReleaseTimer);
  editReleaseTimer = window.setTimeout(() => {
    editingControl = isEditableControl(document.activeElement);
    flushQueuedRender();
  }, 0);
}, true);

document.addEventListener('pointerdown', beginPointerInteraction, true);
document.addEventListener('pointerup', endPointerInteraction, true);
document.addEventListener('pointercancel', endPointerInteraction, true);
media.addEventListener?.('change', syncSystemTheme);
bindInteractions();
renderCurrent();
initBridge();

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') sendIntent('app.ready', { uiVersion: '0.0.2' });
});
