package com.pjl.bills.service;

import com.pjl.bills.dto.GroupDetailDto;
import com.pjl.bills.dto.GroupSummaryDto;
import com.pjl.bills.entity.BillCategory;
import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.DocumentGroup;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.event.SubmissionCreatedEvent;
import com.pjl.bills.repository.BillCategoryRepository;
import com.pjl.bills.repository.DocumentGroupRepository;
import com.pjl.bills.repository.DocumentRepository;
import com.pjl.bills.repository.SubmissionRepository;
import com.pjl.core.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final DocumentGroupRepository groupRepository;
    private final BillCategoryRepository billCategoryRepository;
    private final SubmissionRepository submissionRepository;
    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public DocumentGroup createGroup(Long billCategoryId, String name, MultipartHttpServletRequest request) {
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
                uploadedFiles.put(reqType, files.getFirst()); // Only store first file per type for groups
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Missing required document types for category '%s': %s",
                    category.getName(), String.join(", ", missing)));
        }

        DocumentGroup group = new DocumentGroup();
        group.setBillCategory(category);
        // Save to get ID
        // Wait, name might be null/blank. I'll set it to placeholder if blank, then update with ID later
        group.setName(name != null && !name.isBlank() ? name : "Pending");
        group = groupRepository.save(group);
        
        if (name == null || name.isBlank()) {
            group.setName("Group-" + group.getId());
        }

        // Upload files
        String prefix = "groups/" + group.getId() + "/";
        try {
            if (uploadedFiles.containsKey("invoice")) {
                MultipartFile f = uploadedFiles.get("invoice");
                String key = fileStorage.uploadRawFile(prefix, f.getOriginalFilename(), f.getContentType(), f.getBytes());
                group.setInvoiceFileRef(key);
            }
            if (uploadedFiles.containsKey("po")) {
                MultipartFile f = uploadedFiles.get("po");
                String key = fileStorage.uploadRawFile(prefix, f.getOriginalFilename(), f.getContentType(), f.getBytes());
                group.setPoFileRef(key);
            }
            if (uploadedFiles.containsKey("grn")) {
                MultipartFile f = uploadedFiles.get("grn");
                String key = fileStorage.uploadRawFile(prefix, f.getOriginalFilename(), f.getContentType(), f.getBytes());
                group.setGrnFileRef(key);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded files", e);
        }

        return groupRepository.save(group);
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryDto> listGroups() {
        return groupRepository.findAll().stream().map(group -> {
            int count = submissionRepository.countByDocumentGroup_Id(group.getId());
            return GroupSummaryDto.builder()
                    .id(group.getId())
                    .name(group.getName())
                    .billCategoryId(group.getBillCategory().getId())
                    .billCategoryName(group.getBillCategory().getName())
                    .createdAt(group.getCreatedAt())
                    .runCount(count)
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GroupDetailDto getGroupDetail(Long id) {
        DocumentGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        List<Submission> runs = submissionRepository.findByDocumentGroup_IdOrderByUploadedAtDesc(id);
        List<GroupDetailDto.GroupRunDto> pastRuns = runs.stream()
                .map(s -> GroupDetailDto.GroupRunDto.builder()
                        .submissionId(s.getId())
                        .status(s.getStatus())
                        .submittedAt(s.getUploadedAt())
                        .build())
                .collect(Collectors.toList());

        return GroupDetailDto.builder()
                .id(group.getId())
                .name(group.getName())
                .billCategoryId(group.getBillCategory().getId())
                .billCategoryName(group.getBillCategory().getName())
                .invoiceFileRef(group.getInvoiceFileRef())
                .poFileRef(group.getPoFileRef())
                .grnFileRef(group.getGrnFileRef())
                .createdAt(group.getCreatedAt())
                .pastRuns(pastRuns)
                .build();
    }

    @Transactional
    public DocumentGroup patchGroup(Long id, String name, MultipartHttpServletRequest request) {
        DocumentGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (name != null && !name.isBlank()) {
            group.setName(name);
        }

        String prefix = "groups/" + group.getId() + "/";
        try {
            MultipartFile invoiceFile = request.getFile("invoice");
            if (invoiceFile != null && !invoiceFile.isEmpty()) {
                group.setInvoiceFileRef(fileStorage.uploadRawFile(prefix, invoiceFile.getOriginalFilename(), invoiceFile.getContentType(), invoiceFile.getBytes()));
            }
            MultipartFile poFile = request.getFile("po");
            if (poFile != null && !poFile.isEmpty()) {
                group.setPoFileRef(fileStorage.uploadRawFile(prefix, poFile.getOriginalFilename(), poFile.getContentType(), poFile.getBytes()));
            }
            MultipartFile grnFile = request.getFile("grn");
            if (grnFile != null && !grnFile.isEmpty()) {
                group.setGrnFileRef(fileStorage.uploadRawFile(prefix, grnFile.getOriginalFilename(), grnFile.getContentType(), grnFile.getBytes()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded files", e);
        }

        return groupRepository.save(group);
    }

    @Transactional
    public Long runGroup(Long id) {
        DocumentGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        Submission submission = new Submission();
        submission.setBillCategory(group.getBillCategory());
        submission.setStatus("PENDING");
        submission.setUploadedAt(Instant.now());
        submission.setDocumentGroup(group);
        submission = submissionRepository.save(submission);

        if (group.getInvoiceFileRef() != null) {
            Document doc = new Document();
            doc.setSubmission(submission);
            doc.setDocType("invoice");
            doc.setFileRef(group.getInvoiceFileRef());
            documentRepository.save(doc);
        }
        if (group.getPoFileRef() != null) {
            Document doc = new Document();
            doc.setSubmission(submission);
            doc.setDocType("po");
            doc.setFileRef(group.getPoFileRef());
            documentRepository.save(doc);
        }
        if (group.getGrnFileRef() != null) {
            Document doc = new Document();
            doc.setSubmission(submission);
            doc.setDocType("grn");
            doc.setFileRef(group.getGrnFileRef());
            documentRepository.save(doc);
        }

        eventPublisher.publishEvent(new SubmissionCreatedEvent(this, submission.getId()));
        return submission.getId();
    }

    @Transactional
    public void deleteGroup(Long id) {
        DocumentGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        // Unlink any historical submissions from this group so they aren't deleted
        submissionRepository.unlinkSubmissionsFromGroup(id);

        groupRepository.delete(group);
        log.info("Deleted DocumentGroup ID {}", id);
    }
}
