package gov.rajasthan.smart.srse.lineage;

import gov.rajasthan.smart.srse.analysis.AdditionalJoin;
import gov.rajasthan.smart.srse.analysis.DisplayColumn;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.audit.QueryShapeAudit;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.metadata.ColumnMaskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists in-app lineage graphs for match runs, exports, and saved queries.
 * Best-effort: failures are logged and never fail the officer request.
 */
@Service
public class LineageCaptureService {

    private static final Logger log = LoggerFactory.getLogger(LineageCaptureService.class);

    private final LineageEventRepository events;
    private final LineageNodeRepository nodes;
    private final LineageEdgeRepository edges;
    private final AnalysisColumnMetadataRepository columnMetadata;

    public LineageCaptureService(
            LineageEventRepository events,
            LineageNodeRepository nodes,
            LineageEdgeRepository edges,
            AnalysisColumnMetadataRepository columnMetadata) {
        this.events = events;
        this.nodes = nodes;
        this.edges = edges;
        this.columnMetadata = columnMetadata;
    }

    @Transactional
    public void captureRunBestEffort(RecordMatchRequest req, String actorUsername, String queryId) {
        captureBestEffort(req, actorUsername, LineageAction.RUN, null, null, "Run " + nullToDash(queryId), null);
    }

    @Transactional
    public void captureExportBestEffort(RecordMatchRequest req, String actorUsername, String formatLabel) {
        captureBestEffort(req, actorUsername, LineageAction.EXPORT, null, null,
                "Export " + nullToDash(formatLabel), formatLabel);
    }

    @Transactional
    public void captureSavedBestEffort(RecordMatchRequest req, String actorUsername, Long savedQueryId, String name) {
        captureBestEffort(req, actorUsername, LineageAction.SAVED, null, savedQueryId,
                "Saved query " + nullToDash(name), null);
    }

    private void captureBestEffort(
            RecordMatchRequest req,
            String actorUsername,
            LineageAction action,
            Long auditEventId,
            Long savedQueryId,
            String label,
            String exportFormat) {
        try {
            persist(req, actorUsername == null || actorUsername.isBlank() ? "unknown" : actorUsername,
                    action, auditEventId, savedQueryId, label, exportFormat);
        } catch (RuntimeException ex) {
            log.warn("Lineage capture failed for {}: {}", action, ex.toString());
        }
    }

