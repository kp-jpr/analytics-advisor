// Typed client for the Analysis tab's cross-table fuzzy record-match seam
// (/api/analysis/**). Deliberately separate from adminApi.ts, which speaks to
// the admin surface (/api/admin/**) — this tab picks tables and columns ad hoc
// from the registry, and reaches nothing an officer may not see.
//
// Every table/column reference here is FULLY QUALIFIED —
// catalog › schema › table › column. SRSE maps several catalogs and schemas
// at once (the lakehouse's Silver and Gold layers), so a bare table name is
// no longer an address: the same table name legitimately exists in both
// layers. Officers pick through a four-level cascade that offers only what an
// admin registered on the Admin page.

import { readApiErrorMessage } from "@/lib/apiError";
import {
  authorizedFetch as scopedAuthorizedFetch,
  type AuthScope,
} from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

async function authorizedFetch(
  input: string,
  init: RequestInit = {},
  scope: AuthScope = "officer",
): Promise<Response> {
  return scopedAuthorizedFetch(scope, input, init);
}

export type ColumnInfo = { name: string; dataType: string };

// One rung of the officer-facing cascade. `layer` is the admin's SILVER/GOLD
// tag (or null) — shown as a badge so an officer can tell the two layers'
// same-named tables apart at a glance.
export type RegisteredTable = { name: string; layer: string | null };

// A column of a registered table: live from the lakehouse, decorated with the
// admin's business name / fuzzy flag. Columns the admin hid are already
// filtered out server-side, so anything returned here is selectable.
export type RegisteredColumn = {
  name: string;
  dataType: string;
  businessName: string | null;
  fuzzyMatchable: boolean;
  // Admin opt-in (Aa1): officers get a distinct-values list for this column only when true.
  // Off by default; typing a value always works.
  valuePickerEnabled: boolean;
};

// A fully-qualified table address. Used as the identity of a picked table
// everywhere in the Analysis tab — comparisons must be on all three parts,
// never on `table` alone.
export type TableRef = { catalog: string; schema: string; table: string };

export function qualifiedTableName(ref: TableRef): string {
  return `${ref.catalog}.${ref.schema}.${ref.table}`;
}

export function displayTableName(ref: TableRef): string {
  return `${ref.catalog} › ${ref.schema} › ${ref.table}`;
}

// fuzzyThresholdPercent is set on Source-side criteria only (null on Target
// entries) and applies to that (source, target) pair when either column
// name contains "name" — see RecordMatchService's javadoc.
export type MatchCriterion = TableRef & {
  column: string;
  fuzzyThresholdPercent: number | null;
};

export type DedupSpec = TableRef & { column: string };

export type GroupMode = "COMBINE" | "ANY_OF";

/**
 * One comparison in the JOIN, over 1..N columns per side — this is what lets
 * one `full_name` match `first_name` + `last_name`. The two sides need NOT be
 * the same length. A group with one column on each side is exactly the
 * criterion pair it replaces, so leaving groups off keeps the old payload.
 */
export type MatchGroup = {
  source: MatchCriterion[];
  target: MatchCriterion[];
  mode: GroupMode;
  fuzzyThresholdPercent: number | null;
  fuzzyOptions?: FuzzyOptionsWire | null;
  // Joins a multi-column COMBINE side. Order is the officer's and it matters:
  // Levenshtein is order-sensitive.
  separator: string | null;
};

export type DisplayColumn = TableRef & {
  column: string;
};

export type JoinType = "INNER" | "LEFT" | "RIGHT" | "FULL";

/**
 * Post-join value comparison — projected in SELECT only, never in ON
 * (distinct from {@link MatchGroup} join criteria).
 */
export type ComparisonGroup = {
  source: MatchCriterion[];
  target: MatchCriterion[];
  mode?: GroupMode;
  fuzzyThresholdPercent?: number | null;
  separator?: string | null;
};

export type ComparisonSummaryResponse = {
  totalRows: number;
  matchedRows: number;
  noCounterpartRows: number;
  columns: {
    index: number;
    label: string;
    matchCount: number;
    /** Rate over matched rows only (excludes no-counterpart rows). */
    matchRatePercent: number;
  }[];
};

