package com.pjl.core.sink;

import com.pjl.bills.entity.MatchedLineItemEntity;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.repository.MatchedLineItemRepository;
import com.pjl.bills.repository.SubmissionRepository;
import com.pjl.core.entity.AuditLog;
import com.pjl.core.repository.AuditLogRepository;
import com.pjl.core.sink.dto.MatchedLineItem;
import com.pjl.core.sink.dto.VerificationException;
import com.pjl.core.sink.dto.VerifiedSubmissionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Primary {@link OutputSink} implementation — persists verification results
 * directly into PostgreSQL.
 * <p>
 * Responsibilities (all within a single transaction):
 * <ol>
 *   <li>Update the {@link Submission} status to GREEN / AMBER / RED.</li>
 *   <li>Persist one {@code matched_line_item} row per matched invoice line.</li>
 *   <li>Write one {@code audit_log} row per exception (action = "EXCEPTION").</li>
 *   <li>Write a final {@code audit_log} row recording the overall outcome
 *       (action = "VERIFIED").</li>
 * </ol>
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class PostgresOutputSink implements OutputSink {

    private static final String ENTITY_TYPE = "Submission";

    private final SubmissionRepository       submissionRepository;
    private final AuditLogRepository         auditLogRepository;
    private final MatchedLineItemRepository  matchedLineItemRepository;

    @Override
    @Transactional
    public void saveVerifiedSubmission(VerifiedSubmissionResult result) {
        log.info("Saving verification result for submission id={} status={}",
                result.submissionId(), result.finalStatus());

        // 1. Update Submission status
        Submission submission = submissionRepository.findById(result.submissionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Submission not found: " + result.submissionId()));

        submission.setStatus(result.finalStatus().name());
        if (result.warrantyStatus() != null) {
            submission.setWarrantyStatus(result.warrantyStatus());
        }
        submissionRepository.save(submission);

        // 2. Persist one matched_line_item row per matched line
        for (MatchedLineItem item : result.lineItems()) {
            MatchedLineItemEntity entity = new MatchedLineItemEntity();
            entity.setSubmission(submission);
            entity.setItemDescription(item.itemDescription());
            entity.setInvoiceQuantity(item.invoiceQuantity());
            entity.setInvoiceRate(item.invoiceRate());
            entity.setInvoiceAmount(item.invoiceAmount());
            entity.setPoQuantity(item.poQuantity());
            entity.setPoRate(item.poRate());
            entity.setGrnAcceptedQuantity(item.grnAcceptedQuantity());
            entity.setMatchConfidence(item.matchConfidence());
            entity.setExceptionReason(item.exceptionReason());
            matchedLineItemRepository.save(entity);
        }

        // 3. Persist one audit_log row for each exception
        for (VerificationException ex : result.exceptions()) {
            AuditLog exceptionLog = new AuditLog();
            exceptionLog.setEntityType(ENTITY_TYPE);
            exceptionLog.setEntityId(result.submissionId());
            exceptionLog.setAction("EXCEPTION");
            exceptionLog.setDetail("[" + ex.ruleName() + "] " + ex.reason());
            exceptionLog.setActor("system");
            auditLogRepository.save(exceptionLog);
            log.debug("Logged exception for submission {}: rule={} reason={}",
                    result.submissionId(), ex.ruleName(), ex.reason());
        }

        // 4. Write the final VERIFIED audit entry
        AuditLog verifiedLog = new AuditLog();
        verifiedLog.setEntityType(ENTITY_TYPE);
        verifiedLog.setEntityId(result.submissionId());
        verifiedLog.setAction("VERIFIED");
        verifiedLog.setDetail(String.format(
                "Verification complete. Status=%s, lineItems=%d, exceptions=%d",
                result.finalStatus(),
                result.lineItems().size(),
                result.exceptions().size()
        ));
        verifiedLog.setActor("system");
        auditLogRepository.save(verifiedLog);

        log.info("Verification result persisted for submission id={}: {} line items, {} exceptions",
                result.submissionId(), result.lineItems().size(), result.exceptions().size());
    }
}