    private void persist(
            RecordMatchRequest req,
            String actorUsername,
            LineageAction action,
            Long auditEventId,
            Long savedQueryId,
            String label,
            String exportFormat) {
        String fingerprint = fingerprint(QueryShapeAudit.targetTablesFrom(req), action.name());
        LineageEventEntity event = events.save(new LineageEventEntity(
                Instant.now(), actorUsername, action, auditEventId, savedQueryId, fingerprint, label));

        Map<String, LineageNodeEntity> nodeMap = new LinkedHashMap<>();
        List<LineageEdgeEntity> edgeList = new ArrayList<>();

        String actorKey = "actor:" + actorUsername;
        addNode(nodeMap, actorKey, LineageNodeType.ACTOR, actorUsername, null);

        String runKey = "run:" + event.getId();
        addNode(nodeMap, runKey, action == LineageAction.SAVED ? LineageNodeType.SAVED_QUERY : LineageNodeType.QUERY_RUN,
                label, "{\"action\":\"" + action.name() + "\"}");
        addEdge(edgeList, event.getId(), actorKey, runKey, LineageEdgeType.RAN);

        if (savedQueryId != null) {
            String sqKey = "saved:" + savedQueryId;
            addNode(nodeMap, sqKey, LineageNodeType.SAVED_QUERY, label, "{\"savedQueryId\":" + savedQueryId + "}");
            addEdge(edgeList, event.getId(), runKey, sqKey, LineageEdgeType.USED_BY_QUERY);
        }

        if (action == LineageAction.EXPORT) {
            String exportKey = "export:" + event.getId();
            addNode(nodeMap, exportKey, LineageNodeType.EXPORT,
                    exportFormat == null ? "export" : exportFormat,
                    "{\"format\":\"" + escapeJson(exportFormat == null ? "" : exportFormat) + "\"}");
            addEdge(edgeList, event.getId(), actorKey, exportKey, LineageEdgeType.EXPORTED);
            addEdge(edgeList, event.getId(), runKey, exportKey, LineageEdgeType.EXPORTED);
        }

        Set<String> tables = new LinkedHashSet<>();
        addCriteria(req.sourceCriteria(), "source_", tables, nodeMap, edgeList, event.getId(), runKey);
        addCriteria(req.targetCriteria(), "target_", tables, nodeMap, edgeList, event.getId(), runKey);
        if (req.joinGroups() != null) {
            req.joinGroups().forEach(g -> {
                addCriteria(g.source(), "source_", tables, nodeMap, edgeList, event.getId(), runKey);
                addCriteria(g.target(), "target_", tables, nodeMap, edgeList, event.getId(), runKey);
            });
        }
        addDisplays(req.sourceDisplayColumns(), "source_", tables, nodeMap, edgeList, event.getId(), runKey);
        addDisplays(req.targetDisplayColumns(), "target_", tables, nodeMap, edgeList, event.getId(), runKey);
        if (req.additionalJoins() != null) {
            int n = 1;
            for (AdditionalJoin extra : req.additionalJoins()) {
                String tableQ = extra.catalog() + "." + extra.schema() + "." + extra.table();
                ensureTable(tableQ, extra.catalog(), tables, nodeMap, edgeList, event.getId(), runKey);
                String attrKey = attrKey(tableQ, extra.joinColumn());
                addAttribute(attrKey, tableQ, extra.joinColumn(), nodeMap, edgeList, event.getId());
                addEdge(edgeList, event.getId(), attrKey, runKey, LineageEdgeType.JOINED_ON);
                String resultKey = "result:extra" + n + "_" + extra.joinColumn();
                addNode(nodeMap, resultKey, LineageNodeType.RESULT_COLUMN, "extra" + n + "_" + extra.joinColumn(), null);
                addEdge(edgeList, event.getId(), attrKey, resultKey, LineageEdgeType.PROJECTED_TO);
                addEdge(edgeList, event.getId(), resultKey, runKey, LineageEdgeType.USED_BY_QUERY);
                for (String col : extra.displayColumns()) {
                    if (col.equals(extra.joinColumn())) {
                        continue;
                    }
                    String dAttr = attrKey(tableQ, col);
                    addAttribute(dAttr, tableQ, col, nodeMap, edgeList, event.getId());
                    String dRes = "result:extra" + n + "_" + col;
                    addNode(nodeMap, dRes, LineageNodeType.RESULT_COLUMN, "extra" + n + "_" + col, null);
                    addEdge(edgeList, event.getId(), dAttr, dRes, LineageEdgeType.PROJECTED_TO);
                    addEdge(edgeList, event.getId(), dRes, runKey, LineageEdgeType.USED_BY_QUERY);
                }
                n++;
            }
        }

        Long eventId = event.getId();
        for (LineageNodeEntity node : nodeMap.values()) {
            nodes.save(new LineageNodeEntity(eventId, node.getNodeKey(), node.getNodeType(), node.getLabel(), node.getPayloadJson()));
        }
        for (LineageEdgeEntity edge : edgeList) {
            edges.save(new LineageEdgeEntity(eventId, edge.getFromKey(), edge.getToKey(), edge.getEdgeType()));
        }
    }

    private void addCriteria(
            List<MatchCriterion> criteria,
            String resultPrefix,
            Set<String> tables,
            Map<String, LineageNodeEntity> nodeMap,
            List<LineageEdgeEntity> edgeList,
            Long eventId,
            String runKey) {
        if (criteria == null) {
            return;
        }
        for (MatchCriterion c : criteria) {
            String tableQ = c.catalog() + "." + c.schema() + "." + c.table();
            ensureTable(tableQ, c.catalog(), tables, nodeMap, edgeList, eventId, runKey);
            String attrKey = attrKey(tableQ, c.column());
            addAttribute(attrKey, tableQ, c.column(), nodeMap, edgeList, eventId);
            addEdge(edgeList, eventId, attrKey, runKey, LineageEdgeType.JOINED_ON);
            String outAlias = "result:" + resultPrefix + c.column();
            addNode(nodeMap, outAlias, LineageNodeType.RESULT_COLUMN, resultPrefix + c.column(), null);
            addEdge(edgeList, eventId, attrKey, outAlias, LineageEdgeType.PROJECTED_TO);
            addEdge(edgeList, eventId, outAlias, runKey, LineageEdgeType.USED_BY_QUERY);
        }
    }

