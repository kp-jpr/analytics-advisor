"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import LakehouseCascade, {
  isCascadeComplete,
  type CascadeFetchers,
  type CascadeValue,
} from "@/components/LakehouseCascade";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";
import { BasicRuleListEditor } from "@/components/query-builder/BasicRuleListEditor";
import {
  downloadRecordMatchExport,
  type MatchExportFormat,
  fetchMatchSql,
  listAnalysisCatalogs,
  listAnalysisColumns,
  listAnalysisLayers,
  listAnalysisSourceSystems,
  listAnalysisTableGroups,
  listAnalysisSchemas,
  listAnalysisTables,
  runRecordMatchStream,
  type JoinType,
  type RecordMatchRequest,
  type RegisteredColumn,
} from "@/lib/analysisApi";
import { useQueryResultsStore } from "@/lib/queryResultsStore";
import { useShell } from "@/components/shell/ShellProviders";
import {
  buildExtractExportRequest,
  buildExtractRequest,
  clearDisplayColumnSelection,
  retainValidDisplayColumns,
  selectAllDisplayColumns,
  toggleDisplayColumnSelection,
} from "@/lib/queryBuilderRequestBuild";
import { MetadataColumnStatus } from "@/components/query-builder/MetadataColumnStatus";
import { useQueryBuilderPipeline } from "@/lib/queryBuilderPipelineStore";
import {
  MAX_ADDITIONAL_TABLES,
  type AdditionalTableConfig,
  type ExtractRuleRow,
} from "@/lib/queryBuilderPipelineTypes";

const REGISTRY_FETCHERS: CascadeFetchers = {
  listSourceSystems: listAnalysisSourceSystems,
  listTableGroups: listAnalysisTableGroups,
  listLayers: listAnalysisLayers,
  listCatalogs: listAnalysisCatalogs,
  listSchemas: listAnalysisSchemas,
  listTables: listAnalysisTables,
};

const JOIN_TYPE_OPTIONS: { value: JoinType; label: string }[] = [
  { value: "INNER", label: "Only matching records" },
  { value: "LEFT", label: "All source records" },
  { value: "RIGHT", label: "All target records" },
  { value: "FULL", label: "All records from both" },
];

const MAX_DISPLAYED_ROWS = 10000;
const MAX_ROWS_TO_PARSE = 200000;

function fillCatalog(template: string, values: Record<string, string | number>): string {
  return template.replaceAll(/\{(\w+)\}/g, (_match, name: string) => {
    const value = values[name];
    return value === undefined ? "" : String(value);
  });
}

/**
 * Saved queries that predate the rule list still store one ordinary rule in the
 * legacy column. Show that as a single row until the editor writes the array;
 * clearing the legacy column on that write stops an empty list from bringing it back.
 */
function basicRuleRowsForEditor(
  rows: ExtractRuleRow[],
  legacyId: "legacy-source" | "legacy-target",
  column: string,
  operator: ExtractRuleRow["operator"],
  value: string,
): ExtractRuleRow[] {
  if (rows.length > 0 || column === "") return rows;
  return [{ id: legacyId, column, operator, value }];
}

function attributeSelectionSummary(
  selectedCount: number,
  optionCount: number,
  text: { none: string; all: string; count: string },
): string {
  if (selectedCount <= 0) return text.none;
  if (optionCount > 0 && selectedCount === optionCount) return fillCatalog(text.all, { count: selectedCount });
  return fillCatalog(text.count, { selected: selectedCount, total: optionCount });
}

