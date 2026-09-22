package com.pjl.bills.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OverrideRequest(
        @Pattern(regexp = "^(GREEN|AMBER|RED)$", message = "newStatus must be GREEN, AMBER, or RED")
        String newStatus,

        @NotBlank(message = "reason is required for status overrides")
        String reason
) {
}
