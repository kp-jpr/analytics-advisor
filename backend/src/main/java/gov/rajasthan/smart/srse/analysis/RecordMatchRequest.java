package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import gov.rajasthan.smart.srse.compiler.Ast;

import java.util.List;

/**
 * Cross-table fuzzy/exact record-match request from the Analysis tab.
 *
 * {@code sourceCriteria}/{@code targetCriteria} are 1-N (table, column)
 * picks each ("Add more" in the UI), same size on both sides, AND-combined in
 * the JOIN ON clause; every criterion on a given side must share that side's
 * table (see {@link MatchCriterion}). Each source criterion carries its own
 * {@code fuzzyThresholdPercent}, applied when either column in that pair's
 * name contains "name" (case-insensitive) — other pairs compare exactly.
 *
 * <p>{@code sourceDisplayColumns} / {@code targetDisplayColumns} are optional
 * projections from the same side's table only — included in SELECT, not in ON,
 * match score, or dedup partition. Null or empty lists preserve legacy
 * behaviour (every compared column is also projected).
 *
 * <p>{@code joinGroups}, when present, REPLACES the positional pairing: each
 * group compares 1..N source columns against 1..M target columns, so the two
 * sides no longer have to be the same size (see {@link MatchGroup}). When it
 * is absent the criteria lists are zipped into single-column groups, which
 * emit exactly the SQL they emitted before groups existed.
 *
 * <p>{@code comparisonGroups} are post-join value comparisons — projected in
 * SELECT only, never in ON (see {@link ComparisonGroup}).
 *
 * <p>{@code dedup} is optional. Unknown JSON properties on the wire are ignored.
 *
 * <p>{@code additionalJoins} are further tables inner-joined to the source of
 * this same query. Null or empty leaves the two-table SQL unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordMatchRequest(
        List<MatchCriterion> sourceCriteria,
        List<MatchCriterion> targetCriteria,
        List<DisplayColumn> sourceDisplayColumns,
        List<DisplayColumn> targetDisplayColumns,
        List<MatchGroup> joinGroups,
        boolean highlightDuplicates,
        DedupSpec dedup,
        JoinType joinType,
        List<ComparisonGroup> comparisonGroups,
        boolean mismatchOnly,
        Ast.PredicateSpec sourceRules,
        Ast.PredicateSpec targetRules,
        boolean singleSource,
        List<DisplayColumn> groupByColumns,
        List<AggregateSpec> aggregates,
        Integer resultLimit,
        Boolean countOnly,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AdditionalJoin> additionalJoins) {

    public RecordMatchRequest {
        sourceCriteria = sourceCriteria == null ? List.of() : sourceCriteria;
        targetCriteria = targetCriteria == null ? List.of() : targetCriteria;
        sourceDisplayColumns = sourceDisplayColumns == null ? List.of() : sourceDisplayColumns;
        targetDisplayColumns = targetDisplayColumns == null ? List.of() : targetDisplayColumns;
        joinGroups = joinGroups == null ? List.of() : joinGroups;
        comparisonGroups = comparisonGroups == null ? List.of() : List.copyOf(comparisonGroups);
        groupByColumns = groupByColumns == null ? List.of() : groupByColumns;
        aggregates = aggregates == null ? List.of() : aggregates;
        countOnly = Boolean.TRUE.equals(countOnly);
        additionalJoins = additionalJoins == null ? List.of() : List.copyOf(additionalJoins);
    }

    /** Grouped result when either grouping keys or aggregates are present (§5.5). */
    public boolean grouped() {
        return !groupByColumns.isEmpty() || !aggregates.isEmpty();
    }

    /**
     * The same match with no extract row cap and no count-only wrapper.
     * Screen preview and count-only stay on the run request; export and charts use this shape.
     */
    public RecordMatchRequest forFullResult() {
        if (resultLimit == null && !Boolean.TRUE.equals(countOnly)) {
            return this;
        }
        return new RecordMatchRequest(
                sourceCriteria,
                targetCriteria,
                sourceDisplayColumns,
                targetDisplayColumns,
                joinGroups,
                highlightDuplicates,
                dedup,
                joinType,
                comparisonGroups,
                mismatchOnly,
                sourceRules,
                targetRules,
                singleSource,
                groupByColumns,
                aggregates,
                null,
                false,
                additionalJoins);
    }

    /**
     * The pre-groups shape: two criteria lists paired positionally. Equivalent
     * to passing no groups at all, which is what makes the SQL identical.
     */
    public RecordMatchRequest(List<MatchCriterion> sourceCriteria,
                              List<MatchCriterion> targetCriteria,
                              List<DisplayColumn> sourceDisplayColumns,
                              List<DisplayColumn> targetDisplayColumns,
                              boolean highlightDuplicates,
                              DedupSpec dedup) {
        this(sourceCriteria, targetCriteria, sourceDisplayColumns, targetDisplayColumns,
                List.of(), highlightDuplicates, dedup, null, List.of(), false, null, null, false,
                List.of(), List.of(), null, false, List.of());
    }

    /** Pre–per-target join types: multi-target sub-matches omitted {@code joinType} (INNER). */
    public RecordMatchRequest(List<MatchCriterion> sourceCriteria,
                              List<MatchCriterion> targetCriteria,
                              List<DisplayColumn> sourceDisplayColumns,
                              List<DisplayColumn> targetDisplayColumns,
                              List<MatchGroup> joinGroups,
                              boolean highlightDuplicates,
                              DedupSpec dedup) {
        this(sourceCriteria, targetCriteria, sourceDisplayColumns, targetDisplayColumns,
                joinGroups, highlightDuplicates, dedup, null, List.of(), false, null, null, false,
                List.of(), List.of(), null, false, List.of());
    }

    public RecordMatchRequest(List<MatchCriterion> sourceCriteria,
                              List<MatchCriterion> targetCriteria,
                              List<DisplayColumn> sourceDisplayColumns,
                              List<DisplayColumn> targetDisplayColumns,
                              List<MatchGroup> joinGroups,
                              boolean highlightDuplicates,
                              DedupSpec dedup,
                              JoinType joinType) {
        this(sourceCriteria, targetCriteria, sourceDisplayColumns, targetDisplayColumns,
                joinGroups, highlightDuplicates, dedup, joinType, List.of(), false, null, null, false,
                List.of(), List.of(), null, false, List.of());
    }

    /** Multi-target sub-match (comparisons + mismatch flag; no extract rules). */
    public RecordMatchRequest(List<MatchCriterion> sourceCriteria,
                              List<MatchCriterion> targetCriteria,
                              List<DisplayColumn> sourceDisplayColumns,
                              List<DisplayColumn> targetDisplayColumns,
                              List<MatchGroup> joinGroups,
                              boolean highlightDuplicates,
                              DedupSpec dedup,
                              JoinType joinType,
                              List<ComparisonGroup> comparisonGroups,
                              boolean mismatchOnly) {
        this(sourceCriteria, targetCriteria, sourceDisplayColumns, targetDisplayColumns,
                joinGroups, highlightDuplicates, dedup, joinType, comparisonGroups, mismatchOnly,
                null, null, false, List.of(), List.of(), null, false, List.of());
    }
}
