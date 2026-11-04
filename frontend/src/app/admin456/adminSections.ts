/**
 * Admin workspace sections. Menu visibility is not authorization — the
 * backend still enforces every admin API. This module only decides which
 * section the page may render.
 */

export const ADMIN_SECTION_IDS = [
  "users",
  "scopes",
  "connections",
  "data-sources",
  "registry",
  "columns",
  "audit",
  "lineage",
  "otp",
  "backup",
  "guardrails",
] as const;

export type AdminSectionId = (typeof ADMIN_SECTION_IDS)[number];

export type AdminSectionGroupId = "access" | "data" | "security";

export type AdminPermissions = {
  superAdmin: boolean;
  auditReader: boolean;
};

export type AdminSectionDefinition = {
  id: AdminSectionId;
  group: AdminSectionGroupId;
  label: string;
  description: string;
  superAdminOnly?: boolean;
  auditReaderOnly?: boolean;
};

export const ADMIN_SECTION_GROUPS: readonly { id: AdminSectionGroupId; label: string }[] = [
  { id: "access", label: "Access Management" },
  { id: "data", label: "Data Configuration" },
  { id: "security", label: "Security & Operations" },
];

export const ADMIN_SECTIONS: readonly AdminSectionDefinition[] = [
  {
    id: "users",
    group: "access",
    label: "Users & Roles",
    description: "Create accounts, assign roles and scopes, and activate or deactivate users.",
  },
  {
    id: "scopes",
    group: "access",
    label: "Scope Hierarchy",
    description: "Maintain the dimensions, levels, and nodes that limit what each officer can see.",
  },
  {
    id: "connections",
    group: "data",
    label: "Connections",
    description: "Review and update the operational and analytical database connections.",
  },
  {
    id: "data-sources",
    group: "data",
    label: "Data Sources",
    description: "Connect external databases and browse their catalogs, schemas, tables, views, and columns.",
    superAdminOnly: true,
  },
  {
    id: "registry",
    group: "data",
    label: "Table Registry",
    description: "Register lakehouse tables and the display tags officers use in Analysis.",
  },
  {
    id: "columns",
    group: "data",
    label: "Column Settings",
    description: "Set business names, fuzzy matching, visibility, masking, and how columns are compared.",
  },
  {
    id: "audit",
    group: "security",
    label: "Audit Log",
    description: "Review recorded sign-in, query, and administration events.",
    auditReaderOnly: true,
  },
  {
    id: "lineage",
    group: "security",
    label: "Data Lineage",
    description: "Trace registered sources, attributes, joins, result columns, and who ran or exported.",
    auditReaderOnly: true,
  },
  {
    id: "otp",
    group: "security",
    label: "OTP Gateways",
    description: "Configure the email and SMS gateways that send one-time passwords.",
    superAdminOnly: true,
  },
  {
    id: "backup",
    group: "security",
    label: "Configuration Backup",
    description: "Download or restore connections, table registrations, and column settings.",
  },
  {
    id: "guardrails",
    group: "security",
    label: "Analysis Guardrails",
    description: "Read-only limits this deployment enforces on analysis matches.",
  },
];

const SECTION_ID_SET = new Set<string>(ADMIN_SECTION_IDS);

export function isAdminSectionId(value: string | null | undefined): value is AdminSectionId {
  return typeof value === "string" && SECTION_ID_SET.has(value);
}

export function sectionIsPermitted(section: AdminSectionDefinition, permissions: AdminPermissions): boolean {
  if (section.superAdminOnly && !permissions.superAdmin) return false;
  if (section.auditReaderOnly && !permissions.auditReader) return false;
  return true;
}

export function visibleAdminSections(permissions: AdminPermissions): AdminSectionDefinition[] {
  return ADMIN_SECTIONS.filter((section) => sectionIsPermitted(section, permissions));
}

export function adminSectionById(id: AdminSectionId): AdminSectionDefinition {
  const section = ADMIN_SECTIONS.find((item) => item.id === id);
  if (!section) {
    throw new Error(`Unknown admin section: ${id}`);
  }
  return section;
}

/**
 * Absent or invalid values fall back to Users & Roles. A known section the
 * session may not open falls back to the first permitted section, which is
 * Users & Roles for every administrator.
 */
export function resolveAdminSection(
  raw: string | null | undefined,
  permissions: AdminPermissions,
): AdminSectionId {
  const visible = visibleAdminSections(permissions);
  const fallback = visible[0]?.id ?? "users";
  const usersAllowed = visible.some((section) => section.id === "users");
  const safeDefault: AdminSectionId = usersAllowed ? "users" : fallback;

  if (raw == null || raw.trim() === "") {
    return safeDefault;
  }
  if (!isAdminSectionId(raw)) {
    return safeDefault;
  }
  if (!visible.some((section) => section.id === raw)) {
    return fallback;
  }
  return raw;
}

export function adminSectionHref(id: AdminSectionId): string {
  return `/admin456?section=${id}`;
}
