// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act, useEffect, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import { AdminWorkspace } from "@/components/admin/AdminWorkspace";
import { AuthSessionProvider } from "@/components/shell/AuthSessionProvider";

const harness = vi.hoisted(() => {
  const state = {
    section: null as string | null,
    listeners: new Set<() => void>(),
  };
  return {
    state,
    setSection(section: string | null) {
      state.section = section;
      state.listeners.forEach((listener) => listener());
    },
  };
});

const fetchAuthSession = vi.hoisted(() => vi.fn());
const listUsers = vi.hoisted(() => vi.fn());
const listGrantableNodes = vi.hoisted(() => vi.fn());
const createUser = vi.hoisted(() => vi.fn());
const deactivateUser = vi.hoisted(() => vi.fn());
const listDimensions = vi.hoisted(() => vi.fn());
const listLevels = vi.hoisted(() => vi.fn());
const listScopeNodes = vi.hoisted(() => vi.fn());
const createScopeNode = vi.hoisted(() => vi.fn());
const getConnections = vi.hoisted(() => vi.fn());
const listRegistrations = vi.hoisted(() => vi.fn());
const listColumnMetadata = vi.hoisted(() => vi.fn());
const listLakehouseLayers = vi.hoisted(() => vi.fn());
const listAdminSourceSystems = vi.hoisted(() => vi.fn());
const listAdminTableGroups = vi.hoisted(() => vi.fn());
const browseCatalogs = vi.hoisted(() => vi.fn());
const fetchAnalysisLimits = vi.hoisted(() => vi.fn());
const fetchAuditPage = vi.hoisted(() => vi.fn());
const fetchSmtpGateway = vi.hoisted(() => vi.fn());
const fetchSmsGateway = vi.hoisted(() => vi.fn());
const listExternalSourceTypes = vi.hoisted(() => vi.fn());
const listExternalDataSources = vi.hoisted(() => vi.fn());

vi.mock("next/link", () => ({
  default: ({
    href,
    children,
    scroll,
    ...rest
  }: {
    href: string;
    children: React.ReactNode;
    scroll?: boolean;
  }) => (
    <a href={href} data-scroll={scroll === false ? "false" : "true"} {...rest}>
      {children}
    </a>
  ),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => "/admin456",
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => {
    const [, bump] = useState(0);
    useEffect(() => {
      const listener = () => bump((n) => n + 1);
      harness.state.listeners.add(listener);
      return () => {
        harness.state.listeners.delete(listener);
      };
    }, [bump]);
    const params = new URLSearchParams();
    if (harness.state.section) params.set("section", harness.state.section);
    return params;
  },
}));

vi.mock("@/lib/authToken", () => ({
  fetchAuthSession: (...args: unknown[]) => fetchAuthSession(...args),
  SrseAdminAccessDeniedError: class SrseAdminAccessDeniedError extends Error {
    constructor() {
      super("denied");
      this.name = "SrseAdminAccessDeniedError";
    }
  },
  isLocalAuthMode: () => true,
  clearAuthToken: vi.fn(),
  readStoredAuthToken: () => null,
}));

vi.mock("@/lib/userAdminApi", () => ({
  listUsers: (...args: unknown[]) => listUsers(...args),
  listGrantableNodes: (...args: unknown[]) => listGrantableNodes(...args),
  createUser: (...args: unknown[]) => createUser(...args),
  deactivateUser: (...args: unknown[]) => deactivateUser(...args),
  reactivateUser: vi.fn(),
  replaceUserScopes: vi.fn(),
  resetUserPassword: vi.fn(),
}));

vi.mock("@/lib/scopeAdminApi", () => ({
  listDimensions: (...args: unknown[]) => listDimensions(...args),
  listLevels: (...args: unknown[]) => listLevels(...args),
  listScopeNodes: (...args: unknown[]) => listScopeNodes(...args),
  listGrantableNodes: (...args: unknown[]) => listGrantableNodes(...args),
  createDimension: vi.fn(),
  createLevel: vi.fn(),
  createScopeNode: (...args: unknown[]) => createScopeNode(...args),
  deleteScopeNode: vi.fn(),
}));

