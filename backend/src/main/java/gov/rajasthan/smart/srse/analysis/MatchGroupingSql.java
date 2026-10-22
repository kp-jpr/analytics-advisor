package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Emits and validates GROUP BY / aggregate SELECT for grouped matches (§5.5 / AA-14). */
final class MatchGroupingSql {

    static final String NULL_GROUP_LABEL = "(NULL)";

    private MatchGroupingSql() {}

    static void validateGroupedRequest(RecordMatchRequest req, int maxKeys, int maxAggregates) {
        if (!req.grouped()) {
            return;
        }
        if (req.dedup() != null) {
            throw new IllegalArgumentException("Dedup cannot be combined with grouping");
        }
        if (!req.comparisonGroups().isEmpty() || req.mismatchOnly()) {
            throw new IllegalArgumentException("Post-join comparisons cannot be combined with grouping");
        }
        if (req.groupByColumns().size() > maxKeys) {
            throw new IllegalArgumentException(
                    "At most " + maxKeys + " grouping columns allowed");
        }
        if (req.aggregates().size() > maxAggregates) {
            throw new IllegalArgumentException(
                    "At most " + maxAggregates + " aggregates allowed");
        }
        if (req.aggregates().isEmpty() && req.groupByColumns().isEmpty()) {
            throw new IllegalArgumentException("Grouping requires at least one group key or aggregate");
        }
    }

    static void validateColumnsOnSides(
            RecordMatchRequest req,
            QualifiedTable sourceTable,
            QualifiedTable targetTable,
            Map<String, RegisteredColumn> sourceColumns,
            Map<String, RegisteredColumn> targetColumns) {
        List<DisplayColumn> all = new ArrayList<>();
        all.addAll(req.groupByColumns());
        for (AggregateSpec agg : req.aggregates()) {
            if (agg.column() != null) {
                all.add(agg.column());
            }
        }
        for (DisplayColumn col : all) {
            QualifiedTable table = col.qualifiedTable();
            if (!table.equals(sourceTable) && !table.equals(targetTable)) {
                throw new IllegalArgumentException(
                        "Grouping column must be on source or target table: " + table.qualifiedName());
            }
        }
        for (AggregateSpec agg : req.aggregates()) {
            if (agg.column() == null) {
                continue;
            }
            RegisteredColumn described = columnOn(
                    agg.column(), sourceTable, targetTable, sourceColumns, targetColumns);
            validateAggregateType(agg, described);
        }
    }

    private static RegisteredColumn columnOn(
            DisplayColumn column,
            QualifiedTable sourceTable,
            QualifiedTable targetTable,
            Map<String, RegisteredColumn> sourceColumns,
            Map<String, RegisteredColumn> targetColumns) {
        Map<String, RegisteredColumn> side = column.qualifiedTable().equals(sourceTable)
                ? sourceColumns
                : targetColumns;
        if (!column.qualifiedTable().equals(sourceTable) && !column.qualifiedTable().equals(targetTable)) {
            throw new IllegalArgumentException(
                    "Grouping column must be on source or target table: " + column.qualifiedTable().qualifiedName());
        }
        RegisteredColumn described = side.get(column.column());
        if (described == null) {
            throw new IllegalArgumentException(
                    "Unknown or hidden column: " + column.qualifiedTable().qualifiedName() + "." + column.column());
        }
        return described;
    }

    static void validateAggregateType(AggregateSpec agg, RegisteredColumn described) {
        if (agg.countStar() || described == null) {
            return;
        }
        SqlTypeFamily family = SqlTypeFamily.of(described.dataType());
        if (agg.function() == AggregateFunction.SUM || agg.function() == AggregateFunction.AVG) {
            // TEXT is summable through TRY_CAST because Golden Layer data really does
            // keep numbers in varchar columns, and refusing would block ordinary work.
            // The total never travels alone: appendGroupedSelect emits a companion count
            // of the rows it could not add. BOOLEAN, TEMPORAL and UNKNOWN stay refused —
            // TRY_CAST(date AS DOUBLE) is null for every row, so the answer would be a
            // confident zero, which is worse than an error.
            if (family != SqlTypeFamily.NUMBER && family != SqlTypeFamily.TEXT) {
                throw new IllegalArgumentException(
                        agg.function() + " requires a numeric or text column; "
                                + agg.column().column() + " is " + described.dataType()
                                + ". Use COUNT, or MIN/MAX for ordering.");
            }
        }
    }

    /**
     * Live type per aggregated column, resolved once so emission need not re-introspect.
     * Keyed by fully qualified column name.
     */
    static Map<String, SqlTypeFamily> aggregateColumnTypes(
            RecordMatchRequest req,
            QualifiedTable sourceTable,
            QualifiedTable targetTable,
            Map<String, RegisteredColumn> sourceColumns,
            Map<String, RegisteredColumn> targetColumns) {
        Map<String, SqlTypeFamily> types = new java.util.HashMap<>();
        for (AggregateSpec agg : req.aggregates()) {
            if (agg.column() == null) {
                continue;
            }
            String key = aggregateTypeKey(agg);
            if (types.containsKey(key)) {
                continue;
            }
            RegisteredColumn described = columnOn(
                    agg.column(), sourceTable, targetTable, sourceColumns, targetColumns);
            types.put(key, SqlTypeFamily.of(described.dataType()));
        }
        return types;
    }

    private static String aggregateTypeKey(AggregateSpec agg) {
        return agg.column().qualifiedTable().qualifiedName() + "." + agg.column().column();
    }

    /** True when this aggregate needs a TRY_CAST, and therefore an unparseable count beside it. */
    private static boolean castsText(AggregateSpec agg, Map<String, SqlTypeFamily> types) {
        if (agg.column() == null) {
            return false;
        }
        if (agg.function() != AggregateFunction.SUM && agg.function() != AggregateFunction.AVG) {
            return false;
        }
        return types.get(aggregateTypeKey(agg)) == SqlTypeFamily.TEXT;
    }

