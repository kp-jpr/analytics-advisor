package gov.rajasthan.smart.srse.lineage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LineageEventRepository extends JpaRepository<LineageEventEntity, Long> {

    List<LineageEventEntity> findByOccurredAtBetweenOrderByOccurredAtDesc(
            Instant from, Instant to, Pageable pageable);

    List<LineageEventEntity> findByActorUsernameIgnoreCaseOrderByOccurredAtDesc(
            String actorUsername, Pageable pageable);

    List<LineageEventEntity> findBySavedQueryIdOrderByOccurredAtDesc(Long savedQueryId, Pageable pageable);

    @Query("""
            select e from LineageEventEntity e
            where e.id in (
              select n.eventId from LineageNodeEntity n where n.nodeKey = :nodeKey
            )
            order by e.occurredAt desc
            """)
    List<LineageEventEntity> findByNodeKey(@Param("nodeKey") String nodeKey, Pageable pageable);
}
