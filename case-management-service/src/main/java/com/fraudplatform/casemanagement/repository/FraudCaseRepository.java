package com.fraudplatform.casemanagement.repository;

import com.fraudplatform.casemanagement.entity.FraudCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCase, Long> {
    Page<FraudCase> findByStatus(String status, Pageable pageable);
    boolean existsByTransactionId(String transactionId);
}
