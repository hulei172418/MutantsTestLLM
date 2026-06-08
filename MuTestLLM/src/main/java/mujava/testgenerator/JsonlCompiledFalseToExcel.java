package mujava.testgenerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mujava.MutationSystem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Read a JSONL result file, print records whose compiled == false,
 * and export them to an Excel .xlsx file.
 *
 * Usage:
 *   java JsonlCompiledFalseToExcel input.jsonl compiled_false.xlsx
 */
public class JsonlCompiledFalseToExcel {

    private static final List<String> PREFERRED_COLUMNS = Arrays.asList(
            "lineNo",
            "taskId",
            "compiled",
            "testSetName",
            "targetClassName",
            "methodSignature",
            "mutantName",
            "testJavaFile",
            "failureReason",
            "compileMillis",
            "compileRounds",
            "compileCalls",
            "repairRounds",
            "llmCalls",
            "llmMillis",
            "totalMillis",
            "generatedAt",
            "reportDir"
    );

    public static void main(String[] args) throws Exception {
        String fileDir = "../Programs/ant-1.10.12/llm/report";
        Path reportRoot = Paths.get(fileDir);
        Path llmJson = reportRoot.resolve("llm_generation_results.jsonl");

        Path output = reportRoot.resolve("compiled_false.xlsx");

        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> failedRows = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>(PREFERRED_COLUMNS);

        int total = 0;
        int invalidJson = 0;

        try (BufferedReader br = Files.newBufferedReader(llmJson, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                total++;

                try {
                    JsonNode root = mapper.readTree(line);
                    JsonNode compiledNode = root.get("compiled");

                    boolean compiledFalse = compiledNode != null &&
                            ((compiledNode.isBoolean() && !compiledNode.asBoolean())
                                    || (compiledNode.isTextual() && "false".equalsIgnoreCase(compiledNode.asText())));

                    if (!compiledFalse) {
                        continue;
                    }

                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("lineNo", String.valueOf(lineNo));

                    Iterator<String> fieldNames = root.fieldNames();
                    while (fieldNames.hasNext()) {
                        String key = fieldNames.next();
                        JsonNode value = root.get(key);
                        row.put(key, value == null || value.isNull() ? "" : value.asText());
                        allKeys.add(key);
                    }

                    failedRows.add(row);
                    System.out.println("[compiled=false] line=" + lineNo
                            + ", taskId=" + row.getOrDefault("taskId", "")
                            + ", testSetName=" + row.getOrDefault("testSetName", "")
                            + ", failureReason=" + row.getOrDefault("failureReason", ""));

                } catch (Exception e) {
                    invalidJson++;
                    System.err.println("[WARN] Invalid JSON at line " + lineNo + ": " + e.getMessage());
                }
            }
        }

        List<String> columns = buildColumns(allKeys);
        writeExcel(output, columns, failedRows, total, invalidJson);

        System.out.println("Total records: " + total);
        System.out.println("compiled=false records: " + failedRows.size());
        System.out.println("Invalid JSON lines: " + invalidJson);
        System.out.println("Excel saved to: " + output.toAbsolutePath());
    }

    private static List<String> buildColumns(Set<String> allKeys) {
        List<String> columns = new ArrayList<>();
        for (String col : PREFERRED_COLUMNS) {
            if (allKeys.contains(col)) {
                columns.add(col);
            }
        }
        for (String key : allKeys) {
            if (!columns.contains(key)) {
                columns.add(key);
            }
        }
        return columns;
    }

    private static void writeExcel(Path output,
                                   List<String> columns,
                                   List<Map<String, String>> rows,
                                   int total,
                                   int invalidJson) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet summarySheet = workbook.createSheet("Summary");
            Sheet dataSheet = workbook.createSheet("CompiledFalse");

            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setWrapText(true);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row title = summarySheet.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue("compiled=false export summary");
            titleCell.setCellStyle(titleStyle);

            writeSummaryRow(summarySheet, 1, "Total JSONL records", total);
            writeSummaryRow(summarySheet, 2, "compiled=false records", rows.size());
            writeSummaryRow(summarySheet, 3, "Invalid JSON lines", invalidJson);

            for (int c = 0; c < 2; c++) {
                summarySheet.autoSizeColumn(c);
            }

            Row header = dataSheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(columns.get(c));
                cell.setCellStyle(headerStyle);
            }

            if (rows.isEmpty()) {
                Row row = dataSheet.createRow(1);
                row.createCell(0).setCellValue("No compiled=false records found.");
            } else {
                for (int r = 0; r < rows.size(); r++) {
                    Row excelRow = dataSheet.createRow(r + 1);
                    Map<String, String> item = rows.get(r);
                    for (int c = 0; c < columns.size(); c++) {
                        excelRow.createCell(c).setCellValue(item.getOrDefault(columns.get(c), ""));
                    }
                }
            }

            dataSheet.createFreezePane(0, 1);
            dataSheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, Math.max(1, rows.size()), 0, Math.max(0, columns.size() - 1)
            ));

            for (int c = 0; c < columns.size(); c++) {
                dataSheet.autoSizeColumn(c);
                int width = dataSheet.getColumnWidth(c);
                int maxWidth = 12000;
                if (width > maxWidth) {
                    dataSheet.setColumnWidth(c, maxWidth);
                }
            }

            try (OutputStream os = Files.newOutputStream(output)) {
                workbook.write(os);
            }
        }
    }

    private static void writeSummaryRow(Sheet sheet, int rowIndex, String key, int value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value);
    }
}
