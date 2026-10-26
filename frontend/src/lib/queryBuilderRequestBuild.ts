import { isCascadeComplete, type CascadeValue } from "@/components/LakehouseCascade";
import type {
  AdditionalJoin,
  ComparisonGroup,
  DisplayColumn,
  MatchCriterion,
  PredicateNodeWire,
  PredicateSpecWire,
  RecordMatchRequest,
  RuleOperator,
  TableRef,
} from "@/lib/analysisApi";
import type {
  AdditionalTableConfig,
  ExtractConfig,
  ExtractRuleRow,
  ReportConfig,
} from "@/lib/queryBuilderPipelineTypes";

const RULE_OPS: { value: RuleOperator; needsValue: boolean }[] = [
  { value: "EQ", needsValue: true },
  { value: "NE", needsValue: true },
  { value: "LT", needsValue: true },
  { value: "LTE", needsValue: true },
  { value: "GT", needsValue: true },
  { value: "GTE", needsValue: true },
  { value: "IS_NULL", needsValue: false },
  { value: "NOT_NULL", needsValue: false },
];

function parseRuleValue(raw: string): string | number {
  const trimmed = raw.trim();
  if (trimmed === "") return trimmed;
  const asNum = Number(trimmed);
  if (!Number.isNaN(asNum) && /^-?\d+(\.\d+)?$/.test(trimmed)) return asNum;
  return trimmed;
}

function buildPredicateNode(
  ref: TableRef,
  column: string,
  operator: RuleOperator,
  valueRaw: string,
  inValues?: (string | number | null)[],
): PredicateNodeWire | null {
  if (!column) return null;
  let effectiveOp = operator;
  if (inValues && inValues.length > 1) {
    effectiveOp = "IN";
  } else if (inValues && inValues.length === 1) {
    effectiveOp = "EQ";
  }
  const opMeta = RULE_OPS.find((o) => o.value === effectiveOp);
  const needsValue = effectiveOp === "IN" || (opMeta?.needsValue ?? false);
  const node: PredicateNodeWire = {
    type: "PREDICATE",
    column: {
      table: { catalog: ref.catalog, schema: ref.schema, table: ref.table },
      column,
    },
    operator: effectiveOp,
  };
  if (effectiveOp === "IN" && inValues && inValues.length > 0) {
    node.value = inValues.map((v) => (typeof v === "string" ? parseRuleValue(v) : v));
    return node;
  }
  if (needsValue) {
    // Fuzzy mode shares ruleColumn with the ordinary rule. Turning it off leaves
    // the default GT and a blank value, which would compile to `col > ''`.
    if (inValues && inValues.length === 1) {
      const one = inValues[0];
      node.value = one === null ? null : typeof one === "string" ? parseRuleValue(one) : one;
      return node;
    }
    if (valueRaw.trim() === "") return null;
    node.value = parseRuleValue(valueRaw);
  }
  return node;
}

function buildRuleSpec(
  ref: TableRef,
  column: string,
  operator: RuleOperator,
  valueRaw: string,
): PredicateSpecWire | null {
  const node = buildPredicateNode(ref, column, operator, valueRaw);
  return node ? { root: node } : null;
}

/** One valid row is a predicate; two or more are AND-ed. Invalid rows are dropped. */
function buildRulesFromRows(ref: TableRef, rows: readonly ExtractRuleRow[]): PredicateSpecWire | null {
  const children: PredicateNodeWire[] = [];
  for (const row of rows) {
    const node = buildPredicateNode(ref, row.column, row.operator, row.value, row.inValues);
    if (node) children.push(node);
  }
  if (children.length === 0) return null;
  if (children.length === 1) return { root: children[0] };
  return { root: { type: "GROUP", op: "AND", children } };
}

function refAsTable(ref: CascadeValue): TableRef | null {
  return isCascadeComplete(ref) ? (ref as TableRef) : null;
}

function isNameColumn(column: string): boolean {
  return column.toLowerCase().includes("name");
}

function pairIsFuzzy(sourceCol: string, targetCol: string, threshold: number): boolean {
  if (threshold > 0 && (isNameColumn(sourceCol) || isNameColumn(targetCol))) return true;
  return false;
}

export function toggleDisplayColumnSelection(selected: readonly string[], name: string): string[] {
  return selected.includes(name) ? selected.filter((column) => column !== name) : [...selected, name];
}

