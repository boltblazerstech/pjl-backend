package com.pjl.bills.controller;

import com.pjl.bills.dto.SubmissionDetailDto;
import com.pjl.bills.dto.SubmissionResponse;
import com.pjl.bills.dto.SubmissionSummaryDto;
import com.pjl.bills.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    /**
     * Creates a new submission. Files are uploaded to R2 unconditionally.
     * Returns 202 Accepted immediately; async orchestration handles extraction/verification.
     */
    @PostMapping
    public ResponseEntity<SubmissionResponse> createSubmission(
            @RequestParam("billCategoryId") Long billCategoryId,
            MultipartHttpServletRequest request) {
        try {
            SubmissionResponse response = submissionService.createSubmission(billCategoryId, request);
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Replaces one or more files on an existing submission.
     * At least one file (invoice, po, grn) must be provided in the multipart body.
     * The old R2 objects are orphaned (not deleted).
     */
    @PatchMapping("/{id}/files")
    public ResponseEntity<SubmissionDetailDto> replaceFiles(
            @PathVariable Long id,
            MultipartHttpServletRequest request) {
        try {
            return ResponseEntity.ok(submissionService.replaceFiles(id, request));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Deletes a specific document's file from R2 and clears fileRef/originalFilename.
     * The Document row is preserved (with its docType) so a replacement can be uploaded later via PATCH.
     * Returns 204 No Content.
     */
    @DeleteMapping("/{id}/files/{docType}")
    public ResponseEntity<Void> deleteDocumentFile(
            @PathVariable Long id,
            @PathVariable String docType) {
        try {
            submissionService.deleteDocumentFile(id, docType);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Re-runs the full extraction → verification pipeline for a submission,
     * using its current file refs. Clears previous matched line items, exceptions,
     * and audit log before starting. Returns 202 Accepted immediately.
     */
    @PostMapping("/{id}/rerun")
    public ResponseEntity<Map<String, Object>> rerunSubmission(@PathVariable Long id) {
        try {
            SubmissionResponse resp = submissionService.rerunSubmission(id);
            return ResponseEntity.accepted().body(Map.of(
                    "submissionId", resp.id(),
                    "message", "Rerun started for submission " + id
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Retrieves a paginated list of submissions, optionally filtered by status.
     */
    @GetMapping
    public ResponseEntity<Page<SubmissionSummaryDto>> getSubmissions(
            @RequestParam(required = false) String status,
            @PageableDefault(sort = "uploadedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(submissionService.getSubmissions(status, pageable));
    }

    /**
     * Retrieves the full detail view for a single submission.
     */
    @GetMapping("/{id}")
    public ResponseEntity<SubmissionDetailDto> getSubmissionDetail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(submissionService.getSubmissionDetail(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Manually overrides a submission's status. Requires a clear reason.
     */
    @PatchMapping("/{id}/override")
    public ResponseEntity<SubmissionDetailDto> overrideSubmission(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.pjl.bills.dto.OverrideRequest request) {
        try {
            return ResponseEntity.ok(submissionService.overrideSubmission(id, request));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Completely deletes a submission and all its associated data, including files from R2.
     * Returns 204 No Content on success.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubmission(@PathVariable Long id) {
        try {
            submissionService.deleteSubmission(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
