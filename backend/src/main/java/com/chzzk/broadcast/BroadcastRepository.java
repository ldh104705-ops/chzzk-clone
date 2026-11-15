package com.chzzk.broadcast;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long>, JpaSpecificationExecutor<Broadcast> {

    List<Broadcast> findAllByTypeAndStatusIn(BroadcastType type, List<BroadcastStatus> statuses);

    /** Loads owners in the same query: the list shows the owner's nickname and must not run one query per row. */
    @Override
    @EntityGraph(attributePaths = "owner")
    List<Broadcast> findAll(Specification<Broadcast> spec, Sort sort);
}
