package com.fraudplatform.ingestion.controller;

import com.fraudplatform.ingestion.dto.TransactionRequest;
import com.fraudplatform.ingestion.dto.TransactionResponse;
import com.fraudplatform.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class TransactionController {

    private final IngestionService ingestionService;

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TransactionResponse submit(@Valid @RequestBody TransactionRequest request) {
        return ingestionService.ingest(request);
    }
}
