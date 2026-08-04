export type RequestId = string;
export type Theme = 'system' | 'light' | 'dark';
export type Route = 'console' | 'sessions' | 'nodes' | 'accounts' | 'settings' | 'diagnostics';
export type ApprovalDecision = 'allow' | 'allow_session' | 'deny' | 'cancel';
export type AdapterId = 'claude' | 'codex';

export interface UiIntent<T extends string = string, P = unknown> {
  version: 1;
  type: T;
  requestId: RequestId;
  payload: P;
}

export type HostEnvelope =
  | { version: 1; type: 'view.full'; revision: number; view: AppViewModel }
  | { version: 1; type: 'view.patch'; revision: number; patch: ViewPatch[] }
  | { version: 1; type: 'intent.result'; requestId: RequestId; ok: true; data?: unknown }
  | { version: 1; type: 'intent.result'; requestId: RequestId; ok: false; error: UiError };

export interface ViewPatch { op: 'set' | 'remove'; path: string; value?: unknown }
export interface UiError {
  code: string;
  summary: string;
  retryable: boolean;
  category?: 'auth' | 'rate-limit' | 'network' | 'not-found' | 'parse' | 'unknown';
}

export interface CreateSessionDraft {
  nodeId: string;
  kind: AdapterId;
  cwd?: string;
  title?: string;
  profileId?: string;
  model?: string;
}

export interface NodeDraft { nodeId?: string; displayName: string; relayNode: string }
export interface PairDraft { displayName: string; relayNode: string; pairString: string }

export type MoyuIntent =
  | UiIntent<'app.ready', { uiVersion: string }>
  | UiIntent<'view.reload', Record<string, never>>
  | UiIntent<'appearance.set', { theme: Theme }>
  | UiIntent<'nav.open', { route: Route }>
  | UiIntent<'session.open', { localSessionId: string }>
  | UiIntent<'session.create', CreateSessionDraft>
  | UiIntent<'session.send', { localSessionId: string; text: string }>
  | UiIntent<'session.saveDraft', { localSessionId?: string; text: string }>
  | UiIntent<'session.interrupt', { localSessionId: string }>
  | UiIntent<'session.deleteLocal', { localSessionId: string }>
  | UiIntent<'session.loadOlder', { localSessionId: string; beforeLocalSeq?: number }>
  | UiIntent<'approval.decide', { localSessionId: string; approvalId: string; decision: ApprovalDecision }>
  | UiIntent<'diff.open', { localSessionId: string }>
  | UiIntent<'fs.list', { nodeId: string; path?: string }>
  | UiIntent<'node.connect', { nodeId: string }>
  | UiIntent<'node.disconnect', { nodeId: string }>
  | UiIntent<'node.save', NodeDraft>
  | UiIntent<'node.delete', { nodeId: string }>
  | UiIntent<'node.pair', { relayNode: string; pairString: string; displayName: string }>
  | UiIntent<'node.pairDraft.save', PairDraft>
  | UiIntent<'node.manualSetup.open', { displayName?: string; relayNode?: string }>
  | UiIntent<'node.diagnose', { nodeId: string }>
  | UiIntent<'accounts.activate', { nodeId: string; adapter: AdapterId; profileId: string }>
  | UiIntent<'config.patch', { nodeId: string; patch: ConfigPatch }>
  | UiIntent<'external.open', { url: string }>;

export interface AppViewModel {
  route: Route;
  now: string;
  appearance: { theme: Theme; resolvedTheme: 'light' | 'dark' };
  activeNodeId?: string;
  activeLocalSessionId?: string;
  pairDraft: PairDraft;
  connection: ConnectionView;
  nodes: NodeView[];
  sessions: LocalSessionView[];
  activeSession?: SessionDetailView;
  server?: ServerView;
  accounts?: AccountSwitchingStatus;
  config?: SanitizedConfig;
  diagnostics?: DiagnosticsView;
  ui: { globalBanner?: BannerView; pendingRequestIds: string[] };
}

export interface ConnectionView {
  state: 'offline' | 'overlayStarting' | 'backendConnecting' | 'syncing' | 'online' | 'degraded' | 'error';
  nodeId?: string;
  summary: string;
  phoneBackendRttMs?: number;
  lastOnlineAt?: string;
  error?: UiError;
}

export interface NodeView {
  nodeId: string;
  displayName: string;
  relayNode: string;
  configured: boolean;
  active: boolean;
  overlayState: string;
  backendState: 'unknown' | 'offline' | 'online';
  syncState: 'idle' | 'syncing' | 'current' | 'error';
  relayLatencyMs?: number;
  relayLatencyReliable?: boolean;
  lastConnectedAt?: string;
  secretState: { token: boolean; networkSecret: boolean };
}

