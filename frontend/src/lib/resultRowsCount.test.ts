import { describe, expect, it } from "vitest";
import { exactResultRowTotal, resultRowsNeedExactLoad } from "@/lib/resultRowsCount";

describe("result row exact count", () => {
  it("shows a match of fewer than 10,000 rows immediately", () => {
    expect(resultRowsNeedExactLoad(248, 248, false)).toBe(false);
    expect(exactResultRowTotal(248, false)).toBeNull();
  });

  it("loads the exact total when the screen only kept 10,000 rows", () => {
    expect(resultRowsNeedExactLoad(10_000, 10_000, false)).toBe(true);
    expect(resultRowsNeedExactLoad(10_000, 10_000, true)).toBe(true);
    expect(exactResultRowTotal(10_000, true)).toBeNull();
  });

  it("shows a stored exact total above 10,000 without loading again", () => {
    expect(resultRowsNeedExactLoad(25_000, 10_000, false)).toBe(false);
    expect(exactResultRowTotal(25_000, false)).toBe(25_000);
  });
});