export type RuleOperator =
  | "EQ"
  | "NE"
  | "LT"
  | "LTE"
  | "GT"
  | "GTE"
  | "IN"
  | "NOT_IN"
  | "IS_TRUE"
  | "IS_FALSE"
  | "IS_NULL"
  | "NOT_NULL"
  | "FUZZY_MATCH";

/** §5.3 — must stay aligned with backend {@code FuzzyOptions}. */
export type FuzzyOptionsWire = {
  caseSensitive?: boolean;
  ignoreSpaces?: boolean;
};

export type QualifiedColumnWire = TableRef & { column: string };

export type PredicateNodeWire = {
  type: "PREDICATE";
  column: {
    table: { catalog: string; schema: string; table: string };
    column: string;
  };
  operator: RuleOperator;
  value?: unknown;
};

export type PredicateSpecWire = {
  root: PredicateNodeWire | { type: "GROUP"; op: "AND" | "OR"; children: PredicateNodeWire[] };
};

export type RecordMatchRequest = {
  sourceCriteria: MatchCriterion[];
  targetCriteria: MatchCriterion[];
  sourceDisplayColumns?: DisplayColumn[];
  targetDisplayColumns?: DisplayColumn[];
  joinGroups?: MatchGroup[];
  joinType?: JoinType;
  comparisonGroups?: ComparisonGroup[];
  mismatchOnly?: boolean;
  highlightDuplicates: boolean;
  dedup: DedupSpec | null;
  sourceRules?: PredicateSpecWire | null;
  targetRules?: PredicateSpecWire | null;
  singleSource?: boolean;
  groupByColumns?: DisplayColumn[];
  aggregates?: AggregateSpecWire[];
  /** On-screen extract cap; omitted from export re-runs. */
  resultLimit?: number | null;
  /** COUNT(*) over the same shape instead of row projection. */
  countOnly?: boolean;
  /**
   * Further tables inner-joined to the source in this same query.
   * Omitted when the query is a single table or a two-table match.
   */
  additionalJoins?: AdditionalJoin[];
};

export type AdditionalJoin = {
  catalog: string;
  schema: string;
  table: string;
  sourceColumn: string;
  joinColumn: string;
  displayColumns?: string[];
  /** Omitted when the join is inner, which is the default. */
  joinType?: JoinType;
  /** Basic rules applied inside this table before the join. */
  rules?: PredicateSpecWire | null;
  /** Set only when a join column contains "name". Null keeps an equality join. */
  fuzzyThresholdPercent?: number | null;
  fuzzyOptions?: FuzzyOptionsWire | null;
};

export type HubSide = "SOURCE" | "TARGET";

export type TargetMatchSpec = TableRef & {
  label: string;
  joinCriteria: MatchCriterion[];
  displayColumns?: DisplayColumn[];
  joinGroups?: MatchGroup[];
  joinType?: JoinType;
  comparisonGroups?: ComparisonGroup[];
};

export type AggregateFunction = "COUNT" | "SUM" | "AVG" | "MIN" | "MAX";

export type AggregateSpecWire = {
  function: AggregateFunction;
  column?: DisplayColumn | null;
  distinct?: boolean;
  alias?: string | null;
};

export type AnalysisLimits = {
  maxTargetSets: number;
  multiMatchBudgetSeconds: number;
  maxGroupColumns: number;
  maxGroupingColumns: number;
  maxExtractResultLimit?: number;
  maxAggregates: number;
  maxAnyOfGroupsPerSide: number;
  maxProbedPairs: number;
  blockingPrefixLen: number;
  maxEstimatedRows: number;
  maxColumnDistinctValues: number;
};

export type ColumnValuesResponse = {
  values: (string | null)[];
  truncated: boolean;
  includesNull: boolean;
};

