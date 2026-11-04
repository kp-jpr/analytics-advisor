package gov.rajasthan.smart.srse.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.metadata.ColumnMaskPolicy;

import java.time.Instant;
import java.util.List;

/**
 * Portable snapshot of admin456 configuration: connections, lakehouse
 * registrations, and analysis column overrides. Natural keys only (no DB ids)
 * so the bundle round-trips across redeploys and fresh DB2 volumes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminConfigBundle(
        String schemaVersion,
        Instant exportedAt,
        String dataMode,
        ConnectionBundle connections,
        List<RegisteredTableEntry> registeredTables,
        List<AnalysisColumnMetadataEntry> analysisColumnMetadata) {

    public static final String CURRENT_SCHEMA_VERSION = "2.0";

    public record ConnectionBundle(ConnectionPlane operational, ConnectionPlane analytical) {
    }

    public record ConnectionPlane(
            String jdbcUrl,
            String username,
            String password,
            String driverClassName) {
    }

    public record RegisteredTableEntry(
            String catalog,
            String schema,
            String table,
            String layer) {
    }

    public record AnalysisColumnMetadataEntry(
            String catalog,
            String schema,
            String table,
            String column,
            String businessName,
            boolean fuzzyMatchable,
            boolean visible,
            CompareAs compareAs,
            Boolean valuePickerEnabled,
            ColumnMaskPolicy maskPolicy,
            Integer maskKeepLast) {
        /** Bundles written before the value-picker flag existed: absent means "leave what is stored". */
        public AnalysisColumnMetadataEntry(
                String catalog, String schema, String table, String column, String businessName,
                boolean fuzzyMatchable, boolean visible, CompareAs compareAs) {
            this(catalog, schema, table, column, businessName, fuzzyMatchable, visible, compareAs, null, null, null);
        }

        public AnalysisColumnMetadataEntry(
                String catalog, String schema, String table, String column, String businessName,
                boolean fuzzyMatchable, boolean visible, CompareAs compareAs, Boolean valuePickerEnabled) {
            this(catalog, schema, table, column, businessName, fuzzyMatchable, visible, compareAs,
                    valuePickerEnabled, null, null);
        }
    }
}
