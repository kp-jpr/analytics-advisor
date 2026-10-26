"use client";

import { useShell } from "@/components/shell/ShellProviders";
import type { I18nKey } from "@/lib/i18n/catalog";
import {
  RuleColumnValuePicker,
  ruleSelectionFromInValues,
} from "@/components/query-builder/RuleColumnValuePicker";
import type { RegisteredColumn, RuleOperator, TableRef } from "@/lib/analysisApi";
import { isCascadeComplete, type CascadeValue } from "@/components/LakehouseCascade";
import type { ExtractRuleRow } from "@/lib/queryBuilderPipelineTypes";

const BASIC_RULE_OPS: { value: RuleOperator; labelKey: I18nKey; needsValue: boolean }[] = [
  { value: "EQ", labelKey: "opEq", needsValue: true },
  { value: "NE", labelKey: "opNe", needsValue: true },
  { value: "LT", labelKey: "opLt", needsValue: true },
  { value: "LTE", labelKey: "opLte", needsValue: true },
  { value: "GT", labelKey: "opGt", needsValue: true },
  { value: "GTE", labelKey: "opGte", needsValue: true },
  { value: "IS_NULL", labelKey: "opIsNull", needsValue: false },
  { value: "NOT_NULL", labelKey: "opNotNull", needsValue: false },
];

function fillCatalog(template: string, values: Record<string, string | number>): string {
  return template.replaceAll(/\{(\w+)\}/g, (_match, name: string) => {
    const value = values[name];
    return value === undefined ? "" : String(value);
  });
}