export async function fetchColumnDistinctValues(
  ref: TableRef,
  column: string,
  search?: string,
  signal?: AbortSignal,
): Promise<ColumnValuesResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-values`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({
      catalog: ref.catalog,
      schema: ref.schema,
      table: ref.table,
      column,
      search: search || null,
    }),
    signal,
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json() as Promise<ColumnValuesResponse>;
}

export type MultiTargetRecordMatchRequest = {
  hubCriteria: MatchCriterion[];
  hubDisplayColumns?: DisplayColumn[];
  hubSide: HubSide;
  targets: TargetMatchSpec[];
  highlightDuplicates: boolean;
  dedup: DedupSpec | null;
  mismatchOnly?: boolean;
};

export type PerTargetSummary = {
  label: string;
  rows: number;
  status: string;
  message?: string;
  reason?: string;
};

export type MatchProgressEvent = {
  targetIndex: number;
  label: string;
  phase: "started" | "done" | "error" | "skipped";
  rows?: number;
  message?: string;
  reason?: string;
  // Present on "started" only. The backend cannot put these on the meta line:
  // meta is flushed before any target has been planned.
  sql?: string;
};

export function fetchAnalysisLimits(): Promise<AnalysisLimits> {
  return analysisGet<AnalysisLimits>("/api/analysis/limits");
}

async function analysisGet<T>(path: string): Promise<T> {
  const res = await authorizedFetch(`${API_BASE}${path}`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json() as Promise<T>;
}

const e = encodeURIComponent;

// ---- officer-facing cascade: optional registry filters → Catalog → Schema → Table → Column ----
// Layer, source system and table group are registry filters only — never part of TableRef or match payloads.
// Every level is answered from the admin's registry, NOT the live cluster.

/** Must stay in lockstep with {@code RegistryBrowseFilter} on the backend. */
export type RegistryBrowseFilter = {
  layer?: string;
  sourceSystem?: string;
  tableGroup?: string;
};

function registryBrowseQuery(filter?: RegistryBrowseFilter): string {
  if (!filter) return "";
  const params = new URLSearchParams();
  if (filter.layer) params.set("layer", filter.layer);
  if (filter.sourceSystem) params.set("sourceSystem", filter.sourceSystem);
  if (filter.tableGroup) params.set("tableGroup", filter.tableGroup);
  const q = params.toString();
  return q ? `?${q}` : "";
}

export function listAnalysisLayers(): Promise<string[]> {
  return analysisGet<string[]>(`/api/analysis/lakehouse/layers`);
}

export function listAnalysisSourceSystems(): Promise<string[]> {
  return analysisGet<string[]>(`/api/analysis/lakehouse/source-systems`);
}

export function listAnalysisTableGroups(sourceSystem?: string): Promise<string[]> {
  const q = sourceSystem ? `?sourceSystem=${e(sourceSystem)}` : "";
  return analysisGet<string[]>(`/api/analysis/lakehouse/table-groups${q}`);
}

export function listAnalysisCatalogs(filter?: RegistryBrowseFilter): Promise<string[]> {
  return analysisGet<string[]>(`/api/analysis/lakehouse/catalogs${registryBrowseQuery(filter)}`);
}

export function listAnalysisSchemas(catalog: string, filter?: RegistryBrowseFilter): Promise<string[]> {
  return analysisGet<string[]>(
    `/api/analysis/lakehouse/catalogs/${e(catalog)}/schemas${registryBrowseQuery(filter)}`,
  );
}

export function listAnalysisTables(
  catalog: string,
  schema: string,
  filter?: RegistryBrowseFilter,
): Promise<RegisteredTable[]> {
  return analysisGet<RegisteredTable[]>(
    `/api/analysis/lakehouse/catalogs/${e(catalog)}/schemas/${e(schema)}/tables${registryBrowseQuery(filter)}`,
  );
}

// ---- Database Overview (§3.1.4) — read-only; browsing is not audited ----

export type OverviewTableSummary = {
  catalog: string;
  schema: string;
  table: string;
  qualifiedName: string;
  layer: string | null;
  sourceSystem: string | null;
  tableGroup: string | null;
  sharedReference: boolean;
};

export function listOverviewSourceSystems(): Promise<string[]> {
  return analysisGet<string[]>(`/api/analysis/database-overview/source-systems`);
}

export function listOverviewTableGroups(sourceSystem?: string): Promise<string[]> {
  const q = sourceSystem ? `?sourceSystem=${e(sourceSystem)}` : "";
  return analysisGet<string[]>(`/api/analysis/database-overview/table-groups${q}`);
}

export function listOverviewTables(
  sourceSystem?: string,
  tableGroup?: string,
): Promise<OverviewTableSummary[]> {
  const params = new URLSearchParams();
  if (sourceSystem) params.set("sourceSystem", sourceSystem);
  if (tableGroup) params.set("tableGroup", tableGroup);
  const q = params.toString();
  return analysisGet<OverviewTableSummary[]>(`/api/analysis/database-overview/tables${q ? `?${q}` : ""}`);
}

export function listOverviewColumns(ref: TableRef): Promise<RegisteredColumn[]> {
  return analysisGet<RegisteredColumn[]>(
    `/api/analysis/database-overview/catalogs/${e(ref.catalog)}/schemas/${e(ref.schema)}` +
      `/tables/${e(ref.table)}/columns`,
  );
}

/** One column list per table for the life of the page. A failed load is not kept. */
const analysisColumnCache = new Map<string, Promise<RegisteredColumn[]>>();

export function listAnalysisColumns(ref: TableRef): Promise<RegisteredColumn[]> {
  const key = `${ref.catalog}\u0000${ref.schema}\u0000${ref.table}`;
  const cached = analysisColumnCache.get(key);
  if (cached) {
    return cached;
  }
  const pending = analysisGet<RegisteredColumn[]>(
    `/api/analysis/lakehouse/catalogs/${e(ref.catalog)}/schemas/${e(ref.schema)}` +
      `/tables/${e(ref.table)}/columns`,
  ).catch((err: unknown) => {
    analysisColumnCache.delete(key);
    throw err;
  });
  analysisColumnCache.set(key, pending);
  return pending;
}

export type ColumnMetadataStatus = {
  syncedAt: string | null;
  source: "SNAPSHOT" | "LIVE";
  degraded: boolean;
};

export function fetchColumnMetadataStatus(ref: TableRef): Promise<ColumnMetadataStatus> {
  const q = new URLSearchParams({
    catalog: ref.catalog,
    schema: ref.schema,
    table: ref.table,
  });
  return analysisGet<ColumnMetadataStatus>(`/api/analysis/metadata-status?${q}`);
}

/**
 * The COMPLETE match result as a CSV blob, streamed from the backend.
 *
 * Deliberately a second request rather than a re-serialisation of what the
 * grid holds: above the display limit the browser never held the rows in the
 * first place, and building the file from the screen would hand the officer a
 * silently truncated export. The cost is that the match query runs again —
 * which is why this is on an explicit download click, never automatic.
 */
/** Plans the match and returns display SQL — same query {@link runRecordMatchStream} would run. */
export async function fetchMatchSql(
  req: RecordMatchRequest,
  signal?: AbortSignal,
): Promise<string> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match.sql`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.text();
}