export interface LocalSessionView {
  localSessionId: string;
  remoteSessionId?: string;
  nodeId: string;
  kind: AdapterId;
  title: string;
  updatedAt: string;
  profileId?: string;
  model?: string;
  state: 'localOnly' | 'idle' | 'running' | 'completed' | 'failed' | 'ended';
  unread: number;
  lastSeq: number;
  preview?: string;
}

export interface SessionDetailView extends LocalSessionView {
  cwd?: string;
  messages: TimelineItem[];
  hasOlderLocalMessages: boolean;
  composerDraft: string;
  canSend: boolean;
  canInterrupt: boolean;
  pendingApproval?: ApprovalView;
  transport?: TransportMetricsView;
  diff?: DiffView;
}

export type TimelineItem =
  | { localSeq: number; kind: 'message'; role: 'user' | 'assistant' | 'system'; text: string; createdAt: string }
  | { localSeq: number; kind: 'thinking'; text: string; streaming: boolean; createdAt: string }
  | { localSeq: number; kind: 'tool'; toolCallId: string; tool: string; input?: unknown; output?: string; state: 'running' | 'done' | 'error'; createdAt: string }
  | { localSeq: number; kind: 'approval'; approval: ApprovalView; createdAt: string }
  | { localSeq: number; kind: 'usage'; usage: Usage; costUsd?: number; createdAt: string }
  | { localSeq: number; kind: 'error'; error: UiError; createdAt: string };

export interface ApprovalView {
  approvalId: string;
  kind: 'command' | 'fileChange' | 'permission' | 'mcpElicit' | 'userInput';
  tool?: string;
  summary: string;
  input?: unknown;
  choices: ApprovalDecision[];
  state: 'pending' | 'submitting' | 'allowed' | 'denied' | 'expired';
}

export interface Usage {
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  totalTokens?: number;
}

export interface TransportMetricsView {
  phoneBackendRttMs?: number;
  backendCliQueueMs?: number;
  backendCliDispatchMs?: number;
  cliFirstEventMs?: number;
  relayLatencyMs?: number;
  observedAt?: string;
}

export interface DiagnosticsView {
  net?: unknown;
  transport?: TransportMetricsView;
  lastSyncAt?: string;
  backendVersion?: string;
  protocolVersion: 1;
  notes: string[];
}

export interface BannerView {
  level: 'info' | 'warning' | 'error';
  text: string;
  actionLabel?: string;
  actionIntent?: MoyuIntent;
}

export interface AdapterCapabilities {
  profiles: boolean;
  models: boolean;
  sandbox: boolean;
  approvalsReviewer: boolean;
  approvalChoices: ApprovalDecision[];
  description?: string;
  diff?: boolean;
  interrupt?: boolean;
  resume?: boolean;
}

export interface AdapterStatus {
  adapter: AdapterId;
  displayName: string;
  available: boolean;
  unavailableReason?: string;
  capabilities: AdapterCapabilities;
  supportedModels?: string[];
}

export interface ServerView {
  version: string;
  protocolVersion: 1;
  adapters: AdapterStatus[];
  maxMessageBytes: number;
  features: { diff: boolean; resume: boolean; eventGapSync: boolean };
}

export interface AccountProfileView {
  profileId: string;
  displayName: string;
  nativeDefault: boolean;
  hasCredentials: boolean;
  active: boolean;
}

export interface AccountAdapterStatus extends AdapterStatus { profiles: AccountProfileView[] }
export interface AccountSwitchingStatus { nodeId: string; adapters: AccountAdapterStatus[] }

export interface SanitizedConfig {
  defaultAdapter?: AdapterId;
  model?: string;
  availableModels?: string[];
  approvalPolicy?: 'ask' | 'allow_session' | 'deny';
  sandbox?: 'read-only' | 'workspace-write' | 'danger-full-access';
  approvalsReviewer?: 'auto_review' | 'user';
}

export interface ConfigPatch {
  defaultAdapter?: AdapterId;
  model?: string;
  approvalPolicy?: 'ask' | 'allow_session' | 'deny';
  sandbox?: 'read-only' | 'workspace-write' | 'danger-full-access';
  approvalsReviewer?: 'auto_review' | 'user';
}

export interface DiffFileView { path: string; status: 'staged' | 'unstaged' | 'untracked'; patch?: string }
export interface DiffView {
  isGitRepo: boolean;
  summary?: { staged: number; unstaged: number; untracked: number };
  files: DiffFileView[];
}

declare global {
  interface Window {
    MoyuHost?: { postMessage(message: string): void };
  }
  interface WindowEventMap { 'moyu:view': CustomEvent<HostEnvelope> }
}
