const endpoint = process.argv[2] || 'http://127.0.0.1:9223/json';
const targets = await fetch(endpoint).then((response) => {
  if (!response.ok) throw new Error(`DevTools target discovery failed: HTTP ${response.status}`);
  return response.json();
});
const target = targets.find((item) => item.type === 'page' && item.url?.includes('appassets.androidplatform.net'));
if (!target?.webSocketDebuggerUrl) throw new Error('Moyu WebView DevTools target not found');

const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});

let nextId = 0;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (!message.id || !pending.has(message.id)) return;
  const { resolve, reject } = pending.get(message.id);
  pending.delete(message.id);
  if (message.error) reject(new Error(message.error.message));
  else resolve(message.result);
});

function request(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}

const expression = `JSON.stringify({
  url: location.href,
  title: document.title,
  theme: document.documentElement.dataset.theme || null,
  routeTitle: document.querySelector('.header-route-title')?.textContent || null,
  connection: document.querySelector('.header-status .badge')?.textContent || null,
  activeNav: document.querySelector('.nav-item[aria-current="page"]')?.textContent?.trim() || null,
  pageHeadings: Array.from(document.querySelectorAll('main h1, main h2')).slice(0, 12).map((node) => node.textContent?.trim()),
  visibleButtons: Array.from(document.querySelectorAll('button')).filter((node) => node.offsetParent !== null).slice(0, 20).map((node) => node.textContent?.trim()),
  cards: document.querySelectorAll('.card').length,
  modal: document.querySelector('[role="dialog"] h2')?.textContent?.trim() || null,
  focusedControl: document.activeElement?.matches?.('input, textarea') ? (document.activeElement.id || document.activeElement.name || document.activeElement.tagName) : null,
  focusedValueLength: document.activeElement?.matches?.('input, textarea') ? document.activeElement.value.length : null,
  viewportHeight: window.visualViewport?.height || window.innerHeight
})`;
const evaluated = await request('Runtime.evaluate', { expression, returnByValue: true });
socket.close();
if (evaluated.exceptionDetails) throw new Error(evaluated.exceptionDetails.text || 'WebView evaluation failed');
console.log(JSON.stringify(JSON.parse(evaluated.result.value), null, 2));
