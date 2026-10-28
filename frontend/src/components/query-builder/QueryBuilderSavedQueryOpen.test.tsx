// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import { act } from "react";
import { I18N } from "@/lib/i18n/catalog";
import { useQueryBuilderPipeline } from "@/lib/queryBuilderPipelineStore";
import {
  QueryBuilderSavedQueryOpen,
  queueSavedQueryInfo,
} from "@/components/query-builder/QueryBuilderSavedQueryOpen";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const listSavedQueries = vi.fn();
const createSavedQuery = vi.fn();
const getSavedQuery = vi.fn();

vi.mock("@/components/shell/ShellProviders", () => {
  const t = (key: keyof typeof I18N.en) => I18N.en[key];
  return { useShell: () => ({ t }) };
});

vi.mock("@/lib/savedQueryApi", () => ({
  listSavedQueries: (...args: unknown[]) => listSavedQueries(...args),
  createSavedQuery: (...args: unknown[]) => createSavedQuery(...args),
  getSavedQuery: (...args: unknown[]) => getSavedQuery(...args),
}));

function savedSummary(name: string) {
  return {
    id: 7,
    ownerUserId: 1,
    name,
    description: null,
    updatedAt: "2026-10-27T00:00:00Z",
    ownedByMe: true,
  };
}

describe("Query builder save and open", () => {
  let root: Root | null = null;
  let container: HTMLDivElement;

  beforeEach(() => {
    queueSavedQueryInfo(null);
    listSavedQueries.mockReset();
    createSavedQuery.mockReset();
    getSavedQuery.mockReset();
    listSavedQueries.mockResolvedValue([]);
    useQueryBuilderPipeline.getState().resetWorkflow();
    useQueryBuilderPipeline.getState().setDualMode(false);
    useQueryBuilderPipeline.getState().patchExtract({
      sourceRef: { catalog: "iceberg", schema: "srse", table: "village" },
      displayCols: ["m_id"],
    });
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => {
      root?.unmount();
    });
    container.remove();
  });

  function setInputValue(value: string) {
    const input = document.querySelector("input") as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")?.set;
    setter?.call(input, value);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  }

  async function renderBar() {
    await act(async () => {
      root?.render(<QueryBuilderSavedQueryOpen onLoaded={() => undefined} />);
    });
  }

  it("shows a success message and lists the query after save", async () => {
    createSavedQuery.mockResolvedValue(savedSummary("Village match"));
    listSavedQueries
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([savedSummary("Village match")]);
    await renderBar();

    await act(async () => {
      setInputValue("Village match");
    });
    const button = [...document.querySelectorAll("button")].find(
      (el) => el.textContent?.trim() === I18N.en.qbSaveQuery,
    ) as HTMLButtonElement;
    await act(async () => {
      button.click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(createSavedQuery).toHaveBeenCalledOnce();
    expect(document.querySelector(".srse-save-banner")?.textContent).toBe(I18N.en.msgQuerySaved);
    expect(document.querySelector('[role="status"]')?.textContent).toBe(
      "Query has been saved successfully.",
    );
    const savedOption = [...document.querySelectorAll("option")].find(
      (el) => el.textContent?.trim() === "Village match",
    );
    expect(savedOption).toBeTruthy();
  });

  it("keeps the success message when the saved-query list cannot refresh", async () => {
    createSavedQuery.mockResolvedValue(savedSummary("Village match"));
    listSavedQueries.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error("list failed"));
    await renderBar();

    await act(async () => {
      setInputValue("Village match");
    });
    const button = [...document.querySelectorAll("button")].find(
      (el) => el.textContent?.trim() === I18N.en.qbSaveQuery,
    ) as HTMLButtonElement;
    await act(async () => {
      button.click();
    });

    expect(document.body.textContent).toContain("Query has been saved successfully.");
    expect(document.body.textContent).not.toContain("list failed");
  });

  it("does not claim success when the query is not ready to save", async () => {
    useQueryBuilderPipeline.getState().patchExtract({
      sourceRef: { catalog: "", schema: "", table: "" },
      displayCols: [],
    });
    await renderBar();
    await act(async () => {
      setInputValue("Incomplete");
    });
    const button = [...document.querySelectorAll("button")].find(
      (el) => el.textContent?.trim() === I18N.en.qbSaveQuery,
    ) as HTMLButtonElement;
    await act(async () => {
      button.click();
    });

    expect(createSavedQuery).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain(I18N.en.msgQuerySaveIncomplete);
    expect(document.body.textContent).not.toContain(I18N.en.msgQuerySaved);
  });

  it("opens a saved query into the builder", async () => {
    listSavedQueries.mockResolvedValue([savedSummary("Village match")]);
    getSavedQuery.mockResolvedValue({
      ...savedSummary("Village match"),
      createdAt: "2026-10-27T00:00:00Z",
      request: {
        sourceCriteria: [
          { catalog: "iceberg", schema: "srse", table: "beneficiary", column: "m_id", fuzzyThresholdPercent: null },
        ],
        targetCriteria: [
          { catalog: "iceberg", schema: "srse", table: "bank_txn", column: "m_id", fuzzyThresholdPercent: null },
        ],
        sourceDisplayColumns: [
          { catalog: "iceberg", schema: "srse", table: "beneficiary", column: "district" },
        ],
        targetDisplayColumns: [],
        singleSource: false,
      },
    });
    await renderBar();

    const select = document.querySelector("select") as HTMLSelectElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, "value")?.set;
      setter?.call(select, "7");
      select.dispatchEvent(new Event("change", { bubbles: true }));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(getSavedQuery).toHaveBeenCalledWith(7);
    const extract = useQueryBuilderPipeline.getState().extract;
    expect(extract.sourceRef).toEqual({ catalog: "iceberg", schema: "srse", table: "beneficiary" });
    expect(extract.joinKeySource).toBe("m_id");
    expect(extract.displayCols).toEqual(["district"]);
    expect(document.body.textContent).toContain("Loaded “Village match”");
  });

  it("shows a loader while a saved query is opening and hides it when loaded", async () => {
    listSavedQueries.mockResolvedValue([savedSummary("Village match")]);
    let resolveQuery: (value: unknown) => void = () => undefined;
    getSavedQuery.mockReturnValue(
      new Promise((resolve) => {
        resolveQuery = resolve;
      }),
    );
    await renderBar();

    const select = document.querySelector("select") as HTMLSelectElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, "value")?.set;
      setter?.call(select, "7");
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });

    expect(document.body.textContent).toContain("Loading saved query…");
    expect(select.disabled).toBe(true);
    expect(select.getAttribute("aria-busy")).toBe("true");
    expect(document.querySelector(".srse-inline-spinner")).toBeTruthy();

    await act(async () => {
      resolveQuery({
        ...savedSummary("Village match"),
        createdAt: "2026-10-27T00:00:00Z",
        request: {
          sourceCriteria: [
            { catalog: "iceberg", schema: "srse", table: "beneficiary", column: "m_id", fuzzyThresholdPercent: null },
          ],
          targetCriteria: [
            { catalog: "iceberg", schema: "srse", table: "bank_txn", column: "m_id", fuzzyThresholdPercent: null },
          ],
          sourceDisplayColumns: [
            { catalog: "iceberg", schema: "srse", table: "beneficiary", column: "district" },
          ],
          targetDisplayColumns: [],
          singleSource: false,
        },
      });
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(document.body.textContent).not.toContain("Loading saved query…");
    expect(document.body.textContent).toContain("Loaded “Village match”");
    expect(select.disabled).toBe(false);
  });
});
