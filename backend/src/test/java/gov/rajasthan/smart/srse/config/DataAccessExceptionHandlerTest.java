package gov.rajasthan.smart.srse.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.net.ConnectException;
import java.time.Duration;
import java.net.SocketException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAccessExceptionHandlerTest {

    private final DataAccessExceptionHandler handler = new DataAccessExceptionHandler();

    /**
     * The exact shape observed when the Presto container OOMed: the driver
     * reports a dropped connection as a generic UncategorizedSQLException
     * wrapping a SocketException — NOT as Spring's
     * DataAccessResourceFailureException. Matching on the Spring type alone
     * would miss the very case this handler exists for.
     */
    @Test
    void prestoDroppedConnectionIsServiceUnavailable() {
        SQLException sql = new SQLException("Error executing query", null, 0,
                new SocketException("Socket closed"));
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new UncategorizedSQLException("StatementCallback", "SELECT COUNT(*) FROM beneficiary", sql));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertTrue(res.getBody().message().contains("analytical engine is unavailable"), res.getBody().message());
        assertFalse(res.getBody().message().contains("Socket closed"), res.getBody().message());
    }

    @Test
    void refusedConnectionIsServiceUnavailable() {
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new DataAccessResourceFailureException("pool down", new ConnectException("Connection refused")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertTrue(res.getBody().message().contains("analytical engine is unavailable"), res.getBody().message());
        assertFalse(res.getBody().message().contains("Connection refused"), res.getBody().message());
    }

    /**
     * A query that actually RAN and was rejected is not an outage — it must stay
     * 500, but must carry the driver's message so it is diagnosable, which is
     * exactly what the previous bare "Internal Server Error" destroyed.
     */
    @Test
    void rejectedQueryStays500ButCarriesTheRealMessage() {
        SQLException sql = new SQLException("line 1:8: Column 'bogus' cannot be resolved");
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new UncategorizedSQLException("StatementCallback", "SELECT bogus FROM beneficiary", sql));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertTrue(res.getBody().message().contains("cannot be resolved"), res.getBody().message());
        assertFalse(res.getBody().message().contains("analytical engine is unavailable"), res.getBody().message());
    }

    /** A null message must not surface as the string "null". */
    @Test
    void fallsBackToTheExceptionTypeWhenThereIsNoMessage() {
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new DataAccessResourceFailureException(null, new SocketException()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals(
                "The analytical engine is unavailable. Check the Presto connection, then try again.",
                res.getBody().message());
        assertFalse(res.getBody().message().contains("null"));
    }

    /**
     * A CYCLIC cause chain must terminate. Throwable.initCause rejects
     * self-causation, but a two-node cycle is perfectly constructible — so an
     * identity check against the current node is not sufficient and the walk
     * needs a depth bound. Without one this test hangs rather than fails.
     */
    @Test
    void cyclicCauseChainDoesNotLoopForever() {
        SQLException a = new SQLException("first");
        SQLException b = new SQLException("second", a);
        a.initCause(b);   // a -> b -> a

        ResponseEntity<ApiError> res =
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> handler.dataAccessFailure(
                                new UncategorizedSQLException("task", "SELECT 1", a)));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
    }

    /**
     * The reported case: "User defined type is not supported" is a Presto
     * type-mapping failure that names neither the table nor the columns. Without
     * the SQL there is no way to tell which of Preview's two queries produced it,
     * so the team had nothing to act on.
     */
    @Test
    void queryFailureIncludesTheFailingSqlSoItCanBeDiagnosed() {
        String sql = "SELECT COUNT(*) FROM gold.beneficiary WHERE (gold.beneficiary.gender IN (?))";
        ResponseEntity<ApiError> res = handler.dataAccessFailure(new UncategorizedSQLException(
                "StatementCallback", sql, new SQLException("User defined type is not supported")));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertTrue(res.getBody().message().contains("User defined type is not supported"), res.getBody().message());
        assertTrue(res.getBody().message().contains("gold.beneficiary"), res.getBody().message());
        assertTrue(res.getBody().message().contains("failing query"), res.getBody().message());
    }

    /** Officer values are bound, so the echoed SQL exposes identifiers only — never data. */
    @Test
    void echoedSqlContainsPlaceholdersNotBoundValues() {
        String sql = "SELECT COUNT(*) FROM t WHERE t.gender IN (?) AND t.age BETWEEN ? AND ?";
        ResponseEntity<ApiError> res = handler.dataAccessFailure(new UncategorizedSQLException(
                "StatementCallback", sql, new SQLException("boom")));

        assertTrue(res.getBody().message().contains("IN (?)"), res.getBody().message());
        assertFalse(res.getBody().message().contains("FEMALE"), res.getBody().message());
    }

    /** An outage must not be buried under a SQL dump — connectivity wins. */
    @Test
    void connectivityFailureDoesNotAppendSql() {
        SQLException sql = new SQLException("Error executing query", null, 0,
                new SocketException("Socket closed"));
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new UncategorizedSQLException("cb", "SELECT COUNT(*) FROM t", sql));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertFalse(res.getBody().message().contains("failing query"), res.getBody().message());
    }

    @Test
    void typeMismatchIsAHandledBadRequestOnEveryDatabase() {
        SQLException sql = new SQLException("line 7:17: '=' cannot be applied to date, varchar(10)");
        ResponseEntity<ApiError> res = handler.dataAccessFailure(new UncategorizedSQLException(
                "StatementCallback",
                "SELECT count(*) FROM (SELECT * FROM t WHERE t.dob = ?)",
                sql));

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("TYPE_MISMATCH", res.getBody().code());
        assertTrue(res.getBody().message().contains("YYYY-MM-DD"), res.getBody().message());
        assertFalse(res.getBody().message().contains("failing query"), res.getBody().message());
        assertFalse(res.getBody().message().contains("varchar"), res.getBody().message());
    }

    @Test
    void plainIoExceptionIsAQueryFailureNotAnOutage() {
        SQLException sql = new SQLException("wrapped", new IOException("disk quota exceeded"));
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new UncategorizedSQLException("cb", "SELECT 1", sql));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals("QUERY_FAILED", res.getBody().code());
        assertTrue(res.getBody().message().contains("disk quota exceeded"), res.getBody().message());
    }

    @Test
    void transportIoExceptionStaysUnavailable() {
        SQLException sql = new SQLException("wrapped", new IOException("Connection reset"));
        ResponseEntity<ApiError> res = handler.dataAccessFailure(
                new UncategorizedSQLException("cb", "SELECT 1", sql));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals("LAKEHOUSE_UNAVAILABLE", res.getBody().code());
        assertTrue(res.getBody().requestId() != null && !res.getBody().requestId().isBlank());
    }
}
