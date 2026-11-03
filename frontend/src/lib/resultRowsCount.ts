import { runRecordMatchStream, type RecordMatchRequest } from "@/lib/analysisApi";
import { rowCountFromMatchRow } from "@/lib/queryBuilderRequestBuild";

/** On-screen preview cap. A larger match needs an exact count. */
export const RESULT_ROW_PREVIEW_FLOOR = 10_000;

/** Exact total already stored. A 10,000 preview, or a partial lower bound, is not exact. */
export function exactResultRowTotal(
  totalRows: number | null | undefined,
  totalRowsIsPartial: boolean,
): number | null {
  if (totalRowsIsPartial || totalRows == null || totalRows <= RESULT_ROW_PREVIEW_FLOOR) return null;
  return totalRows;
}

/**
 * The card must load the real total when the screen only kept the 10,000-row preview
 * or the stored total is still a lower bound.
 */
export function resultRowsNeedExactLoad(
  totalRows: number | null | undefined,
  shownRows: number,
  totalRowsIsPartial: boolean,
): boolean {
  if (exactResultRowTotal(totalRows, totalRowsIsPartial) != null) return false;
  if (shownRows >= RESULT_ROW_PREVIEW_FLOOR) return true;
  if (totalRowsIsPartial) return true;
  return totalRows != null && shownRows > 0 && totalRows > shownRows;
}

type ExactFromChartSlot = {
  promise: Promise<number | null>;
  resolve: (total: number | null) => void;
  settled: boolean;
};

const exactFromChartSlots = new Map<string, ExactFromChartSlot>();

function exactFromChartSlot(key: string): ExactFromChartSlot {
  const existing = exactFromChartSlots.get(key);
  if (existing) return existing;
  let resolve!: (total: number | null) => void;
  const promise = new Promise<number | null>((r) => {
    resolve = (total) => {
      const slot = exactFromChartSlots.get(key);
      if (!slot || slot.settled) return;
      slot.settled = true;
      r(total);
    };
  });
  const slot: ExactFromChartSlot = { promise, resolve, settled: false };
  exactFromChartSlots.set(key, slot);
  return slot;
}

/**
 * Result Rows waits on this when the dashboard chart is the source of the exact total.
 * Resolves with the chart `totalRows`, or null if the chart failed (caller may fall back to COUNT).
 */
export function awaitExactFromChart(key: string): Promise<number | null> {
  return exactFromChartSlot(key).promise;
}

/** Chart publishes the full-match total so the Result Rows card can leave Loading. */
export function publishExactFromChart(key: string, totalRows: number | null) {
  exactFromChartSlot(key).resolve(totalRows);
}

/** Full-match COUNT(*). Returns null when the engine does not return a row count. */
export async function fetchExactMatchRowCount(
  req: RecordMatchRequest,
  signal?: AbortSignal,
): Promise<number | null> {
  const counted: RecordMatchRequest = { ...req, countOnly: true };
  delete counted.resultLimit;
  let row: Record<string, unknown> | null = null;
  let failed = false;
  await runRecordMatchStream(
    counted,
    {
      onMeta: () => undefined,
      onRow: (next) => {
        row = next;
      },
      onDone: () => undefined,
      onError: () => {
        failed = true;
      },
    },
    signal,
  );
  if (failed) return null;
  return rowCountFromMatchRow(row);
}
