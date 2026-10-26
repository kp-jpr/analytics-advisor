package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.RuleColumnResolver;
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
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceMetadataService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.metadata.sync.MetadataSyncService;
import org.springframework.core.task.SyncTaskExecutor;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PRODUCT_PLAN 10.A.4 — the SQL the router emits, executed on REAL PostgreSQL and DB2 (and, for
 * the federation route, a real Presto 0.297). Unit tests with mocks cannot see dialect problems:
 * this is the "unsupported construct that parses" class CLAUDE.md warns about.
 *
 * <p>Opt-in; needs the containers from {@code docker/verify/docker-compose.yml}:
 * <pre>
 *   docker compose -f docker/verify/docker-compose.yml up -d
 *   SRSE_REAL_ENGINE_IT=true mvn -f backend/pom.xml test -Dtest=ExternalDirectRealEngineIT -Djacoco.skip=true
 * </pre>
 * Everything the product's registry would do is mocked; everything from the router down is real.
 */
@EnabledIfEnvironmentVariable(named = "SRSE_REAL_ENGINE_IT", matches = "true")
class ExternalDirectRealEngineIT {

    /** Names are what each engine's own metadata would report: DB2 folds unquoted DDL to upper case. */
    enum Engine {
        POSTGRESQL(ExternalDataSourceType.POSTGRESQL, "jdbc:postgresql://localhost:5440/srcdb",
                "aa", "aa_verify_pw", "public", String::toLowerCase),
        DB2(ExternalDataSourceType.DB2, "jdbc:db2://localhost:50001/SRCDB",
                "db2inst1", "aa_verify_pw", "DB2INST1", String::toUpperCase);

        final ExternalDataSourceType type;
        final String url;
        final String user;
        final String password;
        final String schema;
        final UnaryOperator<String> fold;

        Engine(ExternalDataSourceType type, String url, String user, String password,
               String schema, UnaryOperator<String> fold) {
            this.type = type;
            this.url = url;
            this.user = user;
            this.password = password;
            this.schema = schema;
            this.fold = fold;
        }

        String id(String name) {
            return fold.apply(name);
        }
    }

