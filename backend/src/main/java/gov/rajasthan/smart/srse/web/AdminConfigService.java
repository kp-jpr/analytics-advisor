package gov.rajasthan.smart.srse.web;

import com.zaxxer.hikari.HikariDataSource;
import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.config.AnalyticalConnectionService;
import gov.rajasthan.smart.srse.config.ConnectionOverrideStore;
import gov.rajasthan.smart.srse.config.OperationalConnectionService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.metadata.ColumnMaskPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Export/import of the admin456 configuration bundle. Lets a team redeploy
 * without re-entering connections, lakehouse registrations, or analysis column
 * overrides by hand.
 */
@Service
public class AdminConfigService {

    private static final String LEGACY_SCHEMA_VERSION = "1.0";

    private final HikariDataSource operational;
    private final ConnectionOverrideStore overrideStore;
    private final AnalyticalConnectionService analyticalConnectionService;
    private final OperationalConnectionService operationalConnectionService;
    private final RegisteredTableRepository registeredTableRepository;
    private final LakehouseRegistryService registryService;
    private final AnalysisColumnMetadataRepository columnMetadataRepository;

    private final String dataMode;
    private final String analyticalUrl;
    private final String analyticalUsername;
    private final String analyticalDriverClassName;

    public AdminConfigService(
            DataSource operational,
            ConnectionOverrideStore overrideStore,
            AnalyticalConnectionService analyticalConnectionService,
            OperationalConnectionService operationalConnectionService,
            RegisteredTableRepository registeredTableRepository,
            LakehouseRegistryService registryService,
            AnalysisColumnMetadataRepository columnMetadataRepository,
            @Value("${srse.data-mode}") String dataMode,
            @Value("${srse.datasource.analytical.jdbc-url}") String analyticalUrl,
            @Value("${srse.datasource.analytical.username}") String analyticalUsername,
            @Value("${srse.datasource.analytical.driver-class-name}") String analyticalDriverClassName) {
        this.operational = (HikariDataSource) operational;
        this.overrideStore = overrideStore;
        this.analyticalConnectionService = analyticalConnectionService;
        this.operationalConnectionService = operationalConnectionService;
        this.registeredTableRepository = registeredTableRepository;
        this.registryService = registryService;
        this.columnMetadataRepository = columnMetadataRepository;
        this.dataMode = dataMode;
        this.analyticalUrl = analyticalUrl;
        this.analyticalUsername = analyticalUsername;
        this.analyticalDriverClassName = analyticalDriverClassName;
    }

    public AdminConfigBundle export() {
        Properties overrides = overrideStore.load().orElseGet(Properties::new);
        AdminConfigBundle.ConnectionPlane operationalPlane = planeFromOverrides(
                overrides, "operational",
                operational.getJdbcUrl(), operational.getUsername(), operational.getDriverClassName());
        AdminConfigBundle.ConnectionPlane analyticalPlane = planeFromOverrides(
                overrides, "analytical",
                analyticalUrl, analyticalUsername, analyticalDriverClassName);

        List<AdminConfigBundle.RegisteredTableEntry> tables = registeredTableRepository.findAll().stream()
                .map(t -> new AdminConfigBundle.RegisteredTableEntry(
                        t.getCatalogName(), t.getSchemaName(), t.getTableName(), t.getLayer()))
                .toList();

        List<AdminConfigBundle.AnalysisColumnMetadataEntry> columnMetadata =
                columnMetadataRepository.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc()
                        .stream()
                        .map(this::toColumnMetadataEntry)
                        .toList();

        return new AdminConfigBundle(
                AdminConfigBundle.CURRENT_SCHEMA_VERSION,
                Instant.now(),
                dataMode,
                new AdminConfigBundle.ConnectionBundle(
                        maskPasswordsForExport(operationalPlane),
                        maskPasswordsForExport(analyticalPlane)),
                tables,
                columnMetadata);
    }

