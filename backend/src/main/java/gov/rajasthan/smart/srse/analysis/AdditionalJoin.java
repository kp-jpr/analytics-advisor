package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

import java.util.List;

/**
 * One more table joined to the source of an existing source/destination match.
 *
 * <p>The primary pair stays a two-table match. Each additional table joins on
 * {@code sourceColumn = joinColumn}. A null {@code joinType} is an inner join,
 * which is what requests stored before join types existed still produce.
 * {@code rules} are applied inside this table, the same way source and
 * destination rules are, and are absent when the officer set none.
 */
public record AdditionalJoin(
        String catalog,
        String schema,
        String table,
        String sourceColumn,
        String joinColumn,
        List<String> displayColumns,
        JoinType joinType,
        Ast.PredicateSpec rules) {

    public AdditionalJoin {
        displayColumns = displayColumns == null ? List.of() : List.copyOf(displayColumns);
    }

    /** Inner join and no rules. Existing call sites and saved queries use this shape. */
    @JsonIgnore
    public AdditionalJoin(
            String catalog,
            String schema,
            String table,
            String sourceColumn,
            String joinColumn,
            List<String> displayColumns) {
        this(catalog, schema, table, sourceColumn, joinColumn, displayColumns, null, null);
    }

    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }

    public JoinType effectiveJoinType() {
        return JoinType.effective(joinType);
    }
}
