package gov.rajasthan.smart.srse.datasource;

import gov.rajasthan.smart.srse.audit.AuditActionType;
import gov.rajasthan.smart.srse.audit.AuditOutcome;
import gov.rajasthan.smart.srse.audit.AuditService;
import gov.rajasthan.smart.srse.identity.AdminAccessDeniedException;
import gov.rajasthan.smart.srse.identity.AdminAuthorizationService;
import gov.rajasthan.smart.srse.identity.AppUser;
import gov.rajasthan.smart.srse.identity.AuthenticatedUserService;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Optional;

import static gov.rajasthan.smart.srse.datasource.ExternalDataSourceDtos.ConnectionTestView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalDataSourceServiceTest {

    private static final String SECRET = "REDACT_ME";

    @Mock
    private ExternalDataSourceRepository repository;
    @Mock
    private ExternalDataSourceSecretCodec secretCodec;
    @Mock
    private ExternalJdbcAccess jdbcAccess;
    @Mock
    private AuthenticatedUserService authenticatedUserService;
    @Mock
    private AdminAuthorizationService authorization;
    @Mock
    private AuditService auditService;
    @Mock
    private RegisteredTableRepository registeredTables;
    @Mock
    private AppUser actor;

    private ExternalDataSourceService service;

    @BeforeEach
    void setUp() {
        service = new ExternalDataSourceService(
                repository, secretCodec, jdbcAccess, authenticatedUserService,
                authorization, auditService, registeredTables);
        lenient().when(authenticatedUserService.requireCurrentUser()).thenReturn(actor);
        lenient().when(actor.getId()).thenReturn(4L);
    }

    @Test
    void nonSuperAdminCannotListSources() {
        doThrow(new AdminAccessDeniedException("SuperAdmin only"))
                .when(authorization).assertSuperAdminOnly(actor);
        assertThrows(AdminAccessDeniedException.class, () -> service.list());
        verify(repository, never()).findAllByOrderByNameAsc();
    }

    @Test
    void deleteIsBlockedWhileTablesStayRegistered() {
        ExternalDataSource source = source();
        when(repository.findById(8L)).thenReturn(Optional.of(source));
        when(registeredTables.countByExternalDataSourceId(8L)).thenReturn(2L);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.delete(8L));

        assertTrue(failure.getMessage().contains("2"));
        verify(repository, never()).delete(any());
        verify(auditService, never()).recordExternalDataSourceEvent(
                eq(AuditActionType.DATA_SOURCE_DELETED), any(), any(), any());
    }

    @Test
    void deleteSucceedsWhenNothingIsRegistered() {
        ExternalDataSource source = source();
        when(repository.findById(8L)).thenReturn(Optional.of(source));
        when(registeredTables.countByExternalDataSourceId(8L)).thenReturn(0L);

        service.delete(8L);

        verify(repository).delete(source);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordExternalDataSourceEvent(
                eq(AuditActionType.DATA_SOURCE_DELETED), eq(actor), eq(AuditOutcome.SUCCESS), detail.capture());
        assertFalse(detail.getValue().contains(SECRET));
        assertTrue(detail.getValue().contains("Payroll"));
    }

    @Test
    void connectionFailureRedactsThePassword() throws Exception {
        ExternalDataSource source = source();
        when(repository.findById(8L)).thenReturn(Optional.of(source));
        when(secretCodec.decrypt(source.getEncryptedPassword())).thenReturn(SECRET);
        when(jdbcAccess.open(source, SECRET)).thenThrow(new SQLException("login failed for " + SECRET));

        ConnectionTestView result = service.test(8L);

        assertFalse(result.success());
        assertFalse(result.message().contains(SECRET));
        assertTrue(result.message().contains("[redacted]"));
        assertFalse(source.getLastTestMessage().contains(SECRET));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordExternalDataSourceEvent(
                eq(AuditActionType.DATA_SOURCE_TESTED), eq(actor), eq(AuditOutcome.FAILURE), detail.capture());
        assertEquals("name=Payroll", detail.getValue());
    }

    @Test
    void sourceViewDoesNotCarryThePassword() {
        ExternalDataSource source = source();
        when(repository.findAllByOrderByNameAsc()).thenReturn(java.util.List.of(source));
        when(registeredTables.countByExternalDataSourceId(8L)).thenReturn(1L);

        var views = service.list();

        assertEquals(1, views.size());
        assertTrue(views.get(0).passwordConfigured());
        assertEquals(1L, views.get(0).registrationCount());
        assertFalse(views.get(0).toString().contains(SECRET));
        assertFalse(views.get(0).toString().contains("ciphertext-value"));
    }

    @Test
    void db2IsMarkedAsNotFederationCapableAndPostgresqlAs() {
        var offered = service.supportedTypes();
        assertFalse(offered.stream().filter(t -> t.code().equals("DB2")).findFirst().orElseThrow().federationCapable());
        assertTrue(offered.stream().filter(t -> t.code().equals("POSTGRESQL")).findFirst().orElseThrow().federationCapable());
    }

    @Test
    void aPrestoAliasCannotBeSavedForDb2OnCreateOrUpdate() throws Exception {
        var create = new ExternalDataSourceDtos.CreateRequest(
                "Payroll DB2", "DB2", "db.internal", null, "SRCDB", "reader", SECRET, false, "db2_alias");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.create(create));
        assertTrue(refused.getMessage().contains("no IBM DB2 connector"), refused.getMessage());
        verify(repository, never()).save(any());

        ExternalDataSource db2 = new ExternalDataSource("DB2 src", ExternalDataSourceType.DB2,
                "jdbc:db2://db.internal:50000/SRCDB", "reader", "ciphertext-value", null);
        ReflectionTestUtils.setField(db2, "id", 11L);
        when(repository.findById(11L)).thenReturn(java.util.Optional.of(db2));
        assertThrows(IllegalArgumentException.class, () -> service.updateFederationCatalog(11L, "db2_alias"));
        verify(repository, never()).save(any());
    }

    @Test
    void offeredTypesIncludeTheSixSupportedEngines() {
        var offered = service.supportedTypes().stream().map(t -> t.code()).toList();
        assertEquals(
                java.util.List.of("POSTGRESQL", "DB2", "MYSQL", "MARIADB", "SQLSERVER", "ORACLE"),
                offered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "mariadb", "SQLSERVER", "Oracle"})
    void mysqlMariaDbSqlServerAndOracleCanBeCreated(String type) throws Exception {
        when(repository.findByNameIgnoreCase("Legacy")).thenReturn(Optional.empty());
        when(secretCodec.encrypt(SECRET)).thenReturn("ciphertext-value");
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        DatabaseMetaData metadata = org.mockito.Mockito.mock(DatabaseMetaData.class);
        when(jdbcAccess.open(any(), eq(SECRET))).thenReturn(connection);
        when(connection.isValid(8)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("TestDB");
        when(metadata.getDatabaseProductVersion()).thenReturn("1");
        when(repository.save(any())).thenAnswer(invocation -> {
            ExternalDataSource saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 21L);
            return saved;
        });
        when(registeredTables.countByExternalDataSourceId(21L)).thenReturn(0L);

        var request = new ExternalDataSourceDtos.CreateRequest(
                "Legacy", type, "db.internal", null, "app", "reader", SECRET, false, null);

        var created = service.create(request);

        assertEquals(type.trim().toUpperCase(java.util.Locale.ROOT), created.databaseType());
        assertTrue(created.federationCapable());
        assertTrue(created.jdbcUrl().startsWith("jdbc:"));
        verify(repository).save(any());
        verify(jdbcAccess).open(any(), eq(SECRET));
    }

    private static ExternalDataSource source() {
        ExternalDataSource source = new ExternalDataSource(
                "Payroll",
                ExternalDataSourceType.POSTGRESQL,
                "jdbc:postgresql://db.internal:5432/app",
                "reader",
                "ciphertext-value",
                null);
        ReflectionTestUtils.setField(source, "id", 8L);
        return source;
    }
}