    @Transactional
    public ImportResult importConfig(AdminConfigBundle bundle, ImportOptions options) {
        validateSchemaVersion(bundle.schemaVersion());

        ImportResult.Builder result = ImportResult.builder();
        boolean legacy = LEGACY_SCHEMA_VERSION.equals(bundle.schemaVersion());
        if (legacy) {
            result.skip("fieldCatalog", "not part of this product (legacy SRSE section)");
            result.skip("fieldColumnMappings", "not part of this product (legacy SRSE section)");
            result.skip("schemes", "not part of this product (legacy SRSE section)");
        }

        if (bundle.connections() == null) {
            result.skip("connections", "not present in this file");
        } else {
            applyConnections(bundle.connections(), options, result);
        }

        if (bundle.registeredTables() == null || bundle.registeredTables().isEmpty()) {
            result.skip("registeredTables", "not present in this file");
        } else {
            for (AdminConfigBundle.RegisteredTableEntry entry : bundle.registeredTables()) {
                registryService.importRegistration(entry.catalog(), entry.schema(), entry.table(), entry.layer());
                result.registeredTable(qualifiedTable(entry));
            }
        }

        if (bundle.analysisColumnMetadata() == null || bundle.analysisColumnMetadata().isEmpty()) {
            result.skip("analysisColumnMetadata", "not present in this file");
        } else {
            for (AdminConfigBundle.AnalysisColumnMetadataEntry entry : bundle.analysisColumnMetadata()) {
                upsertColumnMetadata(entry);
                result.columnMetadata(qualifiedColumn(entry));
            }
        }

        return result.build();
    }

