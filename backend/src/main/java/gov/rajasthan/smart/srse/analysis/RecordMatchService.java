package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.compiler.ColumnGroupSql;
import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.CompiledQuery;
import gov.rajasthan.smart.srse.compiler.FuzzyMatchSql;
import gov.rajasthan.smart.srse.compiler.FuzzyOptions;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.compiler.TypeCoercion;
import gov.rajasthan.smart.srse.config.QueryFailureMessages;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.execution.JdbcStatements;
import gov.rajasthan.smart.srse.execution.ScopedJdbc;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRoute;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRouter;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseIdentifiers;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Cross-table fuzzy/exact record matching for the Analysis tab.
 *
 * CONTRACT (do not violate):
 *  - This is a deliberate, isolated exception to the Rule Engine's "never
 *    JOIN, only pre-materialized flat-catalogue fields" rule (CLAUDE.md) —
 *    Source/Target table+column identifiers are NOT routed through
 *    {@code RuleCompiler}; every one is fully qualified
 *    ({@code catalog.schema.table.column}) and passes
 *    {@link LakehouseRegistryService}'s two gates before reaching SQL text —
 *    the table must be admin-REGISTERED, and the column must exist in the
 *    LIVE lakehouse and not be hidden. Neither gate alone suffices: the
 *    registry is a snapshot of intent and can name a since-dropped table,
 *    while live introspection alone would let an officer reach any table on
 *    the cluster. Only VALUES (thresholds, fuzzy percentages) are bound parameters.
 *  - Fuzzy-vs-exact per criterion pair is decided by {@link AnalysisColumnMetadataRepository}
 *    (admin-registered override) first, falling back to a name-substring
 *    guess when neither side is registered — see {@link #isFuzzyMatchable}.
 *  - The two sides of a pair are rarely the same physical type, and Presto
 *    does not coerce across type families: an account number held varchar in
 *    one table and bigint in another failed the whole query with "'=' cannot
 *    be applied to varchar, bigint". Every pair therefore goes through
 *    {@code TypeCoercion} with the columns' LIVE types (gathered by the same
 *    registry call that gates them) and the admin's per-column
 *    {@code CompareAs} setting — see {@link #planPairs}.
 *  - Read-only. No write/DELETE capability. "Dedup" is view-only — it
 *    collapses duplicate rows in the returned grid, never touches the
 *    lakehouse.
 *  - The join runs across the FULL source/target tables — no row cap, no
 *    input sampling. An earlier version pre-sampled each side to a top-500
 *    slice before a CROSS JOIN; at real (crore-scale) row counts that made
 *    matches nearly impossible to find (an arbitrary ~0.0005% slice per
 *    side), silently. Instead, every criterion pair becomes part of the
 *    JOIN's ON clause: exact pairs join on equality directly; fuzzy pairs
 *    join on a configurable case-insensitive prefix ("blocking key" — standard
 *    record-linkage technique; {@link AnalysisProperties#blockingPrefixLen()}),
 *    with the real Levenshtein-similarity check applied afterward as a WHERE
 *    filter only within already-blocked candidate pairs. This trades a small,
 *    documented amount of recall (a typo in the first N characters of a fuzzy
 *    column can be missed) for the join being a real
 *    hash join Presto can execute at scale, instead of a nested-loop cross
 *    product over a token sample.
 *  - There is no output row cap either (removed {@code SRSE_ANALYSIS_ROW_CAP}
 *    deliberately) — the only remaining safety net against a runaway/badly
 *    blocked match is {@link GuardrailProperties#queryTimeoutSeconds()}.
 *    Results stream to the client as they're produced (see below), so a
 *    timeout mid-match still leaves whatever rows already streamed visible.
 *    The browser stops RENDERING past its own limit and offers the result as
 *    a download instead; that limit is a display decision and lives there, not
 *    here. {@link #matchCsv} is what makes it safe to draw — the rows past the
 *    limit are still reachable, as a file, uncapped.
 *  - {@link #match} returns a {@link StreamingResponseBody}: request
 *    validation and SQL/param construction happen synchronously (so bad
 *    requests still fail fast with a normal exception before any response
 *    is written), but the JDBC query itself executes lazily, inside the
 *    body-writing callback, streaming one newline-delimited JSON line per
 *    row via {@link RowCallbackHandler} — never materializing the full
 *    result as an in-memory list.
 */
@Service
public class RecordMatchService {

    private static final Logger log = LoggerFactory.getLogger(RecordMatchService.class);

    private static final int MAX_CRITERIA_PER_SIDE = 8;
    private static final long CATALOG_STATS_TTL_MS = 10L * 60L * 1000L;
    /** Independent SHOW STATS and count queries share this pool. Daemon threads so tests can exit. */
    private static final ExecutorService ESTIMATE_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "srse-match-estimate");
        thread.setDaemon(true);
        return thread;
    });

    private final JdbcTemplate jdbc;
    private final LakehouseRegistryService registry;
    private final GuardrailProperties guardrails;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final AnalysisProperties analysisProperties;
    private final ObjectMapper objectMapper;
    private final AnalysisScopeFromService scopeFrom;
    private final RuleCompiler ruleCompiler;

    @Autowired(required = false)
    private AnalysisExecutionRouter executionRouter;

    @Autowired(required = false)
    private gov.rajasthan.smart.srse.monitoring.QueryExecutionMonitor queryMonitor;

    private final ConcurrentHashMap<String, CachedCatalogStats> catalogStatsCache = new ConcurrentHashMap<>();

    public RecordMatchService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                              LakehouseRegistryService registry,
                              GuardrailProperties guardrails,
                              AnalysisColumnMetadataRepository columnMetadata,
                              AnalysisProperties analysisProperties,
                              ObjectMapper objectMapper,
                              AnalysisScopeFromService scopeFrom,
                              RuleCompiler ruleCompiler) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.guardrails = guardrails;
        this.columnMetadata = columnMetadata;
        this.analysisProperties = analysisProperties;
        this.objectMapper = objectMapper;
        this.scopeFrom = scopeFrom;
        this.ruleCompiler = ruleCompiler;
    }

    /**
     * Validates the request and builds the Presto match query without executing it.
     * A join also counts rows so a match above the ceiling is refused before it runs.
     */
    public MatchQuery planMatch(RecordMatchRequest req) {
        PlanTrace trace = new PlanTrace();
        trace.tables = req.singleSource() ? 1 : 2 + req.additionalJoins().size();
        trace.joins = joinLabel(req);
        PLAN_TRACE.set(trace);
        long started = System.nanoTime();
        try {
            MatchQuery query = planMatchBody(req, true);
            trace.planMs = elapsedMs(started);
            return query;
        } catch (RuntimeException ex) {
            PLAN_TRACE.remove();
            throw ex;
        }
    }

    /**
     * Same checks and SQL as {@link #planMatch}, without the live row-count ceiling.
     * Saving stores the definition; the ceiling runs when the officer runs the query.
     */
    public MatchQuery planMatchForSave(RecordMatchRequest req) {
        return planMatchBody(req, false);
    }

    private MatchQuery planMatchBody(RecordMatchRequest req, boolean estimateRows) {
        validateExtractRunOptions(req);
        MatchQuery query;
        if (req.singleSource()) {
            if (!req.additionalJoins().isEmpty()) {
                throw new IllegalArgumentException(
                        "Additional tables require a source and destination join");
            }
            query = route(buildSingleSourceQuery(req), List.of(tableForSingleSource(req)));
        } else {
            JoinPlan join = normalizeJoin(req);
            Sides sides = validateRequest(req, join);
            List<QualifiedTable> referenced = new ArrayList<>();
            referenced.add(sides.sourceTable());
            referenced.add(sides.targetTable());
            for (AdditionalJoin extra : req.additionalJoins()) {
                referenced.add(extra.qualifiedTable());
            }
            query = route(buildMatchQuery(req, join, sides, estimateRows), referenced);
        }
        return applyExtractRunModifiers(query, req);
    }

    private void validateExtractRunOptions(RecordMatchRequest req) {
        if (req.resultLimit() != null && req.resultLimit() > 0
                && req.resultLimit() > analysisProperties.maxExtractResultLimit()) {
            throw new IllegalArgumentException(
                    "resultLimit exceeds the maximum of " + analysisProperties.maxExtractResultLimit());
        }
        if (req.countOnly() && req.grouped()) {
            throw new IllegalArgumentException("countOnly cannot be combined with group-by aggregates");
        }
    }

    private MatchQuery applyExtractRunModifiers(MatchQuery query, RecordMatchRequest req) {
        if (!req.countOnly() && (req.resultLimit() == null || req.resultLimit() <= 0)) {
            return query;
        }
        String sql = query.routedSql();
        List<Object> params = new ArrayList<>(query.params());
        List<String> columns = new ArrayList<>(query.columns());
        if (req.countOnly()) {
            sql = "SELECT COUNT(*) AS row_count FROM (" + sql + ") __cnt";
            columns = List.of("row_count");
        } else if (req.resultLimit() != null && req.resultLimit() > 0) {
            // Presto parses LIMIT as a grammar token — it must be a literal integer,
            // not a bound parameter (see mismatched input '?' expecting integer).
            int limit = req.resultLimit();
            sql = sql + " LIMIT " + limit;
        }
        return new MatchQuery(sql, params, columns, sql, query.route());
    }

    private MatchQuery route(MatchQuery query, List<QualifiedTable> tables) {
        if (executionRouter == null) return query;
        AnalysisExecutionRouter.RoutedSql routed = executionRouter.route(query.sql(), tables);
        return new MatchQuery(query.sql(), query.params(), query.columns(), routed.sql(), routed.route());
    }

    /** Display-only SQL with bound parameters rendered as literals — never re-executed. */
    public String renderQueryForDisplay(MatchQuery query) {
        return renderForDisplay(query.routedSql(), query.params());
    }

    /**
     * Validates hub join/display picks for a multi-target run (same rules as a
     * single-match {@linkplain RecordMatchRequest#sourceCriteria() source side}).
     */
    public QualifiedTable validateHubShape(List<MatchCriterion> hubCriteria, List<DisplayColumn> hubDisplay) {
        QualifiedTable hub = tableOf(hubCriteria, "hubCriteria");
        validateDisplayColumns(hubDisplay, hub, "hubDisplayColumns");
        return hub;
    }

    public StreamingResponseBody match(RecordMatchRequest req) {
        return match(planMatch(req));
    }

    public StreamingResponseBody match(RecordMatchService.MatchQuery query) {
        return match(query, failure -> { });
    }

    /**
     * @param onEngineFailure told when the engine fails mid-stream. The failure is also reported to the
     *     client in-band, and the stream then returns NORMALLY - so a caller that only watches for
     *     exceptions (the query monitor) needs this to learn the query did not succeed.
     */
    public StreamingResponseBody match(
            RecordMatchService.MatchQuery query, java.util.function.Consumer<Throwable> onEngineFailure) {
        return streamResults(query, onEngineFailure, takePlanTrace());
    }

    /**
     * The same match, streamed straight out as CSV.
     *
     * <p>Exists because the browser is where a large result actually hurts.
     * The NDJSON stream is parsed into row objects the grid holds and
     * recomputes over, which is bounded on the client at
     * {@code MAX_DISPLAYED_ROWS} — so past that point the officer could see a
     * count but never get the rows. This path never materialises a row in
     * JavaScript at all: the bytes go from Presto to the file.
     *
     * <p>Validation and SQL construction still happen synchronously, so a bad
     * request 400s before a single byte of the download is written.
     */
    public StreamingResponseBody matchCsv(RecordMatchRequest req) {
        return streamCsv(planMatch(req));
    }

    public StreamingResponseBody matchCsv(RecordMatchService.MatchQuery query) {
        return streamCsv(query);
    }

    public StreamingResponseBody matchJson(RecordMatchRequest req) {
        return streamJson(planMatch(req));
    }

    public StreamingResponseBody matchJson(MatchQuery query) {
        return streamJson(query);
    }

    public StreamingResponseBody matchXml(RecordMatchRequest req) {
        return streamXml(planMatch(req));
    }

    public StreamingResponseBody matchXml(MatchQuery query) {
        return streamXml(query);
    }

    public StreamingResponseBody matchExcel(RecordMatchRequest req) {
        MatchQuery query = planMatch(req);
        assertExcelExportAllowed(query);
        return streamExcel(query);
    }

    public StreamingResponseBody matchExcel(MatchQuery query) {
        return streamExcel(query);
    }

    /**
     * Refuses before streaming when the match would exceed Excel's per-sheet row limit.
     */
    public void assertExcelExportAllowed(MatchQuery query) {
        long rows = MatchExportStreamer.countRows(jdbcFor(query), guardrails.queryTimeoutSeconds(), query);
        if (rows > MatchExportLimits.EXCEL_MAX_ROWS_PER_SHEET) {
            throw new IllegalArgumentException(
                    "Excel export is limited to " + MatchExportLimits.EXCEL_MAX_ROWS_PER_SHEET
                            + " rows per sheet; this match would return " + rows
                            + " rows. Use CSV, JSON, or XML for the complete result.");
        }
    }

    /**
     * Full-result aggregate match rates for {@link RecordMatchRequest#comparisonGroups()} —
     * not derivable from the NDJSON stream, which stops at 200k rows client-side.
     */
    public ComparisonSummaryResponse comparisonSummary(RecordMatchRequest req) {
        return comparisonSummary(planMatch(req), req);
    }

    public ComparisonSummaryResponse comparisonSummary(MatchQuery query, RecordMatchRequest req) {
        if (req.comparisonGroups().isEmpty()) {
            return new ComparisonSummaryResponse(0, 0, 0, List.of());
        }
        JoinType joinType = JoinType.effective(req.joinType());
        boolean outerReconciliation = joinType != JoinType.INNER;
        StringBuilder agg = new StringBuilder("SELECT COUNT(*) AS total_rows");
        if (outerReconciliation) {
            agg.append(", SUM(CASE WHEN \"match_status\" = 'MATCHED' THEN 1 ELSE 0 END) AS matched_rows");
            agg.append(", SUM(CASE WHEN \"match_status\" <> 'MATCHED' THEN 1 ELSE 0 END) AS no_counterpart_rows");
        }
        for (int i = 0; i < req.comparisonGroups().size(); i++) {
            String matchCol = "\"cmp_" + i + "_match\"";
            if (outerReconciliation) {
                agg.append(", SUM(CASE WHEN \"match_status\" = 'MATCHED' AND ").append(matchCol)
                        .append(" THEN 1 ELSE 0 END) AS cmp_").append(i).append("_matches");
            } else {
                agg.append(", SUM(CASE WHEN ").append(matchCol).append(" THEN 1 ELSE 0 END) AS cmp_")
                        .append(i).append("_matches");
            }
        }
        agg.append(" FROM (").append(query.routedSql()).append(") comparison_summary_inner");
        Map<String, Object> row = jdbcFor(query).queryForMap(agg.toString(), query.params().toArray());
        long total = toLong(row.get("total_rows"));
        long matched = outerReconciliation ? toLong(row.get("matched_rows")) : total;
        long noCounterpart = outerReconciliation ? toLong(row.get("no_counterpart_rows")) : 0L;
        List<ComparisonSummaryResponse.ComparisonColumnSummary> columns = new ArrayList<>();
        for (int i = 0; i < req.comparisonGroups().size(); i++) {
            ComparisonGroup g = req.comparisonGroups().get(i);
            long matches = toLong(row.get("cmp_" + i + "_matches"));
            double rate = matched == 0 ? 0.0 : Math.round(matches * 1000.0 / matched) / 10.0;
            columns.add(new ComparisonSummaryResponse.ComparisonColumnSummary(
                    i, comparisonLabel(g), matches, rate));
        }
        return new ComparisonSummaryResponse(total, matched, noCounterpart, List.copyOf(columns));
    }

    /** How many categories a chart draws. One extra group is read so the rest can fold into Other. */
    public static final int CHART_CATEGORY_CAP = 50;

    /**
     * Counts every matching row, grouped by the requested output columns.
     * Returns at most {@link #CHART_CATEGORY_CAP} groups plus an Other bucket for the rest.
     */
    public ChartBucketsResponse chartBuckets(RecordMatchRequest req, List<String> columns) {
        return chartBuckets(planMatch(req.forFullResult()), columns);
    }

    public ChartBucketsResponse chartBuckets(MatchQuery query, List<String> columns) {
        List<String> grouped = validateChartColumns(query, columns);
        String sql = chartBucketSql(query.routedSql(), grouped);
        if (executionRouter != null) {
            sql = executionRouter.adaptChartWrapper(sql, query.route());
        }
        List<Map<String, Object>> rows = jdbcFor(query).queryForList(sql, query.params().toArray());
        if (rows.isEmpty()) {
            return new ChartBucketsResponse(0, false, List.of());
        }
        long total = toLong(cell(rows.get(0), "total_rows"));
        boolean truncated = rows.size() > CHART_CATEGORY_CAP;
        int kept = truncated ? CHART_CATEGORY_CAP : rows.size();
        List<ChartBucketsResponse.ChartBucket> buckets = new ArrayList<>();
        long keptSum = 0;
        for (int i = 0; i < kept; i++) {
            Map<String, Object> row = rows.get(i);
            List<String> values = new ArrayList<>();
            for (int c = 0; c < grouped.size(); c++) {
                values.add(cellText(cell(row, "c" + c)));
            }
            long count = toLong(cell(row, "bucket_count"));
            keptSum += count;
            buckets.add(new ChartBucketsResponse.ChartBucket(List.copyOf(values), count, false));
        }
        if (truncated) {
            long other = Math.max(0, total - keptSum);
            buckets.add(new ChartBucketsResponse.ChartBucket(List.of(), other, true));
        }
        return new ChartBucketsResponse(total, truncated, List.copyOf(buckets));
    }

    private static List<String> validateChartColumns(MatchQuery query, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("chart columns are required");
        }
        if (columns.size() > 5) {
            throw new IllegalArgumentException("chart columns must have 1 to 5 entries");
        }
        List<String> output = query.columns();
        List<String> grouped = new ArrayList<>();
        for (String column : columns) {
            if (column == null || !output.contains(column) || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("chart column is not in the match result: " + column);
            }
            grouped.add(column);
        }
        return List.copyOf(grouped);
    }

    static String chartBucketSql(String matchSql, List<String> columns) {
        StringBuilder select = new StringBuilder("SELECT ");
        StringBuilder groupBy = new StringBuilder("GROUP BY ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                select.append(", ");
                groupBy.append(", ");
            }
            String quoted = "\"" + columns.get(i) + "\"";
            select.append(quoted).append(" AS \"c").append(i).append("\"");
            groupBy.append(quoted);
        }
        select.append(", COUNT(*) AS bucket_count, SUM(COUNT(*)) OVER () AS total_rows FROM (")
                .append(matchSql)
                .append(") chart_src ")
                .append(groupBy)
                .append(" ORDER BY bucket_count DESC LIMIT ")
                .append(CHART_CATEGORY_CAP + 1);
        return select.toString();
    }

    private static Object cell(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String cellText(Object value) {
        if (value == null) {
            return "(blank)";
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? "(blank)" : text;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String comparisonLabel(ComparisonGroup group) {
        String src = group.source().stream().map(MatchCriterion::column).reduce((a, b) -> a + "+" + b).orElse("?");
        String tgt = group.target().stream().map(MatchCriterion::column).reduce((a, b) -> a + "+" + b).orElse("?");
        return src + " ↔ " + tgt;
    }

    /**
     * Both sides' columns as the LIVE lakehouse describes them, gathered by
     * the same pass that gates them. The types travel because the SQL depends
     * on them — see {@link #planPairs}.
     */
    private record Sides(QualifiedTable sourceTable, Map<String, RegisteredColumn> sourceColumns,
                         QualifiedTable targetTable, Map<String, RegisteredColumn> targetColumns) {
    }

    /**
     * The groups that become the ON clause, plus the same columns flattened.
     *
     * <p>Everything OUTSIDE the ON clause — the projections, the dedup
     * partition, the table each side resolves to, the type lookups — still
     * works from a flat list of columns and is indifferent to how they were
     * grouped. Only the join and the match score read {@link #groups()}.
     */
    private record JoinPlan(List<MatchGroup> groups,
                            List<MatchCriterion> sourceColumns,
                            List<MatchCriterion> targetColumns) {
    }

    /**
     * Resolves a request to groups, whichever shape it arrived in.
     *
     * <p>A request without {@code joinGroups} is zipped into single-column
     * groups, which every emitter below treats exactly as it treated a
     * criterion pair — the legacy path stays byte-identical, and the
     * same-size error it has always produced is still produced here.
     */
    private JoinPlan normalizeJoin(RecordMatchRequest req) {
        if (req.singleSource()) {
            return new JoinPlan(List.of(), List.of(), List.of());
        }
        if (req.joinGroups().isEmpty()) {
            if (req.sourceCriteria().size() != req.targetCriteria().size()) {
                throw new IllegalArgumentException("sourceCriteria and targetCriteria must be the same size");
            }
            List<MatchGroup> groups = new ArrayList<>();
            for (int i = 0; i < req.sourceCriteria().size(); i++) {
                groups.add(MatchGroup.of(req.sourceCriteria().get(i), req.targetCriteria().get(i)));
            }
            return new JoinPlan(groups, req.sourceCriteria(), req.targetCriteria());
        }
        validateGroups(req.joinGroups());
        List<MatchCriterion> sourceColumns = new ArrayList<>();
        List<MatchCriterion> targetColumns = new ArrayList<>();
        for (MatchGroup g : req.joinGroups()) {
            sourceColumns.addAll(g.source());
            targetColumns.addAll(g.target());
        }
        return new JoinPlan(req.joinGroups(), List.copyOf(sourceColumns), List.copyOf(targetColumns));
    }

    private void validateGroups(List<MatchGroup> groups) {
        if (groups.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(
                    "joinGroups must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        int anyOfSource = 0;
        int anyOfTarget = 0;
        for (int i = 0; i < groups.size(); i++) {
            MatchGroup g = groups.get(i);
            validateGroupSide(g.source(), i, "source");
            validateGroupSide(g.target(), i, "target");
            if (g.mode() == GroupMode.ANY_OF) {
                if (g.source().size() > 1) {
                    anyOfSource++;
                }
                if (g.target().size() > 1) {
                    anyOfTarget++;
                }
            }
        }
        // Each ANY_OF side becomes its own UNNEST, and two UNNESTs on one side
        // cross-multiply that side's rows before the join ever runs. The cap is
        // the only thing standing between an officer and a quiet row explosion.
        int maxAnyOf = analysisProperties.maxAnyOfGroupsPerSide();
        if (anyOfSource > maxAnyOf || anyOfTarget > maxAnyOf) {
            throw new IllegalArgumentException(
                    "at most " + maxAnyOf + " ANY_OF groups per side are allowed");
        }
    }

    private void validateGroupSide(List<MatchCriterion> columns, int index, String side) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("joinGroups[" + index + "]." + side
                    + " must have at least one column");
        }
        if (columns.size() > analysisProperties.maxGroupColumns()) {
            throw new IllegalArgumentException("joinGroups[" + index + "]." + side
                    + " must have at most " + analysisProperties.maxGroupColumns() + " columns");
        }
    }

    private void validateComparisonGroups(List<ComparisonGroup> groups, Sides sides) {
        if (groups.isEmpty()) {
            return;
        }
        if (groups.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(
                    "comparisonGroups must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        for (int i = 0; i < groups.size(); i++) {
            ComparisonGroup g = groups.get(i);
            if (g.mode() == GroupMode.ANY_OF) {
                throw new IllegalArgumentException("comparisonGroups[" + i + "] does not support ANY_OF");
            }
            validateComparisonSide(g.source(), i, "source", sides.sourceTable());
            validateComparisonSide(g.target(), i, "target", sides.targetTable());
        }
    }

    private void validateComparisonSide(List<MatchCriterion> columns, int index, String side,
                                        QualifiedTable expectedTable) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("comparisonGroups[" + index + "]." + side
                    + " must have at least one column");
        }
        if (columns.size() > analysisProperties.maxGroupColumns()) {
            throw new IllegalArgumentException("comparisonGroups[" + index + "]." + side
                    + " must have at most " + analysisProperties.maxGroupColumns() + " columns");
        }
        for (MatchCriterion c : columns) {
            if (!c.qualifiedTable().equals(expectedTable)) {
                throw new IllegalArgumentException("comparisonGroups[" + index + "]." + side
                        + " must reference " + expectedTable.qualifiedName());
            }
        }
    }

    private List<ComparisonPlan> planComparisons(RecordMatchRequest req, Sides sides, List<Object> params) {
        if (req.comparisonGroups().isEmpty()) {
            return List.of();
        }
        List<ComparisonPlan> plans = new ArrayList<>();
        for (int i = 0; i < req.comparisonGroups().size(); i++) {
            ComparisonGroup g = req.comparisonGroups().get(i);
            SideSql src = comparisonSideSql(g.source(), "src", sides.sourceColumns(), g.separator());
            SideSql tgt = comparisonSideSql(g.target(), "tgt", sides.targetColumns(), g.separator());
            boolean fuzzy = isComparisonGroupFuzzy(g);
            CompareAs mode = comparisonGroupCompareAs(g);
            TypeCoercion.Aligned aligned = fuzzy
                    ? new TypeCoercion.Aligned(
                    TypeCoercion.asText(src.sql(), src.family()),
                    TypeCoercion.asText(tgt.sql(), tgt.family()))
                    : TypeCoercion.align(src.sql(), src.family(), tgt.sql(), tgt.family(), mode);
            String matchExpr;
            String scoreExpr = null;
            if (fuzzy) {
                if (g.fuzzyThresholdPercent() == null) {
                    throw new IllegalArgumentException(
                            "comparisonGroups[" + i + "].fuzzyThresholdPercent is required for a fuzzy comparison");
                }
                double threshold = g.fuzzyThresholdPercent();
                if (threshold < 0 || threshold > 100) {
                    throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
                }
                scoreExpr = "ROUND((" + FuzzyMatchSql.similarityExpr(aligned.left(), aligned.right())
                        + ") * 100, 1)";
                matchExpr = "(" + FuzzyMatchSql.similarityExpr(aligned.left(), aligned.right()) + " >= ?)";
                double thresholdParam = threshold / 100.0;
                params.add(thresholdParam);
                plans.add(new ComparisonPlan(
                        i, aligned.left(), aligned.right(), matchExpr, scoreExpr, List.of(thresholdParam)));
            } else {
                // Both-null counts as a match; one-sided null is a mismatch — PrestoDB 0.297 IS DISTINCT FROM.
                matchExpr = "(NOT (" + aligned.left() + " IS DISTINCT FROM " + aligned.right() + "))";
                plans.add(new ComparisonPlan(
                        i, aligned.left(), aligned.right(), matchExpr, scoreExpr, List.of()));
            }
        }
        return List.copyOf(plans);
    }

    private SideSql comparisonSideSql(List<MatchCriterion> columns, String alias,
                                      Map<String, RegisteredColumn> described, String separator) {
        if (columns.size() == 1) {
            String column = columns.get(0).column();
            return new SideSql(alias + "." + column, familyOf(described, column));
        }
        List<String> refs = columns.stream().map(c -> alias + "." + c.column()).toList();
        return new SideSql(ColumnGroupSql.combine(refs, separator), SqlTypeFamily.TEXT);
    }

    private void appendComparisonSelects(StringBuilder select, Set<String> outerColumns,
                                         List<ComparisonPlan> plans, String noCounterpartWhen) {
        for (ComparisonPlan plan : plans) {
            appendExpressionSelect(select, outerColumns, plan.sourceValueExpr(), "cmp_" + plan.index() + "_source");
            appendExpressionSelect(select, outerColumns, plan.targetValueExpr(), "cmp_" + plan.index() + "_target");
            String verdict = plan.matchBooleanExpr();
            if (noCounterpartWhen != null) {
                verdict = nullVerdictWhenNoCounterpart(noCounterpartWhen, verdict);
            }
            appendExpressionSelect(select, outerColumns, verdict, "cmp_" + plan.index() + "_match");
            if (plan.scorePercentExpr() != null) {
                String score = plan.scorePercentExpr();
                if (noCounterpartWhen != null) {
                    score = "CASE WHEN " + noCounterpartWhen + " THEN NULL ELSE (" + score + ") END";
                }
                appendExpressionSelect(select, outerColumns, score, "cmp_" + plan.index() + "_score_pct");
            }
        }
    }

    private static String nullVerdictWhenNoCounterpart(String noCounterpartWhen, String verdictExpr) {
        return "CASE WHEN " + noCounterpartWhen + " THEN NULL ELSE (" + verdictExpr + ") END";
    }

    /**
     * First join-key columns on each side — used to detect rows with no counterpart
     * under outer joins. A row that satisfied the equi-join cannot have NULL on
     * either key: NULL never equals anything, so it could not have matched.
     */
    private record JoinKeyRefs(String sourceColumnSql, String targetColumnSql) {
    }

    private static JoinKeyRefs joinKeyRefs(JoinPlan join) {
        if (join.sourceColumns().isEmpty() || join.targetColumns().isEmpty()) {
            throw new IllegalArgumentException("join requires at least one source and one target column");
        }
        MatchCriterion source = join.sourceColumns().get(0);
        MatchCriterion target = join.targetColumns().get(0);
        return new JoinKeyRefs("src." + source.column(), "tgt." + target.column());
    }

    private static String noCounterpartPredicate(JoinType joinType, JoinKeyRefs keys) {
        return switch (joinType) {
            case INNER -> throw new IllegalStateException("INNER join has no unmatched rows");
            case LEFT -> keys.targetColumnSql() + " IS NULL";
            case RIGHT -> keys.sourceColumnSql() + " IS NULL";
            case FULL -> "(" + keys.sourceColumnSql() + " IS NULL OR " + keys.targetColumnSql() + " IS NULL)";
        };
    }

    private void appendMatchStatusSelect(StringBuilder select, Set<String> outerColumns,
                                         JoinType joinType, JoinKeyRefs keys) {
        appendExpressionSelect(select, outerColumns, matchStatusExpr(joinType, keys), "match_status");
    }

    private static String matchStatusExpr(JoinType joinType, JoinKeyRefs keys) {
        // See joinKeyRefs — NULL on a join key means this row has no counterpart on that side.
        return switch (joinType) {
            case INNER -> throw new IllegalStateException("match_status is not emitted for INNER joins");
            case LEFT -> "CASE WHEN " + keys.targetColumnSql() + " IS NULL THEN 'NO_TARGET' ELSE 'MATCHED' END";
            case RIGHT -> "CASE WHEN " + keys.sourceColumnSql() + " IS NULL THEN 'NO_SOURCE' ELSE 'MATCHED' END";
            case FULL -> "CASE WHEN " + keys.sourceColumnSql() + " IS NULL THEN 'NO_SOURCE' WHEN "
                    + keys.targetColumnSql() + " IS NULL THEN 'NO_TARGET' ELSE 'MATCHED' END";
        };
    }

    private void appendMismatchOnlyFilter(StringBuilder where, RecordMatchRequest req,
                                          List<ComparisonPlan> plans, List<Object> params,
                                          String noCounterpartWhen) {
        if (!req.mismatchOnly() || plans.isEmpty()) {
            return;
        }
        StringJoiner allMatch = new StringJoiner(" AND ");
        for (ComparisonPlan plan : plans) {
            allMatch.add("(" + plan.matchBooleanExpr() + ")");
            // SELECT already bound these once; WHERE reuses the same expression text.
            params.addAll(plan.matchBindValues());
        }
        String disagreement = "NOT (" + allMatch + ")";
        if (noCounterpartWhen != null) {
            // SELECT alias match_status is not visible in WHERE — use the same predicate as verdict wrapping.
            appendWhereClause(where, "(" + noCounterpartWhen + " OR " + disagreement + ")");
        } else {
            appendWhereClause(where, disagreement);
        }
    }

    private void appendExpressionSelect(StringBuilder select, Set<String> outerColumns, String expression,
                                        String outAlias) {
        if (select.length() > 0) {
            select.append(", ");
        }
        select.append("(").append(expression).append(") AS \"").append(outAlias).append('"');
        outerColumns.add(outAlias);
    }

    private CompareAs comparisonGroupCompareAs(ComparisonGroup group) {
        if (group.source().size() + group.target().size() > 2
                || group.source().size() > 1
                || group.target().size() > 1) {
            return CompareAs.TEXT;
        }
        return CompareAs.resolve(sideCompareAs(group.source()), sideCompareAs(group.target()));
    }

    /**
     * Whether this comparison emits a similarity score rather than an equality
     * verdict. Package-private because {@code MultiTargetRecordMatchService}'s
     * merged layout must allocate the score column on exactly the same
     * condition the SQL emits it — deciding that twice is how a column that is
     * always null ends up in the grid.
     */
    boolean isComparisonGroupFuzzy(ComparisonGroup group) {
        List<MatchCriterion> columns = new ArrayList<>(group.source());
        columns.addAll(group.target());
        boolean anyRegistered = false;
        for (MatchCriterion c : columns) {
            Optional<AnalysisColumnMetadata> meta = findMetadata(c);
            if (meta.isPresent()) {
                anyRegistered = true;
                if (meta.get().isFuzzyMatchable()) {
                    return true;
                }
            }
        }
        if (anyRegistered) {
            return false;
        }
        return columns.stream().anyMatch(c -> c.column().toLowerCase().contains("name"));
    }

    private MatchQuery buildSingleSourceQuery(RecordMatchRequest req) {
        if (!req.targetCriteria().isEmpty() || !req.joinGroups().isEmpty()
                || !req.comparisonGroups().isEmpty()) {
            throw new IllegalArgumentException("singleSource mode cannot include a join");
        }
        QualifiedTable table = tableForSingleSource(req);
        if (req.grouped()) {
            Map<String, RegisteredColumn> described = describeRequestColumns(table, req, req.sourceRules());
            MatchGroupingSql.validateSingleSourceGrouped(
                    req, table, described,
                    analysisProperties.maxGroupColumns(),
                    analysisProperties.maxGroupColumns());
            return buildSingleSourceBody(req, table, described);
        }
        if (req.sourceDisplayColumns().isEmpty() && !req.countOnly()) {
            throw new IllegalArgumentException("singleSource requires at least one display column");
        }
        if (!req.sourceDisplayColumns().isEmpty()) {
            validateDisplayColumns(req.sourceDisplayColumns(), table, "sourceDisplayColumns");
        }
        Map<String, RegisteredColumn> described = describeRequestColumns(table, req, req.sourceRules());
        return buildSingleSourceBody(req, table, described);
    }

    private MatchQuery buildSingleSourceBody(
            RecordMatchRequest req, QualifiedTable table, Map<String, RegisteredColumn> described) {
        AppliedSideRules applied = applySideRules(
                scopeFrom.planFrom(table), req.sourceRules(), table, described);
        ScopeFilteredFrom scope = applied.scope();
        // No join — fan-out guard does not apply; do not call enforceEstimatedRowCeiling with
        // degenerate empty join groups.

        List<Object> scopeParams = new ArrayList<>();
        String from = fromSide(scope, "src", List.of(), scopeParams);

        Set<String> outerColumns = new LinkedHashSet<>();
        StringBuilder select = new StringBuilder();
        if (req.grouped()) {
            MatchGroupingSql.appendGroupedSelect(select, outerColumns, req, table, table,
                    MatchGroupingSql.aggregateColumnTypes(req, table, table, described, described));
        } else if (req.sourceDisplayColumns().isEmpty()) {
            select.append("1 AS \"_row\"");
            outerColumns.add("_row");
        } else {
            appendDisplaySelects(select, outerColumns, "src", "source_", req.sourceDisplayColumns());
        }
        // The score projection's placeholders sit in SELECT, which precedes FROM in the
        // emitted SQL, so its parameters must be bound BEFORE the scope and rule ones.
        // Appending them instead fed the threshold into a lower(), and Presto rejected
        // the statement with "Unexpected parameters (double) for function lower".
        // Same ordering trap CLAUDE.md records for comparison placeholders.
        List<Object> params = new ArrayList<>();
        if (applied.fuzzyScore() != null) {
            select.append(", ").append(applied.fuzzyScore().selectExpr()).append(" AS \"match_score_pct\"");
            outerColumns.add("match_score_pct");
            params.addAll(applied.fuzzyScore().params());
        }
        params.addAll(scopeParams);
        String sql = "SELECT " + select + " FROM " + from;
        String groupBy = MatchGroupingSql.groupByClause(req, table, table);
        if (!groupBy.isEmpty()) {
            sql += " GROUP BY " + groupBy;
        }
        return new MatchQuery(sql, params, List.copyOf(outerColumns));
    }

    private QualifiedTable tableForSingleSource(RecordMatchRequest req) {
        if (!req.sourceDisplayColumns().isEmpty()) {
            return sameTableFromDisplay(req.sourceDisplayColumns(), "sourceDisplayColumns");
        }
        if (!req.groupByColumns().isEmpty()) {
            return sameTableFromDisplay(req.groupByColumns(), "groupByColumns");
        }
        for (AggregateSpec agg : req.aggregates()) {
            if (agg.column() != null) {
                return agg.column().qualifiedTable();
            }
        }
        if (req.countOnly() && req.sourceRules() != null) {
            return tableFromRules(req.sourceRules(), "sourceRules");
        }
        throw new IllegalArgumentException(
                "singleSource requires display columns, a group key, an aggregate column, or source rules");
    }

    private QualifiedTable tableFromRules(Ast.PredicateSpec spec, String label) {
        if (spec == null || spec.root() == null) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return tableFromRuleNode(spec.root(), label);
    }

    private QualifiedTable tableFromRuleNode(Ast.Node node, String label) {
        if (node instanceof Ast.GroupNode group) {
            if (group.children().isEmpty()) {
                throw new IllegalArgumentException(label + " must not be empty");
            }
            return tableFromRuleNode(group.children().get(0), label);
        }
        if (node instanceof Ast.PredicateNode predicate) {
            return predicate.column().table();
        }
        throw new IllegalArgumentException("Unsupported rule node in " + label);
    }

    private record AppliedSideRules(
            ScopeFilteredFrom scope, CompiledQuery.FuzzyScoreProjection fuzzyScore) {
    }

    private AppliedSideRules applySideRules(
            ScopeFilteredFrom scope,
            Ast.PredicateSpec rules,
            QualifiedTable table,
            Map<String, RegisteredColumn> knownColumns) {
        if (rules == null) {
            return new AppliedSideRules(scope, null);
        }
        List<String> columns = new ArrayList<>();
        collectRuleColumns(rules.root(), table, columns);
        CompiledQuery compiled = ruleCompiler.compile(rules, table, knownColumns);
        return new AppliedSideRules(
                scope.withRulePredicate(compiled.predicateSql(), compiled.params()),
                compiled.fuzzyScore());
    }

    private void collectRuleColumns(Ast.Node node, QualifiedTable table, List<String> columns) {
        if (node instanceof Ast.GroupNode group) {
            group.children().forEach(child -> collectRuleColumns(child, table, columns));
            return;
        }
        if (node instanceof Ast.PredicateNode predicate) {
            if (!predicate.column().table().equals(table)) {
                throw new IllegalArgumentException(
                        "Rule column must be on " + table.qualifiedName());
            }
            columns.add(predicate.column().column());
        }
    }

    private QualifiedTable sameTableFromDisplay(List<DisplayColumn> columns, String label) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        QualifiedTable table = columns.get(0).qualifiedTable();
        for (DisplayColumn column : columns) {
            if (!column.qualifiedTable().equals(table)) {
                throw new IllegalArgumentException(
                        label + " must all reference the same table: " + table.qualifiedName());
            }
        }
        return table;
    }

    private Sides validateRequest(RecordMatchRequest req, JoinPlan join) {
        QualifiedTable sourceTable = sameTable(join.sourceColumns(), "sourceCriteria");
        QualifiedTable targetTable = sameTable(join.targetColumns(), "targetCriteria");
        validateAdditionalJoins(req);
        validateDisplayColumns(req.sourceDisplayColumns(), sourceTable, "sourceDisplayColumns");
        validateDisplayColumns(req.targetDisplayColumns(), targetTable, "targetDisplayColumns");

        List<MatchCriterion> comparisonSourceColumns = new ArrayList<>(req.comparisonGroups().stream()
                .flatMap(g -> g.source().stream())
                .toList());
        for (AdditionalJoin extra : req.additionalJoins()) {
            comparisonSourceColumns.add(new MatchCriterion(
                    sourceTable.catalog(), sourceTable.schema(), sourceTable.table(),
                    extra.sourceColumn(), null));
        }
        List<MatchCriterion> comparisonTargetColumns = req.comparisonGroups().stream()
                .flatMap(g -> g.target().stream())
                .toList();
        Sides sides = new Sides(
                sourceTable, describeSide(sourceTable, join.sourceColumns(), req.sourceDisplayColumns(),
                        comparisonSourceColumns, req, req.sourceRules()),
                targetTable, describeSide(targetTable, join.targetColumns(), req.targetDisplayColumns(),
                        comparisonTargetColumns, req, req.targetRules()));
        if (req.dedup() != null) {
            validateSideMembership(req.dedup().qualifiedTable(), sourceTable, targetTable, "dedup.table");
        }
        if (req.grouped()) {
            MatchGroupingSql.validateGroupedRequest(
                    req,
                    analysisProperties.maxGroupColumns(),
                    analysisProperties.maxGroupColumns());
            MatchGroupingSql.validateColumnsOnSides(
                    req, sourceTable, targetTable, sides.sourceColumns(), sides.targetColumns());
        }
        validateOuterJoinRules(req, sides);
        validateComparisonGroups(req.comparisonGroups(), sides);
        return sides;
    }

    /**
     * Outer-join-specific constraints (dedup) — kept out of {@link #validateRequest}'s
     * generic checks so messages stay actionable.
     */
    private void validateOuterJoinRules(RecordMatchRequest req, Sides sides) {
        JoinType joinType = JoinType.effective(req.joinType());
        if (req.dedup() != null && (joinType == JoinType.RIGHT || joinType == JoinType.FULL)) {
            throw new IllegalArgumentException(
                    "Dedup cannot be used with a " + joinType + " join: unmatched rows have NULL "
                            + "source-side partition keys and would collapse into one row. "
                            + "Use INNER or LEFT, or turn dedup off.");
        }
    }

    private MatchQuery buildMatchQuery(RecordMatchRequest req, JoinPlan join, Sides sides, boolean estimateRows) {
        JoinType joinType = JoinType.effective(req.joinType());
        List<Object> params = new ArrayList<>();
        Set<String> outerColumns = new LinkedHashSet<>();
        StringBuilder select = new StringBuilder();
        StringBuilder onClause = new StringBuilder();
        StringBuilder where = new StringBuilder();

        // Every type-dependent decision is made once, here, and both the join
        // and the match score read the same plan — they MUST agree on which
        // groups are fuzzy and on how each side is cast, or the score would be
        // computed over different expressions than the join matched on.
        List<UnnestSide> sourceUnnests = new ArrayList<>();
        List<UnnestSide> targetUnnests = new ArrayList<>();
        List<GroupPlan> groups = planGroups(join, sides, sourceUnnests, targetUnnests);
        ScopeFilteredFrom sourceScope = applySideRules(
                scopeFrom.planFrom(sides.sourceTable()), req.sourceRules(), sides.sourceTable(),
                sides.sourceColumns()).scope();
        ScopeFilteredFrom targetScope = applySideRules(
                scopeFrom.planFrom(sides.targetTable()), req.targetRules(), sides.targetTable(),
                sides.targetColumns()).scope();
        List<PlannedExtra> plannedExtras = planAdditionalTables(req);
        List<ScopeFilteredFrom> extraScopes = plannedExtras.stream().map(PlannedExtra::scope).toList();
        if (estimateRows) {
            enforceEstimatedRowCeiling(
                    sides, groups, sourceScope, targetScope, req.grouped(),
                    req.additionalJoins(), extraScopes);
        }

        List<ComparisonPlan> comparisonPlans = List.of();
        String dedupAlias = null;
        if (req.grouped()) {
            MatchGroupingSql.appendGroupedSelect(
                    select, outerColumns, req, sides.sourceTable(), sides.targetTable(),
                    MatchGroupingSql.aggregateColumnTypes(
                            req, sides.sourceTable(), sides.targetTable(),
                            sides.sourceColumns(), sides.targetColumns()));
        } else {
            // Comparison placeholders live in SELECT, which precedes ON in the final SQL —
            // bind comparison params before join params.
            comparisonPlans = planComparisons(req, sides, params);
            appendCriteriaSelects(select, outerColumns, join, req);
            appendMatchedOnSelects(select, outerColumns, "src", "source_", sourceUnnests);
            appendMatchedOnSelects(select, outerColumns, "tgt", "target_", targetUnnests);
            boolean outerComparison = !req.comparisonGroups().isEmpty() && joinType != JoinType.INNER;
            JoinKeyRefs joinKeys = req.comparisonGroups().isEmpty() ? null : joinKeyRefs(join);
            String noCounterpartWhen = outerComparison ? noCounterpartPredicate(joinType, joinKeys) : null;
            if (outerComparison) {
                appendMatchStatusSelect(select, outerColumns, joinType, joinKeys);
            }
            appendComparisonSelects(select, outerColumns, comparisonPlans, noCounterpartWhen);
            dedupAlias = appendDedupSelect(select, outerColumns, req, join);
            appendMatchScoreSelect(select, outerColumns, req, groups);
        }
        List<Object> onParams = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();
        appendJoinConditions(onClause, where, onParams, whereParams, groups, joinType);
        if (!req.grouped()) {
            appendMismatchOnlyFilter(where, req, comparisonPlans, whereParams,
                    !req.comparisonGroups().isEmpty() && joinType != JoinType.INNER
                            ? noCounterpartPredicate(joinType, joinKeyRefs(join)) : null);
        }

        if (where.length() == 0) {
            where.append("TRUE");
        }

        // Fully-qualified catalog.schema.table on both sides — the two sides
        // can live in different catalogs entirely (a Silver-vs-Gold
        // reconciliation), which Presto joins natively.
        List<Object> scopeParams = new ArrayList<>();
        String sourceFrom = fromSide(sourceScope, "src", sourceUnnests, scopeParams);
        String targetFrom = fromSide(targetScope, "tgt", targetUnnests, scopeParams);
        StringBuilder extraJoins = new StringBuilder();
        appendAdditionalJoins(
                req, sides, select, outerColumns, extraJoins, where, onParams, whereParams, scopeParams, plannedExtras);
        String baseSql = "SELECT " + select + " FROM " + sourceFrom + " " + joinType.joinKeyword() + " "
                + targetFrom + " ON " + onClause + extraJoins + " WHERE " + where;

        if (req.grouped()) {
            String groupBy = MatchGroupingSql.groupByClause(req, sides.sourceTable(), sides.targetTable());
            if (!groupBy.isEmpty()) {
                baseSql += " GROUP BY " + groupBy;
            }
        }
        String finalSql = req.grouped()
                ? baseSql
                : wrapWithDedup(baseSql, req, join, dedupAlias);
        List<Object> allParams = new ArrayList<>(scopeParams.size() + params.size() + onParams.size() + whereParams.size());
        allParams.addAll(scopeParams);
        allParams.addAll(params);
        allParams.addAll(onParams);
        allParams.addAll(whereParams);
        return new MatchQuery(finalSql, allParams, List.copyOf(outerColumns));
    }

    /** One extra table described once, with its rules already compiled into the scope. */
    private record PlannedExtra(ScopeFilteredFrom scope, Map<String, RegisteredColumn> columns) {}

    /** Row and distinct counts for one extra table, used only when its join is not inner. */
    private record ExtraFanOut(JoinType joinType, long extraRows, long groupMax) {}

    /**
     * Scope for each additional table, planned once and reused by the fan-out
     * guard and the SQL. The same describe covers the join column, the columns
     * to show, and every rule column.
     */
    private List<PlannedExtra> planAdditionalTables(RecordMatchRequest req) {
        if (req.additionalJoins().isEmpty()) {
            return List.of();
        }
        List<PlannedExtra> planned = new ArrayList<>(req.additionalJoins().size());
        for (AdditionalJoin extra : req.additionalJoins()) {
            QualifiedTable table = extra.qualifiedTable();
            LinkedHashSet<String> names = new LinkedHashSet<>();
            names.add(extra.joinColumn());
            names.addAll(extra.displayColumns());
            if (extra.rules() != null) {
                List<String> ruleColumns = new ArrayList<>();
                collectRuleColumns(extra.rules().root(), table, ruleColumns);
                names.addAll(ruleColumns);
            }
            Map<String, RegisteredColumn> described = registry.describeColumns(table, List.copyOf(names));
            ScopeFilteredFrom scope = applySideRules(
                    scopeFrom.planFrom(table), extra.rules(), table, described).scope();
            planned.add(new PlannedExtra(scope, described));
        }
        return planned;
    }

    /**
     * Further tables joined to the source. An empty list leaves the two-table
     * SQL unchanged. A null join type stays {@code INNER JOIN}. Each extra
     * table is described once, rules sit inside its derived table, and columns
     * are projected under {@code extraN_}. An id join stays an equality with the
     * same type alignment as the primary join. A name join with a threshold uses
     * the same blocking key and Levenshtein predicate as the primary fuzzy join.
     */
    private void appendAdditionalJoins(
            RecordMatchRequest req,
            Sides sides,
            StringBuilder select,
            Set<String> outerColumns,
            StringBuilder extraJoinSql,
            StringBuilder where,
            List<Object> onParams,
            List<Object> whereParams,
            List<Object> scopeParams,
            List<PlannedExtra> plannedExtras) {
        if (req.additionalJoins().isEmpty() || req.grouped()) {
            return;
        }
        int n = 1;
        for (int i = 0; i < req.additionalJoins().size(); i++) {
            AdditionalJoin extra = req.additionalJoins().get(i);
            String alias = "x" + n;
            PlannedExtra planned = plannedExtras.get(i);
            JoinType extraType = extra.effectiveJoinType();
            String keyword = extraType == JoinType.INNER ? "INNER JOIN" : extraType.joinKeyword();
            extraJoinSql.append(' ').append(keyword).append(' ')
                    .append(fromSide(planned.scope(), alias, List.of(), scopeParams))
                    .append(" ON ");
            if (additionalJoinIsFuzzy(extra)) {
                appendAdditionalFuzzyJoin(
                        extraJoinSql, where, onParams, whereParams, sides, extra, alias, planned.columns(), extraType);
            } else {
                TypeCoercion.Aligned aligned = alignAdditionalJoin(sides, extra, alias, planned.columns());
                extraJoinSql.append(aligned.left()).append(" = ").append(aligned.right());
            }
            String joinOut = "extra" + n + "_" + extra.joinColumn();
            if (!outerColumns.contains(joinOut)) {
                appendSelect(select, outerColumns, alias, extra.joinColumn(),
                        allocateUniqueAlias(joinOut, outerColumns));
            }
            List<DisplayColumn> displays = new ArrayList<>();
            for (String column : extra.displayColumns()) {
                if (!column.equals(extra.joinColumn())) {
                    displays.add(new DisplayColumn(
                            extra.catalog(), extra.schema(), extra.table(), column));
                }
            }
            appendDisplaySelects(select, outerColumns, alias, "extra" + n + "_", displays);
            n++;
        }
    }

    private TypeCoercion.Aligned alignAdditionalJoin(
            Sides sides,
            AdditionalJoin extra,
            String alias,
            Map<String, RegisteredColumn> described) {
        MatchCriterion source = new MatchCriterion(
                sides.sourceTable().catalog(), sides.sourceTable().schema(), sides.sourceTable().table(),
                extra.sourceColumn(), null);
        MatchCriterion target = new MatchCriterion(
                extra.catalog(), extra.schema(), extra.table(), extra.joinColumn(), null);
        CompareAs mode = CompareAs.resolve(
                sideCompareAs(List.of(source)), sideCompareAs(List.of(target)));
        return TypeCoercion.align(
                "src." + extra.sourceColumn(), familyOf(sides.sourceColumns(), extra.sourceColumn()),
                alias + "." + extra.joinColumn(), familyOf(described, extra.joinColumn()),
                mode);
    }

    private void validateAdditionalJoins(RecordMatchRequest req) {
        List<AdditionalJoin> extras = req.additionalJoins();
        if (extras.isEmpty()) {
            return;
        }
        if (req.grouped()) {
            throw new IllegalArgumentException(
                    "Additional tables cannot be combined with grouping. "
                            + "Remove the extra tables or turn grouping off.");
        }
        int max = analysisProperties.maxTargetSets();
        if (extras.size() > max) {
            throw new IllegalArgumentException("At most " + max + " additional tables can be joined");
        }
        for (int i = 0; i < extras.size(); i++) {
            AdditionalJoin extra = extras.get(i);
            extra.qualifiedTable();
            LakehouseIdentifiers.requireSafe("sourceColumn", extra.sourceColumn());
            LakehouseIdentifiers.requireSafe("joinColumn", extra.joinColumn());
            for (String column : extra.displayColumns()) {
                LakehouseIdentifiers.requireSafe("displayColumn", column);
            }
        }
    }

    /**
     * One side's FROM fragment (§7.2.3 Part 0). Scope filter is an inner derived
     * table; ANY_OF UNNEST wraps outside it. Unfiltered SQL stays byte-identical.
     */
    private String fromSide(ScopeFilteredFrom scope, String alias, List<UnnestSide> unnests,
                            List<Object> scopeParamsOut) {
        if (scope.appliesFilter()) {
            scopeParamsOut.addAll(scope.bindValues());
        }
        String innerTable = scope.innerTableExpression();
        if (unnests.isEmpty()) {
            return scope.fromFragment(alias);
        }
        StringBuilder projected = new StringBuilder("t.*");
        StringBuilder clauses = new StringBuilder();
        for (UnnestSide u : unnests) {
            projected.append(", ").append(u.keyAlias()).append(", ").append(u.matchedAlias());
            clauses.append(" ").append(u.clause());
        }
        return "(SELECT " + projected + " FROM " + innerTable + " t" + clauses + ") " + alias;
    }

    private void appendCriteriaSelects(StringBuilder select, Set<String> outerColumns,
                                       JoinPlan join, RecordMatchRequest req) {
        for (MatchCriterion c : join.sourceColumns()) {
            String baseAlias = "source_" + c.column();
            appendSelect(select, outerColumns, "src", c.column(), allocateUniqueAlias(baseAlias, outerColumns));
        }
        for (MatchCriterion c : join.targetColumns()) {
            String baseAlias = "target_" + c.column();
            appendSelect(select, outerColumns, "tgt", c.column(), allocateUniqueAlias(baseAlias, outerColumns));
        }
        appendDisplaySelects(select, outerColumns, "src", "source_", req.sourceDisplayColumns());
        appendDisplaySelects(select, outerColumns, "tgt", "target_", req.targetDisplayColumns());
    }

    private void appendDisplaySelects(StringBuilder select, Set<String> outerColumns, String sqlAlias,
                                      String outPrefix, List<DisplayColumn> display) {
        for (DisplayColumn d : display) {
            String baseAlias = outPrefix + d.column();
            if (outerColumns.contains(baseAlias)) {
                continue;
            }
            String outAlias = allocateUniqueAlias(baseAlias, outerColumns);
            appendSelect(select, outerColumns, sqlAlias, d.column(), outAlias);
        }
    }

    /**
     * Projects each ANY_OF group's {@code matched_on} column.
     *
     * <p>An ANY_OF group returns the same pair once per column that matched, so
     * without this the duplicates look like a bug. With it they are the answer:
     * the row says which of the candidate columns carried the value.
     */
    private void appendMatchedOnSelects(StringBuilder select, Set<String> outerColumns, String sqlAlias,
                                        String outPrefix, List<UnnestSide> unnests) {
        for (UnnestSide u : unnests) {
            String outAlias = allocateUniqueAlias(outPrefix + u.matchedAlias(), outerColumns);
            appendSelect(select, outerColumns, sqlAlias, u.matchedAlias(), outAlias);
        }
    }

    /**
     * Reserves a unique output alias when {@code baseAlias} is already taken by
     * a non-skipped projection (e.g. {@code match_score_pct}).
     */
    static String allocateUniqueAlias(String baseAlias, Set<String> outerColumns) {
        if (!outerColumns.contains(baseAlias)) {
            return baseAlias;
        }
        int suffix = 2;
        while (outerColumns.contains(baseAlias + "_" + suffix)) {
            suffix++;
        }
        return baseAlias + "_" + suffix;
    }

    /**
     * One group resolved: whether it is fuzzy, and the SQL each side compares
     * as once folded and coerced.
     *
     * <p>{@code sourceRef}/{@code targetRef} are what actually goes into SQL.
     * For a fuzzy group they are the TEXT forms (Levenshtein and {@code lower}
     * take nothing else); for an exact one they are the two sides aligned to a
     * common type — which is the whole point: the same account number stored
     * {@code varchar} in one table and {@code bigint} in the other used to fail
     * the query outright with {@code '=' cannot be applied to varchar, bigint}.
     */
    private record GroupPlan(MatchGroup group, boolean fuzzy, String sourceRef, String targetRef) {
    }

    /** Post-join comparison projection — never routed to {@link #appendJoinConditions}. */
    private record ComparisonPlan(
            int index,
            String sourceValueExpr,
            String targetValueExpr,
            String matchBooleanExpr,
            String scorePercentExpr,
            List<Object> matchBindValues) {

        ComparisonPlan {
            matchBindValues = MatchQuery.copyBindParams(matchBindValues);
        }
    }

    /** One ANY_OF side pivoted to rows, and the CROSS JOIN UNNEST that does it. */
    private record UnnestSide(String keyAlias, String matchedAlias, String clause) {
    }

    /** A side of a group as SQL, with the type family that expression yields. */
    private record SideSql(String sql, SqlTypeFamily family) {
    }

    /**
     * Resolves every group once — fuzzy-or-exact, the folding each side needs,
     * and the casts. Both the join and the match score read this, so they
     * cannot drift apart, and each column's admin metadata is fetched once
     * instead of once per use.
     *
     * <p>ANY_OF sides are appended to {@code sourceUnnests}/{@code targetUnnests}
     * as a side effect, because the FROM clause has to know about them before
     * it can be written.
     */
    private List<GroupPlan> planGroups(JoinPlan join, Sides sides,
                                       List<UnnestSide> sourceUnnests, List<UnnestSide> targetUnnests) {
        List<GroupPlan> plans = new ArrayList<>();
        for (int i = 0; i < join.groups().size(); i++) {
            MatchGroup g = join.groups().get(i);
            SideSql src = sideSql(g, g.source(), "src", sides.sourceColumns(), i, sourceUnnests);
            SideSql tgt = sideSql(g, g.target(), "tgt", sides.targetColumns(), i, targetUnnests);

            if (isGroupFuzzy(g)) {
                // CompareAs does not enter into a fuzzy group: Levenshtein
                // similarity is a string measure, so both sides go to text
                // whatever the admin set for a direct comparison.
                plans.add(new GroupPlan(g, true,
                        TypeCoercion.asText(src.sql(), src.family()),
                        TypeCoercion.asText(tgt.sql(), tgt.family())));
            } else {
                CompareAs mode = groupCompareAs(g);
                TypeCoercion.Aligned aligned =
                        TypeCoercion.align(src.sql(), src.family(), tgt.sql(), tgt.family(), mode);
                plans.add(new GroupPlan(g, false, aligned.left(), aligned.right()));
            }
        }
        return plans;
    }

    /**
     * One side of a group as a single SQL expression.
     *
     * <p>A single column is emitted bare, exactly as before groups existed.
     * A COMBINE side is folded to text. An ANY_OF side is pivoted with UNNEST
     * and the expression becomes a reference to the unnested key, so
     * everything downstream — blocking key, similarity, equality — treats it
     * as the ordinary single column it now is.
     */
    private SideSql sideSql(MatchGroup group, List<MatchCriterion> columns, String alias,
                            Map<String, RegisteredColumn> described, int groupIndex,
                            List<UnnestSide> unnests) {
        if (columns.size() == 1) {
            String column = columns.get(0).column();
            return new SideSql(alias + "." + column, familyOf(described, column));
        }
        List<String> names = columns.stream().map(MatchCriterion::column).toList();
        if (group.mode() == GroupMode.ANY_OF) {
            // Within one family Presto already unifies the array's element type
            // (integer with bigint, varchar(20) with varchar(50)); a cast there
            // would only change what the comparison means. Across families it
            // cannot, so the array goes to text.
            SqlTypeFamily first = familyOf(described, names.get(0));
            boolean uniform = names.stream().allMatch(n -> familyOf(described, n) == first);
            String keyAlias = "g" + groupIndex + "_key";
            String matchedAlias = "g" + groupIndex + "_matched_on";
            unnests.add(new UnnestSide(keyAlias, matchedAlias, ColumnGroupSql.unnestClause(
                    "t", names, !uniform, "u" + groupIndex, keyAlias, matchedAlias)));
            return new SideSql(alias + "." + keyAlias, uniform ? first : SqlTypeFamily.TEXT);
        }
        List<String> refs = names.stream().map(n -> alias + "." + n).toList();
        return new SideSql(ColumnGroupSql.combine(refs, group.separator()), SqlTypeFamily.TEXT);
    }

    private static SqlTypeFamily familyOf(Map<String, RegisteredColumn> columns, String column) {
        RegisteredColumn described = columns.get(column);
        return described == null ? SqlTypeFamily.UNKNOWN : SqlTypeFamily.of(described.dataType());
    }

    /**
     * The admin's {@code compare_as} for a group.
     *
     * <p>A multi-column COMBINE is folded with {@code array_join}, so it is
     * text by construction and nothing else can be honoured: reading a
     * concatenated name as a number would {@code TRY_CAST} every row to NULL
     * and return nothing, silently. Otherwise this is the single-column rule
     * unchanged — an explicit setting on either side wins, TEXT breaking a tie.
     */
    private CompareAs groupCompareAs(MatchGroup group) {
        if (group.mode() == GroupMode.COMBINE && !group.isSingleColumnPair()) {
            return CompareAs.TEXT;
        }
        return CompareAs.resolve(sideCompareAs(group.source()), sideCompareAs(group.target()));
    }

    private CompareAs sideCompareAs(List<MatchCriterion> columns) {
        for (MatchCriterion c : columns) {
            Optional<AnalysisColumnMetadata> meta = findMetadata(c);
            if (meta.isPresent()) {
                return meta.get().getCompareAs();
            }
        }
        return CompareAs.AUTO;
    }

    /**
     * AND-s every group into the ON clause. Never an OR: a disjunctive ON costs
     * Presto the hash join and falls back to a nested loop over the cross
     * product, which is why ANY_OF is pivoted with UNNEST in
     * {@link #sideSql} instead of being written as alternatives here.
     */
    private void appendJoinConditions(StringBuilder onClause, StringBuilder where,
                                      List<Object> onParams, List<Object> whereParams,
                                      List<GroupPlan> groups, JoinType joinType) {
        for (int i = 0; i < groups.size(); i++) {
            GroupPlan group = groups.get(i);
            if (onClause.length() > 0) {
                onClause.append(" AND ");
            }
            if (group.fuzzy()) {
                appendFuzzyJoin(onClause, where, onParams, whereParams, i, group, joinType);
            } else {
                onClause.append(group.sourceRef()).append(" = ").append(group.targetRef());
            }
        }
    }

    /**
     * Fuzzy pairs: blocking key always ON. Levenshtein similarity stays in WHERE
     * for INNER only — for outer joins it moves to ON so unmatched preserved-side
     * rows are not filtered away (see class javadoc / CLAUDE.md Analysis section).
     */
    private void appendFuzzyJoin(StringBuilder onClause, StringBuilder where,
                                 List<Object> onParams, List<Object> whereParams,
                                 int index, GroupPlan group, JoinType joinType) {
        if (group.group().fuzzyThresholdPercent() == null) {
            throw new IllegalArgumentException(
                    "sourceCriteria[" + index + "].fuzzyThresholdPercent is required for a name column");
        }
        double threshold = group.group().fuzzyThresholdPercent();
        if (threshold < 0 || threshold > 100) {
            throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
        }
        FuzzyOptions fuzzyOptions = group.group().fuzzyOptions();
        onClause.append(blockingKeyExpr(group.sourceRef(), fuzzyOptions)).append(" = ")
                .append(blockingKeyExpr(group.targetRef(), fuzzyOptions));
        String similarity = FuzzyMatchSql.similarityExpr(
                group.sourceRef(), group.targetRef(), fuzzyOptions) + " >= ?";
        if (joinType == JoinType.INNER) {
            appendWhereClause(where, similarity);
            whereParams.add(threshold / 100.0);
        } else {
            onClause.append(" AND ").append(similarity);
            onParams.add(threshold / 100.0);
        }
    }

    /** Fuzzy only when a threshold was sent and one of the two join columns contains "name". */
    private static boolean additionalJoinIsFuzzy(AdditionalJoin extra) {
        if (extra.fuzzyThresholdPercent() == null) {
            return false;
        }
        String source = extra.sourceColumn() == null ? "" : extra.sourceColumn().toLowerCase();
        String join = extra.joinColumn() == null ? "" : extra.joinColumn().toLowerCase();
        return source.contains("name") || join.contains("name");
    }

    private void appendAdditionalFuzzyJoin(
            StringBuilder onClause,
            StringBuilder where,
            List<Object> onParams,
            List<Object> whereParams,
            Sides sides,
            AdditionalJoin extra,
            String alias,
            Map<String, RegisteredColumn> described,
            JoinType joinType) {
        Double raw = extra.fuzzyThresholdPercent();
        if (raw == null || raw < 0 || raw > 100) {
            throw new IllegalArgumentException("fuzzyThresholdPercent must be between 0 and 100");
        }
        String left = TypeCoercion.asText(
                "src." + extra.sourceColumn(), familyOf(sides.sourceColumns(), extra.sourceColumn()));
        String right = TypeCoercion.asText(
                alias + "." + extra.joinColumn(), familyOf(described, extra.joinColumn()));
        FuzzyOptions options = extra.fuzzyOptions();
        onClause.append(blockingKeyExpr(left, options)).append(" = ").append(blockingKeyExpr(right, options));
        String similarity = FuzzyMatchSql.similarityExpr(left, right, options) + " >= ?";
        if (joinType == JoinType.INNER) {
            appendWhereClause(where, similarity);
            whereParams.add(raw / 100.0);
        } else {
            onClause.append(" AND ").append(similarity);
            onParams.add(raw / 100.0);
        }
    }

    private String appendDedupSelect(StringBuilder select, Set<String> outerColumns, RecordMatchRequest req,
                                     JoinPlan join) {
        if (req.dedup() == null) {
            return null;
        }
        QualifiedTable sourceTable = join.sourceColumns().get(0).qualifiedTable();
        String side = req.dedup().qualifiedTable().equals(sourceTable) ? "src" : "tgt";
        String dedupAlias = "dedup_last_updated";
        appendSelect(select, outerColumns, side, req.dedup().column(), dedupAlias);
        return dedupAlias;
    }

    /**
     * Match score reflects JOIN groups only — display-only columns do not enter
     * the average, and a group counts once however many columns it folds.
     */
    private void appendMatchScoreSelect(StringBuilder select, Set<String> outerColumns,
                                        RecordMatchRequest req, List<GroupPlan> groups) {
        if (!req.highlightDuplicates()) {
            return;
        }
        String scoreExpr = buildMatchScoreExpr(groups);
        select.append(", ").append(scoreExpr).append(" AS \"match_score_pct\"");
        outerColumns.add("match_score_pct");
    }

    /** Appends one AND-ed clause, adding the connector only when something precedes it. */
    private static void appendWhereClause(StringBuilder where, String clause) {
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
    }

    private static String wrapWithDedup(String baseSql, RecordMatchRequest req, JoinPlan join,
                                        String dedupAlias) {
        if (req.dedup() == null) {
            return baseSql;
        }
        // Partitions over every source-side JOIN column, across all groups. An
        // ANY_OF group can return the same hub key several times — once per
        // candidate column that matched — and this is what collapses them.
        String partitionCols = join.sourceColumns().stream()
                .map(c -> "\"source_" + c.column() + "\"")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        return "SELECT * FROM (SELECT base.*, ROW_NUMBER() OVER ("
                + "PARTITION BY " + partitionCols + " ORDER BY \"" + dedupAlias + "\" DESC) AS rn "
                + "FROM (" + baseSql + ") base) ranked WHERE rn = 1";
    }

    private StreamingResponseBody streamResults(
            MatchQuery query,
            java.util.function.Consumer<Throwable> onEngineFailure,
            PlanTrace trace) {
        String displaySql = renderForDisplay(query.routedSql(), query.params());
        return outputStream -> {
            long executeStarted = System.nanoTime();
            try {
                writeLine(outputStream, Map.of("type", "meta", "columns", query.columns(), "sql", displaySql));
                try {
                    JdbcTemplate executionJdbc = ScopedJdbc.withTimeout(
                            jdbcFor(query), guardrails.queryTimeoutSeconds());
                    ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
                    long[] totalRows = {0};
                    executionJdbc.query(query.routedSql(), query.params().toArray(), (RowCallbackHandler) rs -> {
                        Map<String, Object> row = rowMapper.mapRow(rs, 0);
                        try {
                            writeLine(outputStream, Map.of("type", "row", "data", row));
                        } catch (IOException e) {
                            JdbcStatements.cancel(rs.getStatement());
                            throw new UncheckedIOException(e);
                        }
                        totalRows[0]++;
                    });
                    writeLine(outputStream, Map.of("type", "done", "totalRows", totalRows[0]));
                } catch (UncheckedIOException e) {
                    log.warn("Match stream closed by the client; the query was cancelled");
                    throw e.getCause();
                } catch (Exception e) {
                    log.warn("Match query failed: {}", e.toString());
                    onEngineFailure.accept(e);
                    try {
                        writeLine(outputStream, Map.of(
                                "type", "error", "message", QueryFailureMessages.officerMessage(e)));
                    } catch (IOException io) {
                        log.warn("Could not report the match error because the client is gone");
                    }
                }
            } finally {
                if (trace != null) {
                    log.info("{}", planTimingMessage(
                            trace.planMs, elapsedMs(executeStarted), trace.tables, trace.joins, trace.estimateSource()));
                }
            }
        };
    }

    /**
     * Streams the result as CSV, one row at a time, holding nothing.
     *
     * <p>Unlike {@link #streamResults}, a failure part-way is NOT caught and
     * reported in-band. NDJSON can carry an {@code error} event because the
     * client parses events; a CSV cannot say anything a spreadsheet would not
     * read as data, and a file that simply stops looks exactly like a
     * complete one. Letting the exception abort the response instead means the
     * client's fetch rejects and the officer is told the export failed, rather
     * than quietly filing a truncated result.
     */
    private StreamingResponseBody streamCsv(MatchQuery query) {
        return outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.write('\uFEFF');
            MatchExportWriters.writeCsvRow(
                    writer, query.columns().stream().map(Object.class::cast).toList());
            MatchExportStreamer.streamRows(jdbcFor(query), guardrails.queryTimeoutSeconds(), query, row ->
                    MatchExportWriters.writeCsvRow(writer, MatchExportStreamer.columnValues(query, row)));
            writer.flush();
        };
    }

    private StreamingResponseBody streamJson(MatchQuery query) {
        return outputStream -> {
            ObjectMapper mapper = new ObjectMapper();
            try (JsonGenerator gen = mapper.getFactory().createGenerator(outputStream)) {
                gen.writeStartArray();
                MatchExportStreamer.streamRows(
                        jdbcFor(query),
                        guardrails.queryTimeoutSeconds(),
                        query,
                        MatchExportWriters.jsonRowSink(query.columns(), gen));
                gen.writeEndArray();
                gen.flush();
            }
        };
    }

    private StreamingResponseBody streamXml(MatchQuery query) {
        return outputStream -> {
            Writer writer = new BufferedWriter(MatchExportWriters.utf8Writer(outputStream));
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<matchRows>\n");
            MatchExportStreamer.streamRows(
                    jdbcFor(query),
                    guardrails.queryTimeoutSeconds(),
                    query,
                    MatchExportWriters.xmlRowSink(writer, query.columns()));
            writer.write("</matchRows>\n");
            writer.flush();
        };
    }

    private StreamingResponseBody streamExcel(MatchQuery query) {
        return outputStream -> MatchExportWriters.writeExcel(
                outputStream,
                query.columns(),
                body -> MatchExportStreamer.streamRows(
                        jdbcFor(query), guardrails.queryTimeoutSeconds(), query, body));
    }

    JdbcTemplate jdbcFor(MatchQuery query) {
        return executionRouter == null ? jdbc : executionRouter.jdbc(query.route());
    }

    public record MatchQuery(
            String sql,
            List<Object> params,
            List<String> columns,
            String routedSql,
            AnalysisExecutionRoute route) {
        public MatchQuery(String sql, List<Object> params, List<String> columns) {
            this(sql, params, columns, sql, AnalysisExecutionRoute.presto());
        }

        public MatchQuery {
            params = copyBindParams(params);
        }

        private static List<Object> copyBindParams(List<Object> params) {
            if (params == null || params.isEmpty()) {
                return List.of();
            }
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(params));
        }
    }

    private void writeLine(java.io.OutputStream out, Map<String, Object> payload) throws IOException {
        out.write(objectMapper.writeValueAsBytes(payload));
        out.write('\n');
        out.flush();
    }

    private String blockingKeyExpr(String columnRef, FuzzyOptions options) {
        return FuzzyMatchSql.blockingKeyExpr(
                columnRef, analysisProperties.blockingPrefixLen(), options);
    }

    /**
     * Equi-join fan-out estimate before the match query runs:
     * {@code sourceRows × targetRows × ∏ extraRows / ∏ max(leftDistinct, rightDistinct)}.
     * Additional tables use the same formula and the same catalog-statistics
     * shortcut. An empty additional list does not read those tables.
     * Distincts use the blocking-key expression on fuzzy groups. Refuses when above
     * {@link AnalysisProperties#maxEstimatedRows()}.
     */
    private void enforceEstimatedRowCeiling(
            Sides sides,
            List<GroupPlan> groups,
            ScopeFilteredFrom sourceScope,
            ScopeFilteredFrom targetScope,
            boolean groupedResult,
            List<AdditionalJoin> additionalJoins,
            List<ScopeFilteredFrom> extraScopes) {
        long ceiling = analysisProperties.maxEstimatedRows();
        if (ceiling <= 0 || groups.isEmpty()) {
            return;
        }
        List<Callable<TableStats>> statTasks = new ArrayList<>();
        statTasks.add(() -> sourceScope.appliesFilter() ? TableStats.EMPTY : readCatalogStats(sides.sourceTable()));
        statTasks.add(() -> targetScope.appliesFilter() ? TableStats.EMPTY : readCatalogStats(sides.targetTable()));
        for (int i = 0; i < additionalJoins.size(); i++) {
            ScopeFilteredFrom extraScope = extraScopes.get(i);
            QualifiedTable extraTable = additionalJoins.get(i).qualifiedTable();
            statTasks.add(() -> extraScope.appliesFilter() ? TableStats.EMPTY : readCatalogStats(extraTable));
        }
        List<TableStats> stats = invokeEstimate(statTasks);
        TableStats sourceStats = stats.get(0);
        TableStats targetStats = stats.get(1);

        List<Callable<Long>> rowTasks = new ArrayList<>();
        rowTasks.add(() -> rowCountFor(sides.sourceTable(), sourceScope, sourceStats));
        rowTasks.add(() -> rowCountFor(sides.targetTable(), targetScope, targetStats));
        for (int i = 0; i < additionalJoins.size(); i++) {
            QualifiedTable extraTable = additionalJoins.get(i).qualifiedTable();
            ScopeFilteredFrom extraScope = extraScopes.get(i);
            TableStats extraStats = stats.get(i + 2);
            rowTasks.add(() -> rowCountFor(extraTable, extraScope, extraStats));
        }
        List<Long> rowCounts = invokeEstimate(rowTasks);
        long sourceRows = rowCounts.get(0);
        long targetRows = rowCounts.get(1);
        if (sourceRows == 0 || targetRows == 0) {
            return;
        }
        long numerator = multiplyCap(sourceRows, targetRows, Long.MAX_VALUE);
        if (numerator <= 0) {
            return;
        }
        List<Callable<Long>> distinctTasks = new ArrayList<>();
        for (GroupPlan group : groups) {
            distinctTasks.add(() -> sideDistinctEstimate(
                    sides.sourceTable(), sourceScope, group.group().source(), group.fuzzy(),
                    group.group().fuzzyOptions(), sourceStats, sides.sourceColumns()));
            distinctTasks.add(() -> sideDistinctEstimate(
                    sides.targetTable(), targetScope, group.group().target(), group.fuzzy(),
                    group.group().fuzzyOptions(), targetStats, sides.targetColumns()));
        }
        List<Long> groupDistincts = invokeEstimate(distinctTasks);
        long denominator = 1;
        for (int i = 0; i < groups.size(); i++) {
            long groupMax = Math.max(groupDistincts.get(i * 2), groupDistincts.get(i * 2 + 1));
            if (groupMax <= 0) {
                return;
            }
            denominator = multiplyCap(denominator, groupMax, Long.MAX_VALUE);
            if (denominator <= 0) {
                return;
            }
        }
        long primaryNumerator = numerator;
        long primaryDenominator = denominator;
        List<ExtraFanOut> outerFactors = List.of();
        boolean anyOuter = false;
        if (!additionalJoins.isEmpty()) {
            List<Callable<Long>> extraDistinctTasks = new ArrayList<>();
            for (int i = 0; i < additionalJoins.size(); i++) {
                AdditionalJoin extra = additionalJoins.get(i);
                ScopeFilteredFrom extraScope = extraScopes.get(i);
                QualifiedTable extraTable = extra.qualifiedTable();
                TableStats extraStats = stats.get(i + 2);
                extraDistinctTasks.add(() -> sideDistinctEstimate(
                        sides.sourceTable(), sourceScope,
                        List.of(new MatchCriterion(
                                sides.sourceTable().catalog(), sides.sourceTable().schema(),
                                sides.sourceTable().table(), extra.sourceColumn(), null)),
                        false, null, sourceStats, sides.sourceColumns()));
                extraDistinctTasks.add(() -> sideDistinctEstimate(
                        extraTable, extraScope,
                        List.of(new MatchCriterion(
                                extra.catalog(), extra.schema(), extra.table(), extra.joinColumn(), null)),
                        false, null, extraStats, Map.of()));
            }
            List<Long> extraDistincts = invokeEstimate(extraDistinctTasks);
            outerFactors = new ArrayList<>(additionalJoins.size());
            for (int i = 0; i < additionalJoins.size(); i++) {
                AdditionalJoin extra = additionalJoins.get(i);
                long extraRows = rowCounts.get(i + 2);
                if (extraRows == 0) {
                    return;
                }
                long groupMax = Math.max(extraDistincts.get(i * 2), extraDistincts.get(i * 2 + 1));
                if (groupMax <= 0) {
                    return;
                }
                JoinType extraType = extra.effectiveJoinType();
                if (extraType != JoinType.INNER) {
                    anyOuter = true;
                }
                outerFactors.add(new ExtraFanOut(extraType, extraRows, groupMax));
                numerator = multiplyCap(numerator, extraRows, Long.MAX_VALUE);
                if (numerator <= 0) {
                    return;
                }
                denominator = multiplyCap(denominator, groupMax, Long.MAX_VALUE);
                if (denominator <= 0) {
                    return;
                }
            }
        }
        long estimate = numerator / denominator;
        if (numerator == Long.MAX_VALUE || estimate < 0) {
            estimate = ceiling + 1;
        }
        if (anyOuter) {
            estimate = outerExtraEstimate(primaryNumerator, primaryDenominator, outerFactors, ceiling);
        }
        if (estimate > ceiling) {
            String scopeNote = scopeEstimateNote(sourceScope, targetScope);
            String groupedNote = groupedResult
                    ? "That estimate is rows processed before grouping; the grouped result itself may be much smaller. "
                    : "";
            String extraNote = additionalJoins.isEmpty()
                    ? ""
                    : "Additional tables are included in this estimate. ";
            throw new IllegalArgumentException(
                    "Estimated match fan-out is about " + formatEstimate(estimate)
                            + " rows processed before the join"
                            + (groupedResult ? " (before grouping)" : "")
                            + " (limit " + formatEstimate(ceiling) + "). "
                            + groupedNote
                            + scopeNote
                            + extraNote
                            + "Try a longer blocking prefix (SRSE_ANALYSIS_BLOCKING_PREFIX_LEN), "
                            + "a more selective join key, or fewer folded groups.");
        }
    }

    /**
     * Outer extra joins cannot drop the side they preserve. Inner extras keep
     * the single division above. Left keeps at least the prior estimate, right
     * keeps at least the extra table, and full keeps both.
     */
    private static long outerExtraEstimate(
            long primaryNumerator,
            long primaryDenominator,
            List<ExtraFanOut> factors,
            long ceiling) {
        long running = primaryNumerator / primaryDenominator;
        if (primaryNumerator == Long.MAX_VALUE || running < 0) {
            running = ceiling + 1;
        }
        for (ExtraFanOut factor : factors) {
            long productNum = multiplyCap(running, factor.extraRows(), Long.MAX_VALUE);
            long product = productNum / factor.groupMax();
            if (productNum == Long.MAX_VALUE || product < 0) {
                product = ceiling + 1;
            }
            running = switch (factor.joinType()) {
                case LEFT -> Math.max(running, product);
                case RIGHT -> Math.max(product, factor.extraRows());
                case FULL -> Math.max(Math.max(running, product), factor.extraRows());
                default -> product;
            };
        }
        return running;
    }

    private static String scopeEstimateNote(ScopeFilteredFrom sourceScope, ScopeFilteredFrom targetScope) {
        if (!sourceScope.appliesFilter() && !targetScope.appliesFilter()) {
            return "";
        }
        return "Estimate uses officer scope row counts (catalog statistics do not reflect scope filters). ";
    }

    /**
     * Presto's catalog statistics for one table, when {@code ANALYZE} has been
     * run on it. Measured against ground truth on the local stack, the same
     * equi-join formula fed from these instead of from live aggregates landed
     * within 0.9% on a unique key and 0.00003% on a low-cardinality column —
     * for no query cost, because the numbers are already materialised.
     *
     * <p>Returns an EMPTY result rather than throwing when stats are missing,
     * unreadable, or the connector does not support them: the guard then
     * computes as it always did. A deployment that has never run ANALYZE must
     * keep working, just more slowly.
     */
    private TableStats readCatalogStats(QualifiedTable table) {
        if (executionRouter != null && executionRouter.containsExternal(table)) {
            return TableStats.EMPTY;
        }
        String key = table.qualifiedName();
        long now = System.currentTimeMillis();
        CachedCatalogStats cached = catalogStatsCache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            noteCatalogStats(cached.stats());
            return cached.stats();
        }
        synchronized (catalogStatsCache) {
            cached = catalogStatsCache.get(key);
            now = System.currentTimeMillis();
            if (cached != null && cached.expiresAtMillis() > now) {
                noteCatalogStats(cached.stats());
                return cached.stats();
            }
            TableStats loaded = loadCatalogStats(table);
            if (loaded == null) {
                return TableStats.EMPTY;
            }
            catalogStatsCache.put(key, new CachedCatalogStats(loaded, now + CATALOG_STATS_TTL_MS));
            noteCatalogStats(loaded);
            return loaded;
        }
    }

    /** Null when SHOW STATS failed. An empty success is cached; a failure is not. */
    private TableStats loadCatalogStats(QualifiedTable table) {
        Map<String, Long> distinct = new java.util.HashMap<>();
        long[] rowCount = {-1};
        try {
            jdbc.query("SHOW STATS FOR " + table.qualifiedName(), rs -> {
                String column = rs.getString("column_name");
                double distinctValues = rs.getDouble("distinct_values_count");
                boolean distinctNull = rs.wasNull();
                double rows = rs.getDouble("row_count");
                boolean rowsNull = rs.wasNull();
                if (column == null && !rowsNull) {
                    rowCount[0] = (long) rows;
                } else if (column != null && !distinctNull) {
                    distinct.put(column, (long) distinctValues);
                }
            });
        } catch (RuntimeException ex) {
            log.debug("No usable catalog statistics for {} — falling back to live aggregates: {}",
                    table.qualifiedName(), ex.toString());
            return null;
        }
        return new TableStats(rowCount[0] >= 0 ? rowCount[0] : null, Map.copyOf(distinct));
    }

    private record CachedCatalogStats(TableStats stats, long expiresAtMillis) {
    }

    private long rowCountFor(QualifiedTable table, ScopeFilteredFrom scope, TableStats stats) {
        if (scope.appliesFilter()) {
            return queryScopedRowCount(scope, table);
        }
        if (stats.rowCount() != null) {
            return stats.rowCount();
        }
        return queryRowCount(table);
    }

    private <T> List<T> invokeEstimate(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        if (tasks.size() == 1) {
            return List.of(callEstimate(tasks.get(0)));
        }
        PlanTrace trace = PLAN_TRACE.get();
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(ESTIMATE_POOL.submit(() -> {
                if (trace != null) {
                    PLAN_TRACE.set(trace);
                }
                try {
                    return task.call();
                } finally {
                    PLAN_TRACE.remove();
                }
            }));
        }
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Match estimate interrupted", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            }
        }
        return results;
    }

    private static <T> T callEstimate(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** One plan's estimate path. Consumed by the execute log so planning itself adds no query. */
    static final class PlanTrace {
        long planMs;
        int tables;
        String joins = "none";
        boolean catalogStats;
        boolean liveCount;

        String estimateSource() {
            if (catalogStats && liveCount) {
                return "mixed";
            }
            if (liveCount) {
                return "live-count";
            }
            if (catalogStats) {
                return "catalog-stats";
            }
            return "not-estimated";
        }
    }

    private static final ThreadLocal<PlanTrace> PLAN_TRACE = new ThreadLocal<>();

    PlanTrace takePlanTrace() {
        PlanTrace trace = PLAN_TRACE.get();
        PLAN_TRACE.remove();
        return trace;
    }

    static String planTimingMessage(long planMs, long executeMs, int tables, String joins, String estimate) {
        return "Match planMs=" + planMs
                + " executeMs=" + executeMs
                + " tables=" + tables
                + " joins=" + joins
                + " estimate=" + estimate;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String joinLabel(RecordMatchRequest req) {
        if (req.singleSource()) {
            return "none";
        }
        StringBuilder joins = new StringBuilder(JoinType.effective(req.joinType()).name());
        for (AdditionalJoin extra : req.additionalJoins()) {
            joins.append('+').append(extra.effectiveJoinType().name());
        }
        return joins.toString();
    }

    private void noteCatalogStats(TableStats stats) {
        if (stats.rowCount() == null && stats.distinctByColumn().isEmpty()) {
            return;
        }
        PlanTrace trace = PLAN_TRACE.get();
        if (trace != null) {
            trace.catalogStats = true;
        }
    }

    private void noteLiveCount() {
        PlanTrace trace = PLAN_TRACE.get();
        if (trace != null) {
            trace.liveCount = true;
        }
    }

    /** Catalog statistics for one table; every field may be absent. */
    private record TableStats(Long rowCount, Map<String, Long> distinctByColumn) {
        static final TableStats EMPTY = new TableStats(null, Map.of());

        Long distinctFor(String column) {
            return distinctByColumn.get(column);
        }
    }

    private long queryRowCount(QualifiedTable table) {
        noteLiveCount();
        String sql = "SELECT count(*) FROM " + table.qualifiedName();
        Long count = queryLong(sql, List.of(), table);
        return count == null ? 0 : Math.max(0, count);
    }

    private long queryScopedRowCount(ScopeFilteredFrom scope, QualifiedTable table) {
        if (scope.isEmptyResult()) {
            return 0;
        }
        noteLiveCount();
        String sql = "SELECT count(*) FROM " + scope.qualifiedName() + " t WHERE " + scope.whereSql();
        Long count = queryLong(sql, scope.bindValues(), table);
        return count == null ? 0 : Math.max(0, count);
    }

    private long sideDistinctEstimate(
            QualifiedTable table,
            ScopeFilteredFrom scope,
            List<MatchCriterion> columns,
            boolean fuzzy,
            FuzzyOptions fuzzyOptions,
            TableStats stats,
            Map<String, RegisteredColumn> described) {
        if (columns.isEmpty()) {
            return 1;
        }
        long product = 1;
        long ceiling = analysisProperties.maxEstimatedRows();
        String columnQualifier = scope.appliesFilter() ? "t" : table.qualifiedName();
        String fromExpr = scope.appliesFilter()
                ? scope.qualifiedName() + " t WHERE " + scope.whereSql()
                : table.qualifiedName();
        for (MatchCriterion c : columns) {
            // A fuzzy group joins on the blocking-key EXPRESSION, and no catalog
            // statistic describes substr(lower(col), 1, n) — that one is always
            // computed. Exact groups join on the bare column, which stats cover.
            // lower() accepts only text. The join already casts a numeric side;
            // the estimate has to do the same or Presto rejects lower(bigint).
            Long fromStats = (fuzzy || scope.appliesFilter()) ? null : stats.distinctFor(c.column());
            String columnRef = columnQualifier + "." + c.column();
            String valueExpr = fuzzy
                    ? blockingKeyExpr(
                            TypeCoercion.asText(columnRef, familyOf(described, c.column())),
                            fuzzyOptions)
                    : columnRef;
            long distinct = fromStats != null
                    ? fromStats
                    : queryApproxDistinct(fromExpr, scope, valueExpr, table);
            product = multiplyCap(product, distinct, ceiling);
        }
        return product;
    }

    private long queryApproxDistinct(
            String fromExpression,
            ScopeFilteredFrom scope,
            String valueExpression,
            QualifiedTable table) {
        noteLiveCount();
        boolean external = executionRouter != null && executionRouter.containsExternal(table);
        String aggregate = external
                ? "COUNT(DISTINCT " + valueExpression + ")"
                : "CAST(approx_distinct(" + valueExpression + ") AS BIGINT)";
        String sql = "SELECT " + aggregate + " FROM " + fromExpression;
        Long count = queryLong(sql, scope.appliesFilter() ? scope.bindValues() : List.of(), table);
        return count == null ? 0 : Math.max(0, count);
    }

    private Long queryLong(String sql, List<Object> params, QualifiedTable table) {
        return trackEstimate(table.qualifiedName(), () -> queryLongUnchecked(sql, params, table));
    }

    private Long queryLongUnchecked(String sql, List<Object> params, QualifiedTable table) {
        if (executionRouter == null) {
            return params.isEmpty()
                    ? jdbc.queryForObject(sql, Long.class)
                    : jdbc.queryForObject(sql, params.toArray(), Long.class);
        }
        AnalysisExecutionRouter.RoutedSql routed = executionRouter.route(sql, List.of(table));
        JdbcTemplate executionJdbc = ScopedJdbc.withTimeout(
                executionRouter.jdbc(routed.route()), guardrails.queryTimeoutSeconds());
        return params.isEmpty()
                ? executionJdbc.queryForObject(routed.sql(), Long.class)
                : executionJdbc.queryForObject(routed.sql(), params.toArray(), Long.class);
    }

    private <T> T trackEstimate(String qualifiedTable, java.util.concurrent.Callable<T> work) {
        if (queryMonitor == null) {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        try {
            return queryMonitor.trackStandalone(
                    gov.rajasthan.smart.srse.monitoring.ActiveQueryKind.ESTIMATE, qualifiedTable, work);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long multiplyCap(long a, long b, long ceiling) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        if (a > ceiling / b) {
            return ceiling + 1;
        }
        return a * b;
    }

    private static String formatEstimate(long value) {
        return String.format("%,d", value);
    }

    private static String buildMatchScoreExpr(List<GroupPlan> groups) {
        List<String> terms = new ArrayList<>();
        for (GroupPlan group : groups) {
            terms.add(group.fuzzy()
                    ? FuzzyMatchSql.similarityExpr(
                            group.sourceRef(), group.targetRef(), group.group().fuzzyOptions())
                    : "1.0");
        }
        String sum = String.join(" + ", terms);
        return "ROUND((" + sum + ") / " + terms.size() + " * 100, 1)";
    }

    /**
     * Whether a whole GROUP is fuzzy — a combined name is one value and gets
     * one decision, and one threshold.
     *
     * <p>Admin-registered {@link AnalysisColumnMetadata} takes precedence over
     * the name-substring guess. When a folded group's registrations DISAGREE —
     * a fuzzy {@code first_name} beside an exact {@code emp_code} — fuzzy wins.
     * The two mistakes are not symmetric: a group wrongly forced exact returns
     * almost nothing and reads to the officer as "these datasets do not
     * overlap", while one wrongly made fuzzy returns extra rows that carry
     * {@code match_score_pct} and are tunable with the threshold the officer
     * already controls. Visible over silent, the same trade
     * {@link CompareAs#resolve} makes when it lets TEXT win.
     *
     * <p>This MUST stay in sync with the frontend's own
     * fuzzy-eligibility check (analysis/page.tsx), or the officer could see a
     * Fuzzy % control that the backend then silently ignores (or vice versa: a
     * submitted threshold the backend never uses).
     */
    private boolean isGroupFuzzy(MatchGroup group) {
        List<MatchCriterion> columns = new ArrayList<>(group.source());
        columns.addAll(group.target());

        // Registered columns decide as a BLOC, and an unregistered column
        // sitting beside a registered one gets no vote — otherwise the
        // name-substring guess could overturn an explicit admin setting, which
        // is the one thing the single-column rule has always refused to do.
        boolean anyRegistered = false;
        for (MatchCriterion c : columns) {
            Optional<AnalysisColumnMetadata> meta = findMetadata(c);
            if (meta.isPresent()) {
                anyRegistered = true;
                if (meta.get().isFuzzyMatchable()) {
                    return true;
                }
            }
        }
        if (anyRegistered) {
            return false;
        }
        return columns.stream().anyMatch(c -> c.column().toLowerCase().contains("name"));
    }

    private Optional<AnalysisColumnMetadata> findMetadata(MatchCriterion c) {
        return columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                c.catalog(), c.schema(), c.table(), c.column());
    }

    private void appendSelect(StringBuilder select, Set<String> outerColumns,
                              String alias, String column, String outAlias) {
        if (select.length() > 0) {
            select.append(", ");
        }
        select.append(alias).append('.').append(column).append(" AS \"").append(outAlias).append('"');
        outerColumns.add(outAlias);
    }

    /**
     * The one QUALIFIED table every criterion on a side must share.
     *
     * <p>The comparison is on the full {@code catalog.schema.table} triple,
     * not the bare table name — with Silver and Gold layers both registered,
     * two criteria naming the same {@code tbl_txn_bankdtl} can legitimately
     * be two different physical tables, and treating them as one would emit a
     * join whose ON clause silently compared a table against itself.
     */
    private static QualifiedTable tableOf(List<MatchCriterion> criteria, String label) {
        if (criteria == null || criteria.isEmpty() || criteria.size() > MAX_CRITERIA_PER_SIDE) {
            throw new IllegalArgumentException(label + " must have 1 to " + MAX_CRITERIA_PER_SIDE + " entries");
        }
        return sameTable(criteria, label);
    }

    /**
     * The same one-table rule without the per-side column cap, for a list that
     * has already been bounded as GROUPS — eight groups of four columns is a
     * legitimate thirty-two entries, and counting them against the old
     * eight-picks limit would reject a request the group caps just allowed.
     */
    private static QualifiedTable sameTable(List<MatchCriterion> criteria, String label) {
        if (criteria == null || criteria.isEmpty()) {
            throw new IllegalArgumentException(label + " must have at least one entry");
        }
        QualifiedTable table = criteria.get(0).qualifiedTable();
        for (MatchCriterion c : criteria) {
            if (!table.equals(c.qualifiedTable())) {
                throw new IllegalArgumentException(label + " must all reference the same table");
            }
        }
        return table;
    }

    /**
     * Puts one side's columns through the registry gate and keeps what the
     * gate looked up — their live types, which the emitted SQL depends on.
     *
     * <p>One batched call rather than one per criterion: resolving a table's
     * columns walks the whole catalog/schema/table hierarchy, and every
     * criterion here shares one table by {@link #tableOf}'s check.
     */
    private Map<String, RegisteredColumn> describeRequestColumns(
            QualifiedTable table, RecordMatchRequest req, Ast.PredicateSpec rules) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (DisplayColumn column : req.sourceDisplayColumns()) {
            if (table.equals(column.qualifiedTable())) {
                names.add(column.column());
            }
        }
        addDescribedExtras(names, table, req, rules);
        return describeNames(table, names);
    }

    private Map<String, RegisteredColumn> describeSide(
            QualifiedTable table,
            List<MatchCriterion> criteria,
            List<DisplayColumn> display,
            List<MatchCriterion> alsoDescribe,
            RecordMatchRequest req,
            Ast.PredicateSpec rules) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        criteria.forEach(c -> names.add(c.column()));
        display.forEach(d -> names.add(d.column()));
        alsoDescribe.forEach(c -> names.add(c.column()));
        addDescribedExtras(names, table, req, rules);
        return describeNames(table, names);
    }

    private void addDescribedExtras(
            LinkedHashSet<String> names,
            QualifiedTable table,
            RecordMatchRequest req,
            Ast.PredicateSpec rules) {
        if (req.dedup() != null && table.equals(req.dedup().qualifiedTable())) {
            names.add(req.dedup().column());
        }
        for (DisplayColumn column : req.groupByColumns()) {
            if (table.equals(column.qualifiedTable())) {
                names.add(column.column());
            }
        }
        for (AggregateSpec aggregate : req.aggregates()) {
            if (aggregate.column() != null && table.equals(aggregate.column().qualifiedTable())) {
                names.add(aggregate.column().column());
            }
        }
        if (rules != null) {
            List<String> ruleColumns = new ArrayList<>();
            collectRuleColumns(rules.root(), table, ruleColumns);
            names.addAll(ruleColumns);
        }
    }

    private Map<String, RegisteredColumn> describeNames(QualifiedTable table, Collection<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        return registry.describeColumns(table, List.copyOf(names));
    }

    private void validateDisplayColumns(List<DisplayColumn> columns, QualifiedTable sideTable, String label) {
        if (columns.isEmpty()) {
            return;
        }
        for (int i = 0; i < columns.size(); i++) {
            DisplayColumn d = columns.get(i);
            if (!sideTable.equals(d.qualifiedTable())) {
                throw new IllegalArgumentException(label + "[" + i + "] must reference " + sideTable.qualifiedName());
            }
        }
    }

    private void validateSideMembership(QualifiedTable table, QualifiedTable sourceTable,
                                        QualifiedTable targetTable, String label) {
        if (!table.equals(sourceTable) && !table.equals(targetTable)) {
            throw new IllegalArgumentException(label + " must be the request's source or target table");
        }
    }

    /** Display-only rendering with params substituted as literals — never re-executed. */
    private String renderForDisplay(String sql, List<Object> params) {
        String rendered = sql;
        for (Object param : params) {
            rendered = rendered.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(literal(param)));
        }
        return rendered;
    }

    private String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
