package com.fraudplatform.rules.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateRuleRequest {
    @NotBlank public String name;
    @NotBlank public String conditionExpression;
    @Min(1) @Max(100) public int weight;
}
