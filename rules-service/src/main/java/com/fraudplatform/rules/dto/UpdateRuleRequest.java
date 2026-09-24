package com.fraudplatform.rules.dto;

import lombok.Data;

@Data
public class UpdateRuleRequest {
    public String  conditionExpression;  // null = no change
    public Integer weight;               // null = no change
    public Boolean active;               // null = no change
}
