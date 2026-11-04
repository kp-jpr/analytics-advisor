package gov.rajasthan.smart.srse.lineage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface LineageEdgeRepository extends JpaRepository<LineageEdgeEntity, Long> {

    List<LineageEdgeEntity> findByEventId(Long eventId);

    List<LineageEdgeEntity> findByEventIdIn(Collection<Long> eventIds);
}
