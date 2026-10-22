package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.config.QueryFailureMessages;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.execution.JdbcStatements;
import gov.rajasthan.smart.srse.execution.ScopedJdbc;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Matches one hub table against N target tables as N independent two-table
 * JOINs — never an N-way join. Each sub-match reuses {@link RecordMatchService};
 * the hub is always built as {@code src} in SQL so dedup and age-filter logic
 * stay byte-identical to single-table mode. {@link HubSide} only flips merged
 * output column prefixes ({@code source_*} vs {@code target_*}) for the officer's
 * mental model; it does not change emitted SQL.
 *
 * <p>Partial failure: a target that fails planning or execution emits a
 * {@code progress} error and later targets still run (NDJSON). CSV export is
 * all-or-nothing — one failed target aborts the download.
 *
 * <p>{@code match_score_pct} values are per target and not comparable across
 * targets.
 *
 * <p>Each sub-match carries its own {@link TargetMatchSpec#joinType()} (null →
 * INNER) into {@link RecordMatchService} — outer-join predicate routing is not
 * duplicated here. Hub is always {@code src}: LEFT preserves hub rows; RIGHT
 * preserves that target's rows. Dedup (hub-only) is rejected per target when
 * that target uses RIGHT or FULL.
 */
@Service
public class MultiTargetRecordMatchService {

    private static final Logger log = LoggerFactory.getLogger(MultiTargetRecordMatchService.class);

    private final RecordMatchService recordMatchService;
    private final JdbcTemplate jdbc;
    private final GuardrailProperties guardrails;
    private final AnalysisProperties analysisProperties;
    private final ObjectMapper objectMapper;

    public MultiTargetRecordMatchService(RecordMatchService recordMatchService,
                                         @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                                         GuardrailProperties guardrails,
                                         AnalysisProperties analysisProperties,
                                         ObjectMapper objectMapper) {
        this.recordMatchService = recordMatchService;
        this.jdbc = jdbc;
        this.guardrails = guardrails;
        this.analysisProperties = analysisProperties;
        this.objectMapper = objectMapper;
    }

    public int maxTargetSets() {
        return analysisProperties.maxTargetSets();
    }

    public AnalysisLimitsResponse analysisLimits() {
        return analysisProperties.toLimitsResponse();
    }

    public StreamingResponseBody matchMulti(MultiTargetRecordMatchRequest req) {
        return matchMulti(req, null, failure -> { });
    }

    /** @param onTargetFailure told for each target that fails (reported in-band; the stream still completes). */
    public StreamingResponseBody matchMulti(
            MultiTargetRecordMatchRequest req, java.util.function.Consumer<Throwable> onTargetFailure) {
        return matchMulti(req, null, onTargetFailure);
    }