    private static JdbcTemplate jdbcFor(String url, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    // ---------------------------------------------------------------- fixture

    private static void load(Engine engine, JdbcTemplate db) {
        String s = engine.schema;
        for (String table : List.of("PEOPLE", "ADDRESSES")) {
            try {
                db.execute("DROP TABLE " + s + "." + engine.id(table));
            } catch (RuntimeException absent) {
                // first run
            }
        }
        db.execute("CREATE TABLE " + s + "." + engine.id("PEOPLE")
                + " (ID INTEGER, NAME VARCHAR(100), DISTRICT VARCHAR(20), DISTRICT_CODE VARCHAR(20))");
        db.execute("CREATE TABLE " + s + "." + engine.id("ADDRESSES")
                + " (PERSON_ID INTEGER, PERSON_REF VARCHAR(20), OWNER_NAME VARCHAR(100),"
                + " CITY VARCHAR(50), DISTRICT_CODE VARCHAR(20))");
        String people = "INSERT INTO " + s + "." + engine.id("PEOPLE") + " VALUES (?,?,?,?)";
        db.update(people, 1, "Asha", "Jaipur", "RJ-JPR");
        db.update(people, 2, "Bhim", "Jaipur", "RJ-JPR");
        db.update(people, 3, "Chitra", "Alwar", "RJ-ALW");
        db.update(people, 4, "Dev", null, "RJ-ALW");
        db.update(people, 5, "Esha", "Udaipur", "RJ-UDP");
        String addresses = "INSERT INTO " + s + "." + engine.id("ADDRESSES") + " VALUES (?,?,?,?,?)";
        db.update(addresses, 1, "1", "Asha", "Jaipur City", "RJ-JPR");
        db.update(addresses, 2, "2", "Bhim", "Amer", "RJ-JPR");
        db.update(addresses, 3, "003", "Chitraa", "Alwar City", "RJ-ALW");
        db.update(addresses, 9, "9", "Zed", "Nowhere", "RJ-UDP");
    }

    // ---------------------------------------------------------------- wiring

    private record Harness(RecordMatchService match, ColumnDistinctValuesService values) {}

    private Harness harness(Engine engine, JdbcTemplate direct, JdbcTemplate presto,
                            String federationCatalog, String scopeDistrict) {
        RegisteredTableRepository registrations = mock(RegisteredTableRepository.class);
        ExternalDataSourceService sources = mock(ExternalDataSourceService.class);
        ExternalDataSource source = mock(ExternalDataSource.class);
        when(source.getDatabaseType()).thenReturn(engine.type);
        when(source.getName()).thenReturn("verify");
        when(source.getFederationCatalog()).thenReturn(federationCatalog);
        when(sources.requireActiveInternal(7L)).thenReturn(source);
        when(sources.jdbcTemplateForExecution(7L)).thenReturn(direct);
        for (String table : List.of("PEOPLE", "ADDRESSES")) {
            RegisteredTable row = mock(RegisteredTable.class);
            when(row.isExternal()).thenReturn(true);
            when(row.getExternalDataSourceId()).thenReturn(7L);
            when(row.getSchemaName()).thenReturn(engine.schema);
            when(row.getTableName()).thenReturn(engine.id(table));
            when(registrations.findByCatalogNameAndSchemaNameAndTableName(
                    "jdbc_7", engine.schema, engine.id(table))).thenReturn(Optional.of(row));
        }

        LakehouseRegistryService registry = mock(LakehouseRegistryService.class);
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(inv -> {
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            for (Object column : inv.getArgument(1, List.class)) {
                String name = String.valueOf(column);
                String type = name.equalsIgnoreCase("ID") || name.equalsIgnoreCase("PERSON_ID")
                        ? "integer" : "varchar";
                described.put(name, new RegisteredColumn(name, type, null, false, true));
            }
            return described;
        });
        AnalysisColumnMetadataRepository metadata = mock(AnalysisColumnMetadataRepository.class);
        lenient().when(metadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        // Lower-case and UNQUOTED on purpose: this is how the scope predicate is really emitted,
        // and DB2 must fold it onto the upper-case columns.
        AnalysisScopeFromService scope = mock(AnalysisScopeFromService.class);
        lenient().when(scope.planFrom(any())).thenAnswer(inv -> {
            String name = inv.getArgument(0, QualifiedTable.class).qualifiedName();
            return scopeDistrict == null
                    ? ScopeFilteredFrom.unfiltered(name)
                    : ScopeFilteredFrom.filtered(name, "district_code IN (?)", List.of(scopeDistrict));
        });

        AnalysisProperties properties =
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000);
        RecordMatchService match = new RecordMatchService(
                presto, registry, new GuardrailProperties(1000, 30, 50), metadata, properties,
                new ObjectMapper(), scope, new RuleCompiler(new RuleColumnResolver(registry), properties));
        AnalysisExecutionRouter router = new AnalysisExecutionRouter(registrations, sources, presto);
        ReflectionTestUtils.setField(match, "executionRouter", router);
        ColumnDistinctValuesService values = new ColumnDistinctValuesService(presto, registry, scope, properties);
        ReflectionTestUtils.setField(values, "executionRouter", router);
        return new Harness(match, values);
    }

    private RecordMatchRequest join(Engine e, String sourceColumn, String targetColumn, JoinType type,
                                    Double fuzzyThreshold) {
        return new RecordMatchRequest(
                List.of(new MatchCriterion("jdbc_7", e.schema, e.id("PEOPLE"), e.id(sourceColumn), fuzzyThreshold)),
                List.of(new MatchCriterion("jdbc_7", e.schema, e.id("ADDRESSES"), e.id(targetColumn), null)),
                List.of(new DisplayColumn("jdbc_7", e.schema, e.id("PEOPLE"), e.id("NAME"))),
                List.of(new DisplayColumn("jdbc_7", e.schema, e.id("ADDRESSES"), e.id("CITY"))),
                List.of(), false, null, type, List.of(), false, null, null, false,
                List.of(), List.of(), null, false, List.of());
    }

    private static String csv(RecordMatchService service, RecordMatchRequest request) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.matchCsv(request).writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static long dataRows(String csv) {
        return csv.lines().filter(line -> !line.isBlank()).count() - 1;
    }

    // ---------------------------------------------------------------- tests

    @ParameterizedTest
    @EnumSource(Engine.class)
    void allFourJoinTypesReturnTheRightRowsOnTheDirectRoute(Engine e) throws Exception {
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);
        Harness h = harness(e, db, null, null, null);

        assertEquals(3, dataRows(csv(h.match, join(e, "ID", "PERSON_ID", JoinType.INNER, null))));
        assertEquals(5, dataRows(csv(h.match, join(e, "ID", "PERSON_ID", JoinType.LEFT, null))));
        assertEquals(4, dataRows(csv(h.match, join(e, "ID", "PERSON_ID", JoinType.RIGHT, null))));
        assertEquals(6, dataRows(csv(h.match, join(e, "ID", "PERSON_ID", JoinType.FULL, null))));
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void aScopedOfficerNeverSeesAnotherDistrictThroughAnyJoinType(Engine e) throws Exception {
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);

        // RJ-UDP owns person 5 and address 9, which do not match each other.
        Harness udaipur = harness(e, db, null, null, "RJ-UDP");
        for (JoinType type : JoinType.values()) {
            String out = csv(udaipur.match, join(e, "ID", "PERSON_ID", type, null));
            assertFalse(out.contains("Asha") || out.contains("Chitra") || out.contains("Jaipur City"),
                    type + " leaked another district:\n" + out);
        }
        assertEquals(0, dataRows(csv(udaipur.match, join(e, "ID", "PERSON_ID", JoinType.INNER, null))));
        assertEquals(1, dataRows(csv(udaipur.match, join(e, "ID", "PERSON_ID", JoinType.LEFT, null))));
        assertEquals(1, dataRows(csv(udaipur.match, join(e, "ID", "PERSON_ID", JoinType.RIGHT, null))));
        assertEquals(2, dataRows(csv(udaipur.match, join(e, "ID", "PERSON_ID", JoinType.FULL, null))));

        // RJ-ALW: people 3 and 4, address 3 — the unmatched person 4 is theirs, so LEFT keeps them.
        Harness alwar = harness(e, db, null, null, "RJ-ALW");
        assertEquals(1, dataRows(csv(alwar.match, join(e, "ID", "PERSON_ID", JoinType.INNER, null))));
        assertEquals(2, dataRows(csv(alwar.match, join(e, "ID", "PERSON_ID", JoinType.LEFT, null))));
        assertEquals(1, dataRows(csv(alwar.match, join(e, "ID", "PERSON_ID", JoinType.RIGHT, null))));
        assertEquals(2, dataRows(csv(alwar.match, join(e, "ID", "PERSON_ID", JoinType.FULL, null))));
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void groupedCountAggregatesOnlyTheOfficersRows(Engine e) throws Exception {
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);
        Harness jaipur = harness(e, db, null, null, "RJ-JPR");
        RecordMatchRequest grouped = new RecordMatchRequest(
                List.of(new MatchCriterion("jdbc_7", e.schema, e.id("PEOPLE"), e.id("ID"), null)),
                List.of(new MatchCriterion("jdbc_7", e.schema, e.id("ADDRESSES"), e.id("PERSON_ID"), null)),
                List.of(), List.of(), List.of(), false, null, JoinType.INNER, List.of(), false,
                null, null, false,
                List.of(new DisplayColumn("jdbc_7", e.schema, e.id("PEOPLE"), e.id("DISTRICT"))),
                List.of(new AggregateSpec(AggregateFunction.COUNT, null, false, null)),
                null, false, List.of());

        String out = csv(jaipur.match, grouped);

        assertEquals(1, dataRows(out), out);
        assertTrue(out.contains("Jaipur"), out);
        assertTrue(out.contains("2"), out);
        assertFalse(out.contains("Alwar") || out.contains("Udaipur"), out);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void valuePickerOrdersNullsFirstAndSearchesCaseInsensitively(Engine e) {
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);
        Harness h = harness(e, db, null, null, null);

        var all = h.values.execute(h.values.plan(new ColumnDistinctValuesService.ColumnValuesRequest(
                "jdbc_7", e.schema, e.id("PEOPLE"), e.id("DISTRICT"), null)));
        assertTrue(all.includesNull(), all.toString());
        assertEquals(List.of("Alwar", "Jaipur", "Udaipur"),
                all.values().stream().filter(v -> v != null).toList());

        var searched = h.values.execute(h.values.plan(new ColumnDistinctValuesService.ColumnValuesRequest(
                "jdbc_7", e.schema, e.id("PEOPLE"), e.id("DISTRICT"), "jAi")));
        assertEquals(List.of("Jaipur"), searched.values());
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void presto_only_constructs_are_refused_on_the_direct_route(Engine e) {
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);
        Harness h = harness(e, db, null, null, null);

        // number vs text needs TRY_CAST; a fuzzy name pair needs levenshtein_distance
        IllegalArgumentException numberVsText = assertThrows(IllegalArgumentException.class,
                () -> csv(h.match, join(e, "ID", "PERSON_REF", JoinType.INNER, null)));
        assertTrue(numberVsText.getMessage().contains("Presto-only"), numberVsText.getMessage());
        IllegalArgumentException fuzzy = assertThrows(IllegalArgumentException.class,
                () -> csv(h.match, join(e, "NAME", "OWNER_NAME", JoinType.INNER, 70.0)));
        assertTrue(fuzzy.getMessage().contains("Presto-only"), fuzzy.getMessage());
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void metadataSyncIntrospectionMatchesEngineReportedIdentifiers(Engine e) throws Exception {
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);
        ExternalDataSourceService sources = mock(ExternalDataSourceService.class);
        ExternalDataSource source = mock(ExternalDataSource.class);
        when(source.getDatabaseType()).thenReturn(e.type);
        when(sources.requireActiveInternal(7L)).thenReturn(source);
        when(sources.openInternal(source)).thenAnswer(inv -> DriverManager.getConnection(e.url, e.user, e.password));

        ExternalDataSourceMetadataService externalMeta = new ExternalDataSourceMetadataService(sources);
        MetadataSyncService sync = new MetadataSyncService(
                mock(RegisteredTableRepository.class),
                mock(LakehouseBrowseService.class),
                externalMeta,
                mock(gov.rajasthan.smart.srse.metadata.sync.MetadataSyncRunRepository.class),
                mock(gov.rajasthan.smart.srse.metadata.sync.MetadataSyncTableResultRepository.class),
                mock(gov.rajasthan.smart.srse.metadata.sync.TableColumnSnapshotRepository.class),
                new SyncTaskExecutor());

        RegisteredTable people = new RegisteredTable(1L, "jdbc_7", e.schema, e.id("PEOPLE"), "GOLD");
        people.attachExternalSource(7L, null);
        RegisteredTable addresses = new RegisteredTable(2L, "jdbc_7", e.schema, e.id("ADDRESSES"), "GOLD");
        addresses.attachExternalSource(7L, null);

        @SuppressWarnings("unchecked")
        List<LakehouseBrowseService.ColumnInfo> peopleCols = (List<LakehouseBrowseService.ColumnInfo>)
                ReflectionTestUtils.invokeMethod(sync, "introspect", people);
        @SuppressWarnings("unchecked")
        List<LakehouseBrowseService.ColumnInfo> addressCols = (List<LakehouseBrowseService.ColumnInfo>)
                ReflectionTestUtils.invokeMethod(sync, "introspect", addresses);

        assertEquals(
                List.of(e.id("ID"), e.id("NAME"), e.id("DISTRICT"), e.id("DISTRICT_CODE")),
                peopleCols.stream().map(LakehouseBrowseService.ColumnInfo::name).toList());
        assertEquals(
                List.of(e.id("PERSON_ID"), e.id("PERSON_REF"), e.id("OWNER_NAME"), e.id("CITY"), e.id("DISTRICT_CODE")),
                addressCols.stream().map(LakehouseBrowseService.ColumnInfo::name).toList());
        assertFalse(peopleCols.isEmpty());
        assertTrue(peopleCols.stream().allMatch(c -> c.dataType() != null && !c.dataType().isBlank()));
    }

    /** PostgreSQL only: PrestoDB 0.297 ships no DB2 connector, so DB2 can never take this route. */
    @Test
    void federationThroughPrestoRunsWhatTheDirectRouteRefuses() throws Exception {
        Engine e = Engine.POSTGRESQL;
        JdbcTemplate db = jdbcFor(e.url, e.user, e.password);
        load(e, db);
        JdbcTemplate presto = jdbcFor("jdbc:presto://localhost:8082/aa_pg", "aa", null);
        Harness h = harness(e, db, presto, "aa_pg", null);

        // '1','2','003' against integers 1,2,3 — equal only as numbers (TRY_CAST ... AS DOUBLE)
        assertEquals(3, dataRows(csv(h.match, join(e, "ID", "PERSON_REF", JoinType.INNER, null))));
        // Asha=Asha, Bhim=Bhim exactly and Chitra~Chitraa by Levenshtein; Dev/Esha have no counterpart
        assertEquals(3, dataRows(csv(h.match, join(e, "NAME", "OWNER_NAME", JoinType.INNER, 70.0))));
    }
}
