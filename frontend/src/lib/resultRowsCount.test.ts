import { describe, expect, it } from "vitest";
import {
  awaitExactFromChart,
  exactResultRowTotal,
  publishExactFromChart,
  resultRowsNeedExactLoad,
} from "@/lib/resultRowsCount";

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

  it("takes the exact total from the chart response, including when the chart finishes first", async () => {
    const waiting = awaitExactFromChart("result-a");
    let total: number | null | undefined;
    void waiting.then((value) => {
      total = value;
    });
    await Promise.resolve();
    expect(total).toBeUndefined();
    publishExactFromChart("result-a", 42_000);
    await waiting;
    expect(total).toBe(42_000);

    publishExactFromChart("result-b", 99);
    await expect(awaitExactFromChart("result-b")).resolves.toBe(99);
  });

  it("lets the Result Rows card fall back when the chart publishes null", async () => {
    publishExactFromChart("result-miss", null);
    await expect(awaitExactFromChart("result-miss")).resolves.toBeNull();
  });
});
