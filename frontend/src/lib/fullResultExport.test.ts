import { describe, expect, it } from "vitest";
import { bufferedExportAllowed, resultPreviewIsTruncated } from "@/lib/fullResultExport";

describe("full result export", () => {
  it("does not write the 10,000-row preview when the match request is stored", () => {
    expect(resultPreviewIsTruncated(250_000, 10_000)).toBe(true);
    expect(bufferedExportAllowed(true, true)).toBe(false);
    expect(bufferedExportAllowed(true, false)).toBe(false);
  });

  it("does not write a truncated preview when the original request is missing", () => {
    expect(bufferedExportAllowed(false, true)).toBe(false);
  });

  it("may write the buffer only for a complete small result with no stored request", () => {
    expect(resultPreviewIsTruncated(12, 12)).toBe(false);
    expect(bufferedExportAllowed(false, false)).toBe(true);
  });
});