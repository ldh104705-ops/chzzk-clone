package com.chzzk.broadcast;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long>, JpaSpecificationExecutor<Broadcast> {

    List<Broadcast> findAllByTypeAndStatusIn(BroadcastType type, List<BroadcastStatus> statuses);
}
