/** Rows kept on screen. The full result is a separate export, not a longer parse. */
export const MATCH_DISPLAY_ROW_CAP = 10_000;

/** True once the stream has passed the rows the grid will show. */
export function shouldStopMatchStream(rowsSeen: number, cap = MATCH_DISPLAY_ROW_CAP): boolean {
  return rowsSeen > cap;
}
