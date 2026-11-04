"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import {
  adminSectionById,
  resolveAdminSection,
  visibleAdminSections,
  type AdminSectionId,
} from "@/app/admin456/adminSections";
import { AdminIdentityPanel } from "@/components/AdminIdentityPanel";
import { AdminSectionHeader } from "@/components/admin/AdminSectionHeader";
import { AdminSectionNav } from "@/components/admin/AdminSectionNav";
import { ExternalDataSourcesPanel } from "@/components/admin/ExternalDataSourcesPanel";
import {
  AnalysisGuardrailsPanel,
  ColumnMetadataPanel,
  ConfigBackupPanel,
  ConnectionsPanel,
  LakehouseRegistryPanel,
  ActiveQueriesAdminPanel,
  MetadataSnapshotAdminPanel,
} from "@/components/admin/lakehouseAdminPanels";
import { AuditLogPanel } from "@/components/AuditLogPanel";
import { LineagePanel } from "@/components/admin/LineagePanel";
import { OtpGatewayPanel } from "@/components/OtpGatewayPanel";
import { useAuthSession } from "@/components/shell/AuthSessionProvider";
import { listColumnMetadata, type ColumnMetadata } from "@/lib/analysisApi";
import { listRegistrations, type TableRegistration } from "@/lib/adminApi";
import { SrseAdminAccessDeniedError } from "@/lib/authToken";

function errorMessage(err: unknown): string {
  if (err instanceof SrseAdminAccessDeniedError) {
    return err.message;
  }
  return err instanceof Error ? err.message : String(err);
}

export function AdminWorkspace() {
  const searchParams = useSearchParams();
  const { session, loading: sessionLoading } = useAuthSession();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const skipInitialFocus = useRef(true);

  const permissions = {
    superAdmin: Boolean(session?.superAdmin),
    auditReader: Boolean(session?.auditReader),
  };
  const section: AdminSectionId | null =
    sessionLoading || !session ? null : resolveAdminSection(searchParams.get("section"), permissions);
  const needsLakehouse = section === "registry" || section === "columns";

  const [lakehouseRefreshKey, setLakehouseRefreshKey] = useState(0);
  const [registrations, setRegistrations] = useState<TableRegistration[]>([]);
  const [columnMetadata, setColumnMetadata] = useState<ColumnMetadata[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [metadataError, setMetadataError] = useState<string | null>(null);
  const [trackedNeed, setTrackedNeed] = useState(false);
  const [trackedRefresh, setTrackedRefresh] = useState(0);

  if (needsLakehouse && (!trackedNeed || trackedRefresh !== lakehouseRefreshKey)) {
    setTrackedNeed(true);
    setTrackedRefresh(lakehouseRefreshKey);
    setLoading(true);
  } else if (!needsLakehouse && trackedNeed) {
    setTrackedNeed(false);
  }

  useEffect(() => {
    if (!needsLakehouse) return;
    let cancelled = false;
    Promise.all([
      listRegistrations()
        .then((rows) => {
          if (cancelled) return;
          setRegistrations(rows);
          setError(null);
        })
        .catch((err: unknown) => {
          if (!cancelled) setError(errorMessage(err));
        }),
      listColumnMetadata("admin")
        .then((rows) => {
          if (cancelled) return;
          setColumnMetadata(rows);
          setMetadataError(null);
        })
        .catch((err: unknown) => {
          if (!cancelled) setMetadataError(errorMessage(err));
        }),
    ]).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [needsLakehouse, lakehouseRefreshKey]);

  useEffect(() => {
    if (!section) return;
    if (skipInitialFocus.current) {
      skipInitialFocus.current = false;
      return;
    }
    headingRef.current?.focus({ preventScroll: true });
  }, [section]);

  const refreshLakehouse = useCallback(() => {
    setLakehouseRefreshKey((key) => key + 1);
  }, []);

  if (!section || !session) {
    return (
      <main className="admin-workspace">
        <p className="admin-workspace-status">Loading admin…</p>
      </main>
    );
  }

  const active = adminSectionById(section);

  return (
    <main className="admin-workspace">
      <AdminSectionNav sections={visibleAdminSections(permissions)} activeId={section} />
      <div className="admin-workspace-content">
        <AdminSectionHeader title={active.label} description={active.description} headingRef={headingRef} />
        {section === "users" && <AdminIdentityPanel view="users" />}
        {section === "scopes" && <AdminIdentityPanel view="hierarchy" />}
        {section === "connections" && <ConnectionsPanel />}
        {section === "data-sources" && session.superAdmin ? <ExternalDataSourcesPanel /> : null}
        {section === "registry" && (
          <>
            <LakehouseRegistryPanel
              registrations={registrations}
              columnMetadata={columnMetadata}
              loading={loading}
              error={error}
              onChanged={refreshLakehouse}
            />
            {session.superAdmin ? (
              <>
                <ActiveQueriesAdminPanel />
                <MetadataSnapshotAdminPanel />
              </>
            ) : null}
          </>
        )}
        {section === "columns" && (
          <ColumnMetadataPanel
            registrations={registrations}
            rows={columnMetadata}
            error={metadataError}
            loading={loading}
            onChanged={refreshLakehouse}
          />
        )}
        {section === "audit" && session.auditReader ? (
          <section className="srse-card">
            <AuditLogPanel />
          </section>
        ) : null}
        {section === "lineage" && session.auditReader ? (
          <section className="srse-card">
            <LineagePanel
              initialTable={searchParams.get("table") ?? undefined}
              initialColumn={searchParams.get("column") ?? undefined}
            />
          </section>
        ) : null}
        {section === "otp" && session.superAdmin ? <OtpGatewayPanel /> : null}
        {section === "backup" && <ConfigBackupPanel onImported={refreshLakehouse} />}
        {section === "guardrails" && <AnalysisGuardrailsPanel />}
      </div>
    </main>
  );
}
