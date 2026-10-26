import { describe, expect, it } from "vitest";
import type { RuleOperator } from "@/lib/analysisApi";
import {
  buildExtractRequest,
  buildMergedRequest,
  clearDisplayColumnSelection,
  preserveExtractDisplayColumnsOnSave,
  retainValidDisplayColumns,
  selectAllDisplayColumns,
  toggleDisplayColumnSelection,
  withoutCountOnly,
} from "@/lib/queryBuilderRequestBuild";
import {
  EMPTY_EXTRACT_CONFIG,
  EMPTY_REPORT_CONFIG,
  type ExtractConfig,
  type ReportConfig,
} from "@/lib/queryBuilderPipelineTypes";
import { hydrateExtractConfigFromRequest } from "@/lib/savedQueryHydrate";

const SOURCE = { catalog: "iceberg", schema: "srse", table: "beneficiary" };
const TARGET = { catalog: "iceberg_silver", schema: "silver_txn", table: "tbl_txn_bankdtl" };

const VALUE_OPS: RuleOperator[] = ["EQ", "NE", "LT", "LTE", "GT", "GTE"];

function extract(overrides: Partial<ExtractConfig> = {}): ExtractConfig {
  return {
    ...EMPTY_EXTRACT_CONFIG,
    sourceRef: SOURCE,
    targetRef: TARGET,
    displayCols: ["district", "full_name"],
    joinKeySource: "m_id",
    joinKeyTarget: "m_id",
    ...overrides,
  };
}

function report(overrides: Partial<ReportConfig> = {}): ReportConfig {
  return { ...EMPTY_REPORT_CONFIG, ...overrides };
}

/** State after fuzzy source-rule mode selected district and a name. */
function afterFuzzyOn(overrides: Partial<ExtractConfig> = {}): ExtractConfig {
  return extract({
    ruleColumn: "district",
    ruleOp: "GT",
    ruleValue: "",
    fuzzyRuleEnabled: true,
    fuzzyRuleName: "Geeta",
    fuzzyRuleThreshold: 80,
    ...overrides,
  });
}

describe("attribute selection helpers", () => {
  const columns = ["district", "full_name", "age_years"];

  it("toggles, selects all, and clears without touching the other side", () => {
    expect(toggleDisplayColumnSelection(["district"], "full_name")).toEqual(["district", "full_name"]);
    expect(toggleDisplayColumnSelection(["district", "full_name"], "district")).toEqual(["full_name"]);
    expect(selectAllDisplayColumns(columns)).toEqual(columns);
    expect(clearDisplayColumnSelection()).toEqual([]);
  });

  it("drops selections that are not columns of the table now on that side", () => {
    expect(retainValidDisplayColumns(["district", "ifsc", "full_name"], ["district", "full_name"])).toEqual([
      "district",
      "full_name",
    ]);
    expect(retainValidDisplayColumns(["ifsc"], ["district"])).toEqual([]);
  });
});

