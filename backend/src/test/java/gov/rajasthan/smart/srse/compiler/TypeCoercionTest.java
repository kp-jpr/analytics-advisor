package gov.rajasthan.smart.srse.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeCoercionTest {

    private static final String SRC = "src.account_no";
    private static final String TGT = "tgt.account_no";

    /** The reported failure: varchar one side, bigint the other. */
    @Test
    void numberVersusTextComparesAsNumbersByDefault() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.TEXT, TGT, SqlTypeFamily.NUMBER, CompareAs.AUTO);

        assertEquals("TRY_CAST(src.account_no AS DOUBLE)", aligned.left());
        assertEquals(TGT, aligned.right());
    }

    /**
     * Presto coerces within a family perfectly well; a cast here would only
     * change the comparison's semantics for no reason.
     */
    @Test
    void sameFamilyIsLeftAlone() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.NUMBER, TGT, SqlTypeFamily.NUMBER, CompareAs.AUTO);

        assertEquals(SRC, aligned.left());
        assertEquals(TGT, aligned.right());
    }

    @Test
    void identicalTextColumnsAreLeftAlone() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.TEXT, TGT, SqlTypeFamily.TEXT, CompareAs.AUTO);

        assertEquals(SRC, aligned.left());
        assertEquals(TGT, aligned.right());
    }

    /** A type SRSE cannot reason about is emitted untouched, not guessed at. */
    @Test
    void anUnknownTypeOnEitherSideDisablesCoercion() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.UNKNOWN, TGT, SqlTypeFamily.NUMBER, CompareAs.AUTO);

        assertEquals(SRC, aligned.left());
        assertEquals(TGT, aligned.right());
    }

    @Test
    void dateVersusTextComparesAsDatesByDefault() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                "src.dob", SqlTypeFamily.TEXT, "tgt.dob", SqlTypeFamily.TEMPORAL, CompareAs.AUTO);

        assertEquals("TRY_CAST(src.dob AS DATE)", aligned.left());
        assertEquals("tgt.dob", aligned.right());
    }

    @Test
    void dateOverrideCastsNonTemporalSides() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                "src.dob", SqlTypeFamily.TEXT, "tgt.dob", SqlTypeFamily.TEMPORAL, CompareAs.DATE);

        assertEquals("TRY_CAST(src.dob AS DATE)", aligned.left());
        assertEquals("tgt.dob", aligned.right());
    }

    @Test
    void textOverrideForcesBothSidesToText() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.TEXT, TGT, SqlTypeFamily.NUMBER, CompareAs.TEXT);

        assertEquals(SRC, aligned.left());
        assertEquals("CAST(tgt.account_no AS VARCHAR)", aligned.right());
    }

    /**
     * An explicit NUMBER on two text columns is a real instruction, not a
     * no-op: it is how an admin says these two text columns hold the same
     * number written differently ("0123" vs "123").
     */
    @Test
    void numberOverrideAppliesEvenWhenBothSidesAreText() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.TEXT, TGT, SqlTypeFamily.TEXT, CompareAs.NUMBER);

        assertEquals("TRY_CAST(src.account_no AS DOUBLE)", aligned.left());
        assertEquals("TRY_CAST(tgt.account_no AS DOUBLE)", aligned.right());
    }

    /** No numeric reading of date-vs-flag; text is the only shared ground. */
    @Test
    void mixedNonNumericFamiliesFallBackToText() {
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                SRC, SqlTypeFamily.TEMPORAL, TGT, SqlTypeFamily.BOOLEAN, CompareAs.AUTO);

        assertEquals("CAST(src.account_no AS VARCHAR)", aligned.left());
        assertEquals("CAST(tgt.account_no AS VARCHAR)", aligned.right());
    }

    @Test
    void asTextLeavesTextAloneAndCastsEverythingElse() {
        assertEquals(SRC, TypeCoercion.asText(SRC, SqlTypeFamily.TEXT));
        assertEquals("CAST(src.account_no AS VARCHAR)", TypeCoercion.asText(SRC, SqlTypeFamily.NUMBER));
        assertEquals(SRC, TypeCoercion.asText(SRC, SqlTypeFamily.UNKNOWN));
    }

    // ---- column vs bound value (the Rule Engine's case) ----

    @Test
    void numericFieldOnATextColumnCastsTheColumn() {
        assertEquals("TRY_CAST(tbl.annual_income AS DOUBLE)",
                TypeCoercion.alignColumnToValue("tbl.annual_income", SqlTypeFamily.TEXT, SqlTypeFamily.NUMBER));
    }

    @Test
    void textFieldOnANumericColumnCastsTheColumn() {
        assertEquals("CAST(tbl.district_code AS VARCHAR)",
                TypeCoercion.alignColumnToValue("tbl.district_code", SqlTypeFamily.NUMBER, SqlTypeFamily.TEXT));
    }

    @Test
    void matchingColumnAndValueTypesAreLeftAlone() {
        assertEquals("tbl.annual_income",
                TypeCoercion.alignColumnToValue("tbl.annual_income", SqlTypeFamily.NUMBER, SqlTypeFamily.NUMBER));
    }

    /**
     * A boolean or date field is not coerced: TRY_CAST would turn the 'Y'/'N'
     * flags this data is full of into silent NULLs, which is worse than the
     * query failing.
     */
    @Test
    void booleanAndTemporalFieldsAreNeverCoerced() {
        assertEquals("tbl.is_bpl",
                TypeCoercion.alignColumnToValue("tbl.is_bpl", SqlTypeFamily.TEXT, SqlTypeFamily.BOOLEAN));
        assertEquals("tbl.dob",
                TypeCoercion.alignColumnToValue("tbl.dob", SqlTypeFamily.TEXT, SqlTypeFamily.TEMPORAL));
    }

    @Test
    void boundTextCastsToTheColumnTemporalType() {
        assertEquals("DATE", TypeCoercion.temporalSqlType("date"));
        assertEquals("TIMESTAMP", TypeCoercion.temporalSqlType("timestamp(3)"));
        assertEquals("TIMESTAMP WITH TIME ZONE", TypeCoercion.temporalSqlType("timestamp(3) with time zone"));
        assertEquals("TIME", TypeCoercion.temporalSqlType("time"));
        assertEquals("DATE", TypeCoercion.temporalSqlType(null));
    }
}
