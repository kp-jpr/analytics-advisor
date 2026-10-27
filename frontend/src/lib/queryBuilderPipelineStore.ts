import { create } from "zustand";
import type { RecordMatchRequest } from "@/lib/analysisApi";
import {
  EMPTY_EXTRACT_CONFIG,
  EMPTY_REPORT_CONFIG,
  type ExtractConfig,
  type QueryBuilderPipelineMeta,
  type QueryExecutionSnapshot,
  type ReportConfig,
} from "@/lib/queryBuilderPipelineTypes";
import { buildExtractRequest, buildMergedRequest } from "@/lib/queryBuilderRequestBuild";
import {
  hydrateExtractConfigFromRequest,
  hydrateReportConfigFromRequest,
} from "@/lib/savedQueryHydrate";
import { valuePickerSpecSignaturesEqual } from "@/lib/valuePickerSemantics";
import type { PredicateSpecWire } from "@/lib/analysisApi";

type PipelineState = {
  dualMode: boolean;
  extract: ExtractConfig;
  report: ReportConfig;
  extractSucceeded: boolean;
  /** Request body used for the last successful Extract run (extract-stage only). */
  baseExtractRequest: RecordMatchRequest | null;
  /** Last request that produced the data currently on screen (extract or merged report). */
  lastAppliedRequest: RecordMatchRequest | null;
  extractResult: QueryExecutionSnapshot | null;
  reportResult: QueryExecutionSnapshot | null;

  setDualMode: (dualMode: boolean) => void;
  patchExtract: (patch: Partial<ExtractConfig>) => void;
  patchReport: (patch: Partial<ReportConfig>) => void;
  commitExtractSuccess: (extractRequest: RecordMatchRequest, result: QueryExecutionSnapshot) => void;
  commitReportSuccess: (mergedRequest: RecordMatchRequest, result: QueryExecutionSnapshot) => void;
  /** Chart grouping counted every match. Keep the snapshot in step with that exact total. */
  noteExactRowCount: (scope: "extract" | "report", totalRows: number) => void;
  /** Full workflow restore from a saved merged {@link RecordMatchRequest}. Does not mark extract as executed. */
  loadFromMergedRequest: (dualMode: boolean, request: RecordMatchRequest, meta?: QueryBuilderPipelineMeta) => void;
  resetWorkflow: () => void;
  mergedRequestForApply: (dedupActive: boolean) => RecordMatchRequest | null;
};

export const useQueryBuilderPipeline = create<PipelineState>((set, get) => ({
  dualMode: true,
  extract: EMPTY_EXTRACT_CONFIG,
  report: EMPTY_REPORT_CONFIG,
  extractSucceeded: false,
  baseExtractRequest: null,
  lastAppliedRequest: null,
  extractResult: null,
  reportResult: null,

  setDualMode: (dualMode) => set({ dualMode }),

  patchExtract: (patch) =>
    set((s) => {
      if (
        Object.keys(patch).length === 1 &&
        Object.prototype.hasOwnProperty.call(patch, "valuePickerSpec")
      ) {
        const nextSpec = patch.valuePickerSpec as PredicateSpecWire | null | undefined;
        if (valuePickerSpecSignaturesEqual(s.extract.valuePickerSpec, nextSpec ?? null)) {
          return s;
        }
      }
      return {
        extract: { ...s.extract, ...patch },
        extractSucceeded: false,
        baseExtractRequest: null,
        lastAppliedRequest: null,
        extractResult: null,
        reportResult: null,
      };
    }),

  patchReport: (patch) => set((s) => ({ report: { ...s.report, ...patch } })),

  commitExtractSuccess: (extractRequest, result) =>
    set((s) => ({
      extractSucceeded: true,
      baseExtractRequest: extractRequest,
      lastAppliedRequest: extractRequest,
      extractResult: result,
      reportResult: null,
      report: { ...s.report, joinType: s.extract.joinType },
    })),

  commitReportSuccess: (mergedRequest, result) =>
    set({
      lastAppliedRequest: mergedRequest,
      reportResult: result,
    }),

  noteExactRowCount: (scope, totalRows) =>
    set((s) => {
      const exact = { totalRows, totalRowsIsPartial: false as const };
      if (scope === "report" && s.reportResult) {
        return { reportResult: { ...s.reportResult, ...exact } };
      }
      if (s.extractResult) {
        return { extractResult: { ...s.extractResult, ...exact } };
      }
      if (s.reportResult) {
        return { reportResult: { ...s.reportResult, ...exact } };
      }
      return s;
    }),

  loadFromMergedRequest: (dualMode, request, meta) => {
    if (meta) {
      set({
        dualMode: meta.dualMode,
        extract: meta.extract,
        report: meta.report,
        extractSucceeded: false,
        baseExtractRequest: null,
        lastAppliedRequest: null,
        extractResult: null,
        reportResult: null,
      });
      return;
    }
    set({
      dualMode,
      extract: hydrateExtractConfigFromRequest(request),
      report: hydrateReportConfigFromRequest(request),
      extractSucceeded: false,
      baseExtractRequest: null,
      lastAppliedRequest: null,
      extractResult: null,
      reportResult: null,
    });
  },

  resetWorkflow: () =>
    set({
      extract: EMPTY_EXTRACT_CONFIG,
      report: EMPTY_REPORT_CONFIG,
      extractSucceeded: false,
      baseExtractRequest: null,
      lastAppliedRequest: null,
      extractResult: null,
      reportResult: null,
    }),

  mergedRequestForApply: (dedupActive) => {
    const { dualMode, extract, report } = get();
    const reportWithDedup: ReportConfig = {
      ...report,
      dedupEnabled: dedupActive && report.dedupEnabled,
    };
    return buildMergedRequest(dualMode, extract, reportWithDedup);
  },
}));

export function extractRequestIsBuildable(dualMode: boolean, extract: ExtractConfig): boolean {
  return buildExtractRequest(dualMode, extract) !== null;
}