export function BasicRuleListEditor({
  side,
  tableRef,
  columns,
  rows,
  onChange,
  idPrefix,
  sideLabel: sideLabelOverride,
}: Readonly<{
  side: "source" | "target";
  tableRef: CascadeValue;
  columns: RegisteredColumn[];
  rows: ExtractRuleRow[];
  onChange: (next: ExtractRuleRow[]) => void;
  /** Keeps control ids unique when more than one list uses the same side. */
  idPrefix?: string;
  /** Spoken name for this list. Extra tables pass their own table label. */
  sideLabel?: string;
}>) {
  const { t } = useShell();
  const sideLabel = sideLabelOverride ?? (side === "source" ? t("basicRuleSideSource") : t("basicRuleSideTarget"));
  const controlPrefix = idPrefix ?? side;
  const canAddRule = columns.length > 0;

  function addRule() {
    onChange([
      ...rows,
      {
        id: crypto.randomUUID(),
        column: columns[0]?.name ?? "",
        operator: "EQ",
        value: "",
      },
    ]);
  }

  function updateColumn(rowId: string, column: string) {
    onChange(
      rows.map((row) =>
        row.id === rowId ? { ...row, column, value: "", inValues: undefined } : row,
      ),
    );
  }

  function updateOperator(rowId: string, next: string) {
    const op = BASIC_RULE_OPS.find((item) => item.value === next);
    if (!op) return;
    onChange(
      rows.map((row) =>
        row.id === rowId
          ? { ...row, operator: op.value, value: op.needsValue ? row.value : "" }
          : row,
      ),
    );
  }

  function updateValue(rowId: string, value: string) {
    onChange(rows.map((row) => (row.id === rowId ? { ...row, value, inValues: undefined } : row)));
  }

  function updateInValues(rowId: string, keys: string[]) {
    onChange(
      rows.map((row) =>
        row.id === rowId
          ? {
              ...row,
              inValues: keys.map((k) => (k === "__NULL__" ? null : k)),
              value: keys.length === 1 && keys[0] !== "__NULL__" ? keys[0] : "",
            }
          : row,
      ),
    );
  }

  const qualifiedTable: TableRef | null = isCascadeComplete(tableRef)
    ? (tableRef as TableRef)
    : null;

  function removeRule(rowId: string) {
    onChange(rows.filter((row) => row.id !== rowId));
  }

  return (
    <div className="basic-rule-list" role="group" aria-label={fillCatalog(t("ariaBasicRuleList"), { side: sideLabel })}>
      {rows.length === 0 ? (
        <div className="empty basic-rule-list-empty">{t("msgNoRule")}</div>
      ) : (
        rows.map((row, index) => {
          const n = index + 1;
          const needsValue = BASIC_RULE_OPS.find((item) => item.value === row.operator)?.needsValue ?? true;
          const columnId = `basic-rule-${controlPrefix}-${row.id}-column`;
          const operatorId = `basic-rule-${controlPrefix}-${row.id}-operator`;
          const valueId = `basic-rule-${controlPrefix}-${row.id}-value`;
          return (
            <div
              key={row.id}
              className={needsValue ? "basic-rule-row" : "basic-rule-row basic-rule-row-nullary"}
            >
              <div className="field">
                <label htmlFor={columnId}>{t("lblColumn")}</label>
                <select
                  id={columnId}
                  aria-label={fillCatalog(t("ariaBasicRuleColumn"), { side: sideLabel, n })}
                  value={row.column}
                  onChange={(event) => updateColumn(row.id, event.target.value)}
                >
                  <option value="">{t("selNone")}</option>
                  {columns.map((column) => (
                    <option key={column.name} value={column.name}>
                      {column.businessName ?? column.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor={operatorId}>{t("lblOperator")}</label>
                <select
                  id={operatorId}
                  aria-label={fillCatalog(t("ariaBasicRuleOperator"), { side: sideLabel, n })}
                  value={row.operator}
                  onChange={(event) => updateOperator(row.id, event.target.value)}
                >
                  {BASIC_RULE_OPS.map((op) => (
                    <option key={op.value} value={op.value}>
                      {t(op.labelKey)}
                    </option>
                  ))}
                </select>
              </div>
              {needsValue ? (
                <div className="field basic-rule-value-field">
                  <label htmlFor={valueId}>{t("lblValue")}</label>
                  {qualifiedTable && row.column && (row.operator === "EQ" || row.operator === "NE")
                    && columns.find((c) => c.name === row.column)?.valuePickerEnabled ? (
                    <RuleColumnValuePicker
                      table={qualifiedTable}
                      column={row.column}
                      selectedValues={ruleSelectionFromInValues(row.inValues)}
                      onChange={(keys) => updateInValues(row.id, keys)}
                    />
                  ) : null}
                  <input
                    id={valueId}
                    type="text"
                    autoComplete="off"
                    inputMode="text"
                    aria-label={fillCatalog(t("ariaBasicRuleValue"), { side: sideLabel, n })}
                    placeholder={
                      qualifiedTable && row.column && (row.operator === "EQ" || row.operator === "NE")
                        && columns.find((c) => c.name === row.column)?.valuePickerEnabled
                        ? "Or type a value"
                        : undefined
                    }
                    value={row.value}
                    onChange={(event) => updateValue(row.id, event.target.value)}
                  />
                </div>
              ) : null}
              <button
                type="button"
                className="btn danger sm basic-rule-remove"
                aria-label={fillCatalog(t("ariaBasicRuleRemove"), { side: sideLabel, n })}
                onClick={() => removeRule(row.id)}
              >
                {t("btnRemove")}
              </button>
            </div>
          );
        })
      )}
      <button
        type="button"
        className="btn secondary sm basic-rule-list-add"
        aria-label={fillCatalog(t("ariaBasicRuleAdd"), { side: sideLabel })}
        disabled={!canAddRule}
        title={!canAddRule ? t("msgChooseTableBeforeRule") : undefined}
        onClick={addRule}
      >
        {t("btnAddRule")}
      </button>
      {!canAddRule ? <p className="text-muted basic-rule-list-hint">{t("msgChooseTableBeforeRule")}</p> : null}
    </div>
  );
}
