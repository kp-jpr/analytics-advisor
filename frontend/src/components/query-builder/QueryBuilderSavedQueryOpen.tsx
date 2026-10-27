"use client";

import { useEffect, useState } from "react";
import { useShell } from "@/components/shell/ShellProviders";
import { createSavedQuery, getSavedQuery, listSavedQueries, type SavedQuerySummary } from "@/lib/savedQueryApi";
import { presentQueryError } from "@/lib/queryErrorText";
import { preserveExtractDisplayColumnsOnSave } from "@/lib/queryBuilderRequestBuild";
import { dualModeFromRequest } from "@/lib/savedQueryHydrate";
import { useQueryBuilderPipeline } from "@/lib/queryBuilderPipelineStore";

type Notice = { tone: "saved" | "info" | "error"; text: string };

/** Shown on Extract after Report opens a query and leaves this panel. */
let queuedInfo: string | null = null;

export function queueSavedQueryInfo(text: string | null) {
  queuedInfo = text;
}

function takeQueuedInfo(): string | null {
  const text = queuedInfo;
  queuedInfo = null;
  return text;
}

/** Save and reopen a query from Extract, including after a reload. */
export function QueryBuilderSavedQueryOpen({ onLoaded }: { onLoaded: () => void }) {
  const { t } = useShell();
  const [queries, setQueries] = useState<SavedQuerySummary[]>([]);
  const [name, setName] = useState("");
  const [openId, setOpenId] = useState("");
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(() => {
    const queued = takeQueuedInfo();
    return queued ? { tone: "info", text: queued } : null;
  });

  useEffect(() => {
    let cancelled = false;
    void listSavedQueries()
      .then((rows) => {
        if (!cancelled) setQueries(rows);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setQueries([]);
        const raw = err instanceof Error ? err.message : String(err);
        setNotice({ tone: "error", text: presentQueryError(raw, t("msgAnalyticalEngineDown")) });
      });
    return () => {
      cancelled = true;
    };
  }, [t]);

  async function saveQuery() {
    const trimmed = name.trim();
    if (!trimmed || saving) return;
    const state = useQueryBuilderPipeline.getState();
    const built = state.mergedRequestForApply(state.report.dedupEnabled);
    const req = built ? preserveExtractDisplayColumnsOnSave(state.extract, built) : null;
    if (!req) {
      setNotice({ tone: "error", text: t("msgQuerySaveIncomplete") });
      return;
    }
    setSaving(true);
    try {
      await createSavedQuery({ name: trimmed, request: req });
      setName("");
      setNotice({ tone: "saved", text: t("msgQuerySaved") });
      try {
        setQueries(await listSavedQueries());
      } catch {
        // The query is already stored. A failed list refresh must not replace the success message.
      }
    } catch (err: unknown) {
      const raw = err instanceof Error ? err.message : String(err);
      setNotice({ tone: "error", text: presentQueryError(raw, t("msgAnalyticalEngineDown")) });
    } finally {
      setSaving(false);
    }
  }

  return (
    <section role="region" aria-label={t("qbSavedQueriesExtract")} className="section" style={{ marginBottom: "0.75rem" }}>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "0.5rem", alignItems: "center" }}>
        <input
          type="text"
          className="srse-input"
          placeholder={t("qbSaveAs")}
          aria-label={t("qbSaveAs")}
          value={name}
          onChange={(e) => setName(e.target.value)}
          style={{ minWidth: "12rem" }}
        />
        <button
          type="button"
          className="btn"
          disabled={saving || !name.trim()}
          onClick={() => void saveQuery()}
        >
          {t("qbSaveQuery")}
        </button>
        <select
          className="srse-input"
          aria-label="Open saved query"
          value={openId}
          onChange={(e) => {
            const id = Number(e.target.value);
            setOpenId("");
            if (!id) return;
            void (async () => {
              try {
                const detail = await getSavedQuery(id);
                const dual = dualModeFromRequest(detail.request);
                useQueryBuilderPipeline.getState().loadFromMergedRequest(dual, detail.request);
                setNotice({
                  tone: "info",
                  text: `Loaded “${detail.name}”. Review Extract, then run the query.`,
                });
                onLoaded();
              } catch (err: unknown) {
                const raw = err instanceof Error ? err.message : String(err);
                setNotice({ tone: "error", text: presentQueryError(raw, t("msgAnalyticalEngineDown")) });
              }
            })();
          }}
        >
          <option value="">{t("qbOpenSaved")}</option>
          {queries.map((q) => (
            <option key={q.id} value={q.id}>
              {q.name}
              {!q.ownedByMe ? " (shared)" : ""}
            </option>
          ))}
        </select>
      </div>
      {notice?.tone === "saved" && (
        <p className="srse-save-banner" role="status">
          {notice.text}
        </p>
      )}
      {notice?.tone === "info" && (
        <p className="srse-text-muted" role="status" style={{ marginBottom: 0, fontSize: "0.85rem" }}>
          {notice.text}
        </p>
      )}
      {notice?.tone === "error" && (
        <p className="srse-text-danger" role="alert" style={{ marginBottom: 0 }}>
          {notice.text}
        </p>
      )}
    </section>
  );
}
