package gov.rajasthan.smart.srse.config;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns data-access failures into something an officer can act on.
 *
 * <p><b>Why this exists.</b> Nothing mapped {@link DataAccessException}, so any
 * failure reaching Presto or DB2 fell through to Spring's default handler and
 * surfaced as a bare {@code {"status":500,"error":"Internal Server Error"}}.
 * Observed for real: the Presto container OOMed, and every rule Preview then
 * returned that message. It names no cause, distinguishes a dead lakehouse from
 * a genuine bug in no way at all, and sent the reader looking for a fault in
 * the rule they had just built.
 *
 * <p><b>The distinction that matters.</b> A query that could not run because the
 * lakehouse is unreachable is an INFRASTRUCTURE outage — retryable, nobody's
 * rule is wrong, and {@code 503} says so. A query that ran and failed is a
 * different thing and stays {@code 500}, but now carries the driver's message
 * so it can actually be diagnosed instead of being anonymised away.
 *
 * <p>The connectivity test walks the cause chain rather than matching on the
 * Spring exception type, because the PrestoDB driver reports a dropped
 * connection as a generic {@code UncategorizedSQLException} wrapping a
 * {@code SocketException} — it does not map to Spring's
 * {@code DataAccessResourceFailureException} the way a well-behaved driver
 * would. Matching on the type alone would have missed the exact case this was
 * written for.
 *
 * <p>Only affects SYNCHRONOUS failures. The Analysis tab's streaming match
 * handles its own errors mid-stream (see {@code RecordMatchService}), because
 * by then the response body is already being written and the status line is long gone.
 */
@RestControllerAdvice
public class DataAccessExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> dataAccessFailure(DataAccessException ex) {
        String message = QueryFailureMessages.officerMessage(ex);
        if (QueryFailureMessages.isConnectivityFailure(ex)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiError.of("LAKEHOUSE_UNAVAILABLE", message));
        }
        if (QueryFailureMessages.isTypeMismatch(ex)) {
            return ResponseEntity.badRequest()
                    .body(ApiError.of("TYPE_MISMATCH", message));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("QUERY_FAILED", message));
    }
}
