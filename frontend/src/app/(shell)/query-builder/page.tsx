"use client";

import { useShell } from "@/components/shell/ShellProviders";
import ExtractRecordsTab from "@/components/query-builder/ExtractRecordsTab";
import { QueryBuilderSavedQueryOpen } from "@/components/query-builder/QueryBuilderSavedQueryOpen";
import { useQueryBuilderPipeline } from "@/lib/queryBuilderPipelineStore";

const TAB_EXTRACT_ID = "qb-tab-extract";
const PANEL_EXTRACT_ID = "qb-panel-extract";

export default function QueryBuilderPage() {
  const { t } = useShell();
  const dualMode = useQueryBuilderPipeline((s) => s.dualMode);
  const setDualMode = useQueryBuilderPipeline((s) => s.setDualMode);

  return (
    <div className="page active" data-mode={dualMode ? "dual" : "single"}>
      <div className="top-action-bar">
        <div className="page-header">
          <h2>{t("p2Title")}</h2>
          <p className="sub">{t("p2Sub")}</p>
        </div>
      </div>

      <div className="tabs" role="tablist" aria-label={t("p2Title")}>
        <button
          type="button"
          role="tab"
          id={TAB_EXTRACT_ID}
          className="active"
          aria-label="Extract Records tab"
          aria-selected={true}
          aria-controls={PANEL_EXTRACT_ID}
          tabIndex={0}
        >
          {t("tabExtract")}
        </button>
      </div>

      <div id={PANEL_EXTRACT_ID} role="tabpanel" aria-labelledby={TAB_EXTRACT_ID} tabIndex={0}>
        <QueryBuilderSavedQueryOpen onLoaded={() => undefined} />
        <div id="qb1">
          <div className="mode-switch">
            <span className="mode-label">{t("lblMode")}</span>
            <button
              type="button"
              className={dualMode ? "active" : ""}
              aria-label="Dual Source mode"
              onClick={() => setDualMode(true)}
            >
              {t("modeDual")}
            </button>
            <button
              type="button"
              className={!dualMode ? "active" : ""}
              aria-label="Single Source mode"
              onClick={() => setDualMode(false)}
            >
              {t("modeSingle")}
            </button>
          </div>
          <ExtractRecordsTab />
        </div>
      </div>
    </div>
  );
}

export const queryBuilderTabIds = {
  TAB_EXTRACT_ID,
  PANEL_EXTRACT_ID,
} as const;