export function AttributeMultiSelect({
  id,
  label,
  columns,
  selected,
  onChange,
}: Readonly<{
  id: string;
  label: string;
  columns: RegisteredColumn[];
  selected: string[];
  onChange: (next: string[]) => void;
}>) {
  const { t } = useShell();
  const names = columns.map((column) => column.name);
  const summary = attributeSelectionSummary(selected.length, names.length, {
    none: t("attrSummaryNone"),
    all: t("attrSummaryAll"),
    count: t("attrSummaryCount"),
  });
  return (
    <details className="cascade-optional-filters attr-multi-select">
      <summary id={id} className="attr-multi-select-summary" aria-label={fillCatalog(t("ariaAttrSummary"), { label, summary })}>
        <span>{summary}</span>
        <span aria-hidden="true">▾</span>
      </summary>
      <div role="group" className="attr-multi-select-options" aria-label={label}>
        <div className="row attr-multi-select-actions">
          <button
            type="button"
            className="btn secondary sm"
            aria-label={fillCatalog(t("ariaSelectAllAttrs"), { label })}
            onClick={() => onChange(selectAllDisplayColumns(names))}
          >
            {t("btnSelectAll")}
          </button>
          <button
            type="button"
            className="btn secondary sm"
            aria-label={fillCatalog(t("ariaClearAttrs"), { label })}
            onClick={() => onChange(clearDisplayColumnSelection())}
          >
            {t("btnClearSelection")}
          </button>
        </div>
        <div className="attr-multi-select-list">
          {columns.map((column) => {
            const caption = column.businessName ?? column.name;
            return (
              <label key={column.name} className="checkbox-row attr-multi-select-option">
                <input
                  type="checkbox"
                  checked={selected.includes(column.name)}
                  aria-label={fillCatalog(t("ariaAttrColumn"), { label, caption })}
                  onChange={() => onChange(toggleDisplayColumnSelection(selected, column.name))}
                />
                {caption}
              </label>
            );
          })}
        </div>
      </div>
    </details>
  );
}

function AdditionalJoinTables({
  sourceColumns,
  tables,
  defaultSourceColumn,
  onChange,
}: Readonly<{
  sourceColumns: RegisteredColumn[];
  tables: AdditionalTableConfig[];
  defaultSourceColumn: string;
  onChange: (next: AdditionalTableConfig[]) => void;
}>) {
  const { t } = useShell();
  const updateRow = (id: string, patch: Partial<AdditionalTableConfig>) => {
    onChange(tables.map((row) => (row.id === id ? { ...row, ...patch } : row)));
  };
  return (
    <div style={{ marginTop: 16 }}>
      <div className="ttl">{t("qbAdditionalTables")}</div>
      <p className="desc">{fillCatalog(t("qbAdditionalTablesDesc"), { max: MAX_ADDITIONAL_TABLES })}</p>
      {tables.map((row, index) => (
        <AdditionalJoinRow
          key={row.id}
          index={index}
          row={row}
          sourceColumns={sourceColumns}
          onChange={(patch) => updateRow(row.id, patch)}
          onRemove={() => onChange(tables.filter((item) => item.id !== row.id))}
        />
      ))}
      {tables.length < MAX_ADDITIONAL_TABLES ? (
        <button
          type="button"
          className="btn secondary"
          onClick={() =>
            onChange([
              ...tables,
              {
                id: `extra-${Date.now().toString(36)}-${tables.length}`,
                ref: { catalog: "", schema: "", table: "" },
                sourceColumn: defaultSourceColumn,
                joinColumn: "",
                displayCols: [],
                joinType: "INNER",
                ruleRows: [],
              },
            ])
          }
        >
          {t("qbAddTable")} ({tables.length}/{MAX_ADDITIONAL_TABLES})
        </button>
      ) : null}
    </div>
  );
}

