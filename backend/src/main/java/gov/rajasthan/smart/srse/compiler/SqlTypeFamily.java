package gov.rajasthan.smart.srse.compiler;

import java.util.Locale;
import java.util.Set;

/**
 * The comparison family of a physical lakehouse column type.
 *
 * <p>Presto will not implicitly coerce ACROSS these families: comparing a
 * {@code varchar} account number against a {@code bigint} one fails outright
 * with {@code '=' cannot be applied to varchar, bigint}, and a text function
 * such as {@code lower()} on a numeric column fails with
 * {@code Unexpected parameters}. WITHIN a family it coerces fine — integer vs
 * bigint vs decimal, varchar(20) vs varchar(50), date vs timestamp — so those
 * pairs must be left alone rather than wrapped in a cast that would only
 * change their semantics (see {@link TypeCoercion}).
 *
 * <p>Types are the strings {@code information_schema.columns.data_type}
 * reports, so they arrive parameterised: {@code varchar(50)},
 * {@code decimal(10,2)}, {@code timestamp(3) with time zone}. Only the leading
 * type word decides the family.
 */
public enum SqlTypeFamily {

    TEXT,
    NUMBER,
    BOOLEAN,
    TEMPORAL,

    /**
     * Anything else — {@code varbinary}, {@code json}, {@code uuid},
     * {@code array}/{@code map}/{@code row}, or a type SRSE simply has not
     * seen. Also what an unknown or unreadable type degrades to.
     *
     * <p>Treated as "do not touch": a cast SRSE cannot reason about is more
     * likely to turn a working query into a failing one than to fix anything,
     * so an UNKNOWN operand is emitted exactly as it is today.
     */
    UNKNOWN;

    private static final Set<String> TEXT_TYPES = Set.of("varchar", "char", "character", "string");
    private static final Set<String> NUMBER_TYPES = Set.of(
            "bigint", "integer", "int", "smallint", "tinyint",
            "double", "real", "float", "decimal", "numeric");
    private static final Set<String> TEMPORAL_TYPES = Set.of("date", "timestamp", "time", "datetime");

    /**
     * Family JDBC will actually send. A numeric-looking string is still text:
     * the driver binds it as varchar, and a date or number column cannot be
     * compared with that until it is cast.
     */
    public static SqlTypeFamily ofBound(Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        if (value instanceof Number) {
            return NUMBER;
        }
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        return TEXT;
    }

    /** Logical family of an officer-supplied bound value (Rule Engine path). */
    public static SqlTypeFamily ofValue(Object value) {
        if (value == null) {
            return TEXT;
        }
        if (value instanceof Number) {
            return NUMBER;
        }
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return TEXT;
            }
            try {
                Double.parseDouble(trimmed);
                return NUMBER;
            } catch (NumberFormatException ex) {
                return TEXT;
            }
        }
        return TEXT;
    }

    /** Family of a raw {@code information_schema} type string; never null. */
    public static SqlTypeFamily of(String dataType) {
        String base = baseTypeName(dataType);
        if (TEXT_TYPES.contains(base)) {
            return TEXT;
        }
        if (NUMBER_TYPES.contains(base)) {
            return NUMBER;
        }
        if (TEMPORAL_TYPES.contains(base)) {
            return TEMPORAL;
        }
        if ("boolean".equals(base)) {
            return BOOLEAN;
        }
        return UNKNOWN;
    }

    /**
     * The leading type word: everything before the first parenthesis or space.
     * {@code varchar(50)} → {@code varchar}, {@code decimal(10,2)} →
     * {@code decimal}, {@code timestamp(3) with time zone} → {@code timestamp},
     * {@code double precision} → {@code double}.
     */
    private static String baseTypeName(String dataType) {
        if (dataType == null) {
            return "";
        }
        String trimmed = dataType.trim().toLowerCase(Locale.ROOT);
        int end = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(' || c == ' ') {
                end = i;
                break;
            }
        }
        return trimmed.substring(0, end);
    }
}
