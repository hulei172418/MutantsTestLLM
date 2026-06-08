package mujava.testgenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.cmd.TestRunner9_MultiProcess_batched.readExcelFile;

/**
 * 以 mutant_statistic_total_graph_llm.xlsx 为白名单，
 * 扫描 E:/PHD/testJava/Programs 下所有工程 llm/execution-report 的执行结果，
 * 汇总每个 Excel 变异体对应 LLM 测试的编译情况、映射情况、杀死情况。
 *
 * 统计口径：
 * 1. 只统计 Excel 中存在且属于已执行项目的变异体；
 * 2. commons-math-legacy 不统计；
 * 3. 同一个 projectName + targetClassName + methodSignature + mutantName 视为同一变异体；
 * 4. 多个 execution-report 批次中，只要有一次 compiled=true，则最终 testCompiledFinal=true；
 * 5. 多个 execution-report 批次中，只要有一次 KILLED，则最终 suiteKillStatusFinal=KILLED；
 * 6. 多个 execution-report 批次中，只要有一次 mappingStatus=OK，则最终 mappingStatusFinal=OK；
 * 7. suite 里出现但 Excel 中不存在的变异体不新增，只记录 skippedSuiteMutantsNotInExcel 计数。
 */
public class LlmMutantKillDetailCollector {

    private static final Path PROGRAMS_ROOT =
            Paths.get("E:/PHD/testJava/Programs").toAbsolutePath().normalize();

    private static final Path EXCEL_PATH =
            Paths.get("E:/PHD/testJava/MutantParse/data/mutant_statistic_total_graph_llm.xlsx")
                    .toAbsolutePath()
                    .normalize();

    private static final Path OUT_CSV =
            Paths.get("E:/PHD/testJava/MutantParse/data/llm_mutant_kill_detail_merged.csv")
                    .toAbsolutePath()
                    .normalize();

    private static final String LLM_DIR = "llm";
    private static final String EXECUTION_REPORT_DIR = "execution-report";

    private static final String SUITE_JSON = "llm_suite_kill_results.json";
    private static final String TARGET_JSON = "llm_target_kill_results.json";
    private static final String TARGET_CSV = "llm_target_kill_results.csv";

    private static final int COL_OPERATOR = 0;
    private static final int COL_METHOD = 2;
    private static final int COL_CLASS_F = 4;
    private static final int COL_PACKAGE = 6;
    private static final int COL_PROJECT = 7;
    private static final int COL_FILE_PATH = 8;
    private static final int COL_ORIGINAL_GRAPH_PATH = 9;
    private static final int COL_MUTANT_GRAPH_PATH = 10;
    private static final int COL_IS_KILLED = 11;

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
                : PROGRAMS_ROOT;

        Path excelPath = args.length >= 2
                ? Paths.get(args[1]).toAbsolutePath().normalize()
                : EXCEL_PATH;

        Path outCsv = args.length >= 3
                ? Paths.get(args[2]).toAbsolutePath().normalize()
                : OUT_CSV;

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
        System.out.println("[EXCEL] uniqueMutants             = " + excelIndex.byKey.size());
        System.out.println("[EXCEL] projectCounts             = " + excelIndex.projectCounts);

        Map<String, MutantAggregate> aggregateMap = new LinkedHashMap<String, MutantAggregate>();
        for (ExcelEntry e : excelIndex.byKey.values()) {
            MutantAggregate agg = new MutantAggregate();
            fillFromExcel(agg, e);
            aggregateMap.put(e.key, agg);
        }

        System.out.println("[STEP] Scan execution reports");
        List<ProjectReportRoot> reportRoots = findProjectExecutionReportDirs(programsRoot);
        System.out.println("[SCAN] executionReportDirs        = " + reportRoots.size());

        RunStats stats = new RunStats();

        for (ProjectReportRoot pr : reportRoots) {
            if (!isExecutedProject(pr.projectName)) {
                System.out.println("[SKIP-PROJECT] not in executed list: " + pr.projectName);
                continue;
            }

            List<Path> suiteFiles = findFiles(pr.executionReportDir, SUITE_JSON);
            System.out.println("[PROJECT] " + pr.projectName
                    + " executionReportDir=" + pr.executionReportDir
                    + " suiteReports=" + suiteFiles.size());

            for (Path suiteFile : suiteFiles) {
                try {
                    collectOneSuiteReport(pr, suiteFile, aggregateMap, stats);
                    stats.suiteReportCount++;
                } catch (Throwable t) {
                    stats.failedReportCount++;
                    System.err.println("[WARN] failed to parse suite report: " + suiteFile);
                    t.printStackTrace(System.err);
                }
            }
        }

        List<MutantAggregate> rows = new ArrayList<MutantAggregate>(aggregateMap.values());
        sortRows(rows);
        writeCsv(outCsv, rows);

        System.out.println("==================================================");
        System.out.println("[DONE]");
        System.out.println("programsRoot                    = " + programsRoot);
        System.out.println("excelPath                       = " + excelPath);
        System.out.println("outputCsv                       = " + outCsv);
        System.out.println("excelUniqueMutants              = " + excelIndex.byKey.size());
        System.out.println("outputRows                      = " + rows.size());
        System.out.println("suiteReportCount                = " + stats.suiteReportCount);
        System.out.println("failedReportCount               = " + stats.failedReportCount);
        System.out.println("matchedTargetEntries            = " + stats.matchedTargetEntries);
        System.out.println("skippedTargetEntriesNotInExcel  = " + stats.skippedTargetEntriesNotInExcel);
        System.out.println("matchedSuiteMutants             = " + stats.matchedSuiteMutants);
        System.out.println("skippedSuiteMutantsNotInExcel   = " + stats.skippedSuiteMutantsNotInExcel);
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
            e.mutantName = mutantName;
            e.methodSignature = methodSignature;
            e.classSimpleName = classSimpleName;
            e.targetClassName = targetClassName;
            e.rawFilePath = rawFilePath;
            e.originalGraphPath = cell(row, COL_ORIGINAL_GRAPH_PATH);
            e.mutantGraphPath = cell(row, COL_MUTANT_GRAPH_PATH);
            e.excelIsKilled = row.size() > COL_IS_KILLED ? cell(row, COL_IS_KILLED) : "";

