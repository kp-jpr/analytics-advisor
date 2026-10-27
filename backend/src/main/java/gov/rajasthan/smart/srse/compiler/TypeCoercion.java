package gov.rajasthan.smart.srse.compiler;

/**
 * Emits the casts that let two operands of different physical types be
 * compared, instead of letting Presto reject the query.
 *
 * <p><b>The problem.</b> The same business identifier is routinely stored with
 * different types in different tables — an account number as {@code varchar}
 * in the transaction table and {@code bigint} in the golden layer, a flag as
 * {@code varchar} on one side and {@code boolean} on the other. Presto does
 * not implicitly coerce across those families, so the comparison fails to
 * compile: {@code '=' cannot be applied to varchar, bigint}. Text functions
 * are just as strict — {@code lower(bigint)} is {@code Unexpected parameters},
 * which is why a fuzzy pair needs its own treatment.
 *
 * <p><b>What this does NOT do.</b> Nothing, when both operands are in the same
 * {@link SqlTypeFamily}: Presto already coerces integer↔bigint↔decimal,
 * varchar(20)↔varchar(50) and date↔timestamp correctly, and wrapping those in
 * a cast could only change results. Nothing either when a side's type is
 * {@link SqlTypeFamily#UNKNOWN} — a cast SRSE cannot reason about is more
 * likely to break a working query than to fix a broken one.
 *
 * <p><b>Direction.</b> For the mixed case that actually matters — number vs
 * text — the default is to compare AS NUMBERS, with the text side going
 * through {@code TRY_CAST}. See {@link CompareAs#AUTO} for why, and
 * {@link CompareAs} for the per-column admin override that forces the other
 * direction. {@code TRY_CAST} rather than {@code CAST} throughout: a single
 * unparseable row must not fail the whole match, and a NULL simply does not
 * match, which is the outcome that row would have had anyway.
 */
public final class TypeCoercion {

    private TypeCoercion() {
    }

    /** Two operand SQL fragments, ready to be compared with {@code =}. */
    public record Aligned(String left, String right) {
    }

    /**
     * Aligns two column references for a direct comparison.
     *
     * @param mode the resolved per-column override; {@link CompareAs#AUTO}
     *             for the built-in behaviour
     */
    public static Aligned align(String leftSql, SqlTypeFamily leftType,
                                String rightSql, SqlTypeFamily rightType,
                                CompareAs mode) {
        if (leftType == SqlTypeFamily.UNKNOWN || rightType == SqlTypeFamily.UNKNOWN) {
            return new Aligned(leftSql, rightSql);
        }
        CompareAs effective = CompareAs.orAuto(mode);
        if (effective == CompareAs.TEXT) {
            return new Aligned(asText(leftSql, leftType), asText(rightSql, rightType));
        }
        if (effective == CompareAs.NUMBER) {
            return new Aligned(asNumber(leftSql, leftType), asNumber(rightSql, rightType));
        }
        if (effective == CompareAs.DATE) {
            return new Aligned(asDate(leftSql, leftType), asDate(rightSql, rightType));
        }
        if (leftType == rightType) {
            return new Aligned(leftSql, rightSql);
        }
        // The column types are known before the join is written. A date
        // matched to text is compared as a date; a number matched to text
        // is compared as a number. Anything else mixed (a date against a
        // flag, for example) has no shared reading except text.
        if ((leftType == SqlTypeFamily.TEMPORAL && rightType == SqlTypeFamily.TEXT)
                || (rightType == SqlTypeFamily.TEMPORAL && leftType == SqlTypeFamily.TEXT)) {
            return new Aligned(asDate(leftSql, leftType), asDate(rightSql, rightType));
        }
        if (leftType == SqlTypeFamily.NUMBER || rightType == SqlTypeFamily.NUMBER) {
            return new Aligned(asNumber(leftSql, leftType), asNumber(rightSql, rightType));
        }
        return new Aligned(asText(leftSql, leftType), asText(rightSql, rightType));
    }

    /**
     * The operand as text, for the string functions a fuzzy match is built
     * from ({@code lower}, {@code substr}, {@code levenshtein_distance}) —
     * none of which accept a non-text argument.
     *
     * <p>Plain {@code CAST}, not {@code TRY_CAST}: every scalar type has a
     * text form, so there is no row-level failure to absorb, and TRY_CAST
     * would turn a whole-query type error into silent NULLs.
     */
    public static String asText(String sql, SqlTypeFamily type) {
        if (type == SqlTypeFamily.TEXT || type == SqlTypeFamily.UNKNOWN) {
            return sql;
        }
        return "CAST(" + sql + " AS VARCHAR)";
    }

