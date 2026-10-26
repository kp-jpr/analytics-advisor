package gov.rajasthan.smart.srse.analysis;

import com.facebook.presto.sql.parser.ParsingException;
import com.facebook.presto.sql.parser.ParsingOptions;
import com.facebook.presto.sql.parser.SqlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.ColumnGroupSql;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Runs the SQL the Analysis tab emits through PrestoDB's OWN grammar.
 *
 * <p>Every other test in this package asserts that the emitted SQL contains
 * the substrings a test author expected. That catches drift, but it cannot
 * catch a construct that is wrong — a misplaced {@code UNNEST}, a subquery
 * that needs an alias, a lambda the 0.297 grammar spells differently. Those
 * would pass a string assertion and fail in front of an officer, at the one
 * moment nobody is watching a unit test.
 *
 * <p>{@code presto-parser} is pinned to the same version as the JDBC driver
 * ({@code presto.jdbc.version}), so this is the grammar the deployed cluster
 * actually uses, not an approximation of it.
 *
 * <p>WHAT THIS DOES NOT PROVE: parsing is syntax only. It says nothing about
 * whether the catalogs, schemas, tables or columns exist, whether the types
 * resolve, or whether the query returns the right rows. A cross-family
 * comparison Presto rejects at analysis time still parses cleanly here. This
 * closes the "is that UNNEST subquery even valid Presto" question and nothing
 * beyond it.
 *
 * <p>For full analyzer coverage (name resolution, alias scope, function lookup),
 * run {@link AnalysisEmittedSqlPrestoValidateIT} against a local Presto container
 * with {@code SRSE_PRESTO_INTEGRATION=true}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmittedSqlParsesTest {

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";

    private final SqlParser parser = new SqlParser();

    /**
     * Decimal literals are treated AS_DECIMAL, which is what a Presto server
     * does by default ({@code parse-decimal-literals-as-double} is false).
     *
     * <p>Not the no-arg {@code createStatement(sql)}: that defaults to
     * {@code REJECT}, which is the library forcing an embedding caller to
     * choose rather than a statement about the language. Under REJECT the
     * {@code 1.0} in every fuzzy-similarity expression fails to parse even
     * though it is valid SQL that the cluster accepts — which would make this
     * test report a defect that is not there.
     */
    private static final ParsingOptions PARSING_OPTIONS = ParsingOptions.builder()
            .setDecimalLiteralTreatment(ParsingOptions.DecimalLiteralTreatment.AS_DECIMAL)
            .build();

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private LakehouseRegistryService registry;
    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;
    @Mock
    private AnalysisScopeFromService scopeFrom;
    @Mock
    private gov.rajasthan.smart.srse.compiler.RuleCompiler ruleCompiler;

    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        lenient().when(ruleCompiler.compile(any(), any()))
                .thenReturn(new gov.rajasthan.smart.srse.compiler.CompiledQuery("TRUE", List.of()));
        service = new RecordMatchService(
                jdbc, registry, new GuardrailProperties(1000, 30, 50), columnMetadata,
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000), new ObjectMapper(), scopeFrom,
                ruleCompiler);
        lenient().when(scopeFrom.planFrom(any())).thenAnswer(inv -> {
            QualifiedTable table = inv.getArgument(0);
            return ScopeFilteredFrom.unfiltered(table.qualifiedName());
        });
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(inv -> {
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            List<?> requested = inv.getArgument(1, List.class);
            for (Object column : requested) {
                String name = String.valueOf(column);
                described.put(name, new RegisteredColumn(name, "varchar(50)", null, false, true));
            }
            return described;
        });
    }

    private static MatchCriterion col(String table, String column) {
        return new MatchCriterion(CATALOG, SCHEMA, table, column, null);
    }

    private static MatchGroup group(List<MatchCriterion> source, List<MatchCriterion> target,
                                    GroupMode mode, Double threshold) {
        return new MatchGroup(source, target, mode, threshold, null);
    }

    private String sqlFor(List<MatchGroup> groups, boolean highlight, DedupSpec dedup) {
        return sqlFor(groups, highlight, dedup, null);
    }

    private String sqlFor(List<MatchGroup> groups, boolean highlight, DedupSpec dedup, JoinType joinType) {
        return service.planMatch(new RecordMatchRequest(
                List.of(), List.of(), null, null, groups, highlight, dedup, joinType)).sql();
    }

    private RecordMatchRequest requestWithComparisons(List<MatchGroup> groups, List<ComparisonGroup> comparisons,
                                                      boolean mismatchOnly, JoinType joinType) {
        return new RecordMatchRequest(
                List.of(), List.of(), null, null, groups, false, null, joinType,
                comparisons, mismatchOnly);
    }

    private String sqlWithComparisons(List<MatchGroup> groups, List<ComparisonGroup> comparisons,
                                      boolean mismatchOnly, JoinType joinType) {
        return service.planMatch(requestWithComparisons(groups, comparisons, mismatchOnly, joinType)).sql();
    }

    private static int countJdbcPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    private void assertParses(String sql) {
        assertDoesNotThrow(() -> parser.createStatement(sql, PARSING_OPTIONS), sql);
    }

    /** Parsing does not prove JDBC can bind the statement — placeholder count must match params. */
    private void assertParsesWithBindableParams(RecordMatchRequest req) {
        RecordMatchService.MatchQuery query = service.planMatch(req);
        assertParses(query.sql());
        assertEquals(countJdbcPlaceholders(query.sql()), query.params().size(), query.sql());
    }

    private RecordMatchRequest requestFor(List<MatchGroup> groups, boolean highlight, DedupSpec dedup,
                                          JoinType joinType) {
        return new RecordMatchRequest(
                List.of(), List.of(), null, null, groups, highlight, dedup, joinType);
    }

    /** The plain pair, unchanged since before groups existed. */
    @Test
    void singleColumnPairParses() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "district")), List.of(col("golden", "district")),
                        GroupMode.COMBINE, null)), false, null));
    }

    @Test
    void combineFoldParsesIncludingTheLambdaInsideFilter() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "addr_full")),
                        List.of(col("golden", "line1"), col("golden", "line2"), col("golden", "line3")),
                        GroupMode.COMBINE, null)), false, null));
    }

    /**
     * The construct this whole test class was written for: an ANY_OF side
     * becomes a derived table wrapping {@code t.*} plus the unnested columns.
     */
    @Test
    void anyOfUnnestSubqueryParses() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "account_no")),
                        List.of(col("golden", "ja_id"), col("golden", "legacy_id")),
                        GroupMode.ANY_OF, null)), false, null));
    }

    @Test
    void anyOfOnBothSidesParses() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "a1"), col("txn", "a2")),
                        List.of(col("golden", "b1"), col("golden", "b2")),
                        GroupMode.ANY_OF, null)), false, null));
    }

    /** Two UNNESTs on one side — the shape the ANY_OF cap allows at most. */
    @Test
    void twoAnyOfGroupsOnOneSideParse() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "a")), List.of(col("golden", "x1"), col("golden", "x2")),
                        GroupMode.ANY_OF, null),
                group(List.of(col("txn", "b")), List.of(col("golden", "y1"), col("golden", "y2")),
                        GroupMode.ANY_OF, null)), false, null));
    }

    @Test
    void fuzzyFoldWithBlockingKeyAndSimilarityParses() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "full_name")),
                        List.of(col("golden", "first_name"), col("golden", "last_name")),
                        GroupMode.COMBINE, 85.0)), false, null));
    }

    /** Fuzzy ANY_OF: blocking key and Levenshtein over an unnested key. */
    @Test
    void fuzzyAnyOfParses() {
        assertParses(sqlFor(List.of(
                group(List.of(col("txn", "full_name")),
                        List.of(col("golden", "name_a"), col("golden", "name_b")),
                        GroupMode.ANY_OF, 80.0)), false, null));
    }

    /** Everything at once: mixed modes, the score expression, and dedup's window. */
    @Test
    void theWholeThingTogetherParses() {
        assertParses(sqlFor(
                List.of(
                        group(List.of(col("txn", "full_name")),
                                List.of(col("golden", "first_name"), col("golden", "last_name")),
                                GroupMode.COMBINE, 85.0),
                        group(List.of(col("txn", "account_no")),
                                List.of(col("golden", "ja_id"), col("golden", "legacy_id")),
                                GroupMode.ANY_OF, null),
                        group(List.of(col("txn", "district")), List.of(col("golden", "district")),
                                GroupMode.COMBINE, null)),
                true,
                new DedupSpec(CATALOG, SCHEMA, "golden", "updated_at")));
    }

    /** The emitters in isolation, so a break is attributable without a whole query around it. */
    @Test
    void groupSqlFragmentsParseOnTheirOwn() {
        assertParses("SELECT " + ColumnGroupSql.combine(List.of("t.a", "t.b"), " ") + " FROM x");
        assertParses("SELECT k FROM x t " + ColumnGroupSql.unnestClause(
                "t", List.of("a", "b"), false, "u0", "g0_key", "g0_matched_on"));
        assertParses("SELECT k FROM x t " + ColumnGroupSql.unnestClause(
                "t", List.of("a", "b"), true, "u0", "g0_key", "g0_matched_on"));
    }

    /**
     * Proves the parser is actually load-bearing here. Without this, a
     * mistake that made every query above degenerate to something trivially
     * parseable would leave all these tests green and say nothing.
     */
    @Test
    void theParserRejectsSqlThatIsActuallyBroken() {
        assertThrows(ParsingException.class,
                () -> parser.createStatement("SELECT FROM WHERE JOIN (", PARSING_OPTIONS));
    }

    /** Syntax-only coverage per join type — semantic correctness needs manual Presto runs. */
    @ParameterizedTest
    @EnumSource(JoinType.class)
    void eachJoinTypeParsesWithFuzzyCombineAndAnyOf(JoinType joinType) {
        DedupSpec dedup = joinType == JoinType.RIGHT || joinType == JoinType.FULL ? null
                : new DedupSpec(CATALOG, SCHEMA, "golden", "updated_at");
        assertParses(sqlFor(
                List.of(
                        group(List.of(col("txn", "full_name")),
                                List.of(col("golden", "first_name"), col("golden", "last_name")),
                                GroupMode.COMBINE, 85.0),
                        group(List.of(col("txn", "account_no")),
                                List.of(col("golden", "ja_id"), col("golden", "legacy_id")),
                                GroupMode.ANY_OF, null),
                        group(List.of(col("txn", "district")), List.of(col("golden", "district")),
                                GroupMode.COMBINE, null)),
                true, dedup, joinType));
    }

    @Test
    void innerJoinSqlUsesBareJoinKeywordForRegression() {
        String sql = sqlFor(List.of(
                group(List.of(col("txn", "district")), List.of(col("golden", "district")),
                        GroupMode.COMBINE, null)), false, null, JoinType.INNER);
        assertTrue(sql.contains(CATALOG + "." + SCHEMA + ".txn src JOIN "
                + CATALOG + "." + SCHEMA + ".golden tgt"), sql);
        assertFalse(sql.contains("INNER JOIN"), sql);
    }

    @Test
    void postJoinComparisonParses() {
        assertParses(sqlWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(ComparisonGroup.of(col("txn", "pan"), col("golden", "pan"))),
                false, JoinType.INNER));
    }

    @Test
    void postJoinComparisonCombineFoldParses() {
        assertParses(sqlWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(new ComparisonGroup(
                        List.of(col("txn", "addr_full")),
                        List.of(col("golden", "line1"), col("golden", "line2")),
                        GroupMode.COMBINE, null, " ")),
                false, JoinType.INNER));
    }

    @Test
    void postJoinFuzzyComparisonParses() {
        ComparisonGroup fuzzyName = new ComparisonGroup(
                List.of(col("txn", "holder_name")),
                List.of(col("golden", "holder_name")),
                GroupMode.COMBINE, 85.0, null);
        assertParses(sqlWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(fuzzyName),
                false, JoinType.INNER));
    }

    @ParameterizedTest
    @EnumSource(JoinType.class)
    void postJoinComparisonWithEachJoinTypeParses(JoinType joinType) {
        assertParses(sqlWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(ComparisonGroup.of(col("txn", "district"), col("golden", "district"))),
                false, joinType));
    }

    @Test
    void mismatchOnlyComparisonFilterParses() {
        assertParses(sqlWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(ComparisonGroup.of(col("txn", "pan"), col("golden", "pan"))),
                true, JoinType.LEFT));
    }

    @Test
    void mismatchOnlyWithFuzzyComparisonBindsThresholdTwice() {
        ComparisonGroup fuzzyName = new ComparisonGroup(
                List.of(col("txn", "full_name")),
                List.of(col("golden", "full_name")),
                GroupMode.COMBINE, 85.0, null);
        RecordMatchRequest req = requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(fuzzyName),
                true, JoinType.INNER);
        RecordMatchService.MatchQuery query = service.planMatch(req);
        assertEquals(2, countJdbcPlaceholders(query.sql()), query.sql());
        assertEquals(2, query.params().size());
        assertEquals(query.params().get(0), query.params().get(1));
        assertParses(query.sql());
    }

    /**
     * Every shape this class documents must be bindable, not merely parseable —
     * duplicate {@code ?} in SELECT and WHERE without a second bind broke
     * {@code mismatchOnly} with fuzzy comparisons.
     */
    @Test
    void placeholderCountMatchesParamListForAllDocumentedShapes() {
        List<MatchGroup> districtPair = List.of(
                group(List.of(col("txn", "district")), List.of(col("golden", "district")),
                        GroupMode.COMBINE, null));
        List<MatchGroup> mixedHeavy = List.of(
                group(List.of(col("txn", "full_name")),
                        List.of(col("golden", "first_name"), col("golden", "last_name")),
                        GroupMode.COMBINE, 85.0),
                group(List.of(col("txn", "account_no")),
                        List.of(col("golden", "ja_id"), col("golden", "legacy_id")),
                        GroupMode.ANY_OF, null),
                group(List.of(col("txn", "district")), List.of(col("golden", "district")),
                        GroupMode.COMBINE, null));
        ComparisonGroup fuzzyCompare = new ComparisonGroup(
                List.of(col("txn", "holder_name")),
                List.of(col("golden", "holder_name")),
                GroupMode.COMBINE, 85.0, null);
        List<RecordMatchRequest> cases = new ArrayList<>();
        cases.add(requestFor(districtPair, false, null, null));
        cases.add(requestFor(List.of(group(List.of(col("txn", "addr_full")),
                        List.of(col("golden", "line1"), col("golden", "line2")),
                        GroupMode.COMBINE, null)), false, null, null));
        cases.add(requestFor(List.of(group(List.of(col("txn", "account_no")),
                        List.of(col("golden", "ja_id"), col("golden", "legacy_id")),
                        GroupMode.ANY_OF, null)), false, null, null));
        cases.add(requestFor(List.of(group(List.of(col("txn", "a1"), col("txn", "a2")),
                        List.of(col("golden", "b1"), col("golden", "b2")),
                        GroupMode.ANY_OF, null)), false, null, null));
        cases.add(requestFor(List.of(group(List.of(col("txn", "full_name")),
                        List.of(col("golden", "first_name"), col("golden", "last_name")),
                        GroupMode.COMBINE, 85.0)), false, null, null));
        cases.add(requestFor(List.of(group(List.of(col("txn", "full_name")),
                        List.of(col("golden", "name_a"), col("golden", "name_b")),
                        GroupMode.ANY_OF, 80.0)), false, null, null));
        cases.add(requestFor(mixedHeavy, true,
                new DedupSpec(CATALOG, SCHEMA, "golden", "updated_at"), null));
        cases.add(requestFor(mixedHeavy, true, null, JoinType.LEFT));
        cases.add(requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(ComparisonGroup.of(col("txn", "pan"), col("golden", "pan"))),
                false, JoinType.INNER));
        cases.add(requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(new ComparisonGroup(
                        List.of(col("txn", "addr_full")),
                        List.of(col("golden", "line1"), col("golden", "line2")),
                        GroupMode.COMBINE, null, " ")),
                false, JoinType.INNER));
        cases.add(requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(fuzzyCompare),
                false, JoinType.INNER));
        cases.add(requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(ComparisonGroup.of(col("txn", "pan"), col("golden", "pan"))),
                true, JoinType.LEFT));
        cases.add(requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(fuzzyCompare),
                true, JoinType.INNER));
        cases.add(requestWithComparisons(
                List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                        GroupMode.COMBINE, null)),
                List.of(ComparisonGroup.of(col("txn", "pan"), col("golden", "pan"))),
                true, JoinType.LEFT));
        for (JoinType joinType : JoinType.values()) {
            DedupSpec dedup = joinType == JoinType.RIGHT || joinType == JoinType.FULL ? null
                    : new DedupSpec(CATALOG, SCHEMA, "golden", "updated_at");
            cases.add(requestFor(mixedHeavy, true, dedup, joinType));
            cases.add(requestWithComparisons(
                    List.of(group(List.of(col("txn", "m_id")), List.of(col("golden", "m_id")),
                            GroupMode.COMBINE, null)),
                    List.of(ComparisonGroup.of(col("txn", "district"), col("golden", "district"))),
                    false, joinType));
        }
        for (RecordMatchRequest req : cases) {
            assertParsesWithBindableParams(req);
        }
    }

    @Test
    void groupedSingleSourceParses() {
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(), List.of(), List.of(), List.of(), List.of(), false, null, null, List.of(), false,
                null, null, true,
                List.of(new DisplayColumn(CATALOG, SCHEMA, "beneficiary", "district")),
                List.of(new AggregateSpec(AggregateFunction.COUNT, null, false, null)),
                null,
                false,
                List.of());
        assertParsesWithBindableParams(req);
        assertTrue(service.planMatch(req).sql().contains(" GROUP BY "));
    }

    /** AA-15 — registry display labels are filters only; they must never reach emitted SQL. */
    @Test
    void plannedMatchSqlNeverContainsRegistryDisplayLabelTokens() {
        String sql = sqlFor(
                List.of(group(List.of(col("beneficiary", "m_id")), List.of(col("beneficiary", "m_id")), GroupMode.COMBINE, null)),
                false,
                null);
        assertFalse(sql.contains("sourceSystem"));
        assertFalse(sql.contains("tableGroup"));
        assertFalse(sql.contains("UNTAGGED"));
    }
}
