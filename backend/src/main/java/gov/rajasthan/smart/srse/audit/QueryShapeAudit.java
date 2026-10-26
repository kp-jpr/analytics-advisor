package gov.rajasthan.smart.srse.audit;

import gov.rajasthan.smart.srse.analysis.AdditionalJoin;
import gov.rajasthan.smart.srse.analysis.DisplayColumn;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.MultiTargetRecordMatchRequest;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.analysis.TargetMatchSpec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds audit metadata from analysis requests — placeholders only in SQL (A6). */
public final class QueryShapeAudit {

    private QueryShapeAudit() {
    }

    /**
     * Parameterised SQL as emitted by the compiler — never
     * {@code renderQueryForDisplay}, which inlines bound values.
     */
    public static String queryShapeFromSql(String sql) {
        if (sql == null) {
            return null;
        }
        if (sql.length() > 8192) {
            return sql.substring(0, 8192);
        }
        return sql;
    }

    public static String targetTablesFrom(MultiTargetRecordMatchRequest req) {
        Set<String> tables = new LinkedHashSet<>();
        addCriteriaTables(tables, req.hubCriteria());
        addDisplayTables(tables, req.hubDisplayColumns());
        if (req.targets() != null) {
            for (TargetMatchSpec target : req.targets()) {
                addCriteriaTables(tables, target.joinCriteria());
                addDisplayTables(tables, target.displayColumns());
            }
        }
        return String.join(",", tables);
    }

    public static String targetTablesFrom(RecordMatchRequest req) {
        Set<String> tables = new LinkedHashSet<>();
        addCriteriaTables(tables, req.sourceCriteria());
        addCriteriaTables(tables, req.targetCriteria());
        addDisplayTables(tables, req.sourceDisplayColumns());
        addDisplayTables(tables, req.targetDisplayColumns());
        if (req.joinGroups() != null) {
            req.joinGroups().forEach(g -> {
                addCriteriaTables(tables, g.source());
                addCriteriaTables(tables, g.target());
            });
        }
        if (req.comparisonGroups() != null) {
            req.comparisonGroups().forEach(g -> {
                addCriteriaTables(tables, g.source());
                addCriteriaTables(tables, g.target());
            });
        }
        if (req.additionalJoins() != null) {
            for (AdditionalJoin extra : req.additionalJoins()) {
                tables.add(extra.catalog() + "." + extra.schema() + "." + extra.table());
            }
        }
        return String.join(",", tables);
    }

    private static void addCriteriaTables(Set<String> tables, List<MatchCriterion> criteria) {
        if (criteria == null) {
            return;
        }
        for (MatchCriterion c : criteria) {
            tables.add(c.catalog() + "." + c.schema() + "." + c.table());
        }
    }

    private static void addDisplayTables(Set<String> tables, List<DisplayColumn> columns) {
        if (columns == null) {
            return;
        }
        for (DisplayColumn d : columns) {
            tables.add(d.catalog() + "." + d.schema() + "." + d.table());
        }
    }
}
