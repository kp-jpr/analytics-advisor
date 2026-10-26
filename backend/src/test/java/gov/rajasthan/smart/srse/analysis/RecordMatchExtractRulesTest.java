package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.audit.AuditActionType;
import gov.rajasthan.smart.srse.audit.AuditCaptureService;
import gov.rajasthan.smart.srse.audit.AuditEventDraft;
import gov.rajasthan.smart.srse.audit.AuditOutcome;
import gov.rajasthan.smart.srse.audit.AuditScopeSummaryService;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.compiler.RuleColumnResolver;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AA-13 — extract rules inside scoped derived tables; single-source mode. */
@ExtendWith(MockitoExtension.class)
class RecordMatchExtractRulesTest {

    private static final String SRC = "iceberg.srse.beneficiary";
    private static final String TGT = "iceberg_silver.silver_txn.tbl_txn_bankdtl";
    private static final QualifiedTable SOURCE_TABLE = new QualifiedTable("iceberg", "srse", "beneficiary");

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private LakehouseRegistryService registry;
    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;
    @Mock
    private AnalysisScopeFromService scopeFrom;
    @Mock
    private AuditCaptureService auditCapture;
    @Mock
    private AuditScopeSummaryService scopeSummary;

    private RuleCompiler ruleCompiler;
    private RecordMatchService matchService;
    private AnalysisAuditService auditService;

