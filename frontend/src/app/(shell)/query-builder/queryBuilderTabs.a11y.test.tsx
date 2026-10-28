// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import { act } from "react";
import QueryBuilderPage, { queryBuilderTabIds } from "@/app/(shell)/query-builder/page";
import { ShellProviders } from "@/components/shell/ShellProviders";
import { I18N } from "@/lib/i18n/catalog";

vi.mock("@/components/query-builder/ExtractRecordsTab", () => ({
  default: () => <div data-testid="extract-tab" />,
}));

vi.mock("@/components/query-builder/QueryBuilderSavedQueryOpen", () => ({
  QueryBuilderSavedQueryOpen: () => (
    <section role="region" aria-label="Extract saved queries">
      <select aria-label="Open saved query">
        <option value="">Open</option>
      </select>
    </section>
  ),
}));

const pipelineState = {
  dualMode: false,
  extractSucceeded: false,
  setDualMode: vi.fn(),
};

vi.mock("@/lib/queryBuilderPipelineStore", () => ({
  useQueryBuilderPipeline: (selector: (s: typeof pipelineState) => unknown) => selector(pipelineState),
}));

vi.mock("@/lib/savedQueryApi", () => ({
  listSavedQueries: vi.fn().mockResolvedValue([]),
}));

function mockMatchMedia() {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes("767"),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  });
}

function savedQueryRegionsInDom() {
  return [...document.querySelectorAll('[role="region"]')].filter((el) =>
    /saved queries/i.test(el.getAttribute("aria-label") ?? ""),
  );
}

function visibleTabPanels() {
  return [...document.querySelectorAll('[role="tabpanel"]')];
}

describe("Query Builder tab accessibility", () => {
  let root: Root | null = null;
  let container: HTMLDivElement;

  beforeEach(() => {
    mockMatchMedia();
    pipelineState.extractSucceeded = false;
    pipelineState.dualMode = false;
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

  function renderPage() {
    act(() => {
      root?.render(
        <ShellProviders>
          <QueryBuilderPage />
        </ShellProviders>,
      );
    });
  }

  it("exposes only the extract panel and one extract saved-query region by default", () => {
    renderPage();
    expect(document.querySelectorAll('[role="tabpanel"]')).toHaveLength(1);
    expect(document.getElementById(queryBuilderTabIds.PANEL_EXTRACT_ID)).toBeTruthy();
    expect(document.getElementById("qb-panel-report")).toBeNull();
    const regions = savedQueryRegionsInDom();
    expect(regions).toHaveLength(1);
    expect(regions[0]?.getAttribute("aria-label")).toBe("Extract saved queries");
    expect(document.querySelector("#qb2")).toBeNull();
  });

  it("wires the extract tab to its panel and hides Report Analysis", () => {
    renderPage();
    const extractTab = document.getElementById(queryBuilderTabIds.TAB_EXTRACT_ID);
    expect(extractTab?.getAttribute("aria-controls")).toBe(queryBuilderTabIds.PANEL_EXTRACT_ID);
    expect(extractTab?.getAttribute("aria-selected")).toBe("true");
    expect(document.querySelector('[aria-label="Report Analysis tab"]')).toBeNull();
    expect(document.body.textContent).not.toContain(I18N.en.tabReport);
    expect(document.body.textContent).not.toContain("Go to Report Analysis");
    expect(document.querySelector("#qb2")).toBeNull();
  });

  it("keeps Extract Records visible after a successful extract", () => {
    pipelineState.extractSucceeded = true;
    renderPage();
    expect(visibleTabPanels()).toHaveLength(1);
    expect(document.getElementById(queryBuilderTabIds.PANEL_EXTRACT_ID)).toBeTruthy();
    expect(document.querySelector('[aria-label="Report Analysis tab"]')).toBeNull();
    expect(document.querySelector('[aria-label="Report saved queries"]')).toBeNull();
    const regions = savedQueryRegionsInDom();
    expect(regions).toHaveLength(1);
    expect(regions[0]?.getAttribute("aria-label")).toBe("Extract saved queries");
  });
});
