"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { RegistryBrowseFilter } from "@/lib/analysisApi";

/**
 * The Catalog → Schema → Table cascade, shared by every place an admin or
 * officer picks a lakehouse table.
 *
 * Each level is fetched only once the level above it is chosen, and choosing
 * a level clears everything below it — so a stale table name from a previous
 * catalog can never survive into a submitted request. That matters more than
 * usual here: with the lakehouse's Silver and Gold layers both mapped, the
 * same table name exists under more than one catalog, so a leftover value
 * would silently point at the wrong layer rather than failing loudly.
 *
 * When registry filter fetchers are supplied (Analysis registry cascade),
 * optional Source system / Table group / Layer filters may appear in a
 * secondary block. Those values are component-internal state only — they are
 * never part of {@link CascadeValue}, so they cannot ride into match payloads
 * via spread.
 *
 * Deliberately source-agnostic — the caller supplies the fetchers. The
 * Admin page passes the live-browse endpoints (everything the Presto
 * connection can reach); the Analysis tab passes the registry endpoints (only
 * what an admin registered). Same component, two different reaches.
 */

export type CascadeValue = {
  catalog: string;
  schema: string;
  table: string;
};

export const EMPTY_CASCADE: CascadeValue = { catalog: "", schema: "", table: "" };

export function isCascadeComplete(v: CascadeValue): boolean {
  return Boolean(v.catalog && v.schema && v.table);
}

/** Wire sentinel for legacy null-tag rows — filter only, never stored. */
export const UNTAGGED_FILTER = "UNTAGGED";

/** Options worth showing in an optional label filter (excludes empty and UNTAGGED-only lists). */
export function meaningfulLabelFilterOptions(options: string[]): string[] {
  return options.filter((o) => o && o !== UNTAGGED_FILTER);
}

export function shouldShowLabelFilter(options: string[]): boolean {
  return meaningfulLabelFilterOptions(options).length > 0;
}

/** A table option; `layer` (SILVER/GOLD) is rendered as a suffix when present. */
export type TableOption = { name: string; layer?: string | null };

export type CascadeFetchers = {
  listSourceSystems?: () => Promise<string[]>;
  listTableGroups?: (sourceSystem?: string) => Promise<string[]>;
  listLayers?: () => Promise<string[]>;
  listCatalogs: (filter?: RegistryBrowseFilter) => Promise<string[]>;
  listSchemas: (catalog: string, filter?: RegistryBrowseFilter) => Promise<string[]>;
  listTables: (
    catalog: string,
    schema: string,
    filter?: RegistryBrowseFilter,
  ) => Promise<TableOption[]>;
};

type Props = Readonly<{
  value: CascadeValue;
  onChange: (value: CascadeValue) => void;
  fetchers: CascadeFetchers;
  /** Rendered above the row; omit for a bare inline cascade. */
  label?: string;
  idPrefix: string;
  disabled?: boolean;
  /** Compact drops the per-select captions — for dense repeated rows. */
  compact?: boolean;
  /** Prototype Query Builder: `.field` / `.row` layout inside `.pick` cards. */
  appearance?: "legacy" | "prototype";
  onError?: (message: string) => void;
}>;

const selectStyle = { minWidth: 150 } as const;

/** Must stay in lockstep with {@code RegistryDisplayTags.UNTAGGED} on the backend. */
function tagOptionLabel(tag: string): string {
  return tag === UNTAGGED_FILTER ? "Untagged (legacy)" : tag;
}

