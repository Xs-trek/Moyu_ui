import { readFile, readdir, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
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
const mainSource = await readFile(join(rootPath, 'src', 'main.js'), 'utf8');
const stateSource = await readFile(join(rootPath, 'src', 'state.js'), 'utf8');
const viteSource = await readFile(join(rootPath, 'vite.config.js'), 'utf8');
const androidBuildSource = await readFile(join(rootPath, 'android', 'app', 'build.gradle'), 'utf8');
const coordinatorSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'AppCoordinator.java'), 'utf8');
const databaseSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'MoyuDatabase.java'), 'utf8');
const mainActivitySource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'MainActivity.java'), 'utf8');
const backendClientSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'BackendClient.java'), 'utf8');
const artifactCacheSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'ArtifactCache.java'), 'utf8');
const nativeHistoryKeysSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'NativeHistoryKeys.java'), 'utf8');
const nativeHistoryPagingSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'NativeHistoryPaging.java'), 'utf8');
const nativeHistoryIdentitySource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'NativeHistoryIdentity.java'), 'utf8');
const nativeHistoryRetentionSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'NativeHistoryRetention.java'), 'utf8');
const approvalInputSource = await readFile(join(rootPath, 'src', 'approval-input.js'), 'utf8');
const { conversationSegments } = await import(new URL('../src/conversation-outline.js', import.meta.url));
const { modelSuggestions } = await import(new URL('../src/model-options.js', import.meta.url));
const { turnPerformanceLabels } = await import(new URL('../src/turn-performance.js', import.meta.url));
const contractSource = await readFile(join(rootPath, 'contract', 'moyu-ui-contract.d.ts'), 'utf8');
const overlaySource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'OverlayService.java'), 'utf8');
const linkInfoSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'EasyTierLinkInfo.java'), 'utf8');
const jniSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'easytier', 'jni', 'EasyTierJNI.java'), 'utf8');
const brandSvg = await readFile(join(rootPath, 'public', 'assets', 'brands', 'moyu.svg'), 'utf8');
const notificationPng = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'res', 'drawable-xxxhdpi', 'ic_notification.png'));
const viewEmissionPolicySource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'ViewEmissionPolicy.java'), 'utf8');
const viewEmissionThrottleSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'ViewEmissionThrottle.java'), 'utf8');
const latestOnlySlotSource = await readFile(join(rootPath, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'remote', 'LatestOnlySlot.java'), 'utf8');