function AdditionalJoinRow({
  index,
  row,
  sourceColumns,
  onChange,
  onRemove,
}: Readonly<{
  index: number;
  row: AdditionalTableConfig;
  sourceColumns: RegisteredColumn[];
  onChange: (patch: Partial<AdditionalTableConfig>) => void;
  onRemove: () => void;
}>) {
  const { t } = useShell();
  const [columns, setColumns] = useState<RegisteredColumn[]>([]);
  const refKey = `${row.ref.catalog}|${row.ref.schema}|${row.ref.table}`;
  const rowRef = useRef(row);
  const onChangeRef = useRef(onChange);
  rowRef.current = row;
  onChangeRef.current = onChange;

  useEffect(() => {
    const current = rowRef.current;
    if (!isCascadeComplete(current.ref)) {
      setColumns([]);
      return undefined;
    }
    let cancelled = false;
    listAnalysisColumns(current.ref).then((cols) => {
      if (cancelled) return;
      setColumns(cols);
      const names = cols.map((column) => column.name);
      const latest = rowRef.current;
      const displayCols = retainValidDisplayColumns(latest.displayCols, names);
      const joinColumn = latest.joinColumn && names.includes(latest.joinColumn) ? latest.joinColumn : "";
      const ruleRows = (latest.ruleRows ?? []).filter((rule) => names.includes(rule.column));
      if (
        displayCols.length !== latest.displayCols.length
        || joinColumn !== latest.joinColumn
        || ruleRows.length !== (latest.ruleRows ?? []).length
      ) {
        onChangeRef.current({ displayCols, joinColumn, ruleRows });
      }
    });
    return () => {
      cancelled = true;
    };
  }, [refKey]);

  const label = fillCatalog(t("qbExtraTable"), { n: index + 3 });
  return (
    <div
      className="section"
      style={{ marginTop: 12, padding: 12, border: "1px solid var(--srse-border)", borderRadius: 8 }}
    >
      <div className="row" style={{ justifyContent: "space-between", alignItems: "center", gap: 12 }}>
        <div className="ttl" style={{ margin: 0 }}>
          {label}
        </div>
        <button type="button" className="btn secondary sm" onClick={onRemove}>
          {t("qbRemoveTable")}
        </button>
      </div>
      <div className="pick" style={{ marginTop: 8 }}>
        <LakehouseCascade
          idPrefix={`extract-extra-${row.id}`}
          value={row.ref}
          onChange={(ref) => onChange({ ref, joinColumn: "", displayCols: [], ruleRows: [] })}
          fetchers={REGISTRY_FETCHERS}
          appearance="prototype"
        />
      </div>
      <div className="two-col" style={{ marginTop: 12 }}>
        <div className="field">
          <label>{t("qbExtraSourceKey")}</label>
          <select
            aria-label={`${label} ${t("qbExtraSourceKey")}`}
            value={row.sourceColumn}
            onChange={(e) => onChange({ sourceColumn: e.target.value })}
          >
            <option value="">{t("selPKPlaceholder")}</option>
            {sourceColumns.map((column) => (
              <option key={column.name} value={column.name}>
                {column.businessName ?? column.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>{t("qbExtraJoinKey")}</label>
          <select
            aria-label={`${label} ${t("qbExtraJoinKey")}`}
            value={row.joinColumn}
            onChange={(e) => onChange({ joinColumn: e.target.value })}
          >
            <option value="">{t("selPKPlaceholder")}</option>
            {columns.map((column) => (
              <option key={column.name} value={column.name}>
                {column.businessName ?? column.name}
              </option>
            ))}
          </select>
        </div>
      </div>
      {isCascadeComplete(row.ref) ? (
        <div className="pick" style={{ marginTop: 12 }}>
          <div className="field" style={{ maxWidth: 360, marginBottom: 12 }}>
            <label htmlFor={`extract-extra-join-${row.id}`}>{t("qbExtraJoinType")}</label>
            <select
              id={`extract-extra-join-${row.id}`}
              aria-label={`${label} ${t("qbExtraJoinType")}`}
              value={row.joinType ?? "INNER"}
              onChange={(e) => onChange({ joinType: e.target.value as JoinType })}
            >
              {JOIN_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <div className="ttl">{t("qbExtraAttrs")}</div>
          <AttributeMultiSelect
            id={`extract-extra-attrs-${row.id}`}
            label={label}
            columns={columns}
            selected={row.displayCols}
            onChange={(displayCols) => onChange({ displayCols })}
          />
          <div className="ttl" style={{ marginTop: 12 }}>{t("qbExtraRules")}</div>
          <BasicRuleListEditor
            side="target"
            idPrefix={`extra-${row.id}`}
            sideLabel={label}
            tableRef={row.ref}
            columns={columns}
            rows={row.ruleRows ?? []}
            onChange={(ruleRows) => onChange({ ruleRows })}
          />
        </div>
      ) : null}
    </div>
  );
}

type ExtractRecordsTabProps = {
  onGoReport?: () => void;
};

export default function ExtractRecordsTab({ onGoReport }: Readonly<ExtractRecordsTabProps>) {
  const dualMode = useQueryBuilderPipeline((s) => s.dualMode);
  const extract = useQueryBuilderPipeline((s) => s.extract);
  const patchExtract = useQueryBuilderPipeline((s) => s.patchExtract);
  const commitExtractSuccess = useQueryBuilderPipeline((s) => s.commitExtractSuccess);
  const extractResult = useQueryBuilderPipeline((s) => s.extractResult);
  const baseExtractRequest = useQueryBuilderPipeline((s) => s.baseExtractRequest);
  const mode: "single" | "join" = dualMode ? "join" : "single";

  const {
    sourceRef,
    targetRef,
    displayCols,
    targetDisplayCols,
    joinKeySource,
    joinKeyTarget,
    joinType,
    additionalTables,
    ruleColumn,
    ruleOp,
    ruleValue,
    targetRuleColumn,
    targetRuleOp,
    targetRuleValue,
    sourceRuleRows,
    targetRuleRows,
    resultLimit,
    countOnly,
  } = extract;

  const [sourceColumns, setSourceColumns] = useState<RegisteredColumn[]>([]);
  const [targetColumns, setTargetColumns] = useState<RegisteredColumn[]>([]);

  const setSourceRef = (v: CascadeValue) => {
    const current = useQueryBuilderPipeline.getState().extract;
    const sameTable =
      v.catalog === current.sourceRef.catalog &&
      v.schema === current.sourceRef.schema &&
      v.table === current.sourceRef.table;
    if (!sameTable) setSourceColumns([]);
    patchExtract({
      sourceRef: v,
      ...(!isCascadeComplete(v) && current.displayCols.length > 0 ? { displayCols: [] } : {}),
    });
  };
  const setTargetRef = (v: CascadeValue) => {
    const current = useQueryBuilderPipeline.getState().extract;
    const sameTable =
      v.catalog === current.targetRef.catalog &&
      v.schema === current.targetRef.schema &&
      v.table === current.targetRef.table;
    if (!sameTable) setTargetColumns([]);
    const targetCols = current.targetDisplayCols ?? [];
    patchExtract({
      targetRef: v,
      ...(mode === "join" && !isCascadeComplete(v) && targetCols.length > 0 ? { targetDisplayCols: [] } : {}),
    });
  };
  const setJoinKeySource = (v: string) => patchExtract({ joinKeySource: v });
  const setJoinKeyTarget = (v: string) => patchExtract({ joinKeyTarget: v });
  const setJoinType = (v: JoinType) => patchExtract({ joinType: v });
  const commitSourceRuleRows = (next: ExtractRuleRow[]) =>
    patchExtract({ sourceRuleRows: next, ruleColumn: "", ruleValue: "" });
  const commitTargetRuleRows = (next: ExtractRuleRow[]) =>
    patchExtract({ targetRuleRows: next, targetRuleColumn: "", targetRuleValue: "" });

  const [sqlPreview, setSqlPreview] = useState<string | null>(null);
  const [sqlPreviewError, setSqlPreviewError] = useState<string | null>(null);
  const [sqlPreviewLoading, setSqlPreviewLoading] = useState(false);

  const [matchStatus, setMatchStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [matchError, setMatchError] = useState<string | null>(null);
  const [matchColumns, setMatchColumns] = useState<string[]>([]);
  const [matchRows, setMatchRows] = useState<Record<string, unknown>[]>([]);

  /**
   * Totals a SUM or AVG could not include, surfaced above the grid rather than
   * left as another column to scroll past.
   *
   * The backend emits `<alias>_unparseable` beside any aggregate it had to cast,
   * counting non-null values that would not parse as numbers. Zero stays silent —
   * there is nothing to warn about, and a permanent banner is one people learn to
   * ignore. A wrong total that looks right is the thing this exists to prevent.
   */
  const unparseableTotals = useMemo(() => {
    const suffix = "_unparseable";
    return matchColumns
      .filter((c) => c.endsWith(suffix))
      .map((column) => ({
        column,
        forColumn: column.slice(0, -suffix.length),
        total: matchRows.reduce((sum, row) => {
          const raw = row[column];
          const n = typeof raw === "number" ? raw : Number(raw ?? 0);
          return sum + (Number.isFinite(n) ? n : 0);
        }, 0),
      }))
      .filter((u) => u.total > 0);
  }, [matchColumns, matchRows]);
  const [matchSql, setMatchSql] = useState("");
  const [matchTotalRows, setMatchTotalRows] = useState<number | null>(null);
  const [matchTooManyToDisplay, setMatchTooManyToDisplay] = useState(false);
  const [matchCountIsPartial, setMatchCountIsPartial] = useState(false);
  const lastRunRequestRef = useRef<RecordMatchRequest | null>(null);
  const restoredFromPipelineRef = useRef(false);
  const pendingRowsRef = useRef<Record<string, unknown>[]>([]);
  const flushIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const rowsSeenRef = useRef(0);
  const streamColumnsRef = useRef<string[]>([]);
  const carriedRowsRef = useRef<Record<string, unknown>[]>([]);
  const lastSqlRef = useRef("");
  const setLastResult = useQueryResultsStore((s) => s.setLastResult);

  function publishExtractResult(
    columns: string[],
    rows: Record<string, unknown>[],
    totalRows: number | null,
  ) {
    if (columns.length === 0 || rows.length === 0) {
      return;
    }
    setLastResult({
      columns,
      rows,
      totalRows,
      source: "extract",
      matchExportRequest: lastRunRequestRef.current,
    });
  }

  const sourceRefKey = `${sourceRef.catalog}.${sourceRef.schema}.${sourceRef.table}`;
  const targetRefKey = `${targetRef.catalog}.${targetRef.schema}.${targetRef.table}`;

  useEffect(() => {
    if (!isCascadeComplete(sourceRef)) {
      return undefined;
    }
    let cancelled = false;
    listAnalysisColumns(sourceRef).then((cols) => {
      if (cancelled) return;
      setSourceColumns(cols);
      const current = useQueryBuilderPipeline.getState().extract.displayCols;
      const next = retainValidDisplayColumns(current, cols.map((column) => column.name));
      if (next.length !== current.length) patchExtract({ displayCols: next });
    });
    return () => {
      cancelled = true;
    };
  }, [sourceRefKey, sourceRef, patchExtract]);

  useEffect(() => {
    if (mode !== "join" || !isCascadeComplete(targetRef)) {
      return undefined;
    }
    let cancelled = false;
    listAnalysisColumns(targetRef).then((cols) => {
      if (cancelled) return;
      setTargetColumns(cols);
      const current = useQueryBuilderPipeline.getState().extract.targetDisplayCols ?? [];
      const next = retainValidDisplayColumns(current, cols.map((column) => column.name));
      if (next.length !== current.length) patchExtract({ targetDisplayCols: next });
    });
    return () => {
      cancelled = true;
    };
  }, [mode, targetRefKey, targetRef, patchExtract]);

  /** Tab unmount drops local grid state; restore from the pipeline snapshot after Report Analysis. */
  useEffect(() => {
    if (restoredFromPipelineRef.current || !extractResult) {
      return;
    }
    restoredFromPipelineRef.current = true;
    setMatchColumns(extractResult.columns);
    setMatchRows(extractResult.rows);
    setMatchSql(extractResult.sql);
    setSqlPreview(extractResult.sql);
    setMatchTotalRows(extractResult.totalRows);
    setMatchStatus("ok");
    if (baseExtractRequest) {
      lastRunRequestRef.current = baseExtractRequest;
    }
  }, [extractResult, baseExtractRequest]);

  const requestReady = buildExtractRequest(dualMode, extract) !== null;

  useEffect(() => {
    if (!requestReady) {
      setSqlPreview(null);
      setSqlPreviewError(null);
      return undefined;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      const req = buildExtractRequest(dualMode, extract);
      if (!req || controller.signal.aborted) return;
      setSqlPreviewLoading(true);
      setSqlPreviewError(null);
      fetchMatchSql(req, controller.signal)
        .then((sql) => {
          if (!controller.signal.aborted) setSqlPreview(sql);
        })
        .catch((err: unknown) => {
          if (controller.signal.aborted) return;
          if (err instanceof DOMException && err.name === "AbortError") return;
          setSqlPreview(null);
          setSqlPreviewError(err instanceof Error ? err.message : String(err));
        })
        .finally(() => {
          if (!controller.signal.aborted) setSqlPreviewLoading(false);
        });
    }, 1000);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [dualMode, extract, requestReady]);

  const sourceColumnsDisplay = isCascadeComplete(sourceRef) ? sourceColumns : [];
  const targetColumnsDisplay = mode === "join" && isCascadeComplete(targetRef) ? targetColumns : [];

  function buildRequest(): RecordMatchRequest | null {
    return buildExtractRequest(dualMode, extract);
  }

  function commitSuccess(req: RecordMatchRequest, totalRows: number | null, sql: string) {
    const exportReq = buildExtractExportRequest(dualMode, extract) ?? req;
    commitExtractSuccess(exportReq, {
      columns: streamColumnsRef.current,
      rows: carriedRowsRef.current,
      totalRows,
      sql,
    });
  }

  function flushPendingRows() {
    const batch = pendingRowsRef.current;
    if (batch.length === 0) {
      return;
    }
    pendingRowsRef.current = [];
    setMatchRows((prev) => [...prev, ...batch].slice(0, MAX_DISPLAYED_ROWS));
  }

  async function runExtract() {
    const req = buildRequest();
    if (!req) {
      setMatchError("Pick a table, at least one output column, and (for two-table mode) a join key.");
      return;
    }
    if (flushIntervalRef.current) {
      clearInterval(flushIntervalRef.current);
    }
    rowsSeenRef.current = 0;
    pendingRowsRef.current = [];
    streamColumnsRef.current = [];
    carriedRowsRef.current = [];
    setMatchStatus("loading");
    setMatchError(null);
    setMatchColumns([]);
    setMatchRows([]);
    setMatchSql("");
    setMatchTotalRows(null);
    setMatchTooManyToDisplay(false);
    setMatchCountIsPartial(false);
    lastRunRequestRef.current = req;

    flushIntervalRef.current = setInterval(flushPendingRows, 200);
    const controller = new AbortController();

    try {
      await runRecordMatchStream(
        req,
        {
          onMeta: ({ columns, sql }) => {
            streamColumnsRef.current = columns;
            setMatchColumns(columns);
            setMatchSql(sql);
            lastSqlRef.current = sql;
          },
          onRow: (row) => {
            rowsSeenRef.current += 1;
            if (rowsSeenRef.current <= MAX_DISPLAYED_ROWS) {
              pendingRowsRef.current.push(row);
            }
            if (carriedRowsRef.current.length < MAX_DISPLAYED_ROWS) {
              carriedRowsRef.current.push(row);
            }
            if (rowsSeenRef.current === MAX_ROWS_TO_PARSE + 1) {
              setMatchCountIsPartial(true);
              controller.abort();
            }
            if (rowsSeenRef.current === MAX_DISPLAYED_ROWS + 1) {
              setMatchTooManyToDisplay(true);
            }
          },
          onDone: (totalRows) => {
            flushPendingRows();
            setMatchTotalRows(totalRows);
            setMatchStatus("ok");
            publishExtractResult(streamColumnsRef.current, carriedRowsRef.current, totalRows);
            commitSuccess(req, totalRows, lastSqlRef.current);
          },
          onError: (message) => {
            setMatchError(message);
            setMatchStatus("error");
          },
        },
        controller.signal,
      );
      if (matchStatus !== "error") {
        flushPendingRows();
        setMatchStatus((s) => (s === "loading" ? "ok" : s));
      }
    } catch (err: unknown) {
      if (controller.signal.aborted && rowsSeenRef.current > MAX_ROWS_TO_PARSE) {
        flushPendingRows();
        setMatchStatus("ok");
        publishExtractResult(
          streamColumnsRef.current,
          carriedRowsRef.current,
          rowsSeenRef.current,
        );
        commitSuccess(req, rowsSeenRef.current, lastSqlRef.current);
      } else {
        setMatchError(err instanceof Error ? err.message : String(err));
        setMatchStatus("error");
      }
    } finally {
      if (flushIntervalRef.current) {
        clearInterval(flushIntervalRef.current);
        flushIntervalRef.current = null;
      }
    }
  }

  async function previewSql() {
    const req = buildRequest();
    if (!req) {
      setSqlPreviewError("Complete the table, columns, and join key (if two-table) first.");
      return;
    }
    setSqlPreviewLoading(true);
    setSqlPreviewError(null);
    try {
      setSqlPreview(await fetchMatchSql(req));
    } catch (err: unknown) {
      setSqlPreview(null);
      setSqlPreviewError(err instanceof Error ? err.message : String(err));
    } finally {
      setSqlPreviewLoading(false);
    }
  }

  async function downloadFullExport(format: MatchExportFormat) {
    const req =
      baseExtractRequest ??
      buildExtractExportRequest(dualMode, extract) ??
      lastRunRequestRef.current;
    if (!req) {
      throw new Error("Run a query first.");
    }
    const ext = format === "xlsx" ? "xlsx" : format;
    const blob = await downloadRecordMatchExport(req, format);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `extract-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  const { t, modeKey } = useShell();
  const dual = mode === "join";
  const previewText =
    sqlPreview ??
    (matchSql ||
      (isCascadeComplete(sourceRef)
        ? "Ready to plan SQL — use Preview or Run Query."
        : "Select attributes and tables to see the query preview."));
  return (
    <div className="query-builder-embed" data-mode={dual ? "dual" : "single"}>
      <section className="section">
        <h4>
          <span className="step-num">1</span>
          {modeKey("secChooseTables", "secChooseTablesSingle", dual)}
        </h4>
        <p className="desc">{t("secChooseTablesDesc")}</p>
        <div className="two-col">
          <div className="pick">
            <div className="ttl">{t("lblSourceTable")}</div>
            <LakehouseCascade
              idPrefix="extract-source"
              value={sourceRef}
              onChange={setSourceRef}
              fetchers={REGISTRY_FETCHERS}
              appearance="prototype"
            />
          </div>
          <div className="pick dest-only">
            <div className="ttl">{t("lblDestTable")}</div>
            <LakehouseCascade
              idPrefix="extract-target"
              value={targetRef}
              onChange={setTargetRef}
              fetchers={REGISTRY_FETCHERS}
              appearance="prototype"
            />
          </div>
        </div>
      </section>

      {(isCascadeComplete(sourceRef) || (dual && isCascadeComplete(targetRef))) && (
        <section className="section">
          <h4>
            <span className="step-num">2</span>
            {modeKey("secSelectAttrs", "secSelectAttrsSingle", dual)}
          </h4>
          <p className="desc">{t("secSelectAttrsDesc")}</p>
          <div className="two-col">
            {isCascadeComplete(sourceRef) && (
              <div className="pick">
                <div className="ttl">{t("lblSourceAttr")}</div>
                <MetadataColumnStatus tableRef={sourceRef} />
                <AttributeMultiSelect
                  id="extract-source-attrs"
                  label={t("lblSourceAttributes")}
                  columns={sourceColumnsDisplay}
                  selected={displayCols}
                  onChange={(next) => patchExtract({ displayCols: next })}
                />
              </div>
            )}
            {dual && isCascadeComplete(targetRef) && (
              <div className="pick dest-only">
                <div className="ttl">{t("lblDestAttr")}</div>
                <MetadataColumnStatus tableRef={targetRef} />
                <AttributeMultiSelect
                  id="extract-dest-attrs"
                  label={t("lblDestAttributes")}
                  columns={targetColumnsDisplay}
                  selected={targetDisplayCols ?? []}
                  onChange={(next) => patchExtract({ targetDisplayCols: next })}
                />
              </div>
            )}
          </div>
        </section>
      )}

      {dual && isCascadeComplete(sourceRef) && isCascadeComplete(targetRef) && (
        <section className="section">
          <h4>
            <span className="step-num">3</span>
            {modeKey("secPrimaryKeys", "secPrimaryKeysSingle", dual)}
          </h4>
          <p className="desc">{modeKey("secPrimaryKeysDesc", "secPrimaryKeysDescSingle", dual)}</p>
          <div className="two-col">
            <div className="field">
              <label>{t("lblSourcePK")}</label>
              <select
                aria-label={t("lblSourcePK")}
                value={joinKeySource}
                onChange={(e) => setJoinKeySource(e.target.value)}
              >
                <option value="">{t("selPKPlaceholder")}</option>
                {sourceColumnsDisplay.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field dest-only">
              <label>{t("lblDestPK")}</label>
              <select
                aria-label={t("lblDestPK")}
                value={joinKeyTarget}
                onChange={(e) => setJoinKeyTarget(e.target.value)}
              >
                <option value="">{t("selPKPlaceholder")}</option>
                {targetColumnsDisplay.map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="field" style={{ marginTop: 12, maxWidth: 360 }}>
            <label>{t("qbJoinType")}</label>
            <select
              aria-label={t("qbJoinType")}
              value={joinType}
              onChange={(e) => setJoinType(e.target.value as JoinType)}
            >
              {JOIN_TYPE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <AdditionalJoinTables
            sourceColumns={sourceColumnsDisplay}
            tables={additionalTables}
            defaultSourceColumn={joinKeySource}
            onChange={(next) => patchExtract({ additionalTables: next })}
          />
        </section>
      )}

      <section className="section">
        <h4>
          <span className="step-num">4</span>
          {modeKey("secRules", "secRulesSingle", dual)}
        </h4>
        <p className="desc">{t("secRulesDesc")}</p>
        <div className="two-col">
          <div className="pick">
            <div className="ttl">{t("lblRuleSource")}</div>
            <BasicRuleListEditor
              side="source"
              tableRef={sourceRef}
              columns={sourceColumnsDisplay}
              rows={basicRuleRowsForEditor(sourceRuleRows, "legacy-source", ruleColumn, ruleOp, ruleValue)}
              onChange={commitSourceRuleRows}
            />
          </div>
          <div className="pick dest-only">
            <div className="ttl">{t("lblRuleDest")}</div>
            {dual ? (
              <BasicRuleListEditor
                side="target"
                tableRef={targetRef}
                columns={targetColumnsDisplay}
                rows={basicRuleRowsForEditor(
                  targetRuleRows,
                  "legacy-target",
                  targetRuleColumn,
                  targetRuleOp,
                  targetRuleValue,
                )}
                onChange={commitTargetRuleRows}
              />
            ) : (
              <p className="text-muted" style={{ margin: 0 }}>
                {t("secRulesDescSingle")}
              </p>
            )}
          </div>
        </div>
      </section>

      <section className="section">
        <h4>
          <span className="step-num">4</span>
          Run options
        </h4>
        <div className="row" style={{ gap: 16, flexWrap: "wrap", alignItems: "flex-end" }}>
          <label className="field" style={{ margin: 0 }}>
            <span>Row limit (optional)</span>
            <input
              type="number"
              min={1}
              className="srse-input"
              style={{ width: 120 }}
              placeholder="No limit"
              value={resultLimit ?? ""}
              onChange={(e) => {
                const raw = e.target.value.trim();
                patchExtract({ resultLimit: raw === "" ? null : Math.max(1, Number.parseInt(raw, 10) || 1) });
              }}
            />
          </label>
          <label className="checkbox-row" style={{ margin: 0 }}>
            <input
              type="checkbox"
              checked={countOnly}
              onChange={(e) => patchExtract({ countOnly: e.target.checked })}
            />
            Count records only (no row preview)
          </label>
        </div>
      </section>

      <section className="section">
        <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: 12 }}>
          <div style={{ flex: "1 1 280px" }}>
            <h4 style={{ margin: "0 0 8px" }}>{t("lblQueryPreview")}</h4>
            <div className="query-box">{previewText}</div>
          </div>
          <div className="row" style={{ gap: 8, alignSelf: "flex-end" }}>
            <button
              type="button"
              className="btn secondary"
              disabled={!requestReady || sqlPreviewLoading || matchStatus === "loading"}
              onClick={() => void previewSql()}
            >
              {sqlPreviewLoading ? t("btnPlanning") : t("btnPreviewSql")}
            </button>
            <button
              type="button"
              className="btn"
              disabled={!requestReady || matchStatus === "loading"}
              title={!requestReady ? t("msgCompleteQueryBeforeRun") : undefined}
              onClick={() => void runExtract()}
            >
              {matchStatus === "loading" ? t("btnRunning") : t("btnRunQuery")}
            </button>
            {onGoReport && matchStatus === "ok" && matchColumns.length > 0 && (
              <button
                type="button"
                className="btn secondary"
                aria-label="Go to Report Analysis"
                onClick={onGoReport}
              >
                {t("btnGoReport")}
              </button>
            )}
          </div>
        </div>
        {sqlPreviewError && <p className="text-danger">{sqlPreviewError}</p>}
        {matchError && <p className="text-danger">{matchError}</p>}
      </section>

      {unparseableTotals.length > 0 && (
        <div className="alert-warn" role="alert">
          <strong>Some values could not be added to these totals.</strong>
          <p style={{ margin: "0.4rem 0 0.2rem" }}>
            The column holds text, so each value was converted to a number before adding.
            Values that are not numbers were skipped — the totals below are of everything else.
          </p>
          <ul style={{ margin: "0.3rem 0 0", paddingLeft: "1.1rem" }}>
            {unparseableTotals.map((u) => (
              <li key={u.column}>
                <code>{u.forColumn}</code> — {u.total.toLocaleString()} value
                {u.total === 1 ? "" : "s"} skipped
              </li>
            ))}
          </ul>
        </div>
      )}

      {matchColumns.length > 0 && (
        <div className="section" style={{ marginTop: "1.5rem" }}>
          <AnalysisResultsGrid
            appearance="prototype"
            columns={matchColumns}
            rows={matchRows}
            sql={matchSql}
            streaming={matchStatus === "loading"}
            totalRows={matchTotalRows}
            totalRowsIsPartial={matchCountIsPartial}
            tooManyToDisplay={matchTooManyToDisplay}
            displayLimit={MAX_DISPLAYED_ROWS}
            onDownloadFullCsv={() => downloadFullExport("csv")}
            onDownloadFullExport={downloadFullExport}
            highlightDuplicates={false}
            dedupAvailable={false}
            dedupEnabled={false}
            onDedupToggle={() => {}}
          />
        </div>
      )}
    </div>
  );
}
