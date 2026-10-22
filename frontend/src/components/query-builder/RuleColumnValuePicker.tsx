"use client";

import { useEffect, useState } from "react";
import { fetchColumnDistinctValues, type TableRef } from "@/lib/analysisApi";

const NULL_SENTINEL = "__NULL__";

type Props = Readonly<{
  table: TableRef;
  column: string;
  selectedValues: string[];
  onChange: (values: string[]) => void;
}>;

/** Excel-style distinct value checklist for extract rules (single- or multi-select). */
export function RuleColumnValuePicker({ table, column, selectedValues, onChange }: Props) {
  const pickerKey = `${table.catalog}.${table.schema}.${table.table}|${column}`;
  const [fetchState, setFetchState] = useState<{
    key: string;
    values: (string | null)[];
    truncated: boolean;
    error: string | null;
  }>({ key: "", values: [], truncated: false, error: null });
  const [search, setSearch] = useState("");

  const loading = Boolean(column) && fetchState.key !== pickerKey;
  const values = fetchState.key === pickerKey ? fetchState.values : [];
  const truncated = fetchState.key === pickerKey ? fetchState.truncated : false;
  const error = fetchState.key === pickerKey ? fetchState.error : null;
  const selected = new Set(selectedValues);

  useEffect(() => {
    setSearch("");
  }, [pickerKey]);

  useEffect(() => {
    if (!column) {
      return undefined;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      fetchColumnDistinctValues(table, column, search.trim() || undefined, controller.signal)
        .then((res) => {
          if (controller.signal.aborted) return;
          setFetchState({
            key: pickerKey,
            values: res.values,
            truncated: res.truncated,
            error: null,
          });
        })
        .catch((err: unknown) => {
          if (controller.signal.aborted) return;
          if (err instanceof DOMException && err.name === "AbortError") return;
          setFetchState({
            key: pickerKey,
            values: [],
            truncated: false,
            error: err instanceof Error ? err.message : String(err),
          });
        });
    }, 300);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [table, column, pickerKey, search]);

  function toggle(raw: string | null) {
    const key = raw === null ? NULL_SENTINEL : String(raw);
    const next = new Set(selected);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    onChange([...next]);
  }

  if (!column) {
    return null;
  }

  const filtered = values.filter((v) => {
    if (!search.trim()) return true;
    const label = v === null ? "(null)" : String(v);
    return label.toLowerCase().includes(search.trim().toLowerCase());
  });

  return (
    <div className="rule-distinct-picker">
      {truncated ? (
        <p className="text-muted" style={{ margin: "0 0 0.35rem", fontSize: "0.85rem" }}>
          Showing a sample — search to find more values.
        </p>
      ) : null}
      <input
        type="search"
        className="srse-input"
        placeholder="Search values…"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        autoComplete="off"
        inputMode="text"
      />
      {loading ? <p className="text-muted">Loading values…</p> : null}
      {error ? <p className="text-danger">{error}</p> : null}
      {!loading && !error ? (
        <div className="rule-distinct-picker-list" role="listbox" aria-multiselectable="true">
          {filtered.map((v) => {
            const key = v === null ? NULL_SENTINEL : String(v);
            const label = v === null ? "(null)" : String(v);
            return (
              <label key={key} className="checkbox-row">
                <input
                  type="checkbox"
                  checked={selected.has(key)}
                  onChange={() => toggle(v)}
                />
                {label}
              </label>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

export function inValuesFromRuleSelection(selectedKeys: string[]): (string | number | null)[] {
  return selectedKeys.map((k) => (k === NULL_SENTINEL ? null : k));
}

export function ruleSelectionFromInValues(values: (string | number | null)[] | undefined): string[] {
  if (!values?.length) return [];
  return values.map((v) => (v === null ? NULL_SENTINEL : String(v)));
}
