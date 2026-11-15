package com.chzzk.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** Newest first; the id breaks ties between comments created in the same instant. */
    Page<Comment> findByBroadcastIdOrderByCreatedAtDescIdDesc(Long broadcastId, Pageable pageable);
}
