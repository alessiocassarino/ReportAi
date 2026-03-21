package com.claude.reportAi.service;

import com.claude.reportAi.dto.MarkdownTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class XlsxProExportService {

    public String exportXlsxPro(MarkdownTable table, Path exportDir) throws IOException {
        if (!table.isValid()) {
            log.error("Invalid table provided for XLSX export");
            throw new IllegalArgumentException("Table is not valid");
        }

        String fileName = UUID.randomUUID() + ".xlsx";
        Path path = exportDir.resolve(fileName);

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream os = Files.newOutputStream(path)) {

            Sheet sheet = workbook.createSheet("Report");

            // 1. Create and style header row
            Row headerRow = sheet.createRow(0);
            CellStyle headerStyle = createHeaderStyle(workbook);

            for (int i = 0; i < table.getHeaders().size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(table.getHeaders().get(i));
                cell.setCellStyle(headerStyle);
            }

            // 2. Freeze first row
            sheet.createFreezePane(0, 1);

            // 3. Add data rows with alternating colors
            CellStyle evenRowStyle = createEvenRowStyle(workbook);
            CellStyle oddRowStyle = createOddRowStyle(workbook);

            for (int rowIdx = 0; rowIdx < table.getRows().size(); rowIdx++) {
                Row dataRow = sheet.createRow(rowIdx + 1);
                List<String> rowData = table.getRows().get(rowIdx);
                CellStyle rowStyle = (rowIdx % 2 == 0) ? evenRowStyle : oddRowStyle;

                for (int colIdx = 0; colIdx < rowData.size(); colIdx++) {
                    Cell cell = dataRow.createCell(colIdx);
                    String value = rowData.get(colIdx);

                    // Auto-detect number format
                    if (isNumeric(value)) {
                        try {
                            cell.setCellValue(Double.parseDouble(value));
                        } catch (NumberFormatException e) {
                            cell.setCellValue(value);
                        }
                    } else if (isCurrency(value)) {
                        cell.setCellValue(value);
                        CellStyle currencyStyle = createCurrencyStyle(workbook);
                        cell.setCellStyle(currencyStyle);
                        continue;
                    } else {
                        cell.setCellValue(value);
                    }

                    cell.setCellStyle(rowStyle);
                }
            }

            // 4. Auto-size columns
            for (int i = 0; i < table.getHeaders().size(); i++) {
                sheet.autoSizeColumn(i);
                int width = sheet.getColumnWidth(i) + 512;
                sheet.setColumnWidth(i, Math.min(width, 8000));
            }

            // 5. Add auto-filter
            if (!table.getRows().isEmpty()) {
                sheet.setAutoFilter(
                        new CellRangeAddress(0, table.getRows().size(), 0, table.getHeaders().size() - 1)
                );
            }

            workbook.write(os);

            log.info("XLSX Pro export completed -> file={}, rows={}, cols={}",
                    fileName, table.getRows().size(), table.getHeaders().size());

            return fileName;

        } catch (IOException e) {
            log.error("Error during XLSX export: {}", e.getMessage(), e);
            throw new IOException("Failed to export XLSX", e);
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createEvenRowStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(true);
        return style;
    }

    private CellStyle createOddRowStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.WHITE.getIndex());
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(true);
        return style;
    }

    private CellStyle createCurrencyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("€ #,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.replaceAll("[^0-9.-]", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isCurrency(String value) {
        return value != null && (value.startsWith("€") || value.startsWith("$") || value.startsWith("£"));
    }
}
