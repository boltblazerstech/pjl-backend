package com.pjl.bills.service;

import com.pjl.bills.dto.AuditLogDto;
import com.pjl.bills.dto.DocumentDto;
import com.pjl.bills.dto.MatchedLineItemDto;
import com.pjl.bills.dto.SubmissionDetailDto;
import com.pjl.bills.dto.SubmissionResponse;
import com.pjl.bills.dto.SubmissionSummaryDto;
import com.pjl.bills.entity.BillCategory;
import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentGroup;
import com.pjl.bills.entity.MatchedLineItemEntity;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.event.SubmissionCreatedEvent;
import com.pjl.bills.repository.BillCategoryRepository;
import com.pjl.bills.repository.DocumentGroupRepository;
import com.pjl.bills.repository.DocumentRepository;
import com.pjl.bills.repository.MatchedLineItemRepository;
import com.pjl.bills.repository.SubmissionRepository;
import com.pjl.core.entity.AuditLog;
import com.pjl.core.repository.AuditLogRepository;
import com.pjl.core.sink.OutputSink;
import com.pjl.core.storage.FileStorage;
import com.pjl.core.storage.PostgresFileStorage;
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
    private final AuditLogRepository auditLogRepository;
    private final MatchedLineItemRepository matchedLineItemRepository;
    private final FileStorage fileStorage;             // Primary — R2
    private final PostgresFileStorage postgresFileStorage; // Explicit Postgres path for saveAsGroup=false
    private final DocumentGroupRepository groupRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.pjl.bills.VerificationStrategyRegistry strategyRegistry;
    private final OutputSink outputSink;

    /**
     * Creates a new submission for the given bill category using the uploaded files.
     * <p>
     * When {@code saveAsGroup} is {@code true} (the default), the files are first uploaded
     * to R2 as a new {@link com.pjl.bills.entity.DocumentGroup} and the submission is linked
     * to that group. When {@code false}, files are stored only in Postgres bytea for the
     * duration of extraction and no group or R2 object is created.
     */
    @Transactional
    public SubmissionResponse createSubmission(Long billCategoryId,
                                               boolean saveAsGroup,
                                               MultipartHttpServletRequest request) {
        BillCategory category = billCategoryRepository.findById(billCategoryId)
                .orElseThrow(() -> new IllegalArgumentException("BillCategory not found for ID: " + billCategoryId));

        List<String> requiredDocs = category.getRequiredDocumentTypes();
        if (requiredDocs == null) {
            requiredDocs = new ArrayList<>();
        }

        List<String> missing = new ArrayList<>();
        Map<String, List<MultipartFile>> uploadedFiles = new HashMap<>();

        // Validate that every required document type is provided as a part in the request
        // getFiles() supports multiple files under the same form key (multi-page documents)
        for (String reqType : requiredDocs) {
            List<MultipartFile> files = request.getFiles(reqType);
            // Filter out empty entries
            files = files.stream().filter(f -> !f.isEmpty()).toList();
            if (files.isEmpty()) {
                missing.add(reqType);
            } else {
                uploadedFiles.put(reqType, files);
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

        if (saveAsGroup) {
            // ── Path A: create a DocumentGroup and upload files to R2 ──
            DocumentGroup group = createGroupFromFiles(category, null, uploadedFiles);
            submission.setDocumentGroup(group);
            submission = submissionRepository.save(submission);

            // Create Document rows pointing at the R2 keys from the group
            for (Map.Entry<String, List<MultipartFile>> entry : uploadedFiles.entrySet()) {
                String docType = entry.getKey();
                String r2Key = switch (docType) {
                    case "invoice" -> group.getInvoiceFileRef();
                    case "po"      -> group.getPoFileRef();
                    case "grn"     -> group.getGrnFileRef();
                    default        -> null;
                };
                Document doc = new Document();
                doc.setSubmission(submission);
                doc.setDocType(docType);
                doc.setFileRef(r2Key != null ? r2Key : docType);
                documentRepository.save(doc);
            }
        } else {
            // ── Path B: no R2, no group — store bytes in Postgres bytea only ──
            submission = submissionRepository.save(submission);

            for (Map.Entry<String, List<MultipartFile>> entry : uploadedFiles.entrySet()) {
                String docType = entry.getKey();
                List<MultipartFile> files = entry.getValue();

                Document doc = new Document();
                doc.setSubmission(submission);
                doc.setDocType(docType);
                doc.setFileRef("not-saved"); // clearly marks that no permanent file was stored

                doc = documentRepository.save(doc);

                try {
                    if (files.size() == 1) {
                        // Write directly to Postgres file_data — no R2
                        postgresFileStorage.store(doc, files.getFirst().getBytes());
                    } else {
                        List<FileStorage.PageUpload> pages = new ArrayList<>();
                        for (MultipartFile file : files) {
                            pages.add(new FileStorage.PageUpload(
                                    file.getOriginalFilename(),
                                    file.getContentType(),
                                    file.getBytes()
                            ));
                        }
                        postgresFileStorage.storePages(doc, pages);
                        log.info("Stored {} pages (Postgres, no-R2) for document type '{}' (doc id={})",
                                pages.size(), docType, doc.getId());
                    }
                } catch (IOException e) {
                    log.error("Failed to read bytes for document type: {}", docType, e);
                    throw new IllegalArgumentException("Failed to read uploaded file for type: " + docType, e);
                }
            }
        }

        log.info("Created new submission ID {} (Category: {}, saveAsGroup={}) with status PENDING",
                submission.getId(), category.getName(), saveAsGroup);

        // Publish post-commit: async orchestrator picks this up after the TX commits,
        // so it always finds the submission + documents already in the DB.
        eventPublisher.publishEvent(new SubmissionCreatedEvent(this, submission.getId()));

        return new SubmissionResponse(submission.getId(), submission.getStatus());
    }

    /**
     * Creates a DocumentGroup by uploading each file in {@code uploadedFiles} to R2.
     * Only the first file per docType is used (groups are single-file-per-type).
     */
    private DocumentGroup createGroupFromFiles(BillCategory category, String name,
                                               Map<String, List<MultipartFile>> uploadedFiles) {
        DocumentGroup group = new DocumentGroup();
        group.setBillCategory(category);
        group.setName(name != null && !name.isBlank() ? name : "Pending");
        group = groupRepository.save(group);

        if (name == null || name.isBlank()) {
            group.setName("Group-" + group.getId());
        }

        String prefix = "groups/" + group.getId() + "/";
        try {
            for (Map.Entry<String, List<MultipartFile>> entry : uploadedFiles.entrySet()) {
                MultipartFile f = entry.getValue().getFirst();
                String key = fileStorage.uploadRawFile(prefix, f.getOriginalFilename(), f.getContentType(), f.getBytes());
                switch (entry.getKey()) {
                    case "invoice" -> group.setInvoiceFileRef(key);
                    case "po"      -> group.setPoFileRef(key);
                    case "grn"     -> group.setGrnFileRef(key);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded files for group creation", e);
        }

        return groupRepository.save(group);
    }

    /**
     * Retrieves a paginated list of submissions, optionally filtered by status.
     * Returns a lightweight summary view.
     */
    @Transactional(readOnly = true)
    public Page<SubmissionSummaryDto> getSubmissions(String status, Pageable pageable) {
        Page<Submission> page;
        if (status != null && !status.isBlank()) {
            page = submissionRepository.findByStatus(status, pageable);
        } else {
            page = submissionRepository.findAll(pageable);
        }
        
        return page.map(s -> new SubmissionSummaryDto(
                s.getId(),
                s.getBillCategory().getName(),
                s.getStatus(),
                s.getUploadedAt()
        ));
    }

    /**
     * Retrieves the full detail view for a single submission, including its
     * parsed documents (but excluding raw file bytes) and chronologically sorted audit logs.
     */
    @Transactional(readOnly = true)
    public SubmissionDetailDto getSubmissionDetail(Long id) {
        Submission s = submissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + id));

        List<DocumentDto> docs = documentRepository.findBySubmission(s).stream()
                .map(d -> new DocumentDto(d.getId(), d.getDocType(), d.getFileRef(), d.getRawExtraction()))
                .toList();

        List<AuditLogDto> logs = auditLogRepository.findByEntityTypeAndEntityId("Submission", id).stream()
                .sorted(Comparator.comparing(AuditLog::getCreatedAt))
                .map(a -> new AuditLogDto(a.getId(), a.getAction(), a.getDetail(), a.getActor(), a.getCreatedAt()))
                .toList();

        // Filter exceptions (audit entries with action = "EXCEPTION") into a dedicated list
        List<AuditLogDto> exceptions = logs.stream()
                .filter(a -> "EXCEPTION".equals(a.action()))
                .toList();

        // Matched line items (empty for FAILED submissions that never reached the matching stage)
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
                matchedLineItems
        );
    }

    /**
     * Manually overrides the status of a submission and records the action in the audit log.
     * Requires a non-blank reason.
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
        entry.setActor("system"); // This would be the authenticated user ID in a real system
        auditLogRepository.save(entry);

        log.info("Submission ID {} manually overridden from {} to {}", id, oldStatus, newStatus);
        
        return getSubmissionDetail(id);
    }

    @Transactional
    public void reVerifySubmission(Long id) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found"));

        List<Document> documents = documentRepository.findBySubmission(submission);
        com.pjl.bills.VerificationStrategy strategy =
            strategyRegistry.resolve(submission.getBillCategory().getVerificationStrategyKey());

        com.pjl.core.sink.dto.VerifiedSubmissionResult result = strategy.verify(submission, documents);

        // Clear stale matched line items before re-persisting
        matchedLineItemRepository.deleteAll(
                matchedLineItemRepository.findBySubmissionIdOrderByIdAsc(submission.getId()));

        // Re-use the OutputSink to persist the result consistently
        outputSink.saveVerifiedSubmission(result);
    }
}
