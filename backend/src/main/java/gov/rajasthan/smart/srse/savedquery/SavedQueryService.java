package gov.rajasthan.smart.srse.savedquery;

import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.analysis.RecordMatchService;
import gov.rajasthan.smart.srse.audit.AuditActionType;
import gov.rajasthan.smart.srse.audit.AuditCaptureService;
import gov.rajasthan.smart.srse.audit.AuditEventDraft;
import gov.rajasthan.smart.srse.audit.AuditOutcome;
import gov.rajasthan.smart.srse.audit.AuditScopeSummaryService;
import gov.rajasthan.smart.srse.identity.AdminAccessDeniedException;
import gov.rajasthan.smart.srse.identity.AdminAuthorizationService;
import gov.rajasthan.smart.srse.identity.AppUser;
import gov.rajasthan.smart.srse.identity.AppUserRepository;
import gov.rajasthan.smart.srse.identity.AuthenticatedUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static gov.rajasthan.smart.srse.savedquery.SavedQueryDtos.CreateSavedQueryRequest;
import static gov.rajasthan.smart.srse.savedquery.SavedQueryDtos.SavedQueryDetail;
import static gov.rajasthan.smart.srse.savedquery.SavedQueryDtos.SavedQuerySummary;
import static gov.rajasthan.smart.srse.savedquery.SavedQueryDtos.ShareSavedQueryRequest;
import static gov.rajasthan.smart.srse.savedquery.SavedQueryDtos.UpdateSavedQueryRequest;

@Service
public class SavedQueryService {

    private final SavedQueryRepository savedQueryRepository;
    private final SavedQueryGrantRepository grantRepository;
    private final SavedQueryPayloadCodec payloadCodec;
    private final RecordMatchService matchService;
    private final AuthenticatedUserService authenticatedUserService;
    private final AppUserRepository userRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AuditCaptureService auditCapture;
    private final AuditScopeSummaryService scopeSummary;
    private final SavedQueryShareGate shareGate;

    public SavedQueryService(
            SavedQueryRepository savedQueryRepository,
            SavedQueryGrantRepository grantRepository,
            SavedQueryPayloadCodec payloadCodec,
            RecordMatchService matchService,
            AuthenticatedUserService authenticatedUserService,
            AppUserRepository userRepository,
            AdminAuthorizationService adminAuthorizationService,
            AuditCaptureService auditCapture,
            AuditScopeSummaryService scopeSummary,
            SavedQueryShareGate shareGate) {
        this.savedQueryRepository = savedQueryRepository;
        this.grantRepository = grantRepository;
        this.payloadCodec = payloadCodec;
        this.matchService = matchService;
        this.authenticatedUserService = authenticatedUserService;
        this.userRepository = userRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.auditCapture = auditCapture;
        this.scopeSummary = scopeSummary;
        this.shareGate = shareGate;
    }

