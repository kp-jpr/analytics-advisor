package gov.rajasthan.smart.srse.savedquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.analysis.RecordMatchService;
import gov.rajasthan.smart.srse.audit.AuditCaptureService;
import gov.rajasthan.smart.srse.audit.AuditScopeSummaryService;
import gov.rajasthan.smart.srse.identity.AdminAccessDeniedException;
import gov.rajasthan.smart.srse.identity.AdminAuthorizationService;
import gov.rajasthan.smart.srse.identity.AppUser;
import gov.rajasthan.smart.srse.identity.AppUserRepository;
import gov.rajasthan.smart.srse.identity.AuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedQueryServiceTest {

    @Mock
    private SavedQueryRepository savedQueryRepository;
    @Mock
    private SavedQueryGrantRepository grantRepository;
    @Mock
    private RecordMatchService matchService;
    @Mock
    private AuthenticatedUserService authenticatedUserService;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private AuditCaptureService auditCapture;
    @Mock
    private AuditScopeSummaryService scopeSummary;
    @Mock
    private SavedQueryShareGate shareGate;

    private SavedQueryService service;
    private SavedQueryPayloadCodec codec;

    @BeforeEach
    void setUp() {
        codec = new SavedQueryPayloadCodec(new ObjectMapper());
        service = new SavedQueryService(
                savedQueryRepository,
                grantRepository,
                codec,
                matchService,
                authenticatedUserService,
                userRepository,
                adminAuthorizationService,
                auditCapture,
                scopeSummary,
                shareGate);
    }

    @Test
    void reopenReplansUnderCurrentOfficer() {
        AppUser jaipur = user(2L, "jaipurofficer");
        SavedQuery saved = entity(1L, 99L, minimalRequest());
        when(authenticatedUserService.requireCurrentUser()).thenReturn(jaipur);
        when(savedQueryRepository.findById(1L)).thenReturn(Optional.of(saved));
        when(grantRepository.existsBySavedQueryIdAndGranteeUserId(1L, 2L)).thenReturn(true);
        AppUser owner = user(99L, "superadmin");
        when(userRepository.findById(99L)).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doNothing().when(adminAuthorizationService).assertCanManageUser(owner, jaipur);

        service.getForCurrentUser(1L);

        verify(matchService).planMatch(any(RecordMatchRequest.class));
    }

    @Test
    void shareRefusesWhenGranteeCannotRun() {
        AppUser owner = user(1L, "owner");
        AppUser grantee = user(2L, "grantee");
        SavedQuery saved = entity(5L, 1L, minimalRequest());
        when(authenticatedUserService.requireCurrentUser()).thenReturn(owner);
        when(savedQueryRepository.findById(5L)).thenReturn(Optional.of(saved));
        when(userRepository.findById(2L)).thenReturn(Optional.of(grantee));
        org.mockito.Mockito.doThrow(new AdminAccessDeniedException("outside scope"))
                .when(shareGate).assertGranteeCanRun(any(), any());

        assertThrows(AdminAccessDeniedException.class,
                () -> service.share(5L, new SavedQueryDtos.ShareSavedQueryRequest(2L)));

        verify(grantRepository, never()).save(any());
    }

    private SavedQuery entity(long id, long ownerId, RecordMatchRequest request) {
        SavedQuery q = new SavedQuery();
        try {
            Field field = SavedQuery.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(q, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        q.setOwnerUserId(ownerId);
        q.setName("test");
        q.setPayloadText(codec.encode(request));
        q.setCreatedAt(Instant.now());
        q.setUpdatedAt(Instant.now());
        return q;
    }

    private static AppUser user(long id, String username) {
        AppUser u = new AppUser(username, "hash");
        u.setPasswordChangedAt(Instant.now());
        try {
            Field field = AppUser.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return u;
    }

    private static RecordMatchRequest minimalRequest() {
        MatchCriterion c = new MatchCriterion("iceberg", "srse", "beneficiary", "district", null);
        return new RecordMatchRequest(
                List.of(c), List.of(c), List.of(), List.of(), List.of(), false, null, null,
                List.of(), false, null, null, false, List.of(), List.of(), null, false, List.of());
    }
}
