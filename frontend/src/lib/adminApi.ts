import { readApiErrorMessage } from "@/lib/apiError";
import {
  authorizedFetch as scopedAuthorizedFetch,
  type AuthScope,
} from "@/lib/authToken";
import type { CompareAs } from "@/lib/analysisApi";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

async function authorizedFetch(
  input: string,
  init: RequestInit = {},
  scope: AuthScope = "officer",
): Promise<Response> {
  return scopedAuthorizedFetch(scope, input, init);
}

export type ConnectionPlaneInfo = {
  jdbcUrl: string;
  username: string;
  driverClassName: string;
  status: string;
};

export type ConnectionsInfo = {
  dataMode: string;
  environmentLabel: string;
  operational: ConnectionPlaneInfo;
  analytical: ConnectionPlaneInfo;
};

export type UpdateConnectionRequest = {
  jdbcUrl: string;
  username: string;
  password: string;
  driverClassName: string;
};

export type UpdateConnectionResponse = {
  plane: ConnectionPlaneInfo | null;
  restartRequired: boolean;
};

export async function getConnections(): Promise<ConnectionsInfo> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/connections`, { credentials: "include" }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

async function updateConnection(
  plane: "analytical" | "operational",
  req: UpdateConnectionRequest,
): Promise<UpdateConnectionResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/connections/${plane}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Connection update error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export function updateAnalyticalConnection(req: UpdateConnectionRequest): Promise<UpdateConnectionResponse> {
  return updateConnection("analytical", req);
}

export function updateOperationalConnection(req: UpdateConnectionRequest): Promise<UpdateConnectionResponse> {
  return updateConnection("operational", req);
}

// ---------------------------------------------------------------------------
// Admin: lakehouse Catalog → Schema → Table → Column cascade
// ---------------------------------------------------------------------------

export type LakehouseColumnInfo = { name: string; dataType: string };

export type TableRegistration = {
  id: number;
  catalog: string;
  schema: string;
  table: string;
  /** SILVER / GOLD / null — a display tag, not a level of the hierarchy. */
  layer: string | null;
  sourceSystem: string | null;
  tableGroup: string | null;
  qualifiedName: string;
  sharedReference: boolean;
};

export type TableScopeConfig = {
  registrationId: number;
  sharedReference: boolean;
  bindings: {
    id: number;
    scopeLevelId: number;
    dimensionId: number;
    levelDepth: number;
    levelName: string;
    columnName: string;
  }[];
  exemptDimensionIds: number[];
};

async function adminGet<T>(path: string): Promise<T> {
  const res = await authorizedFetch(`${API_BASE}${path}`, { credentials: "include" }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json() as Promise<T>;
}

const enc = encodeURIComponent;

export function browseCatalogs(): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/browse/catalogs`);
}

export function browseSchemas(catalog: string): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/browse/catalogs/${enc(catalog)}/schemas`);
}

export function browseTables(catalog: string, schema: string): Promise<string[]> {
  return adminGet<string[]>(
    `/api/admin/lakehouse/browse/catalogs/${enc(catalog)}/schemas/${enc(schema)}/tables`,
  );
}

export function browseColumns(
  catalog: string,
  schema: string,
  table: string,
): Promise<LakehouseColumnInfo[]> {
  return adminGet<LakehouseColumnInfo[]>(
    `/api/admin/lakehouse/browse/catalogs/${enc(catalog)}/schemas/${enc(schema)}` +
      `/tables/${enc(table)}/columns`,
  );
}

export function listRegistrations(): Promise<TableRegistration[]> {
  return adminGet<TableRegistration[]>(`/api/admin/lakehouse/registrations`);
}

export function listLakehouseLayers(): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/layers`);
}

export function listAdminSourceSystems(): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/source-systems`);
}

export function listAdminTableGroups(): Promise<string[]> {
  return adminGet<string[]>(`/api/admin/lakehouse/table-groups`);
}

export async function renameSourceSystemLabel(from: string, to: string): Promise<{ tablesUpdated: number }> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/rename-source-system`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ from, to }),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function renameTableGroupLabel(from: string, to: string): Promise<{ tablesUpdated: number }> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/rename-table-group`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ from, to }),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function registerTable(req: {
  catalog: string;
  schema: string;
  table: string;
  layer: string;
  sourceSystem?: string | null;
  tableGroup?: string | null;
}): Promise<TableRegistration> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(req),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function updateTableRegistration(
  id: number,
  tags: { layer: string; sourceSystem?: string | null; tableGroup?: string | null },
): Promise<TableRegistration> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(tags),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function unregisterTable(id: number): Promise<void> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations/${id}`, {
    method: "DELETE",
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
}

