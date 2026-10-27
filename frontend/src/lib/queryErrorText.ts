const ANALYTICAL_ENGINE_MARKERS = [
  "analytical engine is unavailable",
  "lakehouse connection unavailable",
  "connection refused",
  "connectexception",
  "failed to fetch",
  "networkerror",
  "socket closed",
  "econnrefused",
  "unknown host",
];

/** A dropped Presto connection, as opposed to a query the engine actually rejected. */
export function isAnalyticalEngineDown(message: string): boolean {
  const text = message.toLowerCase();
  return ANALYTICAL_ENGINE_MARKERS.some((marker) => text.includes(marker));
}

const ANALYSIS_SERVICE_PREFIX = /^Analysis service error \d+:\s*/i;

/** Replaces a raw connection failure with the officer-facing sentence. */
export function presentQueryError(message: string, engineDownText: string): string {
  const text = message.replace(ANALYSIS_SERVICE_PREFIX, "").trim();
  return isAnalyticalEngineDown(text) ? engineDownText : text;
}
