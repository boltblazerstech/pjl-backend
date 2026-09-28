package com.pjl.bills.service;

import com.pjl.bills.entity.PoGrnImportBatch;
import com.pjl.bills.entity.PoGrnLine;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.repository.PoGrnImportBatchRepository;
import com.pjl.bills.repository.PoGrnLineRepository;
import com.pjl.bills.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoGrnImportService {

    private final PoGrnLineRepository poGrnLineRepository;
    private final PoGrnImportBatchRepository poGrnImportBatchRepository;
    private final SubmissionRepository submissionRepository;
    private final com.pjl.bills.service.VerificationOrchestrator verificationOrchestrator;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Transactional
    public Map<String, Object> importExcel(MultipartFile file) {
        log.info("Starting PO/GRN Excel import: {}", file.getOriginalFilename());

        PoGrnImportBatch batch = new PoGrnImportBatch();
        batch.setFileName(file.getOriginalFilename());
        batch.setImportedAt(Instant.now());

        List<PoGrnLine> parsedLines = new ArrayList<>();
        Set<String> distinctPos = new HashSet<>();
        int rowsRead = 0;
        int rowsSkipped = 0;

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            // Find header row
            int headerRowIdx = -1;
            for (Row row : sheet) {
                if (row == null) continue;
                Cell firstCell = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (firstCell != null && firstCell.getCellType() == CellType.STRING) {
                    if ("OU_ID".equalsIgnoreCase(firstCell.getStringCellValue().trim())) {
                        headerRowIdx = row.getRowNum();
                        break;
                    }
                }
                // Try scanning all cells in row for PO_Number just in case
                for (Cell c : row) {
                    if (c != null && c.getCellType() == CellType.STRING && "PO_Number".equalsIgnoreCase(c.getStringCellValue().trim())) {
                        headerRowIdx = row.getRowNum();
                        break;
                    }
                }
                if (headerRowIdx != -1) break;
            }

            if (headerRowIdx == -1) {
                throw new IllegalArgumentException("Could not find header row containing 'PO_Number' or 'OU_ID'.");
            }

            Row headerRow = sheet.getRow(headerRowIdx);
            Map<String, Integer> colMap = new HashMap<>();
            for (Cell cell : headerRow) {
                if (cell != null && cell.getCellType() == CellType.STRING) {
                    colMap.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
                }
            }

            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String poNumber = getStringValue(row, colMap, "PO_Number");
                if (poNumber == null || poNumber.trim().isEmpty()) {
                    continue; // Skip empty rows
                }
                rowsRead++;

                try {
                    PoGrnLine line = new PoGrnLine();
                    line.setImportBatch(batch);
                    line.setPoNumber(poNumber);
                    line.setPoAmendmentNo(getStringValue(row, colMap, "PO_Amendment_No"));
                    line.setPoDate(getDateValue(row, colMap, "PO_Date"));
                    line.setPoOrderQty(getDecimalValue(row, colMap, "PO_Order_Qty"));
                    line.setSupplierCode(getStringValue(row, colMap, "Supplier_code"));
                    line.setSupplierName(getStringValue(row, colMap, "Supplier_Name"));
                    line.setPaytermDesc(getStringValue(row, colMap, "Payterm_Desc"));
                    line.setGrNo(getStringValue(row, colMap, "GR_No"));
                    line.setGrDate(getDateValue(row, colMap, "GR_Date"));
                    line.setGrStatus(getStringValue(row, colMap, "GR_Status"));
                    line.setGrLineNo(getIntValue(row, colMap, "GR_Line_no"));
                    line.setPoLineNo(getIntValue(row, colMap, "PO_Line_no", -1)); // Required
                    line.setItemCode(getStringValue(row, colMap, "Item_code"));
                    line.setItemDesc(getStringValue(row, colMap, "Item_Desc"));
                    line.setDeliveryNoteQty(getDecimalValue(row, colMap, "Delivery_note_Qty"));
                    line.setAcceptedQty(getDecimalValue(row, colMap, "Accepted_Qty"));
                    line.setRejectedQty(getDecimalValue(row, colMap, "Rejected_Qty"));
                    line.setMovedQty(getDecimalValue(row, colMap, "Moved_Qty"));
                    line.setReceivedQty(getDecimalValue(row, colMap, "Received_Qty"));
                    line.setPoUnitRate(getDecimalValue(row, colMap, "PO_Unit_Rate"));
                    line.setPoLineValue(getDecimalValue(row, colMap, "PO_line_Value"));
                    line.setPoStatus(getStringValue(row, colMap, "Po_status"));
                    line.setGrnValue(getDecimalValue(row, colMap, "GRN_Value"));
                    line.setDelynoten(getStringValue(row, colMap, "delynoten"));
                    line.setItemType(getStringValue(row, colMap, "ItemType"));
                    line.setOuId(getStringValue(row, colMap, "OU_ID"));
                    line.setOuName(getStringValue(row, colMap, "OU_Name"));
                    line.setImportedAt(batch.getImportedAt());

                    parsedLines.add(line);
                    distinctPos.add(poNumber);
                } catch (Exception ex) {
                    log.warn("Skipping row {} due to error: {}", i, ex.getMessage());
                    rowsSkipped++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Excel file", e);
        }

        batch.setRowsRead(rowsRead);
        batch.setRowsSkipped(rowsSkipped);
        batch.setDistinctPos(distinctPos.size());
        
        batch = poGrnImportBatchRepository.save(batch);

        // Memory-based UPSERT matching
        List<PoGrnLine> existingLines = poGrnLineRepository.findByPoNumberIn(distinctPos);
        Map<String, PoGrnLine> existingMap = existingLines.stream()
                .collect(Collectors.toMap(PoGrnLine::getUniqueKey, l -> l, (a, b) -> a));

        int rowsInserted = 0;
        int rowsUpdated = 0;
        List<PoGrnLine> toSave = new ArrayList<>();

        for (PoGrnLine parsed : parsedLines) {
            String key = parsed.getUniqueKey();
            PoGrnLine existing = existingMap.get(key);
            if (existing != null) {
                // Update properties
                existing.setPoDate(parsed.getPoDate());
                existing.setPoOrderQty(parsed.getPoOrderQty());
                existing.setSupplierCode(parsed.getSupplierCode());
                existing.setSupplierName(parsed.getSupplierName());
                existing.setPaytermDesc(parsed.getPaytermDesc());
                existing.setGrDate(parsed.getGrDate());
                existing.setGrStatus(parsed.getGrStatus());
                existing.setItemCode(parsed.getItemCode());
                existing.setItemDesc(parsed.getItemDesc());
                existing.setDeliveryNoteQty(parsed.getDeliveryNoteQty());
                existing.setAcceptedQty(parsed.getAcceptedQty());
                existing.setRejectedQty(parsed.getRejectedQty());
                existing.setMovedQty(parsed.getMovedQty());
                existing.setReceivedQty(parsed.getReceivedQty());
                existing.setPoUnitRate(parsed.getPoUnitRate());
                existing.setPoLineValue(parsed.getPoLineValue());
                existing.setPoStatus(parsed.getPoStatus());
                existing.setGrnValue(parsed.getGrnValue());
                existing.setDelynoten(parsed.getDelynoten());
                existing.setItemType(parsed.getItemType());
                existing.setOuId(parsed.getOuId());
                existing.setOuName(parsed.getOuName());
                existing.setImportBatch(batch);
                existing.setImportedAt(batch.getImportedAt());
                toSave.add(existing);
                rowsUpdated++;
            } else {
                toSave.add(parsed);
                existingMap.put(key, parsed); // Add to map to prevent duplicates within same file
                rowsInserted++;
            }
        }

        poGrnLineRepository.saveAll(toSave);
        batch.setRowsInserted(rowsInserted);
        batch.setRowsUpdated(rowsUpdated);

        // Trigger verification for waiting submissions
        int submissionsResolved = 0;
        
        // Custom logic to find waiting submissions - assuming pageable approach
        int pageSize = 100;
        int pageNumber = 0;
        org.springframework.data.domain.Page<Submission> page;
        do {
            page = submissionRepository.findByStatus("AWAITING_PO_GRN", 
                org.springframework.data.domain.PageRequest.of(pageNumber, pageSize));
            
            for (Submission s : page.getContent()) {
                // Determine if this submission's PO is in distinctPos
                // This requires checking the raw extraction. For now, since verifyOnly will gracefully stay AWAITING
                // if it still can't find rows, we can safely just trigger verifyOnly for all of them!
                log.info("Triggering verifyOnly for waiting submission ID {}", s.getId());
                verificationOrchestrator.verifyOnly(s.getId());
                submissionsResolved++;
            }
            pageNumber++;
        } while (page.hasNext());

        batch.setSubmissionsResolved(submissionsResolved);
        poGrnImportBatchRepository.save(batch);

        return Map.of(
            "fileName", batch.getFileName(),
            "rowsRead", rowsRead,
            "rowsInserted", rowsInserted,
            "rowsUpdated", rowsUpdated,
            "rowsSkipped", rowsSkipped,
            "distinctPos", distinctPos.size(),
            "submissionsResolved", submissionsResolved
        );
    }

    // --- Helpers for POI ---

    private String getStringValue(Row row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((long) cell.getNumericCellValue());
        return cell.toString().trim();
    }

    private BigDecimal getDecimalValue(Row row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().replace(",", "").trim();
            if (s.isEmpty()) return null;
            try { return new BigDecimal(s); } catch (Exception e) { return null; }
        }
        return null;
    }

    private Integer getIntValue(Row row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            if (s.isEmpty()) return null;
            try { return Integer.parseInt(s); } catch (Exception e) { return null; }
        }
        return null;
    }

    private Integer getIntValue(Row row, Map<String, Integer> colMap, String colName, int defaultVal) {
        Integer val = getIntValue(row, colMap, colName);
        return val != null ? val : defaultVal;
    }

    private LocalDate getDateValue(Row row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        } else if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            if (s.isEmpty()) return null;
            try {
                return LocalDate.parse(s, DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                // Some fields might have 01-09-2026 instead of 01/09/2026
                try {
                     return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                } catch (Exception ex) {
                     return null;
                }
            }
        }
        return null;
    }
}
