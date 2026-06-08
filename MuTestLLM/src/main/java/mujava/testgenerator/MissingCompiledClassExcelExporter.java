package mujava.testgenerator;

import mujava.MutationSystem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static mujava.cmd.TestRunner9_MultiProcess_batched.getCurr;
import static mujava.testgenerator.tools.TestNameUtils.buildGeneratedTestFqn;

public final class MissingCompiledClassExcelExporter {

    private MissingCompiledClassExcelExporter() {
    }

    private static final String DEFAULT_INPUT_EXCEL =
            "../MutantParse/data/mutant_statistic_total_graph_llm.xlsx";

    private static final String DEFAULT_OUTPUT_EXCEL =
            "../MutantParse/data/missing_llm_class_rows.xlsx";

    /**
     * 原始 Excel 列下标：
     * operator              0
     * line                  1
     * method                2
     * class                 3
     * class_f               4
     * mutation_statement    5
     * package               6
     * project               7
     * file_path             8
     * original_graph_path   9
     * mutant_graph_path     10
     * is_killed             11
     */
    private static final int COL_OPERATOR = 0;
    private static final int COL_PACKAGE = 6;
    private static final int COL_PROJECT = 7;
    private static final int COL_FILE_PATH = 8;
    private static final int COL_MUTANT_GRAPH_PATH = 10;

    private static final String EXTRA_COL_MUTANT_GRAPH_EXISTS = "mutant_graph_exists";

    private static final Set<String> TARGET_PROJECTS = new LinkedHashSet<String>(Arrays.asList(
            "ant-1.10.12",
            "bcel-6.10.0",
            "commons-codec-1.10",
            "commons-csv-1.2",
            "commons-jxpath-1.3",
            "commons-lang3-3.17.0",
            "jackson-core-2.9.9",
            "joda-time-2.14.0",
            "commons-cli-1.11.0",
            "oot",
            "commons-math-core",
            "commons-math-legacy-core",
            "commons-math-legacy-exception",
            "commons-math-neuralnet",
            "commons-math-transform",
            "commons-numbers-angle",
            "commons-numbers-arrays",
            "commons-numbers-combinatorics",
            "commons-numbers-core",
            "commons-numbers-gamma",
            "commons-numbers-primes",
            "commons-numbers-quaternion"
    ));

    public static void main(String[] args) throws Exception {
        String inputExcel = args != null && args.length > 0
                ? args[0]
                : System.getProperty("missing.input", DEFAULT_INPUT_EXCEL);

        String outputExcel = args != null && args.length > 1
                ? args[1]
                : System.getProperty("missing.output", DEFAULT_OUTPUT_EXCEL);

        exportMissingClassRows(Paths.get(inputExcel), Paths.get(outputExcel));
    }

