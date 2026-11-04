package gov.rajasthan.smart.srse.metadata;

/**
 * How a registered column's values are rewritten in match SELECT lists
 * (grid, export, charts). JOIN / WHERE / fuzzy predicates still use the raw column.
 */
public enum ColumnMaskPolicy {
    NONE,
    FULL,
    PARTIAL,
    HASH;

    public static ColumnMaskPolicy orNone(ColumnMaskPolicy policy) {
        return policy == null ? NONE : policy;
    }
}
