"use client";

import { shouldStopMatchStream } from "@/lib/matchStreamLimit";
import { presentQueryError } from "@/lib/queryErrorText";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { useShell } from "@/components/shell/ShellProviders";
import { useQueryResultsStore } from "@/lib/queryResultsStore";
import {
  downloadMultiTargetMatchCsv,
  downloadRecordMatchExport,
  type MatchExportFormat,
  fetchAnalysisLimits,
  fetchComparisonSummary,
  fetchMatchSql,
  listAnalysisCatalogs,
  listAnalysisLayers,
  listAnalysisSourceSystems,
  listAnalysisTableGroups,
  listAnalysisColumns,
  listAnalysisSchemas,
  listAnalysisTables,
  listColumnMetadata,
  qualifiedTableName,
  runMultiTargetMatchStream,
  runRecordMatchStream,
  suggestJoinKeys,
  type JoinKeySuggestion,
  type ColumnMetadata,
  type GroupMode,
  type MatchCriterion,
  type CompareAs,
  type ComparisonSummaryResponse,
  type HubSide,
  type JoinType,
  type AggregateFunction,
  type MatchProgressEvent,
  type MultiTargetRecordMatchRequest,
  type RecordMatchRequest,
  type RegisteredColumn,
  type TableRef,
} from "@/lib/analysisApi";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";
import { ComparisonPairsEditor } from "@/components/ComparisonPairsEditor";
import { ReportSourceFilters } from "@/components/query-builder/ReportSourceFilters";
import { MultiTargetJoinCanvas, createInitialJoinCanvas } from "@/components/MultiTargetJoinCanvas";
import { MetadataColumnStatus } from "@/components/query-builder/MetadataColumnStatus";
import {
  buildMatchGroup,
  isCriterionRowFilled,
  isDisplayRowFilled,
  isNameColumn,
  buildComparisonGroups,
  type ComparisonPairRow,
  pairIsFuzzy,
  rowColumns,
  rowFolds,
} from "@/lib/analysisCriterionModel";
import {
  joinCanvasFromForm,
  buildMultiTargetRequestFromCanvas,
  canvasTargetNodes,
} from "@/lib/joinCanvasModel";
import {
  buildMultiTargetRecordMatchRequest,
  singleTargetMatchRequestFromMulti,
} from "@/lib/multiTargetMatchBuild";
import {
  createSavedQuery,
  getSavedQuery,
  listSavedQueries,
  type SavedQuerySummary,
} from "@/lib/savedQueryApi";
import {
  dualModeFromRequest,
  hydrateReportStateFromSavedRequest,
} from "@/lib/savedQueryHydrate";
import { queueSavedQueryInfo } from "@/components/query-builder/QueryBuilderSavedQueryOpen";
import { useQueryBuilderPipeline } from "@/lib/queryBuilderPipelineStore";
import type { ReportConfig } from "@/lib/queryBuilderPipelineTypes";
import {
  buildMergedRequest,
  preserveExtractDisplayColumnsOnSave,
  rowCountFromMatchRow,
  withoutCountOnly,
} from "@/lib/queryBuilderRequestBuild";
import { requestsEqual } from "@/lib/queryBuilderPipelineTypes";
import { initTargetProgress, mergeTargetProgress, phaseLabel } from "@/lib/multiTargetProgress";
import LakehouseCascade, {
  EMPTY_CASCADE,
  isCascadeComplete,
  type CascadeFetchers,
  type CascadeValue,
} from "@/components/LakehouseCascade";

const fieldLabelStyle = { display: "block", marginBottom: "0.3rem", fontSize: "0.82rem" } as const;

/**
 * The officer's cascade is backed by the REGISTRY, not the live cluster —
 * every level offers only what an admin registered on the Admin page. (The
 * Admin page passes live-browse fetchers to this same component; see
 * LakehouseCascade's javadoc.)
 */
const JOIN_TYPE_OPTIONS: {
  value: JoinType;
  labelKey: "qbJoinOnlyMatching" | "qbJoinAllSource" | "qbJoinAllTarget" | "qbJoinAllBoth";
  hint: string;
}[] = [
  { value: "INNER", labelKey: "qbJoinOnlyMatching", hint: "INNER" },
  { value: "LEFT", labelKey: "qbJoinAllSource", hint: "LEFT" },
  { value: "RIGHT", labelKey: "qbJoinAllTarget", hint: "RIGHT" },
  { value: "FULL", labelKey: "qbJoinAllBoth", hint: "FULL" },
];

const REGISTRY_FETCHERS: CascadeFetchers = {
  listSourceSystems: listAnalysisSourceSystems,
  listTableGroups: listAnalysisTableGroups,
  listLayers: listAnalysisLayers,
  listCatalogs: listAnalysisCatalogs,
  listSchemas: listAnalysisSchemas,
  listTables: listAnalysisTables,
};

// A picked table is now a three-part address, not a name: with the Silver and
// Gold layers both registered, the same table name exists under more than one
// catalog, so every comparison and metadata lookup keys on all three parts.
type CriterionRow = {
  id: string;
  ref: CascadeValue;
  column: string;
  columns: RegisteredColumn[];
  fuzzyThresholdPercent: number;
  fuzzyIgnoreSpaces: boolean;
  fuzzyCaseSensitive: boolean;
  // Columns folded in alongside `column` — a target's first_name + last_name
  // against the hub's one full_name. Empty for every row until the officer
  // clicks "+ add column", which is what keeps the request shape unchanged for
  // everyone who does not need this.
  extraColumns: string[];
  // How this row's side folds. Only read from the SOURCE row of a pair, which
  // already owns the pair's fuzzy threshold.
  mode: GroupMode;
  separator: string;
};

type DisplayRow = {
  id: string;
  ref: CascadeValue;
  column: string;
  columns: RegisteredColumn[];
};

function createEmptyRow(): CriterionRow {
  return {
    id: crypto.randomUUID(),
    ref: EMPTY_CASCADE,
    column: "",
    columns: [],
    fuzzyThresholdPercent: 80,
    fuzzyIgnoreSpaces: false,
    fuzzyCaseSensitive: false,
    extraColumns: [],
    mode: "COMBINE",
    separator: " ",
  };
}

function createEmptyDisplayRow(defaultRef: CascadeValue): DisplayRow {
  return {
    id: crypto.randomUUID(),
    ref: isCascadeComplete(defaultRef) ? { ...defaultRef } : EMPTY_CASCADE,
    column: "",
    columns: [],
  };
}

type TargetBlock = {
  id: string;
  label: string;
  joinRows: CriterionRow[];
  displayRows: DisplayRow[];
  joinType: JoinType;
  /** Post-join comparisons against THIS target's columns (hub is the other side). */
  comparisonPairs: ComparisonPairRow[];
};

function createTargetBlock(defaultLabel: string): TargetBlock {
  return {
    id: crypto.randomUUID(),
    label: defaultLabel,
    joinRows: [createEmptyRow()],
    displayRows: [],
    joinType: "INNER",
    comparisonPairs: [],
  };
}

function metadataKey(ref: TableRef, column: string): string {
  return `${qualifiedTableName(ref)}.${column}`;
}

function isRowFilled(row: CriterionRow): boolean {
  return isCriterionRowFilled(row);
}

// Same auto-detect-by-name-substring pattern as fuzzy-on-"*name*" — no manual
// column picker, checked in priority order against the Target table's columns.
const LAST_UPDATED_HINTS = ["updated", "refresh", "modified", "date"];

function detectLastUpdatedColumn(columns: RegisteredColumn[]): string | null {
  for (const hint of LAST_UPDATED_HINTS) {
    const match = columns.find((c) => c.name.toLowerCase().includes(hint));
    if (match) return match.name;
  }
  return null;
}

/** Query Builder embed uses the same dedup column pick as {@link buildMergedRequest}. */
function embeddedPipelineDedupColumn(extract: {
  displayCols: string[];
  joinKeySource: string;
}): string | null {
  if (extract.displayCols.includes("last_refreshed_at")) {
    return "last_refreshed_at";
  }
  if (extract.joinKeySource) {
    return extract.joinKeySource;
  }
  return null;
}

/**
 * Above this the grid is not rendered at all and the result is offered as a
 * CSV download instead. The rows already buffered are dropped at that point,
 * so the tab's memory does not grow with a result it has decided not to show
 * — and the CSV is streamed fresh from the backend, never rebuilt from what
 * the screen happens to be holding, so it is the COMPLETE result.
 */
const MAX_DISPLAYED_ROWS = 10000;

type CountOnlyResult =
  | { kind: "single"; rows: number }
  | { kind: "multi"; targets: { label: string; rows: number }[] };

function fillCatalog(template: string, values: Record<string, string | number>): string {
  return template.replaceAll(/\{(\w+)\}/g, (_match, name: string) => {
    const value = values[name];
    return value === undefined ? "" : String(value);
  });
}

function updateRowById(rows: CriterionRow[], id: string, patch: Partial<CriterionRow>): CriterionRow[] {
  return rows.map((r) => (r.id === id ? { ...r, ...patch } : r));
}

function removeRowById(rows: CriterionRow[], id: string): CriterionRow[] {
  return rows.length <= 1 ? rows : rows.filter((r) => r.id !== id);
}

/**
 * Display-only mirror of the backend's SqlTypeFamily. Used to warn an officer
 * that the two sides of a pair are stored differently — the query itself is
 * built by TypeCoercion on the server, which is the authority. Anything this
 * cannot classify simply produces no hint rather than a wrong one.
 */
function typeFamilyOf(dataType: string | undefined): "text" | "number" | "other" {
  if (!dataType) return "other";
  const base = dataType.trim().toLowerCase().split(/[( ]/)[0];
  if (["varchar", "char", "character", "string"].includes(base)) return "text";
  if (
    ["bigint", "integer", "int", "smallint", "tinyint", "double", "real", "float", "decimal", "numeric"]
      .includes(base)
  ) {
    return "number";
  }
  return "other";
}

function dataTypeOf(row: CriterionRow): string | undefined {
  return row.columns.find((c) => c.name === row.column)?.dataType;
}

/**
 * "These two are stored differently, and here is how they will be compared" —
 * shown only for a text-vs-number pair, which is the one SRSE actually casts
 * and the one that used to fail the query outright.
 */
function mixedTypeHint(
  row: CriterionRow,
  paired: CriterionRow | undefined,
  compareAsFor: (ref: TableRef, column: string) => CompareAs,
): string | null {
  if (!paired || !row.column || !paired.column) return null;
  const own = typeFamilyOf(dataTypeOf(row));
  const other = typeFamilyOf(dataTypeOf(paired));
  if (own === "other" || other === "other" || own === other) return null;

  const override =
    compareAsFor(row.ref, row.column) !== "AUTO"
      ? compareAsFor(row.ref, row.column)
      : compareAsFor(paired.ref, paired.column);
  const mode = override === "TEXT" ? "text" : "numbers";
  return `Types differ (${dataTypeOf(row)} vs ${dataTypeOf(paired)}) — compared as ${mode}.`;
}

function qbClass(proto: boolean | undefined, legacy: string, modern: string): string {
  return proto ? modern : legacy;
}

type CriterionBoxProps = Readonly<{
  title: string;
  boxId: string;
  prototypeUi?: boolean;
  rows: CriterionRow[];
  showFuzzy: boolean;
  pairedRows?: CriterionRow[];
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null;
  businessNameFor: (ref: TableRef, column: string) => string | null;
  compareAsFor: (ref: TableRef, column: string) => CompareAs;
  onTableChange: (rowId: string, ref: CascadeValue) => void;
  onColumnChange: (rowId: string, column: string) => void;
  onFuzzyChange: (rowId: string, value: number) => void;
  onFuzzyOptionsChange: (rowId: string, patch: { fuzzyIgnoreSpaces?: boolean; fuzzyCaseSensitive?: boolean }) => void;
  onExtraColumnsChange: (rowId: string, extraColumns: string[]) => void;
  onModeChange: (rowId: string, mode: GroupMode) => void;
  onSeparatorChange: (rowId: string, separator: string) => void;
  onRemove: (rowId: string) => void;
  onAdd: () => void;
  onError: (message: string) => void;
}>;

function rowShowsFuzzy(
  row: CriterionRow,
  index: number,
  pairedRows: CriterionRow[] | undefined,
  registeredFuzzyFor: (ref: TableRef, column: string) => boolean | null,
): boolean {
  return pairIsFuzzy(row, pairedRows?.[index], registeredFuzzyFor);
}

/**
 * The fold controls for one pair. Shown only once a side actually holds more
 * than one column, so the box looks exactly as it did for anyone matching one
 * column against one column.
 */
function GroupControls({
  row,
  boxId,
  anyOfCount,
  onModeChange,
  onSeparatorChange,
}: Readonly<{
  row: CriterionRow;
  boxId: string;
  anyOfCount: number;
  onModeChange: (rowId: string, mode: GroupMode) => void;
  onSeparatorChange: (rowId: string, separator: string) => void;
}>) {
  return (
    <div style={{ flex: "1 1 100%", display: "flex", gap: "0.6rem", alignItems: "flex-end", flexWrap: "wrap" }}>
      <div style={{ flex: "0 1 180px" }}>
        <label htmlFor={`${boxId}-mode-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
          Compare as
        </label>
        <select
          id={`${boxId}-mode-${row.id}`}
          className="srse-select"
          style={{ width: "100%" }}
          value={row.mode}
          onChange={(e) => onModeChange(row.id, e.target.value as GroupMode)}
        >
          <option value="COMBINE">Combined into one value</option>
          <option value="ANY_OF">Any one of them</option>
        </select>
      </div>
      {row.mode === "COMBINE" && (
        <div style={{ flex: "0 1 110px" }}>
          <label htmlFor={`${boxId}-sep-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
            Joined by
          </label>
          <input
            id={`${boxId}-sep-${row.id}`}
            type="text"
            className="srse-input"
            style={{ width: "100%" }}
            value={row.separator}
            onChange={(e) => onSeparatorChange(row.id, e.target.value)}
          />
        </div>
      )}
      <p className="srse-text-muted" style={{ fontSize: "0.7rem", margin: 0, flex: "1 1 220px" }}>
        {row.mode === "COMBINE"
          ? "Joined in the order listed — order matters for fuzzy scoring. Empty values are skipped."
          : "Matches if the other side equals any one of these columns; the result says which one."}
      </p>
      {row.mode === "ANY_OF" && anyOfCount > 1 && (
        <p style={{ fontSize: "0.7rem", margin: 0, flex: "1 1 100%", color: "var(--srse-warning)" }}>
          More than one &quot;any one of&quot; group on the same side multiplies that side&apos;s rows
          before the match runs, and can be slow on large tables.
        </p>
      )}
    </div>
  );
}

