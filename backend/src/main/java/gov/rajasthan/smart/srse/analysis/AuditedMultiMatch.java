package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.monitoring.QueryExecutionTicket;

import java.util.List;

/**
 * The audit ticket plus the per-target plans already built for that audit row.
 * Execution reuses {@code queries} instead of planning each target again.
 */
public record AuditedMultiMatch(
        QueryExecutionTicket ticket,
        List<RecordMatchService.MatchQuery> queries) {

    public AuditedMultiMatch(QueryExecutionTicket ticket) {
        this(ticket, List.of());
    }
}
