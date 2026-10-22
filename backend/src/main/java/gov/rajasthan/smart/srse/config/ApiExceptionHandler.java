package gov.rajasthan.smart.srse.config;

import gov.rajasthan.smart.srse.audit.AuditWriteFailureException;
import gov.rajasthan.smart.srse.identity.AdminAccessDeniedException;
import gov.rajasthan.smart.srse.savedquery.SavedQueryPayloadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain validation and configuration errors to HTTP status codes for the
 * surviving API surface (Analysis, lakehouse, connections).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Malformed predicate values (e.g. BETWEEN with != 2 bounds, a
     * FUZZY_MATCH threshold outside 0..100) — compiler-level validation
     * errors, not server bugs. Previously unmapped and fell through to a
     * generic 500; found via live testing of the FUZZY_MATCH threshold guard.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION", ex.getMessage()));
    }

    @ExceptionHandler(SavedQueryPayloadException.class)
    public ResponseEntity<ApiError> savedQueryPayload(SavedQueryPayloadException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(AdminAccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AdminAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", ex.getMessage()));
    }

    /** Export audit row must commit before streaming — refusal if the write fails (§7.3 Q6). */
    @ExceptionHandler(AuditWriteFailureException.class)
    public ResponseEntity<ApiError> auditWriteFailed(AuditWriteFailureException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("AUDIT_UNAVAILABLE", ex.getMessage()));
    }
}
