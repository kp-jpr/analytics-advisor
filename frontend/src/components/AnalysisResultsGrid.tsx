"use client";

import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from "react";
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnFiltersState,
  type SortingState,
} from "@tanstack/react-table";
import { ChartsSection } from "@/components/ChartsSection";
import { ResultTableToolbar } from "@/components/results/ResultTableToolbar";
import { useShell } from "@/components/shell/ShellProviders";
import { fetchChartBuckets, type RecordMatchRequest } from "@/lib/analysisApi";
import {
  resetColumnVisibilityForSchema,
  visibleColumnIds,
} from "@/lib/resultTableColumns";
import { RESULT_DEFAULT_PAGE_SIZE } from "@/lib/resultTableConstants";
import { exportBufferedAnalyticalResult } from "@/lib/resultTableExport";
import { compareCellValues } from "@/lib/resultTableSort";

type Row = Record<string, unknown>;

function prettify(columnId: string): string {
  if (columnId === "match_status" || columnId.endsWith("_match_status")) return "Match status";
  const cmpMatch = columnId.match(/^cmp_(\d+)_match$/);
  if (cmpMatch) return `Compare ${Number(cmpMatch[1]) + 1}: Match?`;
  const cmpScore = columnId.match(/^cmp_(\d+)_score_pct$/);
  if (cmpScore) return `Compare ${Number(cmpScore[1]) + 1}: Score %`;
  const cmpSrc = columnId.match(/^cmp_(\d+)_source$/);
  if (cmpSrc) return `Compare ${Number(cmpSrc[1]) + 1}: Source value`;
  const cmpTgt = columnId.match(/^cmp_(\d+)_target$/);
  if (cmpTgt) return `Compare ${Number(cmpTgt[1]) + 1}: Target value`;
  return columnId
    .replace(/^source_/, "Source: ")
    .replace(/^target_/, "Target: ")
    .replaceAll("_", " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function isComparisonColumn(columnId: string): boolean {
  return /^cmp_\d+_(match|score_pct|source|target)$/.test(columnId);
}

function isMatchStatusColumn(columnId: string): boolean {
  return columnId === "match_status" || columnId.endsWith("_match_status");
}

function displayCell(columnId: string, value: unknown): string {
  if (value === null || value === undefined) {
    if (isComparisonColumn(columnId)) return "—";
    return "";
  }
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

const MATCH_STATUS_STYLES: Record<string, CSSProperties> = {
  MATCHED: { background: "var(--srse-success-bg, #e8f5e9)", fontWeight: 600, padding: "0.1rem 0.35rem", borderRadius: 4 },
  NO_TARGET: { background: "var(--srse-bg-muted, #f0f0f0)", fontWeight: 600, padding: "0.1rem 0.35rem", borderRadius: 4 },
  NO_SOURCE: { background: "var(--srse-bg-muted, #f0f0f0)", fontWeight: 600, padding: "0.1rem 0.35rem", borderRadius: 4 },
};

function filterInputStyle(proto: boolean): CSSProperties {
  return {
    width: "100%",
    padding: "0.25rem 0.4rem",
    fontSize: "0.78rem",
    fontWeight: 400,
    border: proto ? "1px solid var(--border-strong, var(--border))" : "1px solid var(--srse-border-strong)",
    borderRadius: proto ? "var(--radius-sm, 6px)" : "var(--srse-radius-sm)",
    background: proto ? "var(--surface)" : "var(--srse-surface)",
    marginTop: "0.35rem",
  };
}

function ariaSortValue(sorted: false | "asc" | "desc"): "none" | "ascending" | "descending" {
  if (sorted === "asc") return "ascending";
  if (sorted === "desc") return "descending";
  return "none";
}

export type AnalysisResultsGridProps = Readonly<{
  columns: string[];
  rows: Row[];
  sql: string;
  streaming?: boolean;
  totalRows?: number | null;
  totalRowsIsPartial?: boolean;
  tooManyToDisplay?: boolean;
  displayLimit?: number;
  onDownloadFullCsv?: () => Promise<void>;
  onDownloadFullExport?: (format: "csv" | "json" | "xml" | "xlsx") => Promise<void>;
  highlightDuplicates: boolean;
  dedupAvailable: boolean;
  dedupEnabled: boolean;
  dedupDisabledReason?: string;
  onDedupToggle: (enabled: boolean) => void;
  columnLabels?: Record<string, string>;
  fullCsvDownloadNote?: string;
  appearance?: "legacy" | "prototype";
  /** Hide charts section. Query Builder passes false; the dashboard keeps charts. */
  showCharts?: boolean;
  /** Dashboard draws the chart above the table. Other callers keep it below. */
  chartPlacement?: "above" | "below";
  /** When set, charts count the full match instead of the rows on screen. */
  chartRequest?: RecordMatchRequest | null;
  /** Exact match size from chart grouping. */
  onFullRowCount?: (totalRows: number) => void;
  /** When true, CSV/Excel use the backend match export (UI sort does not apply). */
  serverExportAvailable?: boolean;
}>;

function uiClass(appearance: "legacy" | "prototype" | undefined, legacy: string, proto: string): string {
  return appearance === "prototype" ? proto : legacy;
}

function MatchRowCountCaption({
  rowsLength,
  streaming,
  totalRows,
  totalRowsIsPartial,
  tooManyToDisplay,
}: Readonly<{
  rowsLength: number;
  streaming: boolean;
  totalRows: number | null | undefined;
  totalRowsIsPartial: boolean | undefined;
  tooManyToDisplay?: boolean;
}>) {
  if (tooManyToDisplay) {
    return (
      <>
        Showing first {rowsLength.toLocaleString()} of {totalRows == null ? "many" : totalRows.toLocaleString()}
        {totalRowsIsPartial ? "+" : ""} matching rows{streaming ? " (still counting…)" : ""}
      </>
    );
  }
  if (streaming || totalRows == null || totalRows <= rowsLength) {
    return (
      <>
        {rowsLength} row{rowsLength === 1 ? "" : "s"}
        {streaming ? " (loading more…)" : ""}
      </>
    );
  }
  return (
    <>
      Showing {rowsLength} of {totalRows}
      {totalRowsIsPartial ? "+" : ""} matching rows — refine criteria to load more on screen.
    </>
  );
}

function TooManyRowsPanel({
  totalRows,
  totalRowsIsPartial,
  displayLimit,
  fullCsvDownloadNote,
  exportUsesServerOrder,
}: Readonly<{
  totalRows: number | null | undefined;
  totalRowsIsPartial: boolean | undefined;
  displayLimit: number | undefined;
  fullCsvDownloadNote?: string;
  exportUsesServerOrder?: boolean;
}>) {
  const limitLabel = (displayLimit ?? 10000).toLocaleString();

  return (
    <div className="result-too-many-panel" role="status">
      <p style={{ marginTop: 0, marginBottom: "0.5rem", fontWeight: 600 }}>Large result — showing a preview</p>
      <p className="srse-text-muted" style={{ marginTop: 0, lineHeight: 1.5 }}>
        The paginated table shows the first {limitLabel} rows from
        {totalRows != null ? ` ${totalRows.toLocaleString()}${totalRowsIsPartial ? "+" : ""}` : " the complete result"}.
        Download CSV or Excel for every row.
      </p>
      {exportUsesServerOrder && (
        <p className="result-table-export-note" style={{ marginTop: 0 }}>
          Downloads use the server query order (not the temporary sort applied on screen).
        </p>
      )}
      {fullCsvDownloadNote && <p className="srse-text-muted">{fullCsvDownloadNote}</p>}
    </div>
  );
}

export function AnalysisResultsGrid({
  columns,
  rows,
  sql,
  streaming,
  totalRows,
  totalRowsIsPartial,
  tooManyToDisplay,
  displayLimit,
  onDownloadFullCsv,
  onDownloadFullExport,
  highlightDuplicates,
  dedupAvailable,
  dedupEnabled,
  dedupDisabledReason,
  onDedupToggle,
  columnLabels,
  fullCsvDownloadNote,
  appearance = "legacy",
  showCharts = true,
  chartPlacement = "below",
  chartRequest = null,
  onFullRowCount,
  serverExportAvailable = Boolean(onDownloadFullExport || onDownloadFullCsv),
}: AnalysisResultsGridProps) {
  const { t } = useShell();
  const proto = appearance === "prototype";
  const labelFor = (id: string) => columnLabels?.[id] ?? prettify(id);
  const muted = uiClass(appearance, "srse-text-muted", "text-muted");
  const borderVar = proto ? "var(--border)" : "var(--srse-border)";
  const radiusSm = proto ? "var(--radius-sm, 6px)" : "var(--srse-radius-sm)";

  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const [pagination, setPagination] = useState({
    pageIndex: 0,
    pageSize: RESULT_DEFAULT_PAGE_SIZE,
  });
  const [hiddenColumnIds, setHiddenColumnIds] = useState<string[]>([]);
  const [showSql, setShowSql] = useState(true);
  const [exportBusy, setExportBusy] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const columnsKey = columns.join("\u0001");
  useEffect(() => {
    setHiddenColumnIds((prev) => resetColumnVisibilityForSchema(prev, columns));
    setSorting([]);
    setColumnFilters([]);
  }, [columnsKey, columns]);

  const effectiveVisibleIds = useMemo(
    () => visibleColumnIds(columns, hiddenColumnIds),
    [columns, hiddenColumnIds],
  );

  const columnHelper = useMemo(() => createColumnHelper<Row>(), []);

  function comparisonCellStyle(columnId: string, row: Row): CSSProperties | undefined {
    const match = columnId.match(/^cmp_(\d+)_match$/);
    if (match) {
      const v = row[columnId];
      if (v === null || v === undefined) return { color: "var(--srse-text-muted)", fontStyle: "italic" };
      if (v === false || v === 0 || v === "false") return { background: "var(--srse-danger-bg, #fde8e8)", fontWeight: 600 };
      if (v === true || v === 1 || v === "true") return { background: "var(--srse-success-bg, #e8f5e9)" };
    }
    const valueCol = columnId.match(/^cmp_(\d+)_(source|target|score_pct)$/);
    if (valueCol) {
      const verdict = row[`cmp_${valueCol[1]}_match`];
      if (verdict === null || verdict === undefined) return { color: "var(--srse-text-muted)", fontStyle: "italic" };
      if (verdict === false || verdict === 0 || verdict === "false") return { background: "var(--srse-danger-bg, #fde8e8)" };
    }
    return undefined;
  }

  const tableColumns = useMemo(
    () =>
      columns.map((id) =>
        columnHelper.accessor((row) => row[id], {
          id,
          header: labelFor(id),
          enableSorting: true,
          sortingFn: (rowA, rowB, colId) =>
            compareCellValues(rowA.getValue(colId), rowB.getValue(colId)),
          cell: (info) => {
            const style = comparisonCellStyle(id, info.row.original);
            const text = displayCell(id, info.getValue());
            if (isMatchStatusColumn(id)) {
              const statusStyle = MATCH_STATUS_STYLES[String(info.getValue() ?? "")];
              return statusStyle ? <span style={statusStyle}>{text}</span> : text;
            }
            return style ? <span style={style}>{text}</span> : text;
          },
          filterFn: (row, columnId, filterValue) => {
            if (!filterValue) return true;
            return displayCell(columnId, row.getValue(columnId))
              .toLowerCase()
              .includes(String(filterValue).toLowerCase());
          },
        }),
      ),
    [columns, columnHelper, columnLabels],
  );

  const table = useReactTable({
    data: rows,
    columns: tableColumns,
    state: {
      sorting,
      columnFilters,
      columnVisibility: Object.fromEntries(columns.map((c) => [c, effectiveVisibleIds.includes(c)])),
      pagination,
    },
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    onPaginationChange: setPagination,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    autoResetPageIndex: false,
    enableSortingRemoval: true,
  });

  const { pageIndex, pageSize } = table.getState().pagination;
  const pageCount = table.getPageCount();
  const filteredCount = table.getFilteredRowModel().rows.length;

  useEffect(() => {
    setPagination((p) => ({ ...p, pageIndex: 0 }));
  }, [rows, sorting, columnFilters, hiddenColumnIds, columnsKey]);

  const loadChartBuckets = useMemo(() => {
    if (!chartRequest) return undefined;
    return (columns: string[]) => fetchChartBuckets(chartRequest, columns);
  }, [chartRequest]);

  const exportUsesServer = serverExportAvailable && Boolean(onDownloadFullExport || onDownloadFullCsv);

  async function handleExport(format: "csv" | "xlsx" | "json" | "xml") {
    if (streaming) return;
    setExportBusy(true);
    setExportError(null);
    try {
      if (onDownloadFullExport) {
        await onDownloadFullExport(format);
        return;
      }
      if (format === "csv" && onDownloadFullCsv) {
        await onDownloadFullCsv();
        return;
      }
      if ((format === "csv" || format === "xlsx") && !tooManyToDisplay) {
        await exportBufferedAnalyticalResult(format, columns, rows, labelFor);
        return;
      }
      if (tooManyToDisplay) {
        throw new Error(
          "Run the query again to download every row. This screen only kept the first 10,000.",
        );
      }
    } catch (err: unknown) {
      setExportError(err instanceof Error ? err.message : String(err));
    } finally {
      setExportBusy(false);
    }
  }

  const dedupControl =
    dedupAvailable ? (
      <label className={proto ? "checkbox-row" : "srse-checkbox-label"} htmlFor="hide-duplicate-records">
        <input
          id="hide-duplicate-records"
          type="checkbox"
          aria-label="Hide duplicate records"
          checked={dedupEnabled && !dedupDisabledReason}
          disabled={Boolean(dedupDisabledReason) || streaming}
          onChange={(e) => onDedupToggle(e.target.checked)}
        />
        Hide duplicate records
      </label>
    ) : null;

  const rowCountLabel: ReactNode = (
    <h4 style={{ margin: 0 }}>
      {proto ? t("lblQueryResults") : "Match results"}{" "}
      {(rows.length > 0 || totalRows != null) && (
        <span className="badge">
          <MatchRowCountCaption
            rowsLength={rows.length}
            streaming={!!streaming}
            totalRows={totalRows}
            totalRowsIsPartial={totalRowsIsPartial}
            tooManyToDisplay={tooManyToDisplay}
          />
        </span>
      )}
    </h4>
  );

  const chartBlock = showCharts && rows.length > 0 && (chartRequest || !tooManyToDisplay) ? (
    <div style={{ marginTop: chartPlacement === "above" ? 0 : "1.5rem", marginBottom: chartPlacement === "above" ? "1.5rem" : 0 }}>
      <ChartsSection
        rows={rows}
        dimensions={columns.map((c) => ({ key: c, label: labelFor(c) }))}
        getValue={(row, key) => String(row[key] ?? "")}
        getCount={() => 1}
        loadBuckets={loadChartBuckets}
        onFullRowCount={onFullRowCount}
      />
    </div>
  ) : null;

  const titleSection = proto ? rowCountLabel : (
    <>
      {rowCountLabel}
      <span className={muted} style={{ fontSize: "0.85rem", display: "block" }}>
        <MatchRowCountCaption
          rowsLength={rows.length}
          streaming={!!streaming}
          totalRows={totalRows}
          totalRowsIsPartial={totalRowsIsPartial}
          tooManyToDisplay={tooManyToDisplay}
        />
      </span>
    </>
  );

  return (
    <>
      {chartPlacement === "above" ? chartBlock : null}
      <section className={proto ? "section result-table-section" : "srse-card result-table-section"} style={{ width: "100%" }}>
        {tooManyToDisplay && (
          <TooManyRowsPanel
            totalRows={totalRows}
            totalRowsIsPartial={totalRowsIsPartial}
            displayLimit={displayLimit}
            fullCsvDownloadNote={fullCsvDownloadNote}
            exportUsesServerOrder={exportUsesServer}
          />
        )}
        {rows.length === 0 ? <p className={muted}>{t("msgNoMatchRows")}</p> : null}

        {rows.length > 0 && (
          <>
                <div className="result-table-heading">{titleSection}</div>
                <ResultTableToolbar
                  appearance={appearance}
                  rowCountLabel={null}
                  columns={columns}
                  hiddenColumnIds={hiddenColumnIds}
                  onHiddenChange={setHiddenColumnIds}
                  labelFor={labelFor}
                  streaming={streaming || exportBusy}
                  pageSize={pageSize}
                  onPageSizeChange={(size) => table.setPageSize(size)}
                  pageIndex={pageIndex}
                  pageCount={pageCount}
                  totalFilteredRows={filteredCount}
                  totalLoadedRows={rows.length}
                  onFirstPage={() => table.setPageIndex(0)}
                  onPrevPage={() => table.previousPage()}
                  onNextPage={() => table.nextPage()}
                  onLastPage={() => table.setPageIndex(Math.max(0, pageCount - 1))}
                  canPrevious={table.getCanPreviousPage()}
                  canNext={table.getCanNextPage()}
                  onDownloadCsv={() => void handleExport("csv")}
                  onDownloadExcel={() => void handleExport("xlsx")}
                  onDownloadJson={onDownloadFullExport ? () => void handleExport("json") : undefined}
                  onDownloadXml={onDownloadFullExport ? () => void handleExport("xml") : undefined}
                  exportUsesServerOrder={exportUsesServer}
                  csvHeaderHelp="CSV has no styling: row 1 is always the header (UTF-8 with BOM for Excel)."
                  dedupControl={dedupControl}
                  t={t}
                />
                {exportError && (
                  <p className="srse-query-notice" role="status">{exportError}</p>
                )}

                <div className={proto ? "results-scroll result-table-scroll" : "result-table-scroll"} style={proto ? undefined : { overflowX: "auto" }}>
                  <table className={proto ? "data-table result-data-table" : "srse-table result-data-table"}>
                    <thead className="result-table-sticky-head">
                      {table.getHeaderGroups().map((hg) => (
                        <tr key={hg.id}>
                          {hg.headers.map((header) => {
                            const sorted = header.column.getIsSorted();
                            return (
                              <th key={header.id} style={{ verticalAlign: "top" }} aria-sort={ariaSortValue(sorted)}>
                                <button
                                  type="button"
                                  className="result-sort-btn"
                                  onClick={header.column.getToggleSortingHandler()}
                                  onKeyDown={(e) => {
                                    if (e.key === "Enter" || e.key === " ") {
                                      e.preventDefault();
                                      header.column.getToggleSortingHandler()?.(e);
                                    }
                                  }}
                                  aria-label={`Sort by ${String(header.column.columnDef.header)}`}
                                >
                                  {flexRender(header.column.columnDef.header, header.getContext())}
                                  <span className="result-sort-indicator" aria-hidden>
                                    {sorted === "asc" ? "▲" : sorted === "desc" ? "▼" : "⇅"}
                                  </span>
                                </button>
                                <input
                                  id={`filter-${header.id}`}
                                  value={(header.column.getFilterValue() as string) ?? ""}
                                  onChange={(e) => header.column.setFilterValue(e.target.value)}
                                  placeholder="filter…"
                                  style={filterInputStyle(proto)}
                                  onClick={(e) => e.stopPropagation()}
                                  aria-label={`Filter ${String(header.column.columnDef.header)}`}
                                />
                              </th>
                            );
                          })}
                        </tr>
                      ))}
                    </thead>
                    <tbody>
                      {table.getRowModel().rows.map((row) => (
                        <tr
                          key={row.id}
                          style={highlightDuplicates ? { background: "var(--srse-warning-bg, #fff7e6)" } : undefined}
                        >
                          {row.getVisibleCells().map((cell) => (
                            <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                          ))}
                        </tr>
                      ))}
                      {table.getRowModel().rows.length === 0 && (
                        <tr>
                          <td colSpan={table.getVisibleFlatColumns().length} className={muted}>
                            No rows match the current filters.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
          </>
        )}

        <div style={{ marginTop: "1rem" }}>
          {!proto && (
            <button type="button" className="srse-btn srse-btn-ghost srse-btn-sm" onClick={() => setShowSql((s) => !s)}>
              {showSql ? "▾" : "▸"} Show generated SQL
            </button>
          )}
          {showSql && !proto && (
            <pre
              style={{
                marginTop: "0.5rem",
                padding: "0.75rem",
                background: "var(--srse-surface-muted, #f5f5f5)",
                border: `1px solid ${borderVar}`,
                borderRadius: radiusSm,
                fontSize: "0.8rem",
                overflowX: "auto",
                whiteSpace: "pre-wrap",
                wordBreak: "break-word",
              }}
            >
              {sql}
            </pre>
          )}
          {proto && sql && (
            <p className="desc" style={{ fontFamily: "var(--font-mono)", fontSize: 12, marginTop: 8 }}>
              {sql.slice(0, 240)}
              {sql.length > 240 ? "…" : ""}
            </p>
          )}
        </div>
      </section>

      {chartPlacement === "below" ? chartBlock : null}
    </>
  );
}
