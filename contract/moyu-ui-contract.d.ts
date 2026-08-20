export type RequestId = string;
export type Theme = 'system' | 'light' | 'dark';
export type Route = 'console' | 'conversation' | 'sessions' | 'nodes' | 'accounts' | 'settings' | 'diagnostics';
export type ApprovalChoice = 'allow' | 'allow_session' | 'deny' | 'cancel';
export type ApprovalDecision = ApprovalChoice | { allowWithModification: { answers: Record<string, string | string[]> } };
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

export type JsonPrimitive = null | boolean | number | string;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type ViewPatch =
  | { op: 'set'; path: string; value: JsonValue }
  | { op: 'remove'; path: string };
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
  effort?: string;
  permissionMode?: PermissionMode;
}

export type PermissionMode = 'plan' | 'auto' | 'acceptEdits';

export interface NodeDraft { nodeId?: string; displayName: string; relayNode: string }
export interface PairDraft { displayName: string; relayNode: string; pairString: string }
export interface AskUserQuestionOption { label: string; description?: string }
export interface AskUserQuestionItem { question: string; header?: string; options?: AskUserQuestionOption[]; multiSelect?: boolean }

export type MoyuIntent =
  | UiIntent<'app.ready', { uiVersion: string }>
  | UiIntent<'view.reload', Record<string, never>>
  | UiIntent<'appearance.set', { theme: Theme }>
  | UiIntent<'nav.open', { route: Route }>
  | UiIntent<'session.open', { localSessionId: string }>
  | UiIntent<'session.create', CreateSessionDraft>
  | UiIntent<'session.send', { localSessionId: string; text: string }>
  | UiIntent<'attachment.pick', { localSessionId: string }>
  | UiIntent<'attachment.remove', { localSessionId: string; artifactId: string }>
  | UiIntent<'session.saveDraft', { localSessionId?: string; text: string }>
  | UiIntent<'session.effort.set', { localSessionId: string; effort?: string }>
  | UiIntent<'session.model.set', { localSessionId: string; model?: string }>
  | UiIntent<'session.permissionMode.set', { localSessionId: string; permissionMode: PermissionMode }>
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
  | UiIntent<'node.manualSetup.open', { nodeId?: string }>
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
  /** Exact configured backend VIP route observed from EasyTier; never inferred from backendState. */
  peerConnected: boolean;
  linkMode: 'p2p' | 'relay' | 'unknown';
  linkObservedAt?: string;
  relayLatencyMs?: number;
  relayLatencyReliable?: boolean;
  lastConnectedAt?: string;
  secretState: { token: boolean; networkSecret: boolean };
}

export interface LocalSessionView {
  localSessionId: string;
  remoteSessionId?: string;
  nativeSessionId?: string;
  resumable?: boolean;
  nativeMessageCount?: number;
  nativeCachedMessages?: number;
  nativeCacheComplete?: boolean;
  nodeId: string;
  kind: AdapterId;
  title: string;
  updatedAt: string;
  profileId?: string;
  model?: string;
  /** Explicit argv selection, if any. Runtime provider identity is reported per turn. */
  requestedModel?: string;
  runtimeModel?: string;
  effort?: string;
  permissionMode?: PermissionMode;
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
  composerAttachments: ArtifactView[];
  canSend: boolean;
  canInterrupt: boolean;
  effortLevels: string[];
  permissionModes: PermissionMode[];
  pendingApproval?: ApprovalView;
  transport?: TransportMetricsView;
  diff?: DiffView;
}

export type TimelineItem =
  | { localSeq: number; kind: 'message'; role: 'user' | 'assistant' | 'system'; text: string; streaming?: boolean; artifacts?: ArtifactView[]; createdAt: string }
  | { localSeq: number; kind: 'thinking'; text: string; streaming: boolean; createdAt: string }
  | { localSeq: number; kind: 'tool'; toolCallId: string; tool: string; input?: unknown; output?: string; artifacts?: ArtifactView[]; state: 'running' | 'done' | 'error'; createdAt: string }
  | { localSeq: number; kind: 'approval'; approval: ApprovalView; createdAt: string }
  | { localSeq: number; kind: 'usage'; usage: Usage; costUsd?: number; model?: string; effort?: string; performance?: TurnPerformanceView; createdAt: string }
  | { localSeq: number; kind: 'error'; error: UiError; createdAt: string };

