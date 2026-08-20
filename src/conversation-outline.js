function cleanLabel(value, fallback) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  if (!text) return fallback;
  return text.length > 52 ? `${text.slice(0, 49)}…` : text;
}
/**
 * Groups a linear CLI event stream into user-visible turns. The result is UI-only:
 * it never changes message ordering or the backend/session protocol.
 */
export function conversationSegments(items = []) {
  const segments = [];
  let current = null;
  for (const item of items) {
    const startsTurn = item?.kind === 'message' && item.role === 'user';
    if (!current || startsTurn) {
      const ordinal = segments.length + 1;
      current = {
        id: `turn-${String(item?.localSeq ?? ordinal).replace(/[^a-zA-Z0-9_-]/g, '-')}`,
        ordinal,
        title: startsTurn
          ? cleanLabel(item.text, item.artifacts?.length ? '图片消息' : `第 ${ordinal} 段`)
          : '会话开始',
        createdAt: item?.createdAt,
        items: []
      };
      segments.push(current);
    }
    current.items.push(item);
  }
  return segments;
}
