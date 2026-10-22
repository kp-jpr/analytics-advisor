package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.execution.JdbcStatements;
import gov.rajasthan.smart.srse.execution.ScopedJdbc;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Shared Presto → row callback for every match export format (AA-18).
 * One query path; formats only differ in how each row is encoded.
 */
final class MatchExportStreamer {

    @FunctionalInterface
    interface RowSink {
        void accept(Map<String, Object> row) throws IOException;
    }

    private MatchExportStreamer() {
    }

    static void streamRows(
            JdbcTemplate jdbc,
            int queryTimeoutSeconds,
            RecordMatchService.MatchQuery query,
            RowSink sink) {
        JdbcTemplate timed = ScopedJdbc.withTimeout(jdbc, queryTimeoutSeconds);
        ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
        try {
            timed.query(query.routedSql(), query.params().toArray(), (RowCallbackHandler) rs -> {
                Map<String, Object> row = rowMapper.mapRow(rs, 0);
                try {
                    sink.accept(row);
                } catch (IOException e) {
                    JdbcStatements.cancel(rs.getStatement());
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e;
        }
    }

    static long countRows(JdbcTemplate jdbc, int queryTimeoutSeconds, RecordMatchService.MatchQuery query) {
        String countSql = "SELECT count(*) FROM (" + query.routedSql() + ") export_row_count_inner";
        JdbcTemplate timed = ScopedJdbc.withTimeout(jdbc, queryTimeoutSeconds);
        Long count = timed.queryForObject(countSql, query.params().toArray(), Long.class);
        return count == null ? 0L : count;
    }

    static void streamRowsCounting(
            JdbcTemplate jdbc,
            int queryTimeoutSeconds,
            RecordMatchService.MatchQuery query,
            RowSink sink,
            LongConsumer rowCounter) {
        long[] n = {0};
        streamRows(jdbc, queryTimeoutSeconds, query, row -> {
            sink.accept(row);
            n[0]++;
            rowCounter.accept(n[0]);
        });
    }

    static List<Object> columnValues(RecordMatchService.MatchQuery query, Map<String, Object> row) {
        return query.columns().stream().map(row::get).toList();
    }
}
