package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.compiler.TypeCoercion;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRouter;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.execution.ScopedJdbc;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Metadata-first join-key hints for the Analysis tab. Every column returned
 * passes {@link LakehouseRegistryService#listColumns} — the same gate the match
 * engine uses — so a suggestion never names a column the picker cannot select.
 *
 * <p>Overlap probing samples the <strong>source</strong> side only
 * ({@code TABLESAMPLE BERNOULLI}) and scans the <strong>target</strong> table
 * in full for each probed pair — a semi-join bounded by
 * {@link AnalysisProperties#maxProbedPairs()} and
 * {@link GuardrailProperties#queryTimeoutSeconds()}. Expect one full target
 * scan per probed pair (costly by design; officer-triggered only).
 *
 * <p>Key-likeness ({@code approx_distinct/count} on the source) uses one
 * <strong>full-table</strong> aggregate per shortlisted source column — not
 * sampled: a sample inflates low-cardinality ratios by ~1/p and would mis-rank
 * mid-cardinality columns as keys. Only columns appearing in the top metadata
 * pairs are measured, so wide tables stay within the query timeout.
 */
@Service
public class JoinKeySuggestService {

    private static final Logger log = LoggerFactory.getLogger(JoinKeySuggestService.class);

    /** Bernoulli percentage on the source side only; stated in probe reasons. */
    static final int PROBE_SAMPLE_PERCENT = 10;

    /**
     * Columns below this {@code approx_distinct / count} ratio on the source are
     * treated as attributes (e.g. district), not join keys (e.g. id).
     */
    static final double MIN_SOURCE_KEY_DISTINCTNESS = 0.5;

    /** Top metadata pairs whose source columns are measured for key-likeness. */
    private static final int DISTINCTNESS_PAIR_SHORTLIST = 50;

    private final LakehouseRegistryService registry;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final AnalysisProperties analysisProperties;
    private final GuardrailProperties guardrails;
    private final JdbcTemplate jdbc;
    private final AnalysisScopeFromService scopeFrom;
    private final AnalysisExecutionRouter executionRouter;

    public JoinKeySuggestService(LakehouseRegistryService registry,
                                 AnalysisColumnMetadataRepository columnMetadata,
                                 AnalysisProperties analysisProperties,
                                 GuardrailProperties guardrails,
                                 @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                                 AnalysisScopeFromService scopeFrom) {
        this(registry, columnMetadata, analysisProperties, guardrails, jdbc, scopeFrom, null);
    }

    @Autowired
    public JoinKeySuggestService(LakehouseRegistryService registry,
                                 AnalysisColumnMetadataRepository columnMetadata,
                                 AnalysisProperties analysisProperties,
                                 GuardrailProperties guardrails,
                                 @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
                                 AnalysisScopeFromService scopeFrom,
                                 @Autowired(required = false) AnalysisExecutionRouter executionRouter) {
        this.registry = registry;
        this.columnMetadata = columnMetadata;
        this.analysisProperties = analysisProperties;
        this.guardrails = guardrails;
        this.jdbc = jdbc;
        this.scopeFrom = scopeFrom;
        this.executionRouter = executionRouter;
    }

    public List<JoinKeySuggestion> suggest(SuggestJoinKeysRequest req) {
        QualifiedTable source = new QualifiedTable(req.sourceCatalog(), req.sourceSchema(), req.sourceTable());
        QualifiedTable target = new QualifiedTable(req.targetCatalog(), req.targetSchema(), req.targetTable());

        if (executionRouter != null && executionRouter.containsExternal(source, target)) {
            throw new IllegalArgumentException(
                    "Join key suggestions are not available for connected database tables yet");
        }

        List<RegisteredColumn> sourceCols = registry.listColumnsLive(
                source.catalog(), source.schema(), source.table());
        List<RegisteredColumn> targetCols = registry.listColumnsLive(
                target.catalog(), target.schema(), target.table());

        List<ScoredPair> ranked = rankMetadataPairs(source, target, sourceCols, targetCols, Map.of());
        ranked.sort(metadataComparator());

        if (req.probeRequested() && !ranked.isEmpty()) {
            try {
                List<String> distinctnessColumns = shortlistSourceColumnsForDistinctness(ranked);
                Map<String, Double> sourceDistinctness = loadSourceDistinctnessRatios(source, distinctnessColumns);
                reapplyDistinctnessScores(ranked, sourceDistinctness);
                ranked.sort(metadataComparator());
            } catch (RuntimeException ex) {
                log.warn("Join-key source distinctness failed — continuing with metadata-only ranking: {}",
                        ex.getMessage());
            }
        }

        if (req.probeRequested() && !ranked.isEmpty()) {
            try {
                applyProbeOverlaps(source, target, ranked);
                ranked.sort(probeAwareComparator());
            } catch (RuntimeException ex) {
                log.warn("Join-key overlap probe failed — returning metadata-only suggestions: {}", ex.getMessage());
            }
        }

        return ranked.stream()
                .limit(20)
                .map(ScoredPair::toSuggestion)
                .toList();
    }

    private static Comparator<ScoredPair> metadataComparator() {
        return Comparator.comparingInt(ScoredPair::score).reversed()
                .thenComparing(sameTypeFamilyFirst())
                .thenComparing(p -> p.source().name())
                .thenComparing(p -> p.target().name());
    }

    /**
     * When overlap was measured, sort by overlap first so 0% cannot beat a real key.
     * One {@code reversed()} on the overlap+score chain (not per-key — a second
     * {@code reversed()} on {@code thenComparingInt} inverts the whole comparator).
     */
    private static Comparator<ScoredPair> probeAwareComparator() {
        return Comparator
                .comparingDouble((ScoredPair p) -> p.overlapRatio != null ? p.overlapRatio : -1.0)
                .thenComparingInt(ScoredPair::score)
                .reversed()
                .thenComparing(sameTypeFamilyFirst())
                .thenComparing(p -> p.source().name())
                .thenComparing(p -> p.target().name());
    }

    /** Within a score tier, prefer bigint↔bigint over bigint↔varchar identifier pairs. */
    private static Comparator<ScoredPair> sameTypeFamilyFirst() {
        return Comparator.comparing((ScoredPair p) -> p.sourceFamily() == p.targetFamily()).reversed();
    }

    /** Same-package tests: build a pair and run {@link #probeAwareComparator()}. */
    static ScoredPair testScoredPair(String sourceName, String sourceType, String targetName, String targetType,
                                     int score, Double overlapRatio) {
        RegisteredColumn source = new RegisteredColumn(sourceName, sourceType, null, false, true);
        RegisteredColumn target = new RegisteredColumn(targetName, targetType, null, false, true);
        SqlTypeFamily sourceFamily = SqlTypeFamily.of(sourceType);
        SqlTypeFamily targetFamily = SqlTypeFamily.of(targetType);
        ScoredPair pair = new ScoredPair(source, target, sourceFamily, targetFamily, score, "");
        pair.overlapRatio = overlapRatio;
        return pair;
    }

    static void sortProbeAware(List<ScoredPair> ranked) {
        ranked.sort(probeAwareComparator());
    }

    private List<ScoredPair> rankMetadataPairs(
            QualifiedTable source,
            QualifiedTable target,
            List<RegisteredColumn> sourceCols,
            List<RegisteredColumn> targetCols,
            Map<String, Double> sourceDistinctness) {
        List<ScoredPair> pairs = new ArrayList<>();
        for (RegisteredColumn s : sourceCols) {
            SqlTypeFamily sFamily = SqlTypeFamily.of(s.dataType());
            if (sFamily == SqlTypeFamily.UNKNOWN) {
                continue;
            }
            for (RegisteredColumn t : targetCols) {
                SqlTypeFamily tFamily = SqlTypeFamily.of(t.dataType());
                if (tFamily == SqlTypeFamily.UNKNOWN || !typesCompatible(sFamily, tFamily)) {
                    continue;
                }
                int score = scorePair(s, t, sFamily, tFamily, sourceDistinctness);
                if (score <= 0) {
                    continue;
                }
                pairs.add(new ScoredPair(s, t, sFamily, tFamily, score,
                        reasonFor(s, t, sFamily, tFamily, score, null)));
            }
        }
        return pairs;
    }

    /**
     * Cross-family TEXT↔NUMBER is allowed (documented match path). Temporal and
     * boolean are never coerced — exclude pairs where exactly one side is either.
     */
    private static boolean typesCompatible(SqlTypeFamily left, SqlTypeFamily right) {
        if (left == SqlTypeFamily.UNKNOWN || right == SqlTypeFamily.UNKNOWN) {
            return false;
        }
        if (left == SqlTypeFamily.TEMPORAL || right == SqlTypeFamily.TEMPORAL) {
            return left == right;
        }
        if (left == SqlTypeFamily.BOOLEAN || right == SqlTypeFamily.BOOLEAN) {
            return left == right;
        }
        TypeCoercion.align("l", left, "r", right, CompareAs.AUTO);
        return true;
    }

    static int scorePair(RegisteredColumn s, RegisteredColumn t,
                         SqlTypeFamily sFamily, SqlTypeFamily tFamily,
                         Map<String, Double> sourceDistinctness) {
        int base = metadataBaseScore(s, t, sFamily, tFamily);
        if (sourceDistinctness.isEmpty()) {
            return base;
        }
        Double ratio = sourceDistinctness.get(s.name());
        if (ratio == null) {
            return base;
        }
        return applyDistinctnessToScore(base, ratio);
    }

    static int metadataBaseScore(RegisteredColumn s, RegisteredColumn t,
                                 SqlTypeFamily sFamily, SqlTypeFamily tFamily) {
        if (s.name().equalsIgnoreCase(t.name()) && sFamily == tFamily) {
            return 400;
        }
        if (businessNamesMatch(s, t)) {
            return 300;
        }
        if (idMatchesSuffixIdColumn(s.name(), t.name())) {
            return 250;
        }
        String ns = normalizeKeyName(s.name());
        String nt = normalizeKeyName(t.name());
        if (ns.equals(nt) && !ns.isEmpty()) {
            return 250;
        }
        if (ns.length() >= 4 && nt.length() >= 4 && (ns.contains(nt) || nt.contains(ns))) {
            return 200;
        }
        if (AnalysisColumnMatchHeuristics.nameSubstringGuess(s.name())
                && AnalysisColumnMatchHeuristics.nameSubstringGuess(t.name())) {
            return 100;
        }
        return 50;
    }

    /**
     * {@code id} on one side against any {@code *_id} on the other (e.g. beneficiary
     * {@code id} ↔ txn {@code m_id}).
     */
    static boolean idMatchesSuffixIdColumn(String left, String right) {
        return bareIdColumn(left) && endsWithIdSuffix(right) && !left.equalsIgnoreCase(right)
                || bareIdColumn(right) && endsWithIdSuffix(left) && !left.equalsIgnoreCase(right);
    }

    private static boolean bareIdColumn(String name) {
        return "id".equalsIgnoreCase(name.trim());
    }

    private static boolean endsWithIdSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("_id") && !lower.equals("id");
    }

    /**
     * Low-cardinality source columns (district) must not outrank true keys (id)
     * when name matching alone would tie them at 1.0 containment.
     */
    static int applyDistinctnessToScore(int baseScore, double sourceDistinctnessRatio) {
        double ratio = Math.min(1.0, sourceDistinctnessRatio);
        if (ratio < MIN_SOURCE_KEY_DISTINCTNESS) {
            return Math.min(baseScore, 49);
        }
        return baseScore + (int) Math.round(ratio * 100);
    }

    private static boolean businessNamesMatch(RegisteredColumn s, RegisteredColumn t) {
        if (s.businessName() == null || t.businessName() == null) {
            return false;
        }
        return s.businessName().trim().equalsIgnoreCase(t.businessName().trim());
    }

    static String normalizeKeyName(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("_id", "_no", "_num", "_code")) {
            if (s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
            }
        }
        return s.replace("_", "");
    }

    private String reasonFor(RegisteredColumn s, RegisteredColumn t,
                             SqlTypeFamily sFamily, SqlTypeFamily tFamily,
                             int score, Double overlapRatio) {
        if (overlapRatio != null) {
            int pct = (int) Math.round(overlapRatio * 100);
            return pct + "% of sampled source values found in target (source TABLESAMPLE BERNOULLI "
                    + PROBE_SAMPLE_PERCENT + "%, target scanned in full; approx_distinct estimate — not a row count)";
        }
        if (score >= 400) {
            return "Same column name, both " + sFamily.name();
        }
        if (score >= 300) {
            return "Business name match";
        }
        if (score >= 250) {
            if (idMatchesSuffixIdColumn(s.name(), t.name())) {
                return "Identifier column match (id ↔ " + (bareIdColumn(s.name()) ? t.name() : s.name()) + ")";
            }
            return "Normalised name match (" + normalizeKeyName(s.name()) + ")";
        }
        return "Compatible types (" + sFamily.name() + " / " + tFamily.name() + ")";
    }

    private static List<String> shortlistSourceColumnsForDistinctness(List<ScoredPair> ranked) {
        Set<String> names = new LinkedHashSet<>();
        int limit = Math.min(DISTINCTNESS_PAIR_SHORTLIST, ranked.size());
        for (int i = 0; i < limit; i++) {
            names.add(ranked.get(i).source().name());
        }
        return List.copyOf(names);
    }

    private void reapplyDistinctnessScores(List<ScoredPair> ranked, Map<String, Double> sourceDistinctness) {
        for (ScoredPair pair : ranked) {
            int score = scorePair(pair.source(), pair.target(), pair.sourceFamily(), pair.targetFamily(),
                    sourceDistinctness);
            pair.score = score;
            pair.reason = reasonFor(pair.source(), pair.target(), pair.sourceFamily(), pair.targetFamily(),
                    score, pair.overlapRatio);
        }
    }

    private Map<String, Double> loadSourceDistinctnessRatios(QualifiedTable source, List<String> columnNames) {
        if (columnNames.isEmpty()) {
            return Map.of();
        }
        ScopeFilteredFrom sourceScope = scopeFrom.planFrom(source);
        String sql = buildSourceDistinctnessSql(sourceScope, columnNames);
        JdbcTemplate timed = ScopedJdbc.withTimeout(jdbc, analysisProperties.joinKeyDistinctnessTimeoutSeconds());
        RowMapper<Map<String, Double>> mapper = (rs, rowNum) -> readDistinctnessRow(rs, columnNames);
        List<Map<String, Double>> rows = sourceScope.appliesFilter()
                ? timed.query(sql, sourceScope.bindValues().toArray(), mapper)
                : timed.query(sql, mapper);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** Full source-table scan — one aggregate row, named columns only. */
    static String buildSourceDistinctnessSql(ScopeFilteredFrom sourceScope, List<String> columnNames) {
        StringBuilder exprs = new StringBuilder();
        for (String name : columnNames) {
            if (!exprs.isEmpty()) {
                exprs.append(", ");
            }
            exprs.append("LEAST(1.0, CAST(approx_distinct(").append(name)
                    .append(") AS DOUBLE) / NULLIF(CAST(count(*) AS DOUBLE), 0)) AS d_")
                    .append(name.replace('.', '_'));
        }
        String from = sourceScope.appliesFilter()
                ? "(SELECT * FROM " + sourceScope.qualifiedName() + " t WHERE " + sourceScope.whereSql() + ")"
                : sourceScope.qualifiedName();
        return "SELECT " + exprs + " FROM " + from;
    }

    private static Map<String, Double> readDistinctnessRow(ResultSet rs, List<String> columnNames) throws SQLException {
        Map<String, Double> out = new HashMap<>();
        for (String col : columnNames) {
            String alias = "d_" + col.replace('.', '_');
            double v = rs.getDouble(alias);
            if (!rs.wasNull()) {
                out.put(col, v);
            }
        }
        return out;
    }

    private void applyProbeOverlaps(QualifiedTable source, QualifiedTable target, List<ScoredPair> ranked) {
        int limit = Math.min(analysisProperties.maxProbedPairs(), ranked.size());
        for (int i = 0; i < limit; i++) {
            ScoredPair pair = ranked.get(i);
            Double ratio = probeOverlap(source, target, pair);
            pair.overlapRatio = ratio;
            if (ratio != null) {
                pair.reason = reasonFor(pair.source(), pair.target(), pair.sourceFamily(), pair.targetFamily(),
                        pair.score, ratio);
            }
        }
    }

    private Double probeOverlap(QualifiedTable source, QualifiedTable target, ScoredPair pair) {
        CompareAs leftMode = compareAs(source, pair.source().name());
        CompareAs rightMode = compareAs(target, pair.target().name());
        CompareAs mode = CompareAs.resolve(leftMode, rightMode);
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                "s.v", pair.sourceFamily(),
                "t.v", pair.targetFamily(),
                mode);

        ScopeFilteredFrom sourceScope = scopeFrom.planFrom(source);
        ScopeFilteredFrom targetScope = scopeFrom.planFrom(target);
        String sql = buildProbeOverlapSql(
                sourceScope, targetScope, pair.source(), pair.target(), aligned);
        List<Object> params = new ArrayList<>();
        if (sourceScope.appliesFilter()) {
            params.addAll(sourceScope.bindValues());
        }
        if (targetScope.appliesFilter()) {
            params.addAll(targetScope.bindValues());
        }
        JdbcTemplate timed = ScopedJdbc.withTimeout(jdbc, guardrails.queryTimeoutSeconds());
        Double ratio = params.isEmpty()
                ? timed.queryForObject(sql, Double.class)
                : timed.queryForObject(sql, params.toArray(), Double.class);
        if (ratio == null || ratio.isNaN()) {
            return null;
        }
        // HyperLogLog in approx_distinct can slightly exceed 1.0 (e.g. 88,749 vs 86,000 rows) — not a correctness guard.
        return Math.min(1.0, ratio);
    }

    /**
     * Semi-join overlap: distinct sampled source values with a target match /
     * distinct sampled source values. Target is not sampled.
     */
    static String buildProbeOverlapSql(
            ScopeFilteredFrom sourceScope,
            ScopeFilteredFrom targetScope,
            RegisteredColumn sourceCol,
            RegisteredColumn targetCol,
            TypeCoercion.Aligned aligned) {
        String sourceFrom = scopedSampleFrom(sourceScope);
        String targetFrom = scopedFullFrom(targetScope);
        return """
                SELECT CAST(approx_distinct(CASE WHEN t.v IS NOT NULL THEN s.v END) AS DOUBLE)
                     / NULLIF(CAST(approx_distinct(s.v) AS DOUBLE), 0)
                FROM (
                  SELECT %s AS v FROM %s TABLESAMPLE BERNOULLI (%d) WHERE %s IS NOT NULL
                ) s
                LEFT JOIN (
                  SELECT %s AS v FROM %s WHERE %s IS NOT NULL
                ) t ON %s = %s
                """.formatted(
                sourceCol.name(), sourceFrom, PROBE_SAMPLE_PERCENT, sourceCol.name(),
                targetCol.name(), targetFrom, targetCol.name(),
                aligned.left(), aligned.right());
    }

    private static String scopedSampleFrom(ScopeFilteredFrom scope) {
        if (!scope.appliesFilter()) {
            return scope.qualifiedName();
        }
        return "(SELECT * FROM " + scope.qualifiedName() + " t WHERE " + scope.whereSql() + ")";
    }

    private static String scopedFullFrom(ScopeFilteredFrom scope) {
        if (!scope.appliesFilter()) {
            return scope.qualifiedName();
        }
        return "(SELECT * FROM " + scope.qualifiedName() + " t WHERE " + scope.whereSql() + ")";
    }

    private CompareAs compareAs(QualifiedTable table, String column) {
        return columnMetadata
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        table.catalog(), table.schema(), table.table(), column)
                .map(AnalysisColumnMetadata::getCompareAs)
                .orElse(CompareAs.AUTO);
    }

    static final class ScoredPair {
        private final RegisteredColumn source;
        private final RegisteredColumn target;
        private final SqlTypeFamily sourceFamily;
        private final SqlTypeFamily targetFamily;
        private int score;
        private String reason;
        private Double overlapRatio;

        private ScoredPair(RegisteredColumn source, RegisteredColumn target,
                           SqlTypeFamily sourceFamily, SqlTypeFamily targetFamily,
                           int score, String reason) {
            this.source = source;
            this.target = target;
            this.sourceFamily = sourceFamily;
            this.targetFamily = targetFamily;
            this.score = score;
            this.reason = reason;
        }

        RegisteredColumn source() {
            return source;
        }

        RegisteredColumn target() {
            return target;
        }

        SqlTypeFamily sourceFamily() {
            return sourceFamily;
        }

        SqlTypeFamily targetFamily() {
            return targetFamily;
        }

        int score() {
            return score;
        }

        JoinKeySuggestion toSuggestion() {
            return new JoinKeySuggestion(
                    source.name(), target.name(), source.dataType(), target.dataType(), reason);
        }
    }
}