    private void addDisplays(
            List<DisplayColumn> display,
            String prefix,
            Set<String> tables,
            Map<String, LineageNodeEntity> nodeMap,
            List<LineageEdgeEntity> edgeList,
            Long eventId,
            String runKey) {
        if (display == null) {
            return;
        }
        for (DisplayColumn d : display) {
            String tableQ = d.catalog() + "." + d.schema() + "." + d.table();
            ensureTable(tableQ, d.catalog(), tables, nodeMap, edgeList, eventId, runKey);
            String attrKey = attrKey(tableQ, d.column());
            addAttribute(attrKey, tableQ, d.column(), nodeMap, edgeList, eventId);
            String resultKey = "result:" + prefix + d.column();
            addNode(nodeMap, resultKey, LineageNodeType.RESULT_COLUMN, prefix + d.column(), null);
            addEdge(edgeList, eventId, attrKey, resultKey, LineageEdgeType.PROJECTED_TO);
            addEdge(edgeList, eventId, resultKey, runKey, LineageEdgeType.USED_BY_QUERY);
        }
    }

    private void ensureTable(
            String tableQ,
            String catalog,
            Set<String> tables,
            Map<String, LineageNodeEntity> nodeMap,
            List<LineageEdgeEntity> edgeList,
            Long eventId,
            String runKey) {
        if (!tables.add(tableQ)) {
            return;
        }
        String sourceKey = "source:" + catalog;
        addNode(nodeMap, sourceKey, LineageNodeType.DATA_SOURCE, catalog, null);
        String tableKey = "table:" + tableQ;
        addNode(nodeMap, tableKey, LineageNodeType.TABLE, tableQ, null);
        addEdge(edgeList, eventId, sourceKey, tableKey, LineageEdgeType.CONTAINS);
        addEdge(edgeList, eventId, tableKey, runKey, LineageEdgeType.USED_BY_QUERY);
    }

    private void addAttribute(
            String attrKey,
            String tableQ,
            String column,
            Map<String, LineageNodeEntity> nodeMap,
            List<LineageEdgeEntity> edgeList,
            Long eventId) {
        if (nodeMap.containsKey(attrKey)) {
            return;
        }
        String[] parts = tableQ.split("\\.", 3);
        String payload = null;
        if (parts.length == 3) {
            payload = columnMetadata
                    .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(parts[0], parts[1], parts[2], column)
                    .map(AnalysisColumnMetadata::getMaskPolicy)
                    .filter(p -> p != ColumnMaskPolicy.NONE)
                    .map(p -> "{\"maskPolicy\":\"" + p.name() + "\"}")
                    .orElse(null);
        }
        addNode(nodeMap, attrKey, LineageNodeType.ATTRIBUTE, tableQ + "." + column, payload);
        addEdge(edgeList, eventId, "table:" + tableQ, attrKey, LineageEdgeType.CONTAINS);
    }

    private static void addNode(
            Map<String, LineageNodeEntity> nodeMap,
            String key,
            LineageNodeType type,
            String label,
            String payload) {
        nodeMap.putIfAbsent(key, new LineageNodeEntity(0L, key, type, label, payload));
    }

    private static void addEdge(
            List<LineageEdgeEntity> edgeList, Long eventId, String from, String to, LineageEdgeType type) {
        edgeList.add(new LineageEdgeEntity(eventId, from, to, type));
    }

    private static String attrKey(String tableQ, String column) {
        return "attr:" + tableQ + "." + column;
    }

    private static String fingerprint(String tables, String action) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((action + "|" + tables).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception ex) {
            return action + ":" + Integer.toHexString(tables.hashCode());
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
