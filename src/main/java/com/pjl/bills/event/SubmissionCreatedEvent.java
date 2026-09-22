package com.pjl.bills.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published after a Submission and all its Document rows have been persisted
 * and the DB transaction has committed. This ensures the async orchestrator
 * never tries to process a row that doesn't yet exist in the database.
 */
public class SubmissionCreatedEvent extends ApplicationEvent {

    private final Long submissionId;

    public SubmissionCreatedEvent(Object source, Long submissionId) {
        super(source);
        this.submissionId = submissionId;
    }

    public Long getSubmissionId() {
        return submissionId;
    }
}
