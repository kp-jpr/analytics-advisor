package gov.rajasthan.smart.srse.execution;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * A query timeout that belongs to one call.
 *
 * <p>The shared {@code prestoJdbcTemplate} stores a single timeout. A metadata
 * browse and a match running at the same time would otherwise overwrite each
 * other's limit. A template that has a {@link DataSource} gets its own copy.
 * A test double with no data source is timed in place so existing stubs still
 * receive the query.
 */
public final class ScopedJdbc {

    private ScopedJdbc() {
    }

    public static JdbcTemplate withTimeout(JdbcTemplate shared, int timeoutSeconds) {
        DataSource dataSource = shared.getDataSource();
        if (dataSource == null) {
            shared.setQueryTimeout(timeoutSeconds);
            return shared;
        }
        JdbcTemplate scoped = new JdbcTemplate(dataSource);
        scoped.setQueryTimeout(timeoutSeconds);
        return scoped;
    }
}