export default function LakehouseCascade({
  value,
  onChange,
  fetchers,
  label,
  idPrefix,
  disabled = false,
  compact = false,
  appearance = "legacy",
  onError,
}: Props) {
  const proto = appearance === "prototype";
  const [sourceSystems, setSourceSystems] = useState<string[]>([]);
  const [tableGroups, setTableGroups] = useState<string[]>([]);
  const [layers, setLayers] = useState<string[]>([]);
  const [selectedSourceSystem, setSelectedSourceSystem] = useState("");
  const [selectedTableGroup, setSelectedTableGroup] = useState("");
  const [selectedLayer, setSelectedLayer] = useState("");

  const browseFilter = useMemo((): RegistryBrowseFilter => {
    const f: RegistryBrowseFilter = {};
    if (selectedLayer) f.layer = selectedLayer;
    if (selectedSourceSystem) f.sourceSystem = selectedSourceSystem;
    if (selectedTableGroup) f.tableGroup = selectedTableGroup;
    return f;
  }, [selectedLayer, selectedSourceSystem, selectedTableGroup]);

  const browseFilterKey = useMemo(() => JSON.stringify(browseFilter), [browseFilter]);
  const [catalogFetch, setCatalogFetch] = useState<{ key: string; items: string[] }>({
    key: "",
    items: [],
  });
  const [schemaFetch, setSchemaFetch] = useState<{ key: string; items: string[] }>({
    key: "",
    items: [],
  });
  const [tableFetch, setTableFetch] = useState<{ key: string; items: TableOption[] }>({
    key: "",
    items: [],
  });
  const catalogsLoading = catalogFetch.key !== browseFilterKey;
  const catalogs = catalogsLoading ? catalogFetch.items : catalogFetch.items;
  const schemaKey = value.catalog ? `${value.catalog}|${browseFilterKey}` : "";
  const schemas =
    value.catalog && schemaFetch.key === schemaKey ? schemaFetch.items : [];
  const tableKey = value.catalog && value.schema ? `${schemaKey}|${value.schema}` : "";
  const tables =
    value.catalog && value.schema && tableFetch.key === tableKey ? tableFetch.items : [];
  const loading = catalogsLoading;

  const report = useCallback(
    (err: unknown) => onError?.(err instanceof Error ? err.message : String(err)),
    [onError],
  );

  const { listSourceSystems, listTableGroups, listLayers, listCatalogs, listSchemas, listTables } =
    fetchers;

  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const valueRef = useRef(value);
  valueRef.current = value;
  const browseFilterRef = useRef(browseFilter);
  browseFilterRef.current = browseFilter;

  useEffect(() => {
    if (!listSourceSystems) {
      return undefined;
    }
    let cancelled = false;
    listSourceSystems()
      .then((s) => {
        if (!cancelled) setSourceSystems(s);
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [listSourceSystems, report]);

  useEffect(() => {
    if (!listTableGroups) {
      return undefined;
    }
    let cancelled = false;
    const sourceArg = selectedSourceSystem || undefined;
    listTableGroups(sourceArg)
      .then((g) => {
        if (!cancelled) setTableGroups(g);
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [listTableGroups, selectedSourceSystem, report]);

  useEffect(() => {
    if (!listLayers) {
      return undefined;
    }
    let cancelled = false;
    listLayers()
      .then((l) => {
        if (!cancelled) setLayers(l);
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [listLayers, report]);

  useEffect(() => {
    let cancelled = false;
    listCatalogs(browseFilterRef.current)
      .then((c) => {
        if (cancelled) return;
        setCatalogFetch({ key: browseFilterKey, items: c });
        const current = valueRef.current;
        if (current.catalog && !c.includes(current.catalog)) {
          onChangeRef.current({ catalog: "", schema: "", table: "" });
        }
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [browseFilterKey, listCatalogs, report]);

  const sourceSystemOptions = listSourceSystems ? sourceSystems : [];
  const tableGroupOptions = listTableGroups ? tableGroups : [];
  const layerOptions = listLayers ? layers : [];

  const showSourceFilter = listSourceSystems != null && shouldShowLabelFilter(sourceSystemOptions);
  const showGroupFilter = listTableGroups != null && shouldShowLabelFilter(tableGroupOptions);
  const showLayerFilter = listLayers != null && shouldShowLabelFilter(layerOptions);

  const layerSelectOptions = useMemo(() => {
    const meaningful = meaningfulLabelFilterOptions(layerOptions);
    const opts = [...meaningful];
    if (layerOptions.includes(UNTAGGED_FILTER)) {
      opts.push(UNTAGGED_FILTER);
    }
    return opts;
  }, [layerOptions]);

  useEffect(() => {
    const catalog = valueRef.current.catalog;
    if (!catalog) {
      return undefined;
    }
    let cancelled = false;
    listSchemas(catalog, browseFilterRef.current)
      .then((s) => {
        if (cancelled) return;
        setSchemaFetch({ key: schemaKey, items: s });
        const current = valueRef.current;
        if (current.schema && !s.includes(current.schema)) {
          onChangeRef.current({ catalog: current.catalog, schema: "", table: "" });
        }
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [schemaKey, listSchemas, report]);

  useEffect(() => {
    const currentValue = valueRef.current;
    if (!currentValue.catalog || !currentValue.schema) {
      return undefined;
    }
    let cancelled = false;
    listTables(currentValue.catalog, currentValue.schema, browseFilterRef.current)
      .then((t) => {
        if (cancelled) return;
        setTableFetch({ key: tableKey, items: t });
        const current = valueRef.current;
        if (current.table && !t.some((row) => row.name === current.table)) {
          onChangeRef.current({ catalog: current.catalog, schema: current.schema, table: "" });
        }
      })
      .catch(report);
    return () => {
      cancelled = true;
    };
  }, [tableKey, listTables, report]);

  function clearCascade() {
    onChange({ catalog: "", schema: "", table: "" });
  }

  function pickSourceSystem(sourceSystem: string) {
    setSelectedSourceSystem(sourceSystem);
    setSelectedTableGroup("");
    clearCascade();
  }

  function pickTableGroup(tableGroup: string) {
    setSelectedTableGroup(tableGroup);
    clearCascade();
  }

  function pickLayer(layer: string) {
    setSelectedLayer(layer);
    clearCascade();
  }

  function pickCatalog(catalog: string) {
    onChange({ catalog, schema: "", table: "" });
  }

  function pickSchema(schema: string) {
    onChange({ ...value, schema, table: "" });
  }

  function pickTable(table: string) {
    onChange({ ...value, table });
  }

  function caption(text: string, htmlFor: string) {
    if (compact) return null;
    if (proto) {
      return <label htmlFor={htmlFor}>{text}</label>;
    }
    return (
      <label htmlFor={htmlFor} className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>
        {text}
      </label>
    );
  }

  function fieldWrap(id: string, captionText: string, child: ReactNode) {
    if (proto) {
      return (
        <div className="field">
          {caption(captionText, id)}
          {child}
        </div>
      );
    }
    return (
      <div>
        {caption(captionText, id)}
        {child}
      </div>
    );
  }

  const optionalFilters =
    showSourceFilter || showGroupFilter || showLayerFilter ? (
      <details className="cascade-optional-filters">
        <summary className={proto ? "text-muted" : "srse-text-muted"}>Optional filters</summary>
        <div
          className={proto ? "row" : undefined}
          style={
            proto
              ? { marginTop: 8, flexWrap: "wrap", gap: 8 }
              : { display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "flex-end", marginTop: "0.5rem" }
          }
        >
          {showSourceFilter &&
            fieldWrap(
              `${idPrefix}-source-system`,
              "Source system",
              <select
                id={`${idPrefix}-source-system`}
                className={proto ? undefined : "srse-select"}
                style={proto ? undefined : selectStyle}
                value={selectedSourceSystem}
                disabled={disabled}
                onChange={(e) => pickSourceSystem(e.target.value)}
              >
                <option value="">All</option>
                {meaningfulLabelFilterOptions(sourceSystemOptions).map((s) => (
                  <option key={s} value={s}>
                    {tagOptionLabel(s)}
                  </option>
                ))}
                {sourceSystemOptions.includes(UNTAGGED_FILTER) && (
                  <option value={UNTAGGED_FILTER}>{tagOptionLabel(UNTAGGED_FILTER)}</option>
                )}
              </select>,
            )}

          {showGroupFilter &&
            fieldWrap(
              `${idPrefix}-table-group`,
              "Table group",
              <select
                id={`${idPrefix}-table-group`}
                className={proto ? undefined : "srse-select"}
                style={proto ? undefined : selectStyle}
                value={selectedTableGroup}
                disabled={disabled}
                onChange={(e) => pickTableGroup(e.target.value)}
              >
                <option value="">All</option>
                {meaningfulLabelFilterOptions(tableGroupOptions).map((g) => (
                  <option key={g} value={g}>
                    {tagOptionLabel(g)}
                  </option>
                ))}
                {tableGroupOptions.includes(UNTAGGED_FILTER) && (
                  <option value={UNTAGGED_FILTER}>{tagOptionLabel(UNTAGGED_FILTER)}</option>
                )}
              </select>,
            )}

          {showLayerFilter &&
            fieldWrap(
              `${idPrefix}-layer`,
              "Layer",
              <select
                id={`${idPrefix}-layer`}
                className={proto ? undefined : "srse-select"}
                style={proto ? undefined : selectStyle}
                value={selectedLayer}
                disabled={disabled}
                onChange={(e) => pickLayer(e.target.value)}
              >
                <option value="">All</option>
                {layerSelectOptions.map((l) => (
                  <option key={l} value={l}>
                    {tagOptionLabel(l)}
                  </option>
                ))}
              </select>,
            )}
        </div>
      </details>
    ) : null;

  return (
    <div className={proto ? "row" : undefined}>
      {label && !proto && (
        <div className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.3rem" }}>
          {label}
        </div>
      )}
      {label && proto && <div className="ttl">{label}</div>}
      <div
        className={proto ? "row" : undefined}
        style={
          proto ? { flexWrap: "wrap", gap: 8 } : { display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "flex-end" }
        }
      >
        {fieldWrap(
          `${idPrefix}-catalog`,
          "Catalog",
          <select
            id={`${idPrefix}-catalog`}
            className={proto ? undefined : "srse-select"}
            style={proto ? undefined : selectStyle}
            value={value.catalog}
            disabled={disabled}
            onChange={(e) => pickCatalog(e.target.value)}
          >
            <option value="">{loading ? "— loading… —" : "— catalog —"}</option>
            {catalogs.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>,
        )}

        {fieldWrap(
          `${idPrefix}-schema`,
          "Schema",
          <select
            id={`${idPrefix}-schema`}
            className={proto ? undefined : "srse-select"}
            style={proto ? undefined : selectStyle}
            value={value.schema}
            disabled={disabled || !value.catalog}
            onChange={(e) => pickSchema(e.target.value)}
          >
            <option value="">— schema —</option>
            {schemas.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>,
        )}

        {fieldWrap(
          `${idPrefix}-table`,
          "Table",
          <select
            id={`${idPrefix}-table`}
            className={proto ? undefined : "srse-select"}
            style={proto ? undefined : selectStyle}
            value={value.table}
            disabled={disabled || !value.schema}
            onChange={(e) => pickTable(e.target.value)}
          >
            <option value="">— table —</option>
            {tables.map((t) => (
              <option key={t.name} value={t.name}>
                {t.layer ? `${t.name} (${t.layer})` : t.name}
              </option>
            ))}
          </select>,
        )}
      </div>
      {optionalFilters}
    </div>
  );
}
