"use client";

import { useCallback, useMemo } from "react";
import Link from "next/link";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";
import { useShell } from "@/components/shell/ShellProviders";
import { downloadRecordMatchExport, type MatchExportFormat } from "@/lib/analysisApi";
import { visibleAttributeNullCounts } from "@/lib/attributeNullCounts";
import { bufferedExportAllowed, resultPreviewIsTruncated } from "@/lib/fullResultExport";
import { useQueryResultsStore } from "@/lib/queryResultsStore";
import { defaultExportFilename, exportBufferedAnalyticalResult, triggerBlobDownload } from "@/lib/resultTableExport";

export default function DashboardPage() {
  const { t } = useShell();
  const lastResult = useQueryResultsStore((s) => s.lastResult);
  const applyFullRowCount = useQueryResultsStore((s) => s.applyFullRowCount);
  const truncated = resultPreviewIsTruncated(lastResult?.totalRows, lastResult?.rows.length ?? 0);
  const countIsPartial = Boolean(lastResult?.totalRowsIsPartial);

  const numericCols = useMemo(() => {
    if (!lastResult?.rows.length) return [] as string[];
    const { columns, rows } = lastResult;
    return columns.filter((c) =>
      rows.every((r) => {
        const v = r[c];
        if (v === null || v === undefined || v === "") return true;
        return !Number.isNaN(Number(v));
      }),
    );
  }, [lastResult]);

  const labelFor = useCallback((id: string) => {
    const stored = lastResult?.columnLabels?.[id];
    if (stored) return stored;
    return id
      .replace(/^source_/, "Source: ")
      .replace(/^target_/, "Target: ")
      .replaceAll("_", " ");
  }, [lastResult?.columnLabels]);

  const nullByAttribute = useMemo(() => {
    if (!lastResult?.rows.length) return [];
    return visibleAttributeNullCounts(lastResult.columns, lastResult.rows);
  }, [lastResult]);

  const onFullRowCount = useCallback((totalRows: number) => {
    applyFullRowCount(totalRows);
  }, [applyFullRowCount]);

  const downloadFullExport = useCallback(
    async (format: MatchExportFormat) => {
      if (!lastResult?.rows.length) {
        throw new Error("No results to export.");
      }
      const req = lastResult.matchExportRequest;
      if (req && (format === "csv" || format === "xlsx" || format === "json" || format === "xml")) {
        const blob = await downloadRecordMatchExport(req, format);
        triggerBlobDownload(
          blob,
          defaultExportFilename("dashboard-result", format === "xlsx" ? "xlsx" : format),
        );
        return;
      }
      if (!bufferedExportAllowed(Boolean(req), truncated)) {
        throw new Error(
          "Run the query again to download every row. This screen only kept the first 10,000.",
        );
      }
      if (format === "csv" || format === "xlsx") {
        await exportBufferedAnalyticalResult(
          format,
          lastResult.columns,
          lastResult.rows,
          labelFor,
          "dashboard-result",
        );
        return;
      }
      throw new Error("JSON/XML export requires the original query — open Query Builder.");
    },
    [lastResult, labelFor, truncated],
  );

  return (
    <div className="page active">
      <div className="top-action-bar">
        <div className="page-header">
          <h2>{t("p3Title")}</h2>
          <p className="sub">{t("p3Sub")}</p>
        </div>
      </div>

      {!lastResult?.rows.length ? (
        <div className="empty">{t("msgDashEmpty")}</div>
      ) : (
        <div id="dash-body">
          <div className="stat-grid">
            <div className="stat">
              <div className="num">
                {(lastResult.totalRows ?? lastResult.rows.length).toLocaleString()}
              </div>
              <div className="lbl">{t("statResultRows")}</div>
            </div>
            <div className="stat">
              <div className="num">{lastResult.columns.length}</div>
              <div className="lbl">{t("statColsSelected")}</div>
            </div>
            <div className="stat">
              <div className="num">{numericCols.length}</div>
              <div className="lbl">{t("statNumericCols")}</div>
            </div>
            {nullByAttribute.map((item) => {
              const share = lastResult.rows.length === 0
                ? 0
                : Math.round((item.nulls * 1000) / lastResult.rows.length) / 10;
              return (
                <div className="stat" key={item.column}>
                  <div className="num">{item.nulls.toLocaleString()}</div>
                  <div className="lbl">{t("statAttrNull")}</div>
                  <div className="lbl" style={{ textTransform: "none" }}>
                    {labelFor(item.column)} · {share}%
                  </div>
                </div>
              );
            })}
          </div>
          {(truncated || countIsPartial) && nullByAttribute.length > 0 ? (
            <p className="srse-query-notice" role="status" style={{ marginTop: 0 }}>
              {t("dashNullShownNote")}
            </p>
          ) : null}

          {!lastResult.matchExportRequest && (truncated || countIsPartial) ? (
            <p className="srse-query-notice" role="status">
              Run the query again to chart every row. This screen only kept the first 10,000.
            </p>
          ) : null}

          <AnalysisResultsGrid
            appearance="prototype"
            columns={lastResult.columns}
            rows={lastResult.rows}
            sql=""
            totalRows={lastResult.totalRows}
            totalRowsIsPartial={countIsPartial}
            tooManyToDisplay={truncated || countIsPartial}
            displayLimit={10000}
            highlightDuplicates={false}
            dedupAvailable={false}
            dedupEnabled={false}
            onDedupToggle={() => {}}
            showCharts
            chartPlacement="above"
            chartRequest={lastResult.matchExportRequest ?? null}
            columnLabels={lastResult.columnLabels}
            onFullRowCount={onFullRowCount}
            onDownloadFullExport={downloadFullExport}
            serverExportAvailable={Boolean(lastResult.matchExportRequest)}
          />
        </div>
      )}

      <p className="desc" style={{ marginTop: "1rem" }}>
        <Link href="/query-builder">{t("navQueryBuilder")}</Link>
      </p>
    </div>
  );
}