type DisplayColumnBoxProps = Readonly<{
  title: string;
  boxId: string;
  rows: DisplayRow[];
  businessNameFor: (ref: TableRef, column: string) => string | null;
  onTableChange: (rowId: string, ref: CascadeValue) => void;
  onColumnChange: (rowId: string, column: string) => void;
  onRemove: (rowId: string) => void;
  onAdd: () => void;
  onError: (message: string) => void;
}>;

function DisplayColumnBox({
  title,
  boxId,
  rows,
  businessNameFor,
  onTableChange,
  onColumnChange,
  onRemove,
  onAdd,
  onError,
}: DisplayColumnBoxProps) {
  return (
    <div style={{ marginTop: "0.75rem" }}>
      <h3 className="srse-text-muted" style={{ fontSize: "0.85rem", margin: "0 0 0.35rem" }}>
        {title}
      </h3>
      {rows.map((row, index) => (
        <div
          key={row.id}
          style={{
            display: "flex",
            gap: "0.6rem",
            alignItems: "flex-end",
            flexWrap: "wrap",
            marginBottom: "0.5rem",
            paddingBottom: "0.5rem",
            borderBottom: index === rows.length - 1 ? "none" : "1px solid var(--srse-border)",
          }}
        >
          <div style={{ flex: "1 1 100%" }}>
            <LakehouseCascade
              value={row.ref}
              onChange={(ref) => onTableChange(row.id, ref)}
              fetchers={REGISTRY_FETCHERS}
              idPrefix={`${boxId}-${row.id}`}
              onError={onError}
            />
          </div>
          <div style={{ flex: "1 1 150px" }}>
            <label htmlFor={`${boxId}-column-${row.id}`} className="srse-text-muted" style={fieldLabelStyle}>
              Column
            </label>
            <select
              id={`${boxId}-column-${row.id}`}
              className="srse-select"
              style={{ width: "100%" }}
              value={row.column}
              onChange={(e) => onColumnChange(row.id, e.target.value)}
              disabled={!isCascadeComplete(row.ref)}
            >
              <option value="">— select —</option>
              {row.columns.map((c) => (
                <option key={c.name} value={c.name}>
                  {businessNameFor(row.ref, c.name) ?? c.name}
                </option>
              ))}
            </select>
          </div>
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            onClick={() => onRemove(row.id)}
            title="Remove this row"
          >
            ✕
          </button>
        </div>
      ))}
      <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={onAdd}>
        + Add column to show
      </button>
    </div>
  );
}

function CriterionBox({
  title,
  boxId,
  rows,
  showFuzzy,
  pairedRows,
  registeredFuzzyFor,
  businessNameFor,
  compareAsFor,
  onTableChange,
  onColumnChange,
  onFuzzyChange,
  onFuzzyOptionsChange,
  onExtraColumnsChange,
  onModeChange,
  onSeparatorChange,
  onRemove,
  onAdd,
  onError,
  displaySection,
  prototypeUi = false,
}: CriterionBoxProps & { displaySection?: React.ReactNode }) {
  const muted = qbClass(prototypeUi, "srse-text-muted", "text-muted");
  const btnSm = qbClass(prototypeUi, "srse-btn srse-btn-ghost srse-btn-sm", "btn secondary sm");
  const rowBorder = prototypeUi ? "var(--border)" : "var(--srse-border)";
  return (
    <section className={qbClass(prototypeUi, "srse-card", "pick")} style={{ flex: "1 1 380px" }}>
      <div className={prototypeUi ? "ttl" : "srse-card-title"}>{title}</div>
      <p className={muted} style={{ fontSize: "0.82rem", marginTop: 0 }}>
        Catalog › Schema › Table › Column — registered lakehouse tables only
      </p>

      {rows.map((row, index) => (
        <div
          key={row.id}
          style={{
            display: "flex",
            gap: "0.6rem",
            alignItems: "flex-end",
            flexWrap: "wrap",
            marginBottom: "0.6rem",
            paddingBottom: "0.6rem",
            borderBottom: index === rows.length - 1 ? "none" : `1px solid ${rowBorder}`,
          }}
        >
          <div style={{ flex: "1 1 100%" }}>
            <LakehouseCascade
              value={row.ref}
              onChange={(ref) => onTableChange(row.id, ref)}
              fetchers={REGISTRY_FETCHERS}
              idPrefix={`${boxId}-${row.id}`}
              onError={onError}
              appearance={prototypeUi ? "prototype" : "legacy"}
            />
          </div>
          <div style={{ flex: "1 1 150px" }}>
            <label htmlFor={`${boxId}-column-${row.id}`} className={muted} style={fieldLabelStyle}>
              Column
            </label>
            <select
              id={`${boxId}-column-${row.id}`}
              className={prototypeUi ? undefined : "srse-select"}
              style={{ width: "100%" }}
              value={row.column}
              onChange={(e) => onColumnChange(row.id, e.target.value)}
              disabled={!isCascadeComplete(row.ref)}
            >
              <option value="">— select —</option>
              {row.columns.map((c) => (
                <option key={c.name} value={c.name}>
                  {businessNameFor(row.ref, c.name) ?? c.name}
                </option>
              ))}
            </select>
            {dataTypeOf(row) && (
              <div className={muted} style={{ fontSize: "0.7rem", marginTop: "0.2rem" }}>
                {dataTypeOf(row)}
              </div>
            )}
            {(() => {
              const hint = mixedTypeHint(row, pairedRows?.[index], compareAsFor);
              return hint ? (
                <div
                  style={{ fontSize: "0.7rem", marginTop: "0.15rem", color: prototypeUi ? "var(--warn)" : "var(--srse-warning)" }}
                  title="SRSE casts the pair so the match can run at all. Set 'Compare as' on the Admin page to force the other reading."
                >
                  {hint}
                </div>
              ) : null;
            })()}
          </div>
          {showFuzzy && rowShowsFuzzy(row, index, pairedRows, registeredFuzzyFor) && (
            <div style={{ flex: "0 1 100px" }}>
              <label htmlFor={`${boxId}-fuzzy-${row.id}`} className={muted} style={fieldLabelStyle}>
                Fuzzy %
              </label>
              <input
                id={`${boxId}-fuzzy-${row.id}`}
                type="number"
                className={prototypeUi ? undefined : "srse-input"}
                style={{ width: "100%" }}
                min={0}
                max={100}
                value={row.fuzzyThresholdPercent}
                onChange={(e) => onFuzzyChange(row.id, Number(e.target.value))}
              />
              <label style={{ fontSize: "0.72rem", display: "block", marginTop: "0.25rem" }}>
                <input
                  type="checkbox"
                  checked={row.fuzzyIgnoreSpaces}
                  onChange={(e) => onFuzzyOptionsChange(row.id, { fuzzyIgnoreSpaces: e.target.checked })}
                />{" "}
                Ignore spaces
              </label>
              <label style={{ fontSize: "0.72rem", display: "block" }}>
                <input
                  type="checkbox"
                  checked={row.fuzzyCaseSensitive}
                  onChange={(e) => onFuzzyOptionsChange(row.id, { fuzzyCaseSensitive: e.target.checked })}
                />{" "}
                Case sensitive
              </label>
            </div>
          )}
          {rows.length > 1 && (
            <button
              type="button"
              className={btnSm}
              onClick={() => onRemove(row.id)}
              title="Remove this row"
            >
              ✕
            </button>
          )}

          {/* Folded columns: one full_name here against first_name + last_name there. */}
          {row.extraColumns.map((extra, extraIndex) => (
            <div key={`${row.id}-extra-${extraIndex}`} style={{ flex: "1 1 100%", display: "flex", gap: "0.4rem", alignItems: "flex-end" }}>
              <div style={{ flex: "1 1 150px" }}>
                <label
                  htmlFor={`${boxId}-extra-${row.id}-${extraIndex}`}
                  className={muted}
                  style={fieldLabelStyle}
                >
                  …and column {extraIndex + 2}
                </label>
                <select
                  id={`${boxId}-extra-${row.id}-${extraIndex}`}
                  className={prototypeUi ? undefined : "srse-select"}
                  style={{ width: "100%" }}
                  value={extra}
                  onChange={(e) =>
                    onExtraColumnsChange(
                      row.id,
                      row.extraColumns.map((c, k) => (k === extraIndex ? e.target.value : c)),
                    )
                  }
                >
                  <option value="">— select —</option>
                  {row.columns.map((c) => (
                    <option key={c.name} value={c.name}>
                      {businessNameFor(row.ref, c.name) ?? c.name}
                    </option>
                  ))}
                </select>
              </div>
              <button
                type="button"
                className={btnSm}
                onClick={() =>
                  onExtraColumnsChange(row.id, row.extraColumns.filter((_, k) => k !== extraIndex))
                }
                title="Remove this column from the group"
              >
                ✕
              </button>
            </div>
          ))}

          {isCascadeComplete(row.ref) && Boolean(row.column) && (
            <button
              type="button"
              className={btnSm}
              style={{ flex: "0 0 auto" }}
              onClick={() => onExtraColumnsChange(row.id, [...row.extraColumns, ""])}
              title="Match this against more than one column on this side"
            >
              + add column
            </button>
          )}

          {showFuzzy && (rowFolds(row) || (pairedRows?.[index] ? rowFolds(pairedRows[index]) : false)) && (
            <GroupControls
              row={row}
              boxId={boxId}
              anyOfCount={rows.filter((r, i) =>
                r.mode === "ANY_OF"
                && (rowFolds(r) || (pairedRows?.[i] ? rowFolds(pairedRows[i]) : false))).length}
              onModeChange={onModeChange}
              onSeparatorChange={onSeparatorChange}
            />
          )}
        </div>
      ))}

      <button type="button" className={btnSm} onClick={onAdd}>
        + Add more
      </button>
      {displaySection}
    </section>
  );
}

type ReportAnalysisTabProps = {
  embedded?: boolean;
  onRunComplete?: () => void;
  onGoExtract?: () => void;
  onSavedQueryLoaded?: () => void;
};

