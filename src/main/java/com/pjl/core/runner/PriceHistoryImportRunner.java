package com.pjl.core.runner;

import com.pjl.bills.entity.PriceHistory;
import com.pjl.bills.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off script to migrate historical price data from Excel.
 * Triggered by passing --import.excel.enabled=true to the application.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "import.excel.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PriceHistoryImportRunner implements CommandLineRunner {

    private final PriceHistoryRepository priceHistoryRepository;

    @Override
    public void run(String... args) throws Exception {
        log.info("================================================");
        log.info("Starting Excel Import for PriceHistory...");
        log.info("================================================");

        File file = new File("data/store_spare_history.xlsx");
        if (!file.exists()) {
            log.error("File not found: {}", file.getAbsolutePath());
            return;
        }

        long countBlankItemCode = 0;
        long countBlankRate = 0;
        long countBlankDate = 0;
        long countUnparseableDate = 0;
        long totalRead = 0;
        long totalImported = 0;

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        List<PriceHistory> batch = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            
            // Data starts at row 3 (0-indexed 2)
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                totalRead++;

                Cell dateCell = row.getCell(0);      // A
                Cell vendorCell = row.getCell(1);    // B
                Cell itemCodeCell = row.getCell(8);  // I
                Cell rateCell = row.getCell(12);     // M

                boolean skip = false;

                // Check item code
                String itemCode = getCellValueAsString(itemCodeCell);
                if (itemCode == null || itemCode.isBlank()) {
                    countBlankItemCode++;
                    skip = true;
                }

                // Check rate
                String rateStr = getCellValueAsString(rateCell);
                BigDecimal rate = null;
                if (rateStr == null || rateStr.isBlank()) {
                    countBlankRate++;
                    skip = true;
                } else {
                    try {
                        rate = new BigDecimal(rateStr);
                    } catch (NumberFormatException e) {
                        countBlankRate++; // Malformed considered blank for this filter
                        skip = true;
                    }
                }

                // Check date
                LocalDate purchaseDate = null;
                if (dateCell == null || dateCell.getCellType() == CellType.BLANK) {
                    countBlankDate++;
                    skip = true;
                } else if (dateCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                    purchaseDate = dateCell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                } else {
                    String dateStr = getCellValueAsString(dateCell);
                    if (dateStr == null || dateStr.isBlank()) {
                        countBlankDate++;
                        skip = true;
                    } else {
                        try {
                            purchaseDate = LocalDate.parse(dateStr.trim(), dateFormatter);
                        } catch (Exception e) {
                            countUnparseableDate++;
                            skip = true;
                        }
                    }
                }

                if (skip) {
                    continue; // Talley was already added to the respective reasons
                }

                // Vendor fallback
                String vendor = getCellValueAsString(vendorCell);
                if (vendor == null || vendor.isBlank()) {
                    vendor = "Unknown";
                }

                PriceHistory ph = new PriceHistory();
                ph.setItemCode(itemCode.trim());
                ph.setVendor(vendor.trim());
                ph.setRate(rate);
                ph.setPurchaseDate(purchaseDate);
                ph.setSource("excel_import");

                batch.add(ph);
                totalImported++;

                if (batch.size() >= 1000) {
                    priceHistoryRepository.saveAll(batch);
                    batch.clear();
                    log.info("... inserted {} rows so far ...", totalImported);
                }
            }

            if (!batch.isEmpty()) {
                priceHistoryRepository.saveAll(batch);
            }
        }

        log.info("================================================");
        log.info("IMPORT SUMMARY");
        log.info("================================================");
        log.info("Total rows read:        {}", totalRead);
        log.info("Rows imported:          {}", totalImported);
        log.info("Total skipped rows:     {}", totalRead - totalImported);
        log.info(" -- Skipped (blank item code): {}", countBlankItemCode);
        log.info(" -- Skipped (blank rate):      {}", countBlankRate);
        log.info(" -- Skipped (blank date):      {}", countBlankDate);
        log.info(" -- Skipped (bad date format): {}", countUnparseableDate);

        long finalCount = priceHistoryRepository.count();
        log.info("Final PriceHistory DB count: {}", finalCount);
        log.info("================================================");
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                // If it's a whole number, don't return "2201011.0", format cleanly
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val)) {
                    yield String.format("%.0f", val);
                }
                yield String.valueOf(val);
            }
            default -> null;
        };
    }
}
