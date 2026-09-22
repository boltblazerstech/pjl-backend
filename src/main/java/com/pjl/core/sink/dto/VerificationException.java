package com.pjl.core.sink.dto;

/**
 * A single exception (rule violation) raised during bill verification.
 *
 * @param ruleName human-readable rule identifier (e.g. "RATE_MISMATCH", "MISSING_GRN")
 * @param reason   full explanation of why this rule fired
 */
public record VerificationException(
        String ruleName,
        String reason
) {
}
