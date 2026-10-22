package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseIdentifiers;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRoute;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRouter;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.execution.ScopedJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Distinct column values for the value-filter picker (§5.1). Runs through the
 * same scoped derived table as extract rules — never a raw table scan for officers.
 */
@Service
public class ColumnDistinctValuesService {

    private final JdbcTemplate jdbc;
    private final LakehouseRegistryService registry;
    private final AnalysisScopeFromService scopeFrom;
    private final AnalysisProperties analysisProperties;
    private final GuardrailProperties guardrails;

    @Autowired(required = false)
    private AnalysisExecutionRouter executionRouter;

    /** Test constructor. Runtime wiring uses the guardrail-aware constructor. */
    public ColumnDistinctValuesService(
            @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
            LakehouseRegistryService registry,
            AnalysisScopeFromService scopeFrom,
            AnalysisProperties analysisProperties) {
        this(jdbc, registry, scopeFrom, analysisProperties, new GuardrailProperties(1000, 30, 50));
    }

    @Autowired
    public ColumnDistinctValuesService(
            @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
            LakehouseRegistryService registry,
            AnalysisScopeFromService scopeFrom,
            AnalysisProperties analysisProperties,
            GuardrailProperties guardrails) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.scopeFrom = scopeFrom;
        this.analysisProperties = analysisProperties;
        this.guardrails = guardrails;
    }

    public PlannedColumnValues plan(ColumnValuesRequest req) {
        QualifiedColumn column = new QualifiedColumn(
                req.catalog(), req.schema(), req.table(), req.column());
        registry.validateColumn(column);
        registry.requireValuePickerEnabled(column);
        QualifiedTable table = column.table();
        ScopeFilteredFrom scope = scopeFrom.planFrom(table);
        if (scope.isEmptyResult()) {
            return new PlannedColumnValues(
                    "SELECT NULL WHERE 1=0",
                    List.of(),
                    analysisProperties.maxColumnDistinctValues(),
                    false);
        }

        LakehouseIdentifiers.requireSafe("column", column.column());
        int limit = analysisProperties.maxColumnDistinctValues();
        List<Object> params = new ArrayList<>();
        String from = scopedFromFragment(scope, params);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        String search = req.search();
        if (search != null && !search.isBlank()) {
            // Case-INSENSITIVE on both sides. The search box is offered precisely when
            // the list was truncated, so it is the officer's only way to reach a value
            // beyond the cap; a case-sensitive match means typing "jaipur" returns
            // nothing and they conclude the value does not exist — the very mistake
            // reporting truncation is meant to prevent. The term stays bound.
            where.append(" AND lower(CAST(scoped.").append(column.column())
                    .append(" AS VARCHAR)) LIKE lower(?)");
            params.add("%" + search.trim() + "%");
        }

        String sql = "SELECT DISTINCT scoped." + column.column() + " AS v FROM " + from
                + where + " ORDER BY v NULLS FIRST LIMIT " + (limit + 1);
        if (executionRouter == null) {
            return new PlannedColumnValues(sql, List.copyOf(params), limit, true);
        }
        AnalysisExecutionRouter.RoutedSql routed = executionRouter.route(sql, List.of(table));
        return new PlannedColumnValues(
                sql, List.copyOf(params), limit, true, routed.sql(), routed.route());
    }

    public ColumnValuesResponse execute(PlannedColumnValues planned) {
        if (!planned.executes()) {
            return new ColumnValuesResponse(List.of(), false, false);
        }
        JdbcTemplate executionJdbc = ScopedJdbc.withTimeout(
                executionRouter == null ? jdbc : executionRouter.jdbc(planned.route()),
                guardrails.queryTimeoutSeconds());
        List<Object> raw = executionJdbc.query(
                planned.routedSql(),
                planned.params().toArray(),
                (rs, rowNum) -> rs.getObject(1));
        return buildResponse(raw, planned.limit());
    }

    static ColumnValuesResponse buildResponse(List<Object> raw, int limit) {
        if (raw == null || raw.isEmpty()) {
            return new ColumnValuesResponse(List.of(), false, false);
        }
        boolean truncated = raw.size() > limit;
        List<Object> slice = truncated ? raw.subList(0, limit) : raw;
        boolean includesNull = false;
        List<String> values = new ArrayList<>();
        for (Object v : slice) {
            if (v == null) {
                includesNull = true;
                values.add(null);
            } else {
                values.add(String.valueOf(v));
            }
        }
        return new ColumnValuesResponse(values, truncated, includesNull);
    }

    private static String scopedFromFragment(ScopeFilteredFrom scope, List<Object> params) {
        if (!scope.appliesFilter()) {
            return scope.qualifiedName() + " scoped";
        }
        params.addAll(scope.bindValues());
        return "(SELECT * FROM " + scope.qualifiedName() + " t WHERE " + scope.whereSql() + ") scoped";
    }

    public record ColumnValuesRequest(
            String catalog, String schema, String table, String column, String search) {
    }

    public record ColumnValuesResponse(List<String> values, boolean truncated, boolean includesNull) {
        public ColumnValuesResponse {
            values = values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    public record PlannedColumnValues(
            String sql,
            List<Object> params,
            int limit,
            boolean executes,
            String routedSql,
            AnalysisExecutionRoute route) {
        public PlannedColumnValues(String sql, List<Object> params, int limit, boolean executes) {
            this(sql, params, limit, executes, sql, AnalysisExecutionRoute.presto());
        }

        public PlannedColumnValues {
            params = List.copyOf(params);
        }
    }
}
