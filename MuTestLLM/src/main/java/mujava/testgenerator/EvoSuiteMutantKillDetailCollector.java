package mujava.testgenerator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.cmd.TestRunner9_MultiProcess_batched_1.readExcelFile;

/**
 * EvoSuite 变异体杀死结果汇总器。
 *
 * 功能：
 * 1. 以 Excel 变异体清单为白名单；
 * 2. 只扫描 result 目录；
 * 3. 只检查 result/<targetClassName>/traditional_mutants/kill_statistic_evoSuite.txt；
 * 4. 不进入 traditional_mutants/methodSignature/mutantName 子目录；
 * 5. 汇总每个 Excel 变异体在 EvoSuite 测试套件下的 KILLED / LIVE / NOT_REPORTED 状态；
 * 6. 输出 CSV，便于和 LLM 汇总结果横向对比。
 *
 * 默认目录：
 *   ProgramsRoot = E:/PHD/testJava/Programs
 *   Excel       = E:/PHD/testJava/MutantParse/data/mutant_statistic_total_graph_llm.xlsx
 *   Output CSV  = E:/PHD/testJava/MutantParse/data/evosuite_mutant_kill_detail_merged.csv
 *
 * 用法：
 *   1）使用默认路径：
 *      java mujava.testgenerator.EvoSuiteMutantKillDetailCollector_result_only
 *
 *   2）手动传入路径：
 *      java mujava.testgenerator.EvoSuiteMutantKillDetailCollector_result_only ^
 *          E:/PHD/testJava/Programs ^
 *          E:/PHD/testJava/MutantParse/data/mutant_statistic_total_graph_llm.xlsx ^
 *          E:/PHD/testJava/MutantParse/data/evosuite_mutant_kill_detail_merged.csv
 */
public class EvoSuiteMutantKillDetailCollector {

    private static final Path DEFAULT_PROGRAMS_ROOT =
            Paths.get("E:/PHD/testJava/Programs").toAbsolutePath().normalize();

    private static final Path DEFAULT_EXCEL_PATH =
            Paths.get("E:/PHD/testJava/MutantParse/data/mutant_statistic_total_graph_llm.xlsx")
                    .toAbsolutePath()
                    .normalize();

    private static final Path DEFAULT_OUT_CSV =
            Paths.get("E:/PHD/testJava/MutantParse/data/evosuite_mutant_kill_detail_merged.csv")
                    .toAbsolutePath()
                    .normalize();

    private static final String KILL_STATISTIC_FILE = "kill_statistic_evoSuite.txt";
    private static final String TRADITIONAL_MUTANTS_DIR = "traditional_mutants";

    /*
     * true：除了检查 Excel 推导出的精确路径外，再扫描 ProgramsRoot 下所有 result 目录；
     *      进入 result 后，只检查 result/<targetClassName>/traditional_mutants/kill_statistic_evoSuite.txt。
     * false：只检查 Excel 推导出的精确路径，速度最快。
     */
    private static final boolean SCAN_RESULT_DIRS_FALLBACK = true;

