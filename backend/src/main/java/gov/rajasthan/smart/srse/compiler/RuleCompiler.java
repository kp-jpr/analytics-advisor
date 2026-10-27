package gov.rajasthan.smart.srse.compiler;

import gov.rajasthan.smart.srse.analysis.AnalysisProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Rule-to-SQL compiler for Extract Records (§4.5).
 *
 * <p>Walks a {@link Ast.PredicateSpec} and emits a parameterised WHERE fragment
 * for use inside a scoped derived table ({@code t} alias). Values are always
 * bound; columns resolve only through {@link RuleColumnResolver}.
 */
@Component
public class RuleCompiler {

    private static final String TABLE_ALIAS = "t";
    /** Outer alias for single-source extract score projection (§5.4). */
    private static final String OUTER_ALIAS = "src";

    private final RuleColumnResolver columns;
    private final AnalysisProperties analysisProperties;

    public RuleCompiler(RuleColumnResolver columns, AnalysisProperties analysisProperties) {
        this.columns = columns;
        this.analysisProperties = analysisProperties;
    }

    public CompiledQuery compile(Ast.PredicateSpec spec, QualifiedTable table) {
        return compile(spec, table, Map.of());
    }

    public CompiledQuery compile(
            Ast.PredicateSpec spec, QualifiedTable table, Map<String, RegisteredColumn> knownColumns) {
        List<Object> params = new ArrayList<>();
        FuzzyMatchContext fuzzyCtx = new FuzzyMatchContext();
        String sql = emit(spec.root(), table, params, fuzzyCtx, knownColumns);
        CompiledQuery.FuzzyScoreProjection score = fuzzyCtx.buildScoreProjection();
        return new CompiledQuery(sql, params, score);
    }

    private String emit(
            Ast.Node node,
            QualifiedTable table,
            List<Object> params,
            FuzzyMatchContext fuzzyCtx,
            Map<String, RegisteredColumn> knownColumns) {
        if (node instanceof Ast.GroupNode g) {
            return emitGroup(g, table, params, fuzzyCtx, knownColumns);
        }
        if (node instanceof Ast.PredicateNode p) {
            return emitPredicate(p, table, params, fuzzyCtx, knownColumns);
        }
        throw new IllegalStateException("Unexpected node: " + node);
    }

    private String emitGroup(
            Ast.GroupNode g,
            QualifiedTable table,
            List<Object> params,
            FuzzyMatchContext fuzzyCtx,
            Map<String, RegisteredColumn> knownColumns) {
        String joiner = g.op() == Ast.BoolOp.AND ? " AND " : " OR ";
        StringJoiner sj = new StringJoiner(joiner, "(", ")");
        for (Ast.Node child : g.children()) {
            sj.add(emit(child, table, params, fuzzyCtx, knownColumns));
        }
        return sj.toString();
    }

    @SuppressWarnings("unchecked")
    private String emitPredicate(
            Ast.PredicateNode p,
            QualifiedTable table,
            List<Object> params,
            FuzzyMatchContext fuzzyCtx,
            Map<String, RegisteredColumn> knownColumns) {
        RegisteredColumn described = knownColumns.get(p.column().column());
        if (described == null) {
            described = columns.resolve(p.column(), table);
        }
        SqlTypeFamily columnFamily = SqlTypeFamily.of(described.dataType());
        String columnName = p.column().column();
        return switch (p.operator()) {
            case EQ  -> emitComparison(columnName, "=", described, columnFamily, p.value(), params);
            case NE  -> emitComparison(columnName, "<>", described, columnFamily, p.value(), params);
            case LT  -> emitComparison(columnName, "<", described, columnFamily, p.value(), params);
            case LTE -> emitComparison(columnName, "<=", described, columnFamily, p.value(), params);
            case GT  -> emitComparison(columnName, ">", described, columnFamily, p.value(), params);
            case GTE -> emitComparison(columnName, ">=", described, columnFamily, p.value(), params);

            case IS_TRUE  -> TABLE_ALIAS + "." + columnName + " = TRUE";
            case IS_FALSE -> TABLE_ALIAS + "." + columnName + " = FALSE";
            case IS_NULL  -> TABLE_ALIAS + "." + columnName + " IS NULL";
            case NOT_NULL -> TABLE_ALIAS + "." + columnName + " IS NOT NULL";

            case IN -> emitIn(columnName, described, columnFamily, requireListValue(p.value(), "IN"), params, false);
            case NOT_IN -> emitIn(columnName, described, columnFamily, requireListValue(p.value(), "NOT_IN"), params, true);

            case BETWEEN -> emitBetween(columnName, described, columnFamily, requireListValue(p.value(), "BETWEEN"), params);

            case FUZZY_MATCH -> emitFuzzyMatch(
                    columnName,
                    TypeCoercion.asText(TABLE_ALIAS + "." + columnName, columnFamily),
                    (List<Object>) p.value(),
                    params,
                    fuzzyCtx);
        };
    }

    /**
     * The column type is read first. A value JDBC would send as a different
     * kind is cast to that type. A real number filtered against a text column
     * still casts the column, so {@code 123} and {@code '0123'} can meet.
     */
    private String emitComparison(
            String columnName,
            String operator,
            RegisteredColumn described,
            SqlTypeFamily columnFamily,
            Object value,
            List<Object> params) {
        String bare = TABLE_ALIAS + "." + columnName;
        SqlTypeFamily bound = SqlTypeFamily.ofBound(value);
        String columnSql = bare;
        String valueSql = "?";
        if (columnFamily == SqlTypeFamily.TEXT && bound == SqlTypeFamily.NUMBER) {
            columnSql = TypeCoercion.asNumber(bare, columnFamily);
        } else if (TypeCoercion.shouldCastBoundValue(columnFamily, bound)) {
            valueSql = TypeCoercion.boundValueCast(described.dataType(), columnFamily);
        }
        params.add(value);
        return columnSql + " " + operator + " " + valueSql;
    }

