package com.pjl.core.sink.dto;

import java.math.BigDecimal;

/**
 * A single line item matched during bill verification.
 * Carries both invoice-side and PO/GRN-side values so
 * they can be compared in the UI and persisted in full.
 */
public record MatchedLineItem(
        String  itemDescription,
        String  matchedPoRef,
        String  matchedGrnRef,

        // Invoice-side
        int     invoiceQuantity,
        BigDecimal invoiceRate,
        BigDecimal invoiceAmount,

        // PO-side
        int        poQuantity,
        BigDecimal poRate,

        // GRN-side
        int grnAcceptedQuantity,

        // Match quality
        double  matchConfidence,

        // Null when no exception was raised specifically for this line
        String  exceptionReason
) {
}
