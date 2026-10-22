import { readApiErrorMessage } from "@/lib/apiError";
import { authorizedFetch } from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type ExternalSourceType = {
  code: string;
  label: string;
  defaultPort: number;
  // False when PrestoDB has no connector for this database (IBM DB2): no Presto alias can exist.
  federationCapable?: boolean;
};

export type ExternalDataSource = {
  id: number;
  name: string;
  databaseType: string;
  databaseTypeLabel: string;
  jdbcUrl: string;
  username: string;
  federationCatalog: string | null;
  active: boolean;
  passwordConfigured: boolean;
  lastTestedAt: string | null;
  lastTestStatus: string | null;
  lastTestMessage: string | null;
  registrationCount: number;
  federationCapable?: boolean;
};

export type CreateExternalDataSource = {
  name: string;
  databaseType: string;
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  ssl: boolean;
  federationCatalog?: string;
};

export type ExternalTable = {
  catalog: string | null;
  schema: string | null;
  name: string;
  type: string;
  remarks: string | null;
};

export type ExternalTablePage = {
  items: ExternalTable[];
  page: number;
  size: number;
  total: number;
};

export type ExternalColumn = {
  name: string;
  dataType: string;
  jdbcType: number;
  size: number | null;
  decimalDigits: number | null;
  nullable: boolean;
  primaryKey: boolean;
  ordinalPosition: number;
  remarks: string | null;
};

export type ExternalTableRegistration = {
  registrationId: number;
  logicalCatalog: string;
  schema: string;
  table: string;
  sourceName: string;
  physicalCatalog: string | null;
  layer: string | null;
  sourceSystem: string | null;
  tableGroup: string | null;
};

async function adminRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await authorizedFetch("admin", `${API_BASE}${path}`, {
    credentials: "include",
    ...init,
  });
  if (!response.ok) {
    const message = await readApiErrorMessage(response);
    throw new Error(message || `Data-source service error ${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function listExternalSourceTypes(): Promise<ExternalSourceType[]> {
  return adminRequest("/api/admin/data-sources/types");
}

export function listExternalDataSources(): Promise<ExternalDataSource[]> {
  return adminRequest("/api/admin/data-sources");
}

export function createExternalDataSource(body: CreateExternalDataSource): Promise<ExternalDataSource> {
  return adminRequest("/api/admin/data-sources", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function testExternalDataSource(id: number): Promise<{ success: boolean; message: string }> {
  return adminRequest(`/api/admin/data-sources/${id}/test`, { method: "POST" });
}

export function updateExternalFederationCatalog(
  id: number,
  federationCatalog: string | null,
): Promise<ExternalDataSource> {
  return adminRequest(`/api/admin/data-sources/${id}/federation`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ federationCatalog }),
  });
}

export function deleteExternalDataSource(id: number): Promise<void> {
  return adminRequest(`/api/admin/data-sources/${id}`, { method: "DELETE" });
}

export function browseExternalCatalogs(id: number): Promise<string[]> {
  return adminRequest(`/api/admin/data-sources/${id}/catalogs`);
}

export function browseExternalSchemas(id: number, catalog: string): Promise<string[]> {
  const query = catalog ? `?catalog=${encodeURIComponent(catalog)}` : "";
  return adminRequest(`/api/admin/data-sources/${id}/schemas${query}`);
}

export function browseExternalTables(
  id: number,
  options: { catalog: string; schema: string; search: string; page: number; size: number },
): Promise<ExternalTablePage> {
  const query = new URLSearchParams({
    catalog: options.catalog,
    schema: options.schema,
    search: options.search,
    page: String(options.page),
    size: String(options.size),
  });
  return adminRequest(`/api/admin/data-sources/${id}/tables?${query}`);
}

export function browseExternalColumns(
  id: number,
  table: Pick<ExternalTable, "catalog" | "schema" | "name">,
): Promise<ExternalColumn[]> {
  const query = new URLSearchParams({ table: table.name });
  if (table.catalog) query.set("catalog", table.catalog);
  if (table.schema) query.set("schema", table.schema);
  return adminRequest(`/api/admin/data-sources/${id}/columns?${query}`);
}

export function listExternalTableRegistrations(id: number): Promise<ExternalTableRegistration[]> {
  return adminRequest(`/api/admin/data-sources/${id}/registrations`);
}

export function registerExternalTable(
  id: number,
  table: Pick<ExternalTable, "catalog" | "schema" | "name">,
  labels: { layer?: string; sourceSystem?: string; tableGroup?: string } = {},
): Promise<ExternalTableRegistration> {
  return adminRequest(`/api/admin/data-sources/${id}/registrations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      catalog: table.catalog,
      schema: table.schema,
      table: table.name,
      ...labels,
    }),
  });
}

export function unregisterExternalTable(sourceId: number, registrationId: number): Promise<void> {
  return adminRequest(`/api/admin/data-sources/${sourceId}/registrations/${registrationId}`, {
    method: "DELETE",
  });
}