export async function fetchComparisonSummary(
  req: RecordMatchRequest,
): Promise<ComparisonSummaryResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match/comparison-summary`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json() as Promise<ComparisonSummaryResponse>;
}

export type ChartBucket = {
  values: string[];
  count: number;
  other: boolean;
};

export type ChartBucketsResponse = {
  totalRows: number;
  truncated: boolean;
  buckets: ChartBucket[];
};

const chartBucketCache = new Map<string, ChartBucketsResponse>();
const chartBucketInflight = new Map<string, Promise<ChartBucketsResponse>>();

function chartBucketCacheKey(req: RecordMatchRequest, columns: string[]): string {
  return JSON.stringify({ match: req, columns });
}

/** Full-result value counts for charts. The 10,000-row screen preview is not counted. */
export async function fetchChartBuckets(
  req: RecordMatchRequest,
  columns: string[],
): Promise<ChartBucketsResponse> {
  const key = chartBucketCacheKey(req, columns);
  const cached = chartBucketCache.get(key);
  if (cached) return cached;
  const pending = chartBucketInflight.get(key);
  if (pending) return pending;
  const request = (async () => {
    const res = await authorizedFetch(`${API_BASE}/api/analysis/match/chart-buckets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ match: req, columns }),
    });
    if (!res.ok) {
      throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
    }
    const body = (await res.json()) as ChartBucketsResponse;
    chartBucketCache.set(key, body);
    return body;
  })();
  chartBucketInflight.set(key, request);
  try {
    return await request;
  } finally {
    chartBucketInflight.delete(key);
  }
}