export default function ReportAnalysisTab({
  embedded = false,
  onRunComplete,
  onGoExtract,
  onSavedQueryLoaded,
}: Readonly<ReportAnalysisTabProps>) {
  const router = useRouter();
  const { t } = useShell();
  const setLastResult = useQueryResultsStore((s) => s.setLastResult);
  const [controlsPanelOpen, setControlsPanelOpen] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [savedQueries, setSavedQueries] = useState<SavedQuerySummary[]>([]);
  const [saveQueryName, setSaveQueryName] = useState("");
  const [openSavedId, setOpenSavedId] = useState("");
  const [savedQueryNotice, setSavedQueryNotice] = useState<{
    tone: "saved" | "info" | "error";
    text: string;
  } | null>(null);
  const [columnMetadata, setColumnMetadata] = useState<Map<string, ColumnMetadata>>(new Map());

  const [highlightDuplicates, setHighlightDuplicates] = useState(false);

  const [sourceRows, setSourceRows] = useState<CriterionRow[]>([createEmptyRow()]);
  const [targetRows, setTargetRows] = useState<CriterionRow[]>([createEmptyRow()]);
  const [sourceDisplayRows, setSourceDisplayRows] = useState<DisplayRow[]>([]);
  const [targetDisplayRows, setTargetDisplayRows] = useState<DisplayRow[]>([]);
  const [comparisonPairs, setComparisonPairs] = useState<ComparisonPairRow[]>([]);
  const [mismatchOnly, setMismatchOnly] = useState(false);
  const [comparisonSummary, setComparisonSummary] = useState<ComparisonSummaryResponse | null>(null);
  const [comparisonSummaryError, setComparisonSummaryError] = useState<string | null>(null);
  const comparisonPrefilledKeysRef = useRef(new Set<string>());

  const [multiMatchMode, setMultiMatchMode] = useState(false);
  const [hubSide, setHubSide] = useState<HubSide>("SOURCE");
  const [targetBlocks, setTargetBlocks] = useState<TargetBlock[]>([createTargetBlock("Target 1")]);
  const [maxTargetSets, setMaxTargetSets] = useState(5);
  const [targetSetFilter, setTargetSetFilter] = useState<string>("All");
  const [matchProgress, setMatchProgress] = useState<MatchProgressEvent[]>([]);
  const [multiUiMode, setMultiUiMode] = useState<"form" | "canvas">("form");
  const [joinCanvas, setJoinCanvas] = useState(createInitialJoinCanvas);
  const [targetRunStatus, setTargetRunStatus] = useState<ReturnType<typeof initTargetProgress>>([]);
  const pipelineReport = useQueryBuilderPipeline((s) => s.report);
  const patchPipelineReport = useQueryBuilderPipeline((s) => s.patchReport);
  const commitReportSuccess = useQueryBuilderPipeline((s) => s.commitReportSuccess);
  const pipelineDualMode = useQueryBuilderPipeline((s) => s.dualMode);
  const pipelineExtract = useQueryBuilderPipeline((s) => s.extract);
  const [maxAggregates, setMaxAggregates] = useState(4);

  const [joinType, setJoinType] = useState<JoinType>("INNER");
  const [keySuggestions, setKeySuggestions] = useState<JoinKeySuggestion[]>([]);
  const [keySuggestLoading, setKeySuggestLoading] = useState(false);
  const [keySuggestError, setKeySuggestError] = useState<string | null>(null);
  const [sqlPreview, setSqlPreview] = useState<string | null>(null);
  const [sqlPreviewError, setSqlPreviewError] = useState<string | null>(null);
  const [sqlPreviewLoading, setSqlPreviewLoading] = useState(false);

  const [standaloneCountOnly, setStandaloneCountOnly] = useState(false);
  const [countOnlyResult, setCountOnlyResult] = useState<CountOnlyResult | null>(null);
  const [dedupEnabled, setDedupEnabled] = useState(false);
  const multiDedupBlockedByJoin =
    multiMatchMode && targetBlocks.some((b) => b.joinType === "RIGHT" || b.joinType === "FULL");
  const dedupBlockedByJoin =
    multiDedupBlockedByJoin || (!multiMatchMode && (joinType === "RIGHT" || joinType === "FULL"));

  const dedupActive = dedupEnabled && !dedupBlockedByJoin;
  const countOnlyBlocked = Boolean(pipelineReport.groupEnabled && pipelineReport.groupByCol);
  const countOnly = embedded ? pipelineReport.countOnly : standaloneCountOnly;
  const countOnlyActive = countOnly && !countOnlyBlocked;

  useEffect(() => {
    listSavedQueries()
      .then(setSavedQueries)
      .catch(() => setSavedQueries([]));
  }, []);

  const dedupColumn =
    embedded && pipelineDualMode && !multiMatchMode
      ? embeddedPipelineDedupColumn(pipelineExtract)
      : detectLastUpdatedColumn(
          (multiMatchMode ? sourceRows[0] : targetRows[0])?.columns ?? [],
        );

  const [matchStatus, setMatchStatus] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [matchError, setMatchError] = useState<string | null>(null);
  const [matchColumns, setMatchColumns] = useState<string[]>([]);
  const [matchRows, setMatchRows] = useState<Record<string, unknown>[]>([]);
  const [matchSql, setMatchSql] = useState("");
  // The backend deliberately no longer caps the match result (see
  // RecordMatchService's javadoc) — but a browser tab still cannot hold or
  // recompute over an unbounded row array without crashing (confirmed live:
  // an OOM tab crash on a match with far more matches than the grid/charts
  // could safely render). These are a display-side safety limit, not a
  // reintroduction of that backend guardrail: the true total is still
  // counted and shown even when not every row is rendered.
  const [matchTotalRows, setMatchTotalRows] = useState<number | null>(null);
  // Set the moment the result outgrows MAX_DISPLAYED_ROWS: the grid keeps the
  // first page-able preview and offers server exports for the complete result.
  const [matchTooManyToDisplay, setMatchTooManyToDisplay] = useState(false);
  // True when we stopped reading before the stream finished naturally, so
  // matchTotalRows (if set at all) is a lower bound, not an exact count.
  const [matchCountIsPartial, setMatchCountIsPartial] = useState(false);
  // True for the whole streaming lifecycle (first byte to last), not just the
  // initial network round trip — drives the grid's "(loading more…)" caption
  // and disables CSV/column-visibility until the result is actually complete.
  const matchStreaming = matchStatus === "loading";

  // The request the displayed result actually came from. The CSV download must
  // use THIS, not a freshly built one: the officer may have edited the criteria
  // since running the match, and a file that quietly answers a different
  // question than the count on screen is worse than no file.
  const lastRunRequestRef = useRef<RecordMatchRequest | null>(null);
  const lastRunMultiRequestRef = useRef<MultiTargetRecordMatchRequest | null>(null);

  useEffect(() => {
    if (embedded) {
      return;
    }
    if (matchStatus !== "ok" || matchColumns.length === 0 || matchRows.length === 0) {
      return;
    }
    setLastResult({
      columns: matchColumns,
      rows: matchRows,
      totalRows: matchTotalRows,
      source: "report",
      matchExportRequest: lastRunRequestRef.current,
    });
    onRunComplete?.();
  }, [embedded, matchStatus, matchColumns, matchRows, matchTotalRows, setLastResult, onRunComplete]);
  const pendingRowsRef = useRef<Record<string, unknown>[]>([]);
  const flushIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const rowsSeenRef = useRef(0);
  const streamColumnsRef = useRef<string[]>([]);
  const carriedRowsRef = useRef<Record<string, unknown>[]>([]);
  const lastStreamSqlRef = useRef("");
  const lastStreamTotalRowsRef = useRef<number | null>(null);
  const embeddedHydratedRef = useRef(false);
  const lastAppliedPipelineRequest = useQueryBuilderPipeline((s) => s.lastAppliedRequest);

  useEffect(() => {
    // The table list is no longer fetched here — each CriterionBox's cascade
    // loads its own levels from the registry on demand.
    listColumnMetadata()
      .then((entries) => setColumnMetadata(new Map(entries.map((e) => [metadataKey(e, e.column), e]))))
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
    fetchAnalysisLimits()
      .then((limits) => {
        setMaxTargetSets(limits.maxTargetSets);
        setMaxAggregates(limits.maxAggregates);
      })
      .catch(() => {
        /* keep default */
      });
  }, []);

  function syncComparisonPairsToPipeline(next: ComparisonPairRow[]) {
    if (!embedded) {
      return;
    }
    patchPipelineReport({
      comparisonPairs: next.map((p) => ({
        sourceColumn: p.sourceColumn,
        targetColumn: p.targetColumn,
        fuzzyThresholdPercent: p.fuzzyThresholdPercent,
      })),
    });
  }

  function embeddedReportPreview(withDedup: boolean): ReportConfig {
    const { report } = useQueryBuilderPipeline.getState();
    return {
      ...report,
      mismatchOnly,
      highlightDuplicates,
      dedupEnabled: withDedup && dedupEnabled,
      joinType,
      comparisonPairs: comparisonPairs.map((p) => ({
        sourceColumn: p.sourceColumn,
        targetColumn: p.targetColumn,
        fuzzyThresholdPercent: p.fuzzyThresholdPercent,
      })),
      groupEnabled: pipelineReport.groupEnabled,
      groupByCol: pipelineReport.groupByCol,
      aggregateFn: pipelineReport.aggregateFn,
      aggregateCol: pipelineReport.aggregateCol,
      countDistinct: pipelineReport.countDistinct,
      countOnly: countOnlyActive,
      joinFuzzyThresholdPercent:
        sourceRows.find(isRowFilled)?.fuzzyThresholdPercent ?? pipelineReport.joinFuzzyThresholdPercent,
      joinFuzzyIgnoreSpaces: pipelineReport.joinFuzzyIgnoreSpaces,
      joinFuzzyCaseSensitive: pipelineReport.joinFuzzyCaseSensitive,
    };
  }

  function pushEmbeddedReportToPipeline() {
    if (!embedded) {
      return;
    }
    patchPipelineReport({
      mismatchOnly,
      highlightDuplicates,
      dedupEnabled,
      joinType,
      comparisonPairs: comparisonPairs.map((p) => ({
        sourceColumn: p.sourceColumn,
        targetColumn: p.targetColumn,
        fuzzyThresholdPercent: p.fuzzyThresholdPercent,
      })),
      groupEnabled: pipelineReport.groupEnabled,
      groupByCol: pipelineReport.groupByCol,
      aggregateFn: pipelineReport.aggregateFn,
      aggregateCol: pipelineReport.aggregateCol,
      countDistinct: pipelineReport.countDistinct,
      countOnly: countOnlyActive,
      joinFuzzyThresholdPercent:
        sourceRows.find(isRowFilled)?.fuzzyThresholdPercent ?? pipelineReport.joinFuzzyThresholdPercent,
    });
  }

  useEffect(() => {
    if (!embedded || embeddedHydratedRef.current) {
      return;
    }
    embeddedHydratedRef.current = true;
    const { extract, report, extractResult, lastAppliedRequest, dualMode } =
      useQueryBuilderPipeline.getState();
    if (!extractResult) {
      return;
    }
    queueMicrotask(() => {
      setMismatchOnly(report.mismatchOnly);
      setHighlightDuplicates(report.highlightDuplicates);
      setDedupEnabled(report.dedupEnabled);
      setJoinType(report.joinType);
      setComparisonPairs(
        report.comparisonPairs.map((p) => ({
          id: crypto.randomUUID(),
          sourceColumn: p.sourceColumn,
          targetColumn: p.targetColumn,
          fuzzyThresholdPercent: p.fuzzyThresholdPercent,
        })),
      );
      setMatchColumns(extractResult.columns);
      setMatchRows(extractResult.rows);
      setMatchSql(extractResult.sql);
      setMatchTotalRows(extractResult.totalRows);
      setMatchStatus("ok");
      lastRunRequestRef.current = lastAppliedRequest;
      streamColumnsRef.current = extractResult.columns;
      carriedRowsRef.current = extractResult.rows;
      lastStreamSqlRef.current = extractResult.sql;
    });

    void (async () => {
      const srcCols = isCascadeComplete(extract.sourceRef)
        ? await listAnalysisColumns(extract.sourceRef)
        : [];
      const tgtCols =
        dualMode && isCascadeComplete(extract.targetRef)
          ? await listAnalysisColumns(extract.targetRef)
          : [];
      const srcRow = {
        ...createEmptyRow(),
        id: "embedded-source",
        ref: extract.sourceRef,
        column: extract.joinKeySource || extract.displayCols[0] || "",
        columns: srcCols,
        fuzzyThresholdPercent: report.joinFuzzyThresholdPercent,
      };
      setSourceRows([srcRow]);
      if (dualMode && isCascadeComplete(extract.targetRef)) {
        setTargetRows([
          {
            ...createEmptyRow(),
            id: "embedded-target",
            ref: extract.targetRef,
            column: extract.joinKeyTarget,
            columns: tgtCols,
            fuzzyThresholdPercent: 80,
          },
        ]);
      }
      setSourceDisplayRows(
        extract.displayCols.map((column) => ({
          id: crypto.randomUUID(),
          ref: extract.sourceRef,
          column,
          columns: srcCols,
        })),
      );
    })();
  }, [embedded]);

  const reportError = useCallback((message: string) => setLoadError(message), []);

  // Admin-registered override takes precedence, on either side of the pair
  // it's used for — same fallback order as the backend (RecordMatchService),
  // so the UI's Fuzzy % control and the actual query never disagree.
  function isFuzzyMatchable(ref: TableRef, column: string): boolean {
    const entry = columnMetadata.get(metadataKey(ref, column));
    return entry ? entry.fuzzyMatchable : isNameColumn(column);
  }

  /** The Admin setting for one column, or null when it has none — see pairIsFuzzy. */
  function registeredFuzzyFor(ref: TableRef, column: string): boolean | null {
    return columnMetadata.get(metadataKey(ref, column))?.fuzzyMatchable ?? null;
  }

  function businessNameFor(ref: TableRef, column: string): string | null {
    return columnMetadata.get(metadataKey(ref, column))?.businessName ?? null;
  }

  function compareAsFor(ref: TableRef, column: string): CompareAs {
    return columnMetadata.get(metadataKey(ref, column))?.compareAs ?? "AUTO";
  }

  async function handleTableChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    ref: CascadeValue,
  ) {
    // Clearing the column alongside the table matters more than it used to:
    // changing only the catalog can leave a column name that exists in both
    // layers, which would submit a valid-looking but wrong reference.
    setRows((rows) => updateRowById(rows, rowId, { ref, column: "", columns: [] }));
    if (!isCascadeComplete(ref)) return;
    try {
      const cols = await listAnalysisColumns(ref);
      setRows((rows) => updateRowById(rows, rowId, { columns: cols }));
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function handleColumnChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    column: string,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { column }));
  }

  const twoTableRefsReady =
    !multiMatchMode
    && isCascadeComplete(sourceRows[0]?.ref ?? EMPTY_CASCADE)
    && isCascadeComplete(targetRows[0]?.ref ?? EMPTY_CASCADE);

  async function loadKeySuggestions(probe: boolean) {
    const src = sourceRows[0].ref;
    const tgt = targetRows[0].ref;
    if (!isCascadeComplete(src) || !isCascadeComplete(tgt)) return;
    setKeySuggestLoading(true);
    setKeySuggestError(null);
    try {
      const list = await suggestJoinKeys({
        sourceCatalog: src.catalog,
        sourceSchema: src.schema,
        sourceTable: src.table,
        targetCatalog: tgt.catalog,
        targetSchema: tgt.schema,
        targetTable: tgt.table,
        probe,
      });
      setKeySuggestions(list);
    } catch (err: unknown) {
      setKeySuggestError(err instanceof Error ? err.message : String(err));
      setKeySuggestions([]);
    } finally {
      setKeySuggestLoading(false);
    }
  }

  async function attachColumnLists<T extends { ref: CascadeValue; columns: RegisteredColumn[] }>(
    rows: T[],
  ): Promise<T[]> {
    return Promise.all(
      rows.map(async (row) => {
        if (!isCascadeComplete(row.ref)) {
          return { ...row, columns: [] };
        }
        try {
          const cols = await listAnalysisColumns(row.ref);
          return { ...row, columns: cols };
        } catch {
          return { ...row, columns: [] };
        }
      }),
    );
  }

  async function openSavedQuery(id: number) {
    const detail = await getSavedQuery(id);
    if (embedded) {
      const dual = dualModeFromRequest(detail.request);
      useQueryBuilderPipeline.getState().loadFromMergedRequest(dual, detail.request);
      embeddedHydratedRef.current = false;
      const loaded = `Loaded “${detail.name}”. Review Extract, then run the query to refresh results.`;
      queueSavedQueryInfo(loaded);
      setSavedQueryNotice({ tone: "info", text: loaded });
      onSavedQueryLoaded?.();
      return;
    }
    const hydrated = hydrateReportStateFromSavedRequest(detail.request);
    comparisonPrefilledKeysRef.current.clear();
    const [sourceRows, targetRows] = await Promise.all([
      attachColumnLists(
        hydrated.sourceRows.length > 0
          ? hydrated.sourceRows.map((r) => ({ ...r, columns: r.columns ?? [] }))
          : [{ ...createEmptyRow() }],
      ),
      attachColumnLists(
        hydrated.targetRows.length > 0
          ? hydrated.targetRows.map((r) => ({ ...r, columns: r.columns ?? [] }))
          : [{ ...createEmptyRow() }],
      ),
    ]);
    setSourceRows(sourceRows);
    setTargetRows(targetRows);
    setSourceDisplayRows(
      await attachColumnLists(
        hydrated.sourceDisplayRows.map((r) => ({ ...r, columns: [] as RegisteredColumn[] })),
      ),
    );
    setTargetDisplayRows(
      await attachColumnLists(
        hydrated.targetDisplayRows.map((r) => ({ ...r, columns: [] as RegisteredColumn[] })),
      ),
    );
    setJoinType(hydrated.joinType ?? "INNER");
    setComparisonPairs(hydrated.comparisonPairs);
    setHighlightDuplicates(hydrated.highlightDuplicates);
    setDedupEnabled(hydrated.dedupEnabled);
    setMismatchOnly(hydrated.mismatchOnly);
    lastRunRequestRef.current = detail.request;
    setSavedQueryNotice({
      tone: "info",
      text: `Loaded “${detail.name}”. Run match to execute under your scope (values from save are in the request payload).`,
    });
  }

  async function applyKeySuggestion(s: JoinKeySuggestion) {
    const srcRef = sourceRows[0].ref;
    const tgtRef = targetRows[0].ref;
    const srcRowId = sourceRows[0].id;
    const tgtRowId = targetRows[0].id;
    try {
      const [srcCols, tgtCols] = await Promise.all([
        listAnalysisColumns(srcRef),
        listAnalysisColumns(tgtRef),
      ]);
      setSourceRows((rows) =>
        updateRowById(rows, srcRowId, { columns: srcCols, column: s.sourceColumn }),
      );
      setTargetRows((rows) =>
        updateRowById(rows, tgtRowId, { columns: tgtCols, column: s.targetColumn }),
      );
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function handleFuzzyChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    fuzzyThresholdPercent: number,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { fuzzyThresholdPercent }));
  }

  function handleFuzzyOptionsChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    patch: { fuzzyIgnoreSpaces?: boolean; fuzzyCaseSensitive?: boolean },
  ) {
    setRows((rows) => updateRowById(rows, rowId, patch));
  }

  function handleExtraColumnsChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    extraColumns: string[],
  ) {
    setRows((rows) => updateRowById(rows, rowId, { extraColumns }));
  }

  function handleModeChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    mode: GroupMode,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { mode }));
  }

  function handleSeparatorChange(
    setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>,
    rowId: string,
    separator: string,
  ) {
    setRows((rows) => updateRowById(rows, rowId, { separator }));
  }

  function handleRemoveRow(setRows: React.Dispatch<React.SetStateAction<CriterionRow[]>>, rowId: string) {
    setRows((rows) => removeRowById(rows, rowId));
  }

  function defaultSideRef(sideRows: CriterionRow[]): CascadeValue {
    const filled = sideRows.find(isRowFilled);
    return filled?.ref ?? sideRows[0]?.ref ?? EMPTY_CASCADE;
  }

  async function handleDisplayTableChange(
    setRows: React.Dispatch<React.SetStateAction<DisplayRow[]>>,
    rowId: string,
    ref: CascadeValue,
  ) {
    setRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, ref, column: "", columns: [] } : r)));
    if (!isCascadeComplete(ref)) return;
    try {
      const cols = await listAnalysisColumns(ref);
      setRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, columns: cols } : r)));
    } catch (err: unknown) {
      setLoadError(err instanceof Error ? err.message : String(err));
    }
  }

  function filledRefOrUndefined(rows: CriterionRow[]): TableRef | undefined {
    const ref = rows.find(isRowFilled)?.ref;
    return ref && isCascadeComplete(ref) ? ref : undefined;
  }

  function hubRefOrUndefined(): TableRef | undefined {
    return filledRefOrUndefined(sourceRows);
  }

  function buildRequest(withDedup: boolean): RecordMatchRequest | null {
    if (embedded && !multiMatchMode) {
      pushEmbeddedReportToPipeline();
      return useQueryBuilderPipeline.getState().mergedRequestForApply(withDedup);
    }
    const filledSource = sourceRows.filter(isRowFilled);
    const filledTarget = targetRows.filter(isRowFilled);
    const n = Math.min(filledSource.length, filledTarget.length);
    if (n === 0) return null;
    const dedupRef = targetRows[0]?.ref;
    const filledSourceDisplay = sourceDisplayRows.filter(isDisplayRowFilled);
    const filledTargetDisplay = targetDisplayRows.filter(isDisplayRowFilled);
    const pairs = filledSource.slice(0, n).map((r, i) => ({ source: r, target: filledTarget[i] }));
    // Groups are sent only when a row actually folds more than one column.
    // Otherwise the payload is byte-for-byte what it has always been, and so is
    // the SQL the backend builds from it.
    const usesGroups = pairs.some(({ source, target }) => rowFolds(source) || rowFolds(target));
    const req: RecordMatchRequest = {
      sourceCriteria: pairs.map(({ source, target }) => ({
        ...source.ref,
        column: source.column,
        fuzzyThresholdPercent: pairIsFuzzy(source, target, registeredFuzzyFor)
          ? source.fuzzyThresholdPercent
          : null,
      })),
      targetCriteria: pairs.map(({ target }) => ({
        ...target.ref,
        column: target.column,
        fuzzyThresholdPercent: null,
      })),
      highlightDuplicates,
      dedup:
        withDedup && dedupColumn && dedupRef && isCascadeComplete(dedupRef)
          ? { ...dedupRef, column: dedupColumn }
          : null,
    };
    if (filledSourceDisplay.length > 0) {
      req.sourceDisplayColumns = filledSourceDisplay.map((r) => ({ ...r.ref, column: r.column }));
    }
    if (filledTargetDisplay.length > 0) {
      req.targetDisplayColumns = filledTargetDisplay.map((r) => ({ ...r.ref, column: r.column }));
    }
    if (usesGroups) {
      req.joinGroups = pairs.map(({ source, target }) =>
        buildMatchGroup(source, target, pairIsFuzzy(source, target, registeredFuzzyFor)),
      );
    }
    if (joinType !== "INNER") {
      req.joinType = joinType;
    }
    const srcRef = filledSource[0].ref;
    const tgtRef = filledTarget[0].ref;
    if (isCascadeComplete(srcRef) && isCascadeComplete(tgtRef)) {
      const groups = buildComparisonGroups(srcRef, tgtRef, comparisonPairs, registeredFuzzyFor);
      if (groups.length > 0) {
        req.comparisonGroups = groups;
      }
    }
    if (mismatchOnly && req.comparisonGroups && req.comparisonGroups.length > 0) {
      req.mismatchOnly = true;
    }
    if (countOnlyActive) {
      req.countOnly = true;
    }
    return req;
  }

  function multiBuildExtras(withDedup: boolean) {
    const hubRef = sourceRows.find(isRowFilled)?.ref;
    return {
      mismatchOnly,
      highlightDuplicates,
      dedup:
        withDedup && dedupColumn && hubRef && isCascadeComplete(hubRef)
          ? { ...hubRef, column: dedupColumn }
          : null,
      registeredFuzzyFor,
      isFuzzyMatchable,
      maxTargetSets,
    };
  }

  function buildMultiRequest(withDedup: boolean): MultiTargetRecordMatchRequest | null {
    return buildMultiTargetRecordMatchRequest({
      hubRows: sourceRows,
      hubDisplayRows: sourceDisplayRows,
      hubSide,
      targets: targetBlocks.map((b) => ({
        label: b.label,
        joinRows: b.joinRows,
        displayRows: b.displayRows,
        joinType: b.joinType,
        comparisonPairs: b.comparisonPairs,
      })),
      ...multiBuildExtras(withDedup),
    });
  }

  function buildMultiRequestFromCanvas(withDedup: boolean): MultiTargetRecordMatchRequest | null {
    return buildMultiTargetRequestFromCanvas(joinCanvas, multiBuildExtras(withDedup), maxTargetSets);
  }

  useEffect(() => {
    if (multiMatchMode) return;
    const src = sourceRows.find(isRowFilled);
    const tgt = targetRows.find(isRowFilled);
    if (!src || !tgt || !isCascadeComplete(src.ref) || !isCascadeComplete(tgt.ref)) return;
    if (!src.columns.length || !tgt.columns.length) return;
    const key = `${qualifiedTableName(src.ref)}|${qualifiedTableName(tgt.ref)}`;
    if (comparisonPrefilledKeysRef.current.has(key)) return;
    if (comparisonPairs.length > 0) {
      comparisonPrefilledKeysRef.current.add(key);
      return;
    }
    const targetNames = new Set(tgt.columns.map((c) => c.name));
    const intersection = src.columns
      .map((c) => c.name)
      .filter((n) => targetNames.has(n))
      .sort((a, b) => a.localeCompare(b));
    comparisonPrefilledKeysRef.current.add(key);
    if (intersection.length === 0) return;
    const pairs = intersection.map((name) => ({
      id: crypto.randomUUID(),
      sourceColumn: name,
      targetColumn: name,
      fuzzyThresholdPercent: src.fuzzyThresholdPercent,
    }));
    queueMicrotask(() => setComparisonPairs(pairs));
  }, [sourceRows, targetRows, multiMatchMode, comparisonPairs.length]);

  /**
   * Multi-target parity with the two-table prefill: each target starts with the
   * columns whose names exist on both the hub and that target. Keyed per
   * (hub, target, block) so re-pointing one block prefills only that block, and
   * a block the officer has already edited is never overwritten.
   */
  useEffect(() => {
    if (!multiMatchMode) return;
    const hub = sourceRows.find(isRowFilled);
    if (!hub || !isCascadeComplete(hub.ref) || hub.columns.length === 0) return;
    const hubNames = new Set(hub.columns.map((c) => c.name));
    const fills = new Map<string, ComparisonPairRow[]>();
    for (const block of targetBlocks) {
      const tgt = block.joinRows.find(isRowFilled);
      if (!tgt || !isCascadeComplete(tgt.ref) || tgt.columns.length === 0) continue;
      const key = `${qualifiedTableName(hub.ref)}|${qualifiedTableName(tgt.ref)}|${block.id}`;
      if (comparisonPrefilledKeysRef.current.has(key)) continue;
      comparisonPrefilledKeysRef.current.add(key);
      if (block.comparisonPairs.length > 0) continue;
      const intersection = tgt.columns
        .map((c) => c.name)
        .filter((n) => hubNames.has(n))
        .sort((a, b) => a.localeCompare(b));
      if (intersection.length === 0) continue;
      fills.set(
        block.id,
        intersection.map((name) => ({
          id: crypto.randomUUID(),
          sourceColumn: name,
          targetColumn: name,
          fuzzyThresholdPercent: hub.fuzzyThresholdPercent,
        })),
      );
    }
    if (fills.size === 0) return;
    queueMicrotask(() =>
      setTargetBlocks((blocks) =>
        blocks.map((b) => (fills.has(b.id) ? { ...b, comparisonPairs: fills.get(b.id)! } : b)),
      ),
    );
  }, [multiMatchMode, sourceRows, targetBlocks]);

  function buildColumnLabels(): Record<string, string> {
    const labels: Record<string, string> = {};
    const labelSide = (prefix: string, side: string, ref: TableRef, column: string) => {
      const bn = businessNameFor(ref, column);
      if (bn) labels[`${prefix}_${column}`] = `${side}: ${bn}`;
    };
    for (const r of sourceRows) {
      if (isRowFilled(r)) labelSide("source", "Source", r.ref, r.column);
    }
    for (const r of targetRows) {
      if (isRowFilled(r)) labelSide("target", "Target", r.ref, r.column);
    }
    for (const r of sourceDisplayRows) {
      if (isDisplayRowFilled(r)) labelSide("source", "Source", r.ref, r.column);
    }
    for (const r of targetDisplayRows) {
      if (isDisplayRowFilled(r)) labelSide("target", "Target", r.ref, r.column);
    }
    comparisonPairs.forEach((p, i) => {
      if (p.sourceColumn && p.targetColumn) {
        labels[`cmp_${i}_source`] = `Compare: ${p.sourceColumn} (source)`;
        labels[`cmp_${i}_target`] = `Compare: ${p.targetColumn} (target)`;
        labels[`cmp_${i}_match`] = `Compare: ${p.sourceColumn} ↔ ${p.targetColumn} match?`;
        labels[`cmp_${i}_score_pct`] = `Compare: ${p.sourceColumn} ↔ ${p.targetColumn} score %`;
      }
    });
    labels.match_status = "Match status";
    return labels;
  }

  function flushPendingRows() {
    if (pendingRowsRef.current.length > 0) {
      const batch = pendingRowsRef.current;
      pendingRowsRef.current = [];
      setMatchRows((prev) => [...prev, ...batch].slice(0, MAX_DISPLAYED_ROWS));
    }
  }

  function beginMatchStream() {
    setMatchStatus("loading");
    setMatchError(null);
    setMatchColumns([]);
    setMatchRows([]);
    setMatchSql("");
    setMatchTotalRows(null);
    setMatchCountIsPartial(false);
    setMatchTooManyToDisplay(false);
    setComparisonSummary(null);
    setComparisonSummaryError(null);
    setCountOnlyResult(null);
    pendingRowsRef.current = [];
    rowsSeenRef.current = 0;
    if (flushIntervalRef.current !== null) {
      clearInterval(flushIntervalRef.current);
    }
    flushIntervalRef.current = setInterval(flushPendingRows, 100);
  }

  function appendStreamRow(row: Record<string, unknown>, controller: AbortController) {
    rowsSeenRef.current += 1;
    if (rowsSeenRef.current <= MAX_DISPLAYED_ROWS) {
      pendingRowsRef.current.push(row);
      carriedRowsRef.current.push(row);
    } else if (shouldStopMatchStream(rowsSeenRef.current)) {
      setMatchTooManyToDisplay(true);
      setMatchCountIsPartial(true);
      controller.abort();
    }
  }

  function finishStreamFlush() {
    if (flushIntervalRef.current !== null) {
      clearInterval(flushIntervalRef.current);
      flushIntervalRef.current = null;
    }
    flushPendingRows();
  }

  function handleStreamAbort(err: unknown, controller: AbortController) {
    if (controller.signal.aborted) {
      setMatchTotalRows(rowsSeenRef.current);
      setMatchStatus("ok");
    } else {
      setMatchError(presentQueryError(
        err instanceof Error ? err.message : String(err),
        t("msgAnalyticalEngineDown"),
      ));
      setMatchStatus("error");
    }
  }

  /**
   * Streams the COMPLETE result to a file. Built from a fresh backend request,
   * not from `matchRows` — above MAX_DISPLAYED_ROWS the browser deliberately
   * never held the rows, and even below it the grid holds only what it was
   * given. The match query therefore runs again, which is why this is on an
   * explicit click.
   */
  async function downloadFullExport(format: MatchExportFormat) {
    if (multiMatchMode) {
      const multiReq = lastRunMultiRequestRef.current;
      if (!multiReq) {
        throw new Error("Run a match first.");
      }
      const blob = await downloadMultiTargetMatchCsv(multiReq);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `analysis-match-multi-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      return;
    }
    const req = lastRunRequestRef.current;
    if (!req) {
      throw new Error("Run a match first.");
    }
    const ext = format === "xlsx" ? "xlsx" : format;
    const blob = await downloadRecordMatchExport(withoutCountOnly(req), format);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `analysis-match-${new Date().toISOString().slice(0, 19).replaceAll(/[:T]/g, "-")}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  async function runMatch(withDedup: boolean) {
    if (multiMatchMode) {
      const multiReq =
        multiUiMode === "canvas" ? buildMultiRequestFromCanvas(withDedup) : buildMultiRequest(withDedup);
      if (!multiReq) {
        setMatchError(
          multiUiMode === "canvas"
            ? "Complete the join canvas: one hub, target labels, and aligned join edges."
            : "Pick hub match columns and at least one target set (label + paired match columns on each target).",
        );
        return;
      }
      lastRunMultiRequestRef.current = multiReq;
      lastRunRequestRef.current = null;
      setMatchProgress([]);
      const labels = multiReq.targets.map((t) => t.label);
      setTargetRunStatus(initTargetProgress(labels));
      beginMatchStream();
      const controller = new AbortController();
      if (countOnlyActive) {
        const targets: { label: string; rows: number }[] = [];
        try {
          for (let i = 0; i < multiReq.targets.length; i++) {
            const single = singleTargetMatchRequestFromMulti(multiReq, i);
            if (!single) {
              continue;
            }
            let captured: Record<string, unknown> | null = null;
            await runRecordMatchStream(
              { ...single, countOnly: true },
              {
                onMeta: (meta) => {
                  if (meta.sql) {
                    setMatchSql((prev) => (prev ? `${prev}\n\n---\n\n${meta.sql}` : meta.sql));
                  }
                },
                onRow: (row) => {
                  captured = row;
                },
                onDone: () => {
                  const n = rowCountFromMatchRow(captured) ?? 0;
                  const label = labels[i];
                  targets.push({ label, rows: n });
                  setTargetRunStatus((prev) =>
                    mergeTargetProgress(prev, { targetIndex: i, label, phase: "done", rows: n }, labels),
                  );
                },
                onError: (message) => {
                  setTargetRunStatus((prev) =>
                    mergeTargetProgress(
                      prev,
                      { targetIndex: i, label: labels[i], phase: "error", message },
                      labels,
                    ),
                  );
                  throw new Error(message);
                },
              },
              controller.signal,
            );
          }
          setCountOnlyResult({ kind: "multi", targets });
          setMatchColumns([]);
          setMatchRows([]);
          setMatchTotalRows(targets.reduce((sum, t) => sum + t.rows, 0));
          setMatchStatus("ok");
        } catch (err: unknown) {
          handleStreamAbort(err, controller);
        } finally {
          finishStreamFlush();
        }
        return;
      }
      try {
        await runMultiTargetMatchStream(
          multiReq,
          {
            onMeta: (meta) => {
              setMatchColumns(meta.columns);
              setMatchSql("");
            },
            onProgress: (event) => {
              setMatchProgress((prev) => [...prev, event]);
              setTargetRunStatus((prev) => mergeTargetProgress(prev, event, labels));
              // Each target announces its own SQL as it is planned — the meta
              // line is written before any of them exist.
              if (event.sql) {
                setMatchSql((prev) =>
                  prev ? `${prev}\n\n---\n\n${event.sql}` : (event.sql as string),
                );
              }
            },
            onRow: (row) => appendStreamRow(row, controller),
            onDone: (totalRows) => {
              setMatchTotalRows(totalRows);
              setMatchStatus("ok");
            },
            onError: (message) => {
              setMatchError(presentQueryError(message, t("msgAnalyticalEngineDown")));
              setMatchStatus("error");
            },
          },
          controller.signal,
        );
      } catch (err: unknown) {
        handleStreamAbort(err, controller);
      } finally {
        finishStreamFlush();
      }
      return;
    }

    const req = buildRequest(withDedup);
    if (!req) {
      setMatchError("Pick at least one Source table + column and one matching Target table + column.");
      return;
    }
    if (
      embedded
      && !multiMatchMode
      && requestsEqual(req, useQueryBuilderPipeline.getState().lastAppliedRequest)
    ) {
      return;
    }
    lastRunRequestRef.current = req;
    lastRunMultiRequestRef.current = null;
    beginMatchStream();
    carriedRowsRef.current = [];
    streamColumnsRef.current = [];
    lastStreamSqlRef.current = "";

    const controller = new AbortController();
    let streamSucceeded = false;
    let countOnlyRow: Record<string, unknown> | null = null;
    try {
      await runRecordMatchStream(
        req,
        {
          onMeta: (meta) => {
            streamColumnsRef.current = req.countOnly ? [] : meta.columns;
            if (!req.countOnly) {
              setMatchColumns(meta.columns);
            }
            setMatchSql(meta.sql);
            lastStreamSqlRef.current = meta.sql;
          },
          onRow: (row) => {
            if (req.countOnly) {
              countOnlyRow = row;
              return;
            }
            appendStreamRow(row, controller);
          },
          onDone: (totalRows) => {
            if (req.countOnly) {
              const n = rowCountFromMatchRow(countOnlyRow) ?? 0;
              lastStreamTotalRowsRef.current = n;
              setCountOnlyResult({ kind: "single", rows: n });
              setMatchColumns([]);
              setMatchRows([]);
              setMatchTotalRows(n);
              setMatchStatus("ok");
              streamSucceeded = true;
              return;
            }
            lastStreamTotalRowsRef.current = totalRows;
            setMatchTotalRows(totalRows);
            setMatchStatus("ok");
            streamSucceeded = true;
            if (req.comparisonGroups && req.comparisonGroups.length > 0) {
              fetchComparisonSummary(req)
                .then(setComparisonSummary)
                .catch((err: unknown) =>
                  setComparisonSummaryError(presentQueryError(
                    err instanceof Error ? err.message : String(err),
                    t("msgAnalyticalEngineDown"),
                  )),
                );
            }
          },
          onError: (message) => {
            setMatchError(presentQueryError(message, t("msgAnalyticalEngineDown")));
            setMatchStatus("error");
          },
        },
        controller.signal,
      );
    } catch (err: unknown) {
      handleStreamAbort(err, controller);
      if (controller.signal.aborted && rowsSeenRef.current > 0) {
        streamSucceeded = true;
      }
    } finally {
      finishStreamFlush();
      if (embedded && streamSucceeded && req) {
        const snapshot = {
          columns: streamColumnsRef.current,
          rows: carriedRowsRef.current,
          totalRows: lastStreamTotalRowsRef.current ?? rowsSeenRef.current,
          sql: lastStreamSqlRef.current,
        };
        commitReportSuccess(req, snapshot);
        if (!req.countOnly) {
          setLastResult({
            columns: snapshot.columns,
            rows: snapshot.rows,
            totalRows: snapshot.totalRows ?? rowsSeenRef.current,
            source: "report",
            matchExportRequest: withoutCountOnly(req),
          });
          onRunComplete?.();
        }
      }
    }
  }

  const multiTargetLabels =
    multiMatchMode && multiUiMode === "canvas"
      ? canvasTargetNodes(joinCanvas).map((n) => n.label.trim()).filter(Boolean)
      : targetBlocks.map((b) => b.label.trim()).filter(Boolean);

  const displayedMatchRows =
    multiMatchMode && targetSetFilter !== "All"
      ? matchRows.filter((row) => row.match_set_label === targetSetFilter)
      : matchRows;

  const embeddedControlsPanel = embedded ? (
    <aside className={`report-controls-panel${controlsPanelOpen ? "" : " collapsed"}`}>
      <div className="report-panel-inner">
        <div className="report-panel-header">
          <h3>{t("panelControlsTitle")}</h3>
          <button
            type="button"
            className="panel-toggle-btn"
            onClick={() => setControlsPanelOpen((o) => !o)}
            aria-expanded={controlsPanelOpen}
            aria-label={controlsPanelOpen ? t("btnCollapseControls") : t("btnExpandControls")}
            title={controlsPanelOpen ? t("btnCollapseControls") : t("btnExpandControls")}
          >
            ›
          </button>
        </div>
        <div className="ctrl-card">
          <h5>
            <span className="step-num" style={{ width: 18, height: 18, fontSize: 10 }}>
              5
            </span>{" "}
            {t("secFilter")}
          </h5>
          <div className="ctrl-sub">{t("secFilterDesc")}</div>
          {isCascadeComplete(pipelineExtract.sourceRef) && (
            <ReportSourceFilters
              table={pipelineExtract.sourceRef}
              columns={sourceRows.find(isRowFilled)?.columns ?? []}
              report={pipelineReport}
              onPatch={patchPipelineReport}
            />
          )}
          {!multiMatchMode && (
            <label className="checkbox-row" htmlFor="mismatch-only-embed">
              <input
                id="mismatch-only-embed"
                type="checkbox"
                checked={mismatchOnly}
                disabled={comparisonPairs.every((p) => !p.sourceColumn || !p.targetColumn)}
                onChange={(e) => setMismatchOnly(e.target.checked)}
              />
              Show mismatches and unmatched records (server-side filter)
            </label>
          )}
          {multiMatchMode && (
            <label className="checkbox-row" htmlFor="multi-mismatch-only-embed">
              <input
                id="multi-mismatch-only-embed"
                type="checkbox"
                checked={mismatchOnly}
                disabled={targetBlocks.every((b) =>
                  b.comparisonPairs.every((c) => !c.sourceColumn || !c.targetColumn),
                )}
                onChange={(e) => setMismatchOnly(e.target.checked)}
              />
              Show mismatches and unmatched records (every target)
            </label>
          )}
          <label className="checkbox-row" htmlFor="highlight-duplicates-embed" style={{ marginTop: 8 }}>
            <input
              id="highlight-duplicates-embed"
              type="checkbox"
              checked={highlightDuplicates}
              onChange={(e) => {
                const checked = e.target.checked;
                setHighlightDuplicates(checked);
                patchPipelineReport({ highlightDuplicates: checked });
              }}
            />
            Highlight duplicate records
          </label>
          {pipelineDualMode && !multiMatchMode && (
            <label
              className="checkbox-row"
              htmlFor="hide-duplicate-records-embed"
              style={{ marginTop: 8 }}
              title={
                dedupBlockedByJoin
                  ? "Dedup is not available with RIGHT or FULL joins."
                  : !dedupColumn
                    ? "Select a join key or include last_refreshed_at on Extract to enable dedup."
                    : "Hides older duplicate rows, keeping the latest by last-updated date"
              }
            >
              <input
                id="hide-duplicate-records-embed"
                type="checkbox"
                aria-label="Hide duplicate records"
                checked={dedupEnabled && !dedupBlockedByJoin}
                disabled={!dedupColumn || dedupBlockedByJoin || matchStatus === "loading"}
                onChange={(e) => {
                  const enabled = e.target.checked;
                  setDedupEnabled(enabled);
                  patchPipelineReport({ dedupEnabled: enabled });
                }}
              />
              Hide duplicate records
            </label>
          )}
        </div>
        <div className="ctrl-card">
          <h5>
            <span className="step-num" style={{ width: 18, height: 18, fontSize: 10 }}>
              6
            </span>{" "}
            {t("secFuzzy")}
          </h5>
          <div className="ctrl-sub">{t("secFuzzyDesc")}</div>
          <p className="text-muted" style={{ fontSize: 12, margin: "8px 0 0" }}>
            Set fuzzy threshold per match row in the source criteria boxes. Admin column metadata and name heuristics
            decide which pairs use Levenshtein similarity.
          </p>
          {!multiMatchMode && (
            <div className="field" style={{ marginTop: 12 }}>
              <label htmlFor="join-type-embed">{t("qbJoinType")}</label>
              <select
                id="join-type-embed"
                value={joinType}
                onChange={(e) => {
                  const next = e.target.value as JoinType;
                  setJoinType(next);
                  patchPipelineReport({ joinType: next });
                }}
              >
                {JOIN_TYPE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {t(opt.labelKey)} ({opt.hint})
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
        <div className="ctrl-card">
          <h5>
            <span className="step-num" style={{ width: 18, height: 18, fontSize: 10 }}>
              7
            </span>{" "}
            {t("secGroupBy")}
          </h5>
          <div className="ctrl-sub">{t("secGroupByDesc")}</div>
          <label className="checkbox-row" htmlFor="report-group-enabled">
            <input
              id="report-group-enabled"
              type="checkbox"
              aria-label="Group by server-side totals"
              checked={pipelineReport.groupEnabled}
              onChange={(e) => patchPipelineReport({ groupEnabled: e.target.checked })}
            />
            {t("secGroupBy")} — server-side totals
          </label>
          {pipelineReport.groupEnabled && (
            <div className="field-grid" style={{ marginTop: 12 }}>
              <div className="field">
                <label htmlFor="report-group-by-col">{t("lblGroupByCols")}</label>
                <select
                  id="report-group-by-col"
                  value={pipelineReport.groupByCol}
                  onChange={(e) => patchPipelineReport({ groupByCol: e.target.value })}
                >
                  <option value="">{t("selNone")}</option>
                  {(embedded && pipelineExtract.displayCols.length > 0
                    ? pipelineExtract.displayCols.map((name) => ({ name }))
                    : (sourceRows.find(isRowFilled)?.columns ?? [])
                  ).map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="report-aggregate-fn">{t("btnAddAgg")}</label>
                <select
                  id="report-aggregate-fn"
                  value={pipelineReport.aggregateFn}
                  onChange={(e) =>
                    patchPipelineReport({ aggregateFn: e.target.value as AggregateFunction })
                  }
                >
                  <option value="COUNT">COUNT</option>
                  <option value="SUM">SUM</option>
                  <option value="AVG">AVG</option>
                  <option value="MIN">MIN</option>
                  <option value="MAX">MAX</option>
                </select>
              </div>
              <div className="field">
                <label htmlFor="report-aggregate-col">{t("lblDestAttr")}</label>
                <select
                  id="report-aggregate-col"
                  value={pipelineReport.aggregateCol}
                  onChange={(e) => patchPipelineReport({ aggregateCol: e.target.value })}
                >
                  <option value="">COUNT(*)</option>
                  {(sourceRows.find(isRowFilled)?.columns ?? []).map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          )}
          <p className="text-muted" style={{ fontSize: 12, margin: "8px 0 0" }}>
            Up to {maxAggregates} aggregates per request. Apply report options to rerun on the server.
          </p>
        </div>
        {!multiMatchMode && (
          <div className="ctrl-card">
            <h5>Compare columns (after join)</h5>
            <div className="ctrl-sub">Value checks on joined rows — they do not change the join.</div>
            <ComparisonPairsEditor
              pairs={comparisonPairs}
              onChange={(next) => {
                setComparisonPairs(next);
                syncComparisonPairsToPipeline(next);
              }}
              sourceRef={hubRefOrUndefined()}
              targetRef={filledRefOrUndefined(targetRows)}
              sourceColumns={sourceRows.find(isRowFilled)?.columns ?? []}
              targetColumns={targetRows.find(isRowFilled)?.columns ?? []}
              registeredFuzzyFor={registeredFuzzyFor}
              defaultThreshold={sourceRows.find(isRowFilled)?.fuzzyThresholdPercent ?? 80}
            />
          </div>
        )}
      </div>
    </aside>
  ) : null;

  return (
    <div className={embedded ? "query-builder-embed report-workspace" : "srse-page"}>
      <div className={embedded ? "report-results-pane" : undefined} style={embedded ? undefined : { display: "contents" }}>
      {!embedded && (
        <>
          <h1 className="srse-page-title">Analysis</h1>
          <p className="srse-page-description" style={{ maxWidth: "none", whiteSpace: "nowrap" }}>
            Reconcile records across lakehouse tables and columns — fuzzy-match on names, review duplicates, and
            export a clean result set. Nothing here is ever deleted from the lakehouse.
          </p>
        </>
      )}

      {loadError && <p className={embedded ? "text-danger" : "srse-text-danger"}>{loadError}</p>}

      <section
        role="region"
        className={embedded ? "section" : "srse-panel"}
        style={embedded ? undefined : { marginBottom: "1rem", padding: "0.75rem 1rem" }}
        aria-label={t("qbSavedQueriesReport")}
      >
        <p className="srse-text-muted" style={{ marginTop: 0, fontSize: "0.85rem" }}>
          Saved queries store your criteria and typed filter values (names, thresholds) in SRSE&apos;s
          database — not in the audit log. Reopening replans under <strong>your</strong> scope; the
          author&apos;s district does not travel with the query.
        </p>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "0.5rem", alignItems: "center" }}>
          <input
            type="text"
            className="srse-input"
            placeholder={t("qbSaveAs")}
            aria-label={t("qbSaveAs")}
            value={saveQueryName}
            onChange={(e) => setSaveQueryName(e.target.value)}
            style={{ minWidth: "12rem" }}
          />
          <button
            type="button"
            className="srse-btn srse-btn-secondary"
            disabled={multiMatchMode || !saveQueryName.trim()}
            onClick={async () => {
              if (embedded) {
                pushEmbeddedReportToPipeline();
              }
              const pipelineReport = useQueryBuilderPipeline.getState().report;
              let req = embedded
                ? useQueryBuilderPipeline.getState().mergedRequestForApply(pipelineReport.dedupEnabled)
                : buildRequest(false);
              if (embedded && req) {
                const { extract } = useQueryBuilderPipeline.getState();
                req = preserveExtractDisplayColumnsOnSave(extract, req);
              }
              if (!req) {
                setSavedQueryNotice({ tone: "error", text: t("msgQuerySaveIncomplete") });
                return;
              }
              try {
                await createSavedQuery({ name: saveQueryName.trim(), request: req });
                setSaveQueryName("");
                setSavedQueryNotice({ tone: "saved", text: t("msgQuerySaved") });
                try {
                  setSavedQueries(await listSavedQueries());
                } catch {
                  // The query is already stored. A failed list refresh must not replace the success message.
                }
              } catch (err: unknown) {
                const raw = err instanceof Error ? err.message : String(err);
                setSavedQueryNotice({
                  tone: "error",
                  text: presentQueryError(raw, t("msgAnalyticalEngineDown")),
                });
              }
            }}
          >
            {t("qbSaveQuery")}
          </button>
          <select
            className="srse-input"
            aria-label="Open saved query"
            value={openSavedId}
            onChange={async (e) => {
              const id = Number(e.target.value);
              setOpenSavedId("");
              if (!id) return;
              try {
                await openSavedQuery(id);
              } catch (err: unknown) {
                const raw = err instanceof Error ? err.message : String(err);
                setSavedQueryNotice({
                  tone: "error",
                  text: presentQueryError(raw, t("msgAnalyticalEngineDown")),
                });
              }
            }}
          >
            <option value="">{t("qbOpenSaved")}</option>
            {savedQueries.map((q) => (
              <option key={q.id} value={q.id}>
                {q.name}
                {!q.ownedByMe ? " (shared)" : ""}
              </option>
            ))}
          </select>
        </div>
        {savedQueryNotice?.tone === "saved" && (
          <p className="srse-save-banner" role="status">
            {savedQueryNotice.text}
          </p>
        )}
        {savedQueryNotice?.tone === "info" && (
          <p className="srse-text-muted" role="status" style={{ marginBottom: 0, fontSize: "0.85rem" }}>
            {savedQueryNotice.text}
          </p>
        )}
        {savedQueryNotice?.tone === "error" && (
          <p className="srse-query-notice" role="status">
            {savedQueryNotice.text}
          </p>
        )}
      </section>

      {!embedded && (
        <label className="srse-checkbox-label" htmlFor="highlight-duplicates" style={{ marginBottom: "0.5rem", display: "inline-flex" }}>
          <input
            id="highlight-duplicates"
            type="checkbox"
            checked={highlightDuplicates}
            onChange={(e) => setHighlightDuplicates(e.target.checked)}
          />
          {" "}
          Highlight Duplicate Records
          {multiMatchMode && highlightDuplicates && (
            <span className="srse-text-muted" style={{ marginLeft: "0.5rem", fontSize: "0.78rem" }} title="Scores are per target set and are not comparable across sets.">
              (match score is per target set)
            </span>
          )}
        </label>
      )}

      {!embedded && (
        <label
          className="srse-checkbox-label"
          htmlFor="multi-match-mode"
          style={{ marginBottom: "1rem", display: "inline-flex", marginLeft: "1.5rem" }}
        >
          <input
            id="multi-match-mode"
            type="checkbox"
            checked={multiMatchMode}
            onChange={(e) => setMultiMatchMode(e.target.checked)}
          />
          {" "}
          Match against multiple tables
        </label>
      )}

      {multiMatchMode && (
        <div style={{ marginBottom: "1rem", display: "flex", flexWrap: "wrap", gap: "1rem", alignItems: "center" }}>
          <span className="srse-text-muted" style={{ fontSize: "0.82rem" }}>Configure with</span>
          <label className="srse-checkbox-label" htmlFor="multi-ui-form">
            <input
              id="multi-ui-form"
              type="radio"
              name="multi-ui-mode"
              checked={multiUiMode === "form"}
              onChange={() => setMultiUiMode("form")}
            />
            {" "}
            Form
          </label>
          <label className="srse-checkbox-label" htmlFor="multi-ui-canvas">
            <input
              id="multi-ui-canvas"
              type="radio"
              name="multi-ui-mode"
              checked={multiUiMode === "canvas"}
              onChange={() => {
                setMultiUiMode("canvas");
                setJoinCanvas(
                  joinCanvasFromForm(
                    hubSide,
                    sourceRows,
                    sourceDisplayRows,
                    targetBlocks,
                    defaultSideRef(sourceRows),
                  ),
                );
              }}
            />
            {" "}
            Join canvas
          </label>
        </div>
      )}

      {multiMatchMode && multiUiMode === "form" && (
        <div style={{ marginBottom: "1rem" }}>
          <span className="srse-text-muted" style={{ fontSize: "0.82rem", marginRight: "0.75rem" }}>Hub is the</span>
          <label className="srse-checkbox-label" htmlFor="hub-side-source" style={{ display: "inline-flex", marginRight: "1rem" }}>
            <input
              id="hub-side-source"
              type="radio"
              name="hub-side"
              checked={hubSide === "SOURCE"}
              onChange={() => setHubSide("SOURCE")}
            />
            {" "}
            Source side
          </label>
          <label className="srse-checkbox-label" htmlFor="hub-side-target" style={{ display: "inline-flex" }}>
            <input
              id="hub-side-target"
              type="radio"
              name="hub-side"
              checked={hubSide === "TARGET"}
              onChange={() => setHubSide("TARGET")}
            />
            {" "}
            Target side
          </label>
        </div>
      )}

      {!embedded && !multiMatchMode && twoTableRefsReady && (
        <section className="srse-card" style={{ marginBottom: "1rem", width: "100%" }}>
          <h2 className="srse-card-title" style={{ marginTop: 0 }}>
            Join key suggestions
          </h2>
          <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
            Hints only — pick a row to fill the first criterion pair. Nothing is applied until you choose.
          </p>
          <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "0.75rem" }}>
            <button
              type="button"
              className="srse-btn srse-btn-secondary srse-btn-sm"
              disabled={keySuggestLoading}
              onClick={() => loadKeySuggestions(false)}
            >
              Suggest keys
            </button>
            <button
              type="button"
              className="srse-btn srse-btn-ghost srse-btn-sm"
              disabled={keySuggestLoading}
              title="Samples the source (10% Bernoulli) and scans the target in full per pair — bounded and may take several seconds"
              onClick={() => loadKeySuggestions(true)}
            >
              Check overlap (sampled)
            </button>
          </div>
          {keySuggestError && <p className="srse-text-danger">{keySuggestError}</p>}
          {keySuggestLoading && <p className="srse-text-muted">Loading suggestions…</p>}
          {!keySuggestLoading && keySuggestions.length > 0 && (
            <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
              {keySuggestions.map((s) => (
                <li key={`${s.sourceColumn}-${s.targetColumn}`} style={{ marginBottom: "0.4rem" }}>
                  <button
                    type="button"
                    className="srse-btn srse-btn-ghost srse-btn-sm"
                    // .srse-btn is an inline-flex with justify-content:center, which wins over
                    // textAlign — without flex-start these rows render centred in a wide card
                    // and stop reading as a pickable list.
                    style={{ textAlign: "left", justifyContent: "flex-start", width: "100%" }}
                    onClick={() => applyKeySuggestion(s)}
                  >
                    <strong>{s.sourceColumn}</strong> ({s.sourceType}) ↔{" "}
                    <strong>{s.targetColumn}</strong> ({s.targetType}) — {s.reason}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {multiMatchMode && multiUiMode === "canvas" && (
        <MultiTargetJoinCanvas
          fetchers={REGISTRY_FETCHERS}
          maxTargetSets={maxTargetSets}
          hubSide={hubSide}
          onHubSideChange={setHubSide}
          canvas={joinCanvas}
          onCanvasChange={setJoinCanvas}
          buildExtras={multiBuildExtras(false)}
          targetRunStatus={targetRunStatus}
          onReportError={reportError}
          registeredFuzzyFor={registeredFuzzyFor}
          businessNameFor={businessNameFor}
        />
      )}

      {embedded && (
        <section className="section" aria-label="Extract summary">
          <h4>{t("tabExtract")}</h4>
          <p className="desc">
            {pipelineDualMode
              ? `${pipelineExtract.sourceRef.catalog}.${pipelineExtract.sourceRef.schema}.${pipelineExtract.sourceRef.table} ↔ ${pipelineExtract.targetRef.catalog}.${pipelineExtract.targetRef.schema}.${pipelineExtract.targetRef.table}`
              : `${pipelineExtract.sourceRef.catalog}.${pipelineExtract.sourceRef.schema}.${pipelineExtract.sourceRef.table}`}
            {pipelineExtract.displayCols.length > 0
              ? ` · ${pipelineExtract.displayCols.length} attribute(s)`
              : ""}
            {pipelineDualMode && pipelineExtract.joinKeySource
              ? ` · join ${pipelineExtract.joinKeySource} ↔ ${pipelineExtract.joinKeyTarget}`
              : ""}
          </p>
          {isCascadeComplete(pipelineExtract.sourceRef) ? (
            <MetadataColumnStatus tableRef={pipelineExtract.sourceRef} />
          ) : null}
          {pipelineDualMode && isCascadeComplete(pipelineExtract.targetRef) ? (
            <MetadataColumnStatus tableRef={pipelineExtract.targetRef} />
          ) : null}
          {onGoExtract && (
            <button type="button" className="btn secondary sm" onClick={onGoExtract}>
              {t("btnBackExtract")}
            </button>
          )}
        </section>
      )}

      <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", width: "100%" }}>
        {!embedded && !(multiMatchMode && multiUiMode === "canvas") && (
        <>
        <CriterionBox
          prototypeUi={embedded}
          title={multiMatchMode ? "Hub table — match on" : "Select Source"}
          boxId="source"
          rows={sourceRows}
          showFuzzy
          pairedRows={multiMatchMode ? undefined : targetRows}
          registeredFuzzyFor={registeredFuzzyFor}
          businessNameFor={businessNameFor}
          compareAsFor={compareAsFor}
          onTableChange={(rowId, ref) => handleTableChange(setSourceRows, rowId, ref)}
          onColumnChange={(rowId, column) => handleColumnChange(setSourceRows, rowId, column)}
          onFuzzyChange={(rowId, value) => handleFuzzyChange(setSourceRows, rowId, value)}
          onFuzzyOptionsChange={(rowId, patch) => handleFuzzyOptionsChange(setSourceRows, rowId, patch)}
          onExtraColumnsChange={(rowId, cols) => handleExtraColumnsChange(setSourceRows, rowId, cols)}
          onModeChange={(rowId, mode) => handleModeChange(setSourceRows, rowId, mode)}
          onSeparatorChange={(rowId, sep) => handleSeparatorChange(setSourceRows, rowId, sep)}
          onRemove={(rowId) => handleRemoveRow(setSourceRows, rowId)}
          onAdd={() => setSourceRows((rs) => [...rs, createEmptyRow()])}
          onError={reportError}
          displaySection={
            <DisplayColumnBox
              title="Also show (not matched on)"
              boxId="source-display"
              rows={sourceDisplayRows}
              businessNameFor={businessNameFor}
              onTableChange={(rowId, ref) => handleDisplayTableChange(setSourceDisplayRows, rowId, ref)}
              onColumnChange={(rowId, column) =>
                setSourceDisplayRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, column } : r)))
              }
              onRemove={(rowId) => setSourceDisplayRows((rows) => rows.filter((r) => r.id !== rowId))}
              onAdd={() => {
                const ref = defaultSideRef(sourceRows);
                const row = createEmptyDisplayRow(ref);
                setSourceDisplayRows((rows) => [...rows, row]);
                if (isCascadeComplete(ref)) {
                  listAnalysisColumns(ref)
                    .then((cols) =>
                      setSourceDisplayRows((rows) =>
                        rows.map((r) => (r.id === row.id ? { ...r, columns: cols } : r)),
                      ),
                    )
                    .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
                }
              }}
              onError={reportError}
            />
          }
        />
        {!multiMatchMode && (
          <CriterionBox
            prototypeUi={embedded}
            title="Select Target"
            boxId="target"
            rows={targetRows}
            showFuzzy={false}
            registeredFuzzyFor={registeredFuzzyFor}
            businessNameFor={businessNameFor}
            compareAsFor={compareAsFor}
            onTableChange={(rowId, ref) => handleTableChange(setTargetRows, rowId, ref)}
            onColumnChange={(rowId, column) => handleColumnChange(setTargetRows, rowId, column)}
            onFuzzyChange={(rowId, value) => handleFuzzyChange(setTargetRows, rowId, value)}
            onFuzzyOptionsChange={(rowId, patch) => handleFuzzyOptionsChange(setTargetRows, rowId, patch)}
            onExtraColumnsChange={(rowId, cols) => handleExtraColumnsChange(setTargetRows, rowId, cols)}
            onModeChange={(rowId, mode) => handleModeChange(setTargetRows, rowId, mode)}
            onSeparatorChange={(rowId, sep) => handleSeparatorChange(setTargetRows, rowId, sep)}
            onRemove={(rowId) => handleRemoveRow(setTargetRows, rowId)}
            onAdd={() => setTargetRows((rs) => [...rs, createEmptyRow()])}
            onError={reportError}
            displaySection={
              <DisplayColumnBox
                title="Also show (not matched on)"
                boxId="target-display"
                rows={targetDisplayRows}
                businessNameFor={businessNameFor}
                onTableChange={(rowId, ref) => handleDisplayTableChange(setTargetDisplayRows, rowId, ref)}
                onColumnChange={(rowId, column) =>
                  setTargetDisplayRows((rows) => rows.map((r) => (r.id === rowId ? { ...r, column } : r)))
                }
                onRemove={(rowId) => setTargetDisplayRows((rows) => rows.filter((r) => r.id !== rowId))}
                onAdd={() => {
                  const ref = defaultSideRef(targetRows);
                  const row = createEmptyDisplayRow(ref);
                  setTargetDisplayRows((rows) => [...rows, row]);
                  if (isCascadeComplete(ref)) {
                    listAnalysisColumns(ref)
                      .then((cols) =>
                        setTargetDisplayRows((rows) =>
                          rows.map((r) => (r.id === row.id ? { ...r, columns: cols } : r)),
                        ),
                      )
                      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
                  }
                }}
                onError={reportError}
              />
            }
          />
        )}
        </>
        )}
      </div>

      {multiMatchMode && multiUiMode === "form" && (
        <div style={{ width: "100%", marginTop: "1rem" }}>
          <label
            className="srse-checkbox-label"
            htmlFor="multi-mismatch-only"
            style={{ display: "inline-flex", marginBottom: "0.75rem" }}
          >
            <input
              id="multi-mismatch-only"
              type="checkbox"
              checked={mismatchOnly}
              disabled={targetBlocks.every((b) =>
                b.comparisonPairs.every((c) => !c.sourceColumn || !c.targetColumn),
              )}
              onChange={(e) => setMismatchOnly(e.target.checked)}
            />
            {" "}
            Show mismatches and unmatched records (applies to every target)
          </label>
          {targetBlocks.map((block, blockIndex) => (
            <section key={block.id} className="srse-card" style={{ marginBottom: "1rem" }}>
              <div style={{ display: "flex", gap: "0.75rem", alignItems: "center", marginBottom: "0.5rem" }}>
                <label htmlFor={`target-label-${block.id}`} className="srse-text-muted" style={fieldLabelStyle}>
                  Target set label
                </label>
                <input
                  id={`target-label-${block.id}`}
                  className="srse-input"
                  style={{ flex: "1 1 200px" }}
                  value={block.label}
                  onChange={(e) =>
                    setTargetBlocks((blocks) =>
                      blocks.map((b) => (b.id === block.id ? { ...b, label: e.target.value } : b)),
                    )
                  }
                />
                {targetBlocks.length > 1 && (
                  <button
                    type="button"
                    className="srse-btn srse-btn-ghost srse-btn-sm"
                    onClick={() => setTargetBlocks((blocks) => blocks.filter((b) => b.id !== block.id))}
                  >
                    Remove set
                  </button>
                )}
              </div>
              <div style={{ marginBottom: "0.65rem" }}>
                <label htmlFor={`target-join-${block.id}`} className="srse-text-muted" style={fieldLabelStyle}>
                  Join type (hub is always the source side in SQL)
                </label>
                <select
                  id={`target-join-${block.id}`}
                  className="srse-select"
                  style={{ maxWidth: 320 }}
                  value={block.joinType}
                  onChange={(e) =>
                    setTargetBlocks((blocks) =>
                      blocks.map((b) =>
                        b.id === block.id ? { ...b, joinType: e.target.value as JoinType } : b,
                      ),
                    )
                  }
                >
                  {JOIN_TYPE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value} title={opt.hint}>
                      {t(opt.labelKey)}
                    </option>
                  ))}
                </select>
                {block.joinType === "LEFT" && (
                  <p className="srse-text-muted" style={{ fontSize: "0.75rem", margin: "0.35rem 0 0" }}>
                    LEFT keeps every hub row — target columns are empty when this target has no match (useful for
                    &quot;which hub rows miss in {block.label.trim() || "this target"}?&quot;).
                  </p>
                )}
              </div>
              <CriterionBox
                title={`Target ${blockIndex + 1} — match on (paired with hub rows)`}
                boxId={`target-block-${block.id}`}
                rows={block.joinRows}
                showFuzzy={false}
                pairedRows={sourceRows}
                registeredFuzzyFor={registeredFuzzyFor}
                businessNameFor={businessNameFor}
                compareAsFor={compareAsFor}
                onTableChange={(rowId, ref) => {
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id
                        ? {
                            ...b,
                            joinRows: updateRowById(b.joinRows, rowId, { ref, column: "", columns: [] }),
                          }
                        : b,
                    ),
                  );
                  if (!isCascadeComplete(ref)) return;
                  listAnalysisColumns(ref)
                    .then((cols) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? { ...b, joinRows: updateRowById(b.joinRows, rowId, { columns: cols }) }
                            : b,
                        ),
                      ),
                    )
                    .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
                }}
                onColumnChange={(rowId, column) =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id
                        ? { ...b, joinRows: updateRowById(b.joinRows, rowId, { column }) }
                        : b,
                    ),
                  )
                }
                onFuzzyChange={() => {}}
                onFuzzyOptionsChange={() => {}}
                onExtraColumnsChange={(rowId, cols) =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id
                        ? { ...b, joinRows: updateRowById(b.joinRows, rowId, { extraColumns: cols }) }
                        : b,
                    ),
                  )
                }
                onModeChange={() => {}}
                onSeparatorChange={() => {}}
                onRemove={(rowId) =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id ? { ...b, joinRows: removeRowById(b.joinRows, rowId) } : b,
                    ),
                  )
                }
                onAdd={() =>
                  setTargetBlocks((blocks) =>
                    blocks.map((b) =>
                      b.id === block.id ? { ...b, joinRows: [...b.joinRows, createEmptyRow()] } : b,
                    ),
                  )
                }
                onError={reportError}
                displaySection={
                  <DisplayColumnBox
                    title="Also show (not matched on)"
                    boxId={`target-block-display-${block.id}`}
                    rows={block.displayRows}
                    businessNameFor={businessNameFor}
                    onTableChange={(rowId, ref) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? {
                                ...b,
                                displayRows: b.displayRows.map((r) =>
                                  r.id === rowId ? { ...r, ref, column: "", columns: [] } : r,
                                ),
                              }
                            : b,
                        ),
                      )
                    }
                    onColumnChange={(rowId, column) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? {
                                ...b,
                                displayRows: b.displayRows.map((r) =>
                                  r.id === rowId ? { ...r, column } : r,
                                ),
                              }
                            : b,
                        ),
                      )
                    }
                    onRemove={(rowId) =>
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id
                            ? { ...b, displayRows: b.displayRows.filter((r) => r.id !== rowId) }
                            : b,
                        ),
                      )
                    }
                    onAdd={() => {
                      const ref = defaultSideRef(block.joinRows);
                      const row = createEmptyDisplayRow(ref);
                      setTargetBlocks((blocks) =>
                        blocks.map((b) =>
                          b.id === block.id ? { ...b, displayRows: [...b.displayRows, row] } : b,
                        ),
                      );
                      if (isCascadeComplete(ref)) {
                        listAnalysisColumns(ref)
                          .then((cols) =>
                            setTargetBlocks((blocks) =>
                              blocks.map((b) =>
                                b.id === block.id
                                  ? {
                                      ...b,
                                      displayRows: b.displayRows.map((r) =>
                                        r.id === row.id ? { ...r, columns: cols } : r,
                                      ),
                                    }
                                  : b,
                              ),
                            ),
                          )
                          .catch((err: unknown) =>
                            setLoadError(err instanceof Error ? err.message : String(err)),
                          );
                      }
                    }}
                    onError={reportError}
                  />
                }
              />
              <div
                style={{
                  marginTop: "0.75rem",
                  paddingTop: "0.75rem",
                  borderTop: "1px solid var(--srse-border)",
                }}
              >
                <div className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.4rem" }}>
                  Compare columns (after join) — hub ↔ {block.label.trim() || `Target ${blockIndex + 1}`}
                </div>
                <ComparisonPairsEditor
                  pairs={block.comparisonPairs}
                  onChange={(next) =>
                    setTargetBlocks((blocks) =>
                      blocks.map((b) => (b.id === block.id ? { ...b, comparisonPairs: next } : b)),
                    )
                  }
                  sourceRef={hubRefOrUndefined()}
                  targetRef={filledRefOrUndefined(block.joinRows)}
                  sourceColumns={sourceRows.find(isRowFilled)?.columns ?? []}
                  targetColumns={block.joinRows.find(isRowFilled)?.columns ?? []}
                  registeredFuzzyFor={registeredFuzzyFor}
                  defaultThreshold={sourceRows.find(isRowFilled)?.fuzzyThresholdPercent ?? 80}
                />
              </div>
            </section>
          ))}
          <button
            type="button"
            className="srse-btn srse-btn-ghost srse-btn-sm"
            disabled={targetBlocks.length >= maxTargetSets}
            onClick={() =>
              setTargetBlocks((blocks) => [...blocks, createTargetBlock(`Target ${blocks.length + 1}`)])
            }
          >
            + Add target ({targetBlocks.length}/{maxTargetSets})
          </button>
          {dedupEnabled && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "0.5rem" }}>
              Dedup uses a column on the hub table only (not on individual target tables).
            </p>
          )}
        </div>
      )}

      <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "0.5rem" }}>
        Fuzzy % applies to columns marked fuzzy-matchable in Admin, or (if unmapped) when a column
        name contains &quot;name&quot;; other pairs match exactly. Match-on rows pair up in order and form
        the join. Rows under &quot;Also show (not matched on)&quot; are projected in the result only.
        {multiMatchMode &&
          " Multi-target runs one two-table join per target set; the NDJSON stream can include partial results if one set fails — CSV export is all-or-nothing."}
      </p>

      {!embedded && !multiMatchMode && (
        <section className="srse-card" style={{ width: "100%", marginTop: "1rem" }}>
          <h2 className="srse-card-title">Compare columns (after join)</h2>
          <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
            Match criteria above decide <strong>which rows pair up</strong>. These pairs compare values on
            already-joined rows — they never affect the join. Prefilled with column names that exist on
            both sides; add or remove as needed.
          </p>
          <label className="srse-checkbox-label" htmlFor="mismatch-only" style={{ display: "inline-flex", marginBottom: "0.75rem" }}>
            <input
              id="mismatch-only"
              type="checkbox"
              checked={mismatchOnly}
              disabled={comparisonPairs.every((p) => !p.sourceColumn || !p.targetColumn)}
              onChange={(e) => setMismatchOnly(e.target.checked)}
            />
            {" "}
            Show mismatches and unmatched records (server-side filter)
          </label>
          <ComparisonPairsEditor
            pairs={comparisonPairs}
            onChange={setComparisonPairs}
            sourceRef={hubRefOrUndefined()}
            targetRef={filledRefOrUndefined(targetRows)}
            sourceColumns={sourceRows.find(isRowFilled)?.columns ?? []}
            targetColumns={targetRows.find(isRowFilled)?.columns ?? []}
            registeredFuzzyFor={registeredFuzzyFor}
            defaultThreshold={sourceRows.find(isRowFilled)?.fuzzyThresholdPercent ?? 80}
          />
        </section>
      )}

      {!embedded && !multiMatchMode && (
        <section className="srse-card" style={{ marginTop: "1rem" }}>
          <h2 className="srse-card-title">Join type</h2>
          <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
            Pick the same registered table on source and target for a <strong>self-join</strong> — no
            separate mode is needed.{" "}
            {joinType === "FULL" && (
              <span className="srse-text-danger">
                FULL joins can be very large: the match is uncapped server-side, so two big tables
                will usually hit the 10,000-row display limit and the CSV download path.
              </span>
            )}
          </p>
          <label htmlFor="join-type" className="srse-text-muted" style={{ fontSize: "0.85rem" }}>
            Records to include
            <select
              id="join-type"
              className="srse-select"
              style={{ marginLeft: "0.5rem", minWidth: 280 }}
              value={joinType}
              onChange={(e) => setJoinType(e.target.value as JoinType)}
            >
              {JOIN_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {t(opt.labelKey)} ({opt.hint})
                </option>
              ))}
            </select>
          </label>
        </section>
      )}

      <div
        className={embedded ? "row" : undefined}
        style={{
          display: "flex",
          justifyContent: "flex-end",
          gap: "0.6rem",
          flexWrap: "wrap",
          marginTop: "1rem",
          alignItems: "center",
        }}
      >
        {!multiMatchMode && (
          <button
            type="button"
            className={embedded ? "btn secondary" : "srse-btn srse-btn-ghost"}
            disabled={sqlPreviewLoading || matchStatus === "loading"}
            onClick={async () => {
              const req = buildRequest(false);
              if (!req) {
                setSqlPreviewError("Fill in at least one source/target match pair first.");
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
            }}
          >
            {sqlPreviewLoading ? "Planning…" : "Preview SQL"}
          </button>
        )}
        {embedded && (
          <button
            type="button"
            className="btn secondary"
            data-testid="report-filters-toggle"
            aria-expanded={controlsPanelOpen}
            aria-label={t("btnToggleControls")}
            onClick={() => setControlsPanelOpen((o) => !o)}
          >
            {t("btnToggleControls")}
          </button>
        )}
        <label
          className={embedded ? "checkbox-row" : undefined}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: "0.4rem",
            margin: 0,
            fontSize: "0.9rem",
            opacity: countOnlyBlocked ? 0.6 : 1,
          }}
          title={countOnlyBlocked ? t("tipCountOnlyGrouped") : undefined}
        >
          <input
            type="checkbox"
            data-testid="report-count-only"
            checked={countOnly}
            disabled={countOnlyBlocked}
            onChange={(e) => {
              const next = e.target.checked && !countOnlyBlocked;
              if (embedded) {
                patchPipelineReport({ countOnly: next });
              } else {
                setStandaloneCountOnly(next);
              }
            }}
          />
          {t("lblCountOnly")}
        </label>
        <button
          type="button"
          className={embedded ? "btn" : "srse-btn srse-btn-primary"}
          onClick={() => runMatch(dedupActive)}
          disabled={
            matchStatus === "loading"
            || (embedded
              && !multiMatchMode
              && requestsEqual(
                buildMergedRequest(
                  pipelineDualMode,
                  pipelineExtract,
                  embeddedReportPreview(dedupActive),
                ),
                lastAppliedPipelineRequest,
              ))
          }
        >
          {matchStatus === "loading"
            ? t("btnRunning")
            : embedded
              ? "Apply report options"
              : t("qbRunMatch")}
        </button>
      </div>

      {sqlPreviewError && (
        <p className="srse-query-notice" role="status">
          {sqlPreviewError}
        </p>
      )}
      {sqlPreview && (
        embedded ? (
          <div className="query-box" style={{ marginTop: "0.75rem" }}>{sqlPreview}</div>
        ) : (
        <pre
          className="srse-card"
          style={{
            marginTop: "0.75rem",
            overflowX: "auto",
            fontSize: "0.75rem",
            whiteSpace: "pre-wrap",
            wordBreak: "break-word",
          }}
        >
          {sqlPreview}
        </pre>
        )
      )}

      {matchError && (
        <p className="srse-query-notice" role="status">
          {matchError}
        </p>
      )}

      {multiMatchMode && targetRunStatus.length > 0 && (
        <ul className="srse-text-muted" style={{ fontSize: "0.82rem", marginTop: "0.75rem", listStyle: "none", padding: 0 }}>
          {targetRunStatus.map((p) => (
            <li key={`${p.label}-${p.phase}-${p.targetIndex}`} style={{ marginBottom: "0.25rem" }}>
              <strong>{p.label}</strong>:{" "}
              {p.phase === "error" ? (
                <span className="srse-text-danger">{phaseLabel(p)}</span>
              ) : p.phase === "skipped" ? (
                <span title="Shared multi-target time budget — skipped is not the same as zero matches">{phaseLabel(p)}</span>
              ) : (
                phaseLabel(p)
              )}
            </li>
          ))}
        </ul>
      )}

      {countOnlyResult && matchStatus === "ok" && (
        <div
          data-testid="report-count-only-result"
          className={embedded ? undefined : "srse-card"}
          style={{ marginTop: "1.25rem", padding: embedded ? "0.5rem 0" : "1rem 1.25rem" }}
        >
          {countOnlyResult.kind === "single" ? (
            <p style={{ margin: 0, fontSize: "1.35rem", fontWeight: 650 }}>
              {fillCatalog(t("msgCountOnlyRows"), { count: countOnlyResult.rows.toLocaleString() })}
            </p>
          ) : (
            <ul style={{ margin: 0, padding: 0, listStyle: "none" }}>
              {countOnlyResult.targets.map((target) => (
                <li key={target.label} style={{ fontSize: "1.15rem", fontWeight: 650, marginBottom: "0.35rem" }}>
                  {target.label}:{" "}
                  {fillCatalog(t("msgCountOnlyRows"), { count: target.rows.toLocaleString() })}
                </li>
              ))}
            </ul>
          )}
          <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.85rem", flexWrap: "wrap" }}>
            <button
              type="button"
              className={embedded ? "btn sm" : "srse-btn srse-btn-primary srse-btn-sm"}
              aria-label="Download CSV"
              onClick={() => void downloadFullExport("csv")}
            >
              CSV
            </button>
            {!multiMatchMode && (
              <button
                type="button"
                className={embedded ? "btn sm" : "srse-btn srse-btn-primary srse-btn-sm"}
                aria-label="Download Excel"
                onClick={() => void downloadFullExport("xlsx")}
              >
                Excel
              </button>
            )}
          </div>
        </div>
      )}

      {comparisonSummaryError && (
        <p className="srse-query-notice" role="status">
          Comparison summary: {comparisonSummaryError}
        </p>
      )}
      {comparisonSummary && comparisonSummary.columns.length > 0 && (
        <section
          className="srse-card"
          style={{ marginTop: "1rem", display: "flex", flexWrap: "wrap", gap: "1rem" }}
        >
          <p className="srse-text-muted" style={{ margin: 0, width: "100%", fontSize: "0.85rem" }}>
            {(comparisonSummary.matchedRows ?? comparisonSummary.totalRows).toLocaleString()} matched
            {(comparisonSummary.noCounterpartRows ?? 0) > 0 && (
              <>
                , {(comparisonSummary.noCounterpartRows ?? 0).toLocaleString()} with no counterpart
              </>
            )}{" "}
            ({comparisonSummary.totalRows.toLocaleString()} total rows — full result, not the on-screen
            sample). Column rates are over matched rows only.
          </p>
          {comparisonSummary.columns.map((col) => (
            <div key={col.index} style={{ minWidth: 180 }}>
              <div style={{ fontWeight: 600, fontSize: "0.9rem" }}>{col.label}</div>
              <div className="srse-text-muted" style={{ fontSize: "0.85rem" }}>
                {col.matchRatePercent}% match ({col.matchCount.toLocaleString()} /{" "}
                {(comparisonSummary.matchedRows ?? comparisonSummary.totalRows).toLocaleString()} matched)
              </div>
            </div>
          ))}
        </section>
      )}

      {matchColumns.length > 0 && !countOnlyResult && (
        <div style={{ marginTop: "1.5rem" }}>
          {embedded && matchStatus === "ok" && (
            <div className="row" style={{ justifyContent: "flex-end", marginBottom: 12, gap: 8 }}>
              <button
                type="button"
                className="btn secondary sm"
                onClick={() => router.push("/dashboard")}
              >
                {t("btnViewDashboard")}
              </button>
            </div>
          )}
          {multiMatchMode && (
            <div style={{ marginBottom: "0.75rem" }}>
              <label htmlFor="target-set-filter" className="srse-text-muted" style={{ marginRight: "0.5rem" }}>
                Target set
              </label>
              <select
                id="target-set-filter"
                className="srse-select"
                value={targetSetFilter}
                onChange={(e) => setTargetSetFilter(e.target.value)}
              >
                <option value="All">All</option>
                {multiTargetLabels.map((label) => (
                  <option key={label} value={label}>
                    {label}
                  </option>
                ))}
              </select>
            </div>
          )}
          <AnalysisResultsGrid
            appearance={embedded ? "prototype" : "legacy"}
            columns={matchColumns}
            rows={displayedMatchRows}
            sql={matchSql}
            streaming={matchStreaming}
            totalRows={matchTotalRows}
            totalRowsIsPartial={matchCountIsPartial}
            tooManyToDisplay={matchTooManyToDisplay}
            displayLimit={MAX_DISPLAYED_ROWS}
            onDownloadFullCsv={() => downloadFullExport("csv")}
            onDownloadFullExport={multiMatchMode ? undefined : downloadFullExport}
            fullCsvDownloadNote={
              multiMatchMode
                ? "Multi-target CSV export is all-or-nothing: if any target set fails, the whole download aborts (unlike the stream, which can return partial rows)."
                : undefined
            }
            highlightDuplicates={highlightDuplicates}
            dedupAvailable={!embedded && !!dedupColumn}
            dedupEnabled={dedupEnabled}
            dedupDisabledReason={
              dedupBlockedByJoin
                ? multiMatchMode
                  ? "Dedup is not available when any target uses RIGHT or FULL — hub partition keys are NULL on unmatched rows."
                  : "Dedup is not available with RIGHT or FULL joins — unmatched rows share NULL partition keys and would collapse to one row."
                : undefined
            }
            columnLabels={buildColumnLabels()}
            onDedupToggle={(enabled) => {
              setDedupEnabled(enabled);
              patchPipelineReport({ dedupEnabled: enabled });
              runMatch(enabled);
            }}
          />
        </div>
      )}
      </div>
      {embeddedControlsPanel}
    </div>
  );
}
