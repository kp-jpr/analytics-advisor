package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.CompiledQuery;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import gov.rajasthan.smart.srse.datasource.ExternalDataSource;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceService;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceType;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRouter;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PRODUCT_PLAN 10.A.1 — the scope predicate must survive routing to a saved JDBC
 * connection. {@link ExternalDirectExecutionTest} stubs scope as unfiltered, so
 * until this class nothing proved a scoped officer is filtered on the direct
 * route, which bypasses Presto entirely. Uses the REAL {@link AnalysisExecutionRouter}.
 *
 * <p>Covers PostgreSQL and DB2 only (decision 0.13).
 */
@ExtendWith(MockitoExtension.class)
class ExternalScopeEnforcementTest {

    private static final String SCOPE_SQL = "district_code IN (?)";
    private static final String DISTRICT = "RJ-JPR";

    @Mock
    private JdbcTemplate presto;
    @Mock
    private JdbcTemplate direct;
    @Mock
    private LakehouseRegistryService registry;
    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;
    @Mock
    private AnalysisScopeFromService scopeFrom;
    @Mock
    private RuleCompiler ruleCompiler;
    @Mock
    private RegisteredTableRepository registrations;
    @Mock
    private ExternalDataSourceService sources;
    @Mock
    private ExternalDataSource source;

    private RecordMatchService matchService;
    private ColumnDistinctValuesService valuesService;
    private AnalysisExecutionRouter router;

