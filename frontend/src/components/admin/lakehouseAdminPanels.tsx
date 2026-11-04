"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  browseCatalogs,
  browseColumns,
  browseSchemas,
  browseTables,
  getConnections,
  listLakehouseLayers,
  listAdminSourceSystems,
  listAdminTableGroups,
  renameSourceSystemLabel,
  renameTableGroupLabel,
  downloadMetadataManifest,
  exportAdminConfig,
  getActiveQueries,
  getMetadataSyncStatus,
  type ActiveQueryView,
  importAdminConfig,
  startMetadataSync,
  type MetadataSyncRunStatus,
  registerTable,
  unregisterTable,
  updateAnalyticalConnection,
  updateOperationalConnection,
  updateTableRegistration,
  type AdminConfigBundle,
  type AdminConfigImportResult,
  type ConnectionPlaneInfo,
  type ConnectionsInfo,
  type LakehouseColumnInfo,
  type TableRegistration,
} from "@/lib/adminApi";
import {
  deleteColumnMetadata,
  fetchAnalysisLimits,
  upsertColumnMetadata,
  type AnalysisLimits,
  type ColumnMaskPolicy,
  type ColumnMetadata,
  type CompareAs,
} from "@/lib/analysisApi";
import { SrseAdminAccessDeniedError } from "@/lib/authToken";
import { runningEnvironmentLabel } from "@/lib/environmentLabels";
import LakehouseCascade, {
  EMPTY_CASCADE,
  isCascadeComplete,
  type CascadeFetchers,
  type CascadeValue,
} from "@/components/LakehouseCascade";
import { SingleSelectDropdown } from "@/components/MultiSelectDropdown";
import { TableScopeBindingEditor } from "@/components/TableScopeBindingEditor";

/**
 * Admin cascades browse the LIVE lakehouse — everything the current Presto
 * connection can physically reach — which is what makes discovery possible
 * before anything is registered. (The Analysis tab passes registry-backed
 * fetchers to the same component instead; see LakehouseCascade's javadoc.)
 */
const BROWSE_FETCHERS: CascadeFetchers = {
  listCatalogs: browseCatalogs,
  listSchemas: browseSchemas,
  listTables: (catalog, schema) =>
    browseTables(catalog, schema).then((names) => names.map((name) => ({ name }))),
};


/** Registering an already-registered table re-tags its layer rather than duplicating it. */
function registerButtonLabel(saving: boolean, alreadyRegistered: boolean): string {
  if (saving) return "Registering…";
  return alreadyRegistered ? "Update layer" : "+ Register table";
}

function qualified(v: CascadeValue, column?: string): string {
  const base = `${v.catalog}.${v.schema}.${v.table}`;
  return column ? `${base}.${column}` : base;
}

function errorMessage(err: unknown): string {
  if (err instanceof SrseAdminAccessDeniedError) {
    return err.message;
  }
  return err instanceof Error ? err.message : String(err);
}

function StatusBadge({ status }: Readonly<{ status: string }>) {
  const up = status === "up";
  return (
    <span className={up ? "srse-badge srse-badge-success" : "srse-badge srse-badge-danger"}>
      <span className="srse-badge-dot" />
      {status}
    </span>
  );
}

/**
 * Two-click delete: the first click arms it, the second commits.
 *
 * Deliberately not `window.confirm` — a native modal blocks the whole page
 * (and any automated smoke-test driving this screen), and arming inline keeps
 * the row the admin is about to remove visible while they decide.
 */
function ConfirmDeleteButton({
  idleLabel,
  confirmLabel,
  title,
  onConfirm,
  onError,
}: Readonly<{
  idleLabel: string;
  confirmLabel: string;
  title?: string;
  onConfirm: () => Promise<void>;
  onError: (message: string) => void;
}>) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    try {
      await onConfirm();
      setArmed(false);
    } catch (err: unknown) {
      onError(errorMessage(err));
      setArmed(false);
    } finally {
      setBusy(false);
    }
  }

  if (!armed) {
    return (
      <button
        type="button"
        className="srse-btn srse-btn-ghost srse-btn-sm"
        title={title}
        onClick={() => setArmed(true)}
      >
        {idleLabel}
      </button>
    );
  }

  return (
    <span style={{ display: "inline-flex", gap: "0.35rem", alignItems: "center" }}>
      <button type="button" className="srse-btn srse-btn-danger srse-btn-sm" disabled={busy} onClick={run}>
        {busy ? "Working…" : confirmLabel}
      </button>
      <button
        type="button"
        className="srse-btn srse-btn-ghost srse-btn-sm"
        disabled={busy}
        onClick={() => setArmed(false)}
      >
        Cancel
      </button>
    </span>
  );
}

type PlaneKey = "operational" | "analytical";

const PLANE_LABEL: Record<PlaneKey, string> = {
  operational: "Operational (DB2 / JPA)",
  analytical: "Analytical (Presto / JDBC)",
};

