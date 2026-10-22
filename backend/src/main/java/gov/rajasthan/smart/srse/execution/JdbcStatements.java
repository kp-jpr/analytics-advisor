package gov.rajasthan.smart.srse.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;

/** Stops a JDBC statement when the client is no longer reading the result. */
public final class JdbcStatements {

    private static final Logger log = LoggerFactory.getLogger(JdbcStatements.class);

    private JdbcStatements() {
    }

    public static void cancel(Statement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException ex) {
            log.warn("Could not cancel the query: {}", ex.toString());
        }
    }
}
