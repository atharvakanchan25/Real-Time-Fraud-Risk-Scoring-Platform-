package com.fraudplatform.casemanagement.controller;

import com.fraudplatform.casemanagement.dto.VerdictRequest;
import com.fraudplatform.casemanagement.entity.FraudCase;
import com.fraudplatform.casemanagement.service.CaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;

    /** ANALYST and ADMIN can list cases; optionally filter by status=OPEN|CLOSED */
    @GetMapping
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public Page<FraudCase> getCases(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return caseService.getCases(status, pageable);
    }

    /** ANALYST and ADMIN can submit a verdict */
    @PostMapping("/{id}/verdict")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public FraudCase submitVerdict(
            @PathVariable Long id,
            @Valid @RequestBody VerdictRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return caseService.submitVerdict(id, request.verdict(), principal.getUsername());
    }
}
