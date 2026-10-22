package gov.rajasthan.smart.srse.web;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Read-only admin view of the two data-plane connections (Tab 2's
 * "connections" panel). Deliberately read-only — CLAUDE.md locked decision
 * #7 wires DATA_MODE / datasources once at boot via config; this never
 * changes that, it only surfaces what's already configured. Never returns
 * credentials.
 */
@RestController
@RequestMapping("/api/admin")
public class ConnectionInfoController {

    private final HikariDataSource operational;
    private final JdbcTemplate presto;

    private final String dataMode;
    private final String environmentLabelOverride;
    private final String analyticalUrl;
    private final String analyticalUsername;
    private final String analyticalDriverClassName;

    public ConnectionInfoController(
            DataSource operational,
            @Qualifier("prestoJdbcTemplate") JdbcTemplate presto,
            @Value("${srse.data-mode}") String dataMode,
            @Value("${srse.environment-label:}") String environmentLabelOverride,
            @Value("${srse.datasource.analytical.jdbc-url}") String analyticalUrl,
            @Value("${srse.datasource.analytical.username}") String analyticalUsername,
            @Value("${srse.datasource.analytical.driver-class-name}") String analyticalDriverClassName) {
        this.operational = (HikariDataSource) operational;
        this.presto = presto;
        this.dataMode = dataMode;
        this.environmentLabelOverride = environmentLabelOverride;
        this.analyticalUrl = analyticalUrl;
        this.analyticalUsername = analyticalUsername;
        this.analyticalDriverClassName = analyticalDriverClassName;
    }

    @GetMapping("/connections")
    public ConnectionsResponse connections() {
        PlaneInfo operationalInfo = new PlaneInfo(
                operational.getJdbcUrl(), operational.getUsername(),
                operational.getDriverClassName(), checkOperational());
        PlaneInfo analyticalInfo = new PlaneInfo(
                analyticalUrl, analyticalUsername, analyticalDriverClassName, checkAnalytical());
        String environmentLabel =
                EnvironmentLabelResolver.resolve(environmentLabelOverride, dataMode);
        return new ConnectionsResponse(dataMode, environmentLabel, operationalInfo, analyticalInfo);
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

    public record PlaneInfo(String jdbcUrl, String username, String driverClassName, String status) {
    }

    public record ConnectionsResponse(
            String dataMode,
            String environmentLabel,
            PlaneInfo operational,
            PlaneInfo analytical) {
    }
}
