package com.pjl.bills.service;

import com.pjl.bills.dto.AuditLogDto;
import com.pjl.bills.dto.DocumentDto;
import com.pjl.bills.dto.MatchedLineItemDto;
import com.pjl.bills.dto.SubmissionDetailDto;
import com.pjl.bills.dto.SubmissionResponse;
import com.pjl.bills.dto.SubmissionSummaryDto;
import com.pjl.bills.entity.BillCategory;
import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentPage;
import com.pjl.bills.entity.MatchedLineItemEntity;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.event.SubmissionCreatedEvent;
import com.pjl.bills.repository.BillCategoryRepository;
import com.pjl.bills.repository.DocumentPageRepository;
import com.pjl.bills.repository.DocumentRepository;
import com.pjl.bills.repository.MatchedLineItemRepository;
import com.pjl.bills.repository.SubmissionRepository;
import com.pjl.core.entity.AuditLog;
import com.pjl.core.repository.AuditLogRepository;
import com.pjl.core.sink.OutputSink;
import com.pjl.core.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final BillCategoryRepository billCategoryRepository;
    private final SubmissionRepository submissionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentPageRepository documentPageRepository;
    private final AuditLogRepository auditLogRepository;
    private final MatchedLineItemRepository matchedLineItemRepository;
    private final FileStorage fileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final com.pjl.bills.VerificationStrategyRegistry strategyRegistry;
    private final OutputSink outputSink;

    /**
     * Creates a new submission for the given bill category using the uploaded files.
     * Files are unconditionally uploaded to R2 via FileStorage.
     * Returns 202 Accepted immediately; extraction and verification proceed asynchronously.
     */
    @Transactional
    public SubmissionResponse createSubmission(Long billCategoryId,
                                               MultipartHttpServletRequest request) {
        BillCategory category = billCategoryRepository.findById(billCategoryId)
                .orElseThrow(() -> new IllegalArgumentException("BillCategory not found for ID: " + billCategoryId));

        List<String> requiredDocs = category.getRequiredDocumentTypes();
        if (requiredDocs == null) {
            requiredDocs = new ArrayList<>();
        }

        List<String> missing = new ArrayList<>();
        Map<String, MultipartFile> uploadedFiles = new HashMap<>();

        for (String reqType : requiredDocs) {
            List<MultipartFile> files = request.getFiles(reqType);
            files = files.stream().filter(f -> !f.isEmpty()).toList();
            if (files.isEmpty()) {
                missing.add(reqType);
            } else {
                uploadedFiles.put(reqType, files.getFirst());
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Missing required document types for category '%s': %s",
                    category.getName(), String.join(", ", missing)));
        }

        Submission submission = new Submission();
        submission.setBillCategory(category);
        submission.setStatus("PENDING");
        submission.setUploadedAt(Instant.now());
        submission = submissionRepository.save(submission);

        // Upload each file to R2 and create a Document row
        String prefix = "submissions/" + submission.getId() + "/";
        for (Map.Entry<String, MultipartFile> entry : uploadedFiles.entrySet()) {
            String docType = entry.getKey();
            MultipartFile f = entry.getValue();
            try {
                String key = fileStorage.uploadRawFile(prefix, f.getOriginalFilename(),
                        f.getContentType(), f.getBytes());
                Document doc = new Document();
                doc.setSubmission(submission);
                doc.setDocType(docType);
                doc.setFileRef(key);
                doc.setOriginalFilename(f.getOriginalFilename());
                documentRepository.save(doc);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read uploaded file for type: " + docType, e);
            }
        }

        log.info("Created submission ID {} (Category: {}) with status PENDING — {} documents uploaded to R2",
                submission.getId(), category.getName(), uploadedFiles.size());

        eventPublisher.publishEvent(new SubmissionCreatedEvent(this, submission.getId()));

        return new SubmissionResponse(submission.getId(), submission.getStatus());
    }

    /**
     * Creates an invoice-only submission using imported Ramco PO/GRN data.
     */
    @Transactional
    public SubmissionResponse createInvoiceOnlySubmission(Long billCategoryId, MultipartFile invoiceFile) {
        BillCategory category = billCategoryRepository.findById(billCategoryId)
                .orElseThrow(() -> new IllegalArgumentException("BillCategory not found for ID: " + billCategoryId));

        if (invoiceFile == null || invoiceFile.isEmpty()) {
            throw new IllegalArgumentException("Invoice file is required for invoice-only submission.");
        }

        Submission submission = new Submission();
        submission.setBillCategory(category);
        submission.setStatus("PENDING");
        submission.setUploadedAt(Instant.now());
        submission.setPoGrnSource("ramco_import");
        submission = submissionRepository.save(submission);

        String prefix = "submissions/" + submission.getId() + "/";
        try {
            String key = fileStorage.uploadRawFile(prefix, invoiceFile.getOriginalFilename(),
                    invoiceFile.getContentType(), invoiceFile.getBytes());
            Document doc = new Document();
            doc.setSubmission(submission);
            doc.setDocType("invoice");
            doc.setFileRef(key);
            doc.setOriginalFilename(invoiceFile.getOriginalFilename());
            documentRepository.save(doc);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload invoice file", e);
        }

        log.info("Publishing SubmissionCreatedEvent for invoice-only submission {}", submission.getId());
        eventPublisher.publishEvent(new SubmissionCreatedEvent(this, submission.getId()));

        return new SubmissionResponse(submission.getId(), submission.getStatus());
    }

    /**
     * Replaces one or more files on an existing submission. At least one file must be provided.
     * Each provided file is uploaded to R2 and the corresponding Document row's fileRef is updated.
     * The old R2 object is intentionally left in place (orphaned) — cleanup is out of scope for now.
     */
    @Transactional
    public SubmissionDetailDto replaceFiles(Long submissionId, MultipartHttpServletRequest request) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));

        List<Document> docs = documentRepository.findBySubmission(submission);
        Map<String, Document> docByType = new HashMap<>();
        for (Document d : docs) {
            docByType.put(d.getDocType(), d);
        }

        String prefix = "submissions/" + submissionId + "/";
        boolean anyReplaced = false;
        for (String docType : List.of("invoice", "po", "grn")) {
            MultipartFile f = request.getFile(docType);
            if (f == null || f.isEmpty()) continue;
            try {
                String key = fileStorage.uploadRawFile(prefix, f.getOriginalFilename(),
                        f.getContentType(), f.getBytes());
                Document doc = docByType.get(docType);
                if (doc != null) {
                    doc.setFileRef(key);
                    doc.setOriginalFilename(f.getOriginalFilename());
                    documentRepository.save(doc);
                    log.info("Replaced {} file for submission {} → {}", docType, submissionId, key);
                    anyReplaced = true;
                } else {
                    log.warn("No existing document of type '{}' found for submission {} — skipping replacement",
                            docType, submissionId);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read uploaded file for type: " + docType, e);
            }
        }

        if (!anyReplaced) {
            throw new IllegalArgumentException("At least one file (invoice, po, or grn) must be provided");
        }

        return getSubmissionDetail(submissionId);
    }

    /**
     * Re-runs the full extraction → verification pipeline for an existing submission,
     * using its CURRENT file refs (which may have just been updated via replaceFiles).
     * <p>
     * Before starting: clears matched line items, clears audit log entries, resets status to PENDING.
     * Returns immediately — processing is asynchronous.
     */
    @Transactional
    public SubmissionResponse rerunSubmission(Long submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));

        // Clear matched line items
        List<MatchedLineItemEntity> items = matchedLineItemRepository
                .findBySubmissionIdOrderByIdAsc(submissionId);
        matchedLineItemRepository.deleteAll(items);

        // Clear all audit log entries for this submission
        auditLogRepository.deleteByEntityTypeAndEntityId("Submission", submissionId);

        // Reset status and warranty status
        submission.setStatus("PENDING");
        submission.setWarrantyStatus(null);
        submissionRepository.save(submission);

        // For invoice-only, clear the synthesized PO/GRN documents so they are regenerated
        if ("ramco_import".equals(submission.getPoGrnSource())) {
            List<Document> docs = documentRepository.findBySubmission(submission);
            for (Document doc : docs) {
                if ("po".equals(doc.getDocType()) || "grn".equals(doc.getDocType())) {
                    documentRepository.delete(doc);
                }
            }
        }

        log.info("Rerun initiated for submission {} — cleared {} line items and all audit logs",
                submissionId, items.size());

        // Publish event — VerificationOrchestrator picks it up asynchronously after TX commits
        eventPublisher.publishEvent(new SubmissionCreatedEvent(this, submissionId));

        return new SubmissionResponse(submission.getId(), "PENDING");
    }

    /**
     * Retrieves a paginated list of submissions, optionally filtered by status.
     * Includes supplierName and invoiceNo pulled from the invoice document's raw extraction.
     */
    @Transactional(readOnly = true)
    public Page<SubmissionSummaryDto> getSubmissions(String status, Pageable pageable) {
        Page<Submission> page;
        if (status != null && !status.isBlank()) {
            page = submissionRepository.findByStatus(status, pageable);
        } else {
            page = submissionRepository.findAll(pageable);
        }

        return page.map(s -> {
            String supplierName = null;
            String invoiceNo = null;
            // Extract from invoice document's raw_extraction if available
            List<Document> docs = documentRepository.findBySubmission(s);
            for (Document d : docs) {
                if ("invoice".equals(d.getDocType()) && d.getRawExtraction() != null) {
                    Object sn = d.getRawExtraction().get("supplier_name");
                    Object inv = d.getRawExtraction().get("invoice_no");
                    if (sn != null) supplierName = sn.toString();
                    if (inv != null) invoiceNo = inv.toString();
                    break;
                }
            }
            return new SubmissionSummaryDto(
                    s.getId(),
                    s.getBillCategory().getName(),
                    s.getStatus(),
                    s.getUploadedAt(),
                    supplierName,
                    invoiceNo
            );
        });
    }

    /**
     * Retrieves the full detail view for a single submission, including its
     * parsed documents and chronologically sorted audit logs.
     */
    @Transactional(readOnly = true)
    public SubmissionDetailDto getSubmissionDetail(Long id) {
        Submission s = submissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + id));

        List<DocumentDto> docs = documentRepository.findBySubmission(s).stream()
                .map(d -> new DocumentDto(d.getId(), d.getDocType(), d.getFileRef(),
                        d.getOriginalFilename(), d.getRawExtraction()))
                .toList();

        List<AuditLogDto> logs = auditLogRepository.findByEntityTypeAndEntityId("Submission", id).stream()
                .sorted(Comparator.comparing(AuditLog::getCreatedAt))
                .map(a -> new AuditLogDto(a.getId(), a.getAction(), a.getDetail(), a.getActor(), a.getCreatedAt()))
                .toList();

        List<AuditLogDto> exceptions = logs.stream()
                .filter(a -> "EXCEPTION".equals(a.action()))
                .toList();

        List<MatchedLineItemDto> matchedLineItems = matchedLineItemRepository
                .findBySubmissionIdOrderByIdAsc(id).stream()
                .map(e -> new MatchedLineItemDto(
                        e.getId(),
                        e.getItemDescription(),
                        e.getInvoiceQuantity(),
                        e.getInvoiceRate(),
                        e.getInvoiceAmount(),
                        e.getPoQuantity(),
                        e.getPoRate(),
                        e.getGrnAcceptedQuantity(),
                        e.getMatchConfidence(),
                        e.getExceptionReason()
                ))
                .toList();

        return new SubmissionDetailDto(
                s.getId(),
                s.getBillCategory().getName(),
                s.getStatus(),
                s.getUploadedAt(),
                s.getReviewerNotes(),
                s.getWarrantyStatus(),
                docs,
                logs,
                exceptions,
                matchedLineItems,
                s.getPoGrnSource(),
                s.getPoGrnImportBatchId()
        );
    }

    /**
     * Manually overrides the status of a submission and records the action in the audit log.
     */
    @Transactional
    public SubmissionDetailDto overrideSubmission(Long id, com.pjl.bills.dto.OverrideRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("Reason is required for status overrides");
        }

        Submission s = submissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + id));

        String oldStatus = s.getStatus();
        String newStatus = request.newStatus();

        s.setStatus(newStatus);
        submissionRepository.save(s);

        AuditLog entry = new AuditLog();
        entry.setEntityType("Submission");
        entry.setEntityId(s.getId());
        entry.setAction("OVERRIDDEN");
        entry.setDetail(String.format("Changed from %s to %s. Reason: %s", oldStatus, newStatus, request.reason()));
        entry.setActor("system");
        auditLogRepository.save(entry);

        log.info("Submission ID {} manually overridden from {} to {}", id, oldStatus, newStatus);

        return getSubmissionDetail(id);
    }

    /**
     * Deletes a document's file from R2 and clears its fileRef/originalFilename.
     * The Document row is kept with its docType intact so a new file can be uploaded later via PATCH.
     */
    @Transactional
    public void deleteDocumentFile(Long submissionId, String docType) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));

        List<Document> docs = documentRepository.findBySubmission(submission);
        Document target = docs.stream()
                .filter(d -> docType.equalsIgnoreCase(d.getDocType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No document of type '" + docType + "' found for submission " + submissionId));

        String existingKey = target.getFileRef();
        if (existingKey != null && existingKey.contains("/")) {
            fileStorage.deleteFile(existingKey);
            log.info("Deleted R2 file for submission {} docType={} key={}", submissionId, docType, existingKey);
        }

        target.setFileRef(null);
        target.setOriginalFilename(null);
        documentRepository.save(target);
    }

    /**
     * Completely deletes a submission and all its associated data.
     * Deletes files from R2, then removes DocumentPages, Documents, MatchedLineItems,
     * AuditLogs, and finally the Submission itself.
     */
    @Transactional
    public void deleteSubmission(Long id) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + id));

        // 1. Delete R2 files and DocumentPages
        List<Document> docs = documentRepository.findBySubmission(submission);
        for (Document doc : docs) {
            // Delete main file if exists
            String key = doc.getFileRef();
            if (key != null && key.contains("/")) {
                fileStorage.deleteFile(key);
            }
            
            // Delete page files if any
            List<DocumentPage> pages = documentPageRepository.findByDocumentOrderByPageOrderAsc(doc);
            for (DocumentPage page : pages) {
                String pageKey = page.getFileName();
                if (pageKey != null && pageKey.contains("/")) {
                    fileStorage.deleteFile(pageKey);
                }
            }
            // Delete DocumentPage rows for this doc
            documentPageRepository.deleteAll(pages);
        }

        // 2. Delete all MatchedLineItem rows
        List<MatchedLineItemEntity> items = matchedLineItemRepository.findBySubmissionIdOrderByIdAsc(id);
        matchedLineItemRepository.deleteAll(items);

        // 3. Delete all audit_log entries
        auditLogRepository.deleteByEntityTypeAndEntityId("Submission", id);

        // 4. Delete all Document rows
        documentRepository.deleteAll(docs);

        // 5. Delete Submission
        submissionRepository.delete(submission);

        log.info("Deleted submission ID {} and all associated data", id);
    }
}
