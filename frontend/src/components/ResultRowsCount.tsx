"use client";

import { useEffect, useState } from "react";
import type { RecordMatchRequest } from "@/lib/analysisApi";
import {
  exactResultRowTotal,
  fetchExactMatchRowCount,
  resultRowsNeedExactLoad,
} from "@/lib/resultRowsCount";

type ResultRowsCountProps = {
  totalRows: number | null | undefined;
  shownRows: number;
  totalRowsIsPartial: boolean;
  matchRequest: RecordMatchRequest | null;
};

/**
 * Result Rows figure. A finished total is shown at once.
 * A preview of 10,000 shows Loading until the exact full-match count arrives.
 * That update stays in this card and does not render the rest of the page again.
 */
export function ResultRowsCount({
  totalRows,
  shownRows,
  totalRowsIsPartial,
  matchRequest,
}: ResultRowsCountProps) {
  const known = exactResultRowTotal(totalRows, totalRowsIsPartial);
  const needsLoad = known == null && resultRowsNeedExactLoad(totalRows, shownRows, totalRowsIsPartial) && matchRequest != null;
  const [exact, setExact] = useState<number | null>(known);

  useEffect(() => {
    if (!needsLoad || !matchRequest) return undefined;
    const controller = new AbortController();
    let cancelled = false;
    void fetchExactMatchRowCount(matchRequest, controller.signal)
      .then((count) => {
        if (cancelled) return;
        setExact(count ?? totalRows ?? shownRows);
      })
      .catch(() => {
        if (!cancelled) setExact(totalRows ?? shownRows);
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
    // Keyed by the stored result. One count request, then the number stays in this card.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (exact == null && needsLoad) {
    return <span className="result-rows-loading">Loading.....</span>;
  }
  return <>{(exact ?? totalRows ?? shownRows).toLocaleString()}</>;
}
