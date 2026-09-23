package com.fraudplatform.casemanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerdictRequest(
        @NotBlank
        @Pattern(regexp = "CONFIRMED_FRAUD|FALSE_POSITIVE")
        String verdict
) {}