    private static void exportMissingClassRows(Path inputExcel, Path outputExcel) throws Exception {
        if (!Files.isRegularFile(inputExcel)) {
            throw new IllegalArgumentException(
                    "Input excel not found: " + inputExcel.toAbsolutePath().normalize()
            );
        }

        long startedAt = System.currentTimeMillis();

        Map<String, Set<String>> classIndexCache = new HashMap<String, Set<String>>();
        Map<String, String> resultModuleHomeCache = new HashMap<String, String>();

        Map<String, Integer> totalByProject = initProjectCounter();
        Map<String, Integer> existingByProject = initProjectCounter();
        Map<String, Integer> missingByProject = initProjectCounter();
        Map<String, Integer> graphExistsByProject = initProjectCounter();
        Map<String, Integer> graphMissingByProject = initProjectCounter();

        int totalRows = 0;
        int selectedProjectRows = 0;
        int existingClassRows = 0;
        int missingClassRows = 0;
        int invalidRows = 0;
        int graphExistsRows = 0;
        int graphMissingRows = 0;
        IOUtils.setByteArrayMaxOverride(300_000_000);

        try (Workbook inWb = WorkbookFactory.create(new FileInputStream(inputExcel.toFile()));
             Workbook outWb = new XSSFWorkbook()) {

            Sheet inSheet = inWb.getSheetAt(0);
            Sheet outSheet = outWb.createSheet("missing_llm_class_rows");

            DataFormatter formatter = new DataFormatter();

            Row header = inSheet.getRow(0);
            if (header == null) {
                throw new IllegalArgumentException("Input excel has no header row.");
            }

            CellStyle textStyle = createTextStyle(outWb);
            CellStyle headerStyle = createHeaderStyle(outWb);

            short originalLastCellNum = header.getLastCellNum();
            int extraColIndex = originalLastCellNum < 0 ? 0 : originalLastCellNum;

            Row outHeader = outSheet.createRow(0);
            copyRowWithExtra(header, outHeader, textStyle, formatter, EXTRA_COL_MUTANT_GRAPH_EXISTS);
            for (int c = 0; c <= extraColIndex; c++) {
                Cell cell = outHeader.getCell(c);
                if (cell != null) {
                    cell.setCellStyle(headerStyle);
                }
            }

            int outRowIndex = 1;
            int lastRow = inSheet.getLastRowNum();

            for (int i = 1; i <= lastRow; i++) {
                Row row = inSheet.getRow(i);
                if (row == null) {
                    continue;
                }

                totalRows++;

                String project = cellString(row, COL_PROJECT, formatter);
                if (!TARGET_PROJECTS.contains(project)) {
                    continue;
                }

                selectedProjectRows++;
                totalByProject.put(project, totalByProject.get(project) + 1);

                String operator = cellString(row, COL_OPERATOR, formatter);
                String packageName = cellString(row, COL_PACKAGE, formatter);
                String rawFilePath = cellString(row, COL_FILE_PATH, formatter);
                String mutantGraphPath = cellString(row, COL_MUTANT_GRAPH_PATH, formatter);

                int graphExists = graphDirectoryExists(mutantGraphPath);
                if (graphExists == 1) {
                    graphExistsRows++;
                    graphExistsByProject.put(project, graphExistsByProject.get(project) + 1);
                } else {
                    graphMissingRows++;
                    graphMissingByProject.put(project, graphMissingByProject.get(project) + 1);
                }

                if (isBlank(operator) || isBlank(packageName) || isBlank(rawFilePath)) {
                    invalidRows++;
                    missingClassRows++;
                    missingByProject.put(project, missingByProject.get(project) + 1);

                    Row outRow = outSheet.createRow(outRowIndex++);
                    copyRowWithExtra(row, outRow, textStyle, formatter, String.valueOf(graphExists));
                    continue;
                }

                String resultModuleHome;
                try {
                    resultModuleHome = resolveResultModuleHomeCached(rawFilePath, resultModuleHomeCache);
                } catch (Throwable t) {
                    invalidRows++;
                    missingClassRows++;
                    missingByProject.put(project, missingByProject.get(project) + 1);

                    System.err.println("[WARN] resolve resultModuleHome failed, row=" + i
                            + ", project=" + project
                            + ", reason=" + oneLine(String.valueOf(t.getMessage())));

                    Row outRow = outSheet.createRow(outRowIndex++);
                    copyRowWithExtra(row, outRow, textStyle, formatter, String.valueOf(graphExists));
                    continue;
                }

                String testSetName = buildGeneratedTestFqn(packageName, operator);

                Set<String> existingClassFqns = getOrBuildClassIndex(
                        resultModuleHome,
                        classIndexCache
                );

                if (existingClassFqns.contains(testSetName)) {
                    existingClassRows++;
                    existingByProject.put(project, existingByProject.get(project) + 1);
                } else {
                    missingClassRows++;
                    missingByProject.put(project, missingByProject.get(project) + 1);

                    Row outRow = outSheet.createRow(outRowIndex++);
                    copyRowWithExtra(row, outRow, textStyle, formatter, String.valueOf(graphExists));
                }

                if (selectedProjectRows % 5000 == 0) {
                    System.out.println("[PROGRESS] selectedProjectRows=" + selectedProjectRows
                            + ", existingClassRows=" + existingClassRows
                            + ", missingClassRows=" + missingClassRows
                            + ", graphExistsRows=" + graphExistsRows
                            + ", graphMissingRows=" + graphMissingRows
                            + ", elapsedMs=" + (System.currentTimeMillis() - startedAt));
                }
            }

            setFixedColumnWidths(outSheet, extraColIndex + 1);

            Path parent = outputExcel.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (FileOutputStream fos = new FileOutputStream(outputExcel.toFile())) {
                outWb.write(fos);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;

        System.out.println("==================================================");
        System.out.println("[MISSING CLASS EXPORT SUMMARY]");
        System.out.println("inputExcel          = " + inputExcel.toAbsolutePath().normalize());
        System.out.println("outputExcel         = " + outputExcel.toAbsolutePath().normalize());
        System.out.println("totalRows           = " + totalRows);
        System.out.println("selectedProjectRows = " + selectedProjectRows);
        System.out.println("existingClassRows   = " + existingClassRows);
        System.out.println("missingClassRows    = " + missingClassRows);
        System.out.println("invalidRows         = " + invalidRows);
        System.out.println("graphExistsRows     = " + graphExistsRows);
        System.out.println("graphMissingRows    = " + graphMissingRows);
        System.out.println("elapsedMillis       = " + elapsedMs);
        System.out.println();

        for (String p : TARGET_PROJECTS) {
            int total = totalByProject.get(p);
            if (total == 0) {
                continue;
            }

            int existing = existingByProject.get(p);
            int missing = missingByProject.get(p);
            int graphOk = graphExistsByProject.get(p);
            int graphMiss = graphMissingByProject.get(p);

            double missingRatio = missing * 100.0 / total;
            double graphMissingRatio = graphMiss * 100.0 / total;

            System.out.printf(Locale.ROOT,
                    "%-36s total=%8d existing=%8d missing=%8d missingRatio=%6.2f%% graphExists=%8d graphMissing=%8d graphMissingRatio=%6.2f%%%n",
                    p,
                    total,
                    existing,
                    missing,
                    missingRatio,
                    graphOk,
                    graphMiss,
                    graphMissingRatio
            );
        }
    }

    /**
     * 按 LLMTestGeneratorBatch.java 的 resultModuleHome 推导方式：
     *
     * filepath = new File(rawFilePath).getParent().replace("\\", "/").replace("//?/", "");
     * resultModuleHome = Paths.get(getCurr(filepath)).normalize().toString();
     *
     * 这里对 Windows 长路径前缀做了兼容，避免 \\?\E:\xxx 在 Java/Windows 下解析异常。
     */
    private static String resolveResultModuleHomeCached(String rawFilePath,
                                                        Map<String, String> resultModuleHomeCache) {
        String cleaned = stripWindowsLongPrefix(rawFilePath);
        File file = new File(cleaned);
        String parent = file.getParent();

        if (parent == null) {
            throw new IllegalArgumentException("Cannot get parent from file_path: " + rawFilePath);
        }

        String filepath = parent
                .replace("\\", "/")
                .replace("//?/", "");

        String cached = resultModuleHomeCache.get(filepath);
        if (cached != null) {
            return cached;
        }

        String resultModuleHome = Paths.get(getCurr(filepath)).normalize().toString();
        resultModuleHomeCache.put(filepath, resultModuleHome);

        return resultModuleHome;
    }

    /**
     * 按 LLMTestGeneratorBatch 的 class 文件规则建索引：
     *
     * testClassesRoot = resultModuleHome / MutationSystem.TESTSET_MODE_LLMS / classes
     * expectedClassFile = testClassesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class")
     *
     * 这里反过来把 llm/classes 下的 .class 转为 FQN。
     */
    private static Set<String> getOrBuildClassIndex(String resultModuleHome,
                                                    Map<String, Set<String>> classIndexCache) throws IOException {
        Set<String> cached = classIndexCache.get(resultModuleHome);
        if (cached != null) {
            return cached;
        }

        Path classRoot = Paths.get(
                resultModuleHome,
                MutationSystem.TESTSET_MODE_LLMS,
                "classes"
        ).toAbsolutePath().normalize();

        Set<String> set = new HashSet<String>();

        if (!Files.isDirectory(classRoot)) {
            System.out.println("[CLASS-INDEX] classes root not found: " + classRoot);
            classIndexCache.put(resultModuleHome, set);
            return set;
        }

        Files.walkFileTree(classRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();

                if (!name.endsWith(".class")) {
                    return FileVisitResult.CONTINUE;
                }

                if (name.contains("$")) {
                    return FileVisitResult.CONTINUE;
                }

                Path rel = classRoot.relativize(file);
                String fqn = rel.toString()
                        .replace(File.separatorChar, '.')
                        .replace('/', '.')
                        .replace('\\', '.');

                if (fqn.endsWith(".class")) {
                    fqn = fqn.substring(0, fqn.length() - ".class".length());
                }

                set.add(fqn);
                return FileVisitResult.CONTINUE;
            }
        });

        classIndexCache.put(resultModuleHome, set);

        System.out.println("[CLASS-INDEX] resultModuleHome=" + resultModuleHome);
        System.out.println("[CLASS-INDEX] classRoot       =" + classRoot);
        System.out.println("[CLASS-INDEX] classCount      =" + set.size());

        return set;
    }

