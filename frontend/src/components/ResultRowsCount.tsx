"use client";

import { useEffect, useRef } from "react";
import {
  RESULT_ROW_COUNT_MS,
  RESULT_ROW_PREVIEW_FLOOR,
  easedResultRowCount,
  exactResultRowTotal,
  resultRowsNeedCountUp,
} from "@/lib/resultRowsCount";

type ResultRowsCountProps = {
  totalRows: number | null | undefined;
  shownRows: number;
  totalRowsIsPartial: boolean;
};

/**
 * Result Rows figure. The number is painted once, then adjusted on this node only.
 * Later totals from charts or a full count do not render the page again.
 */
export function ResultRowsCount({
  totalRows,
  shownRows,
  totalRowsIsPartial,
}: ResultRowsCountProps) {
  const large = resultRowsNeedCountUp(totalRows, shownRows, totalRowsIsPartial);
  const initial = large ? RESULT_ROW_PREVIEW_FLOOR : (totalRows ?? shownRows);
  const nodeRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!large) return undefined;
    const exact = exactResultRowTotal(totalRows, totalRowsIsPartial);
    const node = nodeRef.current;
    if (exact == null || !node) return undefined;
    let cancelled = false;
    let frame = 0;
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduceMotion) {
      node.textContent = exact.toLocaleString();
      return undefined;
    }
    const started = performance.now();
    const tick = (now: number) => {
      if (cancelled || !nodeRef.current) return;
      const progress = (now - started) / RESULT_ROW_COUNT_MS;
      nodeRef.current.textContent = easedResultRowCount(
        RESULT_ROW_PREVIEW_FLOOR,
        exact,
        progress,
      ).toLocaleString();
      if (progress < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
    };
    // Keyed by the stored result. A later full count must not run this again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <span ref={nodeRef}>{initial.toLocaleString()}</span>;
}
