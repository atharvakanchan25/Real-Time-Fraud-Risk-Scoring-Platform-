package com.fraudplatform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackEvent {
    private String caseId;
    private String transactionId;
    private String userId;
    private String deviceId;
    /** CONFIRMED_FRAUD | FALSE_POSITIVE */
    private String verdict;
    private String analyst;
    private Instant decidedAt;
}
