package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.RuleColumnResolver;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * PRODUCT_PLAN 10.A.4 follow-up — the manual lakehouse checks CLAUDE.md asks to be re-run whenever the
 * match seam changes, as an opt-in test: all four join types across TWO catalogs, a fuzzy pair under
 * LEFT, ANY_OF with an all-NULL candidate row on the preserved side, both COMBINE directions, and the
 * post-join comparison NULL semantics. Real RecordMatchService, real Presto 0.297 + Iceberg; the
 * registry and scope are mocked.
 *
 * <pre>
 *   docker compose up -d minio metastore presto           (the main stack; Presto on :8081)
 *   SRSE_REAL_ENGINE_IT=true mvn -f backend/pom.xml test -Dtest=LakehouseRealEngineIT -Djacoco.skip=true
 * </pre>
 * Writes only to schema {@code aa_it} in both catalogs and drops its tables afterwards.
 */
@EnabledIfEnvironmentVariable(named = "SRSE_REAL_ENGINE_IT", matches = "true")
class LakehouseRealEngineIT {

    private static final String SRC_CATALOG = "iceberg";
    private static final String TGT_CATALOG = "iceberg_silver";
    private static final String SCHEMA = "aa_it";
    private static final String PEOPLE = "people";
    private static final String CONTACTS = "contacts";
    private static final Set<String> FUZZY_COLUMNS = Set.of("name", "owner_name");

    private static JdbcTemplate presto;
    private static RecordMatchService service;

    @BeforeAll
    static void load() {
        presto = new JdbcTemplate(new DriverManagerDataSource("jdbc:presto://localhost:8081", "aa", null));
        for (String catalog : List.of(SRC_CATALOG, TGT_CATALOG)) {
            presto.execute("CREATE SCHEMA IF NOT EXISTS " + catalog + "." + SCHEMA);
        }
        presto.execute("DROP TABLE IF EXISTS " + SRC_CATALOG + "." + SCHEMA + "." + PEOPLE);
        presto.execute("DROP TABLE IF EXISTS " + TGT_CATALOG + "." + SCHEMA + "." + CONTACTS);
        presto.execute("CREATE TABLE " + SRC_CATALOG + "." + SCHEMA + "." + PEOPLE
                + " (id integer, name varchar, fullname varchar, first_name varchar, last_name varchar,"
                + " phone1 varchar, phone2 varchar, income varchar, district varchar)");
        presto.execute("CREATE TABLE " + TGT_CATALOG + "." + SCHEMA + "." + CONTACTS
                + " (person_id integer, owner_name varchar, owner_exact varchar, first_name varchar,"
                + " last_name varchar, phone varchar, income varchar)");
        // person 4 has NO phone at all (the all-NULL ANY_OF candidate row); person 5 matches nothing
        presto.execute("INSERT INTO " + SRC_CATALOG + "." + SCHEMA + "." + PEOPLE + " VALUES "
                + "(1, 'Asha Verma', 'Asha Verma', 'Asha', 'Verma', '111', '222', '100', 'Jaipur'),"
                + "(2, 'Bhim Singh', 'Bhim Singh', 'Bhim', 'Singh', '333', NULL, '200', 'Jaipur'),"
                + "(3, 'Chitra Rao', 'Chitra Rao', 'Chitra', 'Rao', NULL, '444', NULL, 'Alwar'),"
                + "(4, 'Dev Kumar', 'Dev Kumar', 'Dev', 'Kumar', NULL, NULL, '400', 'Alwar'),"
                + "(5, 'Esha Das', 'Esha Das', 'Esha', 'Das', '999', NULL, '500', 'Udaipur')");
        presto.execute("INSERT INTO " + TGT_CATALOG + "." + SCHEMA + "." + CONTACTS + " VALUES "
                + "(1, 'Asha Verma', 'Asha Verma', 'Asha', 'Verma', '222', '100'),"
                + "(2, 'Bheem Singh', 'Bheem Singh', 'Bhim', 'Singh', '333', '250'),"
                + "(3, 'Chitraa Rao', 'Chitraa Rao', 'Chitra', 'Rao', '444', NULL),"
                + "(9, 'Zed Zane', 'Zed Zane', 'Zed', 'Zane', '888', '900')");

        LakehouseRegistryService registry = mock(LakehouseRegistryService.class);
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(inv -> {
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            for (Object column : inv.getArgument(1, List.class)) {
                String name = String.valueOf(column);
                String type = name.equals("id") || name.equals("person_id") ? "integer" : "varchar";
                described.put(name, new RegisteredColumn(name, type, null, false, true));
            }
            return described;
        });
        // Registered, so the name-substring guess cannot make first_name/last_name fuzzy.
        AnalysisColumnMetadataRepository metadata = mock(AnalysisColumnMetadataRepository.class);
        lenient().when(metadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                anyString(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                Optional.of(new AnalysisColumnMetadata(null,
                        new QualifiedColumn(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                                inv.getArgument(3)),
                        null, FUZZY_COLUMNS.contains(inv.<String>getArgument(3)), true)));
        AnalysisScopeFromService scope = mock(AnalysisScopeFromService.class);
        lenient().when(scope.planFrom(any())).thenAnswer(inv ->
                ScopeFilteredFrom.unfiltered(inv.getArgument(0, QualifiedTable.class).qualifiedName()));
        AnalysisProperties properties = new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000);
        service = new RecordMatchService(
                presto, registry, new GuardrailProperties(1000, 60, 50), metadata, properties,
                new ObjectMapper(), scope, new RuleCompiler(new RuleColumnResolver(registry), properties));
    }