    static void appendGroupedSelect(
            StringBuilder select,
            Set<String> outerColumns,
            RecordMatchRequest req,
            QualifiedTable sourceTable,
            QualifiedTable targetTable,
            Map<String, SqlTypeFamily> aggregateTypes) {
        boolean first = true;
        for (DisplayColumn g : req.groupByColumns()) {
            if (!first) {
                select.append(", ");
            }
            first = false;
            String side = sideAlias(g.qualifiedTable(), sourceTable, targetTable);
            String outAlias = allocateUniqueAlias(groupKeyAlias(g, side), outerColumns);
            String expr = side + "." + g.column();
            select.append("COALESCE(CAST(").append(expr).append(" AS VARCHAR), '")
                    .append(NULL_GROUP_LABEL).append("') AS \"").append(outAlias).append("\"");
        }
        for (AggregateSpec agg : req.aggregates()) {
            if (!first) {
                select.append(", ");
            }
            first = false;
            String side = agg.column() == null
                    ? null
                    : sideAlias(agg.column().qualifiedTable(), sourceTable, targetTable);
            String outAlias = allocateUniqueAlias(defaultAggregateAlias(agg), outerColumns);
            boolean cast = castsText(agg, aggregateTypes);
            select.append(aggregateExpression(agg, side, cast)).append(" AS \"").append(outAlias).append("\"");
            if (cast) {
                // Emitted ONLY where a cast happened — a sum over a genuinely numeric
                // column must not grow a column of zeroes. Counts non-null values that
                // failed to parse, not NULLs: SUM has always ignored NULLs, and an absent
                // value is not a lost one. This is the number that makes a total wrong.
                String ref = side + "." + agg.column().column();
                String countAlias = allocateUniqueAlias(outAlias + "_unparseable", outerColumns);
                select.append(", count_if(").append(ref).append(" IS NOT NULL AND TRY_CAST(")
                        .append(ref).append(" AS DOUBLE) IS NULL) AS \"")
                        .append(countAlias).append("\"");
            }
        }
        if (first) {
            throw new IllegalStateException("Grouped select must project at least one column");
        }
    }

    static String groupByClause(
            RecordMatchRequest req, QualifiedTable sourceTable, QualifiedTable targetTable) {
        if (req.groupByColumns().isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (DisplayColumn g : req.groupByColumns()) {
            String side = sideAlias(g.qualifiedTable(), sourceTable, targetTable);
            joiner.add(side + "." + g.column());
        }
        return joiner.toString();
    }

    static String sideAlias(DisplayColumn col, QualifiedTable sourceTable, QualifiedTable targetTable) {
        return sideAlias(col.qualifiedTable(), sourceTable, targetTable);
    }

    static String sideAlias(QualifiedTable table, QualifiedTable sourceTable, QualifiedTable targetTable) {
        if (table.equals(sourceTable)) {
            return "src";
        }
        if (table.equals(targetTable)) {
            return "tgt";
        }
        throw new IllegalArgumentException("Column table " + table.qualifiedName() + " is not source or target");
    }

    private static String sidePrefix(QualifiedTable table, QualifiedTable sourceTable, QualifiedTable targetTable) {
        return sideAlias(table, sourceTable, targetTable);
    }

    private static String aggregateExpression(AggregateSpec agg, String sideAlias, boolean castText) {
        return switch (agg.function()) {
            case COUNT -> {
                if (agg.countStar()) {
                    yield "count(*)";
                }
                String ref = sideAlias + "." + agg.column().column();
                yield agg.distinct() ? "count(DISTINCT " + ref + ")" : "count(" + ref + ")";
            }
            case SUM -> "sum(" + numericOperand(agg, sideAlias, castText) + ")";
            case AVG -> "avg(" + numericOperand(agg, sideAlias, castText) + ")";
            case MIN -> "min(" + sideAlias + "." + agg.column().column() + ")";
            case MAX -> "max(" + sideAlias + "." + agg.column().column() + ")";
        };
    }

    /** MIN and MAX are deliberately absent here: they already mean lexicographic ordering. */
    private static String numericOperand(AggregateSpec agg, String sideAlias, boolean castText) {
        String ref = sideAlias + "." + agg.column().column();
        return castText ? "TRY_CAST(" + ref + " AS DOUBLE)" : ref;
    }

    private static String groupKeyAlias(DisplayColumn g, String side) {
        return side + "_" + g.column();
    }

    private static String defaultAggregateAlias(AggregateSpec agg) {
        if (agg.alias() != null && !agg.alias().isBlank()) {
            return agg.alias().trim();
        }
        if (agg.countStar()) {
            return agg.distinct() ? "count_distinct_all" : "count_all";
        }
        String base = agg.function().name().toLowerCase() + "_" + agg.column().column();
        return agg.distinct() ? "count_distinct_" + agg.column().column() : base;
    }

    private static String allocateUniqueAlias(String baseAlias, Set<String> outerColumns) {
        String candidate = baseAlias;
        int suffix = 2;
        while (outerColumns.contains(candidate)) {
            candidate = baseAlias + "_" + suffix++;
        }
        outerColumns.add(candidate);
        return candidate;
    }

    /** Single-table grouped extract: target table equals source. */
    static void validateSingleSourceGrouped(RecordMatchRequest req, QualifiedTable table,
                                            Map<String, RegisteredColumn> columns,
                                            int maxKeys, int maxAggregates) {
        validateGroupedRequest(req, maxKeys, maxAggregates);
        validateColumnsOnSides(req, table, table, columns, columns);
    }
}
