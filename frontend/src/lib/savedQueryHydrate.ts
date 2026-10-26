import type {
  DisplayColumn,
  MatchCriterion,
  MatchGroup,
  PredicateNodeWire,
  PredicateSpecWire,
  RecordMatchRequest,
  RuleOperator,
} from "@/lib/analysisApi";
import type { ComparisonPairRow, CriterionRowModel } from "@/lib/analysisCriterionModel";
import type { CascadeValue } from "@/components/LakehouseCascade";
import {
  EMPTY_EXTRACT_CONFIG,
  EMPTY_REPORT_CONFIG,
  type ComparisonPairConfig,
  type ExtractConfig,
  type ExtractRuleRow,
  type ReportConfig,
} from "@/lib/queryBuilderPipelineTypes";

export type HydratedDisplayRow = {
  id: string;
  ref: CascadeValue;
  column: string;
};

export type HydratedCriterionRow = CriterionRowModel & { id: string };

export type HydratedReportState = {
  sourceRows: HydratedCriterionRow[];
  targetRows: HydratedCriterionRow[];
  sourceDisplayRows: HydratedDisplayRow[];
  targetDisplayRows: HydratedDisplayRow[];
  joinType: RecordMatchRequest["joinType"];
  comparisonPairs: ComparisonPairRow[];
  highlightDuplicates: boolean;
  dedupEnabled: boolean;
  mismatchOnly: boolean;
};

function refFrom(c: { catalog: string; schema: string; table: string }): CascadeValue {
  return { catalog: c.catalog, schema: c.schema, table: c.table };
}

function rowFromSide(
  criteria: MatchCriterion[],
  groupSide: MatchCriterion[] | undefined,
  group: MatchGroup | undefined,
  side: "source" | "target",
): HydratedCriterionRow {
  const primary = criteria[0];
  const cols = groupSide?.map((c) => c.column) ?? criteria.map((c) => c.column);
  const fuzzyFromGroup = side === "source" ? group?.fuzzyThresholdPercent : null;
  const fuzzyOpts = side === "source" ? group?.fuzzyOptions : null;
  return {
    id: crypto.randomUUID(),
    ref: refFrom(primary),
    column: cols[0] ?? "",
    columns: [],
    extraColumns: cols.slice(1),
    fuzzyThresholdPercent:
      primary.fuzzyThresholdPercent ?? fuzzyFromGroup ?? 80,
    fuzzyIgnoreSpaces: fuzzyOpts?.ignoreSpaces ?? false,
    fuzzyCaseSensitive: fuzzyOpts?.caseSensitive ?? false,
    mode: group?.mode ?? "COMBINE",
    separator: group?.separator ?? " ",
  };
}

function displayFrom(list: DisplayColumn[] | undefined): HydratedDisplayRow[] {
  if (!list?.length) {
    return [];
  }
  return list.map((d) => ({
    id: crypto.randomUUID(),
    ref: refFrom(d),
    column: d.column,
  }));
}

/**
 * Maps a persisted {@link RecordMatchRequest} back into Report Analysis form state.
 * Column lists are left empty — callers should load them via {@code listAnalysisColumns}.
 */
export function hydrateReportStateFromSavedRequest(req: RecordMatchRequest): HydratedReportState {
  const groups = req.joinGroups ?? [];
  const n = Math.max(req.sourceCriteria.length, req.targetCriteria.length, groups.length);
  const sourceRows: HydratedCriterionRow[] = [];
  const targetRows: HydratedCriterionRow[] = [];

  for (let i = 0; i < n; i++) {
    const srcCrit = req.sourceCriteria[i];
    const tgtCrit = req.targetCriteria[i];
    if (!srcCrit || !tgtCrit) {
      continue;
    }
    const group = groups[i];
    sourceRows.push(rowFromSide([srcCrit], group?.source, group, "source"));
    targetRows.push(rowFromSide([tgtCrit], group?.target, group, "target"));
  }

  const comparisonPairs: ComparisonPairRow[] = (req.comparisonGroups ?? []).map((g) => ({
    id: crypto.randomUUID(),
    sourceColumn: g.source[0]?.column ?? "",
    targetColumn: g.target[0]?.column ?? "",
    fuzzyThresholdPercent: g.fuzzyThresholdPercent ?? 80,
  }));

  return {
    sourceRows: sourceRows.length > 0 ? sourceRows : [],
    targetRows: targetRows.length > 0 ? targetRows : [],
    sourceDisplayRows: displayFrom(req.sourceDisplayColumns),
    targetDisplayRows: displayFrom(req.targetDisplayColumns),
    joinType: req.joinType ?? "INNER",
    comparisonPairs,
    highlightDuplicates: req.highlightDuplicates ?? false,
    dedupEnabled: Boolean(req.dedup),
    mismatchOnly: req.mismatchOnly ?? false,
  };
}

