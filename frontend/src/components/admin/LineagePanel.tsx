"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  fetchLineageEventGraph,
  fetchLineageGraph,
  listLineageEvents,
  type LineageEdgeView,
  type LineageEventSummary,
  type LineageGraphView,
  type LineageNodeView,
} from "@/lib/lineageApi";

const TYPE_ORDER = [
  "DATA_SOURCE",
  "TABLE",
  "ATTRIBUTE",
  "RESULT_COLUMN",
  "QUERY_RUN",
  "SAVED_QUERY",
  "EXPORT",
  "ACTOR",
];

function layerFor(type: string): number {
  const idx = TYPE_ORDER.indexOf(type);
  return idx < 0 ? TYPE_ORDER.length : idx;
}

function layoutNodes(nodes: LineageNodeView[]): Map<string, { x: number; y: number }> {
  const byLayer = new Map<number, LineageNodeView[]>();
  for (const node of nodes) {
    const layer = layerFor(node.type);
    const list = byLayer.get(layer) ?? [];
    list.push(node);
    byLayer.set(layer, list);
  }
  const positions = new Map<string, { x: number; y: number }>();
  const layers = [...byLayer.keys()].sort((a, b) => a - b);
  layers.forEach((layer, layerIndex) => {
    const list = byLayer.get(layer) ?? [];
    list.forEach((node, i) => {
      positions.set(node.key, {
        x: 40 + layerIndex * 180,
        y: 40 + i * 56,
      });
    });
  });
  return positions;
}

