package com.fraudplatform.ingestion.service;

import com.fraudplatform.ingestion.dto.FraudRequest;
import com.fraudplatform.ingestion.dto.FraudResponse;
import com.fraudplatform.ingestion.entity.CaseDecision;
import com.fraudplatform.ingestion.entity.Transaction;
import com.fraudplatform.ingestion.repository.CaseDecisionRepository;
import com.fraudplatform.ingestion.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CaseDecisionRepository caseDecisionRepository;
    private final FraudRulesEngine rulesEngine;
    private final GeoStubService geoStubService;

    @Transactional
    public FraudResponse process(FraudRequest req) {
        // 1. Persist transaction first so velocity query counts it
        Transaction tx = new Transaction();
        tx.setUserId(req.getUserId());
        tx.setAmount(req.getAmount());
        tx.setMerchantId(req.getMerchantId());
        tx.setDeviceId(req.getDeviceId());
        tx.setIpAddress(req.getIpAddress());
        tx.setCardLast4(req.getCardLast4());
        tx.setCardCountry(req.getCardCountry());
        tx.setIpCountry(geoStubService.countryForIp(req.getIpAddress()));
        tx = transactionRepository.save(tx);

        // 2. Evaluate rules
        FraudRulesEngine.RuleResult result = rulesEngine.evaluate(req);

        // 3. Persist decision
        CaseDecision decision = new CaseDecision();
        decision.setTransactionId(tx.getId());
        decision.setUserId(req.getUserId());
        decision.setStatus(result.decision());
        decision.setRiskScore(result.riskScore());
        decision.setRulesTriggered(String.join(",", result.triggeredRules()));
        caseDecisionRepository.save(decision);

        return FraudResponse.builder()
                .transactionId(tx.getId())
                .decision(result.decision())
                .riskScore(result.riskScore())
                .rulesTriggered(result.triggeredRules())
                .build();
    }

    public List<CaseDecision> getCasesByStatus(String status) {
        return caseDecisionRepository.findByStatus(status.toUpperCase());
    }
}