    private void validateSchemaVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (!AdminConfigBundle.CURRENT_SCHEMA_VERSION.equals(version)
                && !LEGACY_SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException(
                    "Unsupported schemaVersion: " + version
                            + " (expected " + AdminConfigBundle.CURRENT_SCHEMA_VERSION
                            + " or " + LEGACY_SCHEMA_VERSION + ")");
        }
    }

    private void applyConnections(AdminConfigBundle.ConnectionBundle connections,
                                  ImportOptions options,
                                  ImportResult.Builder result) {
        Properties existing = overrideStore.load().orElseGet(Properties::new);
        if (connections.operational() != null) {
            AdminConfigBundle.ConnectionPlane plane = connections.operational();
            String password = resolvePassword(plane.password(), "operational", existing);
            if (options.testConnections()) {
                operationalConnectionService.update(
                        plane.jdbcUrl(), plane.username(), password, plane.driverClassName());
            } else {
                persistConnectionOverride("operational", plane, password);
            }
            result.operationalRestartRequired(true);
            result.connectionsImported(true);
        }
        if (connections.analytical() != null) {
            AdminConfigBundle.ConnectionPlane plane = connections.analytical();
            String password = resolvePassword(plane.password(), "analytical", existing);
            if (options.testConnections()) {
                analyticalConnectionService.update(
                        plane.jdbcUrl(), plane.username(), password, plane.driverClassName());
            } else {
                persistConnectionOverride("analytical", plane, password);
            }
            result.connectionsImported(true);
        }
    }

    /**
     * Blank or absent password in the file means "keep what is already configured".
     */
    private static String resolvePassword(String fromFile, String prefix, Properties existingOverrides) {
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        return existingOverrides.getProperty(prefix + ".password", "");
    }

    private void persistConnectionOverride(String prefix, AdminConfigBundle.ConnectionPlane plane, String password) {
        Properties props = new Properties();
        props.setProperty(prefix + ".jdbc-url", plane.jdbcUrl());
        props.setProperty(prefix + ".username", plane.username());
        props.setProperty(prefix + ".password", password);
        props.setProperty(prefix + ".driver-class-name", plane.driverClassName());
        overrideStore.save(props);
    }

    private void upsertColumnMetadata(AdminConfigBundle.AnalysisColumnMetadataEntry entry) {
        var existing = columnMetadataRepository
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        entry.catalog(), entry.schema(), entry.table(), entry.column());
        Long existingId = existing.map(AnalysisColumnMetadata::getId).orElse(null);
        AnalysisColumnMetadata entity = new AnalysisColumnMetadata(
                existingId,
                new QualifiedColumn(entry.catalog(), entry.schema(), entry.table(), entry.column()),
                entry.businessName(),
                Boolean.valueOf(entry.fuzzyMatchable()),
                Boolean.valueOf(entry.visible()));
        entity.setCompareAs(CompareAs.orAuto(entry.compareAs()));
        // A restore must not silently switch pickers off: an older bundle has no flag, so keep what is stored.
        entity.setValuePickerEnabled(entry.valuePickerEnabled() != null
                ? entry.valuePickerEnabled()
                : existing.map(AnalysisColumnMetadata::isValuePickerEnabled).orElse(false));
        entity.setMaskPolicy(entry.maskPolicy() != null
                ? entry.maskPolicy()
                : existing.map(AnalysisColumnMetadata::getMaskPolicy).orElse(ColumnMaskPolicy.NONE));
        entity.setMaskKeepLast(entry.maskKeepLast() != null
                ? entry.maskKeepLast()
                : existing.map(AnalysisColumnMetadata::getMaskKeepLast).orElse(4));
        columnMetadataRepository.save(entity);
    }

    private AdminConfigBundle.AnalysisColumnMetadataEntry toColumnMetadataEntry(AnalysisColumnMetadata entity) {
        return new AdminConfigBundle.AnalysisColumnMetadataEntry(
                entity.getCatalogName(),
                entity.getSchemaName(),
                entity.getTableName(),
                entity.getColumnName(),
                entity.getBusinessName(),
                entity.isFuzzyMatchable(),
                entity.isVisible(),
                entity.getCompareAs(),
                entity.isValuePickerEnabled(),
                entity.getMaskPolicy(),
                entity.getMaskKeepLast());
    }

    private static AdminConfigBundle.ConnectionPlane planeFromOverrides(
            Properties overrides, String prefix, String defaultUrl, String defaultUser, String defaultDriver) {
        return new AdminConfigBundle.ConnectionPlane(
                overrides.getProperty(prefix + ".jdbc-url", defaultUrl),
                overrides.getProperty(prefix + ".username", defaultUser),
                null,
                overrides.getProperty(prefix + ".driver-class-name", defaultDriver));
    }

    private static AdminConfigBundle.ConnectionPlane maskPasswordsForExport(AdminConfigBundle.ConnectionPlane plane) {
        return new AdminConfigBundle.ConnectionPlane(
                plane.jdbcUrl(), plane.username(), null, plane.driverClassName());
    }

    private static String qualifiedTable(AdminConfigBundle.RegisteredTableEntry entry) {
        return entry.catalog() + "." + entry.schema() + "." + entry.table();
    }

    private static String qualifiedColumn(AdminConfigBundle.AnalysisColumnMetadataEntry entry) {
        return qualifiedTable(new AdminConfigBundle.RegisteredTableEntry(
                entry.catalog(), entry.schema(), entry.table(), null))
                + "." + entry.column();
    }

    public record ImportOptions(boolean testConnections) {
        public static ImportOptions defaults() {
            return new ImportOptions(true);
        }
    }

    public record SkippedSection(String section, String reason) {
    }

    public record ImportResult(
            int registeredTableCount,
            int columnMetadataCount,
            boolean operationalRestartRequired,
            boolean connectionsImported,
            List<String> importedTables,
            List<String> importedColumns,
            List<SkippedSection> skipped) {

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private int registeredTableCount;
            private int columnMetadataCount;
            private boolean operationalRestartRequired;
            private boolean connectionsImported;
            private final ArrayList<String> importedTables = new ArrayList<>();
            private final ArrayList<String> importedColumns = new ArrayList<>();
            private final ArrayList<SkippedSection> skipped = new ArrayList<>();

            void registeredTable(String key) {
                registeredTableCount++;
                importedTables.add(key);
            }

            void columnMetadata(String key) {
                columnMetadataCount++;
                importedColumns.add(key);
            }

            void operationalRestartRequired(boolean value) {
                operationalRestartRequired = operationalRestartRequired || value;
            }

            void connectionsImported(boolean value) {
                connectionsImported = connectionsImported || value;
            }

            void skip(String section, String reason) {
                skipped.add(new SkippedSection(section, reason));
            }

            ImportResult build() {
                return new ImportResult(
                        registeredTableCount,
                        columnMetadataCount,
                        operationalRestartRequired,
                        connectionsImported,
                        List.copyOf(importedTables),
                        List.copyOf(importedColumns),
                        List.copyOf(skipped));
            }
        }
    }
}