    private String emitIn(
            String columnName,
            RegisteredColumn described,
            SqlTypeFamily columnFamily,
            List<Object> values,
            List<Object> params,
            boolean negate) {
        if (values == null || values.isEmpty()) {
            return negate ? "TRUE" : "FALSE";
        }
        String bare = TABLE_ALIAS + "." + columnName;
        boolean allNumbers = values.stream().allMatch(v -> v instanceof Number);
        String columnSql = bare;
        String valueSql = "?";
        if (columnFamily == SqlTypeFamily.TEXT && allNumbers) {
            columnSql = TypeCoercion.asNumber(bare, columnFamily);
        } else if (values.stream().anyMatch(v ->
                TypeCoercion.shouldCastBoundValue(columnFamily, SqlTypeFamily.ofBound(v)))) {
            valueSql = TypeCoercion.boundValueCast(described.dataType(), columnFamily);
        }
        StringJoiner ph = new StringJoiner(", ", "(", ")");
        for (Object v : values) {
            ph.add(valueSql);
            params.add(v);
        }
        return columnSql + (negate ? " NOT IN " : " IN ") + ph;
    }

    /** Jackson may bind JSON arrays as {@code List<?>}; avoid a raw cast that becomes ClassCastException → 500. */
    private static List<Object> requireListValue(Object value, String operator) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(operator + " requires a list value");
        }
        return new ArrayList<>(list);
    }

    private String emitBetween(
            String columnName,
            RegisteredColumn described,
            SqlTypeFamily columnFamily,
            List<Object> bounds,
            List<Object> params) {
        if (bounds == null || bounds.size() != 2) {
            throw new IllegalArgumentException("BETWEEN requires exactly two bounds");
        }
        String bare = TABLE_ALIAS + "." + columnName;
        boolean allNumbers = bounds.stream().allMatch(v -> v instanceof Number);
        String columnSql = bare;
        String valueSql = "?";
        if (columnFamily == SqlTypeFamily.TEXT && allNumbers) {
            columnSql = TypeCoercion.asNumber(bare, columnFamily);
        } else if (bounds.stream().anyMatch(v ->
                TypeCoercion.shouldCastBoundValue(columnFamily, SqlTypeFamily.ofBound(v)))) {
            valueSql = TypeCoercion.boundValueCast(described.dataType(), columnFamily);
        }
        params.add(bounds.get(0));
        params.add(bounds.get(1));
        return columnSql + " BETWEEN " + valueSql + " AND " + valueSql;
    }

    private String emitFuzzyMatch(
            String columnName,
            String colRef,
            List<Object> value,
            List<Object> params,
            FuzzyMatchContext fuzzyCtx) {
        if (value == null || value.size() < 2) {
            throw new IllegalArgumentException("FUZZY_MATCH requires [name, thresholdPercent]");
        }
        Object nameObj = value.get(0);
        if (!(nameObj instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("FUZZY_MATCH requires a non-blank name");
        }
        double thresholdPct = ((Number) value.get(1)).doubleValue();
        if (thresholdPct < 0 || thresholdPct > 100) {
            throw new IllegalArgumentException("FUZZY_MATCH threshold must be between 0 and 100");
        }
        FuzzyOptions options = parseFuzzyOptions(value);
        int prefixLen = analysisProperties.blockingPrefixLen();
        String blockingCol = FuzzyMatchSql.blockingKeyExpr(colRef, prefixLen, options);
        String blockingConst = FuzzyMatchSql.blockingKeyExpr("?", prefixLen, options);
        String similarity = FuzzyMatchSql.similarityExpr(colRef, "?", options);
        // One for the blocking constant, then however many the similarity needs — it
        // uses the right side twice, and binding once left the statement a parameter
        // short, so every typed-text fuzzy failed outright.
        params.add(name);
        params.addAll(FuzzyMatchSql.rightSideParams(name));
        params.add(thresholdPct / 100.0);
        fuzzyCtx.record(columnName, options, name, thresholdPct);
        return blockingCol + " = " + blockingConst + " AND " + similarity + " >= ?";
    }

    private static FuzzyOptions parseFuzzyOptions(List<Object> value) {
        if (value.size() >= 3 && value.get(2) instanceof Map<?, ?> map) {
            Boolean caseSensitive = map.get("caseSensitive") instanceof Boolean b ? b : null;
            Boolean ignoreSpaces = map.get("ignoreSpaces") instanceof Boolean b ? b : null;
            return new FuzzyOptions(caseSensitive, ignoreSpaces);
        }
        return null;
    }

    /** Captures the first FUZZY_MATCH for outer {@code match_score_pct} projection (§5.4). */
    private static final class FuzzyMatchContext {
        private String columnName;
        private FuzzyOptions options;
        private String boundName;
        private double thresholdPct;

        void record(String columnName, FuzzyOptions options, String boundName, double thresholdPct) {
            if (this.columnName != null) {
                return;
            }
            this.columnName = columnName;
            this.options = options;
            this.boundName = boundName;
            this.thresholdPct = thresholdPct;
        }

        CompiledQuery.FuzzyScoreProjection buildScoreProjection() {
            if (columnName == null) {
                return null;
            }
            String outerCol = OUTER_ALIAS + "." + columnName;
            String sim = FuzzyMatchSql.similarityExpr(outerCol, "?", FuzzyOptions.resolve(options));
            String expr = "ROUND((" + sim + ") * 100, 1)";
            return new CompiledQuery.FuzzyScoreProjection(
                    expr, FuzzyMatchSql.rightSideParams(boundName));
        }
    }
}