export function selectAllDisplayColumns(columnNames: readonly string[]): string[] {
  return [...columnNames];
}

export function clearDisplayColumnSelection(): string[] {
  return [];
}

/** Drop attribute picks that are not columns of the table now selected on that side. */
export function retainValidDisplayColumns(
  selected: readonly string[],
  availableNames: readonly string[],
): string[] {
  const available = new Set(availableNames);
  return selected.filter((name) => available.has(name));
}

function applyExtractRunOptions(req: RecordMatchRequest, extract: ExtractConfig): RecordMatchRequest {
  const next = { ...req };
  if (extract.resultLimit != null && extract.resultLimit > 0) {
    next.resultLimit = extract.resultLimit;
  }
  if (extract.countOnly) {
    next.countOnly = true;
  }
  return next;
}

/** Request for full export — never carries the on-screen row cap or count-only mode. */
export function buildExtractExportRequest(dualMode: boolean, extract: ExtractConfig): RecordMatchRequest | null {
  const withoutRunOptions = { ...extract, resultLimit: null, countOnly: false };
  return buildExtractRequest(dualMode, withoutRunOptions);
}

/** Extract-stage request: tables, projections, keys, rules — no report grouping/comparisons. */
export function buildExtractRequest(dualMode: boolean, extract: ExtractConfig): RecordMatchRequest | null {
  const ref = refAsTable(extract.sourceRef);
  if (!ref) return null;

  const displays: DisplayColumn[] = extract.displayCols.map((column) => ({ ...ref, column }));

  let sourceRules: PredicateSpecWire | null;
  if (extract.sourceRuleRows.length > 0) {
    sourceRules = buildRulesFromRows(ref, extract.sourceRuleRows);
  } else {
    sourceRules = buildRuleSpec(ref, extract.ruleColumn, extract.ruleOp, extract.ruleValue);
    if (extract.useValuePicker && extract.valuePickerSpec) {
      sourceRules = extract.valuePickerSpec;
    } else if (extract.fuzzyRuleEnabled && extract.ruleColumn && extract.fuzzyRuleName.trim()) {
      sourceRules = {
        root: {
          type: "PREDICATE",
          column: {
            table: { catalog: ref.catalog, schema: ref.schema, table: ref.table },
            column: extract.ruleColumn,
          },
          operator: "FUZZY_MATCH",
          value: [
            extract.fuzzyRuleName.trim(),
            extract.fuzzyRuleThreshold,
            {
              ignoreSpaces: extract.fuzzyIgnoreSpaces,
              caseSensitive: extract.fuzzyCaseSensitive,
            },
          ],
        },
      };
    }
  }

  if (!dualMode) {
    if (displays.length === 0 && !extract.countOnly) return null;
    return applyExtractRunOptions(
      {
        sourceCriteria: [],
        targetCriteria: [],
        sourceDisplayColumns: displays,
        highlightDuplicates: false,
        dedup: null,
        sourceRules: sourceRules ?? undefined,
        singleSource: true,
      },
      extract,
    );
  }

  const tgt = refAsTable(extract.targetRef);
  if (!tgt || !extract.joinKeySource || !extract.joinKeyTarget) return null;

  const targetDisplays: DisplayColumn[] = (extract.targetDisplayCols ?? []).map((column) => ({
    ...tgt,
    column,
  }));
  const additionalJoins = additionalJoinsFrom(extract.additionalTables);
  if (additionalJoins === null) return null;
  if (
    displays.length === 0 &&
    targetDisplays.length === 0 &&
    additionalJoins.length === 0 &&
    !extract.countOnly
  ) {
    return null;
  }

  const sourceCriteria: MatchCriterion[] = [
    { ...ref, column: extract.joinKeySource, fuzzyThresholdPercent: null },
  ];
  const targetCriteria: MatchCriterion[] = [
    { ...tgt, column: extract.joinKeyTarget, fuzzyThresholdPercent: null },
  ];
  const targetRules =
    extract.targetRuleRows.length > 0
      ? buildRulesFromRows(tgt, extract.targetRuleRows)
      : buildRuleSpec(tgt, extract.targetRuleColumn, extract.targetRuleOp, extract.targetRuleValue);

  const req: RecordMatchRequest = {
    sourceCriteria,
    targetCriteria,
    sourceDisplayColumns: displays,
    highlightDuplicates: false,
    dedup: null,
    sourceRules: sourceRules ?? undefined,
    targetRules: targetRules ?? undefined,
    singleSource: false,
  };
  if (targetDisplays.length > 0) {
    req.targetDisplayColumns = targetDisplays;
  }
  if (extract.joinType !== "INNER") {
    req.joinType = extract.joinType;
  }
  if (additionalJoins.length > 0) {
    req.additionalJoins = additionalJoins;
  }
  return applyExtractRunOptions(req, extract);
}

