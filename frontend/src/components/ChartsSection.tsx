"use client";

import { useEffect, useMemo, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { VennDiagram } from "@/components/VennDiagram";
import { presentQueryError } from "@/lib/queryErrorText";

export type ChartDimension = { key: string; label: string };

const PIE_COLORS = [
  "#4f46e5", "#059669", "#d97706", "#dc2626", "#0891b2",
  "#7c3aed", "#db2777", "#65a30d", "#0284c7", "#ea580c",
];

// Orange / green / navy, matching the reference 3-circle Venn layout.
const VENN_COLORS = ["#ea580c", "#65a30d", "#0c4a6e"];

const MAX_INTERSECTION_DIMS = 5;
// Separator for composite group-by keys — unlikely to appear in real data values.
const KEY_SEP = "␟";

// Analysis match results are no longer capped at 1000 rows, so a field can
// have thousands of distinct values. Recharts renders one SVG bar/label (or
// pie slice) per category with no virtualization — beyond a few dozen that
// crashes the tab (confirmed live: an OOM "Error code: 5" renderer crash),
// not just looks bad. Cap what actually gets rendered, always by count so
// the most meaningful categories survive the cut, and say so when truncated.
const MAX_CHART_CATEGORIES = 50;
// A plain HTML table row is far cheaper than an SVG bar, so this can be looser.
const MAX_TABLE_ROWS = 200;
// Matrix mode multiplies two axes into rowVals.length * colVals.length cells —
// cap each axis so the product stays bounded even if both dimensions are
// high-cardinality (e.g. two id-like columns).
const MAX_MATRIX_AXIS_VALUES = 40;
// Native <select> handles a few hundred options fine — this is just a sanity
// ceiling for pathological cases, not a rendering-cost concern like the above.
const MAX_SELECT_OPTIONS = 500;

type ChartType = "bar" | "pie" | "intersection";
type IntersectionDisplay = "table" | "venn";

type AggEntry = { value: string; count: number };

type MatrixIntersection = {
  kind: "matrix";
  rowVals: string[];
  colVals: string[];
  totals: Map<string, number>;
  truncated: boolean;
  rowValsFullCount: number;
  colValsFullCount: number;
};

type FlatIntersection = {
  kind: "flat";
  combos: { parts: string[]; count: number }[];
  truncated: boolean;
  fullCount: number;
};

type IntersectionResult = MatrixIntersection | FlatIntersection;

type ChartsSectionProps<T> = Readonly<{
  rows: T[];
  dimensions: ChartDimension[];
  getValue: (row: T, key: string) => string;
  getCount: (row: T) => number;
  title?: string;
  /**
   * When set, bar, pie, and intersection counts come from the full match.
   * `rows` stays the on-screen preview and is not scanned.
   */
  loadBuckets?: (columns: string[]) => Promise<{
    totalRows: number;
    truncated: boolean;
    buckets: { values: string[]; count: number; other: boolean }[];
  }>;
}>;

function chartTypeLabel(t: ChartType): string {
  if (t === "bar") return "Bar Chart";
  if (t === "pie") return "Pie Chart";
  return "Intersection Chart";
}

function chartTypeButtonClass(active: boolean): string {
  return active ? "srse-btn srse-btn-primary srse-btn-sm" : "srse-btn srse-btn-ghost srse-btn-sm";
}

function sortStrings(values: string[]): string[] {
  return [...values].sort((a, b) => a.localeCompare(b));
}

function aggregateSingleDim<T>(
  rows: T[],
  singleDim: string,
  getValue: (row: T, key: string) => string,
  getCount: (row: T) => number,
): AggEntry[] {
  if (!singleDim) return [];
  const totals = new Map<string, number>();
  for (const row of rows) {
    const v = getValue(row, singleDim) || "(blank)";
    totals.set(v, (totals.get(v) ?? 0) + getCount(row));
  }
  return Array.from(totals.entries())
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => b.count - a.count);
}