    @BeforeEach
    void setUp() {
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(invocation -> {
            Collection<?> columns = invocation.getArgument(1);
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            for (Object column : columns) {
                String name = String.valueOf(column);
                described.put(name, new RegisteredColumn(name, "varchar", null, false, true));
            }
            return described;
        });
        lenient().when(ruleCompiler.compile(any(), any())).thenReturn(new CompiledQuery("TRUE", List.of()));
        lenient().when(direct.queryForObject(anyString(), eq(Long.class))).thenReturn(10L);
        lenient().when(direct.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(1L);
        lenient().when(sources.requireActiveInternal(7L)).thenReturn(source);
        lenient().when(sources.jdbcTemplateForExecution(7L)).thenReturn(direct);
        stubRegistration("people");
        stubRegistration("addresses");

        router = new AnalysisExecutionRouter(registrations, sources, presto);
        matchService = new RecordMatchService(
                presto, registry, new GuardrailProperties(1000, 30, 50), columnMetadata,
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 2, 100_000),
                new ObjectMapper(), scopeFrom, ruleCompiler);
        ReflectionTestUtils.setField(matchService, "executionRouter", router);
        valuesService = new ColumnDistinctValuesService(
                presto, registry, scopeFrom,
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 2, 100_000));
        ReflectionTestUtils.setField(valuesService, "executionRouter", router);
    }

    private void useDatabase(ExternalDataSourceType type) {
        lenient().when(source.getDatabaseType()).thenReturn(type);
        lenient().when(source.getName()).thenReturn("Payroll");
    }

    private void officerScopedToOneDistrict() {
        lenient().when(scopeFrom.planFrom(any())).thenAnswer(invocation ->
                ScopeFilteredFrom.filtered(
                        invocation.getArgument(0, QualifiedTable.class).qualifiedName(),
                        SCOPE_SQL, List.of(DISTRICT)));
    }

    private static RecordMatchRequest joinRequest() {
        return new RecordMatchRequest(
                List.of(new MatchCriterion("jdbc_7", "public", "people", "id", null)),
                List.of(new MatchCriterion("jdbc_7", "public", "addresses", "person_id", null)),
                null, null, false, null);
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void matchKeepsTheScopePredicateOnBothSidesAfterRouting(ExternalDataSourceType type) {
        useDatabase(type);
        officerScopedToOneDistrict();

        RecordMatchService.MatchQuery planned = matchService.planMatch(joinRequest());

        String routed = planned.routedSql();
        assertTrue(routed.contains("(SELECT * FROM \"public\".\"people\" t WHERE " + SCOPE_SQL + ") src"), routed);
        assertTrue(routed.contains("(SELECT * FROM \"public\".\"addresses\" t WHERE " + SCOPE_SQL + ") tgt"), routed);
        assertFalse(routed.contains("jdbc_7"), routed);
        assertEquals(List.of(DISTRICT, DISTRICT), planned.params());
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void fanOutEstimateQueriesAreScopedAndStayOffPresto(ExternalDataSourceType type) {
        useDatabase(type);
        officerScopedToOneDistrict();

        matchService.planMatch(joinRequest());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(direct, atLeastOnce()).queryForObject(sql.capture(), params.capture(), eq(Long.class));
        assertFalse(sql.getAllValues().isEmpty());
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            String statement = sql.getAllValues().get(i);
            assertTrue(statement.contains(SCOPE_SQL), "unscoped estimate query: " + statement);
            assertTrue(params.getAllValues().get(i).length >= 1, "scope value not bound: " + statement);
        }
        // An unscoped overload (no bind array) would mean a count that ignores scope.
        verify(direct, never()).queryForObject(anyString(), eq(Long.class));
        verify(presto, never()).queryForObject(anyString(), eq(Long.class));
        verify(presto, never()).queryForObject(anyString(), any(Object[].class), eq(Long.class));
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void officerWithNoEntitlementGetsAnEmptyDerivedTableNotTheRawTable(ExternalDataSourceType type) {
        useDatabase(type);
        lenient().when(scopeFrom.planFrom(any())).thenAnswer(invocation ->
                ScopeFilteredFrom.emptyResult(invocation.getArgument(0, QualifiedTable.class).qualifiedName()));

        RecordMatchService.MatchQuery planned = matchService.planMatch(joinRequest());

        String routed = planned.routedSql();
        assertTrue(routed.contains("(SELECT * FROM \"public\".\"people\" t WHERE 1 = 0) src"), routed);
        assertTrue(routed.contains("(SELECT * FROM \"public\".\"addresses\" t WHERE 1 = 0) tgt"), routed);
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void csvExportRunsTheScopedSqlOnTheDirectConnection(ExternalDataSourceType type) throws Exception {
        useDatabase(type);
        officerScopedToOneDistrict();
        List<String> executed = new ArrayList<>();
        List<Object[]> bound = new ArrayList<>();
        doAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            bound.add(invocation.getArgument(1));
            return null;
        }).when(direct).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

        RecordMatchRequest request = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(new DisplayColumn("jdbc_7", "public", "people", "district")),
                List.of(), List.of(), false, null, null, List.of(), false,
                null, null, true, List.of(), List.of(), null, false, List.of());
        StreamingResponseBody csv = matchService.matchCsv(request);
        csv.writeTo(new ByteArrayOutputStream());

        assertEquals(1, executed.size());
        assertTrue(executed.get(0).contains(SCOPE_SQL), executed.get(0));
        assertEquals(DISTRICT, bound.get(0)[0]);
        verify(presto, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void columnValuePickerIsScopedOnTheDirectRoute(ExternalDataSourceType type) {
        useDatabase(type);
        officerScopedToOneDistrict();

        ColumnDistinctValuesService.PlannedColumnValues planned = valuesService.plan(
                new ColumnDistinctValuesService.ColumnValuesRequest(
                        "jdbc_7", "public", "people", "district", null));

        assertTrue(planned.routedSql().contains(
                "(SELECT * FROM \"public\".\"people\" t WHERE " + SCOPE_SQL + ") scoped"), planned.routedSql());
        assertFalse(planned.routedSql().contains("jdbc_7"), planned.routedSql());
        assertEquals(List.of(DISTRICT), planned.params());
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void multiTargetSubMatchesKeepScopeOnHubAndEachTarget(ExternalDataSourceType type) {
        useDatabase(type);
        officerScopedToOneDistrict();
        stubRegistration("phones");

        MultiTargetRecordMatchRequest multi = new MultiTargetRecordMatchRequest(
                List.of(new MatchCriterion("jdbc_7", "public", "people", "id", null)),
                List.of(),
                HubSide.SOURCE,
                List.of(
                        new TargetMatchSpec(
                                "addrs",
                                "jdbc_7",
                                "public",
                                "addresses",
                                List.of(new MatchCriterion("jdbc_7", "public", "addresses", "person_id", null)),
                                List.of()),
                        new TargetMatchSpec(
                                "phones",
                                "jdbc_7",
                                "public",
                                "phones",
                                List.of(new MatchCriterion("jdbc_7", "public", "phones", "person_id", null)),
                                List.of())),
                false,
                null,
                false);

        for (TargetMatchSpec target : multi.targets()) {
            RecordMatchRequest single = MultiTargetRecordMatchService.toSingleMatch(multi, target);
            RecordMatchService.MatchQuery planned = matchService.planMatch(single);
            String routed = planned.routedSql();
            assertTrue(routed.contains("(SELECT * FROM \"public\".\"people\" t WHERE " + SCOPE_SQL + ") src"), routed);
            assertTrue(routed.contains("(SELECT * FROM \"public\".\"" + target.table() + "\" t WHERE " + SCOPE_SQL + ") tgt"),
                    routed);
            assertFalse(routed.contains("jdbc_7"), routed);
            assertEquals(List.of(DISTRICT, DISTRICT), planned.params());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void sqlPreviewInlinesScopeBindAfterDirectRouting(ExternalDataSourceType type) {
        useDatabase(type);
        officerScopedToOneDistrict();

        RecordMatchService.MatchQuery planned = matchService.planMatch(joinRequest());
        String display = matchService.renderQueryForDisplay(planned);

        assertTrue(display.contains("(SELECT * FROM \"public\".\"people\" t WHERE district_code IN ('" + DISTRICT + "')) src"),
                display);
        assertTrue(display.contains("(SELECT * FROM \"public\".\"addresses\" t WHERE district_code IN ('" + DISTRICT + "')) tgt"),
                display);
        assertFalse(display.contains("jdbc_7"), display);
        assertFalse(display.contains("?"), display);
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSourceType.class, names = {"POSTGRESQL", "DB2"})
    void groupedCountIsScopedBeforeAggregationOnTheDirectRoute(ExternalDataSourceType type) {
        useDatabase(type);
        officerScopedToOneDistrict();
        RecordMatchRequest grouped = new RecordMatchRequest(
                List.of(new MatchCriterion("jdbc_7", "public", "people", "id", null)),
                List.of(new MatchCriterion("jdbc_7", "public", "addresses", "person_id", null)),
                List.of(), List.of(), List.of(), false, null, null, List.of(), false,
                null, null, false,
                List.of(new DisplayColumn("jdbc_7", "public", "people", "district")),
                List.of(new AggregateSpec(AggregateFunction.COUNT, null, false, null)),
                null, false, List.of());

        RecordMatchService.MatchQuery planned = matchService.planMatch(grouped);

        String routed = planned.routedSql();
        int people = routed.indexOf("(SELECT * FROM \"public\".\"people\" t WHERE " + SCOPE_SQL + ") src");
        int addresses = routed.indexOf("(SELECT * FROM \"public\".\"addresses\" t WHERE " + SCOPE_SQL + ") tgt");
        assertTrue(people > 0, routed);
        assertTrue(addresses > people, routed);
        assertTrue(routed.indexOf(" GROUP BY ") > addresses, routed);
        assertFalse(routed.contains("jdbc_7"), routed);
        assertEquals(List.of(DISTRICT, DISTRICT), planned.params());
    }

    private void stubRegistration(String table) {
        RegisteredTable row = mock(RegisteredTable.class);
        lenient().when(row.isExternal()).thenReturn(true);
        lenient().when(row.getExternalDataSourceId()).thenReturn(7L);
        lenient().when(row.getSchemaName()).thenReturn("public");
        lenient().when(row.getTableName()).thenReturn(table);
        lenient().when(registrations.findByCatalogNameAndSchemaNameAndTableName("jdbc_7", "public", table))
                .thenReturn(Optional.of(row));
    }
}
