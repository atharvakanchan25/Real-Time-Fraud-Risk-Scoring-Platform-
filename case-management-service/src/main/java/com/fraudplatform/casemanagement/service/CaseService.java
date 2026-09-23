package com.fraudplatform.casemanagement.service;

import com.fraudplatform.casemanagement.entity.AuditLog;
import com.fraudplatform.casemanagement.entity.FraudCase;
import com.fraudplatform.casemanagement.repository.AuditLogRepository;
import com.fraudplatform.casemanagement.repository.FraudCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaseService {

    private final FraudCaseRepository caseRepository;
    private final AuditLogRepository auditLogRepository;

    public Page<FraudCase> getCases(String status, Pageable pageable) {
        return status != null
                ? caseRepository.findByStatus(status, pageable)
                : caseRepository.findAll(pageable);
    }

    @Transactional
    public FraudCase submitVerdict(Long caseId, String verdict, String analyst) {
        FraudCase fraudCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + caseId));

        if ("CLOSED".equals(fraudCase.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case already closed: " + caseId);
        }

        fraudCase.setVerdict(verdict);
        fraudCase.setStatus("CLOSED");
        fraudCase.setReviewedBy(analyst);
        fraudCase.setReviewedAt(Instant.now());
        caseRepository.save(fraudCase);

        AuditLog audit = new AuditLog();
        audit.setCaseId(caseId);
        audit.setTransactionId(fraudCase.getTransactionId());
        audit.setAnalyst(analyst);
        audit.setVerdict(verdict);
        auditLogRepository.save(audit);

        log.info("AUDIT: analyst={} caseId={} txId={} verdict={} at={}",
                analyst, caseId, fraudCase.getTransactionId(), verdict, audit.getCreatedAt());

        return fraudCase;
    }
}
