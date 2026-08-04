import { readFile, readdir, stat } from 'node:fs/promises';
import { join, relative } from 'node:path';

const root = new URL('..', import.meta.url);
const failures = [];
const passes = [];

async function filesUnder(directory) {
  const result = [];
  for (const name of await readdir(directory)) {
    const path = join(directory, name);
    const info = await stat(path);
    if (info.isDirectory()) result.push(...await filesUnder(path));
    else result.push(path);
  }
  return result;
}

function check(condition, label, detail = '') {
  (condition ? passes : failures).push(`${condition ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`);
}

const rootPath = decodeURIComponent(root.pathname).replace(/^\/([A-Za-z]:)/, '$1');
const srcFiles = (await filesUnder(join(rootPath, 'src'))).filter((file) => /\.(?:js|css)$/.test(file));
const source = (await Promise.all(srcFiles.map(async (file) => `\n/* ${relative(rootPath, file)} */\n${await readFile(file, 'utf8')}`))).join('');
const index = await readFile(join(rootPath, 'index.html'), 'utf8');
const preview = await readFile(join(rootPath, 'preview.html'), 'utf8');
const renderSource = await readFile(join(rootPath, 'src', 'render.js'), 'utf8');
const viteSource = await readFile(join(rootPath, 'vite.config.js'), 'utf8');
const coordinatorSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'AppCoordinator.java'), 'utf8');
const databaseSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'MoyuDatabase.java'), 'utf8');

