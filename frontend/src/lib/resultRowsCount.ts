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
