package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

import java.util.List;

/**
 * One more table joined to the source of an existing source/destination match.
 *
 * <p>The primary pair stays a two-table match. Each additional table is an exact
 * inner join on {@code sourceColumn = joinColumn} and is absent from requests
 * that only compare two tables.
 */
public record AdditionalJoin(
        String catalog,
        String schema,
        String table,
        String sourceColumn,
        String joinColumn,
        List<String> displayColumns) {

    public AdditionalJoin {
        displayColumns = displayColumns == null ? List.of() : List.copyOf(displayColumns);
    }

    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }
}