    @BeforeEach
    void setUp() {
        ruleCompiler = new RuleCompiler(
                new RuleColumnResolver(registry),
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000));
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(inv -> {
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            List<?> columns = inv.getArgument(1, List.class);
            if (columns == null) {
                return described;
            }
            for (Object column : columns) {
                String name = String.valueOf(column);
                described.put(name, new RegisteredColumn(name, "bigint", null, false, true));
            }
            return described;
        });
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        lenient().when(jdbc.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(1L);
        scopeBothSides();
        matchService = new RecordMatchService(
                jdbc,
                registry,
                new GuardrailProperties(1000, 30, 50),
                columnMetadata,
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000),
                new ObjectMapper(),
                scopeFrom,
                ruleCompiler);
    }

    @Test
    void sourceRuleLivesInsideDerivedTableForAllJoinTypes() {
        Ast.PredicateSpec rules = new Ast.PredicateSpec(
                new Ast.PredicateNode(col("age_years"), Ast.Operator.GT, 18));
        for (JoinType joinType : JoinType.values()) {
            RecordMatchRequest req = joinRequest(joinType, rules, null);
            RecordMatchService.MatchQuery query = matchService.planMatch(req);
            assertTrue(
                    query.sql().contains("(SELECT * FROM " + SRC + " t WHERE district_code IN (?) AND (t.age_years > ?)) src"),
                    joinType.name());
            int onAt = query.sql().indexOf(" ON ");
            int whereAt = query.sql().indexOf(" WHERE ", onAt);
            String outerOn = whereAt > onAt
                    ? query.sql().substring(onAt, whereAt)
                    : query.sql().substring(onAt);
            assertFalse(outerOn.contains("age_years"), joinType.name() + " ON must not filter on rules");
        }
    }

    @Test
    void hiddenColumnRefusedBeforeCompile() {
        QualifiedColumn hidden = col("secret_col");
        doThrow(new IllegalArgumentException("hidden"))
                .when(registry).describeColumns(any(), any());
        Ast.PredicateSpec rules = new Ast.PredicateSpec(
                new Ast.PredicateNode(hidden, Ast.Operator.EQ, "x"));
        assertThrows(IllegalArgumentException.class, () -> matchService.planMatch(joinRequest(JoinType.INNER, rules, null)));
    }

    @Test
    void singleSourceUsesScopeAndRuleInDerivedTable() {
        when(scopeFrom.planFrom(SOURCE_TABLE)).thenReturn(
                ScopeFilteredFrom.filtered(SRC, "district_code IN (?)", List.of("RJ-JPR")));
        DisplayColumn district = new DisplayColumn("iceberg", "srse", "beneficiary", "district_code");
        Ast.PredicateSpec rules = new Ast.PredicateSpec(
                new Ast.PredicateNode(col("age_years"), Ast.Operator.GT, 18));
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(district), List.of(),
                List.of(), false, null, null, List.of(), false,
                rules, null, true, List.of(), List.of(), null, false, List.of());
        RecordMatchService.MatchQuery query = matchService.planMatch(req);
        assertTrue(query.sql().contains("(SELECT * FROM " + SRC + " t WHERE district_code IN (?) AND (t.age_years > ?)) src"));
        assertFalse(query.sql().contains(" JOIN "));
        assertArrayEquals(new Object[] {"RJ-JPR", 18}, query.params().toArray());
    }

    @Test
    void superAdminSingleSourceHasNoScopeButKeepsRule() {
        when(scopeFrom.planFrom(SOURCE_TABLE)).thenReturn(ScopeFilteredFrom.unfiltered(SRC));
        DisplayColumn district = new DisplayColumn("iceberg", "srse", "beneficiary", "district_code");
        Ast.PredicateSpec rules = new Ast.PredicateSpec(
                new Ast.PredicateNode(col("age_years"), Ast.Operator.GT, 18));
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(district), List.of(),
                List.of(), false, null, null, List.of(), false,
                rules, null, true, List.of(), List.of(), null, false, List.of());
        RecordMatchService.MatchQuery query = matchService.planMatch(req);
        assertTrue(query.sql().contains("(SELECT * FROM " + SRC + " t WHERE t.age_years > ?) src"));
        assertFalse(query.sql().contains("district_code IN"));
    }

    @Test
    void auditedMatchKeepsRuleValueAsPlaceholderOnly() {
        when(scopeFrom.planFrom(SOURCE_TABLE)).thenReturn(ScopeFilteredFrom.unfiltered(SRC));
        when(scopeSummary.summarizeCurrentOfficer()).thenReturn("RJ-JPR");
        when(scopeSummary.currentActorUserId()).thenReturn(7L);
        auditService = new AnalysisAuditService(
                matchService,
                new ColumnDistinctValuesService(jdbc, registry, scopeFrom,
                        new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000)),
                auditCapture,
                scopeSummary);
        DisplayColumn district = new DisplayColumn("iceberg", "srse", "beneficiary", "district_code");
        Ast.PredicateSpec rules = new Ast.PredicateSpec(
                new Ast.PredicateNode(col("age_years"), Ast.Operator.GT, 48000));
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(district), List.of(),
                List.of(), false, null, null, List.of(), false,
                rules, null, true, List.of(), List.of(), null, false, List.of());
        auditService.planMatchAudited(req);
        ArgumentCaptor<AuditEventDraft> captor = ArgumentCaptor.forClass(AuditEventDraft.class);
        verify(auditCapture).recordBestEffort(captor.capture());
        AuditEventDraft draft = captor.getValue();
        assertTrue(draft.queryShape().contains("?"));
        assertFalse(draft.queryShape().contains("48000"));
        assertEqualsOutcome(draft, AuditActionType.QUERY_EXECUTED, AuditOutcome.SUCCESS);
    }

    @Test
    void typedTextFuzzyRuleInsideDerivedTableWithScoreColumn() {
        when(registry.describeColumns(eq(SOURCE_TABLE), eq(List.of("father_name"))))
                .thenReturn(Map.of("father_name", new RegisteredColumn("father_name", "varchar", null, false, true)));
        DisplayColumn name = new DisplayColumn("iceberg", "srse", "beneficiary", "father_name");
        Ast.PredicateSpec rules = new Ast.PredicateSpec(new Ast.PredicateNode(
                col("father_name"), Ast.Operator.FUZZY_MATCH, List.of("Ram Kumar", 80)));
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(name), List.of(),
                List.of(), false, null, null, List.of(), false,
                rules, null, true, List.of(), List.of(), null, false, List.of());
        RecordMatchService.MatchQuery query = matchService.planMatch(req);
        assertTrue(query.sql().contains("(SELECT * FROM " + SRC + " t WHERE district_code IN (?) AND ("));
        assertTrue(query.sql().contains("substr(lower(t.father_name), 1, 3) = substr(lower(?), 1, 3)"));
        assertFalse(query.sql().contains(" ON "));
        assertTrue(query.sql().contains("match_score_pct"));
        // Order follows the SQL, not the order things were built. The score projection
        // lives in SELECT and so binds first, then the scope predicate, then the rule's
        // blocking constant, its similarity (which uses the right side twice) and its
        // threshold. Binding the score last put the threshold into a lower().
        assertArrayEquals(
                new Object[] {"Ram Kumar", "Ram Kumar", "RJ-SGN", "Ram Kumar", "Ram Kumar", "Ram Kumar", 0.8},
                query.params().toArray());
        assertEquals((int) query.sql().chars().filter(c -> c == '?').count(), query.params().size(),
                "placeholders and parameters must agree, or Presto rejects the statement");
    }

    @Test
    void displaySqlInlinesNullInListRuleWithoutNpe() {
        DisplayColumn district = new DisplayColumn("iceberg", "srse", "beneficiary", "district");
        List<Object> inValues = new ArrayList<>();
        inValues.add("Jaipur");
        inValues.add(null);
        Ast.PredicateSpec rules = new Ast.PredicateSpec(new Ast.PredicateNode(
                col("district"), Ast.Operator.IN, inValues));
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(district), List.of(),
                List.of(), false, null, null, List.of(), false,
                rules, null, true, List.of(), List.of(), null, false, List.of());
        RecordMatchService.MatchQuery query = matchService.planMatch(req);
        String display = matchService.renderQueryForDisplay(query);
        assertTrue(display.contains("'Jaipur'"));
        assertTrue(display.contains("NULL"));
        assertFalse(display.contains("?"), display);
    }

    @Test
    void fieldResolverTypesRemovedFromClasspath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("gov.rajasthan.smart.srse.metadata.FieldResolver"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("gov.rajasthan.smart.srse.metadata.StubFieldResolver"));
    }

    private static void assertEqualsOutcome(AuditEventDraft draft, AuditActionType type, AuditOutcome outcome) {
        org.junit.jupiter.api.Assertions.assertEquals(type, draft.actionType());
        org.junit.jupiter.api.Assertions.assertEquals(outcome, draft.outcome());
    }

    private void scopeBothSides() {
        lenient().when(scopeFrom.planFrom(new QualifiedTable("iceberg", "srse", "beneficiary")))
                .thenReturn(ScopeFilteredFrom.filtered(SRC, "district_code IN (?)", List.of("RJ-SGN")));
        lenient().when(scopeFrom.planFrom(new QualifiedTable("iceberg_silver", "silver_txn", "tbl_txn_bankdtl")))
                .thenReturn(ScopeFilteredFrom.filtered(TGT, "district_code IN (?)", List.of("RJ-SGN")));
    }

    private static RecordMatchRequest joinRequest(
            JoinType joinType, Ast.PredicateSpec sourceRules, Ast.PredicateSpec targetRules) {
        MatchCriterion src = new MatchCriterion("iceberg", "srse", "beneficiary", "m_id", null);
        MatchCriterion tgt = new MatchCriterion("iceberg_silver", "silver_txn", "tbl_txn_bankdtl", "m_id", null);
        return new RecordMatchRequest(
                List.of(src), List.of(tgt), null, null, List.of(), false, null, joinType,
                List.of(), false, sourceRules, targetRules, false, List.of(), List.of(), null, false, List.of());
    }

    private static QualifiedColumn col(String name) {
        return new QualifiedColumn(SOURCE_TABLE, name);
    }
}