    @AfterAll
    static void dropFixture() {
        try {
            presto.execute("DROP TABLE IF EXISTS " + SRC_CATALOG + "." + SCHEMA + "." + PEOPLE);
            presto.execute("DROP TABLE IF EXISTS " + TGT_CATALOG + "." + SCHEMA + "." + CONTACTS);
            presto.execute("DROP SCHEMA IF EXISTS " + SRC_CATALOG + "." + SCHEMA);
            presto.execute("DROP SCHEMA IF EXISTS " + TGT_CATALOG + "." + SCHEMA);
        } catch (RuntimeException ignored) {
            // best effort: the schema is throwaway
        }
    }

    // ------------------------------------------------------------------ builders

    private static MatchCriterion src(String column, Double fuzzy) {
        return new MatchCriterion(SRC_CATALOG, SCHEMA, PEOPLE, column, fuzzy);
    }

    private static MatchCriterion tgt(String column) {
        return new MatchCriterion(TGT_CATALOG, SCHEMA, CONTACTS, column, null);
    }

    private static List<DisplayColumn> srcDisplay(String... columns) {
        return java.util.Arrays.stream(columns)
                .map(c -> new DisplayColumn(SRC_CATALOG, SCHEMA, PEOPLE, c)).toList();
    }

    private static List<DisplayColumn> tgtDisplay(String... columns) {
        return java.util.Arrays.stream(columns)
                .map(c -> new DisplayColumn(TGT_CATALOG, SCHEMA, CONTACTS, c)).toList();
    }

    private static RecordMatchRequest request(
            List<MatchGroup> groups, JoinType joinType, List<ComparisonGroup> comparisons, boolean mismatchOnly) {
        return request(groups, joinType, comparisons, mismatchOnly, false);
    }

    private static RecordMatchRequest request(
            List<MatchGroup> groups, JoinType joinType, List<ComparisonGroup> comparisons, boolean mismatchOnly,
            boolean highlightDuplicates) {
        return new RecordMatchRequest(
                List.of(), List.of(),
                srcDisplay("name"), tgtDisplay("owner_name"),
                groups, highlightDuplicates, null, joinType, comparisons, mismatchOnly, null, null, false,
                List.of(), List.of(), null, false, List.of());
    }

