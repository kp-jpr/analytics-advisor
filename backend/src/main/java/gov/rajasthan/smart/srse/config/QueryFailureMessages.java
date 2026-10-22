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
            return "Lakehouse connection unavailable — the query could not be sent. "
                    + "Check the analytical (Presto) connection on the Admin page, then retry. "
                    + "Underlying error: " + rootMessage(ex);
        }
        if (ex instanceof DataAccessException dataAccess) {
            return "Query failed: " + rootMessage(ex) + failingSql(dataAccess);
        }
        String message = ex.getMessage();
        return message != null && !message.isBlank() ? message : ex.toString();
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
