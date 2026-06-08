package org.utils;

import org.apache.poi.util.IOUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ExcelUtils {

    /**
     * Read an Excel file and return the data as a 2D List
     * 
     * @param filePath   Path to the Excel file
     * @param sheetIndex Index of the sheet to read (starting from 0)
     * @return A 2D List containing the Excel data
     * @throws IOException If reading the file fails
     */
    public static List<List<Object>> readExcel(String filePath, int sheetIndex) throws IOException {
        List<List<Object>> data = new ArrayList<>();
        IOUtils.setByteArrayMaxOverride(200_000_000);
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
                            // Handle date format
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

    public static void main(String[] args) {
        String filePath = "../MutantParse/data/mutant_statistic.xlsx";
        try {
            List<List<Object>> excelData = readExcel(filePath, 0);
            for (List<Object> row : excelData) {
                System.out.println(row);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