function ConnectionCard({
  planeKey,
  plane,
  onLiveUpdate,
}: Readonly<{
  planeKey: PlaneKey;
  plane: ConnectionPlaneInfo;
  onLiveUpdate: (plane: ConnectionPlaneInfo) => void;
}>) {
  const [editing, setEditing] = useState(false);
  const [jdbcUrl, setJdbcUrl] = useState(plane.jdbcUrl);
  const [username, setUsername] = useState(plane.username);
  const [password, setPassword] = useState("");
  const [driverClassName, setDriverClassName] = useState(plane.driverClassName);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  function startEditing() {
    setJdbcUrl(plane.jdbcUrl);
    setUsername(plane.username);
    setPassword("");
    setDriverClassName(plane.driverClassName);
    setError(null);
    setMessage(null);
    setEditing(true);
  }

  async function onSave() {
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const update = planeKey === "analytical" ? updateAnalyticalConnection : updateOperationalConnection;
      const result = await update({ jdbcUrl, username, password, driverClassName });
      if (result.restartRequired) {
        setMessage("Saved — restart the backend for this to take effect.");
      } else if (result.plane) {
        setMessage("Saved and applied immediately — no restart needed.");
        onLiveUpdate(result.plane);
      }
      setEditing(false);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      style={{
        flex: "1 1 320px",
        border: "1px solid var(--srse-border)",
        borderRadius: "var(--srse-radius-sm)",
        padding: "1rem 1.15rem",
        background: "var(--srse-surface)",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.6rem" }}>
        <div style={{ fontWeight: 600, fontSize: "0.92rem" }}>{PLANE_LABEL[planeKey]}</div>
        <button
          type="button"
          className="srse-btn srse-btn-ghost srse-btn-sm"
          onClick={() => (editing ? setEditing(false) : startEditing())}
        >
          {editing ? "Cancel" : "Edit"}
        </button>
      </div>

      {!editing && (
        <>
          <div style={{ fontSize: "0.83rem", fontFamily: "monospace", marginBottom: "0.35rem", color: "var(--srse-text)" }}>
            {plane.jdbcUrl}
          </div>
          <div className="srse-text-muted" style={{ marginBottom: "0.6rem" }}>
            user: {plane.username} · driver: {plane.driverClassName}
          </div>
          <StatusBadge status={plane.status} />
        </>
      )}

      {editing && (
        <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
          <input
            placeholder="JDBC URL"
            value={jdbcUrl}
            onChange={(e) => setJdbcUrl(e.target.value)}
            className="srse-input"
            style={{ fontFamily: "monospace" }}
          />
          <input
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="srse-input"
          />
          <input
            type="password"
            placeholder="Password (write-only — always re-enter)"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="srse-input"
          />
          <input
            placeholder="Driver class name"
            value={driverClassName}
            onChange={(e) => setDriverClassName(e.target.value)}
            className="srse-input"
          />
          <button type="button" className="srse-btn srse-btn-primary" disabled={saving} onClick={onSave}>
            {saving ? "Testing & saving…" : "Test & Save"}
          </button>
        </div>
      )}

      {message && (
        <p className="srse-text-success" style={{ marginBottom: 0, marginTop: "0.6rem" }}>
          {message}
        </p>
      )}
      {error && (
        <p className="srse-text-danger" style={{ marginBottom: 0, marginTop: "0.6rem" }}>
          {error}
        </p>
      )}
    </div>
  );
}

export function AnalysisGuardrailsPanel() {
  const [limits, setLimits] = useState<AnalysisLimits | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchAnalysisLimits()
      .then(setLimits)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  return (
    <section className="srse-card" style={{ marginBottom: "1.25rem" }}>
      <h2 className="srse-section-title">Analysis guardrails (read-only)</h2>
      <p className="srse-text-muted" style={{ marginBottom: "0.75rem", maxWidth: "52rem" }}>
        Limits enforced on match and multi-match runs — set via deployment config (
        <code>SRSE_ANALYSIS_*</code>), not editable in this UI.
      </p>
      {error && <p className="srse-text-danger">{error}</p>}
      {!limits && !error && <p className="srse-text-muted">Loading…</p>}
      {limits && (
        <dl
          style={{
            display: "grid",
            gridTemplateColumns: "minmax(220px, 1fr) auto",
            gap: "0.35rem 1.5rem",
            margin: 0,
            fontSize: "0.88rem",
          }}
        >
          <dt className="srse-text-muted">Max target sets (multi-match)</dt>
          <dd style={{ margin: 0 }}>{limits.maxTargetSets}</dd>
          <dt className="srse-text-muted">Multi-match time budget (seconds)</dt>
          <dd style={{ margin: 0 }}>{limits.multiMatchBudgetSeconds}</dd>
          <dt className="srse-text-muted">Max columns per side per group</dt>
          <dd style={{ margin: 0 }}>{limits.maxGroupColumns}</dd>
          <dt className="srse-text-muted">Max ANY_OF groups per side</dt>
          <dd style={{ margin: 0 }}>{limits.maxAnyOfGroupsPerSide}</dd>
          <dt className="srse-text-muted">Max join-key overlap pairs probed</dt>
          <dd style={{ margin: 0 }}>{limits.maxProbedPairs}</dd>
          <dt className="srse-text-muted">Fuzzy blocking prefix length (characters)</dt>
          <dd style={{ margin: 0 }}>{limits.blockingPrefixLen}</dd>
          <dt className="srse-text-muted">Max estimated match fan-out (rows)</dt>
          <dd style={{ margin: 0 }}>{limits.maxEstimatedRows.toLocaleString()}</dd>
        </dl>
      )}
    </section>
  );
}

export function ConnectionsPanel() {
  const [connections, setConnections] = useState<ConnectionsInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getConnections()
      .then(setConnections)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Connections</h2>
      {error && <p className="srse-text-danger">{error}</p>}
      {!connections && !error && <p className="srse-text-muted">Loading…</p>}
      {connections && (
        <>
          <p className="srse-text-muted" style={{ marginTop: 0, marginBottom: "1rem", lineHeight: 1.5 }}>
            Running environment:{" "}
            <strong style={{ color: "var(--srse-text)" }}>
              {runningEnvironmentLabel(connections.environmentLabel, connections.dataMode)}
            </strong>{" "}
            (<code>DATA_MODE={connections.dataMode}</code>, set via environment config — not editable here).{" "}
            Override the display name with <code>SRSE_ENV_LABEL</code> when this box is UAT or staging.{" "}
            <strong style={{ color: "var(--srse-text)" }}>Analytical</strong> edits below apply immediately, no
            restart. <strong style={{ color: "var(--srse-text)" }}>Operational</strong> edits are tested and saved
            but only take effect after a manual backend restart.
            <br />
            The Presto URL is now <strong style={{ color: "var(--srse-text)" }}>catalog-agnostic</strong>:{" "}
            <code>jdbc:presto://host:8080</code> is enough. Any trailing <code>/catalog/schema</code> is
            only a default — SRSE addresses every table by its full{" "}
            <code>catalog.schema.table</code>, so one connection reaches all registered catalogs
            (including both the Silver and Gold layers).
          </p>
          <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap" }}>
            <ConnectionCard
              planeKey="operational"
              plane={connections.operational}
              onLiveUpdate={(plane) => setConnections((c) => (c ? { ...c, operational: plane } : c))}
            />
            <ConnectionCard
              planeKey="analytical"
              plane={connections.analytical}
              onLiveUpdate={(plane) => setConnections((c) => (c ? { ...c, analytical: plane } : c))}
            />
          </div>
        </>
      )}
    </section>
  );
}


const COMPARE_AS_OPTIONS: { value: CompareAs; label: string; title: string }[] = [
  {
    value: "AUTO",
    label: "Auto",
    title:
      "Default. Compared as numbers when one side is numeric and the other is text, so 0123 still matches 123. Two columns of the same type are compared as they always were.",
  },
  {
    value: "NUMBER",
    label: "Number",
    title:
      "Always compare numerically — the text side goes through TRY_CAST. Text that is not a number simply does not match.",
  },
  {
    value: "TEXT",
    label: "Text",
    title:
      "Always compare as text — use when the text form is the truth, e.g. a code with meaningful leading zeros.",
  },
  {
    value: "DATE",
    label: "Date",
    title:
      "Always compare as calendar dates — non-date sides go through TRY_CAST to DATE.",
  },
];

function CompareAsSelect({
  id,
  value,
  onChange,
}: Readonly<{ id: string; value: CompareAs; onChange: (value: CompareAs) => void }>) {
  return (
    <select
      id={id}
      aria-label="Compare as"
      className="srse-select"
      value={value}
      onChange={(e) => onChange(e.target.value as CompareAs)}
      title={COMPARE_AS_OPTIONS.find((o) => o.value === value)?.title}
    >
      {COMPARE_AS_OPTIONS.map((o) => (
        <option key={o.value} value={o.value} title={o.title}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

function ColumnMetadataRowEditor({
  row,
  orphaned,
  onChanged,
}: Readonly<{
  row: ColumnMetadata;
  /** True once the table this row curates is no longer registered. */
  orphaned: boolean;
  onChanged: () => void;
}>) {
  const [businessName, setBusinessName] = useState(row.businessName ?? "");
  const [fuzzyMatchable, setFuzzyMatchable] = useState(row.fuzzyMatchable);
  const [visible, setVisible] = useState(row.visible);
  const [compareAs, setCompareAs] = useState<CompareAs>(row.compareAs ?? "AUTO");
  const [valuePickerEnabled, setValuePickerEnabled] = useState(row.valuePickerEnabled ?? false);
  const [maskPolicy, setMaskPolicy] = useState<ColumnMaskPolicy>(row.maskPolicy ?? "NONE");
  const [maskKeepLast, setMaskKeepLast] = useState(row.maskKeepLast ?? 4);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const dirty =
    valuePickerEnabled !== (row.valuePickerEnabled ?? false) ||
    businessName !== (row.businessName ?? "") ||
    fuzzyMatchable !== row.fuzzyMatchable ||
    visible !== row.visible ||
    compareAs !== (row.compareAs ?? "AUTO") ||
    maskPolicy !== (row.maskPolicy ?? "NONE") ||
    maskKeepLast !== (row.maskKeepLast ?? 4);

  // Unique per fully-qualified column — the same table.column can exist in
  // both the Silver and Gold catalog, so the bare pair is not a unique DOM id.
  const rowId = `${row.catalog}-${row.schema}-${row.table}-${row.column}`;

  async function onSave() {
    setSaving(true);
    setError(null);
    try {
      await upsertColumnMetadata(
        { catalog: row.catalog, schema: row.schema, table: row.table },
        row.column,
        businessName.trim() || null,
        fuzzyMatchable,
        visible,
        compareAs,
        valuePickerEnabled,
        maskPolicy,
        maskKeepLast,
      );
      onChanged();
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr>
      <td className="srse-text-muted" style={{ fontSize: "0.78rem", fontFamily: "monospace" }}>
        {row.catalog} › {row.schema} › {row.table}
        {/*
          Settings outlive an unregister on purpose — re-registering the table
          restores the admin's intent, hidden columns included, rather than
          silently re-exposing them. That leaves rows pointing at tables nobody
          can reach any more, so say so and offer the delete that clears them.
        */}
        {orphaned && (
          <div
            className="srse-text-danger"
            style={{ fontSize: "0.7rem", marginTop: "0.2rem", fontWeight: 600 }}
            title="This table is not registered, so the setting has no effect. Register the table again to reapply it, or delete the row."
          >
            ⚠ table not registered
          </div>
        )}
      </td>
      <td className="srse-text-muted" style={{ fontSize: "0.8rem", fontFamily: "monospace" }}>
        {row.column}
      </td>
      <td>
        <input
          value={businessName}
          placeholder="e.g. Account Number"
          onChange={(e) => setBusinessName(e.target.value)}
          className="srse-input"
          style={{ width: 220 }}
        />
      </td>
      <td>
        <label className="srse-checkbox-label" htmlFor={`col-meta-fuzzy-${rowId}`} title="Offer approximate (Levenshtein) matching for this column in the Analysis tab">
          <input
            id={`col-meta-fuzzy-${rowId}`}
            type="checkbox"
            checked={fuzzyMatchable}
            onChange={(e) => setFuzzyMatchable(e.target.checked)}
          />
          {" "}
          Fuzzy
        </label>
      </td>
      <td>
        <CompareAsSelect id={`col-meta-compare-${rowId}`} value={compareAs} onChange={setCompareAs} />
      </td>
      <td>
        <label
          className="srse-checkbox-label"
          htmlFor={`col-meta-picker-${rowId}`}
          title="Offer officers a list of this column's distinct values. Runs a SELECT DISTINCT on the engine, so enable it only for low-cardinality reference columns (state, status, gender), not names or IDs."
        >
          <input
            id={`col-meta-picker-${rowId}`}
            type="checkbox"
            checked={valuePickerEnabled}
            onChange={(e) => setValuePickerEnabled(e.target.checked)}
          />
          {" "}
          Value list
        </label>
      </td>
      <td>
        <label className="srse-checkbox-label" htmlFor={`col-meta-visible-${rowId}`} title="Uncheck to hide this column from officers in the Analysis tab">
          <input
            id={`col-meta-visible-${rowId}`}
            type="checkbox"
            checked={visible}
            onChange={(e) => setVisible(e.target.checked)}
          />
          {" "}
          Visible
        </label>
      </td>
      <td>
        <select
          id={`col-meta-mask-${rowId}`}
          aria-label="Mask policy"
          className="srse-select"
          value={maskPolicy}
          onChange={(e) => setMaskPolicy(e.target.value as ColumnMaskPolicy)}
          title="Rewrites values in the result grid, export, and charts. Joins still use the clear column."
        >
          <option value="NONE">None</option>
          <option value="FULL">Full (****)</option>
          <option value="PARTIAL">Partial (keep last)</option>
          <option value="HASH">Hash</option>
        </select>
        {maskPolicy === "PARTIAL" ? (
          <input
            type="number"
            min={1}
            max={64}
            className="srse-input"
            style={{ width: 64, marginTop: "0.35rem" }}
            aria-label="Keep last characters"
            value={maskKeepLast}
            onChange={(e) => setMaskKeepLast(Math.max(1, Number.parseInt(e.target.value, 10) || 4))}
          />
        ) : null}
      </td>
      <td>
        <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
          <button type="button" className="srse-btn srse-btn-sm" disabled={!dirty || saving} onClick={onSave}>
            {saving ? "Saving…" : "Save"}
          </button>
          <ConfirmDeleteButton
            idleLabel="Delete"
            confirmLabel="Yes, delete"
            title="Removes this override. The column stays available to officers with an auto-derived label and default fuzzy matching."
            onConfirm={() =>
              deleteColumnMetadata(
                { catalog: row.catalog, schema: row.schema, table: row.table },
                row.column,
              ).then(onChanged)
            }
            onError={setError}
          />
          <a
            className="srse-btn srse-btn-sm"
            href={`/admin456?section=lineage&table=${encodeURIComponent(`${row.catalog}.${row.schema}.${row.table}`)}&column=${encodeURIComponent(row.column)}`}
          >
            View lineage
          </a>
          {error && <span className="srse-text-danger">{error}</span>}
        </div>
      </td>
    </tr>
  );
}

function RegisterColumnMetadataForm({
  registrations,
  loading = false,
  onCreated,
}: Readonly<{ registrations: TableRegistration[]; loading?: boolean; onCreated: () => void }>) {
  // Scoped to REGISTERED tables, not the whole lakehouse: curating metadata
  // for an unregistered table would be invisible to officers, and the backend
  // rejects it anyway (registration is the first of its two gates).
  const [registrationId, setRegistrationId] = useState("");
  const [columns, setColumns] = useState<LakehouseColumnInfo[]>([]);
  const [columnsForTable, setColumnsForTable] = useState<string | null>(null);
  const [column, setColumn] = useState("");
  const [businessName, setBusinessName] = useState("");
  const [fuzzyMatchable, setFuzzyMatchable] = useState(false);
  const [visible, setVisible] = useState(true);
  const [compareAs, setCompareAs] = useState<CompareAs>("AUTO");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selected = registrations.find((r) => String(r.id) === registrationId) ?? null;

  const tableKey = selected?.qualifiedName ?? null;

  useEffect(() => {
    if (!selected || !tableKey) return;
    let cancelled = false;
    browseColumns(selected.catalog, selected.schema, selected.table)
      .then((cols) => {
        if (!cancelled) {
          setColumns(cols);
          setColumnsForTable(tableKey);
        }
      })
      .catch((err: unknown) => setError(errorMessage(err)));
    return () => {
      cancelled = true;
    };
    // Re-fetch keyed on the qualified name, not the object identity, so a
    // list refresh that returns an equal-but-new object doesn't re-query.
  }, [tableKey, selected?.catalog, selected?.schema, selected?.table]);

  const columnOptions =
    selected && tableKey === columnsForTable
      ? columns.map((c) => ({ value: c.name, label: `${c.name} (${c.dataType})` }))
      : [];

  async function onSubmit() {
    if (!selected || !column) return;
    setSaving(true);
    setError(null);
    try {
      await upsertColumnMetadata(
        { catalog: selected.catalog, schema: selected.schema, table: selected.table },
        column,
        businessName.trim() || null,
        fuzzyMatchable,
        visible,
        compareAs,
      );
      setBusinessName("");
      setFuzzyMatchable(false);
      setVisible(true);
      setCompareAs("AUTO");
      onCreated();
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  if (loading && registrations.length === 0) {
    return <p className="srse-text-muted" style={{ margin: 0 }}>Loading…</p>;
  }

  if (registrations.length === 0) {
    return (
      <p className="srse-text-muted" style={{ margin: 0 }}>
        Register a table in Table Registry first — column metadata can only be attached to a registered table.
      </p>
    );
  }

  return (
    <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
      <select
        value={registrationId}
        onChange={(e) => {
          setRegistrationId(e.target.value);
          setColumn("");
        }}
        className="srse-select"
        style={{ minWidth: 320 }}
      >
        <option value="">— select registered table —</option>
        {registrations.map((r) => (
          <option key={r.id} value={String(r.id)}>
            {r.catalog} › {r.schema} › {r.table}
            {r.layer ? ` (${r.layer})` : ""}
          </option>
        ))}
      </select>
      <SingleSelectDropdown
        options={columnOptions}
        selected={column}
        onChange={setColumn}
        placeholder="— select column —"
        disabled={!selected || columnOptions.length === 0}
        width={260}
        ariaLabel="Analysis column metadata column"
      />
      <input
        placeholder="Business name (e.g. Account Number)"
        value={businessName}
        onChange={(e) => setBusinessName(e.target.value)}
        className="srse-input"
        style={{ width: 220 }}
      />
      <label className="srse-text-muted" htmlFor="register-col-compare" style={{ fontSize: "0.72rem" }}>
        Compare as
      </label>
      <CompareAsSelect id="register-col-compare" value={compareAs} onChange={setCompareAs} />
      <label className="srse-checkbox-label" htmlFor="register-col-fuzzy" title="Offer approximate (Levenshtein) matching for this column in the Analysis tab">
        <input id="register-col-fuzzy" type="checkbox" checked={fuzzyMatchable} onChange={(e) => setFuzzyMatchable(e.target.checked)} />
        {" "}
        Fuzzy matchable
      </label>
      <label className="srse-checkbox-label" htmlFor="register-col-visible" title="Uncheck to hide this column from officers — registering a table exposes all of its columns by default">
        <input id="register-col-visible" type="checkbox" checked={visible} onChange={(e) => setVisible(e.target.checked)} />
        {" "}
        Visible to officers
      </label>
      <button type="button" className="srse-btn srse-btn-primary" disabled={saving || !selected || !column} onClick={onSubmit}>
        {saving ? "Adding…" : "+ Add"}
      </button>
      {error && (
        <p className="srse-text-danger" style={{ width: "100%", margin: 0 }}>
          {error}
        </p>
      )}
    </div>
  );
}

export function ColumnMetadataPanel({
  registrations,
  rows,
  error,
  loading = false,
  onChanged,
}: Readonly<{
  registrations: TableRegistration[];
  rows: ColumnMetadata[];
  error: string | null;
  loading?: boolean;
  onChanged: () => void;
}>) {
  const registeredTables = useMemo(
    () => new Set(registrations.map((r) => r.qualifiedName)),
    [registrations],
  );

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Analysis tab: column business names, fuzzy matching &amp; visibility</h2>
      <p className="srse-page-description" style={{ maxWidth: "none", marginTop: 0 }}>
        Registering a table exposes <strong>all</strong> of its columns to officers. Use this table
        to give individual columns a business name, mark them fuzzy-matchable, or hide them. Columns with
        no entry here stay visible and fall back to an auto-derived label and a name-substring guess for
        fuzzy matching — which is exactly what <strong>Delete</strong> reverts a column to.
        <br />
        <strong>Compare as</strong> only matters when a match puts this column against one of a{" "}
        <em>different</em> type — an account number stored <code>varchar</code> in one table and{" "}
        <code>bigint</code> in another. SRSE casts the pair rather than letting the query fail;{" "}
        <em>Auto</em> compares such a pair as <strong>numbers</strong> (so <code>0123</code> still
        matches <code>123</code>), and setting <em>Text</em> on either side forces the text reading
        instead. Two columns of the same type are unaffected.
      </p>

      {error && <p className="srse-text-danger">{error}</p>}
      {loading && rows.length === 0 && !error ? <p className="srse-text-muted">Loading…</p> : null}

      {rows.length > 0 && (
        <div style={{ overflowX: "auto", marginBottom: "1.25rem" }}>
          <table className="srse-table">
            <thead>
              <tr>
                <th>Catalog › Schema › Table</th>
                <th>Column</th>
                <th>Business name</th>
                <th>Fuzzy matchable</th>
                <th>Compare as</th>
                <th>Value list</th>
                <th>Visible</th>
                <th>Mask</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <ColumnMetadataRowEditor
                  key={`${row.catalog}.${row.schema}.${row.table}.${row.column}`}
                  row={row}
                  orphaned={!registeredTables.has(`${row.catalog}.${row.schema}.${row.table}`)}
                  onChanged={onChanged}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!(loading && rows.length === 0 && registrations.length === 0) && (
        <>
          <h3 className="srse-subheading" style={{ fontSize: "0.95rem" }}>
            Register column metadata
          </h3>
          <RegisterColumnMetadataForm registrations={registrations} loading={loading} onCreated={onChanged} />
        </>
      )}
    </section>
  );
}

/**
 * One registered table, editable in place.
 *
 * Only the layer tag is editable. The catalog/schema/table triple IS the
 * address every mapping, column-metadata row and saved ruleset refers to, so
 * "editing" a registration into a different table would silently orphan all of
 * them — retargeting is Delete + register, which is why both buttons are here.
 */
function RegistrationRow({
  registration,
  curatedColumnCount,
  layerOptions,
  sourceSystemSuggestions,
  tableGroupSuggestions,
  onChanged,
  onError,
}: Readonly<{
  registration: TableRegistration;
  curatedColumnCount: number;
  layerOptions: string[];
  sourceSystemSuggestions: string[];
  tableGroupSuggestions: string[];
  onChanged: () => void;
  onError: (message: string) => void;
}>) {
  const [editing, setEditing] = useState(false);
  const [layer, setLayer] = useState(registration.layer ?? "");
  const [sourceSystem, setSourceSystem] = useState(registration.sourceSystem ?? "");
  const [tableGroup, setTableGroup] = useState(registration.tableGroup ?? "");
  const [saving, setSaving] = useState(false);

  function startEditing() {
    setLayer(registration.layer ?? "");
    setSourceSystem(registration.sourceSystem ?? "");
    setTableGroup(registration.tableGroup ?? "");
    setEditing(true);
  }

  async function onSave() {
    setSaving(true);
    try {
      await updateTableRegistration(registration.id, {
        layer: layer.trim(),
        sourceSystem: sourceSystem.trim() || null,
        tableGroup: tableGroup.trim() || null,
      });
      setEditing(false);
      onChanged();
    } catch (err: unknown) {
      onError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr>
      <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{registration.catalog}</td>
      <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{registration.schema}</td>
      <td style={{ fontFamily: "monospace", fontSize: "0.82rem" }}>{registration.table}</td>
      <td>
        {editing ? (
          <select
            aria-label={`Layer for ${registration.qualifiedName}`}
            value={layer}
            onChange={(e) => setLayer(e.target.value)}
            className="srse-select"
            style={{ width: 140 }}
          >
            <option value="">— layer —</option>
            {layerOptions.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        ) : (
          <>
            {registration.layer ? (
              <span className="srse-badge">{registration.layer}</span>
            ) : (
              <span className="srse-text-muted">Untagged</span>
            )}
          </>
        )}
      </td>
      <td style={{ fontSize: "0.82rem" }}>
        {editing ? (
          <>
            <input
              list={`${registration.id}-ss-suggest`}
              className="srse-input"
              style={{ width: 140 }}
              value={sourceSystem}
              onChange={(e) => setSourceSystem(e.target.value)}
              placeholder="Optional"
            />
            <datalist id={`${registration.id}-ss-suggest`}>
              {sourceSystemSuggestions.map((s) => (
                <option key={s} value={s} />
              ))}
            </datalist>
          </>
        ) : (
          registration.sourceSystem ?? <span className="srse-text-muted">—</span>
        )}
      </td>
      <td style={{ fontSize: "0.82rem" }}>
        {editing ? (
          <>
            <input
              list={`${registration.id}-tg-suggest`}
              className="srse-input"
              style={{ width: 140 }}
              value={tableGroup}
              onChange={(e) => setTableGroup(e.target.value)}
              placeholder="Optional"
            />
            <datalist id={`${registration.id}-tg-suggest`}>
              {tableGroupSuggestions.map((g) => (
                <option key={g} value={g} />
              ))}
            </datalist>
          </>
        ) : (
          registration.tableGroup ?? <span className="srse-text-muted">—</span>
        )}
      </td>
      <td>
        <div style={{ display: "flex", gap: "0.4rem", alignItems: "center", flexWrap: "wrap" }}>
          {editing ? (
            <>
              <button
                type="button"
                className="srse-btn srse-btn-sm"
                disabled={saving}
                onClick={onSave}
              >
                {saving ? "Saving…" : "Save"}
              </button>
              <button
                type="button"
                className="srse-btn srse-btn-ghost srse-btn-sm"
                disabled={saving}
                onClick={() => setEditing(false)}
              >
                Cancel
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="srse-btn srse-btn-ghost srse-btn-sm"
                onClick={startEditing}
                title="Edit display tags (layer, source system, table group). The catalog/schema/table address cannot be edited — delete and register the other table instead."
              >
                Edit
              </button>
              <TableScopeBindingEditor registration={registration} onSaved={onChanged} />
              <a
                className="srse-btn srse-btn-ghost srse-btn-sm"
                href={`/admin456?section=lineage&table=${encodeURIComponent(registration.qualifiedName)}`}
              >
                View lineage
              </a>
              <ConfirmDeleteButton
                idleLabel="Delete"
                confirmLabel="Yes, unregister"
                title="Officers stop seeing this table. Nothing in the lakehouse is touched, and its column settings are kept in case you register it again."
                onConfirm={() => unregisterTable(registration.id).then(onChanged)}
                onError={onError}
              />
              {curatedColumnCount > 0 && (
                <span className="srse-text-muted" style={{ fontSize: "0.72rem" }}>
                  {curatedColumnCount} column setting{curatedColumnCount === 1 ? "" : "s"}
                </span>
              )}
            </>
          )}
        </div>
      </td>
    </tr>
  );
}

function RegistryLabelRenamePanel({
  sourceSystemSuggestions,
  tableGroupSuggestions,
  onRenamed,
  onError,
}: Readonly<{
  sourceSystemSuggestions: string[];
  tableGroupSuggestions: string[];
  onRenamed: () => void;
  onError: (message: string) => void;
}>) {
  const [ssFrom, setSsFrom] = useState("");
  const [ssTo, setSsTo] = useState("");
  const [tgFrom, setTgFrom] = useState("");
  const [tgTo, setTgTo] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  async function onRenameSourceSystem() {
    if (!ssFrom.trim() || !ssTo.trim()) return;
    setBusy(true);
    setNotice(null);
    try {
      const { tablesUpdated } = await renameSourceSystemLabel(ssFrom.trim(), ssTo.trim());
      setSsFrom("");
      setSsTo("");
      onRenamed();
      setNotice(`Renamed source system on ${tablesUpdated} table(s).`);
    } catch (err: unknown) {
      onError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function onRenameTableGroup() {
    if (!tgFrom.trim() || !tgTo.trim()) return;
    setBusy(true);
    setNotice(null);
    try {
      const { tablesUpdated } = await renameTableGroupLabel(tgFrom.trim(), tgTo.trim());
      setTgFrom("");
      setTgTo("");
      onRenamed();
      setNotice(`Renamed table group on ${tablesUpdated} table(s).`);
    } catch (err: unknown) {
      onError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ marginTop: "1.25rem", paddingTop: "1rem", borderTop: "1px solid var(--srse-border)" }}>
      <h3 className="srse-card-title" style={{ fontSize: "1rem" }}>Bulk rename display labels</h3>
      <p className="srse-text-muted" style={{ fontSize: "0.78rem", maxWidth: "none" }}>
        Updates every registration carrying the old label. Audited once per rename.
      </p>
      {notice && <p className="srse-text-muted" style={{ fontSize: "0.78rem" }}>{notice}</p>}
      <div style={{ display: "flex", gap: "1rem", flexWrap: "wrap", marginTop: "0.75rem" }}>
        <div style={{ display: "flex", gap: "0.4rem", flexWrap: "wrap", alignItems: "flex-end" }}>
          <div>
            <label className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>Source system from</label>
            <input list="rename-ss-from" className="srse-input" value={ssFrom} onChange={(e) => setSsFrom(e.target.value)} />
            <datalist id="rename-ss-from">{sourceSystemSuggestions.map((s) => <option key={s} value={s} />)}</datalist>
          </div>
          <div>
            <label className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>to</label>
            <input list="rename-ss-to" className="srse-input" value={ssTo} onChange={(e) => setSsTo(e.target.value)} />
            <datalist id="rename-ss-to">{sourceSystemSuggestions.map((s) => <option key={s} value={s} />)}</datalist>
          </div>
          <button type="button" className="srse-btn srse-btn-sm" disabled={busy || !ssFrom.trim() || !ssTo.trim()} onClick={onRenameSourceSystem}>
            Rename source systems
          </button>
        </div>
        <div style={{ display: "flex", gap: "0.4rem", flexWrap: "wrap", alignItems: "flex-end" }}>
          <div>
            <label className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>Table group from</label>
            <input list="rename-tg-from" className="srse-input" value={tgFrom} onChange={(e) => setTgFrom(e.target.value)} />
            <datalist id="rename-tg-from">{tableGroupSuggestions.map((g) => <option key={g} value={g} />)}</datalist>
          </div>
          <div>
            <label className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>to</label>
            <input list="rename-tg-to" className="srse-input" value={tgTo} onChange={(e) => setTgTo(e.target.value)} />
            <datalist id="rename-tg-to">{tableGroupSuggestions.map((g) => <option key={g} value={g} />)}</datalist>
          </div>
          <button type="button" className="srse-btn srse-btn-sm" disabled={busy || !tgFrom.trim() || !tgTo.trim()} onClick={onRenameTableGroup}>
            Rename table groups
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Catalog → Schema → Table registration. This is the seam between everything
 * the Presto connection can physically reach and what officers are actually
 * offered: the cascade browses the live lakehouse, and registering pins the
 * chosen table into DB2.
 *
 * Columns are deliberately NOT part of a registration — they are re-read live
 * on every use, so a column added upstream appears without re-registration
 * and a dropped one disappears instead of lingering as a broken reference.
 */
export function LakehouseRegistryPanel({
  registrations,
  columnMetadata,
  loading,
  error,
  onChanged,
}: Readonly<{
  registrations: TableRegistration[];
  columnMetadata: ColumnMetadata[];
  loading: boolean;
  error: string | null;
  onChanged: () => void;
}>) {
  const [cascade, setCascade] = useState<CascadeValue>(EMPTY_CASCADE);
  const [layer, setLayer] = useState("");
  const [sourceSystem, setSourceSystem] = useState("");
  const [tableGroup, setTableGroup] = useState("");
  const [layerOptions, setLayerOptions] = useState<string[]>([]);
  const [sourceSystemSuggestions, setSourceSystemSuggestions] = useState<string[]>([]);
  const [tableGroupSuggestions, setTableGroupSuggestions] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const onCascadeError = useCallback((message: string) => setFormError(message), []);

  const reloadLabelVocabulary = useCallback(() => {
    listLakehouseLayers()
      .then(setLayerOptions)
      .catch((err: unknown) => setFormError(errorMessage(err)));
    listAdminSourceSystems()
      .then(setSourceSystemSuggestions)
      .catch((err: unknown) => setFormError(errorMessage(err)));
    listAdminTableGroups()
      .then(setTableGroupSuggestions)
      .catch((err: unknown) => setFormError(errorMessage(err)));
  }, []);

  useEffect(() => {
    reloadLabelVocabulary();
  }, [reloadLabelVocabulary]);

  // How many column settings each table carries, so unregistering says what
  // curation it is putting out of reach (the rows are kept, not deleted).
  const curatedByTable = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of columnMetadata) {
      const key = `${m.catalog}.${m.schema}.${m.table}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  }, [columnMetadata]);

  const alreadyRegistered = useMemo(
    () =>
      isCascadeComplete(cascade) &&
      registrations.some((r) => r.qualifiedName === qualified(cascade)),
    [cascade, registrations],
  );

  async function onRegister() {
    if (!isCascadeComplete(cascade)) return;
    setSaving(true);
    setFormError(null);
    try {
      await registerTable({
        ...cascade,
        layer: layer.trim(),
        sourceSystem: sourceSystem.trim() || null,
        tableGroup: tableGroup.trim() || null,
      });
      setCascade(EMPTY_CASCADE);
      setLayer("");
      setSourceSystem("");
      setTableGroup("");
      reloadLabelVocabulary();
      onChanged();
    } catch (err: unknown) {
      setFormError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="srse-card">
      <h2 className="srse-card-title">Lakehouse registry — Catalog › Schema › Table</h2>
      <p className="srse-page-description" style={{ maxWidth: "none", marginTop: 0 }}>
        Browse the live lakehouse and register the tables SRSE may use. Scoped officers only see registered
        tables that have scope bindings (or dimension exemptions or shared-reference) for every dimension they
        are assigned in — otherwise the table is hidden, not unfiltered. Use Scope bindings on each row.
        Registering exposes all live columns; hide individual ones from Column Settings. Tag each
        table with its layer (<code>BRONZE</code>, <code>SILVER</code>, <code>GOLD</code>, or another
        display tag) so the same table name in two layers stays distinguishable. Older registrations
        without a tag remain reachable in Analysis under <strong>Untagged</strong>.{" "}
        <strong>Edit</strong> re-tags that layer; <strong>Delete</strong> withdraws the table from officers
        without touching anything in the lakehouse, and keeps its column settings in case you register it
        again.
      </p>

      {error && <p className="srse-text-danger">{error}</p>}
      {formError && <p className="srse-text-danger">{formError}</p>}

      <div style={{ display: "flex", gap: "0.75rem", flexWrap: "wrap", alignItems: "flex-end", marginBottom: "1.1rem" }}>
        <LakehouseCascade
          value={cascade}
          onChange={setCascade}
          fetchers={BROWSE_FETCHERS}
          idPrefix="register-table"
          onError={onCascadeError}
        />
        <div>
          <label htmlFor="register-layer" className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>
            Layer (optional)
          </label>
          <select
            id="register-layer"
            value={layer}
            onChange={(e) => setLayer(e.target.value)}
            className="srse-select"
            style={{ width: 140 }}
          >
            <option value="">— layer —</option>
            {layerOptions.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="register-source-system" className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>
            Source system
          </label>
          <input
            id="register-source-system"
            list="register-source-system-suggest"
            className="srse-input"
            style={{ width: 160 }}
            value={sourceSystem}
            onChange={(e) => setSourceSystem(e.target.value)}
            placeholder="Optional"
          />
          <datalist id="register-source-system-suggest">
            {sourceSystemSuggestions.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        </div>
        <div>
          <label htmlFor="register-table-group" className="srse-text-muted" style={{ fontSize: "0.72rem", display: "block" }}>
            Table group
          </label>
          <input
            id="register-table-group"
            list="register-table-group-suggest"
            className="srse-input"
            style={{ width: 160 }}
            value={tableGroup}
            onChange={(e) => setTableGroup(e.target.value)}
            placeholder="Optional"
          />
          <datalist id="register-table-group-suggest">
            {tableGroupSuggestions.map((g) => (
              <option key={g} value={g} />
            ))}
          </datalist>
        </div>
        <button
          type="button"
          className="srse-btn srse-btn-primary"
          disabled={saving || !isCascadeComplete(cascade)}
          onClick={onRegister}
        >
          {registerButtonLabel(saving, alreadyRegistered)}
        </button>
      </div>

      {isCascadeComplete(cascade) && (
        <p className="srse-text-muted" style={{ fontFamily: "monospace", fontSize: "0.8rem", marginTop: 0 }}>
          → {qualified(cascade)}
          {alreadyRegistered ? " (already registered — this will re-tag its layer)" : ""}
        </p>
      )}

      {loading && <p className="srse-text-muted">Loading registrations…</p>}

      {!loading && registrations.length === 0 && (
        <p className="srse-text-muted">
          Nothing registered yet — officers will see no tables in the Analysis tab until you register one.
        </p>
      )}

      <RegistryLabelRenamePanel
        sourceSystemSuggestions={sourceSystemSuggestions}
        tableGroupSuggestions={tableGroupSuggestions}
        onRenamed={() => {
          reloadLabelVocabulary();
          onChanged();
        }}
        onError={setFormError}
      />

      {registrations.length > 0 && (
        <div style={{ overflowX: "auto" }}>
          <table className="srse-table">
            <thead>
              <tr>
                <th>Catalog</th>
                <th>Schema</th>
                <th>Table</th>
                <th>Layer</th>
                <th>Source system</th>
                <th>Table group</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {registrations.map((r) => (
                <RegistrationRow
                  key={r.id}
                  registration={r}
                  curatedColumnCount={curatedByTable.get(r.qualifiedName) ?? 0}
                  layerOptions={layerOptions}
                  sourceSystemSuggestions={sourceSystemSuggestions}
                  tableGroupSuggestions={tableGroupSuggestions}
                  onChanged={() => {
                    reloadLabelVocabulary();
                    onChanged();
                  }}
                  onError={setFormError}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function ActiveQueryTable({ rows, emptyLabel }: Readonly<{ rows: ActiveQueryView[]; emptyLabel: string }>) {
  if (rows.length === 0) {
    return <p className="srse-text-muted">{emptyLabel}</p>;
  }
  return (
    <div style={{ overflowX: "auto" }}>
      <table className="srse-table">
        <thead>
          <tr>
            <th>User</th>
            <th>Kind</th>
            <th>Tables</th>
            <th>Started</th>
            <th>Duration</th>
            <th>State</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.queryId + row.startedAt}>
              <td>{row.username}</td>
              <td>{row.kind}</td>
              <td style={{ fontFamily: "monospace", fontSize: "0.85rem" }}>{row.qualifiedTables}</td>
              <td>{row.startedAt}</td>
              <td>{row.durationMs ?? "—"}</td>
              <td>{row.state}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ActiveQueriesAdminPanel() {
  const [running, setRunning] = useState<ActiveQueryView[]>([]);
  const [recent, setRecent] = useState<ActiveQueryView[]>([]);
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const data = await getActiveQueries();
        if (cancelled) return;
        setRunning(data.running);
        setRecent(data.recent);
        setNote(data.instanceNote);
        setError(null);
      } catch (err: unknown) {
        if (!cancelled) setError(errorMessage(err));
      }
    }
    void poll();
    const id = window.setInterval(() => void poll(), 5000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  return (
    <section className="srse-card" style={{ marginTop: "1.5rem" }}>
      <h3 className="srse-card-title" style={{ marginTop: 0 }}>
        Active queries
      </h3>
      <p className="srse-text-muted">
        Analytical work started on this backend instance (refreshes every 5 seconds while this section is open).
      </p>
      {note ? <p className="srse-text-muted" style={{ fontSize: "0.85rem" }}>{note}</p> : null}
      {error ? <p className="srse-text-danger">{error}</p> : null}
      <h4 style={{ marginBottom: "0.35rem" }}>Running</h4>
      <ActiveQueryTable rows={running} emptyLabel="No queries running or recently finished on this instance." />
      <h4 style={{ marginTop: "1rem", marginBottom: "0.35rem" }}>Recent</h4>
      <ActiveQueryTable rows={recent} emptyLabel="No queries running or recently finished on this instance." />
    </section>
  );
}

export function MetadataSnapshotAdminPanel() {
  const [status, setStatus] = useState<MetadataSyncRunStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setStatus(await getMetadataSyncStatus());
      setError(null);
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function onSyncNow() {
    setSyncing(true);
    setMessage(null);
    setError(null);
    try {
      const started = await startMetadataSync();
      setMessage(started.skippedOverlapping ? "Skipped — a sync is already running." : "Sync started.");
      await reload();
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setSyncing(false);
    }
  }

  async function onDownloadManifest() {
    setError(null);
    try {
      const json = await downloadMetadataManifest();
      const blob = new Blob([json], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `metadata-manifest-${new Date().toISOString().slice(0, 10)}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      setError(errorMessage(err));
    }
  }

  return (
    <section className="srse-card" style={{ marginTop: "1.5rem" }}>
      <h3 className="srse-card-title" style={{ marginTop: 0 }}>
        Column metadata sync
      </h3>
      <p className="srse-text-muted">
        Refresh stored column names and types for all registered tables (Super Admin only).
      </p>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", marginBottom: "0.75rem" }}>
        <button type="button" className="srse-btn srse-btn-primary" disabled={syncing} onClick={() => void onSyncNow()}>
          {syncing ? "Sync started…" : "Sync now"}
        </button>
        <button type="button" className="srse-btn srse-btn-secondary" onClick={() => void onDownloadManifest()}>
          Download JSON manifest
        </button>
      </div>
      {message && <p className="srse-text-muted">{message}</p>}
      {error && <p className="srse-text-danger">{error}</p>}
      {loading && <p className="srse-text-muted">Loading last run…</p>}
      {!loading && status?.runId != null && (
        <p className="srse-text-muted" style={{ fontFamily: "monospace", fontSize: "0.85rem" }}>
          Last run #{status.runId} · {status.trigger ?? "—"} · {status.outcome ?? "—"}
          {status.startedAt ? ` · started ${status.startedAt}` : ""}
          {status.finishedAt ? ` · finished ${status.finishedAt}` : ""}
        </p>
      )}
    </section>
  );
}

export function ConfigBackupPanel({ onImported }: Readonly<{ onImported: () => void }>) {
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [skipConnectionTest, setSkipConnectionTest] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function onDownload() {
    setExporting(true);
    setError(null);
    setMessage(null);
    try {
      const bundle = await exportAdminConfig();
      const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `analytics-advisor-config-${new Date().toISOString().slice(0, 10)}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
      setMessage(
        "Configuration downloaded. Passwords are not included — re-enter DB2 and Presto passwords once after import.",
      );
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setExporting(false);
    }
  }

  async function onUpload(file: File) {
    setImporting(true);
    setError(null);
    setMessage(null);
    try {
      const text = await file.text();
      const bundle = JSON.parse(text) as AdminConfigBundle;
      const result: AdminConfigImportResult = await importAdminConfig(bundle, {
        testConnections: !skipConnectionTest,
      });
      const summary = [
        `${result.registeredTableCount} table registration(s)`,
        `${result.columnMetadataCount} column override(s)`,
        result.connectionsImported ? "connections updated" : null,
      ]
        .filter(Boolean)
        .join(", ");
      let msg = `Import complete: ${summary}.`;
      if (result.skipped.length > 0) {
        const skippedLines = result.skipped.map(
          (s) => `${s.section}: ${s.reason}`,
        );
        msg += ` Not imported from this file — ${skippedLines.join("; ")}.`;
      }
      msg += " JDBC passwords were not in the file; re-enter them on the Connections panel if needed.";
      if (result.operationalRestartRequired) {
        msg += " Restart the backend container for DB2 connection changes to take effect.";
      }
      setMessage(msg);
      onImported();
    } catch (err: unknown) {
      setError(errorMessage(err));
    } finally {
      setImporting(false);
    }
  }

  return (
    <section className="srse-card" style={{ marginBottom: "1.25rem" }}>
      <h2 className="srse-section-title">Configuration backup</h2>
      <p className="srse-text-muted" style={{ marginBottom: "0.85rem", maxWidth: "52rem" }}>
        Download a JSON snapshot of connections (without passwords), lakehouse registrations, and
        analysis column settings. After a redeploy, upload the same file to restore them without
        re-entering everything by hand. Re-enter DB2 and Presto passwords once on the Connections
        panel after import.
      </p>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "0.75rem", alignItems: "center" }}>
        <button type="button" className="srse-btn srse-btn-primary" disabled={exporting} onClick={onDownload}>
          {exporting ? "Exporting…" : "Download configuration JSON"}
        </button>
        <label className="srse-btn" style={{ cursor: importing ? "wait" : "pointer", margin: 0 }}>
          {importing ? "Importing…" : "Upload configuration JSON"}
          <input
            type="file"
            accept="application/json,.json"
            disabled={importing}
            style={{ display: "none" }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = "";
              if (file) void onUpload(file);
            }}
          />
        </label>
        <label className="srse-checkbox-label" style={{ fontSize: "0.85rem" }}>
          <input
            type="checkbox"
            checked={skipConnectionTest}
            onChange={(e) => setSkipConnectionTest(e.target.checked)}
          />
          {" "}
          Skip connection test on import (save credentials only — use when DB2/Presto are not up yet)
        </label>
      </div>
      {message && <p className="srse-text-success" style={{ marginTop: "0.75rem" }}>{message}</p>}
      {error && <p className="srse-text-danger" style={{ marginTop: "0.75rem" }}>{error}</p>}
    </section>
  );
}
