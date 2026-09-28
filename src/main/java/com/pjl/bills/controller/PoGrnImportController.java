package com.pjl.bills.controller;

import com.pjl.bills.entity.PoGrnImportBatch;
import com.pjl.bills.repository.PoGrnImportBatchRepository;
import com.pjl.bills.service.PoGrnImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/po-grn-imports")
@RequiredArgsConstructor
public class PoGrnImportController {

    private final PoGrnImportService poGrnImportService;
    private final PoGrnImportBatchRepository poGrnImportBatchRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is required"));
        }
        return ResponseEntity.ok(poGrnImportService.importExcel(file));
    }

    @GetMapping
    public ResponseEntity<List<PoGrnImportBatch>> listImports() {
        return ResponseEntity.ok(poGrnImportBatchRepository.findAllByOrderByImportedAtDesc());
    }
}
