package com.fraudplatform.scoring.repository;

import com.fraudplatform.scoring.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.userId = :userId AND t.createdAt >= :since")
    long countByUserIdSince(String userId, Instant since);
}
