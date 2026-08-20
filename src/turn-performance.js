function finiteNonNegative(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

export function formatObservedDuration(value) {
  const milliseconds = finiteNonNegative(value);
  if (milliseconds == null) return null;
  if (milliseconds < 1_000) return `${Math.round(milliseconds)} ms`;
  if (milliseconds < 60_000) {
    const seconds = milliseconds / 1_000;
    return `${seconds.toFixed(1)} s`;
  }
  const totalSeconds = Math.round(milliseconds / 1_000);
  const minutes = Math.floor(totalSeconds / 60);
  return `${minutes}m ${String(totalSeconds % 60).padStart(2, '0')}s`;
}

/** End-to-end observed throughput, not a provider-native generation benchmark. The denominator
 * includes backend queueing, CLI startup, provider latency, tools and approvals because those
 * boundaries are the only cross-adapter timings available without provider instrumentation. */
export function turnPerformanceLabels(performance, usage) {
  const milliseconds = finiteNonNegative(performance?.observedDurationMs);
  const outputTokens = finiteNonNegative(usage?.outputTokens);
  const tokensPerSecond = milliseconds != null && milliseconds > 0 && outputTokens != null && outputTokens > 0
    ? outputTokens * 1_000 / milliseconds
    : null;
  return {
    duration: formatObservedDuration(milliseconds),
    speed: tokensPerSecond == null ? null : `${tokensPerSecond.toFixed(1)} t/s`,
  };
}