function predicateRoot(spec: PredicateSpecWire | null | undefined): PredicateNodeWire | null {
  if (!spec?.root || spec.root.type !== "PREDICATE") {
    return null;
  }
  return spec.root;
}

type LegacyRuleFields = {
  column: string;
  operator: RuleOperator;
  value: string;
  fuzzyRuleEnabled: boolean;
  fuzzyRuleName: string;
  fuzzyRuleThreshold: number;
  fuzzyIgnoreSpaces: boolean;
  fuzzyCaseSensitive: boolean;
  useValuePicker: boolean;
  valuePickerSpec: PredicateSpecWire | null;
};

/** Value-picker and fuzzy controls now live on Report, so Extract keeps these empty. */
function emptyLegacyRuleFields(): LegacyRuleFields {
  return {
    column: EMPTY_EXTRACT_CONFIG.ruleColumn,
    operator: EMPTY_EXTRACT_CONFIG.ruleOp,
    value: EMPTY_EXTRACT_CONFIG.ruleValue,
    fuzzyRuleEnabled: EMPTY_EXTRACT_CONFIG.fuzzyRuleEnabled,
    fuzzyRuleName: EMPTY_EXTRACT_CONFIG.fuzzyRuleName,
    fuzzyRuleThreshold: EMPTY_EXTRACT_CONFIG.fuzzyRuleThreshold,
    fuzzyIgnoreSpaces: EMPTY_EXTRACT_CONFIG.fuzzyIgnoreSpaces,
    fuzzyCaseSensitive: EMPTY_EXTRACT_CONFIG.fuzzyCaseSensitive,
    useValuePicker: EMPTY_EXTRACT_CONFIG.useValuePicker,
    valuePickerSpec: EMPTY_EXTRACT_CONFIG.valuePickerSpec,
  };
}

function ruleFieldsFromSpec(spec: PredicateSpecWire | null | undefined): LegacyRuleFields {
  const root = predicateRoot(spec);
  if (!root) {
    return emptyLegacyRuleFields();
  }
  if (root.operator === "IN") {
    return {
      column: root.column.column,
      operator: "IN",
      value: "",
      fuzzyRuleEnabled: false,
      fuzzyRuleName: "",
      fuzzyRuleThreshold: 80,
      fuzzyIgnoreSpaces: false,
      fuzzyCaseSensitive: false,
      useValuePicker: true,
      valuePickerSpec: spec ?? null,
    };
  }
  if (root.operator === "FUZZY_MATCH" && Array.isArray(root.value)) {
    const [name, threshold, opts] = root.value as [string, number, { ignoreSpaces?: boolean; caseSensitive?: boolean }];
    return {
      column: root.column.column,
      operator: "GT",
      value: "",
      fuzzyRuleEnabled: true,
      fuzzyRuleName: name,
      fuzzyRuleThreshold: threshold,
      fuzzyIgnoreSpaces: opts?.ignoreSpaces ?? false,
      fuzzyCaseSensitive: opts?.caseSensitive ?? false,
      useValuePicker: false,
      valuePickerSpec: null,
    };
  }
  const value =
    root.value === undefined || root.value === null
      ? ""
      : typeof root.value === "object"
        ? JSON.stringify(root.value)
        : String(root.value);
  return {
    column: root.column.column,
    operator: root.operator,
    value,
    fuzzyRuleEnabled: false,
    fuzzyRuleName: "",
    fuzzyRuleThreshold: 80,
    fuzzyIgnoreSpaces: false,
    fuzzyCaseSensitive: false,
    useValuePicker: false,
    valuePickerSpec: spec ?? null,
  };
}

/** Operators a basic extract row can hold. IN and FUZZY_MATCH are Report filters. */
const BASIC_RULE_OPERATORS: ReadonlySet<RuleOperator> = new Set([
  "EQ",
  "NE",
  "LT",
  "LTE",
  "GT",
  "GTE",
  "IS_NULL",
  "NOT_NULL",
]);

