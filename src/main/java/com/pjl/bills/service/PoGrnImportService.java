package com.pjl.bills.service;

import com.pjl.bills.entity.Document;
import com.pjl.bills.entity.PoGrnImportBatch;
import com.pjl.bills.entity.Submission;
import com.pjl.bills.repository.DocumentRepository;
import com.pjl.bills.repository.PoGrnImportBatchRepository;
import com.pjl.bills.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoGrnImportService {

    private final JdbcTemplate jdbcTemplate;
    private final PoGrnImportBatchRepository poGrnImportBatchRepository;
    private final SubmissionRepository submissionRepository;
    private final DocumentRepository documentRepository;
    private final VerificationOrchestrator verificationOrchestrator;

    private static final int BATCH_SIZE = 1000;

    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy")
    };

    private static final String UPSERT_SQL = """
            INSERT INTO po_grn_line (
                import_batch_id, po_number, po_amendment_no, po_date, po_order_qty,
                supplier_code, supplier_name, payterm_desc, gr_no, gr_date,
                gr_status, gr_line_no, po_line_no, item_code, item_desc,
                delivery_note_qty, accepted_qty, rejected_qty, moved_qty, received_qty,
                po_unit_rate, po_line_value, po_status, grn_value, delynoten,
                item_type, ou_id, ou_name, imported_at, created_at, updated_at, created_by, updated_by
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?
            )
            ON CONFLICT ON CONSTRAINT uq_po_grn_line
            DO UPDATE SET
                import_batch_id = EXCLUDED.import_batch_id,
                po_date = EXCLUDED.po_date,
                po_order_qty = EXCLUDED.po_order_qty,
                supplier_code = EXCLUDED.supplier_code,
                supplier_name = EXCLUDED.supplier_name,
                payterm_desc = EXCLUDED.payterm_desc,
                gr_date = EXCLUDED.gr_date,
                gr_status = EXCLUDED.gr_status,
                item_code = EXCLUDED.item_code,
                item_desc = EXCLUDED.item_desc,
                delivery_note_qty = EXCLUDED.delivery_note_qty,
                accepted_qty = EXCLUDED.accepted_qty,
                rejected_qty = EXCLUDED.rejected_qty,
                moved_qty = EXCLUDED.moved_qty,
                received_qty = EXCLUDED.received_qty,
                po_unit_rate = EXCLUDED.po_unit_rate,
                po_line_value = EXCLUDED.po_line_value,
                po_status = EXCLUDED.po_status,
                grn_value = EXCLUDED.grn_value,
                delynoten = EXCLUDED.delynoten,
                item_type = EXCLUDED.item_type,
                ou_id = EXCLUDED.ou_id,
                ou_name = EXCLUDED.ou_name,
                imported_at = EXCLUDED.imported_at,
                updated_at = EXCLUDED.updated_at
            """;

    @Transactional
    public Map<String, Object> importExcel(MultipartFile file) {
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "import.xlsx";
        log.info("Starting PO/GRN Excel import: {}", originalFilename);

        PoGrnImportBatch batch = new PoGrnImportBatch();
        batch.setFileName(originalFilename);
        batch.setImportedAt(Instant.now());
        batch = poGrnImportBatchRepository.save(batch);

        Long batchId = batch.getId();
        Instant importedAt = batch.getImportedAt();
        Timestamp nowTs = Timestamp.from(importedAt);

        Long countBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM po_grn_line", Long.class);
        if (countBefore == null) countBefore = 0L;

        Path tempFile = null;
        int rowsRead = 0;
        int rowsSkipped = 0;
        Set<String> distinctPos = new HashSet<>();

        try {
            tempFile = Files.createTempFile("pjl_import_", "_" + originalFilename);
            file.transferTo(tempFile);

            boolean isXls = originalFilename.toLowerCase().endsWith(".xls") && !originalFilename.toLowerCase().endsWith(".xlsx");

            if (isXls) {
                // Fallback for legacy .xls files
                ImportStats stats = parseHssfLegacy(tempFile, batchId, importedAt, nowTs);
                rowsRead = stats.rowsRead;
                rowsSkipped = stats.rowsSkipped;
                distinctPos = stats.distinctPos;
            } else {
                // High-performance streaming SAX parser for .xlsx (minimal memory footprint)
                ImportStats stats = parseXlsxStreaming(tempFile, batchId, importedAt, nowTs);
                rowsRead = stats.rowsRead;
                rowsSkipped = stats.rowsSkipped;
                distinctPos = stats.distinctPos;
            }

        } catch (Exception e) {
            log.error("Failed to parse and import Excel file {}", originalFilename, e);
            throw new RuntimeException("Failed to parse Excel file: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {}
            }
        }

        Long countAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM po_grn_line", Long.class);
        if (countAfter == null) countAfter = countBefore;

        int rowsInserted = (int) Math.max(0, countAfter - countBefore);
        int rowsUpdated = Math.max(0, rowsRead - rowsInserted);

        batch.setRowsRead(rowsRead);
        batch.setRowsInserted(rowsInserted);
        batch.setRowsUpdated(rowsUpdated);
        batch.setRowsSkipped(rowsSkipped);
        batch.setDistinctPos(distinctPos.size());

        // Trigger verification ONLY for submissions waiting for PO numbers present in this batch
        int submissionsResolved = triggerWaitingSubmissions(distinctPos);
        batch.setSubmissionsResolved(submissionsResolved);
        poGrnImportBatchRepository.save(batch);

        log.info("PO/GRN Excel import complete: read={}, inserted={}, updated={}, distinctPOs={}, resolvedSubmissions={}",
                rowsRead, rowsInserted, rowsUpdated, distinctPos.size(), submissionsResolved);

        return Map.of(
                "id", batch.getId(),
                "fileName", batch.getFileName(),
                "rowsRead", rowsRead,
                "rowsInserted", rowsInserted,
                "rowsUpdated", rowsUpdated,
                "rowsSkipped", rowsSkipped,
                "distinctPos", distinctPos.size(),
                "submissionsResolved", submissionsResolved
        );
    }

    private int triggerWaitingSubmissions(Set<String> distinctPos) {
        if (distinctPos == null || distinctPos.isEmpty()) return 0;

        List<Submission> waitingSubmissions = submissionRepository.findByStatus("AWAITING_PO_GRN");
        int triggered = 0;

        for (Submission s : waitingSubmissions) {
            List<Document> docs = documentRepository.findBySubmission(s);
            for (Document d : docs) {
                if ("invoice".equals(d.getDocType()) && d.getRawExtraction() != null) {
                    Object poVal = d.getRawExtraction().get("po_number");
                    if (poVal != null) {
                        String poNum = String.valueOf(poVal).trim();
                        if (distinctPos.contains(poNum)) {
                            log.info("Triggering verifyOnly for waiting submission ID {} (PO: {})", s.getId(), poNum);
                            verificationOrchestrator.verifyOnly(s.getId());
                            triggered++;
                            break;
                        }
                    }
                }
            }
        }

        return triggered;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // High-performance SAX Streaming for XLSX
    // ─────────────────────────────────────────────────────────────────────────────

    private ImportStats parseXlsxStreaming(Path file, Long batchId, Instant importedAt, Timestamp nowTs) throws Exception {
        ImportStats stats = new ImportStats();
        Map<String, Integer> colMap = new HashMap<>();
        AtomicInteger headerRowIdx = new AtomicInteger(-1);

        List<RowData> buffer = new ArrayList<>(BATCH_SIZE);

        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XMLReader parser = XMLHelper.newXMLReader();

            Map<Integer, String> currentRow = new HashMap<>();

            XSSFSheetXMLHandler handler = new XSSFSheetXMLHandler(styles, sst, new SheetContentsHandler() {
                @Override
                public void startRow(int rowNum) {
                    currentRow.clear();
                }

                @Override
                public void endRow(int rowNum) {
                    if (headerRowIdx.get() == -1) {
                        for (Map.Entry<Integer, String> entry : currentRow.entrySet()) {
                            String val = entry.getValue().trim();
                            if ("PO_Number".equalsIgnoreCase(val) || "OU_ID".equalsIgnoreCase(val)) {
                                headerRowIdx.set(rowNum);
                                break;
                            }
                        }
                        if (headerRowIdx.get() != -1) {
                            for (Map.Entry<Integer, String> entry : currentRow.entrySet()) {
                                colMap.put(entry.getValue().trim().toLowerCase(), entry.getKey());
                            }
                        }
                        return;
                    }

                    String poNumber = getString(currentRow, colMap, "po_number");
                    if (poNumber == null || poNumber.isEmpty()) {
                        return; // Skip empty row
                    }

                    try {
                        RowData data = new RowData();
                        data.poNumber = poNumber;
                        data.poAmendmentNo = getString(currentRow, colMap, "po_amendment_no");
                        data.poDate = getDate(currentRow, colMap, "po_date");
                        data.poOrderQty = getDecimal(currentRow, colMap, "po_order_qty");
                        data.supplierCode = getString(currentRow, colMap, "supplier_code");
                        data.supplierName = getString(currentRow, colMap, "supplier_name");
                        data.paytermDesc = getString(currentRow, colMap, "payterm_desc");
                        data.grNo = getString(currentRow, colMap, "gr_no");
                        data.grDate = getDate(currentRow, colMap, "gr_date");
                        data.grStatus = getString(currentRow, colMap, "gr_status");
                        data.grLineNo = getInt(currentRow, colMap, "gr_line_no", null);
                        data.poLineNo = getInt(currentRow, colMap, "po_line_no", -1);
                        data.itemCode = getString(currentRow, colMap, "item_code");
                        data.itemDesc = getString(currentRow, colMap, "item_desc");
                        data.deliveryNoteQty = getDecimal(currentRow, colMap, "delivery_note_qty");
                        data.acceptedQty = getDecimal(currentRow, colMap, "accepted_qty");
                        data.rejectedQty = getDecimal(currentRow, colMap, "rejected_qty");
                        data.movedQty = getDecimal(currentRow, colMap, "moved_qty");
                        data.receivedQty = getDecimal(currentRow, colMap, "received_qty");
                        data.poUnitRate = getDecimal(currentRow, colMap, "po_unit_rate");
                        data.poLineValue = getDecimal(currentRow, colMap, "po_line_value");
                        data.poStatus = getString(currentRow, colMap, "po_status");
                        data.grnValue = getDecimal(currentRow, colMap, "grn_value");
                        data.delynoten = getString(currentRow, colMap, "delynoten");
                        data.itemType = getString(currentRow, colMap, "itemtype");
                        data.ouId = getString(currentRow, colMap, "ou_id");
                        data.ouName = getString(currentRow, colMap, "ou_name");

                        buffer.add(data);
                        stats.rowsRead++;
                        stats.distinctPos.add(poNumber);

                        if (buffer.size() >= BATCH_SIZE) {
                            flushBatch(buffer, batchId, importedAt, nowTs);
                            buffer.clear();
                        }
                    } catch (Exception ex) {
                        stats.rowsSkipped++;
                    }
                }

                @Override
                public void cell(String cellReference, String formattedValue, XSSFComment comment) {
                    if (formattedValue != null) {
                        int col = new CellReference(cellReference).getCol();
                        currentRow.put(col, formattedValue.trim());
                    }
                }
            }, false);

            parser.setContentHandler(handler);

            try (InputStream is = reader.getSheetsData().next()) {
                parser.parse(new InputSource(is));
            }

            if (!buffer.isEmpty()) {
                flushBatch(buffer, batchId, importedAt, nowTs);
                buffer.clear();
            }
        }

        if (headerRowIdx.get() == -1) {
            throw new IllegalArgumentException("Could not find header row containing 'PO_Number' or 'OU_ID'.");
        }

        return stats;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fallback for legacy HSSF (.xls) files
    // ─────────────────────────────────────────────────────────────────────────────

    private ImportStats parseHssfLegacy(Path file, Long batchId, Instant importedAt, Timestamp nowTs) throws Exception {
        ImportStats stats = new ImportStats();
        List<RowData> buffer = new ArrayList<>(BATCH_SIZE);

        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerRowIdx = -1;
            for (Row row : sheet) {
                if (row == null) continue;
                for (Cell c : row) {
                    if (c != null && c.getCellType() == CellType.STRING) {
                        String val = c.getStringCellValue().trim();
                        if ("PO_Number".equalsIgnoreCase(val) || "OU_ID".equalsIgnoreCase(val)) {
                            headerRowIdx = row.getRowNum();
                            break;
                        }
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
                    colMap.put(cell.getStringCellValue().trim().toLowerCase(), cell.getColumnIndex());
                }
            }

            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String poNumber = getStringFromPoi(row, colMap, "po_number");
                if (poNumber == null || poNumber.isEmpty()) continue;

                try {
                    RowData data = new RowData();
                    data.poNumber = poNumber;
                    data.poAmendmentNo = getStringFromPoi(row, colMap, "po_amendment_no");
                    data.poDate = getDateFromPoi(row, colMap, "po_date");
                    data.poOrderQty = getDecimalFromPoi(row, colMap, "po_order_qty");
                    data.supplierCode = getStringFromPoi(row, colMap, "supplier_code");
                    data.supplierName = getStringFromPoi(row, colMap, "supplier_name");
                    data.paytermDesc = getStringFromPoi(row, colMap, "payterm_desc");
                    data.grNo = getStringFromPoi(row, colMap, "gr_no");
                    data.grDate = getDateFromPoi(row, colMap, "gr_date");
                    data.grStatus = getStringFromPoi(row, colMap, "gr_status");
                    data.grLineNo = getIntFromPoi(row, colMap, "gr_line_no", null);
                    data.poLineNo = getIntFromPoi(row, colMap, "po_line_no", -1);
                    data.itemCode = getStringFromPoi(row, colMap, "item_code");
                    data.itemDesc = getStringFromPoi(row, colMap, "item_desc");
                    data.deliveryNoteQty = getDecimalFromPoi(row, colMap, "delivery_note_qty");
                    data.acceptedQty = getDecimalFromPoi(row, colMap, "accepted_qty");
                    data.rejectedQty = getDecimalFromPoi(row, colMap, "rejected_qty");
                    data.movedQty = getDecimalFromPoi(row, colMap, "moved_qty");
                    data.receivedQty = getDecimalFromPoi(row, colMap, "received_qty");
                    data.poUnitRate = getDecimalFromPoi(row, colMap, "po_unit_rate");
                    data.poLineValue = getDecimalFromPoi(row, colMap, "po_line_value");
                    data.poStatus = getStringFromPoi(row, colMap, "po_status");
                    data.grnValue = getDecimalFromPoi(row, colMap, "grn_value");
                    data.delynoten = getStringFromPoi(row, colMap, "delynoten");
                    data.itemType = getStringFromPoi(row, colMap, "itemtype");
                    data.ouId = getStringFromPoi(row, colMap, "ou_id");
                    data.ouName = getStringFromPoi(row, colMap, "ou_name");

                    buffer.add(data);
                    stats.rowsRead++;
                    stats.distinctPos.add(poNumber);

                    if (buffer.size() >= BATCH_SIZE) {
                        flushBatch(buffer, batchId, importedAt, nowTs);
                        buffer.clear();
                    }
                } catch (Exception ex) {
                    stats.rowsSkipped++;
                }
            }

            if (!buffer.isEmpty()) {
                flushBatch(buffer, batchId, importedAt, nowTs);
                buffer.clear();
            }
        }

        return stats;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JDBC Batch Flusher
    // ─────────────────────────────────────────────────────────────────────────────

    private void flushBatch(List<RowData> rows, Long batchId, Instant importedAt, Timestamp nowTs) {
        if (rows == null || rows.isEmpty()) return;

        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RowData r = rows.get(i);
                ps.setLong(1, batchId);
                ps.setString(2, r.poNumber);
                ps.setString(3, r.poAmendmentNo);
                ps.setDate(4, r.poDate != null ? java.sql.Date.valueOf(r.poDate) : null);
                ps.setBigDecimal(5, r.poOrderQty);
                ps.setString(6, r.supplierCode);
                ps.setString(7, r.supplierName);
                ps.setString(8, r.paytermDesc);
                ps.setString(9, r.grNo);
                ps.setDate(10, r.grDate != null ? java.sql.Date.valueOf(r.grDate) : null);
                ps.setString(11, r.grStatus);
                if (r.grLineNo != null) ps.setInt(12, r.grLineNo); else ps.setNull(12, java.sql.Types.INTEGER);
                ps.setInt(13, r.poLineNo != null ? r.poLineNo : -1);
                ps.setString(14, r.itemCode);
                ps.setString(15, r.itemDesc);
                ps.setBigDecimal(16, r.deliveryNoteQty);
                ps.setBigDecimal(17, r.acceptedQty);
                ps.setBigDecimal(18, r.rejectedQty);
                ps.setBigDecimal(19, r.movedQty);
                ps.setBigDecimal(20, r.receivedQty);
                ps.setBigDecimal(21, r.poUnitRate);
                ps.setBigDecimal(22, r.poLineValue);
                ps.setString(23, r.poStatus);
                ps.setBigDecimal(24, r.grnValue);
                ps.setString(25, r.delynoten);
                ps.setString(26, r.itemType);
                ps.setString(27, r.ouId);
                ps.setString(28, r.ouName);
                ps.setTimestamp(29, Timestamp.from(importedAt));
                ps.setTimestamp(30, nowTs);
                ps.setTimestamp(31, nowTs);
                ps.setString(32, "system");
                ps.setString(33, "system");
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Parsing Utilities
    // ─────────────────────────────────────────────────────────────────────────────

    private static String getString(Map<Integer, String> row, Map<String, Integer> colMap, String col) {
        Integer idx = colMap.get(col);
        return idx != null ? row.get(idx) : null;
    }

    private static Integer getInt(Map<Integer, String> row, Map<String, Integer> colMap, String col, Integer defVal) {
        String s = getString(row, colMap, col);
        if (s == null || s.isEmpty()) return defVal;
        if (s.contains(".")) s = s.substring(0, s.indexOf('.'));
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defVal; }
    }

    private static BigDecimal getDecimal(Map<Integer, String> row, Map<String, Integer> colMap, String col) {
        String s = getString(row, colMap, col);
        if (s == null || s.isEmpty()) return null;
        s = s.replace(",", "").trim();
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private static LocalDate getDate(Map<Integer, String> row, Map<String, Integer> colMap, String col) {
        String s = getString(row, colMap, col);
        if (s == null || s.isEmpty()) return null;
        s = s.trim();
        if (s.contains(" ")) s = s.substring(0, s.indexOf(' ')).trim();
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try { return LocalDate.parse(s, fmt); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String getStringFromPoi(Row row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null) return null;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((long) cell.getNumericCellValue());
        return cell.toString().trim();
    }

    private static BigDecimal getDecimalFromPoi(Row row, Map<String, Integer> colMap, String colName) {
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

    private static Integer getIntFromPoi(Row row, Map<String, Integer> colMap, String colName, Integer defaultVal) {
        Integer idx = colMap.get(colName);
        if (idx == null) return defaultVal;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return defaultVal;
        if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            if (s.isEmpty()) return defaultVal;
            if (s.contains(".")) s = s.substring(0, s.indexOf('.'));
            try { return Integer.parseInt(s); } catch (Exception e) { return defaultVal; }
        }
        return defaultVal;
    }

    private static LocalDate getDateFromPoi(Row row, Map<String, Integer> colMap, String colName) {
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
            if (s.contains(" ")) s = s.substring(0, s.indexOf(' ')).trim();
            for (DateTimeFormatter fmt : DATE_FORMATTERS) {
                try { return LocalDate.parse(s, fmt); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static class ImportStats {
        int rowsRead = 0;
        int rowsSkipped = 0;
        Set<String> distinctPos = new HashSet<>();
    }

    private static class RowData {
        String poNumber;
        String poAmendmentNo;
        LocalDate poDate;
        BigDecimal poOrderQty;
        String supplierCode;
        String supplierName;
        String paytermDesc;
        String grNo;
        LocalDate grDate;
        String grStatus;
        Integer grLineNo;
        Integer poLineNo;
        String itemCode;
        String itemDesc;
        BigDecimal deliveryNoteQty;
        BigDecimal acceptedQty;
        BigDecimal rejectedQty;
        BigDecimal movedQty;
        BigDecimal receivedQty;
        BigDecimal poUnitRate;
        BigDecimal poLineValue;
        String poStatus;
        BigDecimal grnValue;
        String delynoten;
        String itemType;
        String ouId;
        String ouName;
    }
}
