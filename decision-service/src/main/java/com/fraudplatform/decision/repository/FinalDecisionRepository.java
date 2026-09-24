package com.fraudplatform.decision.repository;

import com.fraudplatform.decision.entity.FinalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinalDecisionRepository extends JpaRepository<FinalDecision, String> {
    List<FinalDecision> findByOutcome(String outcome);
}
