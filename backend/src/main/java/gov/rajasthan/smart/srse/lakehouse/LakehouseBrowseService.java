package gov.rajasthan.smart.srse.lakehouse;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.execution.ScopedJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Read-only live introspection of the lakehouse hierarchy:
 * <b>Catalog → Schema → Table → Column</b>.
 *
 * <p>This is what the Admin page's cascade browses. It replaces the older
 * single-schema view ({@code AnalysisSchemaService} read
 * {@code conn.getSchema()} and listed tables from the connection's one
 * implicit schema), which could not express the real deployment: watsonx.data
 * exposes several catalogs, each with several schemas, and SRSE maps at least
 * two layers (Silver and Gold) whose catalog/schema/table names all differ.
 *
 * <h2>Injection safety (CLAUDE.md: non-negotiable)</h2>
 * Catalog and schema names have to be INTERPOLATED, not bound — Presto has no
 * placeholder form for a catalog qualifier, so {@code ?.information_schema}
 * is not valid SQL. Two mechanisms cover that:
 * <ol>
 *   <li><b>Allow-list, primary.</b> Every level is validated against the level
 *       above before it is used: a catalog must appear in {@code SHOW CATALOGS},
 *       a schema in that catalog's {@code information_schema.schemata}, a table
 *       in that schema, a column in that table. Only names the lakehouse itself
 *       reported can ever reach SQL text — officer/admin input is matched
 *       against that list, never trusted.</li>
 *   <li><b>Identifier grammar, defence in depth.</b> {@link LakehouseIdentifiers}
 *       rejects anything that is not a bare Presto identifier, including on the
 *       first not-yet-validated use (the validation query itself must
 *       interpolate the catalog in order to run).</li>
 * </ol>
 * Everything that is a VALUE rather than an identifier — schema and table names
 * in {@code WHERE} clauses — is still a bound {@code ?} parameter, as below.
 *
 * <p>Catalog, schema, table, and column lists are cached for two minutes.
 * A match that checks several columns of one table used to repeat
 * {@code SHOW CATALOGS} and three {@code information_schema} queries per
 * column. The cache is dropped when the analytical connection is swapped
 * and when a table is registered or removed.
 */
@Service
public class LakehouseBrowseService {

    /**
     * Never offered as a registerable schema — it is Presto's own metadata
     * view, present in every catalog, and registering it would expose schema
     * internals as if it were beneficiary data.
     */
    private static final String INFORMATION_SCHEMA = "information_schema";

    /** System catalogs that describe the cluster rather than hold data. */
    private static final Set<String> SYSTEM_CATALOGS = Set.of("system", "jmx");

    private static final Duration METADATA_CACHE_TTL = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final int metadataTimeoutSeconds;
    private final Cache<String, List<String>> catalogCache = newCache(4);
    private final Cache<String, List<String>> schemaCache = newCache(64);
    private final Cache<String, List<String>> tableCache = newCache(256);
    private final Cache<String, List<ColumnInfo>> columnCache = newCache(256);

