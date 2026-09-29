package com.pjl.bills.controller;

import com.pjl.bills.entity.PoGrnLine;
import com.pjl.bills.repository.PoGrnLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/po-grn-lines")
@RequiredArgsConstructor
public class PoGrnLineController {

    private final PoGrnLineRepository poGrnLineRepository;

    /**
     * Retrieves a paginated list of imported PO/GRN lines.
     * Can optionally be filtered by a specific importBatchId or searched by PO Number.
     */
    @GetMapping
    public ResponseEntity<Page<PoGrnLine>> getLines(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String search,
            @PageableDefault(sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        
        if (batchId != null) {
            return ResponseEntity.ok(poGrnLineRepository.findByImportBatch_Id(batchId, pageable));
        } else if (StringUtils.hasText(search)) {
            return ResponseEntity.ok(poGrnLineRepository.findByPoNumberContainingIgnoreCase(search, pageable));
        } else {
            return ResponseEntity.ok(poGrnLineRepository.findAll(pageable));
        }
    }
}
