package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.CompiledQuery;
import gov.rajasthan.smart.srse.compiler.RuleCompiler;
import gov.rajasthan.smart.srse.datasource.ExternalDataSource;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceService;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceType;
import gov.rajasthan.smart.srse.execution.AnalysisExecutionRouter;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalDirectExecutionTest {

    @Mock
    private JdbcTemplate presto;
    @Mock
    private JdbcTemplate direct;
    @Mock
    private LakehouseRegistryService registry;
    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;
    @Mock
    private AnalysisScopeFromService scopeFrom;
    @Mock
    private RuleCompiler ruleCompiler;
    @Mock
    private RegisteredTableRepository registrations;
    @Mock
    private ExternalDataSourceService sources;

    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(registry.describeColumns(any(), any())).thenAnswer(invocation -> {
            Collection<?> columns = invocation.getArgument(1);
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            for (Object column : columns) {
                String name = String.valueOf(column);
                described.put(name, new RegisteredColumn(name, "varchar", null, false, true));
            }
            return described;
        });
        lenient().when(scopeFrom.planFrom(any())).thenAnswer(invocation ->
                ScopeFilteredFrom.unfiltered(invocation.getArgument(0, gov.rajasthan.smart.srse.lakehouse.QualifiedTable.class)
                        .qualifiedName()));
        lenient().when(ruleCompiler.compile(any(), any())).thenReturn(new CompiledQuery("TRUE", List.of()));
        lenient().when(direct.queryForObject(anyString(), eq(Long.class))).thenReturn(10L);
        lenient().when(direct.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(1L);
        ExternalDataSource source = mock(ExternalDataSource.class);
        lenient().when(source.getDatabaseType()).thenReturn(ExternalDataSourceType.POSTGRESQL);
        lenient().when(source.getName()).thenReturn("Payroll");
        lenient().when(sources.requireActiveInternal(7L)).thenReturn(source);
        lenient().when(sources.jdbcTemplateForExecution(7L)).thenReturn(direct);
        stubRegistration("people");
        stubRegistration("addresses");
        service = new RecordMatchService(
                presto, registry, new GuardrailProperties(1000, 30, 50), columnMetadata,
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10, 2, 100_000),
                new ObjectMapper(), scopeFrom, ruleCompiler);
        ReflectionTestUtils.setField(service, "executionRouter",
                new AnalysisExecutionRouter(registrations, sources, presto));
    }

    @Test
    void sameSourceJoinStaysOnDirectJdbcAndKeepsTheFanOutQueriesThere() {
        RecordMatchRequest request = new RecordMatchRequest(
                List.of(new MatchCriterion("jdbc_7", "public", "people", "id", null)),
                List.of(new MatchCriterion("jdbc_7", "public", "addresses", "person_id", null)),
                null, null, false, null);

        RecordMatchService.MatchQuery planned = service.planMatch(request);

        assertTrue(planned.sql().contains("jdbc_7.public.people"), planned.sql());
        assertTrue(planned.routedSql().contains("\"public\".\"people\""), planned.routedSql());
        assertTrue(planned.routedSql().contains("\"public\".\"addresses\""), planned.routedSql());
        assertFalse(planned.routedSql().contains("jdbc_7"), planned.routedSql());
        assertFalse(planned.routedSql().contains("Payroll"), planned.routedSql());
        verify(direct, atLeastOnce()).queryForObject(anyString(), eq(Long.class));
        verify(presto, never()).queryForObject(anyString(), eq(Long.class));
    }

    @Test
    void csvAndExcelExportUseTheDirectConnection() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(rs.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(1);
            when(metadata.getColumnLabel(1)).thenReturn("source_district");
            when(rs.getObject(1)).thenReturn("Jaipur");
            handler.processRow(rs);
            return null;
        }).when(direct).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));

        RecordMatchRequest request = new RecordMatchRequest(
                List.of(), List.of(),
                List.of(new DisplayColumn("jdbc_7", "public", "people", "district")),
                List.of(), List.of(), false, null, null, List.of(), false,
                null, null, true, List.of(), List.of(), null, false, List.of());

        ByteArrayOutputStream csvOut = new ByteArrayOutputStream();
        StreamingResponseBody csv = service.matchCsv(request);
        csv.writeTo(csvOut);
        String csvText = csvOut.toString(StandardCharsets.UTF_8);
        assertTrue(csvText.contains("source_district"), csvText);
        assertTrue(csvText.contains("Jaipur"), csvText);
        assertFalse(csvText.contains("Payroll"), csvText);
        assertFalse(csvText.contains("jdbc_7"), csvText);

        ByteArrayOutputStream excelOut = new ByteArrayOutputStream();
        StreamingResponseBody excel = service.matchExcel(request);
        excel.writeTo(excelOut);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelOut.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet.getPaneInformation());
            assertTrue(sheet.getPaneInformation().isFreezePane());
            assertEquals(1, sheet.getPaneInformation().getHorizontalSplitTopRow());
            Row header = sheet.getRow(0);
            assertEquals("source_district", header.getCell(0).getStringCellValue());
            assertEquals("Jaipur", sheet.getRow(1).getCell(0).getStringCellValue());
        }
        verify(direct, atLeastOnce()).setQueryTimeout(30);
        verify(presto, never()).query(anyString(), any(Object[].class), any(RowCallbackHandler.class));
    }

    private void stubRegistration(String table) {
        RegisteredTable row = mock(RegisteredTable.class);
        lenient().when(row.isExternal()).thenReturn(true);
        lenient().when(row.getExternalDataSourceId()).thenReturn(7L);
        lenient().when(row.getSchemaName()).thenReturn("public");
        lenient().when(row.getTableName()).thenReturn(table);
        lenient().when(registrations.findByCatalogNameAndSchemaNameAndTableName("jdbc_7", "public", table))
                .thenReturn(Optional.of(row));
    }
}
