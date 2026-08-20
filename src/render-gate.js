/**
 * Keeps destructive full-page renders away from native-feeling interactions.
 * Only the fact that a render is pending is retained: flush always reads the
 * current store so a silent draft/outline update can never replay stale state.
 */
export function createRenderGate(renderNow, options = {}) {
  /* Backwards compatibility for the small legacy unit fixture. */
  if (typeof options === 'function') options = { isChoiceMenuOpen: options };
  const latestState = options.getState || (() => pendingState);
  const isChoiceMenuOpen = options.isChoiceMenuOpen || (() => false);
  const isInteractionSurfaceOpen = options.isInteractionSurfaceOpen || (() => false);
  let pointerInteraction = false;
  let editingControl = false;
  let composingText = false;
  let textSelection = false;
  let renderQueued = false;
  let pendingState;
  let pendingChange = { source: 'local' };

  function blocked(state, change) {
    if (pointerInteraction || editingControl || composingText || textSelection || isChoiceMenuOpen()) return true;
    /* Stable surfaces own the viewport while open. Intent results and other
     * local app-state changes must not rebuild the page underneath them either;
     * only an explicit interaction-surface update may render immediately. */
    return change?.scope !== 'interaction' && isInteractionSurfaceOpen(state);
  }

  function request(state, change = { source: 'local' }) {
    pendingState = state;
    pendingChange = change;
    if (blocked(state, change)) {
      renderQueued = true;
      return false;
    }
    renderQueued = false;
    renderNow(state);
    return true;
  }

  function flush() {
    if (!renderQueued) return false;
    const state = latestState() || pendingState;
    if (blocked(state, pendingChange)) return false;
    renderQueued = false;
    renderNow(state);
    return true;
  }

  return {
    request,
    flush,
    isQueued() { return renderQueued; },
    setPointerInteraction(value) { pointerInteraction = Boolean(value); },
    setEditingControl(value) { editingControl = Boolean(value); },
    setComposingText(value) { composingText = Boolean(value); },
    setTextSelection(value) { textSelection = Boolean(value); }
  };
}
