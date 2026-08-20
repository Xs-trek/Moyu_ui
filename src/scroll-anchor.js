const NEAR_BOTTOM_PX = 96;

/** Capture the reader position before a view.full render replaces the conversation DOM. */
export function captureScrollAnchor(scroller) {
  if (!scroller) return null;
  const scrollHeight = Math.max(0, Number(scroller.scrollHeight) || 0);
  const clientHeight = Math.max(0, Number(scroller.clientHeight) || 0);
  const scrollTop = Math.max(0, Number(scroller.scrollTop) || 0);
  const bottomDistance = Math.max(0, scrollHeight - clientHeight - scrollTop);
  return { scrollTop, bottomDistance, nearBottom: bottomDistance <= NEAR_BOTTOM_PX };
}

/** Follow streaming output at the bottom; otherwise keep the current reading
 * position. A bottom-relative anchor would move the viewport whenever new
 * content is appended below the reader. */
export function restoreScrollAnchor(scroller, anchor) {
  if (!scroller) return;
  const scrollHeight = Math.max(0, Number(scroller.scrollHeight) || 0);
  const clientHeight = Math.max(0, Number(scroller.clientHeight) || 0);
  const maxScrollTop = Math.max(0, scrollHeight - clientHeight);
  if (!anchor || anchor.nearBottom) {
    scroller.scrollTop = maxScrollTop;
    return;
  }
  scroller.scrollTop = Math.max(0, Math.min(maxScrollTop, Number(anchor.scrollTop) || 0));
}
