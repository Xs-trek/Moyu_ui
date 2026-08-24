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

export function turnPerformanceLabels(performance, usage) {
  const milliseconds = finiteNonNegative(performance?.observedDurationMs);
  const firstOutputMilliseconds = finiteNonNegative(performance?.timeToFirstOutputMs);
  const outputMilliseconds = finiteNonNegative(performance?.outputDurationMs);
  const outputTokens = finiteNonNegative(usage?.outputTokens);
  const reportedSpeed = finiteNonNegative(performance?.outputTokensPerSecond);
  const tokensPerSecond = reportedSpeed ?? (outputMilliseconds != null && outputMilliseconds > 0
    && outputTokens != null && outputTokens > 0 ? outputTokens * 1_000 / outputMilliseconds : null);
  return {
    duration: formatObservedDuration(milliseconds),
    firstOutput: formatObservedDuration(firstOutputMilliseconds),
    speed: tokensPerSecond == null ? null : `${tokensPerSecond.toFixed(1)} t/s`,
  };
}
