package com.fraudplatform.scoring.dlq;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DlqMessageRepository extends JpaRepository<DlqMessage, Long> {
    List<DlqMessage> findByReplayedFalse();
}
