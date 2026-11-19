package com.pjl.bills.dto;

import java.math.BigDecimal;

public record SubmissionInvoiceItemDto(
        Long submissionId,
        String invoiceNo,
        String supplierName,
        BigDecimal totalAmount,
        String status,
        Integer lineItemCount,
        Integer exceptionCount
) {
}