export interface ArtifactView {
  artifactId: string;
  mime: 'image/png' | 'image/jpeg' | 'image/gif' | 'image/webp';
  name: string;
  size: number;
  sha256?: string;
  /** Android appassets same-origin URL backed by the private native cache. */
  localUrl: string;
}

export interface ApprovalView {
  approvalId: string;
  kind: 'command' | 'fileChange' | 'permission' | 'mcpElicit' | 'userInput';
  tool?: string;
  summary: string;
  /** AskUserQuestion carries {questions: AskUserQuestionItem[]}; other tools remain opaque. */
  input?: unknown;
  choices: ApprovalChoice[];
  state: 'pending' | 'submitting' | 'allowed' | 'denied' | 'expired';
}

export interface Usage {
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  totalTokens?: number;
}

/** PC-local observation from accepted input through turn.completed. It includes queueing, CLI,
 * provider, tool and approval time and is not a provider-native generation benchmark. */
export interface TurnPerformanceView {
  observedDurationMs: number;
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
  /** Native model ids are accepted as free text; this is not a provider availability catalog. */
  modelSelection: 'freeform' | 'none';
  sandbox: boolean;
  approvalsReviewer: boolean;
  sandboxModes: Array<'read-only' | 'workspace-write' | 'danger-full-access'>;
  reviewers: Array<'auto_review' | 'user' | 'guardian_subagent'>;
  approvalPolicies: Array<'untrusted' | 'on-failure' | 'on-request' | 'never'>;
  approvalChoices: ApprovalChoice[];
  description?: string;
  diff?: boolean;
  interrupt?: boolean;
  resume?: boolean;
  effortLevels?: string[];
  permissionModes?: PermissionMode[];
  streaming?: { text: boolean; thinking: boolean; tools: boolean };
}

export interface AdapterStatus {
  adapter: AdapterId;
  displayName: string;
  available: boolean;
  unavailableReason?: string;
  capabilities: AdapterCapabilities;
  /** Profile-local native default and final default are read from PC config only. */
  cliDefaultModel?: string;
  effectiveModel?: string;
  modelOverride?: string;
  supportedModels?: string[];
}

export interface ServerView {
  version: string;
  protocolVersion: 1;
  adapters: AdapterStatus[];
  maxMessageBytes: number;
  features: { diff: boolean; resume: boolean; eventGapSync: boolean; sessionEffort?: boolean; sessionModel?: boolean; sessionPermissionMode?: boolean };
}

export interface AccountProfileView {
  profileId: string;
  displayName: string;
  nativeDefault: boolean;
  hasCredentials: boolean;
  active: boolean;
  cliDefaultModel?: string;
  effectiveModel?: string;
  modelOverride?: string;
}

export interface AccountAdapterStatus extends AdapterStatus { profiles: AccountProfileView[] }
export interface AccountSwitchingStatus { nodeId: string; adapters: AccountAdapterStatus[] }

export interface SanitizedConfig {
  defaultAdapter?: AdapterId;
  /** Persisted explicit override only; absent means inherit the selected Profile's CLI default. */
  model?: string;
  modelOverride?: string;
  explicitModel?: boolean;
  cliDefaultModel?: string;
  effectiveModel?: string;
  modelSource?: 'override' | 'cli-default' | 'unknown';
  modelSelection?: 'freeform' | 'none';
  /** Compatibility field; an empty list does not mean the provider has no models. */
  availableModels?: string[];
  effortLevels?: string[];
  sandboxModes?: Array<'read-only' | 'workspace-write' | 'danger-full-access'>;
  reviewers?: Array<'auto_review' | 'user' | 'guardian_subagent'>;
  approvalPolicies?: Array<'untrusted' | 'on-failure' | 'on-request' | 'never'>;
  approvalPolicy?: 'untrusted' | 'on-failure' | 'on-request' | 'never';
  sandbox?: 'read-only' | 'workspace-write' | 'danger-full-access';
  approvalsReviewer?: 'auto_review' | 'user' | 'guardian_subagent';
}

export interface ConfigPatch {
  defaultAdapter?: AdapterId;
  model?: string;
  approvalPolicy?: 'untrusted' | 'on-failure' | 'on-request' | 'never';
  sandbox?: 'read-only' | 'workspace-write' | 'danger-full-access';
  approvalsReviewer?: 'auto_review' | 'user' | 'guardian_subagent';
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
