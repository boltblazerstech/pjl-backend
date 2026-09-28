package com.pjl.bills.dto;

import java.time.Instant;
import java.util.List;

public record SubmissionDetailDto(
        Long id,
        String categoryName,
        String status,
        Instant uploadedAt,
        String reviewerNotes,
        String warrantyStatus,
        List<DocumentDto> documents,
        List<AuditLogDto> auditLogs,
        List<AuditLogDto> exceptions,
        List<MatchedLineItemDto> matchedLineItems,
        String poGrnSource,
        Long poGrnImportBatchId
) {
}
