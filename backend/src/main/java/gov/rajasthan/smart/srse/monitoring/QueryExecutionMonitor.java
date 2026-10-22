package gov.rajasthan.smart.srse.monitoring;

import gov.rajasthan.smart.srse.analysis.AnalysisAuditService;
import gov.rajasthan.smart.srse.audit.AuditOutcome;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.identity.AdminAccessDeniedException;
import gov.rajasthan.smart.srse.identity.AppUser;
import gov.rajasthan.smart.srse.identity.AuthenticatedUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Single choke point for active-query registration and QUERY_FINISHED audit rows.
 * Callers must not hold registry locks while Presto runs.
 */
@Component
public class QueryExecutionMonitor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionMonitor.class);

    private final ActiveQueryRegistry registry;
    private final AnalysisAuditService analysisAudit;
    private final AuthenticatedUserService users;
    private final GuardrailProperties guardrails;

    public QueryExecutionMonitor(
            ActiveQueryRegistry registry,
            @Lazy AnalysisAuditService analysisAudit,
            AuthenticatedUserService users,
            GuardrailProperties guardrails) {
        this.registry = registry;
        this.analysisAudit = analysisAudit;
        this.users = users;
        this.guardrails = guardrails;
    }

    public <T> T track(QueryExecutionTicket ticket, Callable<T> execution) throws Exception {
        boolean registered = ticket != null && registry.tryRegister(ticket);
        long start = System.nanoTime();
        try {
            T result = execution.call();
            complete(ticket, registered, start, AuditOutcome.SUCCESS, ActiveQueryState.SUCCESS);
            return result;
        } catch (Exception ex) {
            AuditOutcome outcome = ex instanceof IOException ? AuditOutcome.CANCELLED : AuditOutcome.FAILURE;
            ActiveQueryState state = outcome == AuditOutcome.CANCELLED
                    ? ActiveQueryState.CANCELLED
                    : ActiveQueryState.FAILURE;
            complete(ticket, registered, start, outcome, state);
            throw ex;
        }
    }

    public StreamingResponseBody trackStream(QueryExecutionTicket ticket, StreamingResponseBody inner) {
        return outputStream -> {
            boolean registered = ticket != null && registry.tryRegister(ticket);
            long start = System.nanoTime();
            try {
                inner.writeTo(outputStream);
                if (ticket != null && ticket.probe().hasFailed()) {
                    // reported in-band by the stream (e.g. the engine timeout): not a success
                    complete(ticket, registered, start, AuditOutcome.FAILURE, ActiveQueryState.FAILURE);
                } else {
                    complete(ticket, registered, start, AuditOutcome.SUCCESS, ActiveQueryState.SUCCESS);
                }
            } catch (IOException ex) {
                complete(ticket, registered, start, AuditOutcome.CANCELLED, ActiveQueryState.CANCELLED);
                throw ex;
            } catch (RuntimeException ex) {
                complete(ticket, registered, start, AuditOutcome.FAILURE, ActiveQueryState.FAILURE);
                throw ex;
            } catch (Error ex) {
                complete(ticket, registered, start, AuditOutcome.FAILURE, ActiveQueryState.FAILURE);
                throw ex;
            }
        };
    }

    /** Standalone analytical work with no QUERY_EXECUTED start row (estimates, suggest-keys probes). */
    public <T> T trackStandalone(ActiveQueryKind kind, String qualifiedTables, Callable<T> execution) throws Exception {
        QueryExecutionTicket ticket = analysisAudit.newStandaloneTicket(kind, qualifiedTables, resolveUsername());
        return track(ticket, execution);
    }

    public ActiveQueryRegistry.ActiveQueriesSnapshot snapshot() {
        Duration abandonedAfter = Duration.ofSeconds(guardrails.queryTimeoutSeconds() * 2L);
        return registry.snapshot(abandonedAfter);
    }

    private void complete(
            QueryExecutionTicket ticket,
            boolean registered,
            long startNanos,
            AuditOutcome auditOutcome,
            ActiveQueryState registryState) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (registered && ticket != null) {
            registry.finish(ticket.queryId(), registryState, durationMs);
        }
        if (ticket != null && ticket.writeQueryFinishedAudit()) {
            try {
                analysisAudit.recordQueryFinished(ticket, auditOutcome, durationMs);
            } catch (RuntimeException ex) {
                log.warn("Query-finished audit write failed for {}: {}", ticket.queryId(), ex.toString());
            }
        }
    }

    private String resolveUsername() {
        try {
            AppUser user = users.requireCurrentUser();
            return user.getUsername();
        } catch (AdminAccessDeniedException ex) {
            return "system";
        }
    }
}