    /**
     * The operand as a number. DOUBLE rather than BIGINT so a decimal or a
     * text value like {@code "1234.50"} survives; identifiers stay exact well
     * past any real account or Jan-Aadhaar number (DOUBLE is exact to 2^53).
     */
    public static String asNumber(String sql, SqlTypeFamily type) {
        if (type == SqlTypeFamily.NUMBER || type == SqlTypeFamily.UNKNOWN) {
            return sql;
        }
        return "TRY_CAST(" + sql + " AS DOUBLE)";
    }

    /** Calendar-date comparison; unparseable text becomes NULL for that row. */
    public static String asDate(String sql, SqlTypeFamily type) {
        if (type == SqlTypeFamily.TEMPORAL || type == SqlTypeFamily.UNKNOWN) {
            return sql;
        }
        return "TRY_CAST(" + sql + " AS DATE)";
    }

    /**
     * Presto type a bound string must be cast to before it can be compared
     * with a temporal column. JDBC sends the officer's text as varchar, and
     * Presto rejects {@code date = varchar} outright. The column stays a
     * date; {@code CAST} (not {@code TRY_CAST}) still fails the query when
     * the text is not a date.
     */
    /**
     * Cast a bound placeholder to the column's family. Used when the filter
     * value's JDBC type is not already that family.
     */
    public static String boundValueCast(String dataType, SqlTypeFamily columnFamily) {
        return switch (columnFamily) {
            case TEMPORAL -> "CAST(? AS " + temporalSqlType(dataType) + ")";
            case NUMBER -> "CAST(? AS DOUBLE)";
            case BOOLEAN -> "CAST(? AS BOOLEAN)";
            default -> "?";
        };
    }

    /** True when a filter value must be cast to the column type before comparison. */
    public static boolean shouldCastBoundValue(SqlTypeFamily columnFamily, SqlTypeFamily boundFamily) {
        if (boundFamily == SqlTypeFamily.UNKNOWN || boundFamily == columnFamily) {
            return false;
        }
        return columnFamily == SqlTypeFamily.TEMPORAL
                || columnFamily == SqlTypeFamily.NUMBER
                || columnFamily == SqlTypeFamily.BOOLEAN;
    }

    public static String temporalSqlType(String dataType) {
        if (dataType == null) {
            return "DATE";
        }
        String lower = dataType.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("timestamp") && lower.contains("with time zone")) {
            return "TIMESTAMP WITH TIME ZONE";
        }
        if (lower.startsWith("timestamp") || lower.startsWith("datetime")) {
            return "TIMESTAMP";
        }
        if (lower.startsWith("time")) {
            return "TIME";
        }
        return "DATE";
    }

    /**
     * Aligns a column against a BOUND VALUE of a known logical type — the Rule
     * Engine's case, where the officer's value arrives already typed by the
     * field catalogue and only the column can move.
     *
     * <p>No {@link CompareAs} override here, deliberately. That setting decides
     * which of two COLUMNS gives way; against a bound value there is nothing to
     * decide — the parameter is sent over JDBC as a Java number or a string,
     * and the column has to meet it. Honouring a TEXT override against a
     * numeric value would emit {@code CAST(col AS VARCHAR) < 48000} and turn a
     * working query into a type error. The per-column knob for this path is the
     * field's declared data type in the catalogue.
     *
     * <p>Only the text↔number axis is coerced. A boolean or temporal field is
     * left exactly as it is: {@code TRY_CAST} to boolean would quietly null out
     * the {@code 'Y'}/{@code 'N'} flags this data is full of, and a date's text
     * format is anybody's guess — both are worth failing loudly on rather than
     * answering wrongly.
     *
     * @return the column reference, cast only if it disagrees with the value
     */
    public static String alignColumnToValue(String columnSql, SqlTypeFamily columnType,
                                            SqlTypeFamily valueType) {
        if (columnType == SqlTypeFamily.UNKNOWN || columnType == valueType) {
            return columnSql;
        }
        return switch (valueType) {
            case NUMBER -> asNumber(columnSql, columnType);
            case TEXT -> asText(columnSql, columnType);
            default -> columnSql;
        };
    }
}