/** Empty when no extra table is configured. Null when a row was started but not finished. */
function additionalJoinsFrom(tables: AdditionalTableConfig[] | undefined): AdditionalJoin[] | null {
  if (!tables || tables.length === 0) return [];
  const joins: AdditionalJoin[] = [];
  for (const row of tables) {
    const ref = refAsTable(row.ref);
    if (!ref || !row.sourceColumn || !row.joinColumn) return null;
    joins.push({
      catalog: ref.catalog,
      schema: ref.schema,
      table: ref.table,
      sourceColumn: row.sourceColumn,
      joinColumn: row.joinColumn,
      displayColumns: row.displayCols ?? [],
    });
  }
  return joins;
}

function buildComparisonGroups(
  sourceRef: TableRef,
  targetRef: TableRef,
  report: ReportConfig,
): ComparisonGroup[] {
  return report.comparisonPairs
    .filter((p) => p.sourceColumn && p.targetColumn)
    .map((p) => ({
      source: [{ ...sourceRef, column: p.sourceColumn, fuzzyThresholdPercent: null }],
      target: [{ ...targetRef, column: p.targetColumn, fuzzyThresholdPercent: null }],
      mode: "COMBINE" as const,
      fuzzyThresholdPercent:
        isNameColumn(p.sourceColumn) || isNameColumn(p.targetColumn) ? p.fuzzyThresholdPercent : null,
      separator: " ",
    }));
}

/** PREDICATE roots stay one node; AND groups contribute their children. */
function flattenAndPredicates(
  root: PredicateSpecWire["root"] | null | undefined,
): PredicateNodeWire[] {
  if (!root) return [];
  if (root.type === "PREDICATE") return [root];
  if (root.op === "AND") return root.children;
  return [];
}

function reportSourceFilterPredicates(ref: TableRef, report: ReportConfig): PredicateNodeWire[] {
  const nodes: PredicateNodeWire[] = [];
  if (report.sourceValueFilterEnabled && report.sourceValueFilterSpec?.root) {
    nodes.push(...flattenAndPredicates(report.sourceValueFilterSpec.root));
  }
  if (!report.sourceFuzzyEnabled) return nodes;
  const column = report.sourceFuzzyColumn.trim();
  const name = report.sourceFuzzyName.trim();
  if (!column || !name) return nodes;
  nodes.push({
    type: "PREDICATE",
    column: {
      table: { catalog: ref.catalog, schema: ref.schema, table: ref.table },
      column,
    },
    operator: "FUZZY_MATCH",
    value: [
      name,
      report.sourceFuzzyThreshold,
      {
        ignoreSpaces: report.sourceFuzzyIgnoreSpaces,
        caseSensitive: report.sourceFuzzyCaseSensitive,
      },
    ],
  });
  return nodes;
}

/** One extra predicate stays a PREDICATE root; two or more are AND-ed with existing rules. */
function combineSourceRules(
  existing: PredicateSpecWire | null | undefined,
  extra: readonly PredicateNodeWire[],
): PredicateSpecWire {
  const children = [...flattenAndPredicates(existing?.root), ...extra];
  if (children.length === 1) return { root: children[0] };
  return { root: { type: "GROUP", op: "AND", children } };
}