    @Transactional(readOnly = true)
    public List<SavedQuerySummary> listForCurrentUser() {
        AppUser me = authenticatedUserService.requireCurrentUser();
        return savedQueryRepository.findVisibleToUser(me.getId()).stream()
                .map(q -> new SavedQuerySummary(
                        q.getId(),
                        q.getOwnerUserId(),
                        q.getName(),
                        q.getDescription(),
                        q.getUpdatedAt(),
                        q.getOwnerUserId().equals(me.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SavedQueryDetail getForCurrentUser(long id) {
        AppUser me = authenticatedUserService.requireCurrentUser();
        SavedQuery query = requireReadable(me, id);
        RecordMatchRequest request = payloadCodec.decode(query.getPayloadText());
        validateForSave(request);
        return new SavedQueryDetail(
                query.getId(),
                query.getOwnerUserId(),
                query.getName(),
                query.getDescription(),
                query.getCreatedAt(),
                query.getUpdatedAt(),
                request);
    }

    @Transactional
    public SavedQueryDetail create(CreateSavedQueryRequest body) {
        AppUser me = authenticatedUserService.requireCurrentUser();
        validateName(body.name());
        validateForSave(body.request());
        Instant now = Instant.now();
        SavedQuery entity = new SavedQuery();
        entity.setOwnerUserId(me.getId());
        entity.setName(body.name().trim());
        entity.setDescription(trimOrNull(body.description()));
        entity.setPayloadText(payloadCodec.encode(body.request()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        SavedQuery saved = savedQueryRepository.save(entity);
        audit(AuditActionType.SAVED_QUERY_CREATED, "saved query " + saved.getId());
        return detailOf(saved, body.request());
    }

    @Transactional
    public SavedQueryDetail update(long id, UpdateSavedQueryRequest body) {
        AppUser me = authenticatedUserService.requireCurrentUser();
        SavedQuery query = requireOwned(me, id);
        validateName(body.name());
        validateForSave(body.request());
        query.setName(body.name().trim());
        query.setDescription(trimOrNull(body.description()));
        query.setPayloadText(payloadCodec.encode(body.request()));
        query.setUpdatedAt(Instant.now());
        audit(AuditActionType.SAVED_QUERY_UPDATED, "saved query " + id);
        return detailOf(query, body.request());
    }

    @Transactional
    public void delete(long id) {
        AppUser me = authenticatedUserService.requireCurrentUser();
        SavedQuery query = requireOwned(me, id);
        savedQueryRepository.delete(query);
        audit(AuditActionType.SAVED_QUERY_DELETED, "saved query " + id);
    }

    @Transactional
    public void share(long id, ShareSavedQueryRequest body) {
        AppUser owner = authenticatedUserService.requireCurrentUser();
        SavedQuery query = requireOwned(owner, id);
        AppUser grantee = userRepository.findById(body.granteeUserId())
                .orElseThrow(() -> new IllegalArgumentException("Grantee user not found"));
        if (grantee.getId().equals(owner.getId())) {
            throw new IllegalArgumentException("Cannot share a saved query with yourself");
        }
        try {
            adminAuthorizationService.assertCanManageUser(owner, grantee);
        } catch (AdminAccessDeniedException ex) {
            throw new AdminAccessDeniedException(
                    "Cannot share outside your administrative scope: " + ex.getMessage());
        }
        RecordMatchRequest request = payloadCodec.decode(query.getPayloadText());
        shareGate.assertGranteeCanRun(request, grantee);
        if (grantRepository.existsBySavedQueryIdAndGranteeUserId(id, grantee.getId())) {
            return;
        }
        SavedQueryGrant grant = new SavedQueryGrant();
        grant.setSavedQueryId(id);
        grant.setGranteeUserId(grantee.getId());
        grant.setCreatedAt(Instant.now());
        grantRepository.save(grant);
        audit(AuditActionType.SAVED_QUERY_SHARED, "saved query " + id + " → user " + grantee.getId());
    }

    /**
     * Checks tables, columns, join shape, and filter SQL under the current officer.
     * Saving and opening store or restore the definition. The row-count ceiling runs
     * when the officer runs the query.
     */
    private void validateForSave(RecordMatchRequest request) {
        matchService.planMatchForSave(request);
    }

    private static SavedQueryDetail detailOf(SavedQuery saved, RecordMatchRequest request) {
        return new SavedQueryDetail(
                saved.getId(),
                saved.getOwnerUserId(),
                saved.getName(),
                saved.getDescription(),
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                request);
    }

    private SavedQuery requireReadable(AppUser viewer, long id) {
        SavedQuery query = savedQueryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Saved query not found"));
        if (query.getOwnerUserId().equals(viewer.getId())) {
            return query;
        }
        if (!grantRepository.existsBySavedQueryIdAndGranteeUserId(id, viewer.getId())) {
            throw new AdminAccessDeniedException("Saved query is not visible to you");
        }
        AppUser owner = userRepository.findById(query.getOwnerUserId())
                .orElseThrow(() -> new IllegalStateException("Saved query owner missing"));
        adminAuthorizationService.assertCanManageUser(owner, viewer);
        return query;
    }

    private SavedQuery requireOwned(AppUser owner, long id) {
        SavedQuery query = savedQueryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Saved query not found"));
        if (!query.getOwnerUserId().equals(owner.getId())) {
            throw new AdminAccessDeniedException("Only the owner may change this saved query");
        }
        return query;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (name.length() > 256) {
            throw new IllegalArgumentException("Name must be at most 256 characters");
        }
    }

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void audit(AuditActionType action, String detail) {
        auditCapture.recordBestEffort(new AuditEventDraft(
                action,
                AuditOutcome.SUCCESS,
                scopeSummary.currentActorUserId(),
                null,
                detail,
                null,
                null,
                scopeSummary.summarizeCurrentOfficer()));
    }
}
