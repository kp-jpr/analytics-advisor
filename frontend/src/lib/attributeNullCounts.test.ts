import { describe, expect, it } from "vitest";
import { attributeNullCounts } from "@/lib/attributeNullCounts";

describe("attribute null counts", () => {
  it("counts null, missing, and blank cells for each attribute", () => {
    const counts = attributeNullCounts(
      ["source_district", "target_name"],
      [
        { source_district: "Jaipur", target_name: "Anita" },
        { source_district: null, target_name: "  " },
        { source_district: "", target_name: undefined },
      ],
    );
    expect(counts).toEqual([
      { column: "source_district", nulls: 2 },
      { column: "target_name", nulls: 2 },
    ]);
  });

  it("keeps a zero when an attribute has no nulls", () => {
    expect(attributeNullCounts(["source_id"], [{ source_id: "1" }, { source_id: "2" }])).toEqual([
      { column: "source_id", nulls: 0 },
    ]);
  });
});
