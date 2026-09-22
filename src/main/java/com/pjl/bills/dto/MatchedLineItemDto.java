package com.pjl.bills.dto;

import java.math.BigDecimal;

public record MatchedLineItemDto(
        Long       id,
        String     itemDescription,
        Integer    invoiceQuantity,
        BigDecimal invoiceRate,
        BigDecimal invoiceAmount,
        Integer    poQuantity,
        BigDecimal poRate,
        Integer    grnAcceptedQuantity,
        Double     matchConfidence,
        String     exceptionReason
) {
}