    /**
     * @param planned per-target plans from the audit pass, in target order. Null plans again.
     */
    public StreamingResponseBody matchMulti(
            MultiTargetRecordMatchRequest req,
            List<RecordMatchService.MatchQuery> planned,
            java.util.function.Consumer<Throwable> onTargetFailure) {
        QualifiedTable hubTable = validateBeforeStream(req);
        MergedLayout layout = MergedLayout.build(req, recordMatchService::isComparisonGroupFuzzy);
        return outputStream -> {
            // No per-target SQL here: `meta` is serialised and flushed before a
            // single target has been planned, so anything this line promised
            // about them could only ever be null. Each target carries its own
            // SQL on its `started` event instead.
            writeLine(outputStream, Map.of(
                    "type", "meta",
                    "columns", layout.supersetColumns(),
                    "targetCount", req.targets().size()));

            long streamStartNanos = System.nanoTime();
            long budgetMillis = analysisProperties.multiMatchBudgetSeconds() * 1000L;
            long totalRows = 0;
            List<Map<String, Object>> perTargetSummary = new ArrayList<>();

            for (int i = 0; i < req.targets().size(); i++) {
                TargetMatchSpec target = req.targets().get(i);
                if (remainingMillis(streamStartNanos, budgetMillis) <= 0) {
                    emitSkipped(outputStream, i, target.label(), perTargetSummary);
                    for (int j = i + 1; j < req.targets().size(); j++) {
                        emitSkipped(outputStream, j, req.targets().get(j).label(), perTargetSummary);
                    }
                    break;
                }

                try {
                    RecordMatchRequest single = toSingleMatch(req, target);
                    RecordMatchService.MatchQuery query = plannedQuery(planned, i, single);

                    // Planned BEFORE `started` is announced, so the event can
                    // carry this target's SQL. A target that fails planning
                    // therefore goes straight to `error` with no `started` —
                    // there is no query to show.
                    writeLine(outputStream, Map.of(
                            "type", "progress",
                            "targetIndex", i,
                            "label", target.label(),
                            "phase", "started",
                            "sql", recordMatchService.renderQueryForDisplay(query)));

                    int timeoutSeconds = timeoutForTarget(streamStartNanos, budgetMillis);
                    if (timeoutSeconds <= 0) {
                        emitSkipped(outputStream, i, target.label(), perTargetSummary);
                        continue;
                    }

                    long targetRows = streamTargetRows(outputStream, query, layout, i, timeoutSeconds);
                    totalRows += targetRows;
                    perTargetSummary.add(Map.of(
                            "label", target.label(),
                            "rows", targetRows,
                            "status", "ok"));
                    writeLine(outputStream, Map.of(
                            "type", "progress",
                            "targetIndex", i,
                            "label", target.label(),
                            "phase", "done",
                            "rows", targetRows));
                } catch (UncheckedIOException e) {
                    log.warn("Multi-target stream closed by the client; the query was cancelled");
                    throw e.getCause();
                } catch (Exception e) {
                    log.warn("Multi-target query failed for {}: {}", target.label(), e.toString());
                    onTargetFailure.accept(e);
                    String message = QueryFailureMessages.officerMessage(e);
                    perTargetSummary.add(Map.of(
                            "label", target.label(),
                            "rows", 0,
                            "status", "error",
                            "message", message));
                    writeLine(outputStream, Map.of(
                            "type", "progress",
                            "targetIndex", i,
                            "label", target.label(),
                            "phase", "error",
                            "message", message));
                }
            }

            writeLine(outputStream, Map.of(
                    "type", "done",
                    "totalRows", totalRows,
                    "perTarget", perTargetSummary));
        };
    }

    /**
     * Same merged columns as NDJSON, executed sequentially. Unlike
     * {@link #matchMulti}, any planning or execution failure aborts the response
     * so the client rejects the download rather than saving a truncated file.
     */
    public ResponseEntity<StreamingResponseBody> matchMultiCsv(MultiTargetRecordMatchRequest req) {
        return matchMultiCsv(req, null);
    }

