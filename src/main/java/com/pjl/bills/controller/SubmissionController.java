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

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    /**
     * Endpoint for submitting a new set of documents for bill verification.
     * Expected as multipart/form-data with 'billCategoryId' and file parts matching 
     * the category's required document types (e.g. 'invoice', 'po', 'grn').
     * <p>
     * Returns 202 Accepted immediately if validation passes.
     */
    @PostMapping
    public ResponseEntity<SubmissionResponse> createSubmission(
            @RequestParam("billCategoryId") Long billCategoryId,
            @RequestParam(value = "saveAsGroup", defaultValue = "true") boolean saveAsGroup,
            MultipartHttpServletRequest request) {
        
        try {
            SubmissionResponse response = submissionService.createSubmission(billCategoryId, saveAsGroup, request);
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
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
     * Retrieves the full detail view for a single submission, including its
     * parsed documents and chronologically sorted audit logs.
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

    @PostMapping("/{id}/reverify")
    public ResponseEntity<Void> reVerifySubmission(@PathVariable Long id) {
        try {
            submissionService.reVerifySubmission(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
