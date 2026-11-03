"use client";

import { useEffect, useState } from "react";
import type { RecordMatchRequest } from "@/lib/analysisApi";
import {
  awaitExactFromChart,
  exactResultRowTotal,
  fetchExactMatchRowCount,
  resultRowsNeedExactLoad,
} from "@/lib/resultRowsCount";

type ResultRowsCountProps = {
  totalRows: number | null | undefined;
  shownRows: number;
  totalRowsIsPartial: boolean;
  matchRequest: RecordMatchRequest | null;
  /**
   * When set with charts on the dashboard, the exact total comes from the chart
   * response (`totalRows`) instead of a separate COUNT query.
   */
  chartHoldKey?: string | null;
};

/**
 * Result Rows figure. A finished total is shown at once.
 * A preview of 10,000 shows Loading until the exact full-match total arrives.
 * That update stays in this card and does not render the rest of the page again.
 */
export function ResultRowsCount({
  totalRows,
  shownRows,
  totalRowsIsPartial,
  matchRequest,
  chartHoldKey = null,
}: ResultRowsCountProps) {
  const known = exactResultRowTotal(totalRows, totalRowsIsPartial);
  const needsLoad = known == null && resultRowsNeedExactLoad(totalRows, shownRows, totalRowsIsPartial) && matchRequest != null;
  const [exact, setExact] = useState<number | null>(known);

  useEffect(() => {
    if (!needsLoad || !matchRequest) return undefined;
    const controller = new AbortController();
    let cancelled = false;

    async function load() {
      const fallback = totalRows ?? shownRows;
      if (chartHoldKey) {
        const fromChart = await awaitExactFromChart(chartHoldKey);
        if (cancelled) return;
        if (fromChart != null) {
          setExact(fromChart);
          return;
        }
      }
      try {
        const count = await fetchExactMatchRowCount(matchRequest!, controller.signal);
        if (!cancelled) setExact(count ?? fallback);
      } catch {
        if (!cancelled) setExact(fallback);
      }
    }

    void load();
    return () => {
      cancelled = true;
      controller.abort();
    };
    // Keyed by the stored result. One load, then the number stays in this card.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (exact == null && needsLoad) {
    return <span className="result-rows-loading">Loading.....</span>;
  }
  return <>{(exact ?? totalRows ?? shownRows).toLocaleString()}</>;
}