    public ResponseEntity<StreamingResponseBody> matchMultiCsv(
            MultiTargetRecordMatchRequest req, List<RecordMatchService.MatchQuery> planned) {
        validateBeforeStream(req);
        MergedLayout layout = MergedLayout.build(req, recordMatchService::isComparisonGroupFuzzy);
        StreamingResponseBody body = outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.write('\uFEFF');
            writeCsvRow(writer, layout.supersetColumns().stream().map(Object.class::cast).toList());

            long streamStartNanos = System.nanoTime();
            long budgetMillis = analysisProperties.multiMatchBudgetSeconds() * 1000L;

            for (int i = 0; i < req.targets().size(); i++) {
                if (remainingMillis(streamStartNanos, budgetMillis) <= 0) {
                    throw new IllegalStateException("Multi-match time budget exhausted before target " + i);
                }
                TargetMatchSpec target = req.targets().get(i);
                RecordMatchRequest single = toSingleMatch(req, target);
                RecordMatchService.MatchQuery query = plannedQuery(planned, i, single);
                int timeoutSeconds = timeoutForTarget(streamStartNanos, budgetMillis);
                if (timeoutSeconds <= 0) {
                    throw new IllegalStateException("Multi-match time budget exhausted before target " + i);
                }
                JdbcTemplate executionJdbc = ScopedJdbc.withTimeout(
                        jdbcFor(query), timeoutSeconds);
                ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
                TargetRowMapper mapper = layout.mapperForTarget(i);
                executionJdbc.query(query.routedSql(), query.params().toArray(), (RowCallbackHandler) rs -> {
                    Map<String, Object> raw = rowMapper.mapRow(rs, 0);
                    try {
                        writeCsvRow(writer, layout.supersetColumns().stream()
                                .map(mapper.mapRow(raw)::get)
                                .toList());
                    } catch (IOException e) {
                        JdbcStatements.cancel(rs.getStatement());
                        throw new UncheckedIOException(e);
                    }
                });
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analysis-match-multi.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private QualifiedTable validateBeforeStream(MultiTargetRecordMatchRequest req) {
        QualifiedTable hubTable = recordMatchService.validateHubShape(req.hubCriteria(), req.hubDisplayColumns());
        if (req.targets() == null || req.targets().isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }
        if (req.targets().size() > analysisProperties.maxTargetSets()) {
            throw new IllegalArgumentException("targets must have 1 to " + analysisProperties.maxTargetSets()
                    + " entries");
        }
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 0; i < req.targets().size(); i++) {
            TargetMatchSpec t = req.targets().get(i);
            String label = t.label() == null ? "" : t.label().trim();
            if (label.isEmpty()) {
                throw new IllegalArgumentException("targets[" + i + "].label must be non-blank");
            }
            if (!labels.add(label)) {
                throw new IllegalArgumentException("target labels must be unique: " + label);
            }
        }
        if (req.dedup() != null && !req.dedup().qualifiedTable().equals(hubTable)) {
            throw new IllegalArgumentException(
                    "dedup must reference the hub table in multi-target mode, not a target table");
        }
        for (int i = 0; i < req.targets().size(); i++) {
            TargetMatchSpec target = req.targets().get(i);
            JoinType joinType = JoinType.effective(target.joinType());
            if (req.dedup() != null && (joinType == JoinType.RIGHT || joinType == JoinType.FULL)) {
                throw new IllegalArgumentException(
                        "Dedup cannot be used with target \"" + target.label() + "\" on a " + joinType
                                + " join: unmatched rows have NULL hub-side partition keys and would "
                                + "collapse into one row. Use INNER or LEFT for that target, or turn dedup off.");
            }
        }
        return hubTable;
    }

    private RecordMatchService.MatchQuery plannedQuery(
            List<RecordMatchService.MatchQuery> planned, int index, RecordMatchRequest single) {
        if (planned != null && index < planned.size() && planned.get(index) != null) {
            return planned.get(index);
        }
        return recordMatchService.planMatch(single);
    }

    private JdbcTemplate jdbcFor(RecordMatchService.MatchQuery query) {
        JdbcTemplate executionJdbc = recordMatchService.jdbcFor(query);
        return executionJdbc == null ? jdbc : executionJdbc;
    }

    private long streamTargetRows(java.io.OutputStream outputStream, RecordMatchService.MatchQuery query,
                                  MergedLayout layout, int targetIndex, int timeoutSeconds) throws IOException {
        JdbcTemplate executionJdbc = ScopedJdbc.withTimeout(jdbcFor(query), timeoutSeconds);
        ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
        TargetRowMapper mapper = layout.mapperForTarget(targetIndex);
        long[] count = {0};
        executionJdbc.query(query.routedSql(), query.params().toArray(), (RowCallbackHandler) rs -> {
            Map<String, Object> raw = rowMapper.mapRow(rs, 0);
            try {
                writeLine(outputStream, Map.of("type", "row", "data", mapper.mapRow(raw)));
            } catch (IOException e) {
                JdbcStatements.cancel(rs.getStatement());
                throw new UncheckedIOException(e);
            }
            count[0]++;
        });
        return count[0];
    }

    private void emitSkipped(java.io.OutputStream outputStream, int index, String label,
                             List<Map<String, Object>> perTargetSummary) throws IOException {
        perTargetSummary.add(Map.of(
                "label", label,
                "rows", 0,
                "status", "skipped",
                "reason", "time budget"));
        writeLine(outputStream, Map.of(
                "type", "progress",
                "targetIndex", index,
                "label", label,
                "phase", "skipped",
                "reason", "time budget"));
    }

    private static long remainingMillis(long streamStartNanos, long budgetMillis) {
        long elapsed = (System.nanoTime() - streamStartNanos) / 1_000_000;
        return budgetMillis - elapsed;
    }

    private int timeoutForTarget(long streamStartNanos, long budgetMillis) {
        long rem = remainingMillis(streamStartNanos, budgetMillis);
        if (rem <= 0) {
            return 0;
        }
        int cap = guardrails.queryTimeoutSeconds();
        return (int) Math.min(cap, Math.max(1, rem / 1000));
    }

    /** Used by analysis audit to plan each sub-match without executing (§7.3). */
    public static RecordMatchRequest toSingleMatch(MultiTargetRecordMatchRequest req, TargetMatchSpec target) {
        return new RecordMatchRequest(
                req.hubCriteria(),
                target.joinCriteria(),
                req.hubDisplayColumns(),
                target.displayColumns(),
                target.joinGroups(),
                req.highlightDuplicates(),
                req.dedup(),
                target.joinType(),
                target.comparisonGroups(),
                req.mismatchOnly());
    }

    private void writeLine(java.io.OutputStream out, Map<String, Object> payload) throws IOException {
        out.write(objectMapper.writeValueAsBytes(payload));
        out.write('\n');
        out.flush();
    }

    private static void writeCsvRow(Writer writer, List<Object> values) throws IOException {
        StringJoiner line = new StringJoiner(",");
        for (Object value : values) {
            line.add(csvField(value));
        }
        writer.write(line.toString());
        writer.write("\r\n");
    }

    private static String csvField(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf('"') < 0 && text.indexOf(',') < 0
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    static String sanitizeLabel(String label) {
        String sanitized = label.replaceAll("[^A-Za-z0-9_]", "_");
        return sanitized.isEmpty() ? "set" : sanitized;
    }

    /**
     * Superset column list and per-target row mappers. Hub SQL columns always
     * live under {@code source_*} / {@code target_*} in the raw JDBC row; output
     * names follow {@link HubSide}.
     */
    private record MergedLayout(List<String> supersetColumns, List<TargetRowMapper> targetMappers) {

        static MergedLayout build(MultiTargetRecordMatchRequest req,
                                  java.util.function.Predicate<ComparisonGroup> comparisonIsFuzzy) {
            Set<String> used = new LinkedHashSet<>();
            List<String> columns = new ArrayList<>();
            columns.add("match_set_label");
            columns.add("match_set_table");

            String hubOutPrefix = req.hubSide() == HubSide.SOURCE ? "source_" : "target_";
            List<HubColumnBinding> hubBindings = new ArrayList<>();
            for (MatchCriterion c : req.hubCriteria()) {
                String out = RecordMatchService.allocateUniqueAlias(hubOutPrefix + c.column(), used);
                used.add(out);
                columns.add(out);
                hubBindings.add(new HubColumnBinding(out, "source_" + c.column()));
            }
            for (DisplayColumn d : req.hubDisplayColumns()) {
                String baseOut = hubOutPrefix + d.column();
                if (used.contains(baseOut)) {
                    continue;
                }
                String out = RecordMatchService.allocateUniqueAlias(baseOut, used);
                used.add(out);
                columns.add(out);
                hubBindings.add(new HubColumnBinding(out, "source_" + d.column()));
            }
            // A target's groups name their own hub-side columns, which need not
            // all appear in hubCriteria. They are projected once, here, not per
            // target: a hub column a single target joins on is simply null on
            // the other targets' rows.
            for (TargetMatchSpec target : req.targets()) {
                for (MatchGroup g : target.joinGroups()) {
                    for (MatchCriterion c : g.source()) {
                        String baseOut = hubOutPrefix + c.column();
                        if (used.contains(baseOut)) {
                            continue;
                        }
                        used.add(baseOut);
                        columns.add(baseOut);
                        hubBindings.add(new HubColumnBinding(baseOut, "source_" + c.column()));
                    }
                }
            }

            // Per-target bindings are collected first and the mappers built
            // afterwards: `columns` is still growing while this loop runs, so a
            // mapper made inside it would be handed a list that was only
            // complete by accident.
            record Pending(String label, String table, List<PeerColumnBinding> bindings, String scoreOut) {
            }
            List<Pending> pending = new ArrayList<>();
            for (TargetMatchSpec target : req.targets()) {
                String sanitized = sanitizeLabel(target.label());
                String peerOutPrefix = req.hubSide() == HubSide.SOURCE
                        ? sanitized + "_target_"
                        : sanitized + "_source_";
                List<PeerColumnBinding> peerBindings = new ArrayList<>();
                for (MatchCriterion c : target.effectiveJoinColumns()) {
                    String out = RecordMatchService.allocateUniqueAlias(peerOutPrefix + c.column(), used);
                    used.add(out);
                    columns.add(out);
                    peerBindings.add(new PeerColumnBinding(out, "target_" + c.column()));
                }
                for (DisplayColumn d : target.displayColumns()) {
                    String baseOut = peerOutPrefix + d.column();
                    if (used.contains(baseOut)) {
                        continue;
                    }
                    String out = RecordMatchService.allocateUniqueAlias(baseOut, used);
                    used.add(out);
                    columns.add(out);
                    peerBindings.add(new PeerColumnBinding(out, "target_" + d.column()));
                }
                // ANY_OF groups return the same pair once per candidate column
                // that matched; these say which one did.
                for (String matchedOn : target.matchedOnColumns()) {
                    String out = RecordMatchService.allocateUniqueAlias(peerOutPrefix + matchedOn, used);
                    used.add(out);
                    columns.add(out);
                    peerBindings.add(new PeerColumnBinding(out, "target_" + matchedOn));
                }
                for (int ci = 0; ci < target.comparisonGroups().size(); ci++) {
                    String cmpPrefix = sanitized + "_cmp_" + ci + "_";
                    // score_pct exists only for a fuzzy comparison — an exact one
                    // emits no score, so declaring the column would put a
                    // permanently empty column in the grid and the CSV.
                    List<String> suffixes = comparisonIsFuzzy.test(target.comparisonGroups().get(ci))
                            ? List.of("source", "target", "match", "score_pct")
                            : List.of("source", "target", "match");
                    for (String suffix : suffixes) {
                        String sqlCol = "cmp_" + ci + "_" + suffix;
                        String out = RecordMatchService.allocateUniqueAlias(cmpPrefix + suffix, used);
                        used.add(out);
                        columns.add(out);
                        peerBindings.add(new PeerColumnBinding(out, sqlCol));
                    }
                }
                if (!target.comparisonGroups().isEmpty()
                        && JoinType.effective(target.joinType()) != JoinType.INNER) {
                    String out = RecordMatchService.allocateUniqueAlias(sanitized + "_match_status", used);
                    used.add(out);
                    columns.add(out);
                    peerBindings.add(new PeerColumnBinding(out, "match_status"));
                }
                String scoreOut = null;
                if (req.highlightDuplicates()) {
                    scoreOut = RecordMatchService.allocateUniqueAlias(sanitized + "_match_score_pct", used);
                    used.add(scoreOut);
                    columns.add(scoreOut);
                }
                pending.add(new Pending(
                        target.label(), target.qualifiedTableName(), List.copyOf(peerBindings), scoreOut));
            }

            List<String> superset = List.copyOf(columns);
            List<HubColumnBinding> hub = List.copyOf(hubBindings);
            List<TargetRowMapper> mappers = pending.stream()
                    .map(p -> new TargetRowMapper(
                            p.label(), p.table(), superset, hub, p.bindings(), p.scoreOut()))
                    .toList();
            return new MergedLayout(superset, mappers);
        }

        TargetRowMapper mapperForTarget(int index) {
            return targetMappers.get(index);
        }
    }

    private record HubColumnBinding(String outputColumn, String sqlColumn) {
    }

    private record PeerColumnBinding(String outputColumn, String sqlColumn) {
    }

    private record TargetRowMapper(
            String label,
            String qualifiedTable,
            List<String> supersetColumns,
            List<HubColumnBinding> hubBindings,
            List<PeerColumnBinding> peerBindings,
            String scoreOutputColumn) {

        Map<String, Object> mapRow(Map<String, Object> sqlRow) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String col : supersetColumns) {
                out.put(col, null);
            }
            out.put("match_set_label", label);
            out.put("match_set_table", qualifiedTable);
            for (HubColumnBinding b : hubBindings) {
                out.put(b.outputColumn(), sqlRow.get(b.sqlColumn()));
            }
            for (PeerColumnBinding b : peerBindings) {
                out.put(b.outputColumn(), sqlRow.get(b.sqlColumn()));
            }
            if (scoreOutputColumn != null) {
                out.put(scoreOutputColumn, sqlRow.get("match_score_pct"));
            }
            return out;
        }
    }
}