    /*
     * Excel 列索引，与 LlmMutantKillDetailCollector 中保持一致。
     */
    private static final int COL_OPERATOR = 0;
    private static final int COL_METHOD = 2;
    private static final int COL_CLASS_F = 4;
    private static final int COL_PACKAGE = 6;
    private static final int COL_PROJECT = 7;
    private static final int COL_FILE_PATH = 8;
    private static final int COL_ORIGINAL_GRAPH_PATH = 9;
    private static final int COL_MUTANT_GRAPH_PATH = 10;
    private static final int COL_IS_KILLED = 11;

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?s)(test_results|mutant_results|killed_mutants|live_mutants|mutant_score)\\s*=\\s*(.*?)(?=\\n(?:test_results|mutant_results|killed_mutants|live_mutants|mutant_score)\\s*=|\\z)"
    );

    private static final Pattern MUTANT_KEY_PATTERN = Pattern.compile("[A-Z]+_\\d+");

    private static final Set<String> EXECUTED_PROJECTS = new LinkedHashSet<String>(Arrays.asList(
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
            // "commons-math-legacy",
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
        Path programsRoot = args.length >= 1
                ? Paths.get(args[0]).toAbsolutePath().normalize()
                : DEFAULT_PROGRAMS_ROOT;

        Path excelPath = args.length >= 2
                ? Paths.get(args[1]).toAbsolutePath().normalize()
                : DEFAULT_EXCEL_PATH;

        Path outCsv = args.length >= 3
                ? Paths.get(args[2]).toAbsolutePath().normalize()
                : DEFAULT_OUT_CSV;

        if (!Files.isDirectory(programsRoot)) {
            throw new IllegalArgumentException("Programs root does not exist: " + programsRoot);
        }
        if (!Files.isRegularFile(excelPath)) {
            throw new IllegalArgumentException("Excel file does not exist: " + excelPath);
        }

        System.out.println("[STEP] Load Excel whitelist");
        ExcelIndex excelIndex = loadExcelIndex(excelPath);

        System.out.println("[EXCEL] totalRows                 = " + excelIndex.totalExcelRows);
        System.out.println("[EXCEL] acceptedRows              = " + excelIndex.acceptedRows);
        System.out.println("[EXCEL] duplicateRows             = " + excelIndex.duplicateRows);
        System.out.println("[EXCEL] uniqueMutants             = " + excelIndex.byFullKey.size());
        System.out.println("[EXCEL] projectCounts             = " + excelIndex.projectCounts);

        Map<String, MutantAggregate> aggregateMap = new LinkedHashMap<String, MutantAggregate>();
        for (ExcelEntry e : excelIndex.byFullKey.values()) {
            MutantAggregate agg = new MutantAggregate();
            fillFromExcel(agg, e);
            aggregateMap.put(e.fullKey, agg);
        }

        System.out.println("[STEP] Find EvoSuite kill statistic files");

        LinkedHashSet<Path> statisticFiles = new LinkedHashSet<Path>();

        int expectedChecked = 0;
        int expectedFound = 0;
        for (ExcelEntry e : excelIndex.byFullKey.values()) {
            expectedChecked++;
            if (!isBlank(e.expectedKillStatisticFile)) {
                Path p = Paths.get(e.expectedKillStatisticFile).toAbsolutePath().normalize();
                if (Files.isRegularFile(p)) {
                    statisticFiles.add(p);
                    expectedFound++;
                }
            }

            if (expectedChecked % 5000 == 0) {
                System.out.println("[SCAN-PROG] checkedExpectedFiles=" + expectedChecked
                        + ", found=" + expectedFound);
            }
        }

        List<Path> scannedFiles = new ArrayList<Path>();
        if (SCAN_RESULT_DIRS_FALLBACK) {
            scannedFiles = findKillStatisticFilesUnderResultDirs(programsRoot);
            statisticFiles.addAll(scannedFiles);
        }

        System.out.println("[SCAN] expectedChecked             = " + expectedChecked);
        System.out.println("[SCAN] expectedFound               = " + expectedFound);
        System.out.println("[SCAN] scanResultDirsFallback      = " + SCAN_RESULT_DIRS_FALLBACK);
        System.out.println("[SCAN] scannedResultKillFiles      = " + scannedFiles.size());
        System.out.println("[SCAN] totalStatisticFiles         = " + statisticFiles.size());

        System.out.println("[STEP] Parse kill statistic files");

        RunStats stats = new RunStats();
        for (Path statisticFile : statisticFiles) {
            try {
                collectOneKillStatistic(statisticFile, excelIndex, aggregateMap, stats);
                stats.statisticFileCount++;
            } catch (Throwable t) {
                stats.failedStatisticFileCount++;
                System.err.println("[WARN] failed to parse statistic file: " + statisticFile);
                t.printStackTrace(System.err);
            }
        }

        List<MutantAggregate> rows = new ArrayList<MutantAggregate>(aggregateMap.values());
        sortRows(rows);
        writeCsv(outCsv, rows);

        int finalKilled = 0;
        int finalLive = 0;
        int finalNotReported = 0;
        int mutantClassFound = 0;

        for (MutantAggregate r : rows) {
            String status = finalKillStatus(r);
            if ("KILLED".equals(status)) {
                finalKilled++;
            } else if ("LIVE".equals(status)) {
                finalLive++;
            } else {
                finalNotReported++;
            }

            if (r.mutantClassFoundEver) {
                mutantClassFound++;
            }
        }

        System.out.println("==================================================");
        System.out.println("[DONE]");
        System.out.println("programsRoot                    = " + programsRoot);
        System.out.println("excelPath                       = " + excelPath);
        System.out.println("outputCsv                       = " + outCsv);
        System.out.println("excelUniqueMutants              = " + excelIndex.byFullKey.size());
        System.out.println("outputRows                      = " + rows.size());
        System.out.println("statisticFileCount              = " + stats.statisticFileCount);
        System.out.println("failedStatisticFileCount        = " + stats.failedStatisticFileCount);
        System.out.println("matchedMutantEntries            = " + stats.matchedMutantEntries);
        System.out.println("skippedMutantsNotInExcel        = " + stats.skippedMutantsNotInExcel);
        System.out.println("ambiguousMatchedMutants         = " + stats.ambiguousMatchedMutants);
        System.out.println("finalKilled                     = " + finalKilled);
        System.out.println("finalLive                       = " + finalLive);
        System.out.println("finalNotReported                = " + finalNotReported);
        System.out.println("mutantClassFound                = " + mutantClassFound);
        System.out.println("evoSuiteScoreOnReportedRows     = " + String.format(Locale.ROOT, "%.5f",
                (finalKilled + finalLive) == 0 ? 0.0 : finalKilled * 100.0 / (finalKilled + finalLive)));
    }

    private static ExcelIndex loadExcelIndex(Path excelPath) {
        List<List<Object>> excelData = readExcelFile(excelPath.toString());

        ExcelIndex index = new ExcelIndex();
        if (excelData == null || excelData.size() <= 1) {
            return index;
        }

        index.totalExcelRows = excelData.size() - 1;

        for (int i = 1; i < excelData.size(); i++) {
            List<Object> row = excelData.get(i);
            if (row == null || row.size() <= COL_FILE_PATH) {
                continue;
            }

            String projectName = cell(row, COL_PROJECT);
            if (!isExecutedProject(projectName)) {
                continue;
            }

            String mutantName = cell(row, COL_OPERATOR);
            String methodSignature = cell(row, COL_METHOD);
            String classSimpleName = cell(row, COL_CLASS_F);
            String targetClassName = cell(row, COL_PACKAGE);
            String rawFilePath = cell(row, COL_FILE_PATH);

            if (isBlank(projectName)
                    || isBlank(mutantName)
                    || isBlank(methodSignature)
                    || isBlank(targetClassName)
                    || isBlank(rawFilePath)) {
                continue;
            }

            ExcelEntry e = new ExcelEntry();
            e.rowIndex = i;
            e.projectName = projectName;
            e.targetClassName = targetClassName;
            e.methodSignature = methodSignature;
            e.mutantName = mutantName;
            e.classSimpleName = classSimpleName;
            e.rawFilePath = rawFilePath;
            e.originalGraphPath = cell(row, COL_ORIGINAL_GRAPH_PATH);
            e.mutantGraphPath = cell(row, COL_MUTANT_GRAPH_PATH);
            e.excelIsKilled = row.size() > COL_IS_KILLED ? cell(row, COL_IS_KILLED) : "";

            fillExcelDerivedPaths(e);

            e.fullKey = fullKey(e.projectName, e.targetClassName, e.methodSignature, e.mutantName);
            e.reportKey = reportKey(e.projectName, e.targetClassName, e.mutantName);
            e.targetMutantKey = targetMutantKey(e.targetClassName, e.mutantName);

            ExcelEntry old = index.byFullKey.get(e.fullKey);
            if (old == null) {
                index.byFullKey.put(e.fullKey, e);
                index.acceptedRows++;

                index.projectCounts.put(e.projectName, index.projectCounts.getOrDefault(e.projectName, 0) + 1);

                addToMultiMap(index.byReportKey, e.reportKey, e.fullKey);
                addToMultiMap(index.byTargetMutantKey, e.targetMutantKey, e.fullKey);
                if (!isBlank(e.expectedKillStatisticFile)) {
                    addToMultiMap(index.byStatisticFile,
                            normalizePathString(e.expectedKillStatisticFile),
                            e.fullKey);
                }
            } else {
                old.duplicateRowIndexes.add(String.valueOf(i));
                index.duplicateRows++;
            }
        }

        return index;
    }

    private static void fillExcelDerivedPaths(ExcelEntry e) {
        String cleanedFilePath = cleanWindowsLongPath(e.rawFilePath);
        File file = new File(cleanedFilePath);
        String parent = file.getParent();
        if (parent == null) {
            return;
        }

        String parentPath = parent.replace("\\", "/");
        try {
            e.resultModuleHome = Paths.get(getCurr(parentPath)).normalize().toString();
        } catch (Throwable ignored) {
            return;
        }

        Path resultModulePath = Paths.get(e.resultModuleHome).normalize();
        Path outerRoot;
        String resultModuleString = resultModulePath.toString().replace('\\', '/');

        if (resultModuleString.matches(".*(?:commons-math|commons-numbers).*")) {
            outerRoot = resultModulePath.getParent();
        } else {
            outerRoot = resultModulePath.getFileName();
        }

        if (outerRoot != null && outerRoot.getFileName() != null) {
            e.sourceRootHome = resolveSourceRootHome(e.resultModuleHome, outerRoot.getFileName().toString());
            e.sourceModuleHome = resolveSourceModuleHome(e.resultModuleHome, e.sourceRootHome);
        }

        Path traditionalMutantsDir = Paths.get(
                e.resultModuleHome,
                "result",
                e.targetClassName,
                TRADITIONAL_MUTANTS_DIR
        ).toAbsolutePath().normalize();

        e.expectedKillStatisticFile = traditionalMutantsDir.resolve(KILL_STATISTIC_FILE).toString();

        Path mutantClassDir = traditionalMutantsDir
                .resolve(e.methodSignature)
                .resolve(e.mutantName)
                .toAbsolutePath()
                .normalize();

        e.mutantClassDir = mutantClassDir.toString();
        e.mutantClassFound = hasClassFile(mutantClassDir);
    }

    private static void fillFromExcel(MutantAggregate agg, ExcelEntry e) {
        agg.projectName = e.projectName;
        agg.targetClassName = e.targetClassName;
        agg.methodSignature = e.methodSignature;
        agg.mutantName = e.mutantName;
        agg.mutationOperator = extractOperator(e.mutantName);
        agg.mutantKey = e.fullKey;
        agg.reportMatchKey = e.reportKey;

        agg.sourceRootHome = nonNull(e.sourceRootHome);
        agg.sourceModuleHome = nonNull(e.sourceModuleHome);
        agg.resultModuleHome = nonNull(e.resultModuleHome);

        agg.expectedKillStatisticFiles.add(nonNull(e.expectedKillStatisticFile));

        agg.rawFilePaths.add(nonNull(e.rawFilePath));
        agg.originalGraphPaths.add(nonNull(e.originalGraphPath));
        agg.mutantGraphPaths.add(nonNull(e.mutantGraphPath));
        agg.excelIsKilledValues.add(nonNull(e.excelIsKilled));

        agg.excelRowIndexes.add(String.valueOf(e.rowIndex));
        agg.excelRowIndexes.addAll(e.duplicateRowIndexes);

        agg.mutantClassDirs.add(nonNull(e.mutantClassDir));
        if (e.mutantClassFound) {
            agg.mutantClassFoundEver = true;
        }
    }

    private static void collectOneKillStatistic(Path statisticFile,
                                                ExcelIndex excelIndex,
                                                Map<String, MutantAggregate> aggregateMap,
                                                RunStats stats) throws Exception {
        KillStatistic stat = parseKillStatistic(statisticFile);
        ReportLocation loc = inferReportLocation(statisticFile);

        String projectName = firstNonBlank(loc.projectName, moduleNameFromResultModuleHome(loc.resultModuleHome));
        String targetClassName = loc.targetClassName;

        if (isBlank(projectName) || isBlank(targetClassName) || !isExecutedProject(projectName)) {
            return;
        }

        LinkedHashSet<String> allReportMutants = new LinkedHashSet<String>();
        allReportMutants.addAll(stat.mutantResults.keySet());
        allReportMutants.addAll(stat.killedMutants);
        allReportMutants.addAll(stat.liveMutants);

        for (List<String> mutants : stat.testResults.values()) {
            if (mutants != null) {
                allReportMutants.addAll(mutants);
            }
        }

        for (String mutantName : allReportMutants) {
            if (isBlank(mutantName)) {
                continue;
            }

            List<String> fullKeys = findMatchedExcelKeys(
                    excelIndex,
                    statisticFile,
                    projectName,
                    targetClassName,
                    mutantName
            );

            if (fullKeys.isEmpty()) {
                stats.skippedMutantsNotInExcel++;
                continue;
            }

            if (fullKeys.size() > 1) {
                stats.ambiguousMatchedMutants++;
            }

            for (String fullKey : fullKeys) {
                MutantAggregate agg = aggregateMap.get(fullKey);
                if (agg == null) {
                    continue;
                }

                stats.matchedMutantEntries++;

                applyReportBasicInfo(agg, statisticFile, loc);
                applyKillInfo(agg, stat, mutantName);
                applyMutantClassInfo(agg, loc.resultModuleHome, targetClassName, agg.methodSignature, mutantName);

                if (fullKeys.size() > 1) {
                    agg.ambiguousMatch = true;
                    agg.matchNotes.add("ambiguous mutantName under same targetClassName: " + mutantName);
                }
            }
        }
    }

    private static List<String> findMatchedExcelKeys(ExcelIndex index,
                                                     Path statisticFile,
                                                     String projectName,
                                                     String targetClassName,
                                                     String mutantName) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();

        List<String> byReportKey = index.byReportKey.get(reportKey(projectName, targetClassName, mutantName));
        if (byReportKey != null) {
            result.addAll(byReportKey);
        }

        /*
         * 如果 projectName 推断与 Excel 不一致，则用当前统计文件路径对应的 expectedKillStatisticFile 进一步兜底。
         */
        if (result.isEmpty() && statisticFile != null) {
            List<String> byFile = index.byStatisticFile.get(normalizePathString(statisticFile.toString()));
            if (byFile != null) {
                for (String key : byFile) {
                    if (key != null && key.endsWith("##" + mutantName)) {
                        result.add(key);
                    }
                }
            }
        }

        /*
         * 最后兜底：targetClassName + mutantName。
         * 注意：如果同一目标类中同名 mutant 出现在多个 methodSignature 下，会标记 ambiguousMatch。
         */
        if (result.isEmpty()) {
            List<String> byTargetMutant = index.byTargetMutantKey.get(targetMutantKey(targetClassName, mutantName));
            if (byTargetMutant != null) {
                result.addAll(byTargetMutant);
            }
        }

        return new ArrayList<String>(result);
    }

    private static void applyReportBasicInfo(MutantAggregate agg, Path statisticFile, ReportLocation loc) {
        agg.reportedEver = true;
        agg.reportCount++;
        agg.killStatisticFiles.add(nonNull(statisticFile));
        agg.reportProjectNames.add(nonNull(loc.projectName));
        agg.reportTargetClassNames.add(nonNull(loc.targetClassName));

        if (!isBlank(loc.resultModuleHome)) {
            agg.resultModuleHome = firstNonBlank(agg.resultModuleHome, loc.resultModuleHome);
        }
    }

    private static void applyKillInfo(MutantAggregate agg, KillStatistic stat, String mutantName) {
        agg.evoSuiteMutantScores.add(String.format(Locale.ROOT, "%.5f", stat.mutantScore));
        agg.evoSuiteSuiteTotalMutants.add(String.valueOf(stat.totalMutants()));
        agg.evoSuiteSuiteKilledCounts.add(String.valueOf(stat.killedMutants.size()));
        agg.evoSuiteSuiteLiveCounts.add(String.valueOf(stat.liveMutants.size()));

        boolean killed = stat.killedMutants.contains(mutantName);
        boolean live = stat.liveMutants.contains(mutantName);

        List<String> testsFromMutantResults = stat.mutantResults.get(mutantName);
        if (testsFromMutantResults != null) {
            for (String testName : testsFromMutantResults) {
                if (!isBlank(testName)) {
                    agg.killingTests.add(testName.trim());
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : stat.testResults.entrySet()) {
            String testName = entry.getKey();
            List<String> mutantsKilledByTest = entry.getValue();
            if (!isBlank(testName)
                    && mutantsKilledByTest != null
                    && mutantsKilledByTest.contains(mutantName)) {
                agg.killingTests.add(testName.trim());
                agg.reverseCheckedTests.add(testName.trim());
            }
        }

        if (killed) {
            agg.evoSuiteKilledEver = true;
            agg.evoSuiteKilledRunCount++;
        }

        if (live) {
            agg.evoSuiteLiveEver = true;
            agg.evoSuiteLiveRunCount++;
        }

        if (!killed && !live && !agg.killingTests.isEmpty()) {
            agg.evoSuiteKilledEver = true;
            agg.evoSuiteKilledRunCount++;
            agg.matchNotes.add("inferred KILLED because killingTests is not empty, but mutant is not in killed_mutants list");
        }

        if (containsIgnoreCase(joinSet(agg.killingTests), "timeout")) {
            agg.timeoutRelatedEver = true;
        }
    }

    private static void applyMutantClassInfo(MutantAggregate agg,
                                             String resultModuleHome,
                                             String targetClassName,
                                             String methodSignature,
                                             String mutantName) {
        String rh = firstNonBlank(resultModuleHome, agg.resultModuleHome);
        if (isBlank(rh) || isBlank(targetClassName) || isBlank(methodSignature) || isBlank(mutantName)) {
            return;
        }

        Path mutantDir = Paths.get(
                rh,
                "result",
                targetClassName,
                TRADITIONAL_MUTANTS_DIR,
                methodSignature,
                mutantName
        ).toAbsolutePath().normalize();

        agg.mutantClassDirs.add(mutantDir.toString());

        if (hasClassFile(mutantDir)) {
            agg.mutantClassFoundEver = true;
        }
    }

    private static KillStatistic parseKillStatistic(Path txtFile) throws IOException {
        KillStatistic stat = new KillStatistic();
        stat.sourceFile = txtFile.toAbsolutePath().normalize().toString();

        String content = readAll(txtFile);
        Map<String, String> sections = splitSections(content);

        stat.testResults = parseTestResultsMap(stripTrailingSemicolon(sections.get("test_results")));
        stat.mutantResults = parseMutantResultsMap(stripTrailingSemicolon(sections.get("mutant_results")));
        stat.killedMutants.addAll(parseList(stripTrailingSemicolon(sections.get("killed_mutants"))));
        stat.liveMutants.addAll(parseList(stripTrailingSemicolon(sections.get("live_mutants"))));

        String scoreText = stripTrailingSemicolon(sections.get("mutant_score"));
        if (!isBlank(scoreText)) {
            try {
                stat.mutantScore = Double.parseDouble(scoreText.trim());
            } catch (NumberFormatException ignored) {
                stat.mutantScore = 0.0;
            }
        }

        if (stat.killedMutants.isEmpty() && stat.liveMutants.isEmpty() && !stat.mutantResults.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : stat.mutantResults.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    stat.liveMutants.add(entry.getKey());
                } else {
                    stat.killedMutants.add(entry.getKey());
                }
            }
        }

        return stat;
    }

    private static ReportLocation inferReportLocation(Path statisticFile) {
        ReportLocation loc = new ReportLocation();
        if (statisticFile == null) {
            return loc;
        }

        Path abs = statisticFile.toAbsolutePath().normalize();

        /*
         * 目标路径：
         *   resultModuleHome/result/<targetClassName>/traditional_mutants/kill_statistic_evoSuite.txt
         */
        Path parent = abs.getParent(); // traditional_mutants
        if (parent == null || parent.getFileName() == null
                || !TRADITIONAL_MUTANTS_DIR.equals(parent.getFileName().toString())) {
            return loc;
        }

        Path targetDir = parent.getParent(); // targetClassName
        if (targetDir == null || targetDir.getFileName() == null) {
            return loc;
        }
        loc.targetClassName = targetDir.getFileName().toString();

        Path resultDir = targetDir.getParent(); // result
        if (resultDir == null || resultDir.getFileName() == null
                || !"result".equals(resultDir.getFileName().toString())) {
            return loc;
        }

        Path resultModuleHome = resultDir.getParent();
        if (resultModuleHome != null) {
            loc.resultModuleHome = resultModuleHome.toAbsolutePath().normalize().toString();
            loc.projectName = moduleNameFromResultModuleHome(loc.resultModuleHome);
        }

        return loc;
    }

    /**
     * 只扫描 result 目录下的 kill_statistic_evoSuite.txt。
     *
     * 只访问：
     *   result/<targetClassName>/traditional_mutants/kill_statistic_evoSuite.txt
     *
     * 不进入：
     *   result/<targetClassName>/traditional_mutants/<methodSignature>/<mutantName>
     */
    private static List<Path> findKillStatisticFilesUnderResultDirs(Path root) throws IOException {
        final List<Path> result = new ArrayList<Path>();
        if (root == null || !Files.isDirectory(root)) {
            return result;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir == null || dir.getFileName() == null) {
                    return FileVisitResult.CONTINUE;
                }

                String name = dir.getFileName().toString();

                if (shouldSkipDirName(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if ("result".equals(name)) {
                    collectKillStatisticFilesInOneResultDir(dir, result);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(result);
        return result;
    }

    private static boolean shouldSkipDirName(String name) {
        if (name == null) {
            return false;
        }

        return ".git".equals(name)
                || ".idea".equals(name)
                || ".mvn".equals(name)
                || "src".equals(name)
                || "target".equals(name)
                || "llm".equals(name)
                || "mujava_logs".equals(name)
                || "worker_runs".equals(name)
                || "checkpoints".equals(name);
    }

    private static void collectKillStatisticFilesInOneResultDir(Path resultDir, List<Path> out) throws IOException {
        if (resultDir == null || out == null || !Files.isDirectory(resultDir)) {
            return;
        }

        try (DirectoryStream<Path> targetDirs = Files.newDirectoryStream(resultDir)) {
            for (Path targetDir : targetDirs) {
                if (targetDir == null || !Files.isDirectory(targetDir)) {
                    continue;
                }

                Path statisticFile = targetDir
                        .resolve(TRADITIONAL_MUTANTS_DIR)
                        .resolve(KILL_STATISTIC_FILE)
                        .toAbsolutePath()
                        .normalize();

                if (Files.isRegularFile(statisticFile)) {
                    out.add(statisticFile);
                }
            }
        }
    }

    private static void writeCsv(Path outCsv, List<MutantAggregate> rows) throws IOException {
        if (outCsv.getParent() != null) {
            Files.createDirectories(outCsv.getParent());
        }

        try (BufferedWriter bw = Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8)) {
            List<String> header = Arrays.asList(
                    "projectName",
                    "targetClassName",
                    "methodSignature",
                    "mutantName",
                    "mutationOperator",
                    "mutantKey",
                    "reportMatchKey",

                    "evoSuiteKillStatusFinal",
                    "evoSuiteKilledRunCount",
                    "evoSuiteLiveRunCount",
                    "killingTestCount",
                    "killingTests",
                    "reverseCheckedTests",
                    "timeoutRelatedEver",

                    "reportedEver",
                    "reportCount",
                    "killStatisticFiles",
                    "reportProjectNames",
                    "reportTargetClassNames",
                    "ambiguousMatch",
                    "matchNotes",

                    "evoSuiteMutantScores",
                    "evoSuiteSuiteTotalMutants",
                    "evoSuiteSuiteKilledCounts",
                    "evoSuiteSuiteLiveCounts",

                    "mutantClassFoundFinal",
                    "mutantClassDirs",

                    "sourceRootHome",
                    "sourceModuleHome",
                    "resultModuleHome",
                    "expectedKillStatisticFiles",

                    "rawFilePaths",
                    "originalGraphPaths",
                    "mutantGraphPaths",
                    "excelIsKilledValues",
                    "excelRowIndexes"
            );

            writeCsvLine(bw, header);

            for (MutantAggregate r : rows) {
                List<String> line = Arrays.asList(
                        r.projectName,
                        r.targetClassName,
                        r.methodSignature,
                        r.mutantName,
                        r.mutationOperator,
                        r.mutantKey,
                        r.reportMatchKey,

                        finalKillStatus(r),
                        String.valueOf(r.evoSuiteKilledRunCount),
                        String.valueOf(r.evoSuiteLiveRunCount),
                        String.valueOf(r.killingTests.size()),
                        joinSet(r.killingTests),
                        joinSet(r.reverseCheckedTests),
                        String.valueOf(r.timeoutRelatedEver),

                        String.valueOf(r.reportedEver),
                        String.valueOf(r.reportCount),
                        joinSet(r.killStatisticFiles),
                        joinSet(r.reportProjectNames),
                        joinSet(r.reportTargetClassNames),
                        String.valueOf(r.ambiguousMatch),
                        joinSet(r.matchNotes),

                        joinSet(r.evoSuiteMutantScores),
                        joinSet(r.evoSuiteSuiteTotalMutants),
                        joinSet(r.evoSuiteSuiteKilledCounts),
                        joinSet(r.evoSuiteSuiteLiveCounts),

                        String.valueOf(r.mutantClassFoundEver),
                        joinSet(r.mutantClassDirs),

                        r.sourceRootHome,
                        r.sourceModuleHome,
                        r.resultModuleHome,
                        joinSet(r.expectedKillStatisticFiles),

                        joinSet(r.rawFilePaths),
                        joinSet(r.originalGraphPaths),
                        joinSet(r.mutantGraphPaths),
                        joinSet(r.excelIsKilledValues),
                        joinSet(r.excelRowIndexes)
                );

                writeCsvLine(bw, line);
            }
        }
    }

    private static String finalKillStatus(MutantAggregate r) {
        if (r.evoSuiteKilledEver) {
            return "KILLED";
        }
        if (r.evoSuiteLiveEver) {
            return "LIVE";
        }
        return "NOT_REPORTED";
    }

    private static Map<String, String> splitSections(String content) {
        LinkedHashMap<String, String> sections = new LinkedHashMap<String, String>();
        if (content == null) {
            return sections;
        }

        Matcher matcher = SECTION_PATTERN.matcher(content);
        while (matcher.find()) {
            sections.put(matcher.group(1).trim(), matcher.group(2).trim());
        }

        return sections;
    }

    private static Map<String, List<String>> parseMutantResultsMap(String mapText) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        String body = unwrap(mapText, '{', '}');
        if (body.isEmpty()) {
            return result;
        }

        List<EntrySpan> spans = findKeySpans(body, MUTANT_KEY_PATTERN);
        for (int i = 0; i < spans.size(); i++) {
            EntrySpan cur = spans.get(i);
            int valueStart = cur.valueStart;
            int valueEnd = (i + 1 < spans.size()) ? spans.get(i + 1).entryStart : body.length();

            String value = body.substring(valueStart, valueEnd);
            value = trimLeadingCommaAndSpace(value);
            value = trimTrailingCommaAndSpace(value);

            result.put(cur.key, parseCommaSeparatedValues(value));
        }

        return result;
    }

    private static Map<String, List<String>> parseTestResultsMap(String mapText) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        String body = unwrap(mapText, '{', '}');
        if (body.isEmpty()) {
            return result;
        }

        Pattern testKeyPattern = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)");
        List<EntrySpan> spans = findKeySpans(body, testKeyPattern);

        for (int i = 0; i < spans.size(); i++) {
            EntrySpan cur = spans.get(i);
            int valueStart = cur.valueStart;
            int valueEnd = (i + 1 < spans.size()) ? spans.get(i + 1).entryStart : body.length();

            String value = body.substring(valueStart, valueEnd);
            value = trimLeadingCommaAndSpace(value);
            value = trimTrailingCommaAndSpace(value);

            result.put(cur.key, parseCommaSeparatedValues(value));
        }

        return result;
    }

    private static List<EntrySpan> findKeySpans(String body, Pattern keyPattern) {
        ArrayList<EntrySpan> spans = new ArrayList<EntrySpan>();
        if (body == null || body.isEmpty()) {
            return spans;
        }

        Matcher matcher = keyPattern.matcher(body);
        while (matcher.find()) {
            int keyStart = matcher.start();
            int keyEnd = matcher.end();

            int p = keyEnd;
            while (p < body.length() && Character.isWhitespace(body.charAt(p))) {
                p++;
            }

            if (p >= body.length() || body.charAt(p) != '=') {
                continue;
            }

            if (keyStart > 0) {
                char previous = body.charAt(keyStart - 1);
                if (previous != ' ' && previous != ',') {
                    continue;
                }
            }

            int entryStart = keyStart;
            while (entryStart > 0 && Character.isWhitespace(body.charAt(entryStart - 1))) {
                entryStart--;
            }
            if (entryStart > 0 && body.charAt(entryStart - 1) == ',') {
                entryStart--;
            }

            EntrySpan span = new EntrySpan();
            span.key = matcher.group();
            span.entryStart = entryStart;
            span.valueStart = p + 1;
            spans.add(span);
        }

        return spans;
    }

    private static List<String> parseList(String text) {
        return parseCommaSeparatedValues(unwrap(text, '[', ']'));
    }

    private static List<String> parseCommaSeparatedValues(String text) {
        ArrayList<String> result = new ArrayList<String>();
        if (text == null) {
            return result;
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return result;
        }

        String[] arr = trimmed.split("\\s*,\\s*");
        LinkedHashSet<String> unique = new LinkedHashSet<String>();

        for (String item : arr) {
            if (!isBlank(item)) {
                unique.add(item.trim());
            }
        }

        result.addAll(unique);
        return result;
    }

    private static String unwrap(String text, char left, char right) {
        if (text == null) {
            return "";
        }

        String s = stripTrailingSemicolon(text).trim();
        if (s.length() >= 2 && s.charAt(0) == left && s.charAt(s.length() - 1) == right) {
            return s.substring(1, s.length() - 1).trim();
        }

        return s;
    }

    private static String stripTrailingSemicolon(String text) {
        if (text == null) {
            return "";
        }

        String s = text.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        return s;
    }

    private static String trimLeadingCommaAndSpace(String text) {
        if (text == null) {
            return "";
        }

        String s = text.trim();
        while (s.startsWith(",")) {
            s = s.substring(1).trim();
        }

        return s;
    }

    private static String trimTrailingCommaAndSpace(String text) {
        if (text == null) {
            return "";
        }

        String s = text.trim();
        while (s.endsWith(",")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        return s;
    }

    private static String readAll(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();

        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            reader.close();
        }

        return sb.toString();
    }

    private static void sortRows(List<MutantAggregate> rows) {
        Collections.sort(rows, new Comparator<MutantAggregate>() {
            @Override
            public int compare(MutantAggregate a, MutantAggregate b) {
                int c;
                c = safe(a.projectName).compareTo(safe(b.projectName));
                if (c != 0) {
                    return c;
                }

                c = safe(a.targetClassName).compareTo(safe(b.targetClassName));
                if (c != 0) {
                    return c;
                }

                c = safe(a.methodSignature).compareTo(safe(b.methodSignature));
                if (c != 0) {
                    return c;
                }

                return compareMutantName(a.mutantName, b.mutantName);
            }
        });
    }

    private static int compareMutantName(String a, String b) {
        String opA = extractOperator(a);
        String opB = extractOperator(b);

        int c = opA.compareTo(opB);
        if (c != 0) {
            return c;
        }

        int nA = mutantNumber(a);
        int nB = mutantNumber(b);
        if (nA != nB) {
            return Integer.compare(nA, nB);
        }

        return safe(a).compareTo(safe(b));
    }

    private static int mutantNumber(String mutantName) {
        if (mutantName == null) {
            return Integer.MAX_VALUE;
        }

        int idx = mutantName.lastIndexOf('_');
        if (idx < 0 || idx + 1 >= mutantName.length()) {
            return Integer.MAX_VALUE;
        }

        try {
            return Integer.parseInt(mutantName.substring(idx + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void writeCsvLine(BufferedWriter bw, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                bw.write(',');
            }
            bw.write(csv(cells.get(i)));
        }
        bw.newLine();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }

        boolean needQuote = s.indexOf(',') >= 0
                || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0
                || s.indexOf(';') >= 0;

        String value = s.replace("\"", "\"\"");
        return needQuote ? "\"" + value + "\"" : value;
    }

    private static void addToMultiMap(Map<String, List<String>> map, String key, String value) {
        if (isBlank(key) || isBlank(value)) {
            return;
        }

        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            map.put(key, list);
        }

        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static boolean hasClassFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }

        try {
            Iterator<Path> iterator = Files.walk(dir).iterator();
            while (iterator.hasNext()) {
                Path p = iterator.next();
                if (Files.isRegularFile(p)
                        && p.getFileName() != null
                        && p.getFileName().toString().endsWith(".class")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }

        return false;
    }

    private static String getCurr(String workDir) {
        Pattern p = Pattern.compile("(?i)^(.*?)(?=[\\\\/]+result(?:[\\\\/]+|$))");
        Matcher m = p.matcher(workDir);

        if (m.find()) {
            return m.group(1);
        }

        throw new IllegalArgumentException("未匹配到 result: " + workDir);
    }

    private static String resolveSourceRootHome(String resultModuleHome, String outerRootName) {
        Path resultPath = Paths.get(resultModuleHome).toAbsolutePath().normalize();

        Path p = resultPath;
        while (p != null && p.getFileName() != null) {
            if (outerRootName.equals(p.getFileName().toString())) {
                return p.toString();
            }
            p = p.getParent();
        }

        Path parent = resultPath.getParent();
        return parent == null ? resultPath.toString() : parent.toString();
    }

    private static String resolveSourceModuleHome(String resultModuleHome, String sourceRootHome) {
        Path resultPath = Paths.get(resultModuleHome).toAbsolutePath().normalize();

        String path = resultPath.toString().replace('\\', '/');
        if (path.matches(".*(?:commons-math|commons-numbers).*")) {
            return resultPath.toString();
        }

        return isBlank(sourceRootHome) ? resultPath.toString() : sourceRootHome;
    }

    private static String moduleNameFromResultModuleHome(String resultModuleHome) {
        if (isBlank(resultModuleHome)) {
            return "";
        }

        try {
            Path p = Paths.get(resultModuleHome).toAbsolutePath().normalize();
            return p.getFileName() == null ? "" : p.getFileName().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String cleanWindowsLongPath(String path) {
        if (path == null) {
            return "";
        }

        String s = path.trim().replace('\\', '/');
        if (s.startsWith("//?/")) {
            s = s.substring("//?/".length());
        }

        return s;
    }

    private static String normalizePathString(String path) {
        if (path == null) {
            return "";
        }

        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Throwable ignored) {
            return path.replace('\\', '/');
        }
    }

    private static boolean isExecutedProject(String projectName) {
        if (isBlank(projectName)) {
            return false;
        }
        return EXECUTED_PROJECTS.contains(projectName.trim());
    }

    private static String fullKey(String projectName,
                                  String targetClassName,
                                  String methodSignature,
                                  String mutantName) {
        return safe(projectName)
                + "##" + safe(targetClassName)
                + "##" + safe(methodSignature)
                + "##" + safe(mutantName);
    }

    private static String reportKey(String projectName,
                                    String targetClassName,
                                    String mutantName) {
        return safe(projectName)
                + "##" + safe(targetClassName)
                + "##" + safe(mutantName);
    }

    private static String targetMutantKey(String targetClassName, String mutantName) {
        return safe(targetClassName)
                + "##" + safe(mutantName);
    }

    private static String extractOperator(String mutantName) {
        if (mutantName == null) {
            return "";
        }

        int idx = mutantName.indexOf('_');
        if (idx > 0) {
            return mutantName.substring(0, idx);
        }

        return mutantName;
    }

    private static String cell(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }

        return String.valueOf(row.get(index)).trim();
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? safe(b) : safe(a);
    }

    private static String nonNull(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean containsIgnoreCase(String text, String key) {
        if (text == null || key == null) {
            return false;
        }

        return text.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT));
    }

    private static String joinSet(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (isBlank(s)) {
                continue;
            }

            if (sb.length() > 0) {
                sb.append("; ");
            }

            sb.append(s.trim());
        }

        return sb.toString();
    }

    private static final class ExcelIndex {
        int totalExcelRows;
        int acceptedRows;
        int duplicateRows;

        final LinkedHashMap<String, ExcelEntry> byFullKey = new LinkedHashMap<String, ExcelEntry>();
        final LinkedHashMap<String, List<String>> byReportKey = new LinkedHashMap<String, List<String>>();
        final LinkedHashMap<String, List<String>> byTargetMutantKey = new LinkedHashMap<String, List<String>>();
        final LinkedHashMap<String, List<String>> byStatisticFile = new LinkedHashMap<String, List<String>>();
        final LinkedHashMap<String, Integer> projectCounts = new LinkedHashMap<String, Integer>();
    }

    private static final class ExcelEntry {
        int rowIndex;

        String fullKey = "";
        String reportKey = "";
        String targetMutantKey = "";

        String projectName = "";
        String targetClassName = "";
        String methodSignature = "";
        String mutantName = "";
        String classSimpleName = "";

        String rawFilePath = "";
        String originalGraphPath = "";
        String mutantGraphPath = "";
        String excelIsKilled = "";

        String resultModuleHome = "";
        String sourceRootHome = "";
        String sourceModuleHome = "";

        String expectedKillStatisticFile = "";
        String mutantClassDir = "";
        boolean mutantClassFound = false;

        final LinkedHashSet<String> duplicateRowIndexes = new LinkedHashSet<String>();
    }

    private static final class ReportLocation {
        String projectName = "";
        String targetClassName = "";
        String resultModuleHome = "";
    }

    private static final class RunStats {
        int statisticFileCount;
        int failedStatisticFileCount;
        int matchedMutantEntries;
        int skippedMutantsNotInExcel;
        int ambiguousMatchedMutants;
    }

    private static final class KillStatistic {
        String sourceFile = "";
        Map<String, List<String>> testResults = new LinkedHashMap<String, List<String>>();
        Map<String, List<String>> mutantResults = new LinkedHashMap<String, List<String>>();
        Set<String> killedMutants = new LinkedHashSet<String>();
        Set<String> liveMutants = new LinkedHashSet<String>();
        double mutantScore = 0.0;

        int totalMutants() {
            LinkedHashSet<String> all = new LinkedHashSet<String>();
            all.addAll(mutantResults.keySet());
            all.addAll(killedMutants);
            all.addAll(liveMutants);
            return all.size();
        }
    }

    private static final class MutantAggregate {
        String projectName = "";
        String targetClassName = "";
        String methodSignature = "";
        String mutantName = "";
        String mutationOperator = "";
        String mutantKey = "";
        String reportMatchKey = "";

        String sourceRootHome = "";
        String sourceModuleHome = "";
        String resultModuleHome = "";

        boolean evoSuiteKilledEver = false;
        boolean evoSuiteLiveEver = false;
        int evoSuiteKilledRunCount = 0;
        int evoSuiteLiveRunCount = 0;

        final Set<String> killingTests = new LinkedHashSet<String>();
        final Set<String> reverseCheckedTests = new LinkedHashSet<String>();
        boolean timeoutRelatedEver = false;

        boolean reportedEver = false;
        int reportCount = 0;
        final Set<String> killStatisticFiles = new LinkedHashSet<String>();
        final Set<String> reportProjectNames = new LinkedHashSet<String>();
        final Set<String> reportTargetClassNames = new LinkedHashSet<String>();
        boolean ambiguousMatch = false;
        final Set<String> matchNotes = new LinkedHashSet<String>();

        final Set<String> evoSuiteMutantScores = new LinkedHashSet<String>();
        final Set<String> evoSuiteSuiteTotalMutants = new LinkedHashSet<String>();
        final Set<String> evoSuiteSuiteKilledCounts = new LinkedHashSet<String>();
        final Set<String> evoSuiteSuiteLiveCounts = new LinkedHashSet<String>();

        boolean mutantClassFoundEver = false;
        final Set<String> mutantClassDirs = new LinkedHashSet<String>();

        final Set<String> expectedKillStatisticFiles = new LinkedHashSet<String>();
        final Set<String> rawFilePaths = new LinkedHashSet<String>();
        final Set<String> originalGraphPaths = new LinkedHashSet<String>();
        final Set<String> mutantGraphPaths = new LinkedHashSet<String>();
        final Set<String> excelIsKilledValues = new LinkedHashSet<String>();
        final Set<String> excelRowIndexes = new LinkedHashSet<String>();
    }

    private static final class EntrySpan {
        String key;
        int entryStart;
        int valueStart;
    }
}