export function getTableScopeConfig(registrationId: number): Promise<TableScopeConfig> {
  return adminGet<TableScopeConfig>(`/api/admin/lakehouse/registrations/${registrationId}/scope`);
}

export async function replaceTableScopeConfig(
  registrationId: number,
  body: {
    bindings: { scopeLevelId: number; columnName: string }[];
    exemptDimensionIds: number[];
    sharedReference?: boolean;
  },
): Promise<TableScopeConfig> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/lakehouse/registrations/${registrationId}/scope`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body),
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin service error ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

// ---------------------------------------------------------------------------
// Admin: configuration backup (connections, registrations, column metadata)
// ---------------------------------------------------------------------------

export type AdminConfigConnectionPlane = {
  jdbcUrl: string;
  username: string;
  password: string | null;
  driverClassName: string;
};

export type AdminConfigBundle = {
  schemaVersion: string;
  exportedAt: string;
  dataMode: string;
  connections: {
    operational: AdminConfigConnectionPlane;
    analytical: AdminConfigConnectionPlane;
  } | null;
  registeredTables: {
    catalog: string;
    schema: string;
    table: string;
    layer: string | null;
  }[];
  analysisColumnMetadata: {
    catalog: string;
    schema: string;
    table: string;
    column: string;
    businessName: string | null;
    fuzzyMatchable: boolean;
    visible: boolean;
    compareAs: CompareAs;
    valuePickerEnabled?: boolean;
  }[];
};

export type AdminConfigImportResult = {
  registeredTableCount: number;
  columnMetadataCount: number;
  operationalRestartRequired: boolean;
  connectionsImported: boolean;
  importedTables: string[];
  importedColumns: string[];
  skipped: { section: string; reason: string }[];
};

export async function exportAdminConfig(): Promise<AdminConfigBundle> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/config/export`, {
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Admin config export failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export type MetadataSyncRunStatus = {
  runId: number | null;
  trigger: "SCHEDULED" | "REGISTRATION" | "MANUAL" | null;
  outcome: "SUCCESS" | "DEGRADED" | "FAILED" | "RUNNING" | null;
  startedAt: string | null;
  finishedAt: string | null;
  tables: {
    catalog: string;
    schema: string;
    table: string;
    outcome: "SUCCESS" | "FAILED";
    errorMessage: string | null;
  }[];
};

export type MetadataSyncStartResponse = {
  runId: number;
  skippedOverlapping: boolean;
};

export async function startMetadataSync(): Promise<MetadataSyncStartResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/metadata/sync`, {
    method: "POST",
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Metadata sync failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function getMetadataSyncStatus(): Promise<MetadataSyncRunStatus> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/metadata/status`, {
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Metadata status failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export type ActiveQueryView = {
  queryId: string;
  username: string;
  kind: string;
  qualifiedTables: string;
  state: string;
  startedAt: string;
  durationMs: number | null;
};

export type ActiveQueriesResponse = {
  instanceNote: string;
  running: ActiveQueryView[];
  recent: ActiveQueryView[];
};

export async function getActiveQueries(): Promise<ActiveQueriesResponse> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/queries/active`, {
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Active queries failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}

export async function downloadMetadataManifest(): Promise<string> {
  const res = await authorizedFetch(`${API_BASE}/api/admin/metadata/manifest`, {
    credentials: "include",
  }, "admin");
  if (!res.ok) {
    throw new Error(`Metadata manifest failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.text();
}

export async function importAdminConfig(
  bundle: AdminConfigBundle,
  options?: { testConnections?: boolean },
): Promise<AdminConfigImportResult> {
  const testConnections = options?.testConnections ?? true;
  const res = await authorizedFetch(
    `${API_BASE}/api/admin/config/import?testConnections=${testConnections ? "true" : "false"}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(bundle),
    },
    "admin",
  );
  if (!res.ok) {
    throw new Error(`Admin config import failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  return res.json();
}
