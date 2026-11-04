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
@Table(name = "lineage_node")
public class LineageNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "node_key", nullable = false, length = 512)
    private String nodeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 32)
    private LineageNodeType nodeType;

    @Column(name = "label", nullable = false, length = 512)
    private String label;

    @Column(name = "payload_json", length = 4000)
    private String payloadJson;

    protected LineageNodeEntity() {
    }

    public LineageNodeEntity(Long eventId, String nodeKey, LineageNodeType nodeType, String label, String payloadJson) {
        this.eventId = eventId;
        this.nodeKey = nodeKey;
        this.nodeType = nodeType;
        this.label = label;
        this.payloadJson = payloadJson;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public LineageNodeType getNodeType() {
        return nodeType;
    }

    public String getLabel() {
        return label;
    }

    public String getPayloadJson() {
        return payloadJson;
    }
}
