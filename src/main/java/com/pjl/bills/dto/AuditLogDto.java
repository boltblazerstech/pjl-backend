package com.pjl.bills.dto;

import java.time.Instant;

public record AuditLogDto(
        Long id,
        String action,
        String detail,
        String actor,
        Instant createdAt
) {
}