vi.mock("@/lib/adminApi", () => ({
  getConnections: (...args: unknown[]) => getConnections(...args),
  listRegistrations: (...args: unknown[]) => listRegistrations(...args),
  listLakehouseLayers: (...args: unknown[]) => listLakehouseLayers(...args),
  listAdminSourceSystems: (...args: unknown[]) => listAdminSourceSystems(...args),
  listAdminTableGroups: (...args: unknown[]) => listAdminTableGroups(...args),
  browseCatalogs: (...args: unknown[]) => browseCatalogs(...args),
  browseSchemas: vi.fn().mockResolvedValue([]),
  browseTables: vi.fn().mockResolvedValue([]),
  browseColumns: vi.fn().mockResolvedValue([]),
  renameSourceSystemLabel: vi.fn(),
  renameTableGroupLabel: vi.fn(),
  exportAdminConfig: vi.fn(),
  importAdminConfig: vi.fn(),
  registerTable: vi.fn(),
  unregisterTable: vi.fn(),
  updateAnalyticalConnection: vi.fn(),
  updateOperationalConnection: vi.fn(),
  updateTableRegistration: vi.fn(),
}));

vi.mock("@/lib/analysisApi", () => ({
  listColumnMetadata: (...args: unknown[]) => listColumnMetadata(...args),
  fetchAnalysisLimits: (...args: unknown[]) => fetchAnalysisLimits(...args),
  deleteColumnMetadata: vi.fn(),
  upsertColumnMetadata: vi.fn(),
}));

vi.mock("@/lib/auditLogApi", () => ({
  fetchAuditPage: (...args: unknown[]) => fetchAuditPage(...args),
  downloadAuditCsv: vi.fn(),
}));

vi.mock("@/lib/gatewayApi", () => ({
  fetchSmtpGateway: (...args: unknown[]) => fetchSmtpGateway(...args),
  fetchSmsGateway: (...args: unknown[]) => fetchSmsGateway(...args),
  saveSmtpGateway: vi.fn(),
  saveSmsGateway: vi.fn(),
  testSmtpGateway: vi.fn(),
  testSmsGateway: vi.fn(),
}));

vi.mock("@/lib/externalDataSourceApi", () => ({
  listExternalSourceTypes: (...args: unknown[]) => listExternalSourceTypes(...args),
  listExternalDataSources: (...args: unknown[]) => listExternalDataSources(...args),
  createExternalDataSource: vi.fn(),
  testExternalDataSource: vi.fn(),
  deleteExternalDataSource: vi.fn(),
  updateExternalFederationCatalog: vi.fn(),
  browseExternalCatalogs: vi.fn().mockResolvedValue([]),
  browseExternalSchemas: vi.fn().mockResolvedValue([]),
  browseExternalTables: vi.fn().mockResolvedValue({ items: [], page: 0, size: 25, total: 0 }),
  browseExternalColumns: vi.fn().mockResolvedValue([]),
  listExternalTableRegistrations: vi.fn().mockResolvedValue([]),
  registerExternalTable: vi.fn(),
  unregisterExternalTable: vi.fn(),
}));

type SessionShape = {
  username: string;
  roles: string[];
  authorities: string[];
  admin: boolean;
  superAdmin: boolean;
  auditReader: boolean;
  mustChangePassword: boolean;
  active: boolean;
};

function session(partial: Partial<SessionShape> = {}): SessionShape {
  return {
    username: "ada",
    roles: ["SUPER_ADMIN", "AUDIT_READER"],
    authorities: [],
    admin: true,
    superAdmin: true,
    auditReader: true,
    mustChangePassword: false,
    active: true,
    ...partial,
  };
}

