package com.pjl.core.sink.dto;

/**
 * Final verification verdict for a submission.
 * <p>
 * GREEN  – all checks passed, no exceptions<br>
 * AMBER  – minor exceptions that need human review<br>
 * RED    – critical exceptions; submission should be rejected
 */
public enum SubmissionStatus {
    GREEN,
    AMBER,
    RED
}