export type MatchExportFormat = "csv" | "json" | "xml" | "xlsx";

const MATCH_EXPORT_PATH: Record<MatchExportFormat, string> = {
  csv: "/api/analysis/match.csv",
  json: "/api/analysis/match.json",
  xml: "/api/analysis/match.xml",
  xlsx: "/api/analysis/match.xlsx",
};

export async function downloadRecordMatchExport(
  req: RecordMatchRequest,
  format: MatchExportFormat,
): Promise<Blob> {
  const res = await authorizedFetch(`${API_BASE}${MATCH_EXPORT_PATH[format]}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.blob();
}

/** @deprecated use {@link downloadRecordMatchExport} */
export async function downloadRecordMatchCsv(req: RecordMatchRequest): Promise<Blob> {
  return downloadRecordMatchExport(req, "csv");
}

export type RecordMatchStreamHandlers = {
  onMeta: (meta: { columns: string[]; sql: string }) => void;
  onRow: (row: Record<string, unknown>) => void;
  onDone: (totalRows: number) => void;
  onError: (message: string) => void;
};

// The match result is no longer a single buffered JSON object — the backend
// streams newline-delimited JSON (one "meta" line, then one "row" line per
// match, then a "done" or "error" line) so the officer sees the first rows
// almost immediately instead of waiting for the whole (now uncapped) result.
// See RecordMatchService's javadoc on the backend for why.
export async function runRecordMatchStream(
  req: RecordMatchRequest,
  handlers: RecordMatchStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  if (!res.body) {
    throw new Error("Analysis service error: streaming response has no body");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  function handleLine(line: string) {
    if (!line.trim()) {
      return;
    }
    const event = JSON.parse(line) as
      | { type: "meta"; columns: string[]; sql: string }
      | { type: "row"; data: Record<string, unknown> }
      | { type: "done"; totalRows: number }
      | { type: "error"; message: string };
    switch (event.type) {
      case "meta":
        handlers.onMeta({ columns: event.columns, sql: event.sql });
        break;
      case "row":
        handlers.onRow(event.data);
        break;
      case "done":
        handlers.onDone(event.totalRows);
        break;
      case "error":
        handlers.onError(event.message);
        break;
    }
  }

  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let newlineIndex: number;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      handleLine(buffer.slice(0, newlineIndex));
      buffer = buffer.slice(newlineIndex + 1);
    }
  }
  if (buffer.trim()) {
    handleLine(buffer);
  }
}

export type MultiTargetMatchStreamHandlers = {
  onMeta: (meta: { columns: string[]; targetCount: number }) => void;
  onProgress: (event: MatchProgressEvent) => void;
  onRow: (row: Record<string, unknown>) => void;
  onDone: (totalRows: number, perTarget: PerTargetSummary[]) => void;
  onError: (message: string) => void;
};

export async function runMultiTargetMatchStream(
  req: MultiTargetRecordMatchRequest,
  handlers: MultiTargetMatchStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match-multi`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  if (!res.body) {
    throw new Error("Analysis service error: streaming response has no body");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  function handleLine(line: string) {
    if (!line.trim()) {
      return;
    }
    const event = JSON.parse(line) as
      | { type: "meta"; columns: string[]; targetCount: number }
      | { type: "progress"; targetIndex: number; label: string; phase: MatchProgressEvent["phase"]; rows?: number; message?: string; reason?: string; sql?: string }
      | { type: "row"; data: Record<string, unknown> }
      | { type: "done"; totalRows: number; perTarget: PerTargetSummary[] }
      | { type: "error"; message: string };
    switch (event.type) {
      case "meta":
        handlers.onMeta({
          columns: event.columns,
          targetCount: event.targetCount,
        });
        break;
      case "progress":
        handlers.onProgress({
          targetIndex: event.targetIndex,
          label: event.label,
          phase: event.phase,
          rows: event.rows,
          message: event.message,
          reason: event.reason,
          sql: event.sql,
        });
        break;
      case "row":
        handlers.onRow(event.data);
        break;
      case "done":
        handlers.onDone(event.totalRows, event.perTarget);
        break;
      case "error":
        handlers.onError(event.message);
        break;
    }
  }

  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let newlineIndex: number;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      handleLine(buffer.slice(0, newlineIndex));
      buffer = buffer.slice(newlineIndex + 1);
    }
  }
  if (buffer.trim()) {
    handleLine(buffer);
  }
}

