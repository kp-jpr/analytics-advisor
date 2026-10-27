package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.audit.AuditActionType;
import gov.rajasthan.smart.srse.audit.AuditCaptureService;
import gov.rajasthan.smart.srse.audit.AuditEventDraft;
import gov.rajasthan.smart.srse.audit.AuditOutcome;
import gov.rajasthan.smart.srse.audit.AuditScopeSummaryService;
import gov.rajasthan.smart.srse.audit.QueryShapeAudit;
import gov.rajasthan.smart.srse.monitoring.ActiveQueryKind;
import gov.rajasthan.smart.srse.monitoring.QueryExecutionTicket;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Analysis audit choke point (§7.3 / AA-11). Records {@code query.sql()} with
 * placeholders — never {@link RecordMatchService#renderQueryForDisplay}.
 */
@Service
public class AnalysisAuditService {

    private final RecordMatchService matchService;
    private final ColumnDistinctValuesService columnValuesService;
    private final AuditCaptureService auditCapture;
    private final AuditScopeSummaryService scopeSummary;

    public AnalysisAuditService(
            RecordMatchService matchService,
            ColumnDistinctValuesService columnValuesService,
            AuditCaptureService auditCapture,
            AuditScopeSummaryService scopeSummary) {
        this.matchService = matchService;
        this.columnValuesService = columnValuesService;
        this.auditCapture = auditCapture;
        this.scopeSummary = scopeSummary;
    }

    public AuditedMatchQuery planMatchAudited(RecordMatchRequest req) {
        return planAudited(req, AuditActionType.QUERY_EXECUTED, ActiveQueryKind.MATCH);
    }

    public RecordMatchService.MatchQuery planPreviewAudited(RecordMatchRequest req) {
        try {
            // Showing SQL while the officer builds the query must not count lakehouse rows.
            // Run still uses planMatch and still applies the 50 million row ceiling.
            RecordMatchService.MatchQuery query = matchService.planMatchForSave(req);
            auditCapture.recordBestEffort(new AuditEventDraft(
                    AuditActionType.QUERY_PREVIEWED,
                    AuditOutcome.SUCCESS,
                    scopeSummary.currentActorUserId(),
                    null,
                    null,
                    QueryShapeAudit.targetTablesFrom(req),
                    QueryShapeAudit.queryShapeFromSql(query.sql()),
                    scopeSummary.summarizeCurrentOfficer()));
            return query;
        } catch (RuntimeException ex) {
            auditCapture.recordBestEffort(new AuditEventDraft(
                    AuditActionType.QUERY_REFUSED,
                    AuditOutcome.REFUSED,
                    scopeSummary.currentActorUserId(),
                    null,
                    truncate(ex.getMessage(), 512),
                    QueryShapeAudit.targetTablesFrom(req),
                    null,
                    scopeSummary.summarizeCurrentOfficer()));
            throw ex;
        }
    }

    public AuditedMultiMatch planMultiMatchAudited(MultiTargetRecordMatchRequest req) {
        QueryExecutionTicket ticket = newTicket(ActiveQueryKind.MULTI_MATCH, QueryShapeAudit.targetTablesFrom(req));
        try {
            StringJoiner shapes = new StringJoiner("\n---\n");
            List<RecordMatchService.MatchQuery> queries = new ArrayList<>();
            if (req.targets() != null) {
                for (TargetMatchSpec target : req.targets()) {
                    RecordMatchService.MatchQuery query =
                            matchService.planMatch(MultiTargetRecordMatchService.toSingleMatch(req, target));
                    queries.add(query);
                    shapes.add(query.sql());
                }
            }
            auditCapture.recordBestEffort(new AuditEventDraft(
                    AuditActionType.QUERY_EXECUTED,
                    AuditOutcome.SUCCESS,
                    ticket.actorUserId(),
                    null,
                    withQueryId("multi-target match", ticket.queryId()),
                    ticket.qualifiedTables(),
                    QueryShapeAudit.queryShapeFromSql(shapes.toString()),
                    ticket.scopeSummary()));
            return new AuditedMultiMatch(ticket, List.copyOf(queries));
        } catch (RuntimeException ex) {
            auditCapture.recordBestEffort(new AuditEventDraft(
                    AuditActionType.QUERY_REFUSED,
                    AuditOutcome.REFUSED,
                    ticket.actorUserId(),
                    null,
                    truncate(ex.getMessage(), 512),
                    ticket.qualifiedTables(),
                    null,
                    ticket.scopeSummary()));
            throw ex;
        }
    }

    public ColumnDistinctValuesService.ColumnValuesResponse columnValuesAudited(
            ColumnDistinctValuesService.ColumnValuesRequest req) {
        ColumnDistinctValuesService.PlannedColumnValues planned = columnValuesService.plan(req);
        String tables = req.catalog() + "." + req.schema() + "." + req.table();
        QueryExecutionTicket ticket = newTicket(ActiveQueryKind.COLUMN_VALUES, tables);
        long start = System.nanoTime();
        try {
            ColumnDistinctValuesService.ColumnValuesResponse response = columnValuesService.execute(planned);
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            auditCapture.recordBestEffort(new AuditEventDraft(
                    AuditActionType.QUERY_EXECUTED,
                    AuditOutcome.SUCCESS,
                    ticket.actorUserId(),
                    null,
                    withQueryId("column distinct values", ticket.queryId()),
                    tables,
                    QueryShapeAudit.queryShapeFromSql(planned.sql()),
                    ticket.scopeSummary(),
                    durationMs));
            return response;
        } catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            auditCapture.recordBestEffort(new AuditEventDraft(
                    AuditActionType.QUERY_REFUSED,
                    AuditOutcome.REFUSED,
                    ticket.actorUserId(),
                    null,
                    truncate(ex.getMessage(), 512),
                    tables,
                    QueryShapeAudit.queryShapeFromSql(planned.sql()),
                    ticket.scopeSummary(),
                    durationMs));
            throw ex;
        }
    }

    public void recordExport(RecordMatchRequest req, RecordMatchService.MatchQuery query, String formatLabel) {
        auditCapture.recordExportRequired(exportDraft(req, query, formatLabel + " export"));
    }

    public void recordExport(RecordMatchRequest req, RecordMatchService.MatchQuery query) {
        recordExport(req, query, "CSV");
    }

    public void recordMultiExport(MultiTargetRecordMatchRequest req, String combinedQueryShape) {
        auditCapture.recordExportRequired(new AuditEventDraft(
                AuditActionType.EXPORT,
                AuditOutcome.SUCCESS,
                scopeSummary.currentActorUserId(),
                null,
                "Multi-target CSV export",
                QueryShapeAudit.targetTablesFrom(req),
                QueryShapeAudit.queryShapeFromSql(combinedQueryShape),
                scopeSummary.summarizeCurrentOfficer()));
    }

    public void recordQueryFinished(QueryExecutionTicket ticket, AuditOutcome outcome, long durationMs) {
        auditCapture.recordBestEffort(new AuditEventDraft(
                AuditActionType.QUERY_FINISHED,
                outcome,
                ticket.actorUserId(),
                null,
                "queryId=" + ticket.queryId(),
                ticket.qualifiedTables(),
                null,
                ticket.scopeSummary(),
                durationMs));
    }

    public QueryExecutionTicket newStandaloneTicket(ActiveQueryKind kind, String qualifiedTables, String username) {
        return new QueryExecutionTicket(
                UUID.randomUUID().toString(),
                kind,
                qualifiedTables,
                scopeSummary.currentActorUserId(),
                username == null || username.isBlank() ? "system" : username,
                scopeSummary.summarizeCurrentOfficer(),
                false);
    }

    private AuditedMatchQuery planAudited(
            RecordMatchRequest req, AuditActionType action, ActiveQueryKind kind) {
        QueryExecutionTicket ticket = newTicket(kind, QueryShapeAudit.targetTablesFrom(req));
        try {
            RecordMatchService.MatchQuery query = matchService.planMatch(req);
            auditCapture.recordBestEffort(buildDraft(action, AuditOutcome.SUCCESS, req, query, null, ticket));
            return new AuditedMatchQuery(query, ticket);
        } catch (RuntimeException ex) {
            auditCapture.recordBestEffort(buildDraft(
                    AuditActionType.QUERY_REFUSED,
                    AuditOutcome.REFUSED,
                    req,
                    null,
                    truncate(ex.getMessage(), 512),
                    ticket));
            throw ex;
        }
    }

    private QueryExecutionTicket newTicket(ActiveQueryKind kind, String qualifiedTables) {
        return new QueryExecutionTicket(
                UUID.randomUUID().toString(),
                kind,
                qualifiedTables,
                scopeSummary.currentActorUserId(),
                currentUsername(),
                scopeSummary.summarizeCurrentOfficer(),
                true);
    }

    private AuditEventDraft exportDraft(
            RecordMatchRequest req,
            RecordMatchService.MatchQuery query,
            String detail) {
        return new AuditEventDraft(
                AuditActionType.EXPORT,
                AuditOutcome.SUCCESS,
                scopeSummary.currentActorUserId(),
                null,
                detail,
                QueryShapeAudit.targetTablesFrom(req),
                QueryShapeAudit.queryShapeFromSql(query.sql()),
                scopeSummary.summarizeCurrentOfficer());
    }

    private AuditEventDraft buildDraft(
            AuditActionType action,
            AuditOutcome outcome,
            RecordMatchRequest req,
            RecordMatchService.MatchQuery query,
            String detail,
            QueryExecutionTicket ticket) {
        return new AuditEventDraft(
                action,
                outcome,
                ticket.actorUserId(),
                null,
                withQueryId(detail, ticket.queryId()),
                ticket.qualifiedTables(),
                query == null ? null : QueryShapeAudit.queryShapeFromSql(query.sql()),
                ticket.scopeSummary());
    }

    private static String withQueryId(String detail, String queryId) {
        String suffix = "queryId=" + queryId;
        if (detail == null || detail.isBlank()) {
            return suffix;
        }
        return detail + "; " + suffix;
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return "system";
        }
        return authentication.getName();
    }

    private static String truncate(String message, int max) {
        if (message == null) {
            return null;
        }
        return message.length() <= max ? message : message.substring(0, max);
    }
}
