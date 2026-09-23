package com.fraudplatform.ingestion.repository;

import com.fraudplatform.ingestion.entity.CaseDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseDecisionRepository extends JpaRepository<CaseDecision, String> {

    List<CaseDecision> findByStatus(String status);
}
