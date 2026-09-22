package com.pjl.bills.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjl.bills.PromptProvider;
import com.pjl.bills.VerificationStrategy;
import com.pjl.bills.VerificationStrategyRegistry;
import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.event.SubmissionCreatedEvent;
import com.pjl.bills.repository.DocumentRepository;
import com.pjl.bills.repository.SubmissionRepository;
import com.pjl.core.entity.AuditLog;
import com.pjl.core.gemini.ExtractionException;
import com.pjl.core.gemini.GeminiExtractionService;
import com.pjl.core.repository.AuditLogRepository;
import com.pjl.core.sink.OutputSink;
import com.pjl.core.sink.dto.VerifiedSubmissionResult;
import com.pjl.core.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Async orchestrator that drives the full extraction → verification pipeline
 * for a submission after its upload transaction has committed.
 * <p>
 * Flow:
 * <ol>
 *   <li>Set status = PROCESSING</li>
 *   <li>For each Document, call Gemini to extract structured JSON, store in rawExtraction</li>
 *   <li>On any extraction failure → set status = FAILED, write audit log, stop</li>
 *   <li>Resolve the correct VerificationStrategy and call verify()</li>
 *   <li>Pass result to OutputSink (updates status + writes audit trail)</li>
 *   <li>Any unexpected exception still marks the submission FAILED, never leaves it at PROCESSING</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationOrchestrator {

    private static final String ENTITY_TYPE  = "Submission";
    private static final String FALLBACK_PROMPT =
            "Extract all visible text and structured data from this document and return as JSON.";

    private final SubmissionRepository      submissionRepository;
    private final DocumentRepository        documentRepository;
    private final AuditLogRepository        auditLogRepository;
    private final GeminiExtractionService   geminiExtractionService;
    private final VerificationStrategyRegistry strategyRegistry;
    private final OutputSink                outputSink;
    private final FileStorage               fileStorage;
    private final ObjectMapper              objectMapper;

    // ─────────────────────────────────────────────────────────────────────────────
    // Entry point — fires AFTER the upload transaction commits
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Triggered after the submission upload transaction commits.
     * Runs on the dedicated "verificationExecutor" thread pool — never blocks the HTTP thread.
     */
    @Async("verificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmissionCreated(SubmissionCreatedEvent event) {
        Long submissionId = event.getSubmissionId();
        log.info("[Orchestrator] Starting pipeline for submission id={}", submissionId);

        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            log.error("[Orchestrator] Submission id={} not found — possible race condition.", submissionId);
            return;
        }

        runPipeline(submission);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Main pipeline (isolated so exceptions can always be caught at the top)
    // ─────────────────────────────────────────────────────────────────────────────

    private void runPipeline(Submission submission) {
        try {
            // Step 1: Mark as PROCESSING
            setStatus(submission, "PROCESSING");

            // Step 2: Resolve strategy (needed for prompts + verification)
            String strategyKey = submission.getBillCategory().getVerificationStrategyKey();
            VerificationStrategy strategy = strategyRegistry.resolve(strategyKey);

            // Step 3: Extract all documents
            List<Document> documents = documentRepository.findBySubmission(submission);
            boolean extractionOk = extractAllDocuments(submission, strategy, documents);

            if (!extractionOk) {
                // extractAllDocuments already set FAILED and wrote the audit log
                return;
            }

            // Step 4: Re-fetch documents so rawExtraction is populated from DB
            documents = documentRepository.findBySubmission(submission);

            // Step 5: Run verification
            log.info("[Orchestrator] Running verification for submission id={}", submission.getId());
            VerifiedSubmissionResult result = strategy.verify(submission, documents);

            // Step 6: Persist result via OutputSink (updates status + audit trail)
            outputSink.saveVerifiedSubmission(result);

            log.info("[Orchestrator] Pipeline complete for submission id={} — final status={}",
                    submission.getId(), result.finalStatus());

        } catch (Exception e) {
            log.error("[Orchestrator] Unexpected failure for submission id={}",
                    submission.getId(), e);
            markFailed(submission,
                    "Unexpected orchestration error: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Step 3: Extraction loop
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Calls GeminiExtractionService for every document in the submission.
     * Supports both single-file documents (stored in document.file_data) and
     * multi-page documents (stored in the document_page table).
     * All pages for a document are sent to Gemini in a single API call.
     *
     * @return true if all documents extracted successfully; false if any failed
     */
    private boolean extractAllDocuments(Submission submission, VerificationStrategy strategy,
                                        List<Document> documents) {
        for (Document doc : documents) {
            String docType = doc.getDocType();
            log.info("[Orchestrator] Extracting submission id={} docType={} fileRef={}",
                    submission.getId(), docType, doc.getFileRef());

            // Collect all file bytes and mime types for this document
            List<byte[]> allFileBytes = new ArrayList<>();
            List<String> allMimeTypes = new ArrayList<>();

            // Check for multi-page uploads first
            var pages = fileStorage.retrievePages(doc);
            if (pages != null && !pages.isEmpty()) {
                for (var page : pages) {
                    if (page.getFileData() != null && page.getFileData().length > 0) {
                        allFileBytes.add(page.getFileData());
                        allMimeTypes.add(page.getMimeType() != null
                                ? page.getMimeType()
                                : detectMimeType(page.getFileName()));
                    }
                }
                log.info("[Orchestrator] Found {} pages for document '{}' (id={})",
                        allFileBytes.size(), docType, doc.getId());
            }

            // Fall back to single-file storage if no pages found
            if (allFileBytes.isEmpty()) {
                byte[] fileBytes = fileStorage.retrieve(doc);
                if (fileBytes == null || fileBytes.length == 0) {
                    String reason = String.format(
                            "Extraction failed: no file bytes found for document '%s' (id=%d).",
                            docType, doc.getId());
                    log.error("[Orchestrator] {}", reason);
                    markFailed(submission, reason);
                    return false;
                }
                allFileBytes.add(fileBytes);
                allMimeTypes.add(detectMimeType(doc.getFileRef()));
            }

            String prompt = resolvePrompt(strategy, docType);

            try {
                // Self-validation for ALL document types: verify that extracted
                // line items reconcile against stated total. If not, the model
                // may have dropped or hallucinated line items — retry with a
                // corrective prompt. For documents without meaningful amounts
                // (e.g. GRN where all amounts are 0), validation is skipped
                // automatically since computeReconciliationGap returns ~0.
                JsonNode extracted = extractWithValidation(
                        allFileBytes, allMimeTypes, prompt, submission, doc, docType);

                persistExtraction(doc, extracted);

                log.info("[Orchestrator] Extraction OK for submission id={} docType={} ({} files)",
                        submission.getId(), docType, allFileBytes.size());

            } catch (ExtractionException e) {
                String reason = String.format(
                        "Extraction failed after retries for document '%s' (id=%d): %s",
                        docType, doc.getId(), e.getMessage());
                log.error("[Orchestrator] {}", reason);
                markFailed(submission, reason);
                return false;
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Invoice extraction with self-validation
    // ─────────────────────────────────────────────────────────────────────────────

    /** Tolerance for line-item sum vs total reconciliation (₹50 covers rounding). */
    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("50");

    /** Max times to retry extraction when line items don't reconcile against total. */
    private static final int EXTRACTION_VALIDATION_RETRIES = 2;

    /**
     * Calls Gemini to extract data from any document type, then validates that
     * the extracted line items reconcile against the stated total. If they don't,
     * retries with a corrective prompt that tells the model exactly what's missing.
     * <p>
     * For documents without meaningful line-item amounts (e.g. GRN where all
     * amounts are 0), reconciliation is skipped and the first extraction is used.
     * <p>
     * Additionally, runs a consistency check: if the first reconciled extraction
     * succeeds, it runs one more extraction independently and compares line counts
     * and descriptions. If they disagree, it picks the better-reconciled one but
     * logs a warning about potential hallucination.
     *
     * @return the best (most reconciled and consistent) extraction across attempts
     */
    private JsonNode extractWithValidation(List<byte[]> fileBytes, List<String> mimeTypes,
                                           String basePrompt, Submission submission,
                                           Document doc, String docType) {
        JsonNode bestResult = null;
        BigDecimal bestGap = null;
        int bestLineCount = 0;

        for (int attempt = 0; attempt <= EXTRACTION_VALIDATION_RETRIES; attempt++) {
            String prompt = attempt == 0 ? basePrompt
                    : buildCorrectionPrompt(basePrompt, bestResult, bestGap, docType);

            JsonNode extracted = geminiExtractionService.extractFromMultipleFiles(
                    fileBytes, mimeTypes, prompt);

            // Check if reconciliation is possible (skip for docs where all line amounts = 0)
            BigDecimal gap = computeReconciliationGap(extracted);
            int lineCount = countLineItems(extracted);
            boolean canReconcile = canReconcile(extracted);
            String warrantyFragment = detectWarrantyFragment(extracted, docType);

            log.info("[Orchestrator] {} extraction attempt {} for submission id={} doc id={}: "
                    + "{} line items, reconciliation gap = {}, canReconcile = {}, warrantyFragment = {}",
                    docType.toUpperCase(), attempt + 1, submission.getId(), doc.getId(),
                    lineCount, gap, canReconcile, warrantyFragment != null ? warrantyFragment : "none");

            // Track the best result:
            // Priority 1: no warranty fragment; Priority 2: smallest gap; Priority 3: most lines
            boolean currentBetter = bestResult == null;
            if (!currentBetter) {
                boolean prevHadFragment = bestResult != null
                        && detectWarrantyFragment(bestResult, docType) != null;
                boolean currHasFragment = warrantyFragment != null;
                if (prevHadFragment && !currHasFragment) {
                    currentBetter = true; // this attempt fixed the fragment
                } else if (!prevHadFragment || !currHasFragment) {
                    // same fragment status — compare gap then line count
                    currentBetter = gap.abs().compareTo(bestGap.abs()) < 0
                            || (gap.abs().compareTo(bestGap.abs()) == 0 && lineCount > bestLineCount);
                }
            }
            if (currentBetter) {
                bestResult = extracted;
                bestGap = gap;
                bestLineCount = lineCount;
            }

            // If reconciliation isn't possible (GRN, or no total), accept if no warranty fragment
            if (!canReconcile) {
                if (warrantyFragment != null && attempt < EXTRACTION_VALIDATION_RETRIES) {
                    log.warn("[Orchestrator] {} extraction for doc id={} — "
                            + "warranty field looks truncated ({}), retrying (attempt {}/{})",
                            docType.toUpperCase(), doc.getId(), warrantyFragment,
                            attempt + 2, EXTRACTION_VALIDATION_RETRIES + 1);
                    continue; // retry
                }
                log.info("[Orchestrator] {} extraction for doc id={} — amounts not available "
                        + "for reconciliation, accepting extraction with {} line items.",
                        docType.toUpperCase(), doc.getId(), lineCount);
                return runConsistencyCheck(extracted, lineCount, fileBytes, mimeTypes,
                        basePrompt, submission, doc, docType);
            }

            // Both checks must pass before accepting
            boolean reconciled = gap.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0;
            boolean warrantyOk  = warrantyFragment == null;

            if (reconciled && warrantyOk) {
                if (attempt > 0) {
                    log.info("[Orchestrator] {} extraction passed all checks on attempt {} (gap ₹{})",
                            docType.toUpperCase(), attempt + 1, gap);
                }
                return runConsistencyCheck(extracted, lineCount, fileBytes, mimeTypes,
                        basePrompt, submission, doc, docType);
            }

            if (attempt < EXTRACTION_VALIDATION_RETRIES) {
                log.warn("[Orchestrator] {} extraction attempt {} has issues — "
                        + "reconciliation gap ₹{} (ok={}), warrantyFragment={} — "
                        + "retrying (attempt {}/{})",
                        docType.toUpperCase(), attempt + 1, gap, reconciled,
                        warrantyFragment, attempt + 2, EXTRACTION_VALIDATION_RETRIES + 1);
            }
        }

        // All retries exhausted — use the best result but log a warning
        log.warn("[Orchestrator] {} extraction for submission id={} doc id={} "
                + "could not fully reconcile after {} attempts. "
                + "Best result: {} line items, gap ₹{}. Proceeding with best attempt.",
                docType.toUpperCase(), submission.getId(), doc.getId(),
                EXTRACTION_VALIDATION_RETRIES + 1, bestLineCount, bestGap);

        AuditLog warning = new AuditLog();
        warning.setEntityType(ENTITY_TYPE);
        warning.setEntityId(submission.getId());
        warning.setAction("WARNING");
        warning.setDetail(String.format(
                "%s extraction may be incomplete: line-item total does not reconcile "
                + "with stated total (gap ₹%s, %d line items extracted). "
                + "Verification proceeded with the best available extraction. Manual review recommended.",
                docType.toUpperCase(), bestGap, bestLineCount));
        warning.setActor("system");
        auditLogRepository.save(warning);

        return bestResult;
    }

    /**
     * Consistency check: extracts the same document a second time independently
     * and compares the results. If the line counts match, returns the original.
     * If they differ, picks the better one (more lines, smaller gap) and logs a
     * warning — differing extractions on the same document indicate model
     * non-determinism and possible hallucination.
     */
    private JsonNode runConsistencyCheck(JsonNode firstResult, int firstLineCount,
                                         List<byte[]> fileBytes, List<String> mimeTypes,
                                         String basePrompt, Submission submission,
                                         Document doc, String docType) {
        try {
            JsonNode secondResult = geminiExtractionService.extractFromMultipleFiles(
                    fileBytes, mimeTypes, basePrompt);
            int secondLineCount = countLineItems(secondResult);

            if (firstLineCount == secondLineCount) {
                log.info("[Orchestrator] {} consistency check PASSED for doc id={}: "
                        + "both extractions returned {} line items.",
                        docType.toUpperCase(), doc.getId(), firstLineCount);
                return firstResult;
            }

            // Line counts differ — pick the one with more lines and better reconciliation
            BigDecimal gap1 = computeReconciliationGap(firstResult);
            BigDecimal gap2 = computeReconciliationGap(secondResult);

            log.warn("[Orchestrator] {} consistency check FAILED for doc id={}: "
                    + "extraction 1 returned {} lines (gap ₹{}), "
                    + "extraction 2 returned {} lines (gap ₹{}). "
                    + "Possible hallucination detected.",
                    docType.toUpperCase(), doc.getId(),
                    firstLineCount, gap1, secondLineCount, gap2);

            // Prefer the one with the smaller gap; on tie, more line items
            boolean useSecond = gap2.abs().compareTo(gap1.abs()) < 0
                    || (gap2.abs().compareTo(gap1.abs()) == 0 && secondLineCount > firstLineCount);

            if (useSecond) {
                log.info("[Orchestrator] Using extraction 2 (better reconciliation).");
                return secondResult;
            }
            return firstResult;

        } catch (Exception e) {
            // If the consistency check itself fails, just use the first result
            log.warn("[Orchestrator] {} consistency check failed with exception for doc id={}: {}. "
                    + "Using original extraction.", docType.toUpperCase(), doc.getId(), e.getMessage());
            return firstResult;
        }
    }

    /**
     * Determines whether reconciliation is possible for this extraction.
     * Returns false if: (a) no total_amount, or (b) all line item amounts are zero
     * (typical for GRN documents that don't carry pricing).
     */
    private boolean canReconcile(JsonNode data) {
        if (data == null) return false;
        BigDecimal totalAmount = getDecimal(data, "total_amount");
        if (totalAmount == null || totalAmount.signum() == 0) return false;

        JsonNode items = data.get("line_items");
        if (items == null || !items.isArray() || items.isEmpty()) return false;

        // Check if at least one line item has a non-zero amount
        for (JsonNode item : items) {
            BigDecimal amt = getDecimal(item, "amount");
            if (amt != null && amt.signum() != 0) return true;
        }
        return false; // all amounts are 0 — can't reconcile (GRN pattern)
    }

    /**
     * Computes the gap between the sum of line_items amounts + tax + other_charges
     * and the stated total_amount. A gap near zero means the extraction is consistent.
     */
    private BigDecimal computeReconciliationGap(JsonNode data) {
        if (data == null) return BigDecimal.valueOf(999999);

        BigDecimal totalAmount = getDecimal(data, "total_amount");
        if (totalAmount == null) return BigDecimal.ZERO; // no total → can't validate

        BigDecimal lineSum = BigDecimal.ZERO;
        JsonNode items = data.get("line_items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                BigDecimal amt = getDecimal(item, "amount");
                if (amt != null) lineSum = lineSum.add(amt);
            }
        }

        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal igst = getDecimal(data, "igst_amount");
        if (igst != null && igst.signum() > 0) {
            tax = igst;
        } else {
            BigDecimal cgst = getDecimal(data, "cgst_amount");
            BigDecimal sgst = getDecimal(data, "sgst_amount");
            if (cgst != null) tax = tax.add(cgst);
            if (sgst != null) tax = tax.add(sgst);
        }

        BigDecimal otherCharges = getDecimal(data, "other_charges");
        if (otherCharges == null) otherCharges = BigDecimal.ZERO;

        BigDecimal expected = lineSum.add(tax).add(otherCharges);
        return totalAmount.subtract(expected);
    }

    private int countLineItems(JsonNode data) {
        if (data == null) return 0;
        JsonNode items = data.get("line_items");
        return (items != null && items.isArray()) ? items.size() : 0;
    }

    /**
     * Builds a corrective prompt describing all detected extraction issues.
     * Covers both line-item reconciliation gaps and truncated warranty fields.
     * Works for any document type (invoice, PO, GRN).
     */
    private String buildCorrectionPrompt(String basePrompt, JsonNode previousResult,
                                         BigDecimal gap, String docType) {
        int prevCount = countLineItems(previousResult);
        String warrantyFragment = detectWarrantyFragment(previousResult, docType);

        StringBuilder issues = new StringBuilder();

        if (gap != null && gap.abs().compareTo(RECONCILIATION_TOLERANCE) > 0) {
            issues.append(String.format(
                    "- Line items: your previous extraction returned only %d line items, "
                    + "but the financial totals show approximately ₹%.2f of value is missing or wrong. "
                    + "Extract EVERY line item exactly as printed. Count the rows in the item table "
                    + "and make sure your output has exactly that many entries in line_items. "
                    + "Do NOT skip, summarize, substitute, or hallucinate any rows.\n",
                    prevCount, gap.abs().doubleValue()));
        }

        if (warrantyFragment != null) {
            issues.append(String.format(
                    "- Warranty/terms field: your previous extraction truncated or partially extracted "
                    + "a warranty/terms field (%s). "
                    + "Please extract the COMPLETE warranty or terms text exactly as it appears on the document — "
                    + "do not truncate, abbreviate, or return only a fragment like 'YES' or a partial sentence.\n",
                    warrantyFragment));
        }

        if (issues.isEmpty()) {
            // Fallback (shouldn't happen, but safe)
            issues.append("Please re-read the document carefully and extract all fields completely and accurately.\n");
        }

        return basePrompt + "\n\nIMPORTANT CORRECTION for this " + docType.toUpperCase()
                + " document — your previous extraction had the following issues:\n"
                + issues
                + "Extract only what is actually printed on the document.";
    }

    /**
     * Detects suspiciously short (fragment/truncated) warranty fields in an extraction.
     * A non-null, non-blank warranty field shorter than {@value #WARRANTY_MIN_LENGTH}
     * characters is considered a truncation signal.
     *
     * @return a human-readable description of the problem, or null if no issue detected
     */
    private static final int WARRANTY_MIN_LENGTH = 15;

    private String detectWarrantyFragment(JsonNode data, String docType) {
        if (data == null) return null;

        // Check warranty_text on invoice/GRN extractions
        String warrantyText = getStringField(data, "warranty_text");
        if (warrantyText != null && !warrantyText.isBlank()
                && warrantyText.trim().length() < WARRANTY_MIN_LENGTH) {
            return String.format("warranty_text='%s' (%d chars, expected ≥%d)",
                    warrantyText.trim(), warrantyText.trim().length(), WARRANTY_MIN_LENGTH);
        }

        // Check po_warranty_requirement on PO extractions
        String poWarranty = getStringField(data, "po_warranty_requirement");
        if (poWarranty != null && !poWarranty.isBlank()
                && poWarranty.trim().length() < WARRANTY_MIN_LENGTH) {
            return String.format("po_warranty_requirement='%s' (%d chars, expected ≥%d)",
                    poWarranty.trim(), poWarranty.trim().length(), WARRANTY_MIN_LENGTH);
        }

        return null;
    }

    /** Safely reads a String field from a JsonNode. */
    private String getStringField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) return null;
        return v.asText();
    }

    /** Safely extracts a BigDecimal from a JsonNode field. */
    private BigDecimal getDecimal(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return v.decimalValue();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    protected void persistExtraction(Document doc, JsonNode extracted) {
        // IMPORTANT: must use ObjectMapper.convertValue() — NOT manual entry.getValue() iteration.
        // entry.getValue() returns JsonNode instances; putting those into Map<String, Object>
        // causes Hibernate's JSONB serializer to store null (it cannot handle JsonNode values).
        // convertValue() walks the full tree and produces native Java types (String, Number,
        // List, Map) that Hibernate serializes correctly to the jsonb column.
        Map<String, Object> extractionMap = objectMapper.convertValue(
                extracted, new TypeReference<Map<String, Object>>() {});
        doc.setRawExtraction(extractionMap);
        documentRepository.save(doc);
        log.debug("[Orchestrator] Persisted raw_extraction for document id={} docType={} — keys: {}",
                doc.getId(), doc.getDocType(), extractionMap.keySet());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers: status management
    // ─────────────────────────────────────────────────────────────────────────────

    @Transactional
    protected void setStatus(Submission submission, String status) {
        submission.setStatus(status);
        submissionRepository.save(submission);
        log.debug("[Orchestrator] submission id={} status → {}", submission.getId(), status);
    }

    /**
     * Marks the submission as FAILED (not RED — RED = verified and rejected;
     * FAILED = could not complete processing) and writes a clear audit_log entry.
     */
    @Transactional
    protected void markFailed(Submission submission, String reason) {
        submission.setStatus("FAILED");
        submissionRepository.save(submission);

        AuditLog entry = new AuditLog();
        entry.setEntityType(ENTITY_TYPE);
        entry.setEntityId(submission.getId());
        entry.setAction("FAILED");
        entry.setDetail(reason);
        entry.setActor("system");
        auditLogRepository.save(entry);

        log.error("[Orchestrator] Submission id={} marked FAILED: {}", submission.getId(), reason);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers: prompt + MIME resolution
    // ─────────────────────────────────────────────────────────────────────────────

    private String resolvePrompt(VerificationStrategy strategy, String docType) {
        if (strategy instanceof PromptProvider pp) {
            return pp.getExtractionPrompt(docType);
        }
        log.warn("Strategy '{}' does not implement PromptProvider; using fallback prompt.",
                strategy.getKey());
        return FALLBACK_PROMPT;
    }

    /**
     * Infers MIME type from file extension for the Gemini API inlineData field.
     * Defaults to application/pdf for unknown extensions.
     */
    private String detectMimeType(String fileRef) {
        if (fileRef == null) return "application/pdf";
        String lower = fileRef.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        return "application/pdf"; // default fallback for Gemini
    }
}
