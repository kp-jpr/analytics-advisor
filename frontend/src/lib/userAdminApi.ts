import { readApiErrorMessage } from "@/lib/apiError";
import { authorizedFetch, type AuthScope } from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

async function adminFetch(input: string, init: RequestInit = {}): Promise<Response> {
  return authorizedFetch("admin", input, init);
}

export type ScopeAssignmentView = {
  scopeNodeId: number;
  dimensionId: number;
  dimensionCode: string;
  path: string;
  name: string;
};

export type UserSummary = {
  id: number;
  username: string;
  email: string | null;
  mobile: string | null;
  active: boolean;
  mfaRequired: boolean;
  mustChangePassword: boolean;
  emailVerified: boolean;
  mobileVerified: boolean;
  roles: string[];
  scopeAssignments: ScopeAssignmentView[];
  createdAt: string;
  lastLoginAt: string | null;
};

export async function listUsers(): Promise<UserSummary[]> {
  const res = await adminFetch(`${API_BASE}/api/admin/users`);
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function createUser(body: {
  username: string;
  email?: string;
  mobile?: string;
  initialPassword: string;
  roles: string[];
  scopeNodeIds?: number[];
  mfaRequired?: boolean;
}): Promise<UserSummary> {
  const res = await adminFetch(`${API_BASE}/api/admin/users`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function updateUser(
  id: number,
  body: { email?: string; mobile?: string; roles: string[]; mfaRequired?: boolean },
): Promise<UserSummary> {
  const res = await adminFetch(`${API_BASE}/api/admin/users/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function replaceUserScopes(id: number, scopeNodeIds: number[]): Promise<UserSummary> {
  const res = await adminFetch(`${API_BASE}/api/admin/users/${id}/scopes`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ scopeNodeIds }),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function deactivateUser(id: number): Promise<UserSummary> {
  const res = await adminFetch(`${API_BASE}/api/admin/users/${id}/deactivate`, { method: "POST" });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function reactivateUser(id: number): Promise<UserSummary> {
  const res = await adminFetch(`${API_BASE}/api/admin/users/${id}/reactivate`, { method: "POST" });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
  return res.json();
}

export async function resetUserPassword(id: number, newPassword: string): Promise<void> {
  const res = await adminFetch(`${API_BASE}/api/admin/users/${id}/reset-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ newPassword }),
  });
  if (!res.ok) throw new Error(await readApiErrorMessage(res));
}
