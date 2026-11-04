package gov.rajasthan.smart.srse.lineage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "lineage_event")
public class LineageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_username", nullable = false, length = 128)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private LineageAction action;

    @Column(name = "audit_event_id")
    private Long auditEventId;

    @Column(name = "saved_query_id")
    private Long savedQueryId;

    @Column(name = "request_fingerprint", length = 128)
    private String requestFingerprint;

    @Column(name = "label", length = 512)
    private String label;

    protected LineageEventEntity() {
    }

    public LineageEventEntity(Instant occurredAt, String actorUsername, LineageAction action,
                              Long auditEventId, Long savedQueryId, String requestFingerprint, String label) {
        this.occurredAt = occurredAt;
        this.actorUsername = actorUsername;
        this.action = action;
        this.auditEventId = auditEventId;
        this.savedQueryId = savedQueryId;
        this.requestFingerprint = requestFingerprint;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public LineageAction getAction() {
        return action;
    }

    public Long getAuditEventId() {
        return auditEventId;
    }

    public Long getSavedQueryId() {
        return savedQueryId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getLabel() {
        return label;
    }
}
