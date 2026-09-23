package com.fraudplatform.ingestion.controller;

import com.fraudplatform.ingestion.dto.FraudRequest;
import com.fraudplatform.ingestion.dto.FraudResponse;
import com.fraudplatform.ingestion.entity.CaseDecision;
import com.fraudplatform.ingestion.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public FraudResponse submit(@Valid @RequestBody FraudRequest request) {
        return transactionService.process(request);
    }

    @GetMapping("/cases")
    public List<CaseDecision> cases(@RequestParam(defaultValue = "REVIEW") String status) {
        return transactionService.getCasesByStatus(status);
    }
}