function stringRuleValue(value: unknown): string {
  if (value === undefined || value === null) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function basicRuleRow(node: PredicateNodeWire): ExtractRuleRow | null {
  if (node.type !== "PREDICATE" || !BASIC_RULE_OPERATORS.has(node.operator)) {
    return null;
  }
  const column = node.column?.column ?? "";
  if (column.trim() === "") {
    return null;
  }
  const nullOperator = node.operator === "IS_NULL" || node.operator === "NOT_NULL";
  const value = nullOperator ? "" : stringRuleValue(node.value);
  if (!nullOperator && value.trim() === "") {
    return null;
  }
  return {
    id: crypto.randomUUID(),
    column,
    operator: node.operator,
    value,
  };
}

/** AND-group children become basic rows. A single predicate, an OR group, or an IN/fuzzy child does not. */
function basicRuleRowsFromSpec(spec: PredicateSpecWire | null | undefined): ExtractRuleRow[] {
  const root = spec?.root;
  if (!root || root.type !== "GROUP" || root.op !== "AND") {
    return [];
  }
  const rows: ExtractRuleRow[] = [];
  for (const child of root.children) {
    const row = basicRuleRow(child);
    if (row) {
      rows.push(row);
    }
  }
  return rows;
}

/** A lone IN or FUZZY_MATCH source predicate is restored on Report, not Extract. */
function sourceLegacyRuleFields(spec: PredicateSpecWire | null | undefined): LegacyRuleFields {
  const root = spec?.root;
  if (root?.type === "PREDICATE" && (root.operator === "IN" || root.operator === "FUZZY_MATCH")) {
    return emptyLegacyRuleFields();
  }
  return ruleFieldsFromSpec(spec);
}

/** Query Builder Extract tab — physical tables, attributes, keys, rules (no report-stage options). */
export function hydrateExtractConfigFromRequest(req: RecordMatchRequest): ExtractConfig {
  const srcCrit = req.sourceCriteria[0];
  const tgtCrit = req.targetCriteria[0];
  const displayRef =
    req.sourceDisplayColumns?.[0] ?? (srcCrit ? refFrom(srcCrit) : EMPTY_EXTRACT_CONFIG.sourceRef);
  const sourceRef: CascadeValue = refFrom(displayRef);
  const targetRef: CascadeValue = tgtCrit ? refFrom(tgtCrit) : EMPTY_EXTRACT_CONFIG.targetRef;
  const sourceRules = sourceLegacyRuleFields(req.sourceRules);
  const targetRules = ruleFieldsFromSpec(req.targetRules);

  return {
    sourceRef,
    targetRef,
    displayCols: (req.sourceDisplayColumns ?? []).map((d) => d.column),
    targetDisplayCols: (req.targetDisplayColumns ?? []).map((d) => d.column),
    joinKeySource: srcCrit?.column ?? "",
    joinKeyTarget: tgtCrit?.column ?? "",
    joinType: req.joinType ?? "INNER",
    ruleColumn: sourceRules.column,
    ruleOp: sourceRules.operator,
    ruleValue: sourceRules.value,
    useValuePicker: sourceRules.useValuePicker,
    valuePickerSpec: sourceRules.valuePickerSpec,
    fuzzyRuleEnabled: sourceRules.fuzzyRuleEnabled,
    fuzzyRuleName: sourceRules.fuzzyRuleName,
    fuzzyRuleThreshold: sourceRules.fuzzyRuleThreshold,
    fuzzyIgnoreSpaces: sourceRules.fuzzyIgnoreSpaces,
    fuzzyCaseSensitive: sourceRules.fuzzyCaseSensitive,
    targetRuleColumn: targetRules.column,
    targetRuleOp: targetRules.operator,
    targetRuleValue: targetRules.value,
    sourceRuleRows: basicRuleRowsFromSpec(req.sourceRules),
    targetRuleRows: basicRuleRowsFromSpec(req.targetRules),
    resultLimit: req.resultLimit ?? null,
    countOnly: req.countOnly ?? false,
    additionalTables: (req.additionalJoins ?? []).map((join, index) => ({
      id: `saved-extra-${index + 1}`,
      ref: { catalog: join.catalog, schema: join.schema, table: join.table },
      sourceColumn: join.sourceColumn,
      joinColumn: join.joinColumn,
      displayCols: join.displayColumns ?? [],
    })),
  };
}

function comparisonConfigs(req: RecordMatchRequest): ComparisonPairConfig[] {
  return (req.comparisonGroups ?? []).map((g) => ({
    sourceColumn: g.source[0]?.column ?? "",
    targetColumn: g.target[0]?.column ?? "",
    fuzzyThresholdPercent: g.fuzzyThresholdPercent ?? 80,
  }));
}

/** One predicate root, or each child of an AND group. OR groups contribute nothing. */
function sourceRuleCandidates(spec: PredicateSpecWire | null | undefined): PredicateNodeWire[] {
  const root = spec?.root;
  if (!root) return [];
  if (root.type === "PREDICATE") return [root];
  if (root.type === "GROUP" && root.op === "AND") {
    return root.children.filter((child) => child.type === "PREDICATE");
  }
  return [];
}

type FuzzyMatchParts = {
  name: string;
  threshold: number;
  ignoreSpaces: boolean;
  caseSensitive: boolean;
};

/** Report fuzzy is the saved `[name, threshold, options]` tuple. */
function fuzzyMatchParts(node: PredicateNodeWire): FuzzyMatchParts | null {
  if (node.operator !== "FUZZY_MATCH" || !Array.isArray(node.value) || node.value.length < 3) {
    return null;
  }
  const [name, threshold, options] = node.value;
  if (typeof name !== "string" || typeof threshold !== "number") return null;
  if (options === null || typeof options !== "object" || Array.isArray(options)) return null;
  const opts = options as { ignoreSpaces?: boolean; caseSensitive?: boolean };
  return {
    name,
    threshold,
    ignoreSpaces:
      typeof opts.ignoreSpaces === "boolean"
        ? opts.ignoreSpaces
        : EMPTY_REPORT_CONFIG.sourceFuzzyIgnoreSpaces,
    caseSensitive:
      typeof opts.caseSensitive === "boolean"
        ? opts.caseSensitive
        : EMPTY_REPORT_CONFIG.sourceFuzzyCaseSensitive,
  };
}

/** First IN and first well-formed FUZZY_MATCH on source rules become Report filters. */
function reportSourceFilters(spec: PredicateSpecWire | null | undefined): Pick<
  ReportConfig,
  | "sourceValueFilterEnabled"
  | "sourceValueFilterColumn"
  | "sourceValueFilterSpec"
  | "sourceFuzzyEnabled"
  | "sourceFuzzyColumn"
  | "sourceFuzzyName"
  | "sourceFuzzyThreshold"
  | "sourceFuzzyIgnoreSpaces"
  | "sourceFuzzyCaseSensitive"
> {
  let valueFilter: PredicateNodeWire | null = null;
  let fuzzyColumn = "";
  let fuzzy: FuzzyMatchParts | null = null;

  for (const node of sourceRuleCandidates(spec)) {
    if (!valueFilter && node.operator === "IN") {
      valueFilter = node;
    }
    if (!fuzzy) {
      const parts = fuzzyMatchParts(node);
      if (parts) {
        fuzzy = parts;
        fuzzyColumn = node.column?.column ?? "";
      }
    }
    if (valueFilter && fuzzy) break;
  }

  return {
    sourceValueFilterEnabled: valueFilter != null,
    sourceValueFilterColumn: valueFilter?.column?.column ?? EMPTY_REPORT_CONFIG.sourceValueFilterColumn,
    sourceValueFilterSpec: valueFilter ? { root: valueFilter } : EMPTY_REPORT_CONFIG.sourceValueFilterSpec,
    sourceFuzzyEnabled: fuzzy != null,
    sourceFuzzyColumn: fuzzy ? fuzzyColumn : EMPTY_REPORT_CONFIG.sourceFuzzyColumn,
    sourceFuzzyName: fuzzy?.name ?? EMPTY_REPORT_CONFIG.sourceFuzzyName,
    sourceFuzzyThreshold: fuzzy?.threshold ?? EMPTY_REPORT_CONFIG.sourceFuzzyThreshold,
    sourceFuzzyIgnoreSpaces: fuzzy?.ignoreSpaces ?? EMPTY_REPORT_CONFIG.sourceFuzzyIgnoreSpaces,
    sourceFuzzyCaseSensitive: fuzzy?.caseSensitive ?? EMPTY_REPORT_CONFIG.sourceFuzzyCaseSensitive,
  };
}

/** Query Builder Report tab — filters, fuzzy/join, grouping, comparisons. */
export function hydrateReportConfigFromRequest(req: RecordMatchRequest): ReportConfig {
  const groupCol = req.groupByColumns?.[0]?.column ?? "";
  const agg = req.aggregates?.[0];
  const joinFuzzy = req.sourceCriteria[0]?.fuzzyThresholdPercent ?? 80;
  const groups = req.joinGroups ?? [];
  const fuzzyOpts = groups[0]?.fuzzyOptions;

  return {
    mismatchOnly: req.mismatchOnly ?? false,
    highlightDuplicates: req.highlightDuplicates ?? false,
    dedupEnabled: Boolean(req.dedup),
    joinType: req.joinType ?? "INNER",
    comparisonPairs: comparisonConfigs(req),
    groupEnabled: Boolean(groupCol || agg),
    groupByCol: groupCol,
    aggregateFn: agg?.function ?? "COUNT",
    aggregateCol: agg?.column?.column ?? "",
    countDistinct: agg?.distinct ?? false,
    countOnly: false,
    joinFuzzyThresholdPercent: joinFuzzy,
    joinFuzzyIgnoreSpaces: fuzzyOpts?.ignoreSpaces ?? false,
    joinFuzzyCaseSensitive: fuzzyOpts?.caseSensitive ?? false,
    ...reportSourceFilters(req.sourceRules),
  };
}

export function dualModeFromRequest(req: RecordMatchRequest): boolean {
  return req.singleSource !== true;
}