describe("ordinary source rules", () => {
  it("emits a fuzzy predicate while fuzzy mode is on, then none after it is turned off", () => {
    const fuzzyOn = afterFuzzyOn();
    const whileOn = buildExtractRequest(false, fuzzyOn)!;
    expect(whileOn.sourceRules?.root).toMatchObject({
      operator: "FUZZY_MATCH",
      column: { column: "district" },
      value: ["Geeta", 80, { ignoreSpaces: false, caseSensitive: false }],
    });

    const fuzzyOff = { ...fuzzyOn, fuzzyRuleEnabled: false };
    for (const dualMode of [false, true]) {
      const req = buildExtractRequest(dualMode, fuzzyOff)!;
      const merged = buildMergedRequest(dualMode, fuzzyOff, EMPTY_REPORT_CONFIG)!;
      expect(req.sourceRules).toBeUndefined();
      expect(merged.sourceRules).toBeUndefined();
    }
  });

  it.each(["", "   "])("omits a value-requiring rule when the value is blank (%j)", (ruleValue) => {
    for (const ruleOp of VALUE_OPS) {
      const single = buildExtractRequest(false, afterFuzzyOn({ fuzzyRuleEnabled: false, ruleOp, ruleValue }))!;
      const dual = buildExtractRequest(true, afterFuzzyOn({ fuzzyRuleEnabled: false, ruleOp, ruleValue }))!;
      expect(single.sourceRules).toBeUndefined();
      expect(dual.sourceRules).toBeUndefined();
    }
  });

  it("still emits a comparison once the ordinary value is filled in", () => {
    const req = buildExtractRequest(
      false,
      afterFuzzyOn({ fuzzyRuleEnabled: false, ruleOp: "GT", ruleValue: "10" }),
    )!;
    expect(req.sourceRules?.root).toMatchObject({
      operator: "GT",
      column: { column: "district" },
      value: 10,
    });
  });

  it("joins a third table into the same request and round-trips it", () => {
    const village = { catalog: "iceberg", schema: "srse", table: "village" };
    const configured = extract({
      additionalTables: [
        {
          id: "extra-1",
          ref: village,
          sourceColumn: "m_id",
          joinColumn: "m_id",
          displayCols: ["village_name"],
          joinType: "INNER",
          ruleRows: [],
        },
      ],
    });
    const req = buildExtractRequest(true, configured)!;
    expect(req.additionalJoins).toEqual([
      {
        ...village,
        sourceColumn: "m_id",
        joinColumn: "m_id",
        displayColumns: ["village_name"],
      },
    ]);
    expect(buildExtractRequest(false, configured)!.additionalJoins).toBeUndefined();
    const restored = hydrateExtractConfigFromRequest(req);
    expect(buildExtractRequest(true, restored)!.additionalJoins).toEqual(req.additionalJoins);
    expect(
      buildExtractRequest(
        true,
        extract({
          additionalTables: [
            {
              id: "extra-1",
              ref: village,
              sourceColumn: "m_id",
              joinColumn: "",
              displayCols: [],
              joinType: "INNER",
              ruleRows: [],
            },
          ],
        }),
      ),
    ).toBeNull();
  });

  it("round-trips an extra table join type and one rule, and omits an inner join type", () => {
    const village = { catalog: "iceberg", schema: "srse", table: "village" };
    const configured = extract({
      additionalTables: [
        {
          id: "extra-1",
          ref: village,
          sourceColumn: "m_id",
          joinColumn: "m_id",
          displayCols: ["village_name"],
          joinType: "LEFT",
          ruleRows: [{ id: "r1", column: "village_name", operator: "EQ", value: "Jaipur" }],
        },
      ],
    });
    const req = buildExtractRequest(true, configured)!;
    expect(req.additionalJoins?.[0]?.joinType).toBe("LEFT");
    expect(req.additionalJoins?.[0]?.rules?.root).toMatchObject({
      type: "PREDICATE",
      operator: "EQ",
      value: "Jaipur",
      column: { column: "village_name", table: village },
    });
    const restored = hydrateExtractConfigFromRequest(req);
    expect(restored.additionalTables[0]?.joinType).toBe("LEFT");
    expect(restored.additionalTables[0]?.ruleRows).toEqual([
      expect.objectContaining({ column: "village_name", operator: "EQ", value: "Jaipur" }),
    ]);
    expect(buildExtractRequest(true, restored)!.additionalJoins).toEqual(req.additionalJoins);

    const inner = buildExtractRequest(true, extract({
      additionalTables: [
        {
          id: "extra-1",
          ref: village,
          sourceColumn: "m_id",
          joinColumn: "m_id",
          displayCols: ["village_name"],
          joinType: "INNER",
          ruleRows: [],
        },
      ],
    }))!;
    expect(inner.additionalJoins?.[0]).not.toHaveProperty("joinType");
    expect(inner.additionalJoins?.[0]).not.toHaveProperty("rules");
  });

  it("maps destination attributes separately and still requires a join key", () => {
    const both = buildExtractRequest(true, extract({ targetDisplayCols: ["ifsc", "branch"] }))!;
    expect(both.sourceDisplayColumns).toEqual([
      { ...SOURCE, column: "district" },
      { ...SOURCE, column: "full_name" },
    ]);
    expect(both.targetDisplayColumns).toEqual([
      { ...TARGET, column: "ifsc" },
      { ...TARGET, column: "branch" },
    ]);

    const targetOnly = buildExtractRequest(
      true,
      extract({ displayCols: [], targetDisplayCols: ["ifsc"] }),
    )!;
    expect(targetOnly.sourceDisplayColumns).toEqual([]);
    expect(targetOnly.targetDisplayColumns).toEqual([{ ...TARGET, column: "ifsc" }]);

    expect(buildExtractRequest(true, extract({ displayCols: [], targetDisplayCols: [] }))).toBeNull();
    expect(
      buildExtractRequest(true, extract({ joinKeySource: "", targetDisplayCols: ["ifsc"] })),
    ).toBeNull();
    expect(
      buildExtractRequest(true, extract({ joinKeyTarget: "", targetDisplayCols: ["ifsc"] })),
    ).toBeNull();
    expect(
      buildExtractRequest(
        true,
        extract({ targetRef: { catalog: "", schema: "", table: "" }, targetDisplayCols: ["ifsc"] }),
      ),
    ).toBeNull();
  });

  it("keeps single-source execution on source attributes only", () => {
    expect(buildExtractRequest(false, extract({ displayCols: [], targetDisplayCols: ["ifsc"] }))).toBeNull();
    const single = buildExtractRequest(false, extract({ targetDisplayCols: ["ifsc"] }))!;
    expect(single.singleSource).toBe(true);
    expect(single.sourceDisplayColumns?.map((column) => column.column)).toEqual(["district", "full_name"]);
    expect(single.targetDisplayColumns).toBeUndefined();
  });

  it("sets countOnly on a report apply and strips it for export", () => {
    const counted = buildMergedRequest(true, extract(), report({ countOnly: true }))!;
    expect(counted.countOnly).toBe(true);
    const exported = withoutCountOnly(counted);
    expect(exported.countOnly).toBeUndefined();
  });

  it("does not send countOnly when group-by is on", () => {
    const grouped = buildMergedRequest(
      true,
      extract(),
      report({ countOnly: true, groupEnabled: true, groupByCol: "district" }),
    )!;
    expect(grouped.countOnly).toBeUndefined();
    expect(grouped.groupByColumns?.[0]?.column).toBe("district");
  });

  it("restores both sides when grouping cleared them on the wire", () => {
    const configured = extract({ targetDisplayCols: ["ifsc"] });
    const grouped = buildMergedRequest(true, configured, {
      ...EMPTY_REPORT_CONFIG,
      groupEnabled: true,
      groupByCol: "district",
    })!;
    expect(grouped.sourceDisplayColumns).toEqual([]);
    expect(grouped.targetDisplayColumns).toBeUndefined();
    const saved = preserveExtractDisplayColumnsOnSave(configured, grouped);
    expect(saved.sourceDisplayColumns).toEqual([
      { ...SOURCE, column: "district" },
      { ...SOURCE, column: "full_name" },
    ]);
    expect(saved.targetDisplayColumns).toEqual([{ ...TARGET, column: "ifsc" }]);
  });

  it("keeps a destination-only dual selection on the saved request after grouping clears the wire", () => {
    const configured = extract({ displayCols: [], targetDisplayCols: ["ifsc", "branch"] });
    const grouped = buildMergedRequest(true, configured, {
      ...EMPTY_REPORT_CONFIG,
      groupEnabled: true,
      groupByCol: "district",
    })!;
    expect(grouped.sourceDisplayColumns).toEqual([]);
    expect(grouped.targetDisplayColumns).toBeUndefined();
    const saved = preserveExtractDisplayColumnsOnSave(configured, grouped);
    expect(saved.sourceDisplayColumns).toEqual([]);
    expect(saved.targetDisplayColumns).toEqual([
      { ...TARGET, column: "ifsc" },
      { ...TARGET, column: "branch" },
    ]);
    expect(saved.singleSource).toBe(false);
  });

  it("compiles two source rules as an AND group on the source table", () => {
    const configured = afterFuzzyOn({
      useValuePicker: true,
      valuePickerSpec: {
        root: {
          type: "PREDICATE",
          column: { table: SOURCE, column: "district" },
          operator: "IN",
          value: ["legacy-picker"],
        },
      },
      sourceRuleRows: [
        { id: "district", column: "district", operator: "EQ", value: "Jaipur" },
        { id: "age", column: "age_years", operator: "GT", value: "18" },
      ],
    });
    const expected = {
      type: "GROUP",
      op: "AND",
      children: [
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "district" },
          operator: "EQ",
          value: "Jaipur",
        },
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "age_years" },
          operator: "GT",
          value: 18,
        },
      ],
    };
    for (const dualMode of [false, true]) {
      expect(buildExtractRequest(dualMode, configured)!.sourceRules?.root).toEqual(expected);
      expect(buildMergedRequest(dualMode, configured, EMPTY_REPORT_CONFIG)!.sourceRules?.root).toEqual(expected);
    }
  });

  it("compiles two target rules as an AND group on the destination table", () => {
    const configured = extract({
      targetRuleColumn: "ifsc",
      targetRuleOp: "EQ",
      targetRuleValue: "SBIN",
      targetRuleRows: [
        { id: "ifsc", column: "ifsc", operator: "EQ", value: "SBIN0001" },
        { id: "branch", column: "branch", operator: "NOT_NULL", value: "" },
      ],
    });
    const req = buildExtractRequest(true, configured)!;
    expect(req.targetRules?.root).toEqual({
      type: "GROUP",
      op: "AND",
      children: [
        {
          type: "PREDICATE",
          column: { table: TARGET, column: "ifsc" },
          operator: "EQ",
          value: "SBIN0001",
        },
        {
          type: "PREDICATE",
          column: { table: TARGET, column: "branch" },
          operator: "NOT_NULL",
        },
      ],
    });
    expect(buildMergedRequest(true, configured, EMPTY_REPORT_CONFIG)!.targetRules).toEqual(req.targetRules);
    expect(req.sourceRules).toBeUndefined();
  });

  it("drops rule rows that lack a column or a required value", () => {
    const configured = extract({
      ruleColumn: "district",
      ruleOp: "EQ",
      ruleValue: "legacy-must-not-win",
      sourceRuleRows: [
        { id: "blank-col", column: "", operator: "EQ", value: "Jaipur" },
        { id: "blank-val", column: "district", operator: "EQ", value: "   " },
        { id: "age", column: "age_years", operator: "GTE", value: "18" },
        { id: "name", column: "full_name", operator: "IS_NULL", value: "" },
      ],
      targetRuleColumn: "ifsc",
      targetRuleOp: "EQ",
      targetRuleValue: "KEEP",
      targetRuleRows: [
        { id: "no-col", column: "", operator: "NOT_NULL", value: "" },
        { id: "no-val", column: "branch", operator: "NE", value: "" },
      ],
    });
    const filtered = buildExtractRequest(true, configured)!;
    expect(filtered.sourceRules?.root).toEqual({
      type: "GROUP",
      op: "AND",
      children: [
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "age_years" },
          operator: "GTE",
          value: 18,
        },
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "full_name" },
          operator: "IS_NULL",
        },
      ],
    });
    expect(filtered.targetRules).toBeUndefined();

    const oneLeft = buildExtractRequest(
      false,
      extract({
        ruleColumn: "district",
        ruleOp: "EQ",
        ruleValue: "legacy-must-not-win",
        sourceRuleRows: [
          { id: "blank-col", column: "", operator: "EQ", value: "Jaipur" },
          { id: "ok", column: "district", operator: "EQ", value: "Jaipur" },
        ],
      }),
    )!;
    expect(oneLeft.sourceRules?.root).toEqual({
      type: "PREDICATE",
      column: { table: SOURCE, column: "district" },
      operator: "EQ",
      value: "Jaipur",
    });
  });

  it("keeps legacy, value-picker, and fuzzy rules when the row arrays are empty", () => {
    const legacy = extract({
      sourceRuleRows: [],
      targetRuleRows: [],
      ruleColumn: "age_years",
      ruleOp: "LTE",
      ruleValue: "60",
      targetRuleColumn: "ifsc",
      targetRuleOp: "IS_NULL",
      targetRuleValue: "",
    });
    const legacyReq = buildExtractRequest(true, legacy)!;
    expect(legacyReq.sourceRules?.root).toEqual({
      type: "PREDICATE",
      column: { table: SOURCE, column: "age_years" },
      operator: "LTE",
      value: 60,
    });
    expect(legacyReq.targetRules?.root).toEqual({
      type: "PREDICATE",
      column: { table: TARGET, column: "ifsc" },
      operator: "IS_NULL",
    });

    const pickerSpec = {
      root: {
        type: "PREDICATE" as const,
        column: { table: SOURCE, column: "district" },
        operator: "IN" as const,
        value: ["Jaipur"],
      },
    };
    const picker = extract({
      sourceRuleRows: [],
      targetRuleRows: [],
      useValuePicker: true,
      valuePickerSpec: pickerSpec,
      ruleColumn: "age_years",
      ruleOp: "GT",
      ruleValue: "1",
      fuzzyRuleEnabled: true,
      fuzzyRuleName: "Geeta",
    });
    expect(buildExtractRequest(false, picker)!.sourceRules).toEqual(pickerSpec);

    const fuzzy = afterFuzzyOn({ sourceRuleRows: [], targetRuleRows: [] });
    expect(buildExtractRequest(true, fuzzy)!.sourceRules?.root).toMatchObject({
      operator: "FUZZY_MATCH",
      column: { table: SOURCE, column: "district" },
      value: ["Geeta", 80, { ignoreSpaces: false, caseSensitive: false }],
    });
    expect(buildExtractRequest(true, fuzzy)!.targetRules).toBeUndefined();
  });

  it.each(["IS_NULL", "NOT_NULL"] as const)("emits %s with a blank value", (ruleOp) => {
    const configured = afterFuzzyOn({ fuzzyRuleEnabled: false, ruleOp, ruleValue: "" });
    for (const dualMode of [false, true]) {
      const req = buildExtractRequest(dualMode, configured)!;
      expect(req.sourceRules?.root).toEqual({
        type: "PREDICATE",
        column: { table: SOURCE, column: "district" },
        operator: ruleOp,
      });
    }
  });
});

