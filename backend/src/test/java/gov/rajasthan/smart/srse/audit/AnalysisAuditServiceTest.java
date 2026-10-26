package gov.rajasthan.smart.srse.audit;

import gov.rajasthan.smart.srse.analysis.AnalysisAuditService;
import gov.rajasthan.smart.srse.analysis.ColumnDistinctValuesService;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.analysis.RecordMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisAuditServiceTest {

    private static final String AADHAAR_LIKE = "123456789012";

    @Mock
    private RecordMatchService matchService;
    @Mock
    private ColumnDistinctValuesService columnValuesService;
    @Mock
    private AuditCaptureService auditCapture;
    @Mock
    private AuditScopeSummaryService scopeSummary;

    private AnalysisAuditService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisAuditService(matchService, columnValuesService, auditCapture, scopeSummary);
        when(scopeSummary.summarizeCurrentOfficer()).thenReturn("BYPASS");
        when(scopeSummary.currentActorUserId()).thenReturn(42L);
    }

    @Test
    void queryShapeKeepsPlaceholdersNeverBoundValues() {
        String sql = "SELECT * FROM t WHERE aadhaar = ? AND name = ?";
        RecordMatchService.MatchQuery query = new RecordMatchService.MatchQuery(
                sql,
                List.of(AADHAAR_LIKE, "officer-value"),
                List.of("aadhaar", "name"));
        when(matchService.planMatch(any())).thenReturn(query);
        RecordMatchRequest req = minimalRequest();
        service.planMatchAudited(req);

        ArgumentCaptor<AuditEventDraft> captor = ArgumentCaptor.forClass(AuditEventDraft.class);
        verify(auditCapture).recordBestEffort(captor.capture());
        AuditEventDraft draft = captor.getValue();
        assertEquals(AuditActionType.QUERY_EXECUTED, draft.actionType());
        assertEquals(AuditOutcome.SUCCESS, draft.outcome());
        assertTrue(draft.queryShape().contains("?"));
        assertFalse(rowContainsSensitiveValue(draft, AADHAAR_LIKE));
        assertFalse(rowContainsSensitiveValue(draft, "officer-value"));
    }

    @Test
    void refusedQueryWritesQueryRefusedNotExecuted() {
        when(matchService.planMatch(any())).thenThrow(new IllegalStateException("fan-out refused"));

        assertThrows(IllegalStateException.class, () -> service.planMatchAudited(minimalRequest()));

        ArgumentCaptor<AuditEventDraft> captor = ArgumentCaptor.forClass(AuditEventDraft.class);
        verify(auditCapture).recordBestEffort(captor.capture());
        assertEquals(AuditActionType.QUERY_REFUSED, captor.getValue().actionType());
        assertEquals(AuditOutcome.REFUSED, captor.getValue().outcome());
        assertNull(captor.getValue().queryShape());
    }

    @Test
    void exportWritesRequiredRow() {
        RecordMatchService.MatchQuery query = new RecordMatchService.MatchQuery(
                "SELECT ?", List.of(AADHAAR_LIKE), List.of("c"));

        service.recordExport(minimalRequest(), query);

        ArgumentCaptor<AuditEventDraft> captor = ArgumentCaptor.forClass(AuditEventDraft.class);
        verify(auditCapture).recordExportRequired(captor.capture());
        assertEquals(AuditActionType.EXPORT, captor.getValue().actionType());
        assertFalse(rowContainsSensitiveValue(captor.getValue(), AADHAAR_LIKE));
    }

    private static boolean rowContainsSensitiveValue(AuditEventDraft draft, String needle) {
        return contains(draft.detail(), needle)
                || contains(draft.targetTables(), needle)
                || contains(draft.queryShape(), needle)
                || contains(draft.scopeSummary(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    private static RecordMatchRequest minimalRequest() {
        MatchCriterion c = new MatchCriterion("c", "s", "beneficiary", "m_id", null);
        return new RecordMatchRequest(
                List.of(c),
                List.of(c),
                List.of(),
                List.of(),
                null,
                false,
                null,
                null,
                null,
                false,
                null,
                null,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of());
    }
}
