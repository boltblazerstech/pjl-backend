package com.pjl.bills;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.Submission;
import com.pjl.core.sink.dto.VerifiedSubmissionResult;

import java.util.List;

/**
 * Strategy interface for verifying a bill submission.
 * <p>
 * Each concrete implementation handles one bill category (e.g. "spares").
 * The key returned by {@link #getKey()} must match the value stored in
 * {@code BillCategory.verificationStrategyKey} so the
 * {@link VerificationStrategyRegistry} can route submissions to the right
 * implementation at runtime.
 * <p>
 * To support a new category, implement this interface, annotate the class
 * with {@code @Component} (or any Spring stereotype), and give it a unique
 * key. No other class needs to change.
 */
public interface VerificationStrategy {

    /**
     * Run the full verification pipeline for the given submission.
     *
     * @param submission the submission being verified
     * @param documents  all documents attached to this submission
     * @return the complete, immutable verification result
     */
    VerifiedSubmissionResult verify(Submission submission, List<Document> documents);

    /**
     * Returns the unique key that identifies this strategy.
     * Must match {@code BillCategory.verificationStrategyKey} exactly
     * (case-sensitive), e.g. {@code "spares"}.
     *
     * @return the strategy key
     */
    String getKey();
}
