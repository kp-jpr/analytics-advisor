package gov.rajasthan.smart.srse.lineage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface LineageNodeRepository extends JpaRepository<LineageNodeEntity, Long> {

    List<LineageNodeEntity> findByEventId(Long eventId);

    List<LineageNodeEntity> findByEventIdIn(Collection<Long> eventIds);
}
