package gov.rajasthan.smart.srse.web;

import gov.rajasthan.smart.srse.audit.AuditDegradedState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.dao.DataAccessException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proof-of-life for the skeleton: verifies BOTH data planes are reachable.
 *
 * GET /api/health/planes
 *   -> { operational: "up"|error, analytical: "up"|error }
 *
 * This is what the first `docker compose up` should hit to confirm the two
 * datasources are wired before any feature work begins.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DataSource operational;          // DB2 / JPA (primary)
    private final JdbcTemplate presto;             // Presto (analytical)
    private final AuditDegradedState auditDegradedState;

    public HealthController(DataSource operational,
                            @Qualifier("prestoJdbcTemplate") JdbcTemplate presto,
                            AuditDegradedState auditDegradedState) {
        this.operational = operational;
        this.presto = presto;
        this.auditDegradedState = auditDegradedState;
    }

    /**
     * Signals whether best-effort query audit writes have failed since startup (§7.3 Q6).
     */
    @GetMapping("/audit")
    public Map<String, Object> audit() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("degraded", auditDegradedState.isDegraded());
        out.put("failureCount", auditDegradedState.failureCount());
        out.put("lastFailureAt", auditDegradedState.lastFailureAt());
        return out;
    }

    @GetMapping("/planes")
    public Map<String, String> planes() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("operational", checkOperational());
        out.put("analytical", checkAnalytical());
        return out;
    }

    private String checkOperational() {
        try (var conn = operational.getConnection()) {
            return conn.isValid(3) ? "up" : "unreachable";
        } catch (SQLException e) {
            return "error: " + e.getMessage();
        }
    }

    private String checkAnalytical() {
        try {
            presto.queryForObject("SELECT 1", Integer.class);
            return "up";
        } catch (DataAccessException e) {
            return "error: " + e.getMessage();
        }
    }
}
