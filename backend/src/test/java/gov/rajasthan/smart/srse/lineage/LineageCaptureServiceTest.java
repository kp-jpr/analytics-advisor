package gov.rajasthan.smart.srse.lineage;

import gov.rajasthan.smart.srse.analysis.DisplayColumn;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineageCaptureServiceTest {

    @Mock LineageEventRepository events;
    @Mock LineageNodeRepository nodes;
    @Mock LineageEdgeRepository edges;
    @Mock AnalysisColumnMetadataRepository columnMetadata;

    @Test
    void runCaptureWritesTablesAttributesAndActor() {
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(events.save(any())).thenAnswer(invocation -> {
            LineageEventEntity incoming = invocation.getArgument(0);
            return new LineageEventEntity(
                    incoming.getOccurredAt(),
                    incoming.getActorUsername(),
                    incoming.getAction(),
                    incoming.getAuditEventId(),
                    incoming.getSavedQueryId(),
                    incoming.getRequestFingerprint(),
                    incoming.getLabel()) {
                @Override
                public Long getId() {
                    return 9L;
                }
            };
        });

        LineageCaptureService service = new LineageCaptureService(events, nodes, edges, columnMetadata);
        RecordMatchRequest req = new RecordMatchRequest(
                List.of(new MatchCriterion("iceberg", "srse", "beneficiary", "id", null)),
                List.of(new MatchCriterion("iceberg", "srse", "beneficiary", "id", null)),
                List.of(new DisplayColumn("iceberg", "srse", "beneficiary", "district")),
                List.of(),
                List.of(),
                false, null, null, List.of(), false,
                null, null, false, List.of(), List.of(), null, false, List.of());

        service.captureRunBestEffort(req, "officer1", "q-1");

        ArgumentCaptor<LineageNodeEntity> nodeCaptor = ArgumentCaptor.forClass(LineageNodeEntity.class);
        verify(nodes, atLeastOnce()).save(nodeCaptor.capture());
        List<String> keys = nodeCaptor.getAllValues().stream().map(LineageNodeEntity::getNodeKey).toList();
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("table:")));
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("attr:")));
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("actor:")));
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("result:")));

        ArgumentCaptor<LineageEdgeEntity> edgeCaptor = ArgumentCaptor.forClass(LineageEdgeEntity.class);
        verify(edges, atLeastOnce()).save(edgeCaptor.capture());
        assertEquals(true, edgeCaptor.getAllValues().stream()
                .anyMatch(e -> e.getEdgeType() == LineageEdgeType.JOINED_ON));
    }
}