/** Full server request = extract base + report-stage options. */
export function buildMergedRequest(
  dualMode: boolean,
  extract: ExtractConfig,
  report: ReportConfig,
): RecordMatchRequest | null {
  const base = buildExtractRequest(dualMode, extract);
  if (!base) return null;

  const ref = refAsTable(extract.sourceRef)!;
  const req: RecordMatchRequest = { ...base };

  req.highlightDuplicates = report.highlightDuplicates;
  req.mismatchOnly = report.mismatchOnly;

  const joinType = report.joinType ?? extract.joinType;
  if (!dualMode) {
    req.singleSource = true;
  } else if (joinType !== "INNER") {
    req.joinType = joinType;
  } else {
    delete req.joinType;
  }

  if (dualMode && req.sourceCriteria?.[0] && req.targetCriteria?.[0]) {
    const srcCol = req.sourceCriteria[0].column;
    const tgtCol = req.targetCriteria[0].column;
    const fuzzy = pairIsFuzzy(srcCol, tgtCol, report.joinFuzzyThresholdPercent);
    req.sourceCriteria = [
      {
        ...req.sourceCriteria[0],
        fuzzyThresholdPercent: fuzzy ? report.joinFuzzyThresholdPercent : null,
      },
    ];
  }

  if (report.mismatchOnly && req.comparisonGroups && req.comparisonGroups.length > 0) {
    req.mismatchOnly = true;
  } else {
    delete req.mismatchOnly;
  }

  if (report.groupEnabled && report.groupByCol) {
    req.groupByColumns = [{ ...ref, column: report.groupByCol }];
    req.sourceDisplayColumns = [];
    delete req.targetDisplayColumns;
    req.aggregates = [];
    if (report.aggregateFn === "COUNT" && !report.aggregateCol) {
      req.aggregates.push({ function: "COUNT", distinct: report.countDistinct });
    } else if (report.aggregateCol) {
      req.aggregates.push({
        function: report.aggregateFn,
        column: { ...ref, column: report.aggregateCol },
        distinct: report.aggregateFn === "COUNT" ? report.countDistinct : false,
      });
    }
    // Backend rejects grouping combined with comparisons or dedup (MatchGroupingSql).
    req.comparisonGroups = [];
    req.dedup = null;
    delete req.mismatchOnly;
  }

  const tgt = refAsTable(extract.targetRef);
  if (dualMode && tgt && !(report.groupEnabled && report.groupByCol)) {
    const groups = buildComparisonGroups(ref, tgt, report);
    if (groups.length > 0) {
      req.comparisonGroups = groups;
    }
  }

  if (report.dedupEnabled && dualMode && tgt && !(report.groupEnabled && report.groupByCol)) {
    const dedupCol =
      extract.sourceRef.catalog && extract.displayCols.includes("last_refreshed_at")
        ? "last_refreshed_at"
        : extract.joinKeySource;
    req.dedup = { ...ref, column: dedupCol };
  }

  const extraSourceFilters = reportSourceFilterPredicates(ref, report);
  if (extraSourceFilters.length > 0) {
    req.sourceRules = combineSourceRules(req.sourceRules, extraSourceFilters);
  }

  const grouped = Boolean(report.groupEnabled && report.groupByCol);
  if (report.countOnly && !grouped) {
    req.countOnly = true;
  } else {
    delete req.countOnly;
  }

  return req;
}

/** Full-result export — never carries count-only or the on-screen row cap. */
export function withoutCountOnly(req: RecordMatchRequest): RecordMatchRequest {
  const next = { ...req };
  delete next.countOnly;
  delete next.resultLimit;
  return next;
}

/** Exact join size from a count-only stream row (`COUNT(*) AS row_count`). */
export function rowCountFromMatchRow(row: Record<string, unknown> | undefined | null): number | null {
  if (!row) return null;
  const raw = row.row_count ?? row.ROW_COUNT;
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "bigint") return Number(raw);
  if (typeof raw === "string" && raw.trim() !== "") {
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/** Persist extract attribute picks on saved queries even when report grouping clears display columns on the wire. */
export function preserveExtractDisplayColumnsOnSave(
  extract: ExtractConfig,
  req: RecordMatchRequest,
): RecordMatchRequest {
  const ref = refAsTable(extract.sourceRef);
  const tgt = refAsTable(extract.targetRef);
  let next = req;
  if (ref && extract.displayCols.length > 0) {
    next = {
      ...next,
      sourceDisplayColumns: extract.displayCols.map((column) => ({ ...ref, column })),
    };
  }
  const targetCols = extract.targetDisplayCols ?? [];
  if (tgt && targetCols.length > 0 && next.singleSource !== true) {
    next = {
      ...next,
      targetDisplayColumns: targetCols.map((column) => ({ ...tgt, column })),
    };
  }
  return next;
}
