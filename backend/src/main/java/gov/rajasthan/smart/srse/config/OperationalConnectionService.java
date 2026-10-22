package gov.rajasthan.smart.srse.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Test-and-persist edits to the Operational (DB2) connection. Deliberately
 * does NOT touch the live JPA {@code DataSource}/{@code EntityManagerFactory}
 * — {@code AnalyticalConnectionService}'s live-swap approach isn't safe here
 * (Hibernate's EMF is built once at boot) and storing the override as a row
 * in this very DB2 would be circular (see {@link ConnectionOverrideStore}'s
 * javadoc). A successful edit is persisted to the override file and takes
 * effect on the next restart.
 */
@Service
public class OperationalConnectionService {

    private final ConnectionOverrideStore overrideStore;
    private final ConnectionPoolFactory poolFactory;
    private final ConnectionProbe connectionProbe;

    @Autowired
    public OperationalConnectionService(ConnectionOverrideStore overrideStore) {
        this(overrideStore, OperationalConnectionService::buildThrowawayPool,
                OperationalConnectionService::checkValid);
    }

    /** Package-private: lets tests substitute a fake pool + probe instead of hitting real DB2. */
    OperationalConnectionService(ConnectionOverrideStore overrideStore, ConnectionPoolFactory poolFactory,
                                 ConnectionProbe connectionProbe) {
        this.overrideStore = overrideStore;
        this.poolFactory = poolFactory;
        this.connectionProbe = connectionProbe;
    }

    public void update(String jdbcUrl, String username, String password, String driverClassName) {
        DataSource candidate = null;
        try {
            // Pool construction itself can throw synchronously (e.g. Hikari's
            // PoolInitializationException on an unreachable host) — not just
            // the explicit probe() call below — so both must be inside this
            // try/catch to map to ConnectionTestFailedException instead of a
            // raw 500.
            candidate = poolFactory.create(jdbcUrl, username, password, driverClassName);
            connectionProbe.probe(candidate);
        } catch (Exception e) {
            throw new ConnectionTestFailedException("operational", e.getMessage(), e);
        } finally {
            // Always throwaway — the live JPA datasource is never touched here.
            if (candidate instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // best-effort cleanup of the test-only pool
                }
            }
        }

        Properties props = new Properties();
        props.setProperty("operational.jdbc-url", jdbcUrl);
        props.setProperty("operational.username", username);
        props.setProperty("operational.password", password);
        props.setProperty("operational.driver-class-name", driverClassName);
        overrideStore.save(props);
    }

    private static HikariDataSource buildThrowawayPool(String jdbcUrl, String username, String password,
                                                        String driverClassName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(3000);
        return new HikariDataSource(config);
    }

    /** Same isValid()-based check as {@code HealthController} — DB2 rejects a FROM-less SELECT 1. */
    private static void checkValid(DataSource ds) {
        try (Connection conn = ds.getConnection()) {
            if (!conn.isValid(3)) {
                throw new IllegalStateException("connection reported invalid");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
