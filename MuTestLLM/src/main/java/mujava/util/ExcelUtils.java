package mujava.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.poi.util.IOUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;


public class ExcelUtils {

    public static void main(String[] args) {
        String filePath = "../MutantParse/data/mutant_statistic_1.xlsx";
        try {
            List<List<Object>> excelData = readExcel(filePath, 0);
            for (List<Object> row : excelData) {
                System.out.println(row);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<List<Object>> readExcel(String filePath, int sheetIndex) throws IOException {
        IOUtils.setByteArrayMaxOverride(300_000_000);
        List<List<Object>> data = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                List<Object> rowData = new ArrayList<>();
                for (Cell cell : row) {
                    switch (cell.getCellType()) {
                        case STRING:
                            rowData.add(cell.getStringCellValue());
                            break;
                        case NUMERIC:
                            // 处理日期格式
                            if (DateUtil.isCellDateFormatted(cell)) {
                                rowData.add(cell.getDateCellValue());
                            } else {
                                rowData.add(cell.getNumericCellValue());
                            }
                            break;
                        case BOOLEAN:
                            rowData.add(cell.getBooleanCellValue());
                            break;
                        case FORMULA:
                            rowData.add(cell.getCellFormula());
                            break;
                        case BLANK:
                            rowData.add("");
                            break;
                        default:
                            rowData.add("UNKNOWN");
                    }
                }
                data.add(rowData);
            }
        }
        return data;
    }

    public static void exportFailedCompileToExcel(List<String> failed, String testSrcDir) throws IOException {
        long tsMillis = System.currentTimeMillis();
        if (failed == null || failed.isEmpty()) return;

        Path evoSuiteDir = Paths.get(testSrcDir).toAbsolutePath().normalize().getParent();
        if (evoSuiteDir == null) evoSuiteDir = Paths.get(".").toAbsolutePath().normalize();
        Files.createDirectories(evoSuiteDir);

        // 失败文件（绝对路径、去重、排序）
        List<String> absFiles = failed.stream()
                .filter(Objects::nonNull)
                .map(p -> Paths.get(p).toAbsolutePath().normalize().toString())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // 失败目录（去重、排序）
        List<String> absDirs = absFiles.stream()
                .map(p -> Paths.get(p).getParent())
                .filter(Objects::nonNull)
                .map(Path::toString)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        Path outXlsx = evoSuiteDir.resolve("compile_failed"+tsMillis+".xlsx");

        try (Workbook wb = new XSSFWorkbook()) {
            // Sheet 1: failed files
            Sheet s1 = wb.createSheet("failed_files");
            Row h1 = s1.createRow(0);
            h1.createCell(0).setCellValue("index");
            h1.createCell(1).setCellValue("file_path");

            for (int i = 0; i < absFiles.size(); i++) {
                Row r = s1.createRow(i + 1);
                r.createCell(0).setCellValue(i + 1);
                r.createCell(1).setCellValue(absFiles.get(i));
            }

            // Sheet 2: failed dirs
            Sheet s2 = wb.createSheet("failed_dirs");
            Row h2 = s2.createRow(0);
            h2.createCell(0).setCellValue("index");
            h2.createCell(1).setCellValue("dir_path");

            for (int i = 0; i < absDirs.size(); i++) {
                Row r = s2.createRow(i + 1);
                r.createCell(0).setCellValue(i + 1);
                r.createCell(1).setCellValue(absDirs.get(i));
            }

            try (OutputStream os = Files.newOutputStream(outXlsx)) {
                wb.write(os);
            }
        }

        System.out.println("[EXPORT] failed excel -> " + outXlsx);
    }
}