    @Autowired
    public LakehouseBrowseService(
            @Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc,
            GuardrailProperties guardrails,
            @Value("${srse.guardrails.metadata-timeout-seconds:15}") int metadataTimeoutSeconds) {
        if (metadataTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "metadata timeout must be positive (match timeout is "
                            + guardrails.queryTimeoutSeconds() + "s)");
        }
        this.jdbc = jdbc;
        this.metadataTimeoutSeconds = metadataTimeoutSeconds;
    }

    /** Tests use the default metadata timeout of 15 seconds. */
    LakehouseBrowseService(JdbcTemplate jdbc, GuardrailProperties guardrails) {
        this(jdbc, guardrails, 15);
    }

    /**
     * Metadata walks use a short timeout. Match execution keeps
     * {@code srse.guardrails.query-timeout-seconds}. The shared
     * {@code prestoJdbcTemplate} stores one timeout, so each caller sets it
     * immediately before its own statement.
     */
    private JdbcTemplate timed() {
        return ScopedJdbc.withTimeout(jdbc, metadataTimeoutSeconds);
    }

    /** Drops cached catalogs, schemas, tables, and columns. */
    public void invalidateMetadata() {
        catalogCache.invalidateAll();
        schemaCache.invalidateAll();
        tableCache.invalidateAll();
        columnCache.invalidateAll();
    }

    private static <T> Cache<String, List<T>> newCache(int maximumSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(METADATA_CACHE_TTL)
                .maximumSize(maximumSize)
                .build();
    }

    /** Every data catalog the current Presto connection can see, e.g. {@code iceberg_data}. */
    public List<String> listCatalogs() {
        return catalogCache.get("all", key -> loadCatalogs());
    }

    private List<String> loadCatalogs() {
        return timed().queryForList("SHOW CATALOGS", String.class).stream()
                // A catalog whose name isn't a bare identifier can't be
                // addressed by the rest of this service, so it's dropped here
                // rather than offered and then rejected on selection.
                .filter(LakehouseIdentifiers::isSafe)
                .filter(c -> !SYSTEM_CATALOGS.contains(c.toLowerCase()))
                .sorted()
                .toList();
    }

    /** Schemas inside {@code catalog}, e.g. {@code jan_aadhar_data_txn}. */
    public List<String> listSchemas(String catalog) {
        validateCatalog(catalog);
        return schemaCache.get(catalog, key -> loadSchemas(catalog));
    }

    private List<String> loadSchemas(String catalog) {
        return timed().queryForList(
                        "SELECT schema_name FROM " + catalog + ".information_schema.schemata "
                                + "ORDER BY schema_name",
                        String.class).stream()
                .filter(s -> !INFORMATION_SCHEMA.equalsIgnoreCase(s))
                .filter(LakehouseIdentifiers::isSafe)
                .toList();
    }

    /** Tables inside {@code catalog.schema}, e.g. {@code tbl_txn_bankdtl}. */
    public List<String> listTables(String catalog, String schema) {
        validateSchema(catalog, schema);
        return tableCache.get(cacheKey(catalog, schema), key -> loadTables(catalog, schema));
    }

    private List<String> loadTables(String catalog, String schema) {
        return timed().queryForList(
                        // catalog is an interpolated identifier (validated above);
                        // schema is a VALUE here, so it binds as a parameter.
                        "SELECT table_name FROM " + catalog + ".information_schema.tables "
                                + "WHERE table_schema = ? ORDER BY table_name",
                        String.class, schema).stream()
                .filter(LakehouseIdentifiers::isSafe)
                .toList();
    }

    /** Columns of {@code catalog.schema.table}, in ordinal order, e.g. {@code bank_id}, {@code account_no}. */
    public List<ColumnInfo> listColumns(String catalog, String schema, String table) {
        String key = cacheKey(catalog, schema, table);
        List<ColumnInfo> cached = columnCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        validateTable(catalog, schema, table);
        return columnCache.get(key, ignored -> loadColumns(catalog, schema, table));
    }

    private List<ColumnInfo> loadColumns(String catalog, String schema, String table) {
        return timed().query(
                "SELECT column_name, data_type FROM " + catalog + ".information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                (rs, rowNum) -> new ColumnInfo(rs.getString("column_name"), rs.getString("data_type")),
                schema, table);
    }

    public List<ColumnInfo> listColumns(QualifiedTable table) {
        return listColumns(table.catalog(), table.schema(), table.table());
    }

    // ---- allow-list validation, one level per rung of the hierarchy ----

    /** Throws unless {@code catalog} is a real catalog on the live connection. */
    public void validateCatalog(String catalog) {
        LakehouseIdentifiers.requireSafe("catalog", catalog);
        if (!listCatalogs().contains(catalog)) {
            throw new IllegalArgumentException("Unknown catalog: " + catalog);
        }
    }

    /** Throws unless {@code schema} is a real schema of {@code catalog}. */
    public void validateSchema(String catalog, String schema) {
        LakehouseIdentifiers.requireSafe("schema", schema);
        if (!listSchemas(catalog).contains(schema)) {
            throw new IllegalArgumentException("Unknown schema: " + catalog + "." + schema);
        }
    }

    /** Throws unless {@code table} is a real table of {@code catalog.schema}. */
    public void validateTable(String catalog, String schema, String table) {
        LakehouseIdentifiers.requireSafe("table", table);
        if (!listTables(catalog, schema).contains(table)) {
            throw new IllegalArgumentException(
                    "Unknown table: " + catalog + "." + schema + "." + table);
        }
    }

    public void validateTable(QualifiedTable table) {
        validateTable(table.catalog(), table.schema(), table.table());
    }

    /** Throws unless {@code column} is a real column of {@code catalog.schema.table}. */
    public void validateColumn(String catalog, String schema, String table, String column) {
        LakehouseIdentifiers.requireSafe("column", column);
        boolean known = listColumns(catalog, schema, table).stream()
                .anyMatch(c -> c.name().equals(column));
        if (!known) {
            throw new IllegalArgumentException(
                    "Unknown column: " + catalog + "." + schema + "." + table + "." + column);
        }
    }

    public void validateColumn(QualifiedColumn column) {
        validateColumn(column.table().catalog(), column.table().schema(),
                column.table().table(), column.column());
    }

    public record ColumnInfo(String name, String dataType) {
    }

    private static String cacheKey(String... parts) {
        return String.join("\u0000", parts);
    }
}
