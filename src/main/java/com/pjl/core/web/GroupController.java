package com.pjl.core.web;

import com.pjl.bills.dto.GroupDetailDto;
import com.pjl.bills.dto.GroupSummaryDto;
import com.pjl.bills.entity.DocumentGroup;
import com.pjl.bills.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public ResponseEntity<DocumentGroup> createGroup(
            @RequestParam("billCategoryId") Long billCategoryId,
            @RequestParam(value = "name", required = false) String name,
            MultipartHttpServletRequest request) {
        DocumentGroup group = groupService.createGroup(billCategoryId, name, request);
        return ResponseEntity.ok(group);
    }

    @GetMapping
    public ResponseEntity<List<GroupSummaryDto>> listGroups() {
        return ResponseEntity.ok(groupService.listGroups());
    }

    @GetMapping("/{id}")
    public ResponseEntity<GroupDetailDto> getGroupDetail(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.getGroupDetail(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DocumentGroup> patchGroup(
            @PathVariable Long id,
            @RequestParam(value = "name", required = false) String name,
            MultipartHttpServletRequest request) {
        DocumentGroup group = groupService.patchGroup(id, name, request);
        return ResponseEntity.ok(group);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> runGroup(@PathVariable Long id) {
        Long submissionId = groupService.runGroup(id);
        return ResponseEntity.accepted().body(Map.of(
                "submissionId", submissionId,
                "message", "Group run started. Submission ID: " + submissionId
        ));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }
}
