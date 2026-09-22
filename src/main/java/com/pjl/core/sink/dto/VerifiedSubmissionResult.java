package com.pjl.core.sink.dto;

import java.util.List;

/**
 * Complete, immutable result produced by a verification strategy.
 * <p>
 * This is a plain DTO — it is never persisted directly as a JPA entity.
 * The {@link com.pjl.core.sink.OutputSink} implementation decides how each
 * field maps to the underlying store.
 *
 * @param submissionId   the ID of the {@code Submission} that was verified
 * @param lineItems      every line item the strategy matched (may be empty, never null)
 * @param exceptions     every rule violation the strategy detected (may be empty, never null)
 * @param finalStatus    one of GREEN / AMBER / RED
 * @param warrantyStatus informational only: NOT_MENTIONED / STATED / DENIED
 */
public record VerifiedSubmissionResult(
        Long submissionId,
        List<MatchedLineItem> lineItems,
        List<VerificationException> exceptions,
        SubmissionStatus finalStatus,
        String warrantyStatus
) {
    public VerifiedSubmissionResult {
        lineItems  = List.copyOf(lineItems);   // defensive — make truly immutable
        exceptions = List.copyOf(exceptions);
    }
}