const forbiddenNetwork = ['fe' + 'tch\\s*\\(', 'XML' + 'HttpRequest', 'Web' + 'Socket', 'Event' + 'Source'];
const forbiddenStorage = ['local' + 'Storage', 'session' + 'Storage', 'Indexed' + 'DB'];
check(forbiddenNetwork.every((pattern) => !new RegExp(pattern).test(source)), '无禁止的网络 API');
check(forbiddenStorage.every((pattern) => !source.includes(pattern)), '无禁止的持久化 API');
check(!/(?:https?:)?\/\//i.test(source + index + preview), '无远程 URL 或协议相对资源');
check(!/\son[a-z]+\s*=/i.test(index + preview), '无内联事件处理器');
check(!/\beval\s*\(|new\s+Function\b/.test(source), '无动态代码执行');
check(!/\.innerHTML\b|insertAdjacentHTML/.test(source), '动态内容未使用 HTML 注入');
const patchState = await import(new URL('../src/state.js?check-safe-patch', import.meta.url));
let safePatchBehavior = false;
try {
  patchState.replaceView({ route: 'console', items: [{ name: 'alpha' }], nested: { 'a/b': 1 } }, 100);
  const validPatch = patchState.applyViewPatch([
    { op: 'set', path: '/items/0/name', value: 'beta' },
    { op: 'set', path: '/items/-', value: { name: 'gamma' } },
    { op: 'set', path: '/nested/a~1b', value: 2 }
  ], 101);
  const validState = patchState.getState();
  const stableView = JSON.stringify(validState.view);
  const invalidTransaction = patchState.applyViewPatch([
    { op: 'set', path: '/nested/a~1b', value: 3 },
    { op: 'remove', path: '/nested/missing' }
  ], 102);
  const rejectedCases = [
    [{ op: 'set', path: '/items/01', value: { name: 'bad-index' } }],
    [{ op: 'remove', path: '/items/-' }],
    [{ op: 'remove', path: '/nested/missing' }],
    [{ op: 'set', path: '/__proto__/polluted', value: true }],
    [{ op: 'set', path: '/nested/~2invalid', value: true }],
    [{ op: 'set', path: '/nested/undefined', value: undefined }],
    [{ op: 'set', path: '/nested/missing-value' }],
    [{ op: 'remove', path: '/items/0', value: null }],
    [{ op: 'set', path: '/nested/extra', value: true, unexpected: true }]
  ].every((candidate) => patchState.applyViewPatch(candidate, 102) === false);
  safePatchBehavior = validPatch
    && validState.revision === 101
    && validState.view.items.length === 2
    && validState.view.items[0].name === 'beta'
    && validState.view.nested['a/b'] === 2
    && invalidTransaction === false
    && rejectedCases
    && patchState.applyViewPatch({ op: 'remove', path: '/items/0' }, 102) === false
    && patchState.applyViewPatch([], 101) === false
    && patchState.getState().revision === 101
    && JSON.stringify(patchState.getState().view) === stableView
    && ({}).polluted === undefined;
} catch { safePatchBehavior = false; }
check(safePatchBehavior, 'view.patch 严格校验、事务回滚、原型链防护与 revision 门控有效');
const toastState = await import(new URL('../src/state.js?check-toast-queue', import.meta.url));
toastState.setNotice('第一条');
const warningNoticeId = toastState.setNotice('第二条', { level: 'warning' });
toastState.setNotice('第三条', { level: 'error' });
toastState.setNotice('第四条');
const boundedToastQueue = toastState.getState().notices;
const boundedToastBehavior = boundedToastQueue.length === 3
  && boundedToastQueue.map((notice) => notice.text).join(',') === '第二条,第三条,第四条'
  && boundedToastQueue.map((notice) => notice.level).join(',') === 'warning,error,info';
toastState.dismissNotice(warningNoticeId);
const dismissToastBehavior = toastState.getState().notices.length === 2
  && !toastState.getState().notices.some((notice) => notice.id === warningNoticeId);
toastState.setNotice('');
check(boundedToastBehavior && dismissToastBehavior && toastState.getState().notices.length === 0
  && renderSource.includes("role: notice.level === 'error' ? 'alert' : 'status'")
  && renderSource.includes('window.setTimeout(() => dismissNotice(notice.id)')
  && renderSource.includes("button('×', 'dismiss-notice'")
  && source.includes('.toast-warning') && source.includes('.toast-error'),
  'Toast 最多三条、兼容字符串调用、分级、自动移除且可逐条关闭');
check(!renderSource.includes("h('select'") && renderSource.includes('choice-menu'), '正式 UI 选择项不触发原生 select');
check(['划', '摸', '摆', '爽', '寄', '困', '饿', '累', '麻'].every((glyph) => renderSource.includes(`'${glyph}'`))
  && renderSource.includes('const brandGlyph = chooseBrandGlyph();')
  && renderSource.includes('globalThis.crypto.getRandomValues(value)')
  && renderSource.includes('text: brandGlyph')
  && renderSource.includes('node.pairDraft.save'), '品牌字每次 HTML 载入随机且本次稳定');
check(renderSource.includes("'./assets/brands/moyu.svg'") && brandSvg.includes('Moyu dead fish')
  && !/(?:https?:)?\/\//i.test(brandSvg.replace('http://www.w3.org/2000/svg', '')), '应用内品牌使用本地死鱼 SVG');
check(notificationPng.subarray(1, 4).toString() === 'PNG' && notificationPng[25] === 6, '通知图标是带 alpha 通道的 PNG mask');
check(!renderSource.includes('.replaceChildren('), '正式 UI 兼容 Android 11 内置 WebView');
check(source.includes('min-height: 100vh; min-height: 100dvh') && source.includes('right: 0; bottom: 0; left: 0;') && !source.includes('inset:'), 'CSS 兼容 Android 11 WebView 布局属性');
check(renderSource.includes("class: 'muted composer-status'")
  && source.includes('.composer-actions { display: flex; flex-wrap: wrap;')
  && source.includes('.composer-actions .composer-status { order: -1; flex: 1 0 100%;')
  && source.includes('.composer-actions .btn { flex: 0 0 auto; white-space: nowrap; }'),
  '360–430px composer 状态与操作按钮不会被挤成竖排');
const performanceFixture = turnPerformanceLabels({ observedDurationMs: 14_600 }, { outputTokens: 906 });
const unavailablePerformance = turnPerformanceLabels(undefined, { outputTokens: 906 });
const invalidPerformance = turnPerformanceLabels({ observedDurationMs: null }, { outputTokens: '906' });
const zeroDurationPerformance = turnPerformanceLabels({ observedDurationMs: 0 }, { outputTokens: 906 });
check(performanceFixture.duration === '14.6 s' && performanceFixture.speed === '62.1 t/s'
  && unavailablePerformance.duration === null && unavailablePerformance.speed === null
  && invalidPerformance.duration === null && invalidPerformance.speed === null
  && zeroDurationPerformance.duration === '0 ms' && zeroDurationPerformance.speed === null
  && coordinatorSource.includes('observedDurationMs'), '回复显示端到端耗时与输出均速且历史缺值不伪造');

const csp = "default-src 'self'; connect-src 'none'; img-src 'self' data:; font-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none';";
check(index.includes(csp) && preview.includes(csp), '正式与预览入口均包含严格 CSP');
check(!index.includes('mock-host.js') && index.includes('src/main.js'), '正式入口未注入 Mock Host');
check(preview.includes('src/mock-host.js') && preview.includes('src/main.js'), '预览入口独立注入 Mock Host');
check(/base:\s*['"]\.\/['"]/.test(viteSource), 'Vite 使用相对 base');
check(/cssTarget:\s*['"]chrome83['"]/.test(viteSource), 'CSS 构建目标覆盖 Android 11 WebView');
check(/modulePreload:\s*\{\s*polyfill:\s*false\s*\}/.test(viteSource), 'Vite 未注入 modulepreload 网络 polyfill');
check(source.includes('pointerInteraction') && source.includes("sendIntent('session.open'") && !source.includes("openRoute('conversation', { notifyHost: false })") && coordinatorSource.includes('route = "conversation"'), '会话打开等待 Host 原子切换且不会闪现上一会话');
check(source.includes('slash-palette') && source.includes("'session.effort.set'") && coordinatorSource.includes('case "session.effort.set"'), '斜杠预索引与原生 effort intent 已接入');
check(renderSource.includes("'session.permissionMode.set'") && renderSource.includes("'session.model.set'")
  && contractSource.includes("export type PermissionMode = 'plan' | 'auto' | 'acceptEdits'")
  && coordinatorSource.includes('case "session.permissionMode.set"') && coordinatorSource.includes('case "session.model.set"')
  && coordinatorSource.includes('"running".equals(s.state)') && coordinatorSource.includes('/permission-mode') && coordinatorSource.includes('/model'), 'Claude 会话仅暴露 Plan/Auto/Accept Edits，模型与模式由用户在空闲时手动切换');
check(renderSource.includes('if (!matches.length) { palette.hidden = true')
  && renderSource.includes('if (slashOption) { slashOption.click(); return; }'), '未知或自定义 CLI slash 不会被本地预索引吞掉 Enter');
check(renderSource.includes("'./assets/brands/anthropic.svg'")
  && renderSource.includes("'./assets/brands/openai.svg'")
  && source.includes('.provider-glyph img')
  && existsSync(join(rootPath, 'public/assets/brands/anthropic.svg'))
  && existsSync(join(rootPath, 'public/assets/brands/openai.svg')), '会话平台使用随包分发的真实 Claude Code/Codex CLI 图标');
check(renderSource.includes("action === 'toggle-output' || action === 'toggle-session-info'")
  && renderSource.includes("class: `session-summary ${sessionInfoOpen ? 'expanded' : ''}`")
  && source.includes('grid-template-rows: auto auto minmax(0, 1fr) auto')
  && source.includes('.session-summary-row')
  && !source.includes('.session-meta-strip'), '会话顶部默认压缩为单行摘要，详细元数据按需展开');
check(!renderSource.includes('console-timeline-card')
  && !renderSource.includes('if (session) page.append(composer(')
  && renderSource.includes("'专注会话', 'open-session'"), 'Console 只提供活动会话摘要和入口，不重复时间线与消息输入框');
check(source.includes('本会话始终允许此工具类型') && source.includes('后续所有同名工具调用（如所有 Bash）不再询问'), 'allow_session 文案准确说明同名工具在本会话内的放行范围');
const { askUserDecision, askUserQuestions } = await import(new URL('../src/approval-input.js', import.meta.url));
const askFixture = { kind: 'userInput', tool: 'AskUserQuestion', input: { questions: [
  { question: 'single?', options: [{ label: 'A' }, { label: 'B' }] },
  { question: 'multi?', options: [{ label: 'X' }, { label: 'Y' }], multiSelect: true }
] } };
const askQuestions = askUserQuestions(askFixture);
const askDecision = askUserDecision(askQuestions, [['B'], ['X', 'Y']]);
check(renderSource.includes("dataset: { form: 'approval-questions'")
  && renderSource.includes("approval.tool === 'ExitPlanMode'")
  && askDecision.allowWithModification.answers['single?'] === 'B'
  && Array.isArray(askDecision.allowWithModification.answers['multi?'])
  && coordinatorSource.includes('approvalDecision(p)')
  && coordinatorSource.includes('optJSONObject("allowWithModification")')
  && contractSource.includes('allowWithModification: { answers: Record<string, string | string[]> }'), 'AskUserQuestion 结构化回答与 ExitPlanMode 原生确认契约闭环');
check(source.includes('item.costUsd') && source.includes('turn.costUsd')
  && source.includes('缓存读') && source.includes('缓存写') && source.includes('费用'), '回复卡片显示四类 token 与 Host 提供的费用');
check(renderSource.includes('document.startViewTransition(commit)') && renderSource.includes('routeChanged')
  && source.includes('::view-transition-old(root)') && source.includes('@media (prefers-reduced-motion: reduce)')
  && source.includes('.page-enter') && !/\.page\s*\{[^}]*animation:/s.test(source), '页面切换使用可降级 View Transition，Host 高频更新不触发入场动画');
check(renderSource.includes('收起系统记录') && renderSource.includes('展开过程') && renderSource.includes('展开工具详情')
  && renderSource.includes('展开审批详情') && !renderSource.includes("class: 'segment-divider'"), '正文常显，系统/思考/已完成工具/已处理审批默认折叠且主时间线不重复分段黄框');
const { captureScrollAnchor, restoreScrollAnchor } = await import(new URL('../src/scroll-anchor.js', import.meta.url));
const historyScroller = { scrollHeight: 1200, clientHeight: 300, scrollTop: 500 };
const historyAnchor = captureScrollAnchor(historyScroller);
const replacedHistoryScroller = { scrollHeight: 1500, clientHeight: 300, scrollTop: 0 };
restoreScrollAnchor(replacedHistoryScroller, historyAnchor);
const liveScroller = { scrollHeight: 1200, clientHeight: 300, scrollTop: 860 };
const liveAnchor = captureScrollAnchor(liveScroller);
const replacedLiveScroller = { scrollHeight: 1500, clientHeight: 300, scrollTop: 0 };
restoreScrollAnchor(replacedLiveScroller, liveAnchor);
check(renderSource.includes("local.scrollHeight > local.clientHeight")
  && renderSource.includes('document.scrollingElement || document.documentElement')
  && renderSource.includes("state.route === 'conversation' && renderedRoute === 'conversation'")
  && renderSource.includes('restoreScrollAnchor(conversationScroller(), scrollAnchor)')
  && replacedHistoryScroller.scrollTop === 500
  && replacedLiveScroller.scrollTop === 1200, '连续 view.full 保留历史阅读位置，近底流式输出自动跟随');
const { createRenderGate } = await import(new URL('../src/render-gate.js', import.meta.url));
const gatedRevisions = [];
let choiceMenuOpen = false;
const renderGate = createRenderGate((state) => gatedRevisions.push(state.revision), () => choiceMenuOpen);
renderGate.setPointerInteraction(true);
renderGate.request({ revision: 1 });
choiceMenuOpen = true;
renderGate.setPointerInteraction(false);
const stayedQueuedWhileOpen = !renderGate.flush();
renderGate.request({ revision: 2 });
choiceMenuOpen = false;
const flushedLatestAfterClose = renderGate.flush();
check(stayedQueuedWhileOpen && flushedLatestAfterClose && gatedRevisions.length === 1 && gatedRevisions[0] === 2
  && source.includes("new Event('moyu:choice-menu-change')")
  && source.includes("document.querySelector('.choice-menu:not([hidden])')"), '下拉菜单打开期间合并 Host 更新，关闭后仅渲染最新 view.full');
const stableRenders = [];
let latestGateState = { revision: 10 };
let stableSurfaceOpen = true;
const stableGate = createRenderGate((next) => stableRenders.push(next.revision), {
  getState: () => latestGateState,
  isInteractionSurfaceOpen: () => stableSurfaceOpen
});
stableGate.request({ revision: 9 }, { source: 'host' });
latestGateState = { revision: 11, localUiKept: true };
const blockedStableSurface = !stableGate.flush();
stableGate.request({ revision: 10 }, { source: 'local', scope: 'app' });
const blockedLocalAppUpdate = !stableGate.flush();
stableSurfaceOpen = false;
const flushedCurrentState = stableGate.flush();
stableGate.setTextSelection(true);
stableGate.request({ revision: 12 }, { source: 'host' });
latestGateState = { revision: 13 };
const blockedSelection = !stableGate.flush();
stableGate.setTextSelection(false);
const flushedAfterSelection = stableGate.flush();
check(blockedStableSurface && blockedLocalAppUpdate && flushedCurrentState && blockedSelection && flushedAfterSelection
  && stableRenders.join('|') === '11|13'
  && mainSource.includes("document.addEventListener('selectionchange'")
  && mainSource.includes("document.addEventListener('compositionstart'")
  && mainSource.includes("moyu:interaction-surface-change")
  && stateSource.includes("source: change.source || 'local'")
  && renderSource.includes("{ scope: 'interaction' }"), '目录、选区与 IME 期间只保留最新 Host/本地状态且不回放旧 UI 快照');
check(source.includes('[data-selection-scope]') && source.includes('-webkit-user-select: none')
  && source.includes('body.focus-open #app [data-selection-scope]')
  && mainSource.includes('activeSelectionScope = scopeForNode(selection.anchorNode)')
  && renderSource.includes("button('专注编辑', 'focus-draft'")
  && renderSource.includes("'focus-content'") && renderSource.includes('focus-reader-copy')
  && index.includes('id="focus-root"') && preview.includes('id="focus-root"'), '内容框选择边界、完整复制入口与单 WebView 专注阅读/编辑层闭环');
check(renderSource.includes('export function handleUiBack()')
  && renderSource.includes('window.MoyuUi = Object.freeze({ handleBack: handleUiBack })')
  && mainActivitySource.includes('window.MoyuUi.handleBack()===true'), '系统返回先按 HTML 顶层交互层消费，再回退原生路由');
check(viewEmissionPolicySource.includes('"text.delta"') && viewEmissionPolicySource.includes('"tool.output"')
  && viewEmissionThrottleSource.includes('executor.schedule') && coordinatorSource.includes('VIEW_EMIT_DELAY_MS = 100L')
  && coordinatorSource.includes('if(ViewEmissionPolicy.shouldThrottle(type))viewEmitter.request()')
  && latestOnlySlotSource.includes('pending = value') && mainActivitySource.includes('viewDispatchSlot.offer(script)')
  && databaseSource.includes('body.optBoolean("streaming", false)'), 'Android 流式视图限频、WebView 单槽背压与流式摘要冻结闭环');
check(source.includes('editingControl') && source.includes("document.querySelector('#composer-input')?.blur()") && coordinatorSource.includes('"session.saveDraft".equals(type)') && !/"pong"\.equals\(type\)[^\n]*emitView/.test(coordinatorSource), '输入法激活期间 Host 更新不会重建输入控件');
check(coordinatorSource.includes('Integer messageAfter=0') && coordinatorSource.includes('syncOne(opened); subscribe(opened);')
  && coordinatorSource.includes('deleteLegacySyntheticSystemMessages') && databaseSource.includes('text.startsWith("[approval:")'), '打开会话从规范消息游标零修复时间线并清理旧审批/错误 system 副本');
check(databaseSource.includes('db.update("sessions"') && !/insertWithOnConflict\("sessions"[^\n]*CONFLICT_REPLACE/.test(databaseSource), '保存会话不会触发 timeline 外键级联删除');
check(databaseSource.includes('putCanonicalUserTimeline')
  && databaseSource.includes('WHERE session_id=? AND substr(item_key,1,11)=? ORDER BY local_seq ASC')
  && databaseSource.includes('new String[]{sessionId, "local:user:"}')
  && databaseSource.includes('canonicalText.equals(candidate.optString("text", ""))')
  && databaseSource.includes('WHERE session_id=? AND item_key=?')
  && coordinatorSource.includes('db.putCanonicalUserTimeline(session.localSessionId,key,seq,item)'), '本地 user pending 仅在同会话按键名/文本/最早序号归并到 canonical');
check(databaseSource.includes('native_id TEXT') && databaseSource.includes('findNativeSession')
  && coordinatorSource.includes('/native-sessions?limit=" + NativeHistoryPaging.PAGE_SIZE + "&offset=" + offset')
  && coordinatorSource.includes('snapshot.optInt("nextOffset", -1)')
  && nativeHistoryPagingSource.includes('serverNextOffset <= currentOffset')
  && coordinatorSource.includes('/messages?after=')
  && coordinatorSource.includes('/resume')
  && coordinatorSource.includes('completeNativePromotion')
  && databaseSource.includes('deleteTimelinePrefix')
  && nativeHistoryKeysSource.includes('"native:" + localSessionId')
  && coordinatorSource.includes('db.findNativeSession(nodeId,kind,cliRef)')
  && nativeHistoryIdentitySource.includes('nativeRowExists')
  && coordinatorSource.includes('db.mergeSessions(local,obsolete)')
  && databaseSource.includes('public synchronized void mergeSessions')
  && databaseSource.includes('db.beginTransaction()')
  && coordinatorSource.includes('messagesTruncatedBeforeSeq')
  && databaseSource.includes('deleteTimelinePrefixAfterRemoteSeq')
  && databaseSource.includes('remote_seq>?')
  && nativeHistoryRetentionSource.includes('nativeSeq <= truncatedBeforeSeq'), '原生历史分页、live/native 对账及长历史离线保留均有界且按本地会话隔离');
check(!coordinatorSource.includes('session.model=runtimeModel') && coordinatorSource.includes('meta.put("model",runtimeModel)')
  && renderSource.includes('本轮实际模型') && renderSource.includes('请求模型'), '请求模型与本轮实际模型分层显示，兼容端身份不会覆盖用户选择');
check(source.includes("'attachment.pick'") && source.includes("'attachment.remove'")
  && source.includes('composerAttachments') && source.includes('artifact-gallery')
  && source.includes('hasAttachments') && renderSource.includes('artifact.localUrl')
  && mainActivitySource.includes('Intent.ACTION_OPEN_DOCUMENT')
  && mainActivitySource.includes('cache.open(artifactId)')
  && artifactCacheSource.includes('MAX_BYTES = 8 * 1024 * 1024')
  && coordinatorSource.includes('backend.uploadArtifact')
  && coordinatorSource.includes('backend.downloadArtifact')
  && backendClientSource.includes('readBounded(input, ArtifactCache.MAX_BYTES)')
  && !backendClientSource.includes('response.body().bytes()'), '系统图片选择、8 MiB 有界二进制上传下载与 appassets 同源展示闭环');
check(jniSource.includes('native String collectNetworkInfos(int maxLength)')
  && overlaySource.includes('EasyTierJNI.collectNetworkInfos(4)')
  && linkInfoSource.includes('optJSONArray("peer_route_pairs")')
  && linkInfoSource.includes('routeAdvertisesTarget(route, target)')
  && linkInfoSource.includes('route.optJSONArray("proxy_cidrs")')
  && linkInfoSource.includes('route.has("cost_latency_first")')
  && linkInfoSource.includes('if (cost == 1) return MODE_P2P')
  && linkInfoSource.includes('if (cost > 1) return MODE_RELAY')
  && coordinatorSource.includes('.put("peerConnected",active&&overlayPeerConnected)')
  && coordinatorSource.includes('.put("linkMode",active?overlayLinkMode:"unknown")'), 'EasyTier ABI 不变且只依目标 PC 路由 cost 保守标注链路');
check(source.includes("crypto?.randomUUID") && source.includes('MAX_MESSAGE_BYTES'), 'Bridge 包含 requestId fallback 与消息上限');
check(coordinatorSource.includes('if(closed||!uiReady)return;')
  && !coordinatorSource.includes('worker.execute(this::emitView)')
  && coordinatorSource.includes('required(p, "uiVersion")'), 'Android 首个 view.full 严格等待 app.ready');
check(databaseSource.includes('transitionApprovalState')
  && coordinatorSource.includes('"pending","submitting"')
  && coordinatorSource.includes('"submitting","pending"'), '原生审批以持久化 submitting 状态防止重复提交并在发送失败时回滚');
check(coordinatorSource.includes('sameOnlineNode&&!"running".equals(s.state)')
  && coordinatorSource.includes('"session_busy"'), '运行中会话不可重复发送但仍可中断');
check(coordinatorSource.includes('disconnectNode(required(p, "nodeId"))')
  && coordinatorSource.includes('MoyuDatabase.NodeRecord node=db.getNode(nodeId)')
  && contractSource.includes("UiIntent<'node.manualSetup.open', { nodeId?: string }>")
  && renderSource.includes("sendIntent('node.manualSetup.open', { nodeId: target.dataset.nodeId }"), '节点编辑与断开均按精确 nodeId 处理');
check(coordinatorSource.includes('lastSyncAt = emptyToNull(db.setting("lastSyncAt", ""))')
  && coordinatorSource.includes('private void markSynced()')
  && !coordinatorSource.includes('.put("lastSyncAt",online?Instant.now()'), '诊断最近同步时间来自真实完成的同步并持久化');
check(backendClientSource.includes('buildWebSocketRequest(apiBase, token)')
  && backendClientSource.includes('header("Authorization", "Bearer " + token)')
  && !backendClientSource.includes('/ws?token='), 'WebSocket 长期凭据只放 Authorization header，不进入 URL');
check(source.includes('prefers-color-scheme') && source.includes('prefers-reduced-motion'), '主题与低动画模式完整');
check(source.includes('html[data-theme="dark"] .conversation-header')
  && source.includes('html[data-theme="dark"] .segment-divider')
  && source.includes('html[data-theme="dark"] .choice-option[aria-selected="true"]')
  && source.includes('html[data-theme="dark"] .setting-value-button')
  && source.includes('html[data-theme="dark"] .conversation-header .btn-quiet')
  && source.includes('html[data-theme="dark"] .fixture-panel select'),
  '夜间高饱和色块统一使用高对比深色前景');
check(source.includes('Profile（创建后冻结）') && source.includes('本次会话能力') && source.includes('supportedModels'), '新建会话按 capabilities 渲染');
const outlineFixture = conversationSegments([
  { localSeq: 1, kind: 'message', role: 'system', text: 'restored' },
  { localSeq: 2, kind: 'message', role: 'user', text: '第一段需求' },
  { localSeq: 3, kind: 'tool', tool: 'rg' },
  { localSeq: 4, kind: 'message', role: 'user', text: '第二段需求' },
  { localSeq: 5, kind: 'message', role: 'assistant', text: 'done' }
]);
check(outlineFixture.length === 3 && outlineFixture[1].id === 'turn-2' && outlineFixture[2].items.length === 2
  && renderSource.includes("'jump-latest'") && renderSource.includes("'jump-segment'")
  && source.includes('--composer-height'), '会话按用户回合生成目录、快速到底且底部留白跟随输入区');
const modelFixture = modelSuggestions({
  config: { defaultAdapter: 'claude', model: '', effectiveModel: 'glm-5.2' },
  sessions: [{ kind: 'claude', model: 'claude-sonnet' }, { kind: 'codex', model: 'gpt-x' }],
  server: { adapters: [{ adapter: 'claude', supportedModels: ['claude-sonnet'] }] },
  accounts: { adapters: [{ adapter: 'claude', profiles: [{ effectiveModel: 'profile-local' }] }] }
}, 'claude');
check(modelFixture.join('|') === 'glm-5.2|profile-local|claude-sonnet'
  && renderSource.includes("dataset: { form: 'model-config' }")
  && renderSource.includes("form.dataset.form === 'model-config'")
  && renderSource.includes('不会向 AI 服务探测模型目录'), '模型配置支持 CLI 默认、本地建议与自由输入且不做 provider 探测');
check(renderSource.includes("button('浏览 PC', 'open-files'") && renderSource.includes("next.createDraft = { ...next.createDraft, cwd: target.dataset.path }"), 'PC 工作目录浏览入口可回填新会话草稿');
check(renderSource.includes('function richText') && renderSource.includes("action === 'copy-text'") && !renderSource.includes('innerHTML'), '消息支持安全轻量富文本与复制');
check(source.includes('diff-line-add') && source.includes('diff-line-del') && source.includes('diff-line-hunk'), 'Diff 包含行级视觉语义');
check(source.includes('远端热会话') && source.includes('正在同步会话'), '会话列表包含远端状态与同步骨架');
check(source.includes('--safe-left') && source.includes('--safe-right'), '支持横向安全区');
check(source.includes('.session-list { grid-template-columns: minmax(0, 1fr); }')
  && source.includes('.session-card { min-width: 0; overflow-wrap: break-word; word-break: break-word; overflow-wrap: anywhere; }'), 'Codex 长标题不会撑宽手机端会话列表');
check(!/(?:api[_-]?key|bearer|oauth[_-]?token|networkSecret)\s*[:=]\s*['"][^'"]{8,}/i.test(source), '无可疑密钥字面量');

const mockData = await readFile(join(rootPath, 'src', 'mock-data.js'), 'utf8');
const fixtureCount = (mockData.match(/^\s*\['[a-z0-9-]+',\s*'\d{2}\s*·/gm) || []).length;
check(fixtureCount === 33 && mockData.includes("['codex-long-title', '33 · Codex 长标题卡片'"), '包含 Codex 长标题在内的 33 组 fixture', `检测到 ${fixtureCount}`);
globalThis.matchMedia = () => ({ matches: false });
const mockModule = await import(new URL('../src/mock-data.js', import.meta.url));
let matrixValid = true;
const approvalPolicies = new Set(['untrusted', 'on-failure', 'on-request', 'never']);
for (const [id] of mockModule.fixtures) {
  for (const theme of ['light', 'dark']) {
    const fixtureView = mockModule.makeFixture(id, theme);
    matrixValid &&= fixtureView.appearance.resolvedTheme === theme
      && typeof fixtureView.route === 'string'
      && Array.isArray(fixtureView.nodes)
      && Array.isArray(fixtureView.sessions)
      && fixtureView.diagnostics?.protocolVersion === 1
      && fixtureView.server.adapters.every((adapter) => ['freeform', 'none'].includes(adapter.capabilities.modelSelection)
        && Array.isArray(adapter.capabilities.sandboxModes)
        && Array.isArray(adapter.capabilities.reviewers)
        && Array.isArray(adapter.capabilities.approvalPolicies)
        && adapter.capabilities.approvalPolicies.every((policy) => approvalPolicies.has(policy)))
      && approvalPolicies.has(fixtureView.config.approvalPolicy)
      && (fixtureView.activeSession?.state !== 'running' || fixtureView.activeSession.canSend === false);
  }
}
check(matrixValid, '33 fixtures × light/dark 数据矩阵有效', '66 个组合');

let distFiles = [];
try { distFiles = await filesUnder(join(rootPath, 'dist')); } catch { /* build has not run */ }
const relDist = distFiles.map((file) => relative(join(rootPath, 'dist'), file).replaceAll('\\', '/'));
check(relDist.includes('index.html') && relDist.includes('preview.html') && relDist.includes('phone.html'), 'dist 包含正式、完整与手机画幅三个入口');
check(source.includes('.device-frame {\n  box-sizing: content-box;')
  && source.includes('.viewport-360 { width: 360px; height: 800px; }')
  && source.includes('.viewport-768 { width: 768px; height: 1024px; }'),
  '手机画幅壳的标注尺寸等于 iframe 实际 CSS viewport');
check(relDist.some((file) => file.startsWith('assets/')), 'dist 包含本地 assets');
check(relDist.includes('assets/brands/moyu.svg'), 'dist 包含本地 Moyu 品牌资产');
check(androidBuildSource.includes("exclude 'preview.html'")
  && androidBuildSource.includes("exclude 'phone.html'")
  && androidBuildSource.includes("exclude 'assets/preview-*'")
  && androidBuildSource.includes("exclude 'assets/phone-*'"), 'Android UI 同步排除全部预览入口及其专用 chunk');
const distScripts = distFiles.filter((file) => file.endsWith('.js'));
const distScriptSource = (await Promise.all(distScripts.map((file) => readFile(file, 'utf8')))).join('\n');
check(distScripts.length > 0 && forbiddenNetwork.every((pattern) => !new RegExp(pattern).test(distScriptSource)), 'dist JavaScript 不含禁止的网络 API');
for (const htmlName of ['index.html', 'preview.html', 'phone.html']) {
  if (!relDist.includes(htmlName)) continue;
  const html = await readFile(join(rootPath, 'dist', htmlName), 'utf8');
  check(!/(?:src|href)=["']\//.test(html), `${htmlName} 不含绝对资源路径`);
  check(!/(?:https?:)?\/\//i.test(html), `${htmlName} 不依赖远程资源`);
}

console.log([...passes, ...failures].join('\n'));
console.log(`\n${passes.length} 项通过，${failures.length} 项失败。`);
if (failures.length) process.exitCode = 1;
