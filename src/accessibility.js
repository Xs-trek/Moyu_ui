export function activateDialog(dialog, { mandatory = false, onClose, returnFocus = document.activeElement } = {}) {
  let active = true;
  const focusable = () => Array.from(dialog.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'));
  const keydown = (event) => {
    if (event.key === 'Escape' && event.target.closest('.choice-menu:not([hidden])')) return;
    if (event.key === 'Escape' && !mandatory) { event.preventDefault(); event.stopPropagation(); cleanup({ restoreFocus: false }); onClose?.(); return; }
    if (event.key !== 'Tab') return;
    const nodes = focusable();
    if (!nodes.length) { event.preventDefault(); dialog.focus(); return; }
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (!dialog.contains(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); return; }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  };
  const cleanup = ({ restoreFocus = true } = {}) => {
    if (!active) return;
    active = false;
    dialog.removeEventListener('keydown', keydown);
    if (restoreFocus && returnFocus?.isConnected) returnFocus.focus?.({ preventScroll: true });
  };
  dialog.addEventListener('keydown', keydown);
  requestAnimationFrame(() => (focusable()[0] || dialog).focus());
  return cleanup;
}

export function safeStringify(value) {
  try { return JSON.stringify(value, null, 2); }
  catch { return '无法显示该输入'; }
}
