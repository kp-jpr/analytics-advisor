package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.RuleColumnResolver;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * PRODUCT_PLAN 10.B.6 (was 7.2.6) — Group By must aggregate only rows the officer may see.
 * {@link RecordMatchGroupingTest} proves it for single-source SQL; this proves it for a grouped
 * two-table join of every type, for the grouped CSV export, and for an unentitled officer.
 *
 * <p>The property: each side's scope filter lives in its own derived table in FROM, so it is
 * applied BEFORE the join and therefore before GROUP BY — never in an outer WHERE, where an
 * outer join could make it a no-op for the preserved side.
 */
@ExtendWith(MockitoExtension.class)
class GroupedScopeEnforcementTest {

    private static final QualifiedTable SOURCE = new QualifiedTable("iceberg", "srse", "beneficiary");
    private static final QualifiedTable TARGET =
            new QualifiedTable("iceberg_silver", "silver_txn", "tbl_txn_bankdtl");
    private static final String SCOPE_SQL = "district_code IN (?)";

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private LakehouseRegistryService registry;
    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;
    @Mock
    private AnalysisScopeFromService scopeFrom;

    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        AnalysisProperties properties =
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000);
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        lenient().when(jdbc.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(1L);
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(inv -> {
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            for (Object column : inv.getArgument(1, List.class)) {
                String name = String.valueOf(column);
                described.put(name, new RegisteredColumn(name, "bigint", null, false, true));
            }
            return described;
        });
        service = new RecordMatchService(
                jdbc, registry, new GuardrailProperties(1000, 30, 50), columnMetadata, properties,
                new ObjectMapper(), scopeFrom,
                new RuleCompiler(new RuleColumnResolver(registry), properties));
    }

    private void scopeSides(String sourceValue, String targetValue) {
        lenient().when(scopeFrom.planFrom(SOURCE)).thenReturn(
                ScopeFilteredFrom.filtered(SOURCE.qualifiedName(), SCOPE_SQL, List.of(sourceValue)));
        lenient().when(scopeFrom.planFrom(TARGET)).thenReturn(
                ScopeFilteredFrom.filtered(TARGET.qualifiedName(), SCOPE_SQL, List.of(targetValue)));
    }

    private static RecordMatchRequest groupedJoin(JoinType joinType) {
        return new RecordMatchRequest(
                List.of(new MatchCriterion("iceberg", "srse", "beneficiary", "m_id", null)),
                List.of(new MatchCriterion("iceberg_silver", "silver_txn", "tbl_txn_bankdtl", "m_id", null)),
                List.of(), List.of(), List.of(), false, null, joinType, List.of(), false,
                null, null, false,
                List.of(new DisplayColumn("iceberg", "srse", "beneficiary", "district")),
                List.of(new AggregateSpec(AggregateFunction.COUNT, null, false, null)),
                null, false, List.of());
    }

    @ParameterizedTest
    @EnumSource(value = JoinType.class, names = {"INNER", "LEFT", "FULL"})
    void bothSidesAreFilteredInsideTheirDerivedTablesBeforeGroupBy(JoinType joinType) {
        scopeSides("RJ-SRC", "RJ-TGT");

        RecordMatchService.MatchQuery query = service.planMatch(groupedJoin(joinType));

        String sql = query.sql();
        int source = sql.indexOf("(SELECT * FROM " + SOURCE.qualifiedName() + " t WHERE " + SCOPE_SQL + ") src");
        int target = sql.indexOf("(SELECT * FROM " + TARGET.qualifiedName() + " t WHERE " + SCOPE_SQL + ") tgt");
        int groupBy = sql.indexOf(" GROUP BY ");
        assertTrue(source > 0, sql);
        assertTrue(target > source, sql);
        assertTrue(groupBy > target, "GROUP BY must follow both scoped derived tables: " + sql);
        // The scope must not have migrated to the outer WHERE, where an outer join would nullify it.
        assertTrue(sql.contains(" WHERE TRUE GROUP BY"), sql);
        // Distinct values per side prove the binds are in the order the placeholders appear.
        assertEquals(List.of("RJ-SRC", "RJ-TGT"), query.params());
    }

    @ParameterizedTest
    @EnumSource(value = JoinType.class, names = {"INNER", "LEFT", "FULL"})
    void anOfficerWithNoEntitlementAggregatesOverAnEmptySetNotTheRawTable(JoinType joinType) {
        lenient().when(scopeFrom.planFrom(SOURCE)).thenReturn(ScopeFilteredFrom.emptyResult(SOURCE.qualifiedName()));
        lenient().when(scopeFrom.planFrom(TARGET)).thenReturn(ScopeFilteredFrom.emptyResult(TARGET.qualifiedName()));

        String sql = service.planMatch(groupedJoin(joinType)).sql();

        assertTrue(sql.contains("(SELECT * FROM " + SOURCE.qualifiedName() + " t WHERE 1 = 0) src"), sql);
        assertTrue(sql.contains("(SELECT * FROM " + TARGET.qualifiedName() + " t WHERE 1 = 0) tgt"), sql);
        assertFalse(sql.contains("FROM " + SOURCE.qualifiedName() + " src"), sql);
        assertFalse(sql.contains("JOIN " + TARGET.qualifiedName() + " tgt"), sql);
    }

    @Test
    void groupedCsvExportRunsTheScopedGroupedSql() throws Exception {
        scopeSides("RJ-SRC", "RJ-TGT");
        List<String> executed = new ArrayList<>();
        List<Object[]> bound = new ArrayList<>();
        doAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            bound.add(invocation.getArgument(1));
            return null;
        }).when(jdbc).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

        service.matchCsv(groupedJoin(JoinType.LEFT)).writeTo(new ByteArrayOutputStream());

        assertEquals(1, executed.size());
        String sql = executed.get(0);
        assertTrue(sql.contains("WHERE " + SCOPE_SQL + ") src"), sql);
        assertTrue(sql.contains("WHERE " + SCOPE_SQL + ") tgt"), sql);
        assertTrue(sql.contains(" GROUP BY "), sql);
        assertEquals(List.of("RJ-SRC", "RJ-TGT"), List.of(bound.get(0)));
    }
}
