package gov.rajasthan.smart.srse.lakehouse;

import org.junit.jupiter.api.BeforeEach;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LakehouseBrowseServiceTest {

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";
    private static final String TABLE = "tbl_txn_bankdtl";

    @Mock
    private JdbcTemplate jdbc;

    private LakehouseBrowseService service;

    @BeforeEach
    void setUp() {
        service = new LakehouseBrowseService(jdbc, new GuardrailProperties(1000, 180, 50));
    }

    private void stubCatalogs(String... catalogs) {
        lenient().when(jdbc.queryForList(eq("SHOW CATALOGS"), eq(String.class)))
                .thenReturn(List.of(catalogs));
    }

    private void stubSchemas(String... schemas) {
        lenient().when(jdbc.queryForList(
                        eq("SELECT schema_name FROM " + CATALOG + ".information_schema.schemata "
                                + "ORDER BY schema_name"), eq(String.class)))
                .thenReturn(List.of(schemas));
    }

    private void stubTables(String... tables) {
        lenient().when(jdbc.queryForList(
                        eq("SELECT table_name FROM " + CATALOG + ".information_schema.tables "
                                + "WHERE table_schema = ? ORDER BY table_name"),
                        eq(String.class), eq(SCHEMA)))
                .thenReturn(List.of(tables));
    }

    @Test
    void listsCatalogsSortedAndWithoutSystemCatalogs() {
        stubCatalogs("system", "iceberg_gold", "jmx", CATALOG);

        assertEquals(List.of("iceberg_data", "iceberg_gold"), service.listCatalogs());
    }

    @Test
    void listsSchemasExcludingInformationSchema() {
        stubCatalogs(CATALOG);
        stubSchemas("information_schema", SCHEMA, "jan_aadhar_data_mst");

        assertEquals(List.of(SCHEMA, "jan_aadhar_data_mst"), service.listSchemas(CATALOG));
    }

    /**
     * The catalog is interpolated (Presto has no placeholder for a catalog
     * qualifier) but the schema is a VALUE, so it must still bind.
     */
    @Test
    void listTablesInterpolatesCatalogAndBindsSchema() {
        stubCatalogs(CATALOG);
        stubSchemas(SCHEMA);
        stubTables(TABLE, "tbl_txn_doc_engine");

        assertEquals(List.of(TABLE, "tbl_txn_doc_engine"), service.listTables(CATALOG, SCHEMA));

        verify(jdbc).queryForList(
                eq("SELECT table_name FROM " + CATALOG + ".information_schema.tables "
                        + "WHERE table_schema = ? ORDER BY table_name"),
                eq(String.class), eq(SCHEMA));
    }

    @Test
    void listColumnsBindsSchemaAndTableAsParameters() {
        stubCatalogs(CATALOG);
        stubSchemas(SCHEMA);
        stubTables(TABLE);

        service.listColumns(CATALOG, SCHEMA, TABLE);

        verify(jdbc).query(
                eq("SELECT column_name, data_type FROM " + CATALOG + ".information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position"),
                any(RowMapper.class), eq(SCHEMA), eq(TABLE));
    }

    @Test
    void rejectsCatalogThatIsNotInShowCatalogs() {
        stubCatalogs(CATALOG);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.listSchemas("not_a_catalog"));
        assertTrue(e.getMessage().contains("Unknown catalog"), e.getMessage());
    }

    @Test
    void rejectsSchemaThatIsNotInThatCatalog() {
        stubCatalogs(CATALOG);
        stubSchemas(SCHEMA);

        assertThrows(IllegalArgumentException.class, () -> service.listTables(CATALOG, "other_schema"));
    }

    @Test
    void rejectsTableThatIsNotInThatSchema() {
        stubCatalogs(CATALOG);
        stubSchemas(SCHEMA);
        stubTables(TABLE);

        assertThrows(IllegalArgumentException.class,
                () -> service.listColumns(CATALOG, SCHEMA, "tbl_missing"));
    }

    /**
     * The identifier guard must fire BEFORE the allow-list query, because
     * that query has to interpolate the catalog in order to run at all — so a
     * malicious catalog name must never reach the database, not even to be
     * rejected by it.
     */
    @Test
    void injectionAttemptInCatalogNeverReachesTheDatabase() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.listSchemas("iceberg_data.x UNION SELECT 1--"));
        assertTrue(e.getMessage().contains("Illegal catalog identifier"), e.getMessage());
        verify(jdbc, never()).queryForList(eq("SHOW CATALOGS"), eq(String.class));
    }

    @Test
    void injectionAttemptInSchemaNeverReachesTheDatabase() {
        stubCatalogs(CATALOG);

        assertThrows(IllegalArgumentException.class,
                () -> service.listTables(CATALOG, "s'; DROP TABLE x--"));
        verify(jdbc, never()).queryForList(
                eq("SELECT schema_name FROM " + CATALOG + ".information_schema.schemata "
                        + "ORDER BY schema_name"), eq(String.class));
    }

    /**
     * A catalog the lakehouse reports but that this service could not safely
     * address is dropped from the list rather than offered and then rejected
     * when an admin clicks it.
     */
    @Test
    void catalogWithAnUnsafeNameIsNotOffered() {
        stubCatalogs(CATALOG, "weird-catalog name");

        assertEquals(List.of(CATALOG), service.listCatalogs());
    }

    /**
     * Catalog metadata uses a short timeout so a slow information_schema walk
     * cannot hold a pooled connection for the full match timeout.
     */
    @Test
    void browseAppliesTheMetadataQueryTimeout() {
        when(jdbc.queryForList(eq("SHOW CATALOGS"), eq(String.class))).thenReturn(List.of("iceberg"));
        when(jdbc.queryForList(contains("information_schema.schemata"), eq(String.class)))
                .thenReturn(List.of("srse"));
        when(jdbc.queryForList(contains("information_schema.tables"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("beneficiary"));
        service.listTables("iceberg", "srse");
        verify(jdbc, atLeastOnce()).setQueryTimeout(15);
    }

    @Test
    void repeatedCatalogListHitsPrestoOnceUntilInvalidated() {
        stubCatalogs(CATALOG);
        service.listCatalogs();
        service.listCatalogs();
        verify(jdbc, times(1)).queryForList(eq("SHOW CATALOGS"), eq(String.class));

        service.invalidateMetadata();
        service.listCatalogs();
        verify(jdbc, times(2)).queryForList(eq("SHOW CATALOGS"), eq(String.class));
    }

    @Test
    void cachedColumnListDoesNotListTablesAgain() {
        stubCatalogs(CATALOG);
        stubSchemas(SCHEMA);
        stubTables(TABLE);
        lenient().when(jdbc.query(
                        contains("information_schema.columns"),
                        any(RowMapper.class),
                        eq(SCHEMA),
                        eq(TABLE)))
                .thenReturn(List.of(new LakehouseBrowseService.ColumnInfo("bank_id", "varchar")));

        service.listColumns(CATALOG, SCHEMA, TABLE);
        service.listColumns(CATALOG, SCHEMA, TABLE);

        verify(jdbc, times(1)).queryForList(
                contains("information_schema.tables"), eq(String.class), eq(SCHEMA));
    }
}