function computeIntersection<T>(
  rows: T[],
  intersectionDims: string[],
  getValue: (row: T, key: string) => string,
  getCount: (row: T) => number,
): IntersectionResult | null {
  if (intersectionDims.length < 2) return null;
  const totals = new Map<string, number>();
  for (const row of rows) {
    const key = intersectionDims.map((d) => getValue(row, d) || "(blank)").join(KEY_SEP);
    totals.set(key, (totals.get(key) ?? 0) + getCount(row));
  }
  if (intersectionDims.length === 2) {
    const rowValsFull = sortStrings(
      Array.from(new Set(rows.map((r) => getValue(r, intersectionDims[0]) || "(blank)"))),
    );
    const colValsFull = sortStrings(
      Array.from(new Set(rows.map((r) => getValue(r, intersectionDims[1]) || "(blank)"))),
    );
    return {
      kind: "matrix",
      rowVals: rowValsFull.slice(0, MAX_MATRIX_AXIS_VALUES),
      colVals: colValsFull.slice(0, MAX_MATRIX_AXIS_VALUES),
      totals,
      truncated: rowValsFull.length > MAX_MATRIX_AXIS_VALUES || colValsFull.length > MAX_MATRIX_AXIS_VALUES,
      rowValsFullCount: rowValsFull.length,
      colValsFullCount: colValsFull.length,
    };
  }
  const combosFull = Array.from(totals.entries())
    .map(([key, count]) => ({ parts: key.split(KEY_SEP), count }))
    .sort((a, b) => b.count - a.count);
  const truncated = combosFull.length > MAX_TABLE_ROWS;
  return {
    kind: "flat",
    combos: truncated ? combosFull.slice(0, MAX_TABLE_ROWS) : combosFull,
    truncated,
    fullCount: combosFull.length,
  };
}

