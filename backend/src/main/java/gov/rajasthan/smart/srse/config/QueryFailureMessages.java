package gov.rajasthan.smart.srse.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Officer-facing text for a failed analytical query. Synchronous handlers and
 * the NDJSON match stream both use this so a dropped connection and a rejected
 * query read the same way on every screen.
 */
public final class QueryFailureMessages {

    private static final int MAX_CAUSE_DEPTH = 50;

    private QueryFailureMessages() {
    }

    public static String officerMessage(Throwable ex) {
        if (isConnectivityFailure(ex)) {
            return "The analytical engine is unavailable. Check the Presto connection, then try again.";
        }
        if (isTypeMismatch(ex)) {
            return "This comparison does not match the column type on this database. "
                    + "Enter a date as YYYY-MM-DD. PostgreSQL, DB2, MySQL, SQL Server, Oracle, "
                    + "and the lakehouse each compare a date only with a date.";
        }
        if (ex instanceof DataAccessException dataAccess) {
            return "Query failed: " + rootMessage(ex) + failingSql(dataAccess);
        }
        String message = ex.getMessage();
        return message != null && !message.isBlank() ? message : ex.toString();
    }

    /**
     * A comparison the engine refused because the two sides are different kinds
     * of value (a date column against text, a number against a date). Each
     * database phrases that refusal differently. It is a query the officer can
     * correct, not an outage and not an internal fault.
     */
    public static boolean isTypeMismatch(Throwable ex) {
        String text = chainText(ex);
        return text.contains("cannot be applied to")
                || text.contains("operator does not exist")
                || text.contains("inconsistent datatypes")
                || text.contains("operand type clash")
                || text.contains("conversion failed when converting")
                || text.contains("sql0401")
                || text.contains("incompatible data types")
                || text.contains("incorrect date value")
                || text.contains("ora-01861")
                || text.contains("ora-00932");
    }

    /**
     * True only for a lost, refused, or timed-out connection. A plain
     * {@link IOException} counts when its text names the transport; other I/O
     * failures stay query errors so they are not reported as an outage.
     */
    public static boolean isConnectivityFailure(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; current = current.getCause(), depth++) {
            if (current instanceof SocketException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return true;
            }
            if (current instanceof IOException && transportMessage(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static String chainText(Throwable ex) {
        StringBuilder text = new StringBuilder();
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; current = current.getCause(), depth++) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                text.append(' ').append(message.toLowerCase(Locale.ROOT));
            }
        }
        return text.toString();
    }

    private static boolean transportMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("connection")
                || text.contains("socket")
                || text.contains("broken pipe")
                || text.contains("reset")
                || text.contains("timed out")
                || text.contains("timeout");
    }

    private static String failingSql(DataAccessException ex) {
        String sql = null;
        if (ex instanceof UncategorizedSQLException uncategorized) {
            sql = uncategorized.getSql();
        } else if (ex instanceof BadSqlGrammarException badGrammar) {
            sql = badGrammar.getSql();
        }
        return sql != null && !sql.isBlank() ? " — failing query: " + sql : "";
    }

    static String rootMessage(Throwable ex) {
        Throwable root = ex;
        for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null && !message.isBlank()
                ? message
                : root.getClass().getSimpleName();
    }
}