export async function downloadMultiTargetMatchCsv(req: MultiTargetRecordMatchRequest): Promise<Blob> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/match-multi.csv`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.blob();
}

// Admin-managed business name / fuzzy-matchable / visibility override per
// physical column, keyed by the FULL catalog.schema.table.column address —
// see AnalysisColumnMetadata's javadoc on the backend for why this is
// separate from the Rule Engine's field catalogue, and why table+column alone
// was not a unique key once Silver and Gold layers were both mapped.
// Unregistered columns of a registered table are visible and fall back to an
// auto-derived label and a name-substring guess for fuzzy eligibility.
export type ColumnMetadata = TableRef & {
  column: string;
  businessName: string | null;
  fuzzyMatchable: boolean;
  visible: boolean;
  compareAs: CompareAs;
  valuePickerEnabled: boolean;
};

/**
 * How a column is coerced when it is compared against a column of a DIFFERENT
 * type family — the fix for "'=' cannot be applied to varchar, bigint" when
 * the same account number is varchar in one table and bigint in the other.
 *
 * - AUTO   — let SRSE decide: a number-vs-text pair is compared as NUMBERS,
 *            so '0123' still matches 123. The default.
 * - NUMBER — always numerically; the text side goes through TRY_CAST, and text
 *            that is not a number simply does not match.
 * - TEXT   — always as text; use when the text form is the truth (a code with
 *            meaningful leading zeros).
 *
 * Two columns of the SAME family are always compared as they always were —
 * Presto coerces those itself, and this setting does not enter into it.
 */
export type CompareAs = "AUTO" | "NUMBER" | "TEXT" | "DATE";

export async function listColumnMetadata(scope: AuthScope = "officer"): Promise<ColumnMetadata[]> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata`, { credentials: "include" }, scope);
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

/**
 * Drops the override for one column, reverting it to the defaults every
 * uncurated column of a registered table already has — visible, auto-derived
 * label, name-substring fuzzy guess. Also the way to clear an orphaned row
 * whose table is no longer registered.
 */
export async function deleteColumnMetadata(ref: TableRef, column: string): Promise<void> {
  const params = new URLSearchParams({ ...ref, column });
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata?${params}`, {
    method: "DELETE",
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
}

export async function upsertColumnMetadata(
  ref: TableRef,
  column: string,
  businessName: string | null,
  fuzzyMatchable: boolean,
  visible: boolean = true,
  compareAs: CompareAs = "AUTO",
  // undefined = leave the stored value alone (the server keeps it when the field is absent)
  valuePickerEnabled?: boolean,
): Promise<ColumnMetadata> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/column-metadata`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ ...ref, column, businessName, fuzzyMatchable, visible, compareAs, valuePickerEnabled }),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Analysis service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export type JoinKeySuggestion = {
  sourceColumn: string;
  targetColumn: string;
  sourceType: string;
  targetType: string;
  reason: string;
};

export async function suggestJoinKeys(req: {
  sourceCatalog: string;
  sourceSchema: string;
  sourceTable: string;
  targetCatalog: string;
  targetSchema: string;
  targetTable: string;
  probe?: boolean;
}): Promise<JoinKeySuggestion[]> {
  const res = await authorizedFetch(`${API_BASE}/api/analysis/suggest-keys`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ ...req, probe: req.probe ?? false }),
  });
  if (!res.ok) {
    throw new Error(`Suggest keys failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}
