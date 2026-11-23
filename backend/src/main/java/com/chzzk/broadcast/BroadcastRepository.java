package com.chzzk.broadcast;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long>, JpaSpecificationExecutor<Broadcast> {

    List<Broadcast> findAllByTypeAndStatusIn(BroadcastType type, List<BroadcastStatus> statuses);

    /**
     * Atomic READY/LIVE -> ENDED. Returns 1 only for the caller that actually changed the row, so when several
     * backend instances race to end the same broadcast exactly one of them goes on to announce it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Broadcast b set b.status = :ended, b.endedAt = :now "
            + "where b.id = :id and b.type = :type and b.status in :active")
    int endIfActive(@Param("id") long id, @Param("type") BroadcastType type, @Param("ended") BroadcastStatus ended,
                    @Param("active") List<BroadcastStatus> active, @Param("now") Instant now);

    /** Loads owners in the same query: the list shows the owner's nickname and must not run one query per row. */
    @Override
    @EntityGraph(attributePaths = "owner")
    List<Broadcast> findAll(Specification<Broadcast> spec, Sort sort);
}