function LineageGraphCanvas({
  nodes,
  edges,
  selectedKey,
  onSelect,
}: Readonly<{
  nodes: LineageNodeView[];
  edges: LineageEdgeView[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
}>) {
  const positions = useMemo(() => layoutNodes(nodes), [nodes]);
  const width = Math.max(720, [...positions.values()].reduce((m, p) => Math.max(m, p.x), 0) + 200);
  const height = Math.max(360, [...positions.values()].reduce((m, p) => Math.max(m, p.y), 0) + 80);

  return (
    <svg width="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Data lineage graph">
      {edges.map((edge) => {
        const from = positions.get(edge.fromKey);
        const to = positions.get(edge.toKey);
        if (!from || !to) return null;
        return (
          <line
            key={`${edge.fromKey}-${edge.toKey}-${edge.type}`}
            x1={from.x + 120}
            y1={from.y + 16}
            x2={to.x}
            y2={to.y + 16}
            stroke="#94a3b8"
            strokeWidth={1.5}
            markerEnd="url(#arrow)"
          />
        );
      })}
      <defs>
        <marker id="arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
          <path d="M0,0 L6,3 L0,6 Z" fill="#94a3b8" />
        </marker>
      </defs>
      {nodes.map((node) => {
        const pos = positions.get(node.key);
        if (!pos) return null;
        const active = selectedKey === node.key;
        return (
          <g key={node.key} transform={`translate(${pos.x}, ${pos.y})`} onClick={() => onSelect(node.key)} style={{ cursor: "pointer" }}>
            <rect
              width={140}
              height={36}
              rx={6}
              fill={active ? "#0f766e" : "#f8fafc"}
              stroke={active ? "#0f766e" : "#cbd5e1"}
            />
            <text x={8} y={14} fontSize={10} fill={active ? "#ecfdf5" : "#64748b"}>
              {node.type}
            </text>
            <text x={8} y={28} fontSize={11} fill={active ? "#fff" : "#0f172a"}>
              {(node.label.length > 18 ? `${node.label.slice(0, 16)}…` : node.label)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

export function LineagePanel({
  initialTable,
  initialColumn,
}: Readonly<{ initialTable?: string; initialColumn?: string }>) {
  const [table, setTable] = useState(initialTable ?? "");
  const [column, setColumn] = useState(initialColumn ?? "");
  const [actor, setActor] = useState("");
  const [events, setEvents] = useState<LineageEventSummary[]>([]);
  const [graph, setGraph] = useState<LineageGraphView | null>(null);
  const [selectedEventId, setSelectedEventId] = useState<number | null>(null);
  const [selectedNodeKey, setSelectedNodeKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedNode = graph?.nodes.find((n) => n.key === selectedNodeKey) ?? null;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [eventRows, neighbourhood] = await Promise.all([
        listLineageEvents({
          table: table.trim() || undefined,
          column: column.trim() || undefined,
          actor: actor.trim() || undefined,
          limit: 40,
        }),
        fetchLineageGraph({
          table: table.trim() || undefined,
          column: column.trim() || undefined,
          actor: actor.trim() || undefined,
          limit: 25,
        }),
      ]);
      setEvents(eventRows);
      setGraph(neighbourhood);
      setSelectedEventId(null);
      setSelectedNodeKey(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [actor, column, table]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openEvent(id: number) {
    setLoading(true);
    setError(null);
    try {
      const next = await fetchLineageEventGraph(id);
      setGraph(next);
      setSelectedEventId(id);
      setSelectedNodeKey(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <p className="srse-text-muted" style={{ marginTop: 0 }}>
        In-app lineage from registered sources through attributes, joins, result columns, and who ran or exported.
      </p>
      <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", marginBottom: "1rem" }}>
        <input
          className="srse-input"
          placeholder="catalog.schema.table"
          value={table}
          onChange={(e) => setTable(e.target.value)}
          aria-label="Filter by table"
          style={{ minWidth: 220 }}
        />
        <input
          className="srse-input"
          placeholder="column"
          value={column}
          onChange={(e) => setColumn(e.target.value)}
          aria-label="Filter by column"
          style={{ width: 140 }}
        />
        <input
          className="srse-input"
          placeholder="actor username"
          value={actor}
          onChange={(e) => setActor(e.target.value)}
          aria-label="Filter by actor"
          style={{ width: 160 }}
        />
        <button type="button" className="srse-btn srse-btn-sm" onClick={() => void load()} disabled={loading}>
          {loading ? "Loading…" : "Search"}
        </button>
      </div>
      {error ? <p className="srse-text-danger">{error}</p> : null}
      <div style={{ display: "grid", gridTemplateColumns: "minmax(220px, 280px) 1fr", gap: "1rem" }}>
        <div>
          <h4 style={{ marginTop: 0 }}>Recent events</h4>
          <ul style={{ listStyle: "none", padding: 0, margin: 0, maxHeight: 420, overflow: "auto" }}>
            {events.map((event) => (
              <li key={event.id} style={{ marginBottom: "0.5rem" }}>
                <button
                  type="button"
                  className="srse-btn srse-btn-sm"
                  style={{
                    width: "100%",
                    textAlign: "left",
                    background: selectedEventId === event.id ? "#ecfdf5" : undefined,
                  }}
                  onClick={() => void openEvent(event.id)}
                >
                  <div style={{ fontWeight: 600 }}>{event.action}</div>
                  <div className="srse-text-muted" style={{ fontSize: "0.78rem" }}>
                    {event.actorUsername} · {new Date(event.occurredAt).toLocaleString()}
                  </div>
                  <div style={{ fontSize: "0.78rem" }}>{event.label}</div>
                </button>
              </li>
            ))}
            {events.length === 0 && !loading ? (
              <li className="srse-text-muted">No lineage events yet. Run or export a query to capture one.</li>
            ) : null}
          </ul>
        </div>
        <div>
          <h4 style={{ marginTop: 0 }}>{selectedEventId ? `Event #${selectedEventId}` : "Neighbourhood graph"}</h4>
          <div style={{ border: "1px solid #e2e8f0", borderRadius: 8, background: "#fff", overflow: "auto" }}>
            {graph && graph.nodes.length > 0 ? (
              <LineageGraphCanvas
                nodes={graph.nodes}
                edges={graph.edges}
                selectedKey={selectedNodeKey}
                onSelect={setSelectedNodeKey}
              />
            ) : (
              <p className="srse-text-muted" style={{ padding: "1rem" }}>
                {loading ? "Loading graph…" : "No graph nodes for this filter."}
              </p>
            )}
          </div>
          {selectedNode ? (
            <div style={{ marginTop: "0.75rem" }}>
              <h4 style={{ marginBottom: "0.35rem" }}>Selected node</h4>
              <dl style={{ display: "grid", gridTemplateColumns: "120px 1fr", gap: "0.25rem 0.75rem", margin: 0 }}>
                <dt className="srse-text-muted">Type</dt>
                <dd style={{ margin: 0 }}>{selectedNode.type}</dd>
                <dt className="srse-text-muted">Label</dt>
                <dd style={{ margin: 0 }}>{selectedNode.label}</dd>
                <dt className="srse-text-muted">Key</dt>
                <dd style={{ margin: 0, fontFamily: "monospace", fontSize: "0.8rem" }}>{selectedNode.key}</dd>
                {selectedNode.payloadJson ? (
                  <>
                    <dt className="srse-text-muted">Payload</dt>
                    <dd style={{ margin: 0, fontFamily: "monospace", fontSize: "0.8rem" }}>{selectedNode.payloadJson}</dd>
                  </>
                ) : null}
              </dl>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
