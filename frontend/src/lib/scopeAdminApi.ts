import { readApiErrorMessage } from "@/lib/apiError";
import { authorizedFetch } from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

async function adminFetch(input: string, init: RequestInit = {}): Promise<Response> {
  return authorizedFetch("admin", input, init);
}

export type DimensionView = { id: number; code: string; name: string; displayOrder: number };
export type LevelView = { id: number; dimensionId: number; depth: number; name: string };
export type NodeView = {
  id: number;
  dimensionId: number;
  levelId: number;
  parentId: number | null;
  code: string;
  name: string;
  path: string;
  grantable: boolean;
};

export async function listDimensions(): Promise<DimensionView[]> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/dimensions`);
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function createDimension(body: {
  code: string;
  name: string;
  displayOrder: number;
}): Promise<DimensionView> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/dimensions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function listLevels(dimensionId: number): Promise<LevelView[]> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/levels?dimensionId=${dimensionId}`);
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function createLevel(body: {
  dimensionId: number;
  depth: number;
  name: string;
}): Promise<LevelView> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/levels`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function listScopeNodes(dimensionId?: number): Promise<NodeView[]> {
  const q = dimensionId != null ? `?dimensionId=${dimensionId}` : "";
  const res = await adminFetch(`${API_BASE}/api/admin/scope/nodes${q}`);
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function listGrantableNodes(): Promise<NodeView[]> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/nodes/grantable`);
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  const body = (await res.json()) as { nodes: NodeView[] };
  return body.nodes;
}

export async function createScopeNode(body: {
  dimensionId: number;
  levelId: number;
  parentId?: number | null;
  code: string;
  name: string;
}): Promise<NodeView> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/nodes`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function deleteScopeNode(id: number): Promise<void> {
  const res = await adminFetch(`${API_BASE}/api/admin/scope/nodes/${id}`, { method: "DELETE" });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
}
