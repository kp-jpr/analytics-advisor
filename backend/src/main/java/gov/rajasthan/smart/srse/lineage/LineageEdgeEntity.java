package gov.rajasthan.smart.srse.lineage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "lineage_edge")
public class LineageEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "from_key", nullable = false, length = 512)
    private String fromKey;

    @Column(name = "to_key", nullable = false, length = 512)
    private String toKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "edge_type", nullable = false, length = 32)
    private LineageEdgeType edgeType;

    protected LineageEdgeEntity() {
    }

    public LineageEdgeEntity(Long eventId, String fromKey, String toKey, LineageEdgeType edgeType) {
        this.eventId = eventId;
        this.fromKey = fromKey;
        this.toKey = toKey;
        this.edgeType = edgeType;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getFromKey() {
        return fromKey;
    }

    public String getToKey() {
        return toKey;
    }

    public LineageEdgeType getEdgeType() {
        return edgeType;
    }
}
