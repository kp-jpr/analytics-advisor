package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.audit.AuditWriteFailureException;
import gov.rajasthan.smart.srse.analysis.TargetMatchSpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import gov.rajasthan.smart.srse.monitoring.ActiveQueryKind;
import gov.rajasthan.smart.srse.monitoring.QueryExecutionMonitor;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class RecordMatchController {

    private final RecordMatchService matchService;
    private final AnalysisAuditService analysisAuditService;
    private final MultiTargetRecordMatchService multiMatchService;
    private final JoinKeySuggestService joinKeySuggestService;
    private final QueryExecutionMonitor queryMonitor;

    public RecordMatchController(RecordMatchService matchService,
                                 AnalysisAuditService analysisAuditService,
                                 MultiTargetRecordMatchService multiMatchService,
                                 JoinKeySuggestService joinKeySuggestService,
                                 QueryExecutionMonitor queryMonitor) {
        this.matchService = matchService;
        this.analysisAuditService = analysisAuditService;
        this.multiMatchService = multiMatchService;
        this.joinKeySuggestService = joinKeySuggestService;
        this.queryMonitor = queryMonitor;
    }

    /** Scoped distinct values for the value-filter picker (§5.1) — audited as QUERY_EXECUTED. */
    @PostMapping("/column-values")
    public ColumnDistinctValuesService.ColumnValuesResponse columnValues(
            @RequestBody ColumnDistinctValuesService.ColumnValuesRequest req) {
        return analysisAuditService.columnValuesAudited(req);
    }

    @GetMapping("/limits")
    public AnalysisLimitsResponse limits() {
        return multiMatchService.analysisLimits();
    }

    /**
     * Streams the match result as newline-delimited JSON (one {@code meta}
     * line, then one {@code row} line per match, then a {@code done} or
     * {@code error} line) instead of one buffered JSON object — see
     * {@link RecordMatchService#match} for why.
     */
    @PostMapping(value = "/match", produces = "application/x-ndjson")
    public StreamingResponseBody match(@RequestBody RecordMatchRequest req) {
        AuditedMatchQuery audited = analysisAuditService.planMatchAudited(req);
        return queryMonitor.trackStream(
                audited.ticket(), matchService.match(audited.query(), audited.ticket().probe()::failed));
    }

    /**
     * The same match as a CSV download — the complete result, not the slice
     * the browser is willing to hold.
     *
     * <p>The grid stops rendering past its own row limit, so above it this is
     * the ONLY way to get the rows; it is deliberately not built from what the
     * screen is showing. Streamed straight from Presto, so a result far larger
     * than the tab could survive still downloads.
     */
    @PostMapping(value = "/match.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> matchCsv(@RequestBody RecordMatchRequest req) {
        return exportMatch(req, "CSV", "analysis-match.csv", "text", "csv", matchService::matchCsv);
    }

    @PostMapping(value = "/match.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> matchJson(@RequestBody RecordMatchRequest req) {
        return exportMatch(req, "JSON", "analysis-match.json", "application", "json", matchService::matchJson);
    }

    @PostMapping(value = "/match.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<StreamingResponseBody> matchXml(@RequestBody RecordMatchRequest req) {
        return exportMatch(req, "XML", "analysis-match.xml", "application", "xml", matchService::matchXml);
    }

    @PostMapping(value = "/match.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<StreamingResponseBody> matchExcel(@RequestBody RecordMatchRequest req) {
        AuditedMatchQuery audited = analysisAuditService.planMatchAudited(req);
        matchService.assertExcelExportAllowed(audited.query());
        return exportMatchWithQuery(
                req,
                audited.query(),
                "Excel",
                "analysis-match.xlsx",
                "application",
                "vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                q -> queryMonitor.trackStream(audited.ticket().withKind(ActiveQueryKind.EXPORT), matchService.matchExcel(q)));
    }

    private ResponseEntity<StreamingResponseBody> exportMatch(
            RecordMatchRequest req,
            String formatLabel,
            String filename,
            String mediaType,
            String mediaSubtype,
            java.util.function.Function<RecordMatchService.MatchQuery, StreamingResponseBody> bodyFactory) {
        AuditedMatchQuery audited = analysisAuditService.planMatchAudited(req);
        return exportMatchWithQuery(
                req,
                audited.query(),
                formatLabel,
                filename,
                mediaType,
                mediaSubtype,
                q -> queryMonitor.trackStream(
                        audited.ticket().withKind(ActiveQueryKind.EXPORT), bodyFactory.apply(q)));
    }

    private ResponseEntity<StreamingResponseBody> exportMatchWithQuery(
            RecordMatchRequest req,
            RecordMatchService.MatchQuery query,
            String formatLabel,
            String filename,
            String mediaType,
            String mediaSubtype,
            java.util.function.Function<RecordMatchService.MatchQuery, StreamingResponseBody> bodyFactory) {
        try {
            analysisAuditService.recordExport(req, query, formatLabel);
        } catch (RuntimeException ex) {
            throw new AuditWriteFailureException("Export audit row could not be written", ex);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType(mediaType, mediaSubtype, StandardCharsets.UTF_8))
                .body(bodyFactory.apply(query));
    }

    /**
     * Plans the match and returns display SQL only — same query {@link #match}
     * would execute, without running it against Presto.
     */
    @PostMapping(value = "/match.sql", produces = MediaType.TEXT_PLAIN_VALUE)
    public String matchSql(@RequestBody RecordMatchRequest req) {
        RecordMatchService.MatchQuery query = analysisAuditService.planPreviewAudited(req);
        return matchService.renderQueryForDisplay(query);
    }

    /** Aggregate comparison match rates over the full join result (not a streamed sample). */
    @PostMapping("/match/comparison-summary")
    public ComparisonSummaryResponse comparisonSummary(@RequestBody RecordMatchRequest req) throws Exception {
        AuditedMatchQuery audited = analysisAuditService.planMatchAudited(req);
        return queryMonitor.track(
                audited.ticket().withKind(ActiveQueryKind.COMPARISON_SUMMARY),
                () -> matchService.comparisonSummary(audited.query(), req));
    }

    /**
     * Join-key hints for two registered tables — metadata-only by default;
     * optional bounded overlap probe when {@code probe: true}.
     */
    @PostMapping("/suggest-keys")
    public List<JoinKeySuggestion> suggestKeys(@RequestBody SuggestJoinKeysRequest req) throws Exception {
        if (!req.probeRequested()) {
            return joinKeySuggestService.suggest(req);
        }
        String tables = req.sourceCatalog() + "." + req.sourceSchema() + "." + req.sourceTable()
                + ", " + req.targetCatalog() + "." + req.targetSchema() + "." + req.targetTable();
        return queryMonitor.trackStandalone(ActiveQueryKind.SUGGEST_KEYS, tables, () -> joinKeySuggestService.suggest(req));
    }

    /**
     * One hub table against N targets — N independent two-table JOINs merged
     * into one NDJSON stream. Partial per-target failure is reported in-band;
     * see {@link MultiTargetRecordMatchService}.
     */
    @PostMapping(value = "/match-multi", produces = "application/x-ndjson")
    public StreamingResponseBody matchMulti(@RequestBody MultiTargetRecordMatchRequest req) {
        AuditedMultiMatch audited = analysisAuditService.planMultiMatchAudited(req);
        return queryMonitor.trackStream(
                audited.ticket(),
                multiMatchService.matchMulti(req, audited.queries(), audited.ticket().probe()::failed));
    }

    /**
     * Combined multi-target CSV — all-or-nothing (unlike the NDJSON stream).
     */
    @PostMapping(value = "/match-multi.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> matchMultiCsv(@RequestBody MultiTargetRecordMatchRequest req) {
        AuditedMultiMatch audited = analysisAuditService.planMultiMatchAudited(req);
        String combinedShape = combinedMultiQueryShape(audited);
        try {
            analysisAuditService.recordMultiExport(req, combinedShape);
        } catch (RuntimeException ex) {
            throw new AuditWriteFailureException("Export audit row could not be written", ex);
        }
        ResponseEntity<StreamingResponseBody> response = multiMatchService.matchMultiCsv(req, audited.queries());
        StreamingResponseBody tracked = queryMonitor.trackStream(
                audited.ticket().withKind(ActiveQueryKind.EXPORT), response.getBody());
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(tracked);
    }

    private String combinedMultiQueryShape(AuditedMultiMatch audited) {
        java.util.StringJoiner shapes = new java.util.StringJoiner("\n---\n");
        for (RecordMatchService.MatchQuery query : audited.queries()) {
            shapes.add(query.sql());
        }
        return shapes.toString();
    }
}
