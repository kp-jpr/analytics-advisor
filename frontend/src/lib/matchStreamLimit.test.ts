import { describe, expect, it } from "vitest";
import { MATCH_DISPLAY_ROW_CAP, shouldStopMatchStream } from "@/lib/matchStreamLimit";
import { isAnalyticalEngineDown, presentQueryError } from "@/lib/queryErrorText";

describe("match display cap", () => {
  it("keeps the first 10000 rows and stops the stream on the next one", () => {
    expect(shouldStopMatchStream(MATCH_DISPLAY_ROW_CAP)).toBe(false);
    expect(shouldStopMatchStream(MATCH_DISPLAY_ROW_CAP + 1)).toBe(true);
  });
});

describe("analytical engine errors", () => {
  const friendly = "The analytical engine is unavailable. Check the Presto connection, then try again.";

  it("replaces a raw connection failure and leaves a real query error", () => {
    expect(isAnalyticalEngineDown("Connection refused")).toBe(true);
    expect(presentQueryError("java.net.ConnectException: Connection refused", friendly)).toBe(friendly);
    expect(presentQueryError("Column 'bogus' cannot be resolved", friendly)).toBe(
      "Column 'bogus' cannot be resolved",
    );
    expect(
      presentQueryError(
        "Analysis service error 400: sourceDisplayColumns must have 0 to 8 entries",
        friendly,
      ),
    ).toBe("sourceDisplayColumns must have 0 to 8 entries");
  });
});
