package com.pjl.core.sink;

import com.pjl.core.sink.dto.VerifiedSubmissionResult;

/**
 * Output sink for persisting verification results.
 * <p>
 * Implementations decide where and how the result is stored (e.g. Postgres,
 * Ramco ERP, a message queue). Callers never depend on a concrete class —
 * always inject this interface so a {@code RamcoOutputSink} can be swapped in
 * later with zero changes to verification logic.
 */
public interface OutputSink {

    void saveVerifiedSubmission(VerifiedSubmissionResult result);
}
