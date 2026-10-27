// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import { act } from "react";
import { I18N } from "@/lib/i18n/catalog";
import { JoinColumnFuzzyFields } from "@/components/query-builder/ExtractRecordsTab";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.mock("@/components/shell/ShellProviders", () => {
  const t = (key: keyof typeof I18N.en) => I18N.en[key];
  return { useShell: () => ({ t, modeKey: (dual: keyof typeof I18N.en) => I18N.en[dual] }) };
});

describe("Join column fuzzy control", () => {
  let root: Root | null = null;
  let container: HTMLDivElement;

  afterEach(() => {
    act(() => {
      root?.unmount();
    });
    container?.remove();
  });

  function render(sourceColumn: string, targetColumn: string) {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    act(() => {
      root?.render(
        <JoinColumnFuzzyFields
          sourceColumn={sourceColumn}
          targetColumn={targetColumn}
          threshold={80}
          ignoreSpaces={false}
          caseSensitive={false}
          onThreshold={() => undefined}
          onIgnoreSpaces={() => undefined}
          onCaseSensitive={() => undefined}
        />,
      );
    });
  }

  it("shows Fuzzy % when a join column contains name", () => {
    render("full_name", "m_id");
    expect(document.getElementById("extract-join-fuzzy")).toBeTruthy();
    expect(document.body.textContent).toContain(I18N.en.qbJoinFuzzyHint);
  });

  it("hides Fuzzy % when both join columns are ids", () => {
    render("m_id", "account_no");
    expect(document.getElementById("extract-join-fuzzy")).toBeNull();
    expect(document.body.textContent).not.toContain(I18N.en.qbJoinFuzzyPercent);
  });
});