describe("report-stage source filters", () => {
  const districtIn = {
    root: {
      type: "PREDICATE" as const,
      column: { table: SOURCE, column: "district" },
      operator: "IN" as const,
      value: ["Jaipur", "Kota"],
    },
  };

  it("applies a value filter alone as the source rule", () => {
    const configured = extract();
    const merged = buildMergedRequest(
      false,
      configured,
      report({
        sourceValueFilterEnabled: true,
        sourceValueFilterColumn: "district",
        sourceValueFilterSpec: districtIn,
        sourceFuzzyEnabled: false,
        sourceFuzzyColumn: "full_name",
        sourceFuzzyName: "Geeta",
      }),
    )!;
    expect(merged.sourceRules).toEqual(districtIn);
    expect(buildExtractRequest(false, configured)!.sourceRules).toBeUndefined();
  });

  it("applies a fuzzy filter alone on the extract source table", () => {
    const merged = buildMergedRequest(
      true,
      extract(),
      report({
        sourceValueFilterEnabled: false,
        sourceValueFilterSpec: districtIn,
        sourceFuzzyEnabled: true,
        sourceFuzzyColumn: " full_name ",
        sourceFuzzyName: "  Geeta Kumari  ",
        sourceFuzzyThreshold: 75,
        sourceFuzzyIgnoreSpaces: true,
        sourceFuzzyCaseSensitive: true,
      }),
    )!;
    expect(merged.sourceRules).toEqual({
      root: {
        type: "PREDICATE",
        column: { table: SOURCE, column: "full_name" },
        operator: "FUZZY_MATCH",
        value: ["Geeta Kumari", 75, { ignoreSpaces: true, caseSensitive: true }],
      },
    });
  });

  it("ANDs both report filters with two extract basic rules", () => {
    const configured = extract({
      sourceRuleRows: [
        { id: "district", column: "district", operator: "EQ", value: "Jaipur" },
        { id: "age", column: "age_years", operator: "GT", value: "18" },
      ],
    });
    const merged = buildMergedRequest(
      true,
      configured,
      report({
        sourceValueFilterEnabled: true,
        sourceValueFilterSpec: districtIn,
        sourceFuzzyEnabled: true,
        sourceFuzzyColumn: "full_name",
        sourceFuzzyName: "Geeta",
        sourceFuzzyThreshold: 80,
        sourceFuzzyIgnoreSpaces: false,
        sourceFuzzyCaseSensitive: false,
      }),
    )!;
    expect(merged.sourceRules?.root).toEqual({
      type: "GROUP",
      op: "AND",
      children: [
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "district" },
          operator: "EQ",
          value: "Jaipur",
        },
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "age_years" },
          operator: "GT",
          value: 18,
        },
        districtIn.root,
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "full_name" },
          operator: "FUZZY_MATCH",
          value: ["Geeta", 80, { ignoreSpaces: false, caseSensitive: false }],
        },
      ],
    });
    expect(buildExtractRequest(true, configured)!.sourceRules?.root).toEqual({
      type: "GROUP",
      op: "AND",
      children: [
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "district" },
          operator: "EQ",
          value: "Jaipur",
        },
        {
          type: "PREDICATE",
          column: { table: SOURCE, column: "age_years" },
          operator: "GT",
          value: 18,
        },
      ],
    });
  });

  it("leaves sourceRules unchanged when report filters are disabled or blank", () => {
    const configured = extract({
      sourceRuleRows: [{ id: "age", column: "age_years", operator: "GTE", value: "18" }],
    });
    const base = buildExtractRequest(false, configured)!;
    const disabled = buildMergedRequest(false, configured, EMPTY_REPORT_CONFIG)!;
    expect(JSON.stringify(disabled.sourceRules)).toBe(JSON.stringify(base.sourceRules));

    const blank = buildMergedRequest(
      false,
      configured,
      report({
        sourceValueFilterEnabled: true,
        sourceValueFilterSpec: null,
        sourceFuzzyEnabled: true,
        sourceFuzzyColumn: "  ",
        sourceFuzzyName: "   ",
      }),
    )!;
    expect(JSON.stringify(blank.sourceRules)).toBe(JSON.stringify(base.sourceRules));

    const populatedButOff = buildMergedRequest(
      true,
      extract(),
      report({
        sourceValueFilterEnabled: false,
        sourceValueFilterSpec: districtIn,
        sourceFuzzyEnabled: false,
        sourceFuzzyColumn: "full_name",
        sourceFuzzyName: "Geeta",
      }),
    )!;
    expect(populatedButOff.sourceRules).toBeUndefined();
    expect(buildExtractRequest(true, extract())!.sourceRules).toBeUndefined();
  });
});