    /**
     * 判断 mutant_graph_path 对应的 graph 目录是否存在。
     *
     * 兼容两种情况：
     * 1. mutant_graph_path 本身就是 .../graph
     * 2. mutant_graph_path 是变异体目录 .../ROR_13，此时检查 .../ROR_13/graph
     */
    private static int graphDirectoryExists(String rawMutantGraphPath) {
        if (isBlank(rawMutantGraphPath)) {
            return 0;
        }

        try {
            String cleaned = stripWindowsLongPrefix(rawMutantGraphPath);
            Path p = Paths.get(cleaned).toAbsolutePath().normalize();

            if (Files.isDirectory(p)) {
                return 1;
            }

            if (!p.getFileName().toString().equalsIgnoreCase("graph")) {
                Path graphChild = p.resolve("graph").toAbsolutePath().normalize();
                if (Files.isDirectory(graphChild)) {
                    return 1;
                }
            }

            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Map<String, Integer> initProjectCounter() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        for (String p : TARGET_PROJECTS) {
            map.put(p, 0);
        }
        return map;
    }

    private static String cellString(Row row, int index, DataFormatter formatter) {
        if (row == null || index < 0) {
            return "";
        }

        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }

        String s = formatter.formatCellValue(cell);
        return s == null ? "" : s.trim();
    }

