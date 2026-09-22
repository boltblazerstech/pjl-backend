package com.pjl.bills.dto;

import java.time.Instant;

public record SubmissionSummaryDto(
        Long id,
        String categoryName,
        String status,
        Instant uploadedAt
) {
}
