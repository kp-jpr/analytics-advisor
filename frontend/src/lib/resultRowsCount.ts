/** On-screen preview cap. A larger match counts up from this floor after paint. */
export const RESULT_ROW_PREVIEW_FLOOR = 10_000;

/** Count-up duration. One eased step, not one tick per row. */
export const RESULT_ROW_COUNT_MS = 400;

/**
 * A match is larger than the preview when the stored total already is,
 * or when the screen is holding the 10,000-row preview and that total is still a lower bound.
 */
export function resultRowsNeedCountUp(
  totalRows: number | null | undefined,
  shownRows: number,
  totalRowsIsPartial: boolean,
): boolean {
  if (totalRows != null && totalRows > RESULT_ROW_PREVIEW_FLOOR) return true;
  return shownRows >= RESULT_ROW_PREVIEW_FLOOR && totalRowsIsPartial;
}

/** Exact total already in hand. A partial lower bound is not exact. */
export function exactResultRowTotal(
  totalRows: number | null | undefined,
  totalRowsIsPartial: boolean,
): number | null {
  if (totalRowsIsPartial || totalRows == null || totalRows <= RESULT_ROW_PREVIEW_FLOOR) return null;
  return totalRows;
}

/** Ease-out so the last frames settle on the total. `t` is 0..1 progress. */
export function easedResultRowCount(from: number, to: number, t: number): number {
  const clamped = Math.min(1, Math.max(0, t));
  const eased = 1 - (1 - clamped) ** 3;
  return Math.round(from + (to - from) * eased);
}
