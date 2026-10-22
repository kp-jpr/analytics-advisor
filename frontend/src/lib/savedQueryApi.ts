import { readApiErrorMessage } from "./apiError";
import { authorizedFetch as officerFetch } from "./authToken";
import type { RecordMatchRequest } from "./analysisApi";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

async function authorizedFetch(input: string, init: RequestInit = {}): Promise<Response> {
  return officerFetch("officer", input, init);
}

export type SavedQuerySummary = {
  id: number;
  ownerUserId: number;
  name: string;
  description: string | null;
  updatedAt: string;
  ownedByMe: boolean;
};

export type SavedQueryDetail = SavedQuerySummary & {
  createdAt: string;
  request: RecordMatchRequest;
};

export function listSavedQueries(): Promise<SavedQuerySummary[]> {
  return authorizedFetch(`${API_BASE}/api/saved-queries`, { credentials: "include" }).then(async (res) => {
    if (!res.ok) throw new Error(await readApiErrorMessage(res));
    return res.json() as Promise<SavedQuerySummary[]>;
  });
}

export function getSavedQuery(id: number): Promise<SavedQueryDetail> {
  return authorizedFetch(`${API_BASE}/api/saved-queries/${id}`, { credentials: "include" }).then(async (res) => {
    if (!res.ok) throw new Error(await readApiErrorMessage(res));
    return res.json() as Promise<SavedQueryDetail>;
  });
}

export function createSavedQuery(body: {
  name: string;
  description?: string;
  request: RecordMatchRequest;
}): Promise<SavedQueryDetail> {
  return authorizedFetch(`${API_BASE}/api/saved-queries`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body),
  }).then(async (res) => {
    if (!res.ok) throw new Error(await readApiErrorMessage(res));
    return res.json() as Promise<SavedQueryDetail>;
  });
}

export function deleteSavedQuery(id: number): Promise<void> {
  return authorizedFetch(`${API_BASE}/api/saved-queries/${id}`, {
    method: "DELETE",
    credentials: "include",
  }).then(async (res) => {
    if (!res.ok) throw new Error(await readApiErrorMessage(res));
  });
}