    /**
     * 复制原始行，并在最后追加一个字段。
     */
    private static void copyRowWithExtra(Row src,
                                         Row dst,
                                         CellStyle textStyle,
                                         DataFormatter formatter,
                                         String extraValue) {
        short last = src.getLastCellNum();
        int lastCell = last < 0 ? 0 : last;

        for (int c = 0; c < lastCell; c++) {
            Cell srcCell = src.getCell(c);
            Cell dstCell = dst.createCell(c, CellType.STRING);
            dstCell.setCellStyle(textStyle);

            if (srcCell == null) {
                dstCell.setCellValue("");
            } else {
                dstCell.setCellValue(formatter.formatCellValue(srcCell));
            }
        }

        Cell extraCell = dst.createCell(lastCell, CellType.STRING);
        extraCell.setCellStyle(textStyle);
        extraCell.setCellValue(extraValue == null ? "" : extraValue);
    }

    private static CellStyle createTextStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("@"));
        return style;
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = createTextStyle(wb);
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void setFixedColumnWidths(Sheet sheet, int colCount) {
        int count = Math.max(0, colCount);

        for (int c = 0; c < count; c++) {
            sheet.setColumnWidth(c, 24 * 256);
        }

        // file_path
        if (count > 8) {
            sheet.setColumnWidth(8, 90 * 256);
        }

        // original_graph_path
        if (count > 9) {
            sheet.setColumnWidth(9, 90 * 256);
        }

        // mutant_graph_path
        if (count > 10) {
            sheet.setColumnWidth(10, 90 * 256);
        }

        // mutant_graph_exists
        if (count > 12) {
            sheet.setColumnWidth(12, 24 * 256);
        }
    }

    private static String stripWindowsLongPrefix(String s) {
        if (s == null) {
            return "";
        }

        String v = s.trim();

        if (v.startsWith("\\\\?\\")) {
            return v.substring(4);
        }

        if (v.startsWith("//?/")) {
            return v.substring(4);
        }

        return v;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }

        return s.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}