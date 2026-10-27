package gov.rajasthan.smart.srse.compiler;

import gov.rajasthan.smart.srse.analysis.AnalysisProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleCompilerTest {

    private static final QualifiedTable TABLE =
            new QualifiedTable("iceberg", "srse", "beneficiary");

    @Mock
    private LakehouseRegistryService registry;

    private RuleCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new RuleCompiler(
                new RuleColumnResolver(registry),
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 100, 100_000));
        lenient().when(registry.describeColumns(eq(TABLE), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<String> cols = invocation.getArgument(1);
                    java.util.Map<String, RegisteredColumn> map = new java.util.HashMap<>();
                    for (String c : cols) {
                        map.put(c, column(c, "varchar"));
                    }
                    return map;
                });
    }

    @Test
    void compilesParameterisedPredicate() {
        var income = new Ast.PredicateNode(
                col("annual_income_total"), Ast.Operator.LT, 48000);
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(income), TABLE);
        assertTrue(q.predicateSql().contains("t.annual_income_total"));
        assertTrue(q.predicateSql().contains("?"));
        assertEquals(List.of(48000), q.params());
    }

    @Test
    void numberTypedValueUsesTryCastOnVarcharColumn() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("annual_income_total"))))
                .thenReturn(Map.of("annual_income_total", column("annual_income_total", "varchar")));
        var node = new Ast.PredicateNode(col("annual_income_total"), Ast.Operator.LT, 48000);
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertTrue(q.predicateSql().contains("TRY_CAST(t.annual_income_total AS DOUBLE)"));
    }

    @Test
    void booleanColumnIsNotCoerced() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("is_active"))))
                .thenReturn(Map.of("is_active", column("is_active", "boolean")));
        var node = new Ast.PredicateNode(col("is_active"), Ast.Operator.IS_TRUE);
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.is_active = TRUE", q.predicateSql());
    }

    @Test
    void numericColumnCastsATextValueToDouble() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("age"))))
                .thenReturn(Map.of("age", column("age", "bigint")));
        var node = new Ast.PredicateNode(col("age"), Ast.Operator.EQ, "18");
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.age = CAST(? AS DOUBLE)", q.predicateSql());
        assertEquals(List.of("18"), q.params());
    }

    @Test
    void dateColumnCastsTheBoundTextToDate() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("dob"))))
                .thenReturn(Map.of("dob", column("dob", "date")));
        var node = new Ast.PredicateNode(col("dob"), Ast.Operator.EQ, "2000-01-01");
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.dob = CAST(? AS DATE)", q.predicateSql());
        assertEquals(List.of("2000-01-01"), q.params());
        assertFalse(q.predicateSql().contains("TRY_CAST"));
        assertFalse(q.predicateSql().contains("CAST(t.dob"));
    }

    @Test
    void timestampColumnCastsTheBoundTextToTimestamp() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("updated_at"))))
                .thenReturn(Map.of("updated_at", column("updated_at", "timestamp(3) with time zone")));
        var node = new Ast.PredicateNode(col("updated_at"), Ast.Operator.GTE, "2000-01-01 00:00:00");
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.updated_at >= CAST(? AS TIMESTAMP WITH TIME ZONE)", q.predicateSql());
    }

    @Test
    void dateInListCastsEveryBoundText() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("dob"))))
                .thenReturn(Map.of("dob", column("dob", "date")));
        var node = new Ast.PredicateNode(col("dob"), Ast.Operator.IN, List.of("2000-01-01", "2001-02-02"));
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.dob IN (CAST(? AS DATE), CAST(? AS DATE))", q.predicateSql());
        assertEquals(List.of("2000-01-01", "2001-02-02"), q.params());
    }

    @Test
    void wrongTableRefused() {
        QualifiedColumn other = new QualifiedColumn("iceberg", "srse", "other", "x");
        var node = new Ast.PredicateNode(other, Ast.Operator.EQ, "a");
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(new Ast.PredicateSpec(node), TABLE));
    }

    private static QualifiedColumn col(String name) {
        return new QualifiedColumn(TABLE, name);
    }

    @Test
    void inListMatchesHandBuiltPredicate() {
        var node = new Ast.PredicateNode(col("district"), Ast.Operator.IN, List.of("Jaipur", "Udaipur"));
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.district IN (?, ?)", q.predicateSql());
        assertEquals(List.of("Jaipur", "Udaipur"), q.params());
    }

    @Test
    void inListAllowsSqlNullElement() {
        List<Object> values = new ArrayList<>();
        values.add("Jaipur");
        values.add(null);
        var node = new Ast.PredicateNode(col("district"), Ast.Operator.IN, values);
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertEquals("t.district IN (?, ?)", q.predicateSql());
        assertEquals(2, q.params().size());
        assertEquals("Jaipur", q.params().get(0));
        assertEquals(null, q.params().get(1));
    }

    @Test
    void fuzzyMatchUsesBlockingKeyAndKeepsTypedValueAsPlaceholder() {
        when(registry.describeColumns(eq(TABLE), eq(List.of("father_name"))))
                .thenReturn(Map.of("father_name", column("father_name", "varchar")));
        var node = new Ast.PredicateNode(
                col("father_name"),
                Ast.Operator.FUZZY_MATCH,
                List.of("Ram Kumar", 80));
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(node), TABLE);
        assertTrue(q.predicateSql().contains("substr(lower(t.father_name), 1, 3) = substr(lower(?), 1, 3)"));
        assertTrue(q.predicateSql().contains("levenshtein_distance(lower(t.father_name), lower(?))"));
        // Three bindings of the name, not two: one for the blocking constant and two
        // for the similarity, which uses its right side in levenshtein_distance AND in
        // the length denominator. Binding it once left the statement a parameter short
        // and every typed-text fuzzy failed with a SQLException.
        assertEquals(List.of("Ram Kumar", "Ram Kumar", "Ram Kumar", 0.8), q.params());
        assertTrue(q.fuzzyScore().selectExpr().contains("src.father_name"));
        assertEquals(List.of("Ram Kumar", "Ram Kumar"), q.fuzzyScore().params());
        assertEquals(countPlaceholders(q.predicateSql()), q.params().size(),
                "every placeholder in the predicate needs a parameter");
        assertEquals(countPlaceholders(q.fuzzyScore().selectExpr()), q.fuzzyScore().params().size(),
                "every placeholder in the score projection needs a parameter");
    }

    private static RegisteredColumn column(String name, String type) {
        return new RegisteredColumn(name, type, null, false, true);
    }

    private static int countPlaceholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }
}
