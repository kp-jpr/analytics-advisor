import { describe, expect, it } from "vitest";
import {
  RESULT_ROW_PREVIEW_FLOOR,
  easedResultRowCount,
  exactResultRowTotal,
  resultRowsNeedCountUp,
} from "@/lib/resultRowsCount";

describe("result row count-up", () => {
  it("leaves a match of 10,000 or fewer as a static number", () => {
    expect(resultRowsNeedCountUp(248, 248, false)).toBe(false);
    expect(resultRowsNeedCountUp(10_000, 10_000, false)).toBe(false);
    expect(exactResultRowTotal(10_000, false)).toBeNull();
  });

  it("counts up when the stored total is already above the preview", () => {
    expect(resultRowsNeedCountUp(25_000, 10_000, false)).toBe(true);
    expect(exactResultRowTotal(25_000, false)).toBe(25_000);
  });

  it("counts up from the preview when the stored total is only a lower bound", () => {
    expect(resultRowsNeedCountUp(10_000, 10_000, true)).toBe(true);
    expect(exactResultRowTotal(10_000, true)).toBeNull();
  });

  it("eases from the preview floor to the total within one animation", () => {
    expect(easedResultRowCount(RESULT_ROW_PREVIEW_FLOOR, 1_000_000, 0)).toBe(RESULT_ROW_PREVIEW_FLOOR);
    expect(easedResultRowCount(RESULT_ROW_PREVIEW_FLOOR, 1_000_000, 1)).toBe(1_000_000);
    const mid = easedResultRowCount(RESULT_ROW_PREVIEW_FLOOR, 1_000_000, 0.5);
    expect(mid).toBeGreaterThan(RESULT_ROW_PREVIEW_FLOOR);
    expect(mid).toBeLessThan(1_000_000);
  });
});