    private static MatchGroup exact(String sourceColumn, String targetColumn) {
        return MatchGroup.of(src(sourceColumn, null), tgt(targetColumn));
    }

    private static List<List<String>> rows(String csv) {
        List<List<String>> out = new ArrayList<>();
        for (String line : csv.replace("\uFEFF", "").replace("\r", "").split("\n")) {
            if (!line.isBlank()) {
                out.add(java.util.Arrays.asList(line.split(",", -1)));
            }
        }
        return out;
    }

    private static List<List<String>> run(RecordMatchRequest request) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.matchCsv(request).writeTo(out);
        return rows(out.toString(StandardCharsets.UTF_8));
    }

    private static int dataRows(List<List<String>> table) {
        return table.size() - 1;
    }

    private static int col(List<List<String>> table, String header) {
        int i = table.get(0).stream().map(h -> h.replace("\"", "").trim()).toList().indexOf(header);
        assertTrue(i >= 0, "missing column " + header + " in " + table.get(0));
        return i;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void allFourJoinTypesAcrossTwoCatalogs() throws Exception {
        MatchGroup byId = exact("id", "person_id");
        assertEquals(3, dataRows(run(request(List.of(byId), JoinType.INNER, List.of(), false))));
        assertEquals(5, dataRows(run(request(List.of(byId), JoinType.LEFT, List.of(), false))));
        assertEquals(4, dataRows(run(request(List.of(byId), JoinType.RIGHT, List.of(), false))));
        assertEquals(6, dataRows(run(request(List.of(byId), JoinType.FULL, List.of(), false))));
    }

    @Test
    void aFuzzyPairKeepsUnmatchedSourceRowsUnderLeft() throws Exception {
        // Asha=Asha and Chitra~Chitraa; Bhim/Bheem differ inside the 3-character blocking prefix.
        MatchGroup fuzzy = MatchGroup.of(src("name", 70.0), tgt("owner_name"));

        // match_score_pct is only projected when the officer asks for it (highlightDuplicates)
        List<List<String>> inner = run(request(List.of(fuzzy), JoinType.INNER, List.of(), false, true));
        assertEquals(2, dataRows(inner));
        assertTrue(inner.get(0).stream().anyMatch(h -> h.contains("match_score_pct")), inner.get(0).toString());

        // The old defect: fuzzy similarity in WHERE silently turned LEFT into INNER (2 rows, not 5).
        assertEquals(5, dataRows(run(request(List.of(fuzzy), JoinType.LEFT, List.of(), false))));
        assertEquals(4, dataRows(run(request(List.of(fuzzy), JoinType.RIGHT, List.of(), false))));
        assertEquals(7, dataRows(run(request(List.of(fuzzy), JoinType.FULL, List.of(), false))));
    }

    @Test
    void anyOfWithAnAllNullCandidateRowSurvivesLeft() throws Exception {
        // person 4 has phone1 = phone2 = NULL; person 5's phone matches nothing.
        MatchGroup anyOf = new MatchGroup(
                List.of(src("phone1", null), src("phone2", null)),
                List.of(tgt("phone")), GroupMode.ANY_OF, null, null);

        List<List<String>> inner = run(request(List.of(anyOf), JoinType.INNER, List.of(), false));
        assertEquals(3, dataRows(inner));
        assertTrue(inner.get(0).stream().anyMatch(h -> h.contains("matched_on")), inner.get(0).toString());

        // ANY_OF pivots each candidate column to its own row BEFORE the join, so under LEFT a person who
        // matched through one column also shows an unmatched row for a candidate that matched nothing
        // (person 1: phone2 matches, phone1 does not). Row COUNTS therefore exceed the person count; what
        // must hold is that no source row is ever lost, including the all-NULL one.
        List<List<String>> left = run(request(List.of(anyOf), JoinType.LEFT, List.of(), false));
        assertEquals(allNames(left, "source_name"),
                Set.of("Asha Verma", "Bhim Singh", "Chitra Rao", "Dev Kumar", "Esha Das"),
                "a LEFT join lost a source row: " + left);
        List<String> devRow = left.stream().skip(1)
                .filter(r -> r.get(col(left, "source_name")).contains("Dev Kumar")).findFirst().orElseThrow();
        assertEquals("", devRow.get(col(left, "target_owner_name")).trim(), "all-NULL candidate row: " + devRow);

        List<List<String>> right = run(request(List.of(anyOf), JoinType.RIGHT, List.of(), false));
        assertEquals(Set.of("Asha Verma", "Bheem Singh", "Chitraa Rao", "Zed Zane"),
                allNames(right, "target_owner_name"), "a RIGHT join lost a target row: " + right);

        List<List<String>> full = run(request(List.of(anyOf), JoinType.FULL, List.of(), false));
        assertEquals(Set.of("Asha Verma", "Bhim Singh", "Chitra Rao", "Dev Kumar", "Esha Das"),
                allNames(full, "source_name"), "a FULL join lost a source row: " + full);
        assertEquals(Set.of("Asha Verma", "Bheem Singh", "Chitraa Rao", "Zed Zane"),
                allNames(full, "target_owner_name"), "a FULL join lost a target row: " + full);
    }

    private static Set<String> allNames(List<List<String>> table, String header) {
        int i = col(table, header);
        Set<String> names = new java.util.TreeSet<>();
        for (List<String> row : table.subList(1, table.size())) {
            String value = row.get(i).replace("\"", "").trim();
            if (!value.isEmpty()) {
                names.add(value);
            }
        }
        return names;
    }

    @Test
    void combineFoldsTheManySideInEitherDirection() throws Exception {
        // first_name + last_name on the SOURCE against owner_name: only the exactly-equal pair matches
        MatchGroup sourceMany = new MatchGroup(
                List.of(src("first_name", null), src("last_name", null)),
                List.of(tgt("owner_exact")), GroupMode.COMBINE, null, " ");
        assertEquals(1, dataRows(run(request(List.of(sourceMany), JoinType.INNER, List.of(), false))));

        // name on the source against first_name + last_name on the TARGET: three people line up
        MatchGroup targetMany = new MatchGroup(
                List.of(src("fullname", null)),
                List.of(tgt("first_name"), tgt("last_name")), GroupMode.COMBINE, null, " ");
        assertEquals(3, dataRows(run(request(List.of(targetMany), JoinType.INNER, List.of(), false))));
    }

    @Test
    void postJoinComparisonKeepsNullSemanticsAndUnmatchedRows() throws Exception {
        MatchGroup byId = exact("id", "person_id");
        ComparisonGroup income = ComparisonGroup.of(src("income", null), tgt("income"));

        List<List<String>> left = run(request(List.of(byId), JoinType.LEFT, List.of(income), false));
        assertEquals(5, dataRows(left));
        int name = col(left, "source_name");
        int verdict = col(left, "cmp_0_match");
        int status = col(left, "match_status");
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (List<String> row : left.subList(1, left.size())) {
            byName.put(row.get(name).replace("\"", ""), row);
        }
        assertEquals("true", byName.get("Asha Verma").get(verdict));   // 100 = 100
        assertEquals("false", byName.get("Bhim Singh").get(verdict));  // 200 vs 250
        assertEquals("true", byName.get("Chitra Rao").get(verdict));   // NULL vs NULL reads as a match
        assertEquals("", byName.get("Dev Kumar").get(verdict));        // no counterpart: NULL, NOT false
        assertEquals("", byName.get("Esha Das").get(verdict));
        assertEquals("MATCHED", byName.get("Asha Verma").get(status));
        assertEquals("NO_TARGET", byName.get("Dev Kumar").get(status));

        // mismatch-only must keep the no-counterpart rows (three-valued logic would drop them)
        List<List<String>> mismatches = run(request(List.of(byId), JoinType.LEFT, List.of(income), true));
        assertEquals(3, dataRows(mismatches), mismatches.toString()); // Bhim, Dev, Esha
    }
}
