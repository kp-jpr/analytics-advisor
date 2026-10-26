import type { CascadeValue } from "@/components/LakehouseCascade";
import type {
  AggregateFunction,
  JoinType,
  PredicateSpecWire,
  RecordMatchRequest,
  RuleOperator,
} from "@/lib/analysisApi";

/** Serializable comparison pair (no React row id). */
export type ComparisonPairConfig = {
  sourceColumn: string;
  targetColumn: string;
  fuzzyThresholdPercent: number;
};

/** One basic extract rule. `id` is stable for list identity; it is not a server key. */
export type ExtractRuleRow = {
  id: string;
  column: string;
  operator: RuleOperator;
  value: string;
  /** Distinct-picker selections; when length > 1 the rule compiles as IN. */
  inValues?: (string | number | null)[];
};

/** Extract Records tab — tables, attributes, keys, and row-level rules only. */
export type ExtractConfig = {
  sourceRef: CascadeValue;
  targetRef: CascadeValue;
  /** Source attributes. Kept under this name so older callers stay valid. */
  displayCols: string[];
  /** Destination attributes. Empty for single-source and for saves that predate the field. */
  targetDisplayCols: string[];
  joinKeySource: string;
  joinKeyTarget: string;
  joinType: JoinType;
  ruleColumn: string;
  ruleOp: RuleOperator;
  ruleValue: string;
  useValuePicker: boolean;
  valuePickerSpec: PredicateSpecWire | null;
  fuzzyRuleEnabled: boolean;
  fuzzyRuleName: string;
  fuzzyRuleThreshold: number;
  fuzzyIgnoreSpaces: boolean;
  fuzzyCaseSensitive: boolean;
  targetRuleColumn: string;
  targetRuleOp: RuleOperator;
  targetRuleValue: string;
  /** Basic rules on the source table. Empty until the multi-rule UI is wired. */
  sourceRuleRows: ExtractRuleRow[];
  /** Basic rules on the destination table. Empty until the multi-rule UI is wired. */
  targetRuleRows: ExtractRuleRow[];
  /** Optional cap on rows returned for on-screen extract (export re-runs without this). */
  resultLimit: number | null;
  /** When true, run COUNT(*) instead of projecting rows. */
  countOnly: boolean;
  /** Further tables inner-joined to the source. Empty keeps the two-table query. */
  additionalTables: AdditionalTableConfig[];
};

/** One extra table joined to the source inside the same query. `id` is list identity only. */
export type AdditionalTableConfig = {
  id: string;
  ref: CascadeValue;
  sourceColumn: string;
  joinColumn: string;
  displayCols: string[];
  /** Null or INNER on the wire is an inner join. The card always stores a value. */
  joinType: JoinType;
  /** Basic row rules compiled inside this table before it is joined. */
  ruleRows: ExtractRuleRow[];
};

/** How many further tables can be joined beyond the source and destination. */
export const MAX_ADDITIONAL_TABLES = 5;

/** Report Analysis stage — filters, fuzzy/join options, grouping, comparisons. */
export type ReportConfig = {
  mismatchOnly: boolean;
  highlightDuplicates: boolean;
  dedupEnabled: boolean;
  joinType: JoinType;
  comparisonPairs: ComparisonPairConfig[];
  groupEnabled: boolean;
  groupByCol: string;
  aggregateFn: AggregateFunction;
  aggregateCol: string;
  countDistinct: boolean;
  /** When true, run COUNT(*) instead of projecting rows. Disabled with group-by. */
  countOnly: boolean;
  /** Per-side fuzzy threshold on the primary join key (source side). */
  joinFuzzyThresholdPercent: number;
  joinFuzzyIgnoreSpaces: boolean;
  joinFuzzyCaseSensitive: boolean;
  /** Source value filter on the Report stage. */
  sourceValueFilterEnabled: boolean;
  sourceValueFilterColumn: string;
  sourceValueFilterSpec: PredicateSpecWire | null;
  /** Source fuzzy filter on the Report stage. */
  sourceFuzzyEnabled: boolean;
  sourceFuzzyColumn: string;
  sourceFuzzyName: string;
  sourceFuzzyThreshold: number;
  sourceFuzzyIgnoreSpaces: boolean;
  sourceFuzzyCaseSensitive: boolean;
};

export type QueryExecutionSnapshot = {
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows: number | null;
  sql: string;
};

export type QueryBuilderPipelineMeta = {
  dualMode: boolean;
  extract: ExtractConfig;
  report: ReportConfig;
};

export const EMPTY_EXTRACT_CONFIG: ExtractConfig = {
  sourceRef: { catalog: "", schema: "", table: "" },
  targetRef: { catalog: "", schema: "", table: "" },
  displayCols: [],
  targetDisplayCols: [],
  joinKeySource: "",
  joinKeyTarget: "",
  joinType: "INNER",
  ruleColumn: "",
  ruleOp: "GT",
  ruleValue: "",
  useValuePicker: false,
  valuePickerSpec: null,
  fuzzyRuleEnabled: false,
  fuzzyRuleName: "",
  fuzzyRuleThreshold: 80,
  fuzzyIgnoreSpaces: false,
  fuzzyCaseSensitive: false,
  targetRuleColumn: "",
  targetRuleOp: "GT",
  targetRuleValue: "",
  sourceRuleRows: [],
  targetRuleRows: [],
  resultLimit: null,
  countOnly: false,
  additionalTables: [],
};

export const EMPTY_REPORT_CONFIG: ReportConfig = {
  mismatchOnly: false,
  highlightDuplicates: false,
  dedupEnabled: false,
  joinType: "INNER",
  comparisonPairs: [],
  groupEnabled: false,
  groupByCol: "",
  aggregateFn: "COUNT",
  aggregateCol: "",
  countDistinct: false,
  countOnly: false,
  joinFuzzyThresholdPercent: 80,
  joinFuzzyIgnoreSpaces: false,
  joinFuzzyCaseSensitive: false,
  sourceValueFilterEnabled: false,
  sourceValueFilterColumn: "",
  sourceValueFilterSpec: null,
  sourceFuzzyEnabled: false,
  sourceFuzzyColumn: "",
  sourceFuzzyName: "",
  sourceFuzzyThreshold: 80,
  sourceFuzzyIgnoreSpaces: false,
  sourceFuzzyCaseSensitive: false,
};

/** Stable JSON comparison for “did server-side report options change since last apply?” */
export function requestsEqual(a: RecordMatchRequest | null, b: RecordMatchRequest | null): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return JSON.stringify(a) === JSON.stringify(b);
}
