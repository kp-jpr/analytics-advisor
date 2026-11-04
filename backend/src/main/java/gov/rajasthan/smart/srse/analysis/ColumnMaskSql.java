package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.metadata.ColumnMaskPolicy;

/**
 * Rewrites a physical column reference for the match SELECT list only.
 * JOIN ON / WHERE / fuzzy predicates must keep using the bare column.
 */
public final class ColumnMaskSql {

    private ColumnMaskSql() {
    }

    /**
     * @param qualifiedRef SQL fragment such as {@code src.aadhaar} (already validated identifiers)
     */
    public static String maskExpression(String qualifiedRef, ColumnMaskPolicy policy, int keepLast) {
        ColumnMaskPolicy effective = ColumnMaskPolicy.orNone(policy);
        if (effective == ColumnMaskPolicy.NONE) {
            return qualifiedRef;
        }
        String asVarchar = "CAST(" + qualifiedRef + " AS VARCHAR)";
        return switch (effective) {
            case FULL -> "CASE WHEN " + qualifiedRef + " IS NULL THEN NULL ELSE '****' END";
            case PARTIAL -> {
                int n = keepLast < 1 ? 4 : Math.min(keepLast, 64);
                yield "CASE WHEN " + qualifiedRef + " IS NULL THEN NULL ELSE "
                        + "CONCAT('****', SUBSTR(" + asVarchar + ", GREATEST(LENGTH(" + asVarchar + ") - "
                        + n + " + 1, 1))) END";
            }
            case HASH -> "CASE WHEN " + qualifiedRef + " IS NULL THEN NULL ELSE "
                    + "to_hex(sha256(to_utf8(" + asVarchar + "))) END";
            case NONE -> qualifiedRef;
        };
    }
}
