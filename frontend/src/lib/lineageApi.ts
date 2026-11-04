import { readApiErrorMessage } from "@/lib/apiError";
import { authorizedFetch } from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type LineageEventSummary = {
  id: number;
  occurredAt: string;
  actorUsername: string;
  action: string;
  auditEventId: number | null;
  savedQueryId: number | null;
  requestFingerprint: string | null;
  label: string | null;
};

export type LineageNodeView = {
  key: string;
  type: string;
  label: string;
  payloadJson: string | null;
};

export type LineageEdgeView = {
  fromKey: string;
  toKey: string;
  type: string;
};

export type LineageGraphView = {
  events: LineageEventSummary[];
  nodes: LineageNodeView[];
  edges: LineageEdgeView[];
};

export async function listLineageEvents(params: {
  actor?: string;
  table?: string;
  column?: string;
  savedQueryId?: number;
  limit?: number;
}): Promise<LineageEventSummary[]> {
  const q = new URLSearchParams();
  if (params.actor) q.set("actor", params.actor);
  if (params.table) q.set("table", params.table);
  if (params.column) q.set("column", params.column);
  if (params.savedQueryId != null) q.set("savedQueryId", String(params.savedQueryId));
  if (params.limit != null) q.set("limit", String(params.limit));
  const res = await authorizedFetch("admin", `${API_BASE}/api/admin/lineage/events?${q}`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Lineage service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function fetchLineageEventGraph(id: number): Promise<LineageGraphView> {
  const res = await authorizedFetch("admin", `${API_BASE}/api/admin/lineage/events/${id}`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Lineage service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function fetchLineageGraph(params: {
  table?: string;
  column?: string;
  actor?: string;
  limit?: number;
}): Promise<LineageGraphView> {
  const q = new URLSearchParams();
  if (params.table) q.set("table", params.table);
  if (params.column) q.set("column", params.column);
  if (params.actor) q.set("actor", params.actor);
  if (params.limit != null) q.set("limit", String(params.limit));
  const res = await authorizedFetch("admin", `${API_BASE}/api/admin/lineage/graph?${q}`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`Lineage service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}