function SingleDimensionChart({
  chartType,
  singleAgg,
}: Readonly<{
  chartType: "bar" | "pie";
  singleAgg: AggEntry[];
}>) {
  if (singleAgg.length === 0) {
    return <p className="srse-text-muted">No data to chart.</p>;
  }
  if (chartType === "bar") {
    return (
      <div style={{ width: "100%", height: 320 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={singleAgg} margin={{ top: 8, right: 16, left: 0, bottom: 48 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--srse-border)" />
            <XAxis
              dataKey="value"
              angle={-30}
              textAnchor="end"
              interval={0}
              height={60}
              tick={{ fontSize: 12, fill: "var(--srse-text-muted)" }}
            />
            <YAxis allowDecimals={false} tick={{ fontSize: 12, fill: "var(--srse-text-muted)" }} />
            <Tooltip />
            <Bar dataKey="count" fill="var(--srse-primary)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    );
  }
  return (
    <div style={{ width: "100%", height: 360 }}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={singleAgg} dataKey="count" nameKey="value" cx="50%" cy="50%" outerRadius={110} label>
            {singleAgg.map((entry, i) => (
              <Cell key={entry.value} fill={PIE_COLORS[i % PIE_COLORS.length]} />
            ))}
          </Pie>
          <Tooltip />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}

function MatrixIntersectionTable({
  intersection,
  intersectionDims,
  dimLabel,
}: Readonly<{
  intersection: MatrixIntersection;
  intersectionDims: string[];
  dimLabel: (key: string) => string;
}>) {
  return (
    <div>
      {intersection.truncated && (
        <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.6rem" }}>
          Showing the top {intersection.rowVals.length} of {intersection.rowValsFullCount} row values and
          top {intersection.colVals.length} of {intersection.colValsFullCount} column values by name —
          pick lower-cardinality fields to see the rest.
        </p>
      )}
      <div style={{ overflowX: "auto" }}>
        <table className="srse-table">
          <thead>
            <tr>
              <th>
                {dimLabel(intersectionDims[0])} vs {dimLabel(intersectionDims[1])}
              </th>
              {intersection.colVals.map((c) => (
                <th key={c}>{c}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {intersection.rowVals.map((r) => (
              <tr key={r}>
                <td style={{ fontWeight: 500 }}>{r}</td>
                {intersection.colVals.map((c) => (
                  <td key={c}>{intersection.totals.get(`${r}${KEY_SEP}${c}`) ?? 0}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function FlatIntersectionTable({
  intersection,
  intersectionDims,
  dimLabel,
}: Readonly<{
  intersection: FlatIntersection;
  intersectionDims: string[];
  dimLabel: (key: string) => string;
}>) {
  return (
    <div>
      {intersection.truncated && (
        <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.6rem" }}>
          Showing the top {MAX_TABLE_ROWS} of {intersection.fullCount} combinations by count — pick fewer
          or lower-cardinality fields, or use the data grid / CSV export, to see the rest.
        </p>
      )}
      <div style={{ overflowX: "auto" }}>
        <table className="srse-table">
          <thead>
            <tr>
              {intersectionDims.map((k) => (
                <th key={k}>{dimLabel(k)}</th>
              ))}
              <th>Count</th>
            </tr>
          </thead>
          <tbody>
            {intersection.combos.map((combo) => {
              const comboKey = combo.parts.join(KEY_SEP);
              return (
                <tr key={comboKey}>
                  {combo.parts.map((p, j) => (
                    <td key={`${comboKey}-${intersectionDims[j] ?? j}`}>{p}</td>
                  ))}
                  <td>{combo.count}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function IntersectionContent({
  intersectionDims,
  intersectionDisplay,
  intersection,
  vennCounts,
  vennEligibleDims,
  dimLabel,
  vennValues,
}: Readonly<{
  intersectionDims: string[];
  intersectionDisplay: IntersectionDisplay;
  intersection: IntersectionResult | null;
  vennCounts: Record<string, number> | null;
  vennEligibleDims: boolean;
  dimLabel: (key: string) => string;
  vennValues: Record<string, string>;
}>) {
  if (intersectionDims.length < 2) {
    return <p className="srse-text-muted">Pick at least 2 fields to see their intersection.</p>;
  }
  if (vennEligibleDims && intersectionDisplay === "venn" && vennCounts) {
    return (
      <VennDiagram
        sets={intersectionDims.map((d, i) => ({
          label: `${dimLabel(d)} = ${vennValues[d] ?? ""}`,
          color: VENN_COLORS[i % VENN_COLORS.length],
        }))}
        counts={vennCounts}
      />
    );
  }
  if (intersection?.kind === "matrix") {
    return (
      <MatrixIntersectionTable
        intersection={intersection}
        intersectionDims={intersectionDims}
        dimLabel={dimLabel}
      />
    );
  }
  if (intersection?.kind === "flat") {
    return (
      <FlatIntersectionTable
        intersection={intersection}
        intersectionDims={intersectionDims}
        dimLabel={dimLabel}
      />
    );
  }
  return null;
}

/**
 * Generic Bar/Pie/Intersection chart section, shared by the Rule Engine's
 * breakdown table (ResultsPanel) and the Analysis tab's match grid
 * (AnalysisResultsGrid) — both aggregate over already-fetched rows,
 * client-side, no new API calls. `getCount` lets each caller decide what
 * "one" means: Rule Engine rows are pre-aggregated (sum row.count), Analysis
 * rows are one match per row (count rows, i.e. always 1).
 */
export function ChartsSection<T>({
  rows,
  dimensions,
  getValue,
  getCount,
  title = "Charts",
  loadBuckets,
}: ChartsSectionProps<T>) {
  const [chartType, setChartType] = useState<ChartType>("bar");
  const [singleDim, setSingleDim] = useState(dimensions[0]?.key ?? "");
  const [intersectionDims, setIntersectionDims] = useState<string[]>([]);
  const [intersectionDisplay, setIntersectionDisplay] = useState<IntersectionDisplay>("venn");
  const [vennValues, setVennValues] = useState<Record<string, string>>({});
  const [serverSingle, setServerSingle] = useState<AggEntry[] | null>(null);
  const [serverSingleTruncated, setServerSingleTruncated] = useState(false);
  const [serverIntersection, setServerIntersection] = useState<FlatIntersection | null>(null);
  const [serverIntersectionTruncated, setServerIntersectionTruncated] = useState(false);
  const [chartLoading, setChartLoading] = useState(false);
  const [chartNotice, setChartNotice] = useState<string | null>(null);
  const serverMode = Boolean(loadBuckets);
  const intersectionKey = intersectionDims.join("\u0001");

  useEffect(() => {
    if (!loadBuckets) return undefined;
    if (chartType === "intersection") {
      if (intersectionDims.length < 2) {
        setServerIntersection(null);
        setChartLoading(false);
        return undefined;
      }
      let cancelled = false;
      setChartLoading(true);
      setChartNotice(null);
      loadBuckets(intersectionDims)
        .then((payload) => {
          if (cancelled) return;
          const combos = payload.buckets.map((bucket) => ({
            parts: bucket.other
              ? intersectionDims.map(() => "Other")
              : bucket.values.map((value) => value || "(blank)"),
            count: bucket.count,
          }));
          setServerIntersection({
            kind: "flat",
            combos,
            truncated: false,
            fullCount: combos.length,
          });
          setServerIntersectionTruncated(payload.truncated);
        })
        .catch((err: unknown) => {
          if (!cancelled) {
            setChartNotice(presentQueryError(
              err instanceof Error ? err.message : String(err),
              "The analytical engine is unavailable. Check the Presto connection, then try again.",
            ));
          }
        })
        .finally(() => {
          if (!cancelled) setChartLoading(false);
        });
      return () => {
        cancelled = true;
      };
    }
    if (!singleDim) return undefined;
    let cancelled = false;
    setChartLoading(true);
    setChartNotice(null);
    loadBuckets([singleDim])
      .then((payload) => {
        if (cancelled) return;
        setServerSingle(
          payload.buckets.map((bucket) => ({
            value: bucket.other ? "Other" : bucket.values[0] || "(blank)",
            count: bucket.count,
          })),
        );
        setServerSingleTruncated(payload.truncated);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setChartNotice(presentQueryError(
            err instanceof Error ? err.message : String(err),
            "The analytical engine is unavailable. Check the Presto connection, then try again.",
          ));
        }
      })
      .finally(() => {
        if (!cancelled) setChartLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [loadBuckets, chartType, singleDim, intersectionKey, intersectionDims]);

  const singleAggFull = useMemo(
    () => (serverMode ? (serverSingle ?? []) : aggregateSingleDim(rows, singleDim, getValue, getCount)),
    [serverMode, serverSingle, rows, singleDim, getValue, getCount],
  );
  const singleAggTruncated = serverMode ? serverSingleTruncated : singleAggFull.length > MAX_CHART_CATEGORIES;
  const singleAgg = serverMode || !singleAggTruncated ? singleAggFull : singleAggFull.slice(0, MAX_CHART_CATEGORIES);

  const intersection = useMemo(
    () => (serverMode ? serverIntersection : computeIntersection(rows, intersectionDims, getValue, getCount)),
    [serverMode, serverIntersection, rows, intersectionDims, getValue, getCount],
  );

  const vennEligibleDims = !serverMode && (intersectionDims.length === 2 || intersectionDims.length === 3);

  const distinctValuesFor = useMemo(
    () => (key: string) =>
      sortStrings(Array.from(new Set(rows.map((r) => getValue(r, key) || "(blank)")))).slice(0, MAX_SELECT_OPTIONS),
    [rows, getValue],
  );

  const vennCounts = useMemo(() => {
    if (serverMode || !vennEligibleDims) return null;
    const totals: Record<string, number> = {};
    for (const row of rows) {
      const bits = intersectionDims
        .map((d) => (getValue(row, d) || "(blank)") === (vennValues[d] ?? "") ? "1" : "0")
        .join("");
      if (!/1/.test(bits)) continue;
      totals[bits] = (totals[bits] ?? 0) + getCount(row);
    }
    return totals;
  }, [serverMode, rows, intersectionDims, vennValues, vennEligibleDims, getValue, getCount]);

  function toggleIntersectionDim(key: string) {
    setIntersectionDims((cur) => {
      if (cur.includes(key)) {
        setVennValues((v) => {
          const next = { ...v };
          delete next[key];
          return next;
        });
        return cur.filter((k) => k !== key);
      }
      if (cur.length >= MAX_INTERSECTION_DIMS) return cur;
      const values = distinctValuesFor(key);
      if (values.length > 0) {
        setVennValues((v) => ({ ...v, [key]: values[0] }));
      }
      return [...cur, key];
    });
  }

  function dimLabel(key: string): string {
    return dimensions.find((d) => d.key === key)?.label ?? key;
  }

  if (rows.length === 0) return null;

  return (
    <section className="srse-card" style={{ width: "100%" }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          flexWrap: "wrap",
          gap: "0.75rem",
          marginBottom: "0.75rem",
        }}
      >
        <h2 className="srse-card-title" style={{ margin: 0 }}>
          {title}
        </h2>
        <div style={{ display: "flex", gap: "0.4rem" }}>
          {(["bar", "pie", "intersection"] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={chartTypeButtonClass(chartType === t)}
              onClick={() => setChartType(t)}
            >
              {chartTypeLabel(t)}
            </button>
          ))}
        </div>
      </div>

      {(chartType === "bar" || chartType === "pie") && (
        <div>
          <label
            htmlFor="charts-single-dim"
            style={{ display: "flex", alignItems: "center", gap: "0.5rem", fontSize: "0.85rem", marginBottom: "0.75rem" }}
          >
            <span className="srse-text-muted">Field</span>
            <select
              id="charts-single-dim"
              className="srse-select"
              value={singleDim}
              onChange={(e) => setSingleDim(e.target.value)}
            >
              {dimensions.map((d) => (
                <option key={d.key} value={d.key}>
                  {d.label}
                </option>
              ))}
            </select>
          </label>

          {chartNotice && (
            <p className="srse-query-notice" role="status">{chartNotice}</p>
          )}
          {chartLoading && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem" }}>Counting every matching row…</p>
          )}

          {singleAggTruncated && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "-0.35rem", marginBottom: "0.6rem" }}>
              {serverMode
                ? `Counts include every matching row. Showing the top ${MAX_CHART_CATEGORIES} values by count. Remaining rows are in Other.`
                : `Showing the top ${MAX_CHART_CATEGORIES} of ${singleAggFull.length} distinct values by count — pick a lower-cardinality field, or use the data grid / CSV export, to see the rest.`}
            </p>
          )}
          {serverMode && !singleAggTruncated && !chartLoading && serverSingle && serverSingle.length > 0 && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginTop: "-0.35rem", marginBottom: "0.6rem" }}>
              Counts include every matching row.
            </p>
          )}

          <SingleDimensionChart chartType={chartType} singleAgg={singleAgg} />
        </div>
      )}

      {chartType === "intersection" && (
        <div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: "0.4rem 1rem", marginBottom: "0.85rem" }}>
            {dimensions.map((d) => (
              <label key={d.key} className="srse-checkbox-label" htmlFor={`intersect-dim-${d.key}`}>
                <input
                  id={`intersect-dim-${d.key}`}
                  type="checkbox"
                  checked={intersectionDims.includes(d.key)}
                  onChange={() => toggleIntersectionDim(d.key)}
                />
                {" "}
                {d.label}
              </label>
            ))}
          </div>

          {vennEligibleDims && (
            <div style={{ display: "flex", alignItems: "center", gap: "0.4rem", marginBottom: "0.85rem" }}>
              {(["table", "venn"] as const).map((d) => (
                <button
                  key={d}
                  type="button"
                  className={chartTypeButtonClass(intersectionDisplay === d)}
                  onClick={() => setIntersectionDisplay(d)}
                >
                  {d === "table" ? "Table" : "Venn Diagram"}
                </button>
              ))}
            </div>
          )}

          {vennEligibleDims && intersectionDisplay === "venn" && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: "0.75rem", marginBottom: "1rem" }}>
              {intersectionDims.map((d) => (
                <label
                  key={d}
                  htmlFor={`venn-value-${d}`}
                  style={{ display: "flex", alignItems: "center", gap: "0.4rem", fontSize: "0.85rem" }}
                >
                  <span className="srse-text-muted">{dimLabel(d)} =</span>
                  <select
                    id={`venn-value-${d}`}
                    className="srse-select"
                    value={vennValues[d] ?? ""}
                    onChange={(e) => setVennValues((v) => ({ ...v, [d]: e.target.value }))}
                  >
                    {distinctValuesFor(d).map((v) => (
                      <option key={v} value={v}>
                        {v}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
          )}

          {serverMode && serverIntersectionTruncated && intersectionDims.length >= 2 && (
            <p className="srse-text-muted" style={{ fontSize: "0.78rem", marginBottom: "0.6rem" }}>
              Counts include every matching row. Showing the top {MAX_CHART_CATEGORIES} combinations by count. Remaining rows are in Other.
            </p>
          )}

          <IntersectionContent
            intersectionDims={intersectionDims}
            intersectionDisplay={intersectionDisplay}
            intersection={intersection}
            vennCounts={vennCounts}
            vennEligibleDims={vennEligibleDims}
            dimLabel={dimLabel}
            vennValues={vennValues}
          />
        </div>
      )}
    </section>
  );
}
