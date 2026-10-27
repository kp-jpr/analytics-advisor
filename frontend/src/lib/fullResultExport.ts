/** True when the screen kept fewer rows than the match returned. */
export function resultPreviewIsTruncated(
  totalRows: number | null | undefined,
  shownRows: number,
): boolean {
  return totalRows != null && totalRows > shownRows;
}

/**
 * A stored match request always downloads from the server.
 * A truncated preview without that request must not be written out as the file.
 */
export function bufferedExportAllowed(hasMatchRequest: boolean, truncated: boolean): boolean {
  return !hasMatchRequest && !truncated;
}
