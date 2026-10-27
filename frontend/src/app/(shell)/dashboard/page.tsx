"use client";

import { useCallback, useMemo } from "react";
import Link from "next/link";
import { AnalysisResultsGrid } from "@/components/AnalysisResultsGrid";
import { ChartsSection } from "@/components/ChartsSection";
import { useShell } from "@/components/shell/ShellProviders";
import { downloadRecordMatchExport, fetchChartBuckets, type MatchExportFormat } from "@/lib/analysisApi";
import { bufferedExportAllowed, resultPreviewIsTruncated } from "@/lib/fullResultExport";
import { useQueryResultsStore } from "@/lib/queryResultsStore";
import { defaultExportFilename, exportBufferedAnalyticalResult, triggerBlobDownload } from "@/lib/resultTableExport";

export default function DashboardPage() {
  const { t } = useShell();
  const lastResult = useQueryResultsStore((s) => s.lastResult);
  const truncated = resultPreviewIsTruncated(lastResult?.totalRows, lastResult?.rows.length ?? 0);

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

  const labelFor = useCallback((id: string) => id, []);

  const loadBuckets = useCallback(
    (columns: string[]) => {
      const req = lastResult?.matchExportRequest;
      if (!req) {
        return Promise.reject(new Error(
          "Run the query again to chart every row. This screen only kept the first 10,000.",
        ));
      }
      return fetchChartBuckets(req, columns);
    },
    [lastResult?.matchExportRequest],
  );

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
              <div className="num">{lastResult.totalRows ?? lastResult.rows.length}</div>
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
          </div>

          {lastResult.matchExportRequest ? (
            <ChartsSection
              title={t("secGroupCount")}
              rows={lastResult.rows}
              dimensions={lastResult.columns.map((column) => ({ key: column, label: column }))}
              getValue={(row, key) => String(row[key] ?? "")}
              getCount={() => 1}
              loadBuckets={loadBuckets}
            />
          ) : truncated ? (
            <p className="srse-query-notice" role="status">
              Run the query again to chart every row. This screen only kept the first 10,000.
            </p>
          ) : (
            <ChartsSection
              title={t("secGroupCount")}
              rows={lastResult.rows}
              dimensions={lastResult.columns.map((column) => ({ key: column, label: column }))}
              getValue={(row, key) => String(row[key] ?? "")}
              getCount={() => 1}
            />
          )}

          <AnalysisResultsGrid
            appearance="prototype"
            columns={lastResult.columns}
            rows={lastResult.rows}
            sql=""
            totalRows={lastResult.totalRows}
            totalRowsIsPartial={truncated && lastResult.totalRows == null}
            tooManyToDisplay={truncated}
            displayLimit={10000}
            highlightDuplicates={false}
            dedupAvailable={false}
            dedupEnabled={false}
            onDedupToggle={() => {}}
            showCharts={false}
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