            fillExcelPathsAndTestInfo(e);

            e.key = aggregateKey(e.projectName, e.targetClassName, e.methodSignature, e.mutantName);

            ExcelEntry old = index.byKey.get(e.key);
            if (old == null) {
                index.byKey.put(e.key, e);
                index.acceptedRows++;
                index.projectCounts.put(e.projectName, index.projectCounts.getOrDefault(e.projectName, 0) + 1);
            } else {
                old.duplicateRowIndexes.add(String.valueOf(i));
                if (e.excelTestCompiled) {
                    old.excelTestCompiled = true;
                    old.javaFile = firstNonBlank(old.javaFile, e.javaFile);
                    old.classFile = firstNonBlank(old.classFile, e.classFile);
                }
                index.duplicateRows++;
            }
        }

        return index;
    }

    private static void fillExcelPathsAndTestInfo(ExcelEntry e) {
        String filePath = cleanWindowsLongPath(e.rawFilePath);
        File f = new File(filePath);
        String parent = f.getParent();
        if (parent == null) {
            return;
        }

        String filepath = parent.replace("\\", "/");
        try {
            e.resultModuleHome = Paths.get(getCurr(filepath)).normalize().toString();
        } catch (Throwable ignored) {
            return;
        }

        Path resultPath = Paths.get(e.resultModuleHome).normalize();
        Path outerRoot;
        String pathStr = resultPath.toString().replace('\\', '/');

        if (pathStr.matches(".*(?:commons-math|commons-numbers).*")) {
            outerRoot = resultPath.getParent();
        } else {
            outerRoot = resultPath.getFileName();
        }

        if (outerRoot != null && outerRoot.getFileName() != null) {
            e.sourceRootHome = resolveSourceRootHome(e.resultModuleHome, outerRoot.getFileName().toString());
            e.sourceModuleHome = resolveSourceModuleHome(e.resultModuleHome, e.sourceRootHome);
        }

        e.expectedTestSetName = buildExpectedLlmTestSetName(
                e.targetClassName,
                e.classSimpleName,
                e.mutantName,
                e.resultModuleHome
        );

        fillTestFiles(e);
        fillMutantClassInfoFromExcel(e);
    }

    private static void fillTestFiles(ExcelEntry e) {
        if (isBlank(e.resultModuleHome) || isBlank(e.expectedTestSetName)) {
            return;
        }

        Path classesRoot = Paths.get(e.resultModuleHome, "llm", "classes").toAbsolutePath().normalize();
        Path srcRoot = Paths.get(e.resultModuleHome, "llm", "src").toAbsolutePath().normalize();

        Path cls = classesRoot.resolve(e.expectedTestSetName.replace('.', File.separatorChar) + ".class");
        Path src = srcRoot.resolve(e.expectedTestSetName.replace('.', File.separatorChar) + ".java");

        e.classFile = cls.toString();
        e.javaFile = src.toString();
        e.excelTestCompiled = Files.isRegularFile(cls);

        if (Files.isRegularFile(src)) {
            e.javaFile = src.toString();
        }

        if (e.excelTestCompiled) {
            return;
        }

        String simple = simpleName(e.expectedTestSetName);

        Path foundClass = findFileBySimpleName(classesRoot, simple + ".class");
        if (foundClass != null) {
            e.classFile = foundClass.toString();
            e.expectedTestSetName = toClassName(classesRoot, foundClass);
            e.excelTestCompiled = true;
        }

        Path foundJava = findFileBySimpleName(srcRoot, simple + ".java");
        if (foundJava != null) {
            e.javaFile = foundJava.toString();
            if (foundClass == null) {
                e.expectedTestSetName = toClassName(srcRoot, foundJava);
            }
        }
    }

    private static void fillMutantClassInfoFromExcel(ExcelEntry e) {
        if (isBlank(e.resultModuleHome)
                || isBlank(e.targetClassName)
                || isBlank(e.methodSignature)
                || isBlank(e.mutantName)) {
            return;
        }

        Path mutantDir = Paths.get(
                e.resultModuleHome,
                "result",
                e.targetClassName,
                "traditional_mutants",
                e.methodSignature,
                e.mutantName
        ).toAbsolutePath().normalize();

        e.mutantClassDir = mutantDir.toString();
        e.mutantClassFound = hasClassFile(mutantDir);
    }

    private static void fillFromExcel(MutantAggregate agg, ExcelEntry e) {
        agg.projectName = e.projectName;
        agg.targetClassName = e.targetClassName;
        agg.methodSignature = e.methodSignature;
        agg.mutantName = e.mutantName;
        agg.mutationOperator = extractOperator(e.mutantName);
        agg.mutantKey = e.key;

        agg.excelRowIndexes.add(String.valueOf(e.rowIndex));
        agg.excelRowIndexes.addAll(e.duplicateRowIndexes);

        agg.rawFilePaths.add(nonNull(e.rawFilePath));
        agg.originalGraphPaths.add(nonNull(e.originalGraphPath));
        agg.mutantGraphPaths.add(nonNull(e.mutantGraphPath));
        agg.excelIsKilledValues.add(nonNull(e.excelIsKilled));

        agg.resultModuleHome = nonNull(e.resultModuleHome);
        agg.sourceRootHome = nonNull(e.sourceRootHome);
        agg.sourceModuleHome = nonNull(e.sourceModuleHome);

        agg.testSetNames.add(nonNull(e.expectedTestSetName));
        agg.javaFiles.add(nonNull(e.javaFile));
        agg.classFiles.add(nonNull(e.classFile));

        if (e.excelTestCompiled) {
            agg.testCompiledEver = true;
            agg.testCompiledFromExcel = true;
        }

        if (e.mutantClassFound) {
            agg.mutantClassFoundEver = true;
        }
        agg.mutantClassDirs.add(nonNull(e.mutantClassDir));
    }

    private static List<ProjectReportRoot> findProjectExecutionReportDirs(Path programsRoot) throws IOException {
        List<ProjectReportRoot> result = new ArrayList<ProjectReportRoot>();
        final LinkedHashSet<String> seen = new LinkedHashSet<String>();

        if (programsRoot == null || !Files.isDirectory(programsRoot)) {
            return result;
        }

        /*
         * 这里必须递归扫描任意层级下的 llm/execution-report。
         * 普通项目通常是：Programs/<project>/llm/execution-report；
         * 但 commons-math / commons-numbers 是多模块工程，报告在：
         * Programs/<parent>/<module>/llm/execution-report。
         * 如果只扫描一级目录，就会漏掉 commons-math-core、commons-numbers-core 等模块，
         * 后续所有 Excel 白名单行都会保持 NOT_REPORTED。
         */
        try (java.util.stream.Stream<Path> stream = Files.walk(programsRoot)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path dir = it.next();
                if (dir == null || !Files.isDirectory(dir)) {
                    continue;
                }

                Path fileName = dir.getFileName();
                Path parent = dir.getParent();
                Path grandParent = parent == null ? null : parent.getParent();

                if (fileName == null
                        || parent == null
                        || parent.getFileName() == null
                        || grandParent == null
                        || !EXECUTION_REPORT_DIR.equals(fileName.toString())
                        || !LLM_DIR.equals(parent.getFileName().toString())) {
                    continue;
                }

                Path executionReportDir = dir.toAbsolutePath().normalize();
                String dedupKey = executionReportDir.toString();
                if (!seen.add(dedupKey)) {
                    continue;
                }

                Path moduleDir = grandParent.toAbsolutePath().normalize();
                ProjectReportRoot pr = new ProjectReportRoot();
                pr.projectDir = moduleDir;
                pr.projectName = moduleDir.getFileName() == null ? "" : moduleDir.getFileName().toString();
                pr.executionReportDir = executionReportDir;
                result.add(pr);
            }
        }

        Collections.sort(result, new Comparator<ProjectReportRoot>() {
            @Override
            public int compare(ProjectReportRoot a, ProjectReportRoot b) {
                int c = safe(a.projectName).compareTo(safe(b.projectName));
                if (c != 0) {
                    return c;
                }
                return safe(a.executionReportDir).compareTo(safe(b.executionReportDir));
            }
        });
        return result;
    }

    private static void collectOneSuiteReport(ProjectReportRoot pr,
                                              Path suiteFile,
                                              Map<String, MutantAggregate> aggregateMap,
                                              RunStats stats) throws Exception {
        Path reportDir = suiteFile.getParent();
        JSONObject suite = readJson(suiteFile);

        String targetClassName = optFirst(suite, "targetClassName", "className", "target");
        String methodSignature = optFirst(suite, "methodSignatureFilter", "methodSignature", "method");

        String sourceModuleHome = suite.optString("sourceModuleHome", "");
        String resultModuleHome = suite.optString("resultModuleHome", "");
        String suiteStatus = suite.optString("suiteStatus", "");
        String suiteFailureReason = suite.optString("suiteFailureReason", "");
        boolean sameMethodFallback = suite.optBoolean("sameMethodFallback", false);

        String suiteMutantScore = String.valueOf(suite.optDouble("suiteMutantScore", 0.0));
        int suitePrimaryRuns = suite.optInt("suitePrimaryRuns", 0);
        int suiteFallbackRuns = suite.optInt("suiteFallbackRuns", 0);
        int suiteOriginalSuccessCount = suite.optInt("suiteOriginalSuccessCount", 0);
        int suiteOriginalFailureCount = suite.optInt("suiteOriginalFailureCount", 0);
        int suiteMutantsWithoutMappedTest = suite.optInt("suiteMutantsWithoutMappedTest", 0);
        int suiteTimeoutRuns = suite.optInt("suiteTimeoutRuns", 0);
        int suiteTimeoutMutants = suite.optInt("suiteTimeoutMutants", 0);
        long suiteElapsedMillis = suite.optLong("suiteElapsedMillis", 0L);

        Set<String> killedMutants = jsonArrayToSet(suite.optJSONArray("suiteKilledMutants"));
        Set<String> liveMutants = jsonArrayToSet(suite.optJSONArray("suiteLiveMutants"));
        Map<String, String> suiteMutantResults = jsonObjectToStringMap(suite.optJSONObject("suiteMutantResults"));

        List<TargetEntry> targetEntries = readTargetEntries(reportDir);

        for (TargetEntry e : targetEntries) {
            String tcn = firstNonBlank(e.targetClassName, targetClassName);
            String m = firstNonBlank(e.methodSignature, methodSignature);
            String mutantName = e.mutantName;

            if (isBlank(tcn) || isBlank(m) || isBlank(mutantName)) {
                continue;
            }

            String reportProjectName = resolveReportProjectName(e.projectName, resultModuleHome, pr);
            String key = aggregateKey(reportProjectName, tcn, m, mutantName);
            MutantAggregate agg = aggregateMap.get(key);
            if (agg == null && !safe(reportProjectName).equals(safe(pr.projectName))) {
                // 兼容旧的单模块报告或历史 CSV：如果报告内部 projectName / resultModuleHome 推断失败，
                // 再退回扫描目录名试一次。
                key = aggregateKey(pr.projectName, tcn, m, mutantName);
                agg = aggregateMap.get(key);
            }
            if (agg == null) {
                stats.skippedTargetEntriesNotInExcel++;
                continue;
            }

            stats.matchedTargetEntries++;

            applyReportBasicInfo(agg, reportDir, sourceModuleHome, resultModuleHome);
            applySuiteInfo(
                    agg,
                    suiteStatus,
                    suiteFailureReason,
                    sameMethodFallback,
                    suiteMutantScore,
                    suitePrimaryRuns,
                    suiteFallbackRuns,
                    suiteOriginalSuccessCount,
                    suiteOriginalFailureCount,
                    suiteMutantsWithoutMappedTest,
                    suiteTimeoutRuns,
                    suiteTimeoutMutants,
                    suiteElapsedMillis
            );
            applyTargetEntry(agg, e);
            applyKillInfo(agg, mutantName, killedMutants, liveMutants, suiteMutantResults);
            applyMutantClassInfo(agg, resultModuleHome, tcn, m, mutantName);
        }

        LinkedHashSet<String> suiteAllMutants = new LinkedHashSet<String>();
        suiteAllMutants.addAll(killedMutants);
        suiteAllMutants.addAll(liveMutants);

        for (String mutantName : suiteAllMutants) {
            if (isBlank(targetClassName) || isBlank(methodSignature) || isBlank(mutantName)) {
                continue;
            }

            String reportProjectName = resolveReportProjectName("", resultModuleHome, pr);
            String key = aggregateKey(reportProjectName, targetClassName, methodSignature, mutantName);
            MutantAggregate agg = aggregateMap.get(key);
            if (agg == null && !safe(reportProjectName).equals(safe(pr.projectName))) {
                key = aggregateKey(pr.projectName, targetClassName, methodSignature, mutantName);
                agg = aggregateMap.get(key);
            }
            if (agg == null) {
                stats.skippedSuiteMutantsNotInExcel++;
                continue;
            }

            stats.matchedSuiteMutants++;

            applyReportBasicInfo(agg, reportDir, sourceModuleHome, resultModuleHome);
            applySuiteInfo(
                    agg,
                    suiteStatus,
                    suiteFailureReason,
                    sameMethodFallback,
                    suiteMutantScore,
                    suitePrimaryRuns,
                    suiteFallbackRuns,
                    suiteOriginalSuccessCount,
                    suiteOriginalFailureCount,
                    suiteMutantsWithoutMappedTest,
                    suiteTimeoutRuns,
                    suiteTimeoutMutants,
                    suiteElapsedMillis
            );
            applyKillInfo(agg, mutantName, killedMutants, liveMutants, suiteMutantResults);
            applyMutantClassInfo(agg, resultModuleHome, targetClassName, methodSignature, mutantName);
        }
    }

    private static void applyReportBasicInfo(MutantAggregate agg,
                                             Path reportDir,
                                             String sourceModuleHome,
                                             String resultModuleHome) {
        agg.reportDirs.add(nonNull(reportDir));
        agg.reportCount++;

        if (!isBlank(sourceModuleHome)) {
            agg.sourceModuleHome = firstNonBlank(agg.sourceModuleHome, sourceModuleHome);
        }
        if (!isBlank(resultModuleHome)) {
            agg.resultModuleHome = firstNonBlank(agg.resultModuleHome, resultModuleHome);
        }
    }

    private static void applySuiteInfo(MutantAggregate agg,
                                       String suiteStatus,
                                       String suiteFailureReason,
                                       boolean sameMethodFallback,
                                       String suiteMutantScore,
                                       int suitePrimaryRuns,
                                       int suiteFallbackRuns,
                                       int suiteOriginalSuccessCount,
                                       int suiteOriginalFailureCount,
                                       int suiteMutantsWithoutMappedTest,
                                       int suiteTimeoutRuns,
                                       int suiteTimeoutMutants,
                                       long suiteElapsedMillis) {
        agg.suiteStatuses.add(blankToUnknown(suiteStatus));
        if (!isBlank(suiteFailureReason)) {
            agg.suiteFailureReasons.add(suiteFailureReason);
        }

        agg.sameMethodFallbackEver = agg.sameMethodFallbackEver || sameMethodFallback;
        agg.suiteMutantScores.add(suiteMutantScore);

        agg.suitePrimaryRunsTotal += suitePrimaryRuns;
        agg.suiteFallbackRunsTotal += suiteFallbackRuns;
        agg.suiteOriginalSuccessCountTotal += suiteOriginalSuccessCount;
        agg.suiteOriginalFailureCountTotal += suiteOriginalFailureCount;
        agg.suiteMutantsWithoutMappedTestTotal += suiteMutantsWithoutMappedTest;
        agg.suiteTimeoutRunsTotal += suiteTimeoutRuns;
        agg.suiteTimeoutMutantsTotal += suiteTimeoutMutants;
        agg.suiteElapsedMillisTotal += suiteElapsedMillis;
    }

    private static void applyTargetEntry(MutantAggregate agg, TargetEntry e) {
        agg.hasTargetEntry = true;
        agg.targetEntryCount++;

        if (!isBlank(e.rowIndex)) {
            agg.reportRowIndexes.add(e.rowIndex);
        }

        if (!isBlank(e.testSetName)) {
            agg.testSetNames.add(e.testSetName);
        }

        if (!isBlank(e.javaFile)) {
            agg.javaFiles.add(e.javaFile);
        }

        if (!isBlank(e.classFile)) {
            agg.classFiles.add(e.classFile);
        }

        if (!isBlank(e.excelIsKilled)) {
            agg.excelIsKilledValues.add(e.excelIsKilled);
        }

        boolean compiled = parseBoolean(e.compiled);
        if (compiled) {
            agg.testCompiledEver = true;
            agg.testCompiledSuccessCount++;
        } else {
            agg.testCompiledFailureCount++;
        }

        String mappingStatus = blankToUnknown(e.mappingStatus);
        agg.mappingStatuses.add(mappingStatus);
        if ("OK".equalsIgnoreCase(mappingStatus)) {
            agg.mappingOkEver = true;
        }

        if (parseBoolean(e.originalPassed)) {
            agg.originalPassedEver = true;
        }

        if (parseBoolean(e.mutantExecuted)) {
            agg.mutantExecutedEver = true;
        }

        if (parseBoolean(e.targetKilled)) {
            agg.targetKilledEver = true;
        }

        if (!isBlank(e.targetStatus)) {
            agg.targetStatuses.add(e.targetStatus);
        }

        if (!isBlank(e.targetFailureReason)) {
            agg.targetFailureReasons.add(e.targetFailureReason);
        }
    }

    private static void applyKillInfo(MutantAggregate agg,
                                      String mutantName,
                                      Set<String> killedMutants,
                                      Set<String> liveMutants,
                                      Map<String, String> suiteMutantResults) {
        boolean killed = killedMutants.contains(mutantName);
        boolean live = liveMutants.contains(mutantName);

        if (killed) {
            agg.suiteKilledEver = true;
            agg.suiteKilledRunCount++;
        }

        if (live) {
            agg.suiteLiveEver = true;
            agg.suiteLiveRunCount++;
        }

        String tests = suiteMutantResults.get(mutantName);
        if (!isBlank(tests)) {
            addCommaSeparated(agg.killingTests, tests);
        }

        if (killed && !isBlank(tests)) {
            boolean primary = false;
            for (String testSetName : agg.testSetNames) {
                if (containsPrimaryTest(tests, testSetName)) {
                    primary = true;
                    break;
                }
            }

            if (primary) {
                agg.killedByPrimaryTestEver = true;
            } else {
                agg.killedByFallbackTestEver = true;
            }
        }

        if (containsIgnoreCase(tests, "timeout")) {
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
                "traditional_mutants",
                methodSignature,
                mutantName
        ).toAbsolutePath().normalize();

        agg.mutantClassDirs.add(mutantDir.toString());

        if (hasClassFile(mutantDir)) {
            agg.mutantClassFoundEver = true;
        }
    }

    private static List<TargetEntry> readTargetEntries(Path reportDir) throws Exception {
        Path targetJson = reportDir.resolve(TARGET_JSON);
        if (Files.isRegularFile(targetJson)) {
            List<TargetEntry> list = readTargetEntriesFromJson(targetJson);
            if (!list.isEmpty()) {
                return list;
            }
        }

        Path targetCsv = reportDir.resolve(TARGET_CSV);
        if (Files.isRegularFile(targetCsv)) {
            return readTargetEntriesFromCsv(targetCsv);
        }

        return new ArrayList<TargetEntry>();
    }

    private static List<TargetEntry> readTargetEntriesFromJson(Path targetJson) throws Exception {
        List<TargetEntry> list = new ArrayList<TargetEntry>();
        JSONObject o = readJson(targetJson);

        JSONArray arr = o.optJSONArray("targetLevelEntries");
        if (arr == null) {
            arr = o.optJSONArray("testEntries");
        }
        if (arr == null) {
            arr = o.optJSONArray("entries");
        }
        if (arr == null) {
            return list;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) {
                continue;
            }

            TargetEntry t = new TargetEntry();
            t.rowIndex = optAsString(e, "rowIndex");
            t.projectName = e.optString("projectName", "");
            t.targetClassName = e.optString("targetClassName", "");
            t.methodSignature = e.optString("methodSignature", "");
            t.mutantName = e.optString("mutantName", "");
            t.testSetName = e.optString("testSetName", "");
            t.javaFile = e.optString("javaFile", "");
            t.classFile = e.optString("classFile", "");
            t.compiled = optAsString(e, "compiled");
            t.mappingStatus = e.optString("mappingStatus", "");
            t.originalPassed = optAsString(e, "originalPassed");
            t.mutantExecuted = optAsString(e, "mutantExecuted");
            t.targetKilled = optAsString(e, "targetKilled");
            t.targetStatus = e.optString("targetStatus", "");
            t.targetFailureReason = optFirst(e, "targetFailureReason", "failureReason");
            t.excelIsKilled = e.optString("excelIsKilled", "");

            list.add(t);
        }

        return list;
    }

    private static List<TargetEntry> readTargetEntriesFromCsv(Path csvFile) throws Exception {
        List<TargetEntry> list = new ArrayList<TargetEntry>();

        try (BufferedReader br = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                return list;
            }

            List<String> header = parseCsvLine(headerLine);
            Map<String, Integer> idx = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < header.size(); i++) {
                idx.put(header.get(i), i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                List<String> cells = parseCsvLine(line);

                TargetEntry t = new TargetEntry();
                t.rowIndex = get(cells, idx, "rowIndex");
                t.projectName = get(cells, idx, "projectName");
                t.targetClassName = get(cells, idx, "targetClassName");
                t.methodSignature = get(cells, idx, "methodSignature");
                t.mutantName = get(cells, idx, "mutantName");
                t.testSetName = get(cells, idx, "testSetName");
                t.compiled = get(cells, idx, "compiled");
                t.mappingStatus = get(cells, idx, "mappingStatus");
                t.originalPassed = get(cells, idx, "originalPassed");
                t.mutantExecuted = get(cells, idx, "mutantExecuted");
                t.targetKilled = get(cells, idx, "targetKilled");
                t.targetStatus = get(cells, idx, "targetStatus");
                t.targetFailureReason = get(cells, idx, "failureReason");
                if (isBlank(t.targetFailureReason)) {
                    t.targetFailureReason = get(cells, idx, "targetFailureReason");
                }
                t.javaFile = get(cells, idx, "javaFile");
                t.classFile = get(cells, idx, "classFile");
                t.excelIsKilled = get(cells, idx, "excelIsKilled");

                list.add(t);
            }
        }

        return list;
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

                    "testCompiledFinal",
                    "testCompiledFromExcel",
                    "testCompiledSuccessCount",
                    "testCompiledFailureCount",
                    "mappingStatusFinal",
                    "allMappingStatuses",

                    "mutantClassFoundFinal",
                    "mutantClassDirs",

                    "suiteKillStatusFinal",
                    "suiteKilledRunCount",
                    "suiteLiveRunCount",
                    "killingTests",
                    "killedByPrimaryTestEver",
                    "killedByFallbackTestEver",

                    "hasTargetEntry",
                    "targetEntryCount",
                    "testSetNames",
                    "targetKilledEver",
                    "originalPassedEver",
                    "mutantExecutedEver",
                    "targetStatuses",
                    "targetFailureReasons",

                    "suiteStatuses",
                    "suiteFailureReasons",
                    "sameMethodFallbackEver",
                    "suiteMutantScores",
                    "suitePrimaryRunsTotal",
                    "suiteFallbackRunsTotal",
                    "suiteOriginalSuccessCountTotal",
                    "suiteOriginalFailureCountTotal",
                    "suiteMutantsWithoutMappedTestTotal",
                    "suiteTimeoutRunsTotal",
                    "suiteTimeoutMutantsTotal",
                    "suiteElapsedMillisTotal",
                    "timeoutRelatedEver",

                    "sourceRootHome",
                    "sourceModuleHome",
                    "resultModuleHome",
                    "javaFiles",
                    "classFiles",
                    "rawFilePaths",
                    "originalGraphPaths",
                    "mutantGraphPaths",
                    "excelIsKilledValues",
                    "excelRowIndexes",
                    "reportRowIndexes",
                    "reportCount",
                    "reportDirs"
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

                        finalCompiledStatus(r),
                        String.valueOf(r.testCompiledFromExcel),
                        String.valueOf(r.testCompiledSuccessCount),
                        String.valueOf(r.testCompiledFailureCount),
                        finalMappingStatus(r),
                        joinSet(r.mappingStatuses),

                        String.valueOf(r.mutantClassFoundEver),
                        joinSet(r.mutantClassDirs),

                        finalKillStatus(r),
                        String.valueOf(r.suiteKilledRunCount),
                        String.valueOf(r.suiteLiveRunCount),
                        joinSet(r.killingTests),
                        String.valueOf(r.killedByPrimaryTestEver),
                        String.valueOf(r.killedByFallbackTestEver),

                        String.valueOf(r.hasTargetEntry),
                        String.valueOf(r.targetEntryCount),
                        joinSet(r.testSetNames),
                        String.valueOf(r.targetKilledEver),
                        String.valueOf(r.originalPassedEver),
                        String.valueOf(r.mutantExecutedEver),
                        joinSet(r.targetStatuses),
                        joinSet(r.targetFailureReasons),

                        joinSet(r.suiteStatuses),
                        joinSet(r.suiteFailureReasons),
                        String.valueOf(r.sameMethodFallbackEver),
                        joinSet(r.suiteMutantScores),
                        String.valueOf(r.suitePrimaryRunsTotal),
                        String.valueOf(r.suiteFallbackRunsTotal),
                        String.valueOf(r.suiteOriginalSuccessCountTotal),
                        String.valueOf(r.suiteOriginalFailureCountTotal),
                        String.valueOf(r.suiteMutantsWithoutMappedTestTotal),
                        String.valueOf(r.suiteTimeoutRunsTotal),
                        String.valueOf(r.suiteTimeoutMutantsTotal),
                        String.valueOf(r.suiteElapsedMillisTotal),
                        String.valueOf(r.timeoutRelatedEver),

                        r.sourceRootHome,
                        r.sourceModuleHome,
                        r.resultModuleHome,
                        joinSet(r.javaFiles),
                        joinSet(r.classFiles),
                        joinSet(r.rawFilePaths),
                        joinSet(r.originalGraphPaths),
                        joinSet(r.mutantGraphPaths),
                        joinSet(r.excelIsKilledValues),
                        joinSet(r.excelRowIndexes),
                        joinSet(r.reportRowIndexes),
                        String.valueOf(r.reportCount),
                        joinSet(r.reportDirs)
                );

                writeCsvLine(bw, line);
            }
        }
    }

    private static String finalCompiledStatus(MutantAggregate r) {
        return String.valueOf(r.testCompiledEver);
    }

    private static String finalMappingStatus(MutantAggregate r) {
        if (r.mappingOkEver) {
            return "OK";
        }
        if (r.mappingStatuses.isEmpty()) {
            return "UNKNOWN";
        }
        return joinSet(r.mappingStatuses);
    }

    private static String finalKillStatus(MutantAggregate r) {
        if (r.suiteKilledEver) {
            return "KILLED";
        }
        if (r.suiteLiveEver) {
            return "LIVE";
        }
        return "NOT_REPORTED";
    }

    private static List<ProjectReportRoot> findFilesAsProjectRoots(Path programsRoot) throws IOException {
        return findProjectExecutionReportDirs(programsRoot);
    }

    private static List<Path> findFiles(Path root, String fileName) throws IOException {
        List<Path> result = new ArrayList<Path>();
        if (!Files.isDirectory(root)) {
            return result;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (Files.isRegularFile(p)
                        && p.getFileName() != null
                        && fileName.equals(p.getFileName().toString())) {
                    result.add(p.toAbsolutePath().normalize());
                }
            }
        }

        Collections.sort(result);
        return result;
    }

    private static JSONObject readJson(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return new JSONObject(text);
    }

    private static Set<String> jsonArrayToSet(JSONArray arr) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (arr == null) {
            return set;
        }

        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!isBlank(s)) {
                set.add(s);
            }
        }

        return set;
    }

    private static Map<String, String> jsonObjectToStringMap(JSONObject obj) {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        if (obj == null) {
            return map;
        }

        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = obj.opt(k);
            map.put(k, v == null ? "" : String.valueOf(v));
        }

        return map;
    }

    private static void sortRows(List<MutantAggregate> rows) {
        Collections.sort(rows, new Comparator<MutantAggregate>() {
            @Override
            public int compare(MutantAggregate a, MutantAggregate b) {
                int c;
                c = safe(a.projectName).compareTo(safe(b.projectName));
                if (c != 0) return c;
                c = safe(a.targetClassName).compareTo(safe(b.targetClassName));
                if (c != 0) return c;
                c = safe(a.methodSignature).compareTo(safe(b.methodSignature));
                if (c != 0) return c;
                return safe(a.mutantName).compareTo(safe(b.mutantName));
            }
        });
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

        boolean quote = s.indexOf(',') >= 0
                || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0
                || s.indexOf(';') >= 0;

        String v = s.replace("\"", "\"\"");
        return quote ? "\"" + v + "\"" : v;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<String>();
        if (line == null) {
            return cells;
        }

        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (inQuote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    sb.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuote = true;
                } else if (ch == ',') {
                    cells.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(ch);
                }
            }
        }

        cells.add(sb.toString());
        return cells;
    }

    private static String get(List<String> cells, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        if (i == null || i < 0 || i >= cells.size()) {
            return "";
        }
        return cells.get(i);
    }

    private static void addCommaSeparated(Set<String> out, String text) {
        if (isBlank(text)) {
            return;
        }

        String[] arr = text.split(",");
        for (String s : arr) {
            if (!isBlank(s)) {
                out.add(s.trim());
            }
        }
    }

    private static boolean containsPrimaryTest(String killingTests, String testSetName) {
        if (isBlank(killingTests) || isBlank(testSetName)) {
            return false;
        }

        String[] parts = killingTests.split(",");
        for (String part : parts) {
            String s = part == null ? "" : part.trim();
            if (s.equals(testSetName) || s.startsWith(testSetName + "#")) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasClassFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
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

    private static Path findFileBySimpleName(Path root, String fileName) {
        if (root == null || fileName == null || !Files.isDirectory(root)) {
            return null;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (Files.isRegularFile(p)
                        && p.getFileName() != null
                        && fileName.equals(p.getFileName().toString())) {
                    return p.toAbsolutePath().normalize();
                }
            }
        } catch (IOException ignored) {
            return null;
        }

        return null;
    }

    private static String buildExpectedLlmTestSetName(String targetClassName,
                                                      String classSimpleName,
                                                      String mutantName,
                                                      String resultModuleHome) {
        String rawTarget = targetClassName == null ? "" : targetClassName.trim();
        String rawPkg = packageName(rawTarget);
        String rawSimple = isBlank(classSimpleName) ? simpleName(rawTarget) : classSimpleName.trim();

        String normalizedTarget = normalizeTargetClassNameForTestName(rawTarget);
        String normalizedPkg = packageName(normalizedTarget);
        String normalizedSimple = isBlank(classSimpleName) ? simpleName(normalizedTarget) : classSimpleName.trim();

        LinkedHashSet<String> candidates = new LinkedHashSet<String>();

        if (!rawPkg.isEmpty()) {
            candidates.add(rawPkg + "." + rawSimple + "_" + mutantName + "_Test");
        } else {
            candidates.add(rawSimple + "_" + mutantName + "_Test");
        }

        if (!normalizedPkg.isEmpty()) {
            candidates.add(normalizedPkg + "." + normalizedSimple + "_" + mutantName + "_Test");
        } else {
            candidates.add(normalizedSimple + "_" + mutantName + "_Test");
        }

        candidates.add(rawSimple + "_" + mutantName + "_Test");
        candidates.add(normalizedSimple + "_" + mutantName + "_Test");

        if (!isBlank(resultModuleHome)) {
            Path classesRoot = Paths.get(resultModuleHome, "llm", "classes").toAbsolutePath().normalize();
            Path srcRoot = Paths.get(resultModuleHome, "llm", "src").toAbsolutePath().normalize();

            for (String c : candidates) {
                if (testClassOrSourceExists(classesRoot, srcRoot, c)) {
                    return c;
                }
            }
        }

        return candidates.iterator().next();
    }

    private static boolean testClassOrSourceExists(Path classesRoot, Path srcRoot, String testSetName) {
        Path cls = classesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class");
        if (Files.isRegularFile(cls)) {
            return true;
        }

        Path src = srcRoot.resolve(testSetName.replace('.', File.separatorChar) + ".java");
        return Files.isRegularFile(src);
    }

    private static String normalizeTargetClassNameForTestName(String targetClassName) {
        if (targetClassName == null) {
            return "";
        }
        return targetClassName
                .replaceFirst("^(?:main(?:\\.java)?|java)\\.", "")
                .trim();
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

    private static String resolveReportProjectName(String entryProjectName,
                                                   String resultModuleHome,
                                                   ProjectReportRoot pr) {
        /*
         * 优先级必须与 LLMTestExecutor 的真实执行结果一致：
         * 1) llm_target_kill_results.json 里的 projectName，通常是 Excel 原始 project 列；
         * 2) suite JSON 里的 resultModuleHome 的最后一级目录，适配 commons-math / commons-numbers 子模块；
         * 3) 扫描到的报告目录对应模块名；
         * 4) 最后才兜底父目录名。
         */
        String projectName = firstNonBlank(entryProjectName, moduleNameFromResultModuleHome(resultModuleHome));
        if (!isBlank(projectName)) {
            return projectName;
        }
        if (pr != null && !isBlank(pr.projectName)) {
            return pr.projectName;
        }
        return "";
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

    private static String cleanWindowsLongPath(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim().replace('\\', '/');
        if (s.startsWith("//?/")) {
            s = s.substring("//?/".length());
        }
        return s;
    }

    private static String toClassName(Path root, Path file) {
        String rel = root.relativize(file).toString();
        if (rel.endsWith(".class")) {
            rel = rel.substring(0, rel.length() - ".class".length());
        }
        if (rel.endsWith(".java")) {
            rel = rel.substring(0, rel.length() - ".java".length());
        }
        return rel.replace(File.separatorChar, '.').replace('/', '.').replace('\\', '.');
    }

    private static boolean isExecutedProject(String projectName) {
        if (isBlank(projectName)) {
            return false;
        }
        return EXECUTED_PROJECTS.contains(projectName.trim());
    }

    private static String aggregateKey(String projectName,
                                       String targetClassName,
                                       String methodSignature,
                                       String mutantName) {
        return safe(projectName)
                + "##" + safe(targetClassName)
                + "##" + safe(methodSignature)
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

    private static String packageName(String className) {
        if (className == null) {
            return "";
        }
        int idx = className.lastIndexOf('.');
        return idx < 0 ? "" : className.substring(0, idx);
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    private static String cell(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }

    private static boolean parseBoolean(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v)
                || "1".equals(v)
                || "yes".equals(v)
                || "y".equals(v)
                || "ok".equals(v);
    }

    private static String optAsString(JSONObject o, String key) {
        if (o == null || key == null || !o.has(key) || o.isNull(key)) {
            return "";
        }
        Object v = o.opt(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String optFirst(JSONObject o, String... keys) {
        if (o == null || keys == null) {
            return "";
        }

        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String v = o.optString(key, "");
            if (!isBlank(v)) {
                return v;
            }
        }

        return "";
    }

    private static boolean containsIgnoreCase(String text, String key) {
        if (text == null || key == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT));
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? safe(b) : safe(a);
    }

    private static String blankToUnknown(String s) {
        return isBlank(s) ? "UNKNOWN" : s.trim();
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
            sb.append(s);
        }
        return sb.toString();
    }

    private static final class ExcelIndex {
        int totalExcelRows;
        int acceptedRows;
        int duplicateRows;
        final LinkedHashMap<String, ExcelEntry> byKey = new LinkedHashMap<String, ExcelEntry>();
        final LinkedHashMap<String, Integer> projectCounts = new LinkedHashMap<String, Integer>();
    }

    private static final class ExcelEntry {
        int rowIndex;
        String key = "";

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

        String expectedTestSetName = "";
        String javaFile = "";
        String classFile = "";
        boolean excelTestCompiled = false;

        String mutantClassDir = "";
        boolean mutantClassFound = false;

        final LinkedHashSet<String> duplicateRowIndexes = new LinkedHashSet<String>();
    }

    private static final class ProjectReportRoot {
        Path projectDir;
        String projectName;
        Path executionReportDir;
    }

    private static final class TargetEntry {
        String rowIndex = "";
        String projectName = "";
        String targetClassName = "";
        String methodSignature = "";
        String mutantName = "";
        String testSetName = "";
        String javaFile = "";
        String classFile = "";
        String compiled = "";
        String mappingStatus = "";
        String originalPassed = "";
        String mutantExecuted = "";
        String targetKilled = "";
        String targetStatus = "";
        String targetFailureReason = "";
        String excelIsKilled = "";
    }

    private static final class RunStats {
        int suiteReportCount;
        int failedReportCount;
        int matchedTargetEntries;
        int skippedTargetEntriesNotInExcel;
        int matchedSuiteMutants;
        int skippedSuiteMutantsNotInExcel;
    }

    private static final class MutantAggregate {
        String projectName = "";
        String targetClassName = "";
        String methodSignature = "";
        String mutantName = "";
        String mutationOperator = "";
        String mutantKey = "";

        String sourceRootHome = "";
        String sourceModuleHome = "";
        String resultModuleHome = "";

        boolean hasTargetEntry = false;
        int targetEntryCount = 0;

        boolean testCompiledEver = false;
        boolean testCompiledFromExcel = false;
        int testCompiledSuccessCount = 0;
        int testCompiledFailureCount = 0;

        boolean mappingOkEver = false;
        final Set<String> mappingStatuses = new LinkedHashSet<String>();

        boolean mutantClassFoundEver = false;
        final Set<String> mutantClassDirs = new LinkedHashSet<String>();

        boolean suiteKilledEver = false;
        boolean suiteLiveEver = false;
        int suiteKilledRunCount = 0;
        int suiteLiveRunCount = 0;

        final Set<String> killingTests = new LinkedHashSet<String>();
        boolean killedByPrimaryTestEver = false;
        boolean killedByFallbackTestEver = false;

        boolean targetKilledEver = false;
        boolean originalPassedEver = false;
        boolean mutantExecutedEver = false;

        final Set<String> testSetNames = new LinkedHashSet<String>();
        final Set<String> targetStatuses = new LinkedHashSet<String>();
        final Set<String> targetFailureReasons = new LinkedHashSet<String>();

        final Set<String> suiteStatuses = new LinkedHashSet<String>();
        final Set<String> suiteFailureReasons = new LinkedHashSet<String>();
        boolean sameMethodFallbackEver = false;
        final Set<String> suiteMutantScores = new LinkedHashSet<String>();

        int suitePrimaryRunsTotal = 0;
        int suiteFallbackRunsTotal = 0;
        int suiteOriginalSuccessCountTotal = 0;
        int suiteOriginalFailureCountTotal = 0;
        int suiteMutantsWithoutMappedTestTotal = 0;
        int suiteTimeoutRunsTotal = 0;
        int suiteTimeoutMutantsTotal = 0;
        long suiteElapsedMillisTotal = 0L;
        boolean timeoutRelatedEver = false;

        final Set<String> javaFiles = new LinkedHashSet<String>();
        final Set<String> classFiles = new LinkedHashSet<String>();
        final Set<String> rawFilePaths = new LinkedHashSet<String>();
        final Set<String> originalGraphPaths = new LinkedHashSet<String>();
        final Set<String> mutantGraphPaths = new LinkedHashSet<String>();
        final Set<String> excelIsKilledValues = new LinkedHashSet<String>();

        final Set<String> excelRowIndexes = new LinkedHashSet<String>();
        final Set<String> reportRowIndexes = new LinkedHashSet<String>();

        int reportCount = 0;
        final Set<String> reportDirs = new LinkedHashSet<String>();
    }
}