const SECTION_TITLES = [
  ["users", "Users & Roles", "Create user"],
  ["scopes", "Scope Hierarchy", "Add node"],
  ["connections", "Connections", "jdbc:presto://presto:8080"],
  ["data-sources", "Data Sources", "No data sources connected"],
  ["registry", "Table Registry", "Lakehouse registry"],
  ["columns", "Column Settings", "Register a table in Table Registry first"],
  ["audit", "Audit Log", "Query shapes show SQL placeholders"],
  ["otp", "OTP Gateways", "OTP gateways (SuperAdmin)"],
  ["backup", "Configuration Backup", "Download configuration JSON"],
  ["guardrails", "Analysis Guardrails", "Max target sets"],
] as const;

function contentOf(container: HTMLElement): HTMLElement {
  const content = container.querySelector(".admin-workspace-content");
  if (!content) throw new Error("missing admin content");
  return content as HTMLElement;
}

async function flush() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function renderWorkspace() {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(
      <AuthSessionProvider>
        <AdminWorkspace />
      </AuthSessionProvider>,
    );
  });
  return { container, root };
}

describe("Admin workspace", () => {
  let root: Root | null = null;
  let container: HTMLDivElement | null = null;

  beforeEach(() => {
    harness.state.section = null;
    harness.state.listeners.clear();
    fetchAuthSession.mockReset();
    fetchAuthSession.mockResolvedValue(session());
    listUsers.mockReset();
    listUsers.mockResolvedValue([
      {
        id: 7,
        username: "pat",
        email: null,
        mobile: null,
        active: true,
        mfaRequired: false,
        mustChangePassword: false,
        emailVerified: false,
        mobileVerified: false,
        roles: ["OFFICER"],
        scopeAssignments: [],
        createdAt: "2026-01-01T00:00:00Z",
        lastLoginAt: null,
      },
    ]);
    listGrantableNodes.mockReset();
    listGrantableNodes.mockResolvedValue([]);
    createUser.mockReset();
    createUser.mockResolvedValue({ id: 9 });
    deactivateUser.mockReset();
    deactivateUser.mockResolvedValue(undefined);
    listDimensions.mockReset();
    listDimensions.mockResolvedValue([{ id: 1, code: "GEO", name: "Geography", displayOrder: 1 }]);
    listLevels.mockReset();
    listLevels.mockResolvedValue([{ id: 10, dimensionId: 1, depth: 1, name: "District" }]);
    listScopeNodes.mockReset();
    listScopeNodes.mockResolvedValue([]);
    createScopeNode.mockReset();
    createScopeNode.mockResolvedValue({ id: 3 });
    getConnections.mockReset();
    getConnections.mockResolvedValue({
      dataMode: "SYNTHETIC",
      environmentLabel: "Development",
      operational: {
        jdbcUrl: "jdbc:postgresql://db/srse",
        username: "srse",
        driverClassName: "org.postgresql.Driver",
        status: "up",
      },
      analytical: {
        jdbcUrl: "jdbc:presto://presto:8080",
        username: "srse",
        driverClassName: "com.facebook.presto.jdbc.PrestoDriver",
        status: "up",
      },
    });
    listRegistrations.mockReset();
    listRegistrations.mockResolvedValue([]);
    listColumnMetadata.mockReset();
    listColumnMetadata.mockResolvedValue([]);
    listLakehouseLayers.mockReset();
    listLakehouseLayers.mockResolvedValue(["GOLD"]);
    listAdminSourceSystems.mockReset();
    listAdminSourceSystems.mockResolvedValue([]);
    listAdminTableGroups.mockReset();
    listAdminTableGroups.mockResolvedValue([]);
    browseCatalogs.mockReset();
    browseCatalogs.mockResolvedValue([]);
    fetchAnalysisLimits.mockReset();
    fetchAnalysisLimits.mockResolvedValue({
      maxTargetSets: 4,
      multiMatchBudgetSeconds: 30,
      maxGroupColumns: 4,
      maxGroupingColumns: 8,
      maxAggregates: 8,
      maxAnyOfGroupsPerSide: 2,
      maxProbedPairs: 12,
      blockingPrefixLen: 3,
      maxEstimatedRows: 50000000,
      maxColumnDistinctValues: 500,
    });
    fetchAuditPage.mockReset();
    fetchAuditPage.mockResolvedValue({
      entries: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 1,
      retentionDays: 90,
      retentionNote: "Kept 90 days",
    });
    fetchSmtpGateway.mockReset();
    fetchSmtpGateway.mockResolvedValue({
      host: "smtp.example",
      port: 587,
      username: "mailer",
      fromAddress: "aa@example",
      tls: true,
      passwordConfigured: false,
    });
    fetchSmsGateway.mockReset();
    fetchSmsGateway.mockResolvedValue({
      endpoint: "https://sms.example",
      username: "sms",
      senderId: "SRSE",
      passwordConfigured: false,
    });
    listExternalSourceTypes.mockReset();
    listExternalSourceTypes.mockResolvedValue([
      { code: "POSTGRESQL", label: "PostgreSQL", defaultPort: 5432 },
      { code: "DB2", label: "IBM DB2", defaultPort: 50000 },
    ]);
    listExternalDataSources.mockReset();
    listExternalDataSources.mockResolvedValue([]);
  });

  afterEach(() => {
    act(() => {
      root?.unmount();
    });
    container?.remove();
    root = null;
    container = null;
  });

  async function mount() {
    ({ container, root } = renderWorkspace());
    await flush();
    await flush();
    return container!;
  }

  it("defaults to Users & Roles and marks that nav item current", async () => {
    const view = await mount();
    const panel = contentOf(view);
    expect(panel.querySelector("h1")?.textContent).toBe("Users & Roles");
    expect(panel.textContent).toContain("Create user");
    expect(panel.textContent).not.toContain("Add node");
    expect(view.querySelector(".srse-nav")).toBeNull();
    expect(view.textContent).not.toContain("Users & scopes");
    const current = view.querySelector("nav[aria-label='Admin sections'] a[aria-current='page']");
    expect(current?.textContent).toContain("Users & Roles");
    expect(current?.getAttribute("href")).toBe("/admin456?section=users");
    expect(document.activeElement).not.toBe(panel.querySelector("h1"));
    expect(listUsers).toHaveBeenCalledTimes(1);
    expect(listRegistrations).not.toHaveBeenCalled();
    expect(getConnections).not.toHaveBeenCalled();
    expect(fetchAnalysisLimits).not.toHaveBeenCalled();
    expect(listDimensions).not.toHaveBeenCalled();
  });

  it.each(SECTION_TITLES)("renders %s from the section query", async (id, title, marker) => {
    harness.state.section = id;
    const view = await mount();
    const panel = contentOf(view);
    expect(panel.querySelectorAll("h1")).toHaveLength(1);
    expect(panel.querySelector("h1")?.textContent).toBe(title);
    expect(panel.textContent).toContain(marker);
    const current = view.querySelector("a[aria-current='page']");
    expect(current?.getAttribute("href")).toBe(`/admin456?section=${id}`);
  });

  it("falls back to Users & Roles for an invalid section", async () => {
    harness.state.section = "not-a-section";
    const view = await mount();
    expect(contentOf(view).querySelector("h1")?.textContent).toBe("Users & Roles");
    expect(view.querySelector("a[aria-current='page']")?.getAttribute("href")).toBe("/admin456?section=users");
    expect(fetchAuditPage).not.toHaveBeenCalled();
    expect(fetchSmtpGateway).not.toHaveBeenCalled();
  });

  it("hides audit and otp unless the session allows them, including direct links", async () => {
    fetchAuthSession.mockResolvedValue(session({ superAdmin: false, auditReader: false, roles: ["ADMIN"] }));
    harness.state.section = "otp";
    const view = await mount();
    const hrefs = Array.from(view.querySelectorAll("nav[aria-label='Admin sections'] a")).map((anchor) =>
      anchor.getAttribute("href"),
    );
    expect(hrefs).not.toContain("/admin456?section=audit");
    expect(hrefs).not.toContain("/admin456?section=lineage");
    expect(hrefs).not.toContain("/admin456?section=otp");
    expect(hrefs).not.toContain("/admin456?section=data-sources");
    expect(contentOf(view).querySelector("h1")?.textContent).toBe("Users & Roles");
    expect(view.textContent).not.toContain("OTP gateways");
    expect(view.textContent).not.toContain("Query shapes");
    expect(fetchSmtpGateway).not.toHaveBeenCalled();
    expect(fetchAuditPage).not.toHaveBeenCalled();
  });

  it("does not open the audit panel from a direct link without audit-reader permission", async () => {
    fetchAuthSession.mockResolvedValue(session({ superAdmin: true, auditReader: false, roles: ["SUPER_ADMIN"] }));
    harness.state.section = "audit";
    const view = await mount();
    expect(view.querySelector("a[href='/admin456?section=audit']")).toBeNull();
    expect(contentOf(view).textContent).not.toContain("Query shapes");
    expect(contentOf(view).querySelector("h1")?.textContent).toBe("Users & Roles");
    expect(fetchAuditPage).not.toHaveBeenCalled();
  });

  it("shows audit and otp in the menu when the session allows them", async () => {
    const view = await mount();
    expect(view.querySelector("a[href='/admin456?section=audit']")).toBeTruthy();
    expect(view.querySelector("a[href='/admin456?section=lineage']")).toBeTruthy();
    expect(view.querySelector("a[href='/admin456?section=otp']")).toBeTruthy();
  });

  it("keeps scope hierarchy free of the old internal tabs and still creates a node", async () => {
    harness.state.section = "scopes";
    const view = await mount();
    await flush();
    const panel = contentOf(view);
    expect(panel.querySelector(".srse-nav")).toBeNull();
    expect(panel.textContent).not.toContain("Users & scopes");
    expect(panel.textContent).toContain("Add dimension");
    expect(panel.textContent).not.toContain("Create user");
    const codes = panel.querySelectorAll("input[placeholder='Code']");
    const names = panel.querySelectorAll("input[placeholder='Name']");
    const code = codes[codes.length - 1] as HTMLInputElement;
    const name = names[names.length - 1] as HTMLInputElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
      setter?.call(code, "JAIPUR");
      code.dispatchEvent(new Event("input", { bubbles: true }));
      setter?.call(name, "Jaipur");
      name.dispatchEvent(new Event("input", { bubbles: true }));
    });
    const add = Array.from(panel.querySelectorAll("button")).find((button) => button.textContent === "Add node");
    await act(async () => {
      add?.click();
    });
    await flush();
    expect(createScopeNode).toHaveBeenCalledWith(
      expect.objectContaining({ code: "JAIPUR", name: "Jaipur" }),
    );
  });

  it("still deactivates a user from Users & Roles", async () => {
    const view = await mount();
    const button = Array.from(view.querySelectorAll("button")).find((item) => item.textContent === "Deactivate");
    expect(button).toBeTruthy();
    await act(async () => {
      button?.click();
    });
    await flush();
    expect(deactivateUser).toHaveBeenCalledWith(7);
  });

  it("loads registry and column data once, and only after those sections open", async () => {
    const view = await mount();
    expect(listRegistrations).not.toHaveBeenCalled();
    await act(async () => {
      harness.setSection("registry");
    });
    await flush();
    await flush();
    expect(contentOf(view).querySelector("h1")?.textContent).toBe("Table Registry");
    expect(listRegistrations).toHaveBeenCalledTimes(1);
    expect(listColumnMetadata).toHaveBeenCalledTimes(1);
    expect(getConnections).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(contentOf(view).querySelector("h1"));
    await act(async () => {
      harness.setSection("columns");
    });
    await flush();
    expect(contentOf(view).querySelector("h1")?.textContent).toBe("Column Settings");
    expect(listRegistrations).toHaveBeenCalledTimes(1);
    expect(listColumnMetadata).toHaveBeenCalledTimes(1);
    expect(view.querySelectorAll("h1")).toHaveLength(1);
  });
});
