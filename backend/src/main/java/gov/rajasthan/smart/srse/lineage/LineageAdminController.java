package gov.rajasthan.smart.srse.lineage;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/lineage")
public class LineageAdminController {

    private final LineageEventRepository events;
    private final LineageNodeRepository nodes;
    private final LineageEdgeRepository edges;

    public LineageAdminController(
            LineageEventRepository events,
            LineageNodeRepository nodes,
            LineageEdgeRepository edges) {
        this.events = events;
        this.nodes = nodes;
        this.edges = edges;
    }

    @GetMapping("/events")
    public List<LineageEventSummary> listEvents(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) Long savedQueryId,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String column,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        PageRequest page = PageRequest.of(0, size);
        List<LineageEventEntity> found;
        if (savedQueryId != null) {
            found = events.findBySavedQueryIdOrderByOccurredAtDesc(savedQueryId, page);
        } else if (actor != null && !actor.isBlank()) {
            found = events.findByActorUsernameIgnoreCaseOrderByOccurredAtDesc(actor.trim(), page);
        } else if (table != null && !table.isBlank()) {
            String key = column != null && !column.isBlank()
                    ? "attr:" + table.trim() + "." + column.trim()
                    : "table:" + table.trim();
            found = events.findByNodeKey(key, page);
        } else {
            Instant end = to != null ? to : Instant.now();
            Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
            found = events.findByOccurredAtBetweenOrderByOccurredAtDesc(start, end, page);
        }
        return found.stream().map(LineageEventSummary::from).toList();
    }

    @GetMapping("/events/{id}")
    public LineageGraphView eventGraph(@PathVariable long id) {
        LineageEventEntity event = events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lineage event not found"));
        return graphForEvents(List.of(event));
    }

    @GetMapping("/graph")
    public LineageGraphView aggregatedGraph(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String column,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "25") int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        PageRequest page = PageRequest.of(0, size);
        List<LineageEventEntity> found;
        if (actor != null && !actor.isBlank()) {
            found = events.findByActorUsernameIgnoreCaseOrderByOccurredAtDesc(actor.trim(), page);
        } else if (table != null && !table.isBlank()) {
            String key = column != null && !column.isBlank()
                    ? "attr:" + table.trim() + "." + column.trim()
                    : "table:" + table.trim();
            found = events.findByNodeKey(key, page);
        } else {
            Instant end = Instant.now();
            found = events.findByOccurredAtBetweenOrderByOccurredAtDesc(end.minus(14, ChronoUnit.DAYS), end, page);
        }
        return graphForEvents(found);
    }

    private LineageGraphView graphForEvents(List<LineageEventEntity> found) {
        if (found.isEmpty()) {
            return new LineageGraphView(List.of(), List.of(), List.of());
        }
        List<Long> ids = found.stream().map(LineageEventEntity::getId).toList();
        Map<String, LineageNodeView> mergedNodes = new LinkedHashMap<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        List<LineageEdgeView> mergedEdges = new ArrayList<>();
        for (LineageNodeEntity node : nodes.findByEventIdIn(ids)) {
            mergedNodes.putIfAbsent(node.getNodeKey(), LineageNodeView.from(node));
        }
        for (LineageEdgeEntity edge : edges.findByEventIdIn(ids)) {
            String key = edge.getFromKey() + ">" + edge.getToKey() + ">" + edge.getEdgeType();
            if (edgeKeys.add(key)) {
                mergedEdges.add(LineageEdgeView.from(edge));
            }
        }
        return new LineageGraphView(
                found.stream().map(LineageEventSummary::from).toList(),
                List.copyOf(mergedNodes.values()),
                List.copyOf(mergedEdges));
    }

    public record LineageEventSummary(
            long id,
            Instant occurredAt,
            String actorUsername,
            String action,
            Long auditEventId,
            Long savedQueryId,
            String requestFingerprint,
            String label) {
        static LineageEventSummary from(LineageEventEntity entity) {
            return new LineageEventSummary(
                    entity.getId(),
                    entity.getOccurredAt(),
                    entity.getActorUsername(),
                    entity.getAction().name(),
                    entity.getAuditEventId(),
                    entity.getSavedQueryId(),
                    entity.getRequestFingerprint(),
                    entity.getLabel());
        }
    }

    public record LineageNodeView(String key, String type, String label, String payloadJson) {
        static LineageNodeView from(LineageNodeEntity entity) {
            return new LineageNodeView(
                    entity.getNodeKey(), entity.getNodeType().name(), entity.getLabel(), entity.getPayloadJson());
        }
    }

    public record LineageEdgeView(String fromKey, String toKey, String type) {
        static LineageEdgeView from(LineageEdgeEntity entity) {
            return new LineageEdgeView(entity.getFromKey(), entity.getToKey(), entity.getEdgeType().name());
        }
    }

    public record LineageGraphView(
            List<LineageEventSummary> events,
            List<LineageNodeView> nodes,
            List<LineageEdgeView> edges) {
    }
}
