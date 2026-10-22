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
        String colRef = columnRef(p.column().column(), columnFamily, p.operator(), p.value());
        return switch (p.operator()) {
            case EQ  -> bind(colRef + " = ?",  p.value(), params);
            case NE  -> bind(colRef + " <> ?", p.value(), params);
            case LT  -> bind(colRef + " < ?",  p.value(), params);
            case LTE -> bind(colRef + " <= ?", p.value(), params);
            case GT  -> bind(colRef + " > ?",  p.value(), params);
            case GTE -> bind(colRef + " >= ?", p.value(), params);

            case IS_TRUE  -> colRef + " = TRUE";
            case IS_FALSE -> colRef + " = FALSE";
            case IS_NULL  -> colRef + " IS NULL";
            case NOT_NULL -> colRef + " IS NOT NULL";

            case IN     -> emitIn(colRef, requireListValue(p.value(), "IN"), params, false);
            case NOT_IN -> emitIn(colRef, requireListValue(p.value(), "NOT_IN"), params, true);

            case BETWEEN -> emitBetween(colRef, requireListValue(p.value(), "BETWEEN"), params);

            case FUZZY_MATCH -> emitFuzzyMatch(p.column().column(), colRef, (List<Object>) p.value(), params, fuzzyCtx);
        };
    }

    private String columnRef(String columnName, SqlTypeFamily columnFamily,
                             Ast.Operator operator, Object value) {
        String bare = TABLE_ALIAS + "." + columnName;
        if (operator == Ast.Operator.IS_NULL
                || operator == Ast.Operator.NOT_NULL
                || operator == Ast.Operator.IS_TRUE
                || operator == Ast.Operator.IS_FALSE) {
            return bare;
        }
        if (columnFamily == SqlTypeFamily.BOOLEAN || columnFamily == SqlTypeFamily.TEMPORAL) {
            return bare;
        }
        if (value == null) {
            return bare;
        }
        return TypeCoercion.alignColumnToValue(bare, columnFamily, SqlTypeFamily.ofValue(value));
    }

    private String bind(String frag, Object value, List<Object> params) {
        params.add(value);
        return frag;
    }

    private String emitIn(String col, List<Object> values, List<Object> params, boolean negate) {
        if (values == null || values.isEmpty()) {
            return negate ? "TRUE" : "FALSE";
        }
        StringJoiner ph = new StringJoiner(", ", "(", ")");
        for (Object v : values) {
            ph.add("?");
            params.add(v);
        }
        return col + (negate ? " NOT IN " : " IN ") + ph;
    }

    /** Jackson may bind JSON arrays as {@code List<?>}; avoid a raw cast that becomes ClassCastException → 500. */
    private static List<Object> requireListValue(Object value, String operator) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(operator + " requires a list value");
        }
        return new ArrayList<>(list);
    }

    private String emitBetween(String col, List<Object> bounds, List<Object> params) {
        if (bounds == null || bounds.size() != 2) {
            throw new IllegalArgumentException("BETWEEN requires exactly two bounds");
        }
        params.add(bounds.get(0));
        params.add(bounds.get(1));
        return col + " BETWEEN ? AND ?";
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