const forbiddenNetwork = ['fe' + 'tch\\s*\\(', 'XML' + 'HttpRequest', 'Web' + 'Socket', 'Event' + 'Source'];
const forbiddenStorage = ['local' + 'Storage', 'session' + 'Storage', 'Indexed' + 'DB'];
check(forbiddenNetwork.every((pattern) => !new RegExp(pattern).test(source)), '无禁止的网络 API');
check(forbiddenStorage.every((pattern) => !source.includes(pattern)), '无禁止的持久化 API');
check(!/(?:https?:)?\/\//i.test(source + index + preview), '无远程 URL 或协议相对资源');
check(!/\son[a-z]+\s*=/i.test(index + preview), '无内联事件处理器');
check(!/\beval\s*\(|new\s+Function\b/.test(source), '无动态代码执行');
check(!/\.innerHTML\b|insertAdjacentHTML/.test(source), '动态内容未使用 HTML 注入');
check(!renderSource.includes("h('select'") && renderSource.includes('choice-menu'), '正式 UI 选择项不触发原生 select');
check(renderSource.includes("'划'") && renderSource.includes('node.pairDraft.save'), '品牌字与配对草稿已接入');
check(!renderSource.includes('.replaceChildren('), '正式 UI 兼容 Android 11 内置 WebView');
check(source.includes('min-height: 100vh; min-height: 100dvh') && source.includes('right: 0; bottom: 0; left: 0;') && !source.includes('inset:'), 'CSS 兼容 Android 11 WebView 布局属性');

const csp = "default-src 'self'; connect-src 'none'; img-src 'self' data:; font-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none';";
check(index.includes(csp) && preview.includes(csp), '正式与预览入口均包含严格 CSP');
check(!index.includes('mock-host.js') && index.includes('src/main.js'), '正式入口未注入 Mock Host');
check(preview.includes('src/mock-host.js') && preview.includes('src/main.js'), '预览入口独立注入 Mock Host');
check(/base:\s*['"]\.\/['"]/.test(viteSource), 'Vite 使用相对 base');
check(/cssTarget:\s*['"]chrome83['"]/.test(viteSource), 'CSS 构建目标覆盖 Android 11 WebView');
check(source.includes('pointerInteraction') && source.includes("openRoute('conversation', { notifyHost: false })") && coordinatorSource.includes('route = "conversation"'), '会话打开为单 Host intent 且进入全屏会话路由');
check(source.includes('slash-palette') && source.includes("'session.effort.set'") && coordinatorSource.includes('case "session.effort.set"'), '斜杠预索引与原生 effort intent 已接入');
check(source.includes("claude ? 'anthropic' : 'openai'") && !source.includes("class: 'platform-mark'"), '会话平台使用 Anthropic/OpenAI 标识');
check(!source.includes('item.costUsd') && source.includes('缓存读') && source.includes('缓存写'), '回复卡片显示四类 token 且不显示价格');
check(source.includes('.page-enter') && !/\.page\s*\{[^}]*animation:/s.test(source), 'Host 高频状态更新不会重复触发页面入场动画');
check(source.includes('editingControl') && source.includes("document.querySelector('#composer-input')?.blur()") && coordinatorSource.includes('"session.saveDraft".equals(type)') && !/"pong"\.equals\(type\)[^\n]*emitView/.test(coordinatorSource), '输入法激活期间 Host 更新不会重建输入控件');
check(coordinatorSource.includes('Integer messageAfter=0') && coordinatorSource.includes('syncOne(opened); subscribe(opened);'), '打开会话会从规范消息游标零修复本地时间线');
check(databaseSource.includes('db.update("sessions"') && !/insertWithOnConflict\("sessions"[^\n]*CONFLICT_REPLACE/.test(databaseSource), '保存会话不会触发 timeline 外键级联删除');
check(source.includes("crypto?.randomUUID") && source.includes('MAX_MESSAGE_BYTES'), 'Bridge 包含 requestId fallback 与消息上限');
check(source.includes('prefers-color-scheme') && source.includes('prefers-reduced-motion'), '主题与低动画模式完整');
check(source.includes('Profile（创建后冻结）') && source.includes('本次会话能力') && source.includes('supportedModels'), '新建会话按 capabilities 渲染');
check(source.includes('diff-line-add') && source.includes('diff-line-del') && source.includes('diff-line-hunk'), 'Diff 包含行级视觉语义');
check(source.includes('远端热会话') && source.includes('正在同步会话'), '会话列表包含远端状态与同步骨架');
check(source.includes('--safe-left') && source.includes('--safe-right'), '支持横向安全区');
check(!/(?:api[_-]?key|bearer|oauth[_-]?token|networkSecret)\s*[:=]\s*['"][^'"]{8,}/i.test(source), '无可疑密钥字面量');

const mockData = await readFile(join(rootPath, 'src', 'mock-data.js'), 'utf8');
const fixtureCount = (mockData.match(/^\s*\['[a-z0-9-]+',\s*'\d{2}\s*·/gm) || []).length;
check(fixtureCount === 30, '包含 30 组 fixture', `检测到 ${fixtureCount}`);
globalThis.matchMedia = () => ({ matches: false });
const mockModule = await import(new URL('../src/mock-data.js', import.meta.url));
let matrixValid = true;
for (const [id] of mockModule.fixtures) {
  for (const theme of ['light', 'dark']) {
    const fixtureView = mockModule.makeFixture(id, theme);
    matrixValid &&= fixtureView.appearance.resolvedTheme === theme
      && typeof fixtureView.route === 'string'
      && Array.isArray(fixtureView.nodes)
      && Array.isArray(fixtureView.sessions)
      && fixtureView.diagnostics?.protocolVersion === 1;
  }
}
check(matrixValid, '30 fixtures × light/dark 数据矩阵有效', '60 个组合');

let distFiles = [];
try { distFiles = await filesUnder(join(rootPath, 'dist')); } catch { /* build has not run */ }
const relDist = distFiles.map((file) => relative(join(rootPath, 'dist'), file).replaceAll('\\', '/'));
check(relDist.includes('index.html') && relDist.includes('preview.html'), 'dist 包含两个入口');
check(relDist.some((file) => file.startsWith('assets/')), 'dist 包含本地 assets');
for (const htmlName of ['index.html', 'preview.html']) {
  if (!relDist.includes(htmlName)) continue;
  const html = await readFile(join(rootPath, 'dist', htmlName), 'utf8');
  check(!/(?:src|href)=["']\//.test(html), `${htmlName} 不含绝对资源路径`);
  check(!/(?:https?:)?\/\//i.test(html), `${htmlName} 不依赖远程资源`);
}

console.log([...passes, ...failures].join('\n'));
console.log(`\n${passes.length} 项通过，${failures.length} 项失败。`);
if (failures.length) process.exitCode = 1;
