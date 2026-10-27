import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { RecordMatchRequest } from "@/lib/analysisApi";

export type QueryResultRow = Record<string, unknown>;

export type QueryResultsPayload = {
  columns: string[];
  rows: QueryResultRow[];
  totalRows: number | null;
  /**
   * True when the stream stopped at the on-screen cap, so {@link totalRows} is a lower bound.
   * Cleared once chart grouping returns the exact match size.
   */
  totalRowsIsPartial?: boolean;
  /** Same field names the Query Builder chart uses. */
  columnLabels?: Record<string, string>;
  capturedAt: string;
  source: "extract" | "report";
  /** When set, dashboard can re-run server CSV/Excel for the full analytical result. */
  matchExportRequest?: RecordMatchRequest | null;
};

type QueryResultsState = {
  lastResult: QueryResultsPayload | null;
  setLastResult: (payload: Omit<QueryResultsPayload, "capturedAt">) => void;
  /** Replace a stopped-stream lower bound with the exact match size from chart grouping. */
  applyFullRowCount: (totalRows: number) => void;
  clear: () => void;
};

/** Carried client state for page 3 — no second /api/analysis/match call. */
export const useQueryResultsStore = create<QueryResultsState>()(
  persist(
    (set) => ({
      lastResult: null,
      setLastResult: (payload) =>
        set({
          lastResult: { ...payload, capturedAt: new Date().toISOString() },
        }),
      applyFullRowCount: (totalRows) =>
        set((state) => {
          if (!state.lastResult) return state;
          return {
            lastResult: {
              ...state.lastResult,
              totalRows,
              totalRowsIsPartial: false,
            },
          };
        }),
      clear: () => set({ lastResult: null }),
    }),
    { name: "aa-query-results-v1", partialize: (s) => ({ lastResult: s.lastResult }) },
  ),
);
