package com.fraudplatform.rules.repository;

import com.fraudplatform.rules.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleRepository extends JpaRepository<Rule, Long> {
    List<Rule> findByActiveTrue();
}
