import { beforeEach, describe, expect, it, vi } from "vitest";

const fetchMock = vi.fn();

vi.mock("@/lib/authToken", () => ({
  authorizedFetch: (...args: unknown[]) => fetchMock(...args),
}));

import { listAnalysisColumns } from "@/lib/analysisApi";

describe("analysis column cache", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => [{ name: "m_id", dataType: "bigint" }],
    });
  });

  it("loads each table once for the life of the page", async () => {
    const ref = { catalog: "iceberg", schema: "srse", table: "cache_probe_village" };
    const first = listAnalysisColumns(ref);
    const second = listAnalysisColumns(ref);
    expect(second).toBe(first);
    await first;
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
