package gov.rajasthan.smart.srse.lakehouse;

import gov.rajasthan.smart.srse.audit.AuditActionType;
import gov.rajasthan.smart.srse.audit.AuditService;
import gov.rajasthan.smart.srse.identity.AuthenticatedUserService;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceMetadataService;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceService;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceType;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.metadata.sync.MetadataSnapshotService;
import gov.rajasthan.smart.srse.metadata.sync.MetadataSyncService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The registered subset of the lakehouse — the officer-facing allow-list.
 *
 * <p>Two collaborators, two different jobs, and the distinction is the whole
 * point of this class:
 * <ul>
 *   <li>{@link LakehouseBrowseService} answers "what does the lakehouse
 *       physically contain right now?" — live, uncached, everything the
 *       connection can reach. Only the Admin cascade browses it.</li>
 *   <li>{@link RegisteredTableRepository} answers "what has an admin chosen
 *       to expose?" — persisted in DB2, small, stable. Everything
 *       officer-facing reads this.</li>
 * </ul>
 *
 * <p>Registration is at TABLE granularity and columns are never copied into
 * DB2: {@link #listColumns} re-reads the live column list on every call and
 * merely decorates it with admin metadata. So a column added upstream appears
 * without re-registration, and a column dropped upstream disappears instead
 * of lingering as a stale row that would compile into a broken query.
 *
 * <p><b>Two gates, not one.</b> {@link #validateColumn} checks BOTH that the
 * table is registered AND that the column really exists in the lakehouse. The
 * registry alone is not sufficient — it is a snapshot of an admin's intent and
 * can name a table that has since been dropped — and the live schema alone is
 * not sufficient either, since it would let an officer reach any table on the
 * cluster. Both must agree before an identifier reaches SQL text.
 */
@Service
public class LakehouseRegistryService {

    static final String NOT_VISIBLE_IN_SCOPE =
            "Table is not visible in your scope — bind scope columns, exempt dimensions, or mark shared reference";

    private final RegisteredTableRepository registrations;
    private final AnalysisColumnMetadataRepository columnMetadata;
    private final LakehouseBrowseService browse;
    private final OfficerRegistryScopeService officerScope;
    private final RegisteredTableScopeCatalog scopeCatalog;
    private final TableScopeRegistrationService tableScopeRegistrationService;
    private final AuthenticatedUserService authenticatedUserService;
    private final AuditService auditService;
    private final ExternalDataSourceMetadataService externalMetadata;
    private final ExternalDataSourceService externalSources;
    private final MetadataSnapshotService metadataSnapshotService;
    private final MetadataSyncService metadataSyncService;

    @Autowired
    public LakehouseRegistryService(
            RegisteredTableRepository registrations,
            AnalysisColumnMetadataRepository columnMetadata,
            LakehouseBrowseService browse,
            OfficerRegistryScopeService officerScope,
            RegisteredTableScopeCatalog scopeCatalog,
            TableScopeRegistrationService tableScopeRegistrationService,
            AuthenticatedUserService authenticatedUserService,
            AuditService auditService,
            ExternalDataSourceMetadataService externalMetadata,
            ExternalDataSourceService externalSources,
            @Autowired(required = false) MetadataSnapshotService metadataSnapshotService,
            @Autowired(required = false) MetadataSyncService metadataSyncService) {
        this.registrations = registrations;
        this.columnMetadata = columnMetadata;
        this.browse = browse;
        this.officerScope = officerScope;
        this.scopeCatalog = scopeCatalog;
        this.tableScopeRegistrationService = tableScopeRegistrationService;
        this.authenticatedUserService = authenticatedUserService;
        this.auditService = auditService;
        this.externalMetadata = externalMetadata;
        this.externalSources = externalSources;
        this.metadataSnapshotService = metadataSnapshotService;
        this.metadataSyncService = metadataSyncService;
    }

    /** Test-compatible constructor for native-Presto registry scenarios. */
    public LakehouseRegistryService(
            RegisteredTableRepository registrations,
            AnalysisColumnMetadataRepository columnMetadata,
            LakehouseBrowseService browse,
            OfficerRegistryScopeService officerScope,
            RegisteredTableScopeCatalog scopeCatalog,
            TableScopeRegistrationService tableScopeRegistrationService,
            AuthenticatedUserService authenticatedUserService,
            AuditService auditService) {
        this(registrations, columnMetadata, browse, officerScope, scopeCatalog,
                tableScopeRegistrationService, authenticatedUserService, auditService, null, null, null, null);
    }

    /** Test-compatible constructor for external-source registration scenarios. */
    public LakehouseRegistryService(
            RegisteredTableRepository registrations,
            AnalysisColumnMetadataRepository columnMetadata,
            LakehouseBrowseService browse,
            OfficerRegistryScopeService officerScope,
            RegisteredTableScopeCatalog scopeCatalog,
            TableScopeRegistrationService tableScopeRegistrationService,
            AuthenticatedUserService authenticatedUserService,
            AuditService auditService,
            ExternalDataSourceMetadataService externalMetadata,
            ExternalDataSourceService externalSources) {
        this(registrations, columnMetadata, browse, officerScope, scopeCatalog,
                tableScopeRegistrationService, authenticatedUserService, auditService,
                externalMetadata, externalSources, null, null);
    }

    // ---- admin: registration ----

    /**
     * Registers {@code catalog.schema.table}, or updates its layer tag if it
     * is already registered. Validated against the LIVE lakehouse first, so a
     * typo'd or non-existent table can never enter the registry and become an
     * officer-visible dead end.
     */
    @Transactional
    public RegisteredTable register(String catalog, String schema, String table, String layer) {
        return register(catalog, schema, table, layer, null, null);
    }

    @Transactional
    public RegisteredTable register(
            String catalog,
            String schema,
            String table,
            String layer,
            String sourceSystem,
            String tableGroup) {
        browse.validateTable(catalog, schema, table);
        String source = RegistryDisplayTags.normaliseOptional(sourceSystem);
        String group = RegistryDisplayTags.normaliseOptional(tableGroup);
        RegisteredTable existing = registrations
                .findByCatalogNameAndSchemaNameAndTableName(catalog, schema, table)
                .orElse(null);
        if (existing != null) {
            existing.setLayer(LakehouseLayers.normaliseOptional(layer));
            existing.setSourceSystem(source);
            existing.setTableGroup(group);
            RegisteredTable saved = registrations.save(existing);
            auditService.recordRegistryEvent(
                    AuditActionType.TABLE_REGISTERED,
                    authenticatedUserService.requireCurrentUser(),
                    saved.toQualifiedTable().qualifiedName(),
                    "registration updated");
            triggerMetadataSync(saved);
            return saved;
        }
        RegisteredTable saved = registrations.save(new RegisteredTable(
                null, catalog, schema, table, LakehouseLayers.normaliseOptional(layer), false, source, group));
        auditService.recordRegistryEvent(
                AuditActionType.TABLE_REGISTERED,
                authenticatedUserService.requireCurrentUser(),
                saved.toQualifiedTable().qualifiedName(),
                "registered");
        triggerMetadataSync(saved);
        return saved;
    }

    /** Registers a table discovered through a saved JDBC source. */
    @Transactional
    public RegisteredTable registerExternal(
            long sourceId,
            String physicalCatalog,
            String schema,
            String table,
            String layer,
            String sourceSystem,
            String tableGroup) {
        if (externalMetadata == null || externalSources == null) {
            throw new IllegalStateException("External data-source registry is unavailable");
        }
        var source = externalSources.requireForBrowse(sourceId);
        String tableName = table == null ? "" : table.trim();
        String requestedSchema = schema == null ? "" : schema.trim();
        String storedCatalog = normalisePhysicalCatalog(physicalCatalog);
        String logicalSchema = requestedSchema;
        if (logicalSchema.isEmpty()
                && (source.getDatabaseType() == ExternalDataSourceType.MYSQL
                    || source.getDatabaseType() == ExternalDataSourceType.MARIADB)) {
            logicalSchema = storedCatalog == null ? "" : storedCatalog;
        }
        LakehouseIdentifiers.requireSafe("schema", logicalSchema);
        LakehouseIdentifiers.requireSafe("table", tableName);
        externalMetadata.validateRegisteredTableCandidate(
                sourceId, storedCatalog, requestedSchema.isEmpty() ? null : requestedSchema, tableName);
        String logicalCatalog = "jdbc_" + sourceId;
        String requestedSourceLabel = RegistryDisplayTags.normaliseOptional(sourceSystem);
        String sourceLabel = requestedSourceLabel == null ? source.getName() : requestedSourceLabel;
        String group = RegistryDisplayTags.normaliseOptional(tableGroup);
        String schemaForRegistration = logicalSchema;
        RegisteredTable registration = registrations
                .findByCatalogNameAndSchemaNameAndTableName(logicalCatalog, schemaForRegistration, tableName)
                .orElseGet(() -> new RegisteredTable(
                        null, logicalCatalog, schemaForRegistration, tableName,
                        LakehouseLayers.normaliseOptional(layer), false, sourceLabel, group));
        if (registration.getExternalDataSourceId() != null
                && registration.getExternalDataSourceId() != sourceId) {
            throw new IllegalStateException("Logical table address is already attached to another source");
        }
        registration.setLayer(LakehouseLayers.normaliseOptional(layer));
        registration.setSourceSystem(sourceLabel);
        registration.setTableGroup(group);
        registration.attachExternalSource(sourceId, storedCatalog);
        RegisteredTable saved = registrations.save(registration);
        auditService.recordRegistryEvent(
                AuditActionType.TABLE_REGISTERED,
                authenticatedUserService.requireCurrentUser(),
                saved.toQualifiedTable().qualifiedName(),
                "external source=" + source.getName());
        triggerMetadataSync(saved);
        return saved;
    }

    /**
     * Physical catalog is stored for the admin explorer. It is never interpolated
     * into officer SQL — the logical address is {@code jdbc_<id>.schema.table}.
     * Hyphens are allowed here because a database name is not a SQL identifier
     * in the direct-JDBC path. Quotes, semicolons, and line breaks are not.
     */
    private static String normalisePhysicalCatalog(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return null;
        }
        String trimmed = catalog.trim();
        if (trimmed.length() > 128
                || trimmed.indexOf(';') >= 0
                || trimmed.indexOf('\'') >= 0
                || trimmed.indexOf('"') >= 0
                || trimmed.indexOf('\n') >= 0
                || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Catalog contains unsupported characters");
        }
        return trimmed;
    }

    /**
     * Re-tags an existing registration's layer, addressed by id.
     *
     * <p>Only the layer is editable. The catalog/schema/table triple IS the
     * registration's identity — every mapping, column-metadata row and saved
     * ruleset refers to a table by that address, not by this row's id, so
     * "editing" a registration into a different table would silently orphan
     * all of them. Pointing SRSE at another table is unregister + register.
     */
    @Transactional
    public RegisteredTable updateLayer(long id, String layer) {
        RegisteredTable existing = registrations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such registration: " + id));
        existing.setLayer(LakehouseLayers.normaliseOptional(layer));
        return registrations.save(existing);
    }

    /** Updates display tags; address fields are not editable. */
    @Transactional
    public RegisteredTable updateRegistrationTags(
            long id, String layer, String sourceSystem, String tableGroup) {
        RegisteredTable existing = registrations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such registration: " + id));
        existing.setLayer(LakehouseLayers.normaliseOptional(layer));
        existing.setSourceSystem(RegistryDisplayTags.normaliseOptional(sourceSystem));
        existing.setTableGroup(RegistryDisplayTags.normaliseOptional(tableGroup));
        return registrations.save(existing);
    }

    @Transactional
    public int renameSourceSystemLabel(String fromLabel, String toLabel) {
        String from = RegistryDisplayTags.normaliseOptional(fromLabel);
        String to = RegistryDisplayTags.normaliseOptional(toLabel);
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both labels are required for rename");
        }
        if (from.equals(to)) {
            return 0;
        }
        int updated = registrations.bulkRenameSourceSystem(from, to);
        if (updated > 0) {
            auditService.recordRegistryEvent(
                    AuditActionType.REGISTRY_LABEL_RENAMED,
                    authenticatedUserService.requireCurrentUser(),
                    "sourceSystem",
                    from + " → " + to + " (" + updated + " tables)");
        }
        return updated;
    }

    @Transactional
    public int renameTableGroupLabel(String fromLabel, String toLabel) {
        String from = RegistryDisplayTags.normaliseOptional(fromLabel);
        String to = RegistryDisplayTags.normaliseOptional(toLabel);
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both labels are required for rename");
        }
        if (from.equals(to)) {
            return 0;
        }
        int updated = registrations.bulkRenameTableGroup(from, to);
        if (updated > 0) {
            auditService.recordRegistryEvent(
                    AuditActionType.REGISTRY_LABEL_RENAMED,
                    authenticatedUserService.requireCurrentUser(),
                    "tableGroup",
                    from + " → " + to + " (" + updated + " tables)");
        }
        return updated;
    }

    @Transactional
    public void unregister(long id) {
        RegisteredTable existing = registrations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such registration: " + id));
        String qualified = existing.toQualifiedTable().qualifiedName();
        deleteMetadataSnapshots(existing);
        tableScopeRegistrationService.deleteScopeDataForRegistration(id);
        registrations.deleteById(id);
        browse.invalidateMetadata();
        auditService.recordRegistryEvent(
                AuditActionType.TABLE_UNREGISTERED,
                authenticatedUserService.requireCurrentUser(),
                qualified,
                "unregistered");
    }

    /**
     * Config-import path only — persists a registration without live
     * lakehouse validation so an admin can restore the allow-list before
     * Presto is reachable. Normal admin UI flows must keep using
     * {@link #register}.
     */
    @Transactional
    public RegisteredTable importRegistration(String catalog, String schema, String table, String layer) {
        RegisteredTable existing = registrations
                .findByCatalogNameAndSchemaNameAndTableName(catalog, schema, table)
                .orElse(null);
        if (existing != null) {
            existing.setLayer(LakehouseLayers.normaliseForImport(layer));
            return registrations.save(existing);
        }
        RegisteredTable saved = registrations.save(
                new RegisteredTable(null, catalog, schema, table, LakehouseLayers.normaliseForImport(layer)));
        triggerMetadataSync(saved);
        return saved;
    }

    public List<RegisteredTable> listRegistrations() {
        return allOrdered();
    }

    public List<RegisteredTable> listExternalRegistrations(long sourceId) {
        return allOrdered().stream()
                .filter(row -> row.getExternalDataSourceId() != null
                        && row.getExternalDataSourceId() == sourceId)
                .toList();
    }

    private List<RegisteredTable> allOrdered() {
        return registrations.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAsc();
    }

    List<RegisteredTable> filtered(RegistryBrowseFilter filter) {
        List<RegisteredTable> rows = allOrdered();
        rows = filterByTag(rows, filter.layer(), RegisteredTable::getLayer, LakehouseLayers.UNTAGGED);
        rows = filterByTag(rows, filter.sourceSystem(), RegisteredTable::getSourceSystem, RegistryDisplayTags.UNTAGGED);
        rows = filterByTag(rows, filter.tableGroup(), RegisteredTable::getTableGroup, RegistryDisplayTags.UNTAGGED);
        return rows;
    }

    private static List<RegisteredTable> filterByTag(
            List<RegisteredTable> rows,
            String filter,
            java.util.function.Function<RegisteredTable, String> getter,
            String untaggedSentinel) {
        if (filter == null) {
            return rows;
        }
        if (untaggedSentinel.equals(filter)) {
            return rows.stream().filter(r -> getter.apply(r) == null).toList();
        }
        return rows.stream().filter(r -> filter.equals(getter.apply(r))).toList();
    }

    private List<RegisteredTable> filteredByLayer(String layerFilter) {
        return filtered(new RegistryBrowseFilter(layerFilter, null, null));
    }

    /**
     * Distinct layer tags for the officer cascade, plus {@link LakehouseLayers#UNTAGGED}
     * when any registration has {@code layer IS NULL}.
     */
    public List<String> listLayers() {
        TreeSet<String> distinct = new TreeSet<>();
        boolean hasUntagged = false;
        for (RegisteredTable row : officerVisible(allOrdered())) {
            if (row.getLayer() == null) {
                hasUntagged = true;
            } else {
                distinct.add(row.getLayer());
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>(distinct);
        if (hasUntagged) {
            out.add(LakehouseLayers.UNTAGGED);
        }
        return List.copyOf(out);
    }

    /** Distinct source-system labels for officers (§3.1.2 / 3.1.4). */
    public List<String> listSourceSystems() {
        return distinctOptionalTags(officerVisible(allOrdered()), RegisteredTable::getSourceSystem);
    }

    /** Distinct table-group labels within an optional source-system filter. */
    public List<String> listTableGroups(String sourceSystemFilter) {
        RegistryBrowseFilter filter = new RegistryBrowseFilter(
                null, RegistryDisplayTags.parseFilterParam(sourceSystemFilter), null);
        return distinctOptionalTags(officerVisible(filtered(filter)), RegisteredTable::getTableGroup);
    }

    /**
     * Database Overview table list — same visibility as the Analysis cascade (§3.1.4).
     * Column lists are loaded lazily via {@link #listColumns}.
     */
    public List<OverviewTableSummary> listOverviewTables(String sourceSystem, String tableGroup) {
        RegistryBrowseFilter filter = RegistryBrowseFilter.parse(null, sourceSystem, tableGroup);
        return officerVisible(filtered(filter)).stream().map(OverviewTableSummary::from).toList();
    }

    /** Admin vocabulary autocomplete — all registrations, not scope-filtered. */
    public List<String> listAllSourceSystemLabels() {
        return distinctOptionalTags(allOrdered(), RegisteredTable::getSourceSystem).stream()
                .filter(l -> !RegistryDisplayTags.UNTAGGED.equals(l))
                .toList();
    }

    public List<String> listAllTableGroupLabels() {
        return distinctOptionalTags(allOrdered(), RegisteredTable::getTableGroup).stream()
                .filter(l -> !RegistryDisplayTags.UNTAGGED.equals(l))
                .toList();
    }

    private static List<String> distinctOptionalTags(
            List<RegisteredTable> rows, java.util.function.Function<RegisteredTable, String> getter) {
        TreeSet<String> distinct = new TreeSet<>();
        boolean hasUntagged = false;
        for (RegisteredTable row : rows) {
            String tag = getter.apply(row);
            if (tag == null || tag.isBlank()) {
                hasUntagged = true;
            } else {
                distinct.add(tag);
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>(distinct);
        if (hasUntagged) {
            out.add(RegistryDisplayTags.UNTAGGED);
        }
        return List.copyOf(out);
    }

    public record OverviewTableSummary(
            String catalog,
            String schema,
            String table,
            String qualifiedName,
            String layer,
            String sourceSystem,
            String tableGroup,
            boolean sharedReference) {
        static OverviewTableSummary from(RegisteredTable entity) {
            return new OverviewTableSummary(
                    entity.getCatalogName(),
                    entity.getSchemaName(),
                    entity.getTableName(),
                    entity.toQualifiedTable().qualifiedName(),
                    entity.getLayer(),
                    entity.getSourceSystem(),
                    entity.getTableGroup(),
                    entity.isSharedReference());
        }
    }

    // ---- officer-facing cascade: registered AND scope-visible (§7.2.2a) ----
    //
    // Do NOT audit metadata browsing here — every dropdown open would fire
    // cascade calls and outnumber real query/export events by orders of magnitude (§7.3 A7).

    public List<String> listCatalogs() {
        return listCatalogs(RegistryBrowseFilter.none());
    }

    public List<String> listCatalogs(String layerFilter) {
        return listCatalogs(new RegistryBrowseFilter(layerFilter, null, null));
    }

    public List<String> listCatalogs(RegistryBrowseFilter filter) {
        return officerVisible(filtered(filter)).stream()
                .map(RegisteredTable::getCatalogName)
                .distinct()
                .sorted()
                .toList();
    }

    public List<String> listSchemas(String catalog) {
        return listSchemas(catalog, RegistryBrowseFilter.none());
    }

    public List<String> listSchemas(String catalog, String layerFilter) {
        return listSchemas(catalog, new RegistryBrowseFilter(layerFilter, null, null));
    }

    public List<String> listSchemas(String catalog, RegistryBrowseFilter filter) {
        return officerVisible(filtered(filter)).stream()
                .filter(r -> catalog.equals(r.getCatalogName()))
                .map(RegisteredTable::getSchemaName)
                .distinct()
                .sorted()
                .toList();
    }

    public List<RegisteredTable> listTables(String catalog, String schema) {
        return listTables(catalog, schema, RegistryBrowseFilter.none());
    }

    public List<RegisteredTable> listTables(String catalog, String schema, String layerFilter) {
        return listTables(catalog, schema, new RegistryBrowseFilter(layerFilter, null, null));
    }

    public List<RegisteredTable> listTables(String catalog, String schema, RegistryBrowseFilter filter) {
        List<RegisteredTable> candidates = filter.hasAnyTagFilter()
                ? filtered(filter).stream()
                        .filter(r -> catalog.equals(r.getCatalogName()) && schema.equals(r.getSchemaName()))
                        .sorted(java.util.Comparator.comparing(RegisteredTable::getTableName))
                        .toList()
                : registrations.findByCatalogNameAndSchemaNameOrderByTableName(catalog, schema);
        return officerVisible(candidates);
    }

    /**
     * Officer-facing column list for Query Builder pickers — snapshot when fresh,
     * otherwise live. Execution paths use {@link #listColumnsLive} instead.
     */
    public List<RegisteredColumn> listColumnsForDisplay(String catalog, String schema, String table) {
        RegisteredTable registration = requireVisibleRegistration(catalog, schema, table);
        Map<String, AnalysisColumnMetadata> byColumn = loadColumnMetadata(catalog, schema, table);
        java.util.stream.Stream<LakehouseBrowseService.ColumnInfo> physical;
        if (metadataSnapshotService != null) {
            var snapshot = metadataSnapshotService.freshSnapshot(catalog, schema, table);
            if (snapshot.isPresent()) {
                physical = metadataSnapshotService.toColumnInfos(snapshot.get().columns()).stream();
                return decorateAndFilterVisible(physical, byColumn);
            }
        }
        physical = livePhysicalColumns(registration).stream();
        return decorateAndFilterVisible(physical, byColumn);
    }

    /**
     * Live columns for execution, join-key hints and type coercion — never the snapshot.
     */
    public List<RegisteredColumn> listColumnsLive(String catalog, String schema, String table) {
        RegisteredTable registration = requireVisibleRegistration(catalog, schema, table);
        Map<String, AnalysisColumnMetadata> byColumn = loadColumnMetadata(catalog, schema, table);
        return decorateAndFilterVisible(livePhysicalColumns(registration).stream(), byColumn);
    }

    /**
     * @deprecated pick {@link #listColumnsForDisplay} (Query Builder pickers) or {@link #listColumnsLive}
     *     (anything that feeds SQL). Defaults to LIVE: a caller that forgot to choose must never be handed
     *     a possibly-stale snapshot on an execution path.
     */
    @Deprecated
    public List<RegisteredColumn> listColumns(String catalog, String schema, String table) {
        return listColumnsLive(catalog, schema, table);
    }

    /**
     * Whether a REGISTERED table physically carries all of these columns.
     *
     * <p>Deliberately NOT filtered by visibility, unlike {@link #listColumns}:
     * this answers a question about the table's shape, not about what an
     * officer may pick. The Analysis tab's age filter uses it to decide whether
     * the catalogue's age expression can apply to a side at all, and an admin
     * hiding {@code date_of_birth} from the picker must not silently drop an
     * age filter that the data fully supports.
     *
     * <p>Compared case-insensitively — the column names come from an admin's
     * hand-typed mapping, while the lakehouse reports its own casing.
     */
    public boolean hasColumns(QualifiedTable table, Collection<String> columns) {
        RegisteredTable registration = requireVisibleRegistration(table);
        Set<String> live = livePhysicalColumns(registration).stream()
                .map(c -> c.name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return columns.stream().allMatch(c -> live.contains(c.toLowerCase(java.util.Locale.ROOT)));
    }

    // ---- gates ----

    /** Throws unless an admin has registered {@code catalog.schema.table}. */
    public void validateRegistered(String catalog, String schema, String table) {
        requireVisibleRegistration(catalog, schema, table);
    }

    public RegisteredTable requireVisibleRegistration(String catalog, String schema, String table) {
        LakehouseIdentifiers.requireSafe("catalog", catalog);
        LakehouseIdentifiers.requireSafe("schema", schema);
        LakehouseIdentifiers.requireSafe("table", table);
        RegisteredTable row = registrations
                .findByCatalogNameAndSchemaNameAndTableName(catalog, schema, table)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Table is not registered for SRSE: " + catalog + "." + schema + "." + table));
        assertOfficerVisible(row);
        return row;
    }

    public RegisteredTable requireVisibleRegistration(QualifiedTable table) {
        return requireVisibleRegistration(table.catalog(), table.schema(), table.table());
    }

    public void validateRegistered(QualifiedTable table) {
        validateRegistered(table.catalog(), table.schema(), table.table());
    }

    /**
     * Throws unless the table is registered AND the column exists live AND
     * the admin has not hidden it. This is the single gate every ad-hoc
     * identifier passes through before {@code RecordMatchService} puts it in
     * SQL text.
     *
     * <p>Prefer {@link #validateColumns} when checking several columns of the
     * same table — see there for why.
     */
    public void validateColumn(QualifiedColumn column) {
        validateColumns(column.table(), List.of(column.column()));
    }

    /**
     * Batch form of {@link #validateColumn} for several columns of ONE table.
     *
     * <p>Exists for latency, not convenience. Resolving a table's column list
     * walks the whole hierarchy — {@code SHOW CATALOGS}, then the catalog's
     * schemata, then the schema's tables, then the columns themselves — so
     * validating a match's criteria one at a time issued that walk once per
     * criterion (up to 8 per side, both sides) before the match query even
     * started. Since every criterion on a side shares one table by
     * construction, one walk covers them all.
     */
    public void validateColumns(QualifiedTable table, Collection<String> columns) {
        describeColumns(table, columns);
    }

    /**
     * The same two gates as {@link #validateColumns}, handing back what the
     * check already looked up: each requested column with its LIVE type.
     *
     * <p>Exists so a caller that needs the types — the Analysis match, which
     * has to know whether two columns are comparable before it can emit the
     * join (see {@code TypeCoercion}) — does not walk the catalog/schema/table
     * hierarchy a second time to find out. The walk is the expensive part;
     * validating and describing are the same lookup.
     *
     * @return requested column name → its live description, in request order
     */
    public Map<String, RegisteredColumn> describeColumns(QualifiedTable table, Collection<String> columns) {
        validateRegistered(table);
        Map<String, RegisteredColumn> available = listColumnsLive(
                table.catalog(), table.schema(), table.table()).stream()
                .collect(java.util.stream.Collectors.toMap(RegisteredColumn::name, Function.identity()));
        Map<String, RegisteredColumn> described = new LinkedHashMap<>();
        for (String column : columns) {
            LakehouseIdentifiers.requireSafe("column", column);
            RegisteredColumn found = available.get(column);
            if (found == null) {
                throw new IllegalArgumentException(
                        "Unknown or hidden column: " + table.qualifiedName() + "." + column);
            }
            described.put(column, found);
        }
        return described;
    }

    private Map<String, AnalysisColumnMetadata> loadColumnMetadata(String catalog, String schema, String table) {
        return columnMetadata.findByCatalogNameAndSchemaNameAndTableName(catalog, schema, table).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AnalysisColumnMetadata::getColumnName, Function.identity()));
    }

    List<LakehouseBrowseService.ColumnInfo> livePhysicalColumns(RegisteredTable registration) {
        if (registration.isExternal()) {
            if (externalMetadata == null) {
                throw new IllegalStateException("External metadata service is unavailable");
            }
            return externalMetadata.registeredColumns(
                            registration.getExternalDataSourceId(),
                            registration.getExternalCatalog(),
                            registration.getSchemaName(),
                            registration.getTableName())
                    .stream()
                    .map(c -> new LakehouseBrowseService.ColumnInfo(c.name(), c.dataType()))
                    .toList();
        }
        return browse.listColumns(
                registration.getCatalogName(),
                registration.getSchemaName(),
                registration.getTableName());
    }

    private static List<RegisteredColumn> decorateAndFilterVisible(
            java.util.stream.Stream<LakehouseBrowseService.ColumnInfo> physical,
            Map<String, AnalysisColumnMetadata> byColumn) {
        return physical
                .map(c -> {
                    AnalysisColumnMetadata meta = byColumn.get(c.name());
                    return new RegisteredColumn(
                            c.name(), c.dataType(),
                            meta != null ? meta.getBusinessName() : null,
                            meta != null && meta.isFuzzyMatchable(),
                            meta == null || meta.isVisible(),
                            meta != null && meta.isValuePickerEnabled());
                })
                .filter(RegisteredColumn::visible)
                .toList();
    }

    private void triggerMetadataSync(RegisteredTable table) {
        browse.invalidateMetadata();
        if (metadataSyncService != null) {
            metadataSyncService.scheduleSyncAfterRegistration(table);
        }
    }

    private void deleteMetadataSnapshots(RegisteredTable table) {
        if (metadataSnapshotService != null) {
            metadataSnapshotService.deleteSnapshotsForTable(
                    table.getCatalogName(), table.getSchemaName(), table.getTableName());
        }
    }

    /**
     * @param visible retained on the record so display/live lists can filter
     *                on it; always {@code true} in what those methods return.
     */
    public record RegisteredColumn(String name, String dataType, String businessName,
                                   boolean fuzzyMatchable, boolean visible, boolean valuePickerEnabled) {
        /** Picker off — what every column is until an admin enables it. */
        public RegisteredColumn(String name, String dataType, String businessName,
                                boolean fuzzyMatchable, boolean visible) {
            this(name, dataType, businessName, fuzzyMatchable, visible, false);
        }
    }

    /**
     * The server-side gate behind the value pickers (Aa1 / 0.11): a distinct-values query runs only for
     * a column an admin has switched the picker on for. Enforced here, not just in the UI, because the
     * endpoint is callable directly. Call after {@link #validateColumn}, so an unregistered or hidden
     * column is reported as such first.
     */
    public void requireValuePickerEnabled(QualifiedColumn column) {
        boolean enabled = columnMetadata
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        column.table().catalog(), column.table().schema(),
                        column.table().table(), column.column())
                .map(AnalysisColumnMetadata::isValuePickerEnabled)
                .orElse(false);
        if (!enabled) {
            throw new IllegalArgumentException(
                    "A value list is not available for column " + column.column()
                            + ". Type the value instead, or ask an admin to enable the value picker for this column.");
        }
    }

    List<RegisteredTable> officerVisible(List<RegisteredTable> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        TableScopePolicy.OfficerScopeView scope = officerScope.currentOfficerScope();
        Map<Long, TableScopePolicy.TableScopeMetadata> metadata = scopeCatalog.metadataForTables(rows);
        return rows.stream()
                .filter(r -> TableScopePolicy.isTableVisible(
                        scope,
                        metadata.getOrDefault(
                                r.getId(),
                                new TableScopePolicy.TableScopeMetadata(
                                        r.isSharedReference(), List.of(), Set.of()))))
                .toList();
    }

    void assertOfficerVisible(RegisteredTable row) {
        TableScopePolicy.OfficerScopeView scope = officerScope.currentOfficerScope();
        TableScopePolicy.TableScopeMetadata metadata = scopeCatalog.metadataFor(row);
        if (!TableScopePolicy.isTableVisible(scope, metadata)) {
            throw new IllegalArgumentException(NOT_VISIBLE_IN_SCOPE);
        }
    }

    /** For §7.2.3 — coarsest binding per dimension for the current officer and table. */
    public Map<Long, TableScopePolicy.LevelBinding> chosenBindingsForCurrentOfficer(
            RegisteredTable table) {
        TableScopePolicy.OfficerScopeView scope = officerScope.currentOfficerScope();
        TableScopePolicy.TableScopeMetadata metadata = scopeCatalog.metadataFor(table);
        Map<Long, TableScopePolicy.LevelBinding> chosen = new LinkedHashMap<>();
        for (Long dimensionId : scope.assignmentsByDimension().keySet()) {
            TableScopePolicy.chosenBindingForDimension(scope, metadata, dimensionId)
                    .ifPresent(b -> chosen.put(dimensionId, b));
        }
        return Map.copyOf(chosen);
    }
}
