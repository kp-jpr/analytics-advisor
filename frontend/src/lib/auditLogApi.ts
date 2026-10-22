import { readApiErrorMessage } from "@/lib/apiError";
import { authorizedFetch } from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type AuditActionType = string;
export type AuditOutcome = "SUCCESS" | "FAILURE" | "REFUSED";

export type AuditEventRow = {
  id: number;
  occurredAt: string;
  actorUserId: number | null;
  actorUsername: string | null;
  targetUserId: number | null;
  actionType: AuditActionType;
  outcome: AuditOutcome | null;
  detail: string | null;
  sourceIp: string | null;
  targetTables: string | null;
  queryShape: string | null;
  scopeSummary: string | null;
};

export type AuditPage = {
  entries: AuditEventRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  retentionDays: number;
  retentionNote: string;
};

export type AuditListParams = {
  from?: string;
  to?: string;
  actorUserId?: number;
  actionType?: string;
  outcome?: string;
  page?: number;
  size?: number;
};

function queryString(params: AuditListParams): string {
  const q = new URLSearchParams();
  if (params.from) q.set("from", params.from);
  if (params.to) q.set("to", params.to);
  if (params.actorUserId != null) q.set("actorUserId", String(params.actorUserId));
  if (params.actionType) q.set("actionType", params.actionType);
  if (params.outcome) q.set("outcome", params.outcome);
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  const s = q.toString();
  return s ? `?${s}` : "";
}

export async function fetchAuditPage(params: AuditListParams): Promise<AuditPage> {
  const res = await authorizedFetch("admin", `${API_BASE}/api/admin/audit${queryString(params)}`);
  if (!res.ok) {
    throw new Error(await readApiErrorMessage(res));
  }
  return (await res.json()) as AuditPage;
}

export async function downloadAuditCsv(params: AuditListParams): Promise<void> {
  const res = await authorizedFetch("admin", `${API_BASE}/api/admin/audit.csv${queryString(params)}`);
  if (!res.ok) {
    throw new Error(await readApiErrorMessage(res));
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "audit-log.csv";
  a.click();
  URL.revokeObjectURL(url);
}
