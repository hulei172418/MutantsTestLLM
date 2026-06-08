package mujava.testgenerator;

import mujava.MutationSystem;
import mujava.cmd.MuJavaRuntimeSupport8;
import mujava.cmd.TestRunner9_MultiProcess_batched;
import mujava.test.TestResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static mujava.cmd.TestRunner9_MultiProcess_batched.*;

/**
 * Excel-driven LLM test execution/evaluation driver.
 *
 * Excel columns:
 * 0  operator              -> mutantName
 * 2  method                -> methodSignature
 * 4  class_f               -> target simple class name
 * 6  package               -> MuJava targetClassName / result directory name
 * 7  project               -> project name
 * 8  file_path             -> mutant class file path, used to infer resultModuleHome
 * 9  original_graph_path   -> optional
 * 10 mutant_graph_path     -> optional
 * 11 is_killed             -> optional reference label
 *
 * Important:
 * - The mapping between an LLM test and a mutant is taken from the Excel row.
 * - This class no longer infers methodSignature from test class names.
 * - It assumes operator/mutantName is unique within the same targetClassName.
 */
public final class LLMTestExecutor {

    private LLMTestExecutor() {
    }

    private static final String LLMS_DIR = MutationSystem.TESTSET_MODE_LLMS;

    private static final String TARGET_RESULTS_JSON = "llm_target_kill_results.json";
    private static final String TARGET_RESULTS_CSV = "llm_target_kill_results.csv";
    private static final String SUITE_RESULTS_JSON = "llm_suite_kill_results.json";
    private static final String SUMMARY_TXT = "llm_execution_summary.txt";

    private static final int COL_OPERATOR = 0;
    private static final int COL_METHOD = 2;
    private static final int COL_CLASS_F = 4;
    private static final int COL_PACKAGE = 6;
    private static final int COL_PROJECT = 7;
    private static final int COL_FILE_PATH = 8;
    private static final int COL_ORIGINAL_GRAPH_PATH = 9;
    private static final int COL_MUTANT_GRAPH_PATH = 10;
    private static final int COL_IS_KILLED = 11;
    private static final String RUN_ID = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());

    private static final boolean ENABLE_TEST_FILE_INDEX =
            Boolean.parseBoolean(System.getProperty("llm.executor.testFileIndex", "true"));

    private static final Map<String, TestFileIndex> TEST_FILE_INDEX_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, TestFileIndex>());
    private static final boolean COMPILE_ONLY_EXISTING_SRC =
            Boolean.parseBoolean(System.getProperty("llm.compileOnlyExistingSrc", "false"));
    /**
     * Execution granularity:
     * - method: evaluate one target method at a time. This is recommended for LLM-generated mutation tests.
     * - class : keep the old class-level suite behavior.
     *
     * Override example:
     *   -Dllm.executor.granularity=class
     */
    private static final String EXECUTION_GRANULARITY =
            System.getProperty("llm.executor.granularity", "method").trim().toLowerCase(Locale.ROOT);

    /**
     * Quick mode only runs the primary mapped test for each mutant.
     * Formal method-suite evaluation should keep this false, so same-method fallback is enabled.
     *
     * Override example:
     *   -Dllm.executor.quickPrimaryOnly=true
     */
    private static final boolean QUICK_PRIMARY_ONLY =
            Boolean.parseBoolean(System.getProperty("llm.executor.quickPrimaryOnly", "false"));

    private static final boolean SAME_METHOD_FALLBACK_BY_DEFAULT = !QUICK_PRIMARY_ONLY;
    private static final boolean RUN_TARGET_LEVEL_BY_DEFAULT = false;
    private static final boolean RUN_SUITE_LEVEL_BY_DEFAULT = true;

    /**
     * Usage:
     *   java mujava.testgenerator.LLMTestExecutor <excelPath> [timeoutMillis]
     */
    public static void main(String[] args) throws Exception {
        String excelPath = args.length >= 1
                ? args[0]
                : "../MutantParse/data/mutant_statistic_total_graph_llm1.xlsx";

        int timeoutMillis = args.length >= 2 ? Integer.parseInt(args[1]) : 5 * 1000;

        List<List<Object>> excelData = readExcelFile(excelPath);
        if (excelData.isEmpty()) {
            System.err.println("[ERROR] Excel 数据为空: " + excelPath);
            return;
        }

        String testMode = MutationSystem.TESTSET_MODE_LLMS;
        TestRunner9_MultiProcess_batched.setTestSetMode(testMode);
        System.setProperty(MutationSystem.TESTSET_MODE_PROP, testMode);

        bootstrapMuJavaConfigProperty(excelPath);

        List<ExcelMutantRow> rows = parseExcelRows(excelData);
        if (rows.isEmpty()) {
            System.err.println("[ERROR] 没有可执行的 Excel 行: " + excelPath);
            return;
        }

        LinkedHashMap<String, List<ExcelMutantRow>> grouped =
                "method".equalsIgnoreCase(EXECUTION_GRANULARITY)
                        ? groupRowsByTargetMethod(rows)
                        : groupRowsByTarget(rows);

        int executedClassCount = 0;
        int skippedClassCount = 0;
        List<EvaluationResult> allResults = new ArrayList<EvaluationResult>();

        for (Map.Entry<String, List<ExcelMutantRow>> group : grouped.entrySet()) {
            List<ExcelMutantRow> classRows = group.getValue();
            if (classRows == null || classRows.isEmpty()) {
                skippedClassCount++;
                continue;
            }

            ExcelMutantRow first = classRows.get(0);

            EvaluationRequest request = new EvaluationRequest();
            request.sourceModuleHome = first.sourceModuleHome;
            request.resultModuleHome = first.resultModuleHome;
            request.targetClassName = first.targetClassName;
            request.methodSignatureFilter = "method".equalsIgnoreCase(EXECUTION_GRANULARITY)
                    ? first.methodSignature
                    : "";
            request.timeoutMillis = timeoutMillis;
            request.configOrExcelPath = excelPath;

            System.out.println("==================================================");
            System.out.println("[BEGIN-EVALUATE]");
            System.out.println("targetClassName     = " + request.targetClassName);
            if (!isBlank(request.methodSignatureFilter)) {
                System.out.println("methodSignature     = " + request.methodSignatureFilter);
            }
            System.out.println("sourceModuleHome    = " + request.sourceModuleHome);
            System.out.println("resultModuleHome    = " + request.resultModuleHome);
            System.out.println("executionGranularity= " + EXECUTION_GRANULARITY);
            System.out.println("sameMethodFallback  = " + SAME_METHOD_FALLBACK_BY_DEFAULT);
            System.out.println("rowsInGroup         = " + classRows.size());
            System.out.println("testMode            = " + testMode);
            System.out.println("timeoutMillis       = " + timeoutMillis);

            EvaluationResult result = evaluateTargetClass(request, classRows);
            allResults.add(result);

            executedClassCount++;

            System.out.println(result.toConsoleString());
            System.out.println("[END-EVALUATE] targetClassName = " + request.targetClassName
                    + (isBlank(request.methodSignatureFilter) ? "" : ", methodSignature = " + request.methodSignatureFilter));
        }

        writeAllClassSummary(rows.get(0).resultModuleHome, allResults);

        System.out.println("==================================================");
        System.out.println("[LLM EXECUTION SUMMARY]");
        System.out.println("excelPath          = " + excelPath);
        System.out.println("totalExcelRows     = " + (excelData.size() - 1));
        System.out.println("parsedRows         = " + rows.size());
        System.out.println("executionGranularity= " + EXECUTION_GRANULARITY);
        System.out.println("groupCount         = " + grouped.size());
        System.out.println("executedGroupCount = " + executedClassCount);
        System.out.println("skippedGroupCount  = " + skippedClassCount);
    }

    private static List<ExcelMutantRow> parseExcelRows(List<List<Object>> excelData) {
        List<ExcelMutantRow> rows = new ArrayList<ExcelMutantRow>();

        if (excelData == null || excelData.size() <= 1) {
            return rows;
        }

        int startRow = Math.max(1, Integer.getInteger("llm.executor.startRow", 1));
        int endRowExclusive = Math.min(excelData.size(), Integer.getInteger("llm.executor.endRow", excelData.size()));
        if (startRow >= endRowExclusive) {
            System.err.println("[WARN] empty row range: startRow=" + startRow
                    + " ; endRowExclusive=" + endRowExclusive
                    + " ; excelSize=" + excelData.size());
            return rows;
        }
        System.out.println("[EXCEL-RANGE] startRow=" + startRow
                + " ; endRowExclusive=" + endRowExclusive
                + " ; excelSize=" + excelData.size());

        for (int i = startRow; i < endRowExclusive; i++) {
            List<Object> row = excelData.get(i);
            if (row == null || row.size() <= COL_FILE_PATH) {
                System.err.println("[WARN] skip bad excel row: " + i + " ; column count=" + (row == null ? 0 : row.size()));
                continue;
            }

            ExcelMutantRow r = new ExcelMutantRow();
            // if(!"ant-1.10.12".equals(String.valueOf(excelData.get(i).get(7)).trim()))
            //     continue;
            String p = String.valueOf(excelData.get(i).get(7)).trim();
            if (
                    !p.equals("ant-1.10.12")
                            && !p.equals("bcel-6.10.0")
                            && !p.equals("commons-codec-1.10")
                            && !p.equals("commons-csv-1.2")
                            && !p.equals("commons-jxpath-1.3")
                            && !p.equals("commons-lang3-3.17.0")
                            && !p.equals("jackson-core-2.9.9")
                            && !p.equals("joda-time-2.14.0")
                            && !p.equals("commons-cli-1.11.0")
                            && !p.equals("oot")
                            // && !p.equals("commons-math-legacy")
                            && !p.equals("commons-math-core")
                            && !p.equals("commons-math-legacy-core")
                            && !p.equals("commons-math-legacy-exception")
                            && !p.equals("commons-math-neuralnet")
                            && !p.equals("commons-math-transform")
                            && !p.equals("commons-numbers-angle")
                            && !p.equals("commons-numbers-arrays")
                            && !p.equals("commons-numbers-combinatorics")
                            && !p.equals("commons-numbers-core")
                            && !p.equals("commons-numbers-gamma")
                            && !p.equals("commons-numbers-primes")
                            && !p.equals("commons-numbers-quaternion"))
                continue;

            r.rowIndex = i;
            r.mutantName = cell(row, COL_OPERATOR);
            r.methodSignature = cell(row, COL_METHOD);
            r.classSimpleName = cell(row, COL_CLASS_F);
            r.targetClassName = cell(row, COL_PACKAGE);
            r.projectName = cell(row, COL_PROJECT);
            r.rawFilePath = cell(row, COL_FILE_PATH);
            r.originalGraphPath = cell(row, COL_ORIGINAL_GRAPH_PATH);
            r.mutantGraphPath = cell(row, COL_MUTANT_GRAPH_PATH);
            r.isKilled = row.size() > COL_IS_KILLED ? cell(row, COL_IS_KILLED) : "";

            if (isBlank(r.mutantName)
                    || isBlank(r.methodSignature)
                    || isBlank(r.targetClassName)
                    || isBlank(r.rawFilePath)) {
                System.err.println("[WARN] skip bad excel row: " + i
                        + " ; operator=" + r.mutantName
                        + " ; method=" + r.methodSignature
                        + " ; targetClassName=" + r.targetClassName
                        + " ; filePath=" + r.rawFilePath);
                continue;
            }

            String filePath = cleanWindowsLongPath(r.rawFilePath);
            String parent = new File(filePath).getParent();
            if (parent == null) {
                System.err.println("[WARN] skip row without parent path: " + i + " ; filePath=" + r.rawFilePath);
                continue;
            }

            String filepath = parent.replace("\\", "/");
            String workDir = getCurr(filepath);

            r.resultModuleHome = Paths.get(workDir).normalize().toString();

            Path resultPath = Paths.get(r.resultModuleHome).normalize();
            Path outerRoot;
            String pathStr = resultPath.toString().replace('\\', '/');

            if (pathStr.matches(".*(?:commons-math|commons-numbers).*")) {
                outerRoot = resultPath.getParent();
            } else {
                outerRoot = resultPath.getFileName();
            }

            if (outerRoot == null || outerRoot.getFileName() == null) {
                System.err.println("[WARN] skip row with bad outerRoot: " + i + " ; resultModuleHome=" + r.resultModuleHome);
                continue;
            }

            r.sourceRootHome = resolveSourceRootHome(
                    r.resultModuleHome,
                    outerRoot.getFileName().toString()
            );

            r.sourceModuleHome = resolveSourceModuleHome(
                    r.resultModuleHome,
                    r.sourceRootHome
            );

            r.expectedTestSetName = buildExpectedLlmTestSetName(
                    r.targetClassName,
                    r.classSimpleName,
                    r.mutantName,
                    r.resultModuleHome
            );

            fillTestFiles(r);

            rows.add(r);
        }

        return rows;
    }

    private static TestFileIndex getTestFileIndex(String resultModuleHome) {
        Path classesRoot = Paths.get(resultModuleHome, LLMS_DIR, "classes").toAbsolutePath().normalize();
        Path srcRoot = Paths.get(resultModuleHome, LLMS_DIR, "src").toAbsolutePath().normalize();

        String key = classesRoot.toString() + "##" + srcRoot.toString();

        TestFileIndex cached = TEST_FILE_INDEX_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        synchronized (TEST_FILE_INDEX_CACHE) {
            cached = TEST_FILE_INDEX_CACHE.get(key);
            if (cached != null) {
                return cached;
            }

            long start = System.currentTimeMillis();
            TestFileIndex index = new TestFileIndex(classesRoot, srcRoot);

            scanTestFiles(classesRoot, ".class", index.classByFqn, index.classBySimpleName);
            scanTestFiles(srcRoot, ".java", index.javaByFqn, index.javaBySimpleName);

            TEST_FILE_INDEX_CACHE.put(key, index);

            System.out.println("[TEST-FILE-INDEX] classesRoot=" + classesRoot
                    + " classCount=" + index.classByFqn.size()
                    + " srcRoot=" + srcRoot
                    + " javaCount=" + index.javaByFqn.size()
                    + " elapsedMs=" + (System.currentTimeMillis() - start));

            return index;
        }
    }

    private static void scanTestFiles(Path root,
                                      String suffix,
                                      Map<String, Path> byFqn,
                                      Map<String, List<Path>> bySimpleName) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();

                        String className = toClassName(root, p);
                        byFqn.putIfAbsent(className, p);

                        List<Path> list = bySimpleName.get(fileName);
                        if (list == null) {
                            list = new ArrayList<Path>();
                            bySimpleName.put(fileName, list);
                        }
                        list.add(p);
                    });
        } catch (IOException e) {
            System.err.println("[WARN] scan test files failed: root=" + root
                    + " ; reason=" + e.getMessage());
        }
    }

    private static void writeAllClassSummary(String resultModuleHome,
                                             List<EvaluationResult> allResults) throws IOException {
        Path dir = Paths.get(resultModuleHome, LLMS_DIR, "execution-report", RUN_ID)
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String summaryBaseName = "method".equalsIgnoreCase(EXECUTION_GRANULARITY)
                ? "all_method_summary"
                : "all_class_summary";

        Path csvFile = dir.resolve(summaryBaseName + ".csv");
        try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            bw.write("targetClassName,methodSignature,methodCount,generatedMutantCount,generatedTestCount,compiledTestCount,compileRate,executableCompiledTestCount,targetExecutedTestCount,targetKilledCount,targetKillScore,suiteStatus,suiteKilledCount,suiteLiveCount,suiteMutantScore,suitePrimaryRuns,suiteFallbackRuns,suiteOriginalSuccessCount,suiteOriginalFailureCount,suiteMutantsWithoutMappedTest,suiteTimeoutRuns,suiteTimeoutMutants,suiteElapsedMillis");
            bw.newLine();

            for (EvaluationResult r : allResults) {
                double compileRate = r.generatedTestCount == 0
                        ? 0.0
                        : r.compiledTestCount * 100.0 / r.generatedTestCount;

                bw.write(csv(r.targetClassName)); bw.write(',');
                bw.write(csv(r.methodSignatureFilter)); bw.write(',');
                bw.write(String.valueOf(r.methodCount)); bw.write(',');
                bw.write(String.valueOf(r.generatedMutantCount)); bw.write(',');
                bw.write(String.valueOf(r.generatedTestCount)); bw.write(',');
                bw.write(String.valueOf(r.compiledTestCount)); bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", compileRate)); bw.write(',');
                bw.write(String.valueOf(r.executableCompiledTestCount)); bw.write(',');
                bw.write(String.valueOf(r.targetExecutedTestCount)); bw.write(',');
                bw.write(String.valueOf(r.targetKilledCount)); bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", r.targetKillScore)); bw.write(',');
                bw.write(csv(r.suiteStatus)); bw.write(',');
                bw.write(String.valueOf(r.suiteKilledCount)); bw.write(',');
                bw.write(String.valueOf(r.suiteLiveCount)); bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", r.suiteMutantScore)); bw.write(',');
                bw.write(String.valueOf(r.suitePrimaryRuns)); bw.write(',');
                bw.write(String.valueOf(r.suiteFallbackRuns)); bw.write(',');
                bw.write(String.valueOf(r.suiteOriginalSuccessCount)); bw.write(',');
                bw.write(String.valueOf(r.suiteOriginalFailureCount)); bw.write(',');
                bw.write(String.valueOf(r.suiteMutantsWithoutMappedTest)); bw.write(',');
                bw.write(String.valueOf(r.suiteTimeoutRuns)); bw.write(',');
                bw.write(String.valueOf(r.suiteTimeoutMutants)); bw.write(',');
                bw.write(String.valueOf(r.suiteElapsedMillis));
                bw.newLine();
            }
        }

        JSONArray arr = new JSONArray();
        for (EvaluationResult r : allResults) {
            arr.put(r.toSuiteJson());
        }
        writeText(dir.resolve(summaryBaseName + ".json"), arr.toString(2));

        if ("method".equalsIgnoreCase(EXECUTION_GRANULARITY)) {
            writeAggregatedClassSummary(dir, allResults);
        }
    }

    private static void writeAggregatedClassSummary(Path dir,
                                                    List<EvaluationResult> allResults) throws IOException {
        LinkedHashMap<String, EvaluationResult> byClass = new LinkedHashMap<String, EvaluationResult>();
        if (allResults != null) {
            for (EvaluationResult r : allResults) {
                if (r == null) {
                    continue;
                }
                String key = r.resultModuleHome + "##" + r.targetClassName;
                EvaluationResult agg = byClass.get(key);
                if (agg == null) {
                    agg = new EvaluationResult();
                    agg.sourceModuleHome = r.sourceModuleHome;
                    agg.resultModuleHome = r.resultModuleHome;
                    agg.targetClassName = r.targetClassName;
                    agg.methodSignatureFilter = "<ALL_METHODS>";
                    agg.timeoutMillis = r.timeoutMillis;
                    agg.suiteStatus = "OK";
                    byClass.put(key, agg);
                }
                agg.methodCount += Math.max(1, r.methodCount);
                agg.generatedMutantCount += r.generatedMutantCount;
                agg.generatedTestCount += r.generatedTestCount;
                agg.compiledTestCount += r.compiledTestCount;
                agg.executableCompiledTestCount += r.executableCompiledTestCount;
                agg.targetExecutedTestCount += r.targetExecutedTestCount;
                agg.targetKilledCount += r.targetKilledCount;
                agg.suiteKilledCount += r.suiteKilledCount;
                agg.suiteLiveCount += r.suiteLiveCount;
                agg.suitePrimaryRuns += r.suitePrimaryRuns;
                agg.suiteFallbackRuns += r.suiteFallbackRuns;
                agg.suiteOriginalSuccessCount += r.suiteOriginalSuccessCount;
                agg.suiteOriginalFailureCount += r.suiteOriginalFailureCount;
                agg.suiteMutantsWithoutMappedTest += r.suiteMutantsWithoutMappedTest;
                agg.suiteTimeoutRuns += r.suiteTimeoutRuns;
                agg.suiteTimeoutMutants += r.suiteTimeoutMutants;
                agg.suiteElapsedMillis += r.suiteElapsedMillis;
                agg.suiteKilledMutants.addAll(r.suiteKilledMutants);
                agg.suiteLiveMutants.addAll(r.suiteLiveMutants);
                if (!"OK".equalsIgnoreCase(r.suiteStatus)) {
                    agg.suiteStatus = "PARTIAL";
                }
            }
        }

        for (EvaluationResult agg : byClass.values()) {
            int targetTotal = agg.targetExecutedTestCount;
            agg.targetKillScore = targetTotal == 0 ? 0.0 : agg.targetKilledCount * 100.0 / targetTotal;
            int suiteTotal = agg.suiteKilledCount + agg.suiteLiveCount;
            agg.suiteMutantScore = suiteTotal == 0 ? 0.0 : agg.suiteKilledCount * 100.0 / suiteTotal;
        }

        Path csvFile = dir.resolve("all_class_summary.csv");
        try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            bw.write("targetClassName,methodCount,generatedMutantCount,generatedTestCount,compiledTestCount,compileRate,executableCompiledTestCount,targetExecutedTestCount,targetKilledCount,targetKillScore,suiteStatus,suiteKilledCount,suiteLiveCount,suiteMutantScore,suitePrimaryRuns,suiteFallbackRuns,suiteOriginalSuccessCount,suiteOriginalFailureCount,suiteMutantsWithoutMappedTest,suiteTimeoutRuns,suiteTimeoutMutants,suiteElapsedMillis");
            bw.newLine();
            for (EvaluationResult r : byClass.values()) {
                double compileRate = r.generatedTestCount == 0 ? 0.0 : r.compiledTestCount * 100.0 / r.generatedTestCount;
                bw.write(csv(r.targetClassName)); bw.write(',');
                bw.write(String.valueOf(r.methodCount)); bw.write(',');
                bw.write(String.valueOf(r.generatedMutantCount)); bw.write(',');
                bw.write(String.valueOf(r.generatedTestCount)); bw.write(',');
                bw.write(String.valueOf(r.compiledTestCount)); bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", compileRate)); bw.write(',');
                bw.write(String.valueOf(r.executableCompiledTestCount)); bw.write(',');
                bw.write(String.valueOf(r.targetExecutedTestCount)); bw.write(',');
                bw.write(String.valueOf(r.targetKilledCount)); bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", r.targetKillScore)); bw.write(',');
                bw.write(csv(r.suiteStatus)); bw.write(',');
                bw.write(String.valueOf(r.suiteKilledCount)); bw.write(',');
                bw.write(String.valueOf(r.suiteLiveCount)); bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", r.suiteMutantScore)); bw.write(',');
                bw.write(String.valueOf(r.suitePrimaryRuns)); bw.write(',');
                bw.write(String.valueOf(r.suiteFallbackRuns)); bw.write(',');
                bw.write(String.valueOf(r.suiteOriginalSuccessCount)); bw.write(',');
                bw.write(String.valueOf(r.suiteOriginalFailureCount)); bw.write(',');
                bw.write(String.valueOf(r.suiteMutantsWithoutMappedTest)); bw.write(',');
                bw.write(String.valueOf(r.suiteTimeoutRuns)); bw.write(',');
                bw.write(String.valueOf(r.suiteTimeoutMutants)); bw.write(',');
                bw.write(String.valueOf(r.suiteElapsedMillis));
                bw.newLine();
            }
        }

        JSONArray arr = new JSONArray();
        for (EvaluationResult r : byClass.values()) {
            arr.put(r.toSuiteJson());
        }
        writeText(dir.resolve("all_class_summary.json"), arr.toString(2));
    }

    public static EvaluationResult evaluateTargetClass(EvaluationRequest request,
                                                       List<ExcelMutantRow> rows) throws Exception {
        validate(request);

        if (request.configOrExcelPath != null && !request.configOrExcelPath.trim().isEmpty()) {
            Path p = Paths.get(request.configOrExcelPath).toAbsolutePath().normalize();
            if (p.getFileName() != null && "mujava.config".equalsIgnoreCase(p.getFileName().toString())) {
                System.setProperty("mujava.config.path", p.toString());
            } else {
                bootstrapMuJavaConfigProperty(p.toString());
            }
        } else {
            bootstrapMuJavaConfigProperty(null);
        }

        TestRunner9_MultiProcess_batched.setTestSetMode(MutationSystem.TESTSET_MODE_LLMS);
        System.setProperty(MutationSystem.TESTSET_MODE_PROP, MutationSystem.TESTSET_MODE_LLMS);
        System.setProperty("mujava.result.module.home", request.resultModuleHome);

        MuJavaRuntimeSupport8.initializeProject(
                request.sourceModuleHome,
                request.resultModuleHome,
                request.targetClassName
        );

        EvaluationResult result = new EvaluationResult();
        result.sourceModuleHome = request.sourceModuleHome;
        result.resultModuleHome = request.resultModuleHome;
        result.targetClassName = request.targetClassName;
        result.methodSignature = request.methodSignatureFilter;
        result.methodSignatureFilter = request.methodSignatureFilter;
        result.timeoutMillis = request.timeoutMillis;

        MutationIndex mutationIndex = buildMutationIndex(request.methodSignatureFilter);
        result.generatedMutantCount = mutationIndex.totalMutants;
        result.methodCount = mutationIndex.methodToMutants.size();

        List<LlmTestEntry> entries = buildEntriesFromExcelRows(rows, mutationIndex);

        result.generatedTestCount = entries.size();
        result.compiledTestCount = countCompiled(entries);
        result.executableCompiledTestCount = countExecutable(entries);
        result.testEntries.addAll(entries);

        // runTargetLevelVerification(request, result);
        // runSuiteLevelVerification(request, result);
        // writeReports(result);
        if (RUN_TARGET_LEVEL_BY_DEFAULT) {
            System.out.println("[PHASE] target-level begin");
            runTargetLevelVerification(request, result);
            System.out.println("[PHASE] target-level end");
        } else {
            markTargetLevelSkipped(result);
        }

        if (RUN_SUITE_LEVEL_BY_DEFAULT) {
            System.out.println("[PHASE] suite-level begin");
            runSuiteLevelVerification(request, result);
            System.out.println("[PHASE] suite-level end");
        } else {
            result.suiteStatus = "SKIP_DISABLED";
        }

        writeReports(result);

        return result;
    }

    public static EvaluationResult evaluateTargetClass(EvaluationRequest request) throws Exception {
        if (request.configOrExcelPath == null || request.configOrExcelPath.trim().isEmpty()) {
            throw new IllegalArgumentException("configOrExcelPath is required for evaluateTargetClass(request)");
        }

        List<List<Object>> excelData = readExcelFile(request.configOrExcelPath);
        List<ExcelMutantRow> rows = parseExcelRows(excelData);

        List<ExcelMutantRow> filtered = new ArrayList<ExcelMutantRow>();
        for (ExcelMutantRow r : rows) {
            if (safeEquals(r.resultModuleHome, request.resultModuleHome)
                    && safeEquals(r.targetClassName, request.targetClassName)
                    && (isBlank(request.methodSignatureFilter)
                    || safeEquals(r.methodSignature, request.methodSignatureFilter))) {
                filtered.add(r);
            }
        }

        return evaluateTargetClass(request, filtered);
    }


    private static void markTargetLevelSkipped(EvaluationResult result) {
        result.targetExecutedTestCount = 0;
        result.targetKilledCount = 0;
        result.targetKillScore = 0.0;

        if (result.testEntries != null) {
            for (LlmTestEntry entry : result.testEntries) {
                if (entry != null) {
                    entry.targetStatus = "SKIP_DISABLED";
                    entry.targetFailureReason = "target-level disabled for large-scale suite evaluation";
                }
            }
        }
    }

    private static void runTargetLevelVerification(EvaluationRequest request,
                                                   EvaluationResult result) {
        int executed = 0;
        int killed = 0;

        for (LlmTestEntry entry : result.testEntries) {
            if (!entry.compiled) {
                entry.targetStatus = "SKIP_NOT_COMPILED";
                continue;
            }
            if (!entry.executable()) {
                entry.targetStatus = "SKIP_NO_MUTANT_MAPPING";
                continue;
            }
            if (!"OK".equalsIgnoreCase(entry.mappingStatus)) {
                entry.targetStatus = "SKIP_BAD_MAPPING";
                entry.targetFailureReason = entry.mappingStatus;
                continue;
            }

            executed++;
            try {
                TestRunner9_MultiProcess_batched.setTestSetMode(MutationSystem.TESTSET_MODE_LLMS);
                System.setProperty(MutationSystem.TESTSET_MODE_PROP, MutationSystem.TESTSET_MODE_LLMS);
                System.setProperty("mujava.result.module.home", request.resultModuleHome);

                TestRunner9_MultiProcess_batched.SingleMutantRunResult single =
                        TestRunner9_MultiProcess_batched.runSingleLlmMutantTest(
                                result.targetClassName,
                                entry.testSetName,
                                entry.methodSignature,
                                entry.mutantName,
                                request.timeoutMillis,
                                request.sourceModuleHome,
                                request.resultModuleHome
                        );

                entry.originalPassed = single.originalPassed;
                entry.mutantExecuted = single.mutantExecuted;
                entry.targetKilled = single.killed;
                entry.targetStatus = single.status;
                entry.targetFailureReason = single.failureReason;
                entry.originalResults.putAll(single.originalResults);
                entry.mutantResults.putAll(single.mutantResults);

                if (entry.targetKilled) {
                    killed++;
                }
            } catch (Throwable t) {
                entry.targetStatus = "ERROR";
                entry.targetFailureReason = oneLine(t.toString());
            }
        }

        result.targetExecutedTestCount = executed;
        result.targetKilledCount = killed;
        result.targetKillScore = executed == 0 ? 0.0 : killed * 100.0 / executed;
    }

    private static void runSuiteLevelVerification(EvaluationRequest request,
                                                  EvaluationResult result) {
        if (!RUN_SUITE_LEVEL_BY_DEFAULT) {
            result.suiteStatus = "SKIP_DISABLED";
            return;
        }

        List<TestRunner9_MultiProcess_batched.LlmMappedTest> mappedTests =
                new ArrayList<TestRunner9_MultiProcess_batched.LlmMappedTest>();
        LinkedHashSet<String> testSetNames = new LinkedHashSet<String>();

        for (LlmTestEntry entry : result.testEntries) {
            if (!entry.compiled || !"OK".equalsIgnoreCase(entry.mappingStatus)) {
                continue;
            }
            if (!entry.executable()) {
                continue;
            }

            TestRunner9_MultiProcess_batched.LlmMappedTest mapped =
                    new TestRunner9_MultiProcess_batched.LlmMappedTest();
            mapped.testSetName = entry.testSetName;
            mapped.methodSignature = entry.methodSignature;
            mapped.mutantName = entry.mutantName;
            mappedTests.add(mapped);
            testSetNames.add(entry.testSetName);
        }

        result.suiteTestSetNames.addAll(testSetNames);

        if (mappedTests.isEmpty()) {
            result.suiteStatus = "SKIP_NO_COMPILED_TESTS";
            return;
        }

        try {
            TestRunner9_MultiProcess_batched.setTestSetMode(MutationSystem.TESTSET_MODE_LLMS);
            System.setProperty(MutationSystem.TESTSET_MODE_PROP, MutationSystem.TESTSET_MODE_LLMS);
            System.setProperty("mujava.result.module.home", request.resultModuleHome);

            TestRunner9_MultiProcess_batched.LlmMappedSuiteResult mappedResult =
                    TestRunner9_MultiProcess_batched.runLlmMappedSuite(
                            result.targetClassName,
                            mappedTests,
                            SAME_METHOD_FALLBACK_BY_DEFAULT,
                            request.timeoutMillis,
                            request.sourceModuleHome,
                            request.resultModuleHome,
                            request.methodSignatureFilter
                    );

            result.suiteStatus = mappedResult.status;
            result.suiteFailureReason = mappedResult.failureReason;
            result.suitePrimaryRuns = mappedResult.primaryRuns;
            result.suiteFallbackRuns = mappedResult.fallbackRuns;
            result.suiteOriginalSuccessCount = mappedResult.originalSuccessCount;
            result.suiteOriginalFailureCount = mappedResult.originalFailureCount;
            result.suiteMutantsWithoutMappedTest = mappedResult.mutantsWithoutMappedTest;
            result.suiteElapsedMillis = mappedResult.elapsedMillis;
            result.suiteTimeoutRuns = mappedResult.timeoutRuns;
            result.suiteTimeoutMutants = mappedResult.timeoutMutants;

            TestResult tr = mappedResult.testResult;
            if (tr == null) {
                result.suiteStatus = "ERROR_NULL_TEST_RESULT";
                return;
            }

            result.suiteKilledMutants = toStringList(tr.killed_mutants);
            result.suiteLiveMutants = toStringList(tr.live_mutants);
            result.suiteKilledCount = result.suiteKilledMutants.size();
            result.suiteLiveCount = result.suiteLiveMutants.size();
            result.suiteMutantScore = tr.mutant_score;

            if (tr.test_results != null) {
                result.suiteTestResults.putAll(tr.test_results);
            }
            if (tr.mutant_results != null) {
                result.suiteMutantResults.putAll(tr.mutant_results);
            }
        } catch (Throwable t) {
            result.suiteStatus = "ERROR";
            result.suiteFailureReason = oneLine(t.toString());
        }
    }

    private static LinkedHashMap<String, List<ExcelMutantRow>> groupRowsByTarget(List<ExcelMutantRow> rows) {
        LinkedHashMap<String, List<ExcelMutantRow>> grouped = new LinkedHashMap<String, List<ExcelMutantRow>>();
        if (rows == null) {
            return grouped;
        }

        for (ExcelMutantRow r : rows) {
            String key = r.resultModuleHome + "##" + r.targetClassName;
            List<ExcelMutantRow> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<ExcelMutantRow>();
                grouped.put(key, list);
            }
            list.add(r);
        }
        return grouped;
    }

    private static LinkedHashMap<String, List<ExcelMutantRow>> groupRowsByTargetMethod(List<ExcelMutantRow> rows) {
        LinkedHashMap<String, List<ExcelMutantRow>> grouped = new LinkedHashMap<String, List<ExcelMutantRow>>();
        if (rows == null) {
            return grouped;
        }

        for (ExcelMutantRow r : rows) {
            if (r == null) {
                continue;
            }

            String key = r.resultModuleHome
                    + "##" + r.targetClassName
                    + "##" + r.methodSignature;

            List<ExcelMutantRow> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<ExcelMutantRow>();
                grouped.put(key, list);
            }
            list.add(r);
        }

        return grouped;
    }

    private static List<LlmTestEntry> buildEntriesFromExcelRows(List<ExcelMutantRow> rows,
                                                                MutationIndex mutationIndex) {
        List<LlmTestEntry> entries = new ArrayList<LlmTestEntry>();

        LinkedHashSet<String> seenTests = new LinkedHashSet<String>();
        LinkedHashSet<String> seenTargetPairs = new LinkedHashSet<String>();

        for (ExcelMutantRow r : rows) {
            if (r == null) {
                continue;
            }

            LlmTestEntry e = new LlmTestEntry();
            e.rowIndex = r.rowIndex;
            e.targetClassName = r.targetClassName;
            e.methodSignature = r.methodSignature;
            e.mutantName = r.mutantName;
            e.safeMutantName = safeJavaIdentifier(r.mutantName);
            e.testSetName = r.expectedTestSetName;
            e.javaFile = r.javaFile;
            e.classFile = r.classFile;
            e.compiled = r.compiled;
            e.projectName = r.projectName;
            e.rawFilePath = r.rawFilePath;
            e.originalGraphPath = r.originalGraphPath;
            e.mutantGraphPath = r.mutantGraphPath;
            e.excelIsKilled = r.isKilled;

            String targetPairKey = r.methodSignature + "##" + r.mutantName;
            String testKey = r.expectedTestSetName;

            if (!seenTargetPairs.add(targetPairKey)) {
                e.mappingStatus = "DUPLICATE_METHOD_MUTANT";
            } else if (!seenTests.add(testKey)) {
                e.mappingStatus = "DUPLICATE_TEST_CLASS";
            } else if (!mutationIndex.methodToMutants.containsKey(r.methodSignature)) {
                e.mappingStatus = "METHOD_NOT_FOUND";
            } else if (!mutationIndex.methodToMutants.get(r.methodSignature).contains(r.mutantName)) {
                e.mappingStatus = "MUTANT_NOT_FOUND_UNDER_METHOD";
            } else if (!e.compiled) {
                e.mappingStatus = "TEST_NOT_COMPILED";
            } else {
                e.mappingStatus = "OK";
            }

            entries.add(e);
        }

        return entries;
    }

    private static MutationIndex buildMutationIndex(String methodSignatureFilter) throws Exception {
        MutationIndex index = new MutationIndex();

        List<String> methodSignatures =
                MuJavaRuntimeSupport8.readMethodSignatures(MutationSystem.TRADITIONAL_MUTANT_PATH);

        for (String methodSignature : methodSignatures) {
            if (!isBlank(methodSignatureFilter)
                    && !methodSignatureFilter.equals(methodSignature)) {
                continue;
            }

            String methodPath = MutationSystem.TRADITIONAL_MUTANT_PATH
                    + File.separator + methodSignature;

            List<String> mutants = MuJavaRuntimeSupport8.listMutantDirectories(methodPath);

            index.methodToMutants.put(methodSignature, new ArrayList<String>(mutants));

            for (String mutantName : mutants) {
                index.totalMutants++;
                index.methodMutantPairs.add(methodSignature + "##" + mutantName);
                index.mutantToMethods
                        .computeIfAbsent(mutantName, k -> new ArrayList<String>())
                        .add(methodSignature);
            }
        }

        return index;
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

        Path classesRoot = Paths.get(resultModuleHome, LLMS_DIR, "classes").toAbsolutePath().normalize();
        Path srcRoot = Paths.get(resultModuleHome, LLMS_DIR, "src").toAbsolutePath().normalize();

        for (String c : candidates) {
            if (testClassOrSourceExists(classesRoot, srcRoot, c)) {
                return c;
            }
        }

        return candidates.iterator().next();
    }

    private static void fillTestFiles(ExcelMutantRow r) {
        Path classesRoot = Paths.get(r.resultModuleHome, LLMS_DIR, "classes").toAbsolutePath().normalize();
        Path srcRoot = Paths.get(r.resultModuleHome, LLMS_DIR, "src").toAbsolutePath().normalize();

        Path cls = classesRoot.resolve(r.expectedTestSetName.replace('.', File.separatorChar) + ".class");
        Path src = srcRoot.resolve(r.expectedTestSetName.replace('.', File.separatorChar) + ".java");

        r.classFile = cls.toString();
        r.javaFile = src.toString();
        r.compiled = Files.isRegularFile(cls);

        if (Files.isRegularFile(src)) {
            r.javaFile = src.toString();
        }

        if (r.compiled || !ENABLE_TEST_FILE_INDEX) {
            return;
        }

        TestFileIndex index = getTestFileIndex(r.resultModuleHome);

        String expected = r.expectedTestSetName;
        String expectedSimple = simpleName(expected);

        String preferredPackage = packageName(expected);
        String normalizedTarget = normalizeTargetClassNameForTestName(r.targetClassName);
        String normalizedPackage = packageName(normalizedTarget);
        if (!isBlank(normalizedPackage)) {
            preferredPackage = normalizedPackage;
        }

        Path foundClass = index.findClassByFqn(expected);
        if (foundClass == null) {
            foundClass = index.findClassBySimpleName(expectedSimple + ".class", preferredPackage);
        }

        Path foundJava = index.findJavaByFqn(expected);
        if (foundJava == null) {
            foundJava = index.findJavaBySimpleName(expectedSimple + ".java", preferredPackage);
        }

        if (foundClass != null) {
            r.classFile = foundClass.toString();
            r.compiled = true;
            r.expectedTestSetName = toClassName(classesRoot, foundClass);
        }

        if (foundJava != null) {
            r.javaFile = foundJava.toString();
            if (foundClass == null) {
                r.expectedTestSetName = toClassName(srcRoot, foundJava);
            }
        }
    }

    private static Path findFileBySimpleName(Path root, String fileName) {
        if (root == null || fileName == null || !Files.isDirectory(root)) {
            return null;
        }

        try {
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                Optional<Path> found = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> fileName.equals(p.getFileName().toString()))
                        .findFirst();
                return found.orElse(null);
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean testClassOrSourceExists(Path classesRoot, Path srcRoot, String testSetName) {
        Path cls = classesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class");
        if (Files.isRegularFile(cls)) {
            return true;
        }

        Path src = srcRoot.resolve(testSetName.replace('.', File.separatorChar) + ".java");
        return Files.isRegularFile(src);
    }

    private static void writeReports(EvaluationResult result) throws IOException {
        Path reportDir = getReportDir(result);
        Files.createDirectories(reportDir);

        writeText(reportDir.resolve(TARGET_RESULTS_JSON), result.toJson().toString(2));
        writeTargetCsv(reportDir.resolve(TARGET_RESULTS_CSV), result.testEntries);
        writeText(reportDir.resolve(SUITE_RESULTS_JSON), result.toSuiteJson().toString(2));
        writeText(reportDir.resolve(SUMMARY_TXT), result.toConsoleString());
    }

    private static Path getReportDir(EvaluationResult result) {
        if (!isBlank(result.methodSignature)) {
            return Paths.get(
                    result.resultModuleHome,
                    LLMS_DIR,
                    "execution-report",
                    RUN_ID,
                    sanitizeFileName(result.targetClassName),
                    result.methodSignature
            ).toAbsolutePath().normalize();
        }

        return Paths.get(
                result.resultModuleHome,
                LLMS_DIR,
                "execution-report",
                RUN_ID,
                sanitizeFileName(result.targetClassName)
        ).toAbsolutePath().normalize();
    }

    private static String sanitizeFileName(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "_";
        }
        return s.replaceAll("[\\\\/:*?\"<>|()\\s]+", "_");
    }


    private static void writeTargetCsv(Path file, List<LlmTestEntry> entries) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            bw.write("rowIndex,targetClassName,methodSignature,mutantName,testSetName,compiled,mappingStatus,originalPassed,mutantExecuted,targetKilled,targetStatus,failureReason,javaFile,classFile,excelIsKilled");
            bw.newLine();

            for (LlmTestEntry e : entries) {
                bw.write(String.valueOf(e.rowIndex)); bw.write(',');
                bw.write(csv(e.targetClassName)); bw.write(',');
                bw.write(csv(e.methodSignature)); bw.write(',');
                bw.write(csv(e.mutantName)); bw.write(',');
                bw.write(csv(e.testSetName)); bw.write(',');
                bw.write(String.valueOf(e.compiled)); bw.write(',');
                bw.write(csv(e.mappingStatus)); bw.write(',');
                bw.write(String.valueOf(e.originalPassed)); bw.write(',');
                bw.write(String.valueOf(e.mutantExecuted)); bw.write(',');
                bw.write(String.valueOf(e.targetKilled)); bw.write(',');
                bw.write(csv(e.targetStatus)); bw.write(',');
                bw.write(csv(e.targetFailureReason)); bw.write(',');
                bw.write(csv(e.javaFile)); bw.write(',');
                bw.write(csv(e.classFile)); bw.write(',');
                bw.write(csv(e.excelIsKilled));
                bw.newLine();
            }
        }
    }

    private static int countCompiled(List<LlmTestEntry> entries) {
        int n = 0;
        for (LlmTestEntry e : entries) {
            if (e.compiled) {
                n++;
            }
        }
        return n;
    }

    private static int countExecutable(List<LlmTestEntry> entries) {
        int n = 0;
        for (LlmTestEntry e : entries) {
            if (e.compiled && e.executable() && "OK".equalsIgnoreCase(e.mappingStatus)) {
                n++;
            }
        }
        return n;
    }

    private static String normalizeTargetClassNameForTestName(String targetClassName) {
        if (targetClassName == null) {
            return "";
        }
        return targetClassName
                .replaceFirst("^(?:main(?:\\.java)?|java)\\.", "")
                .trim();
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

    private static String cell(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String csv(String s) {
        if (s == null) {
            s = "";
        }
        s = s.replace("\r", " ").replace("\n", " ");
        if (s.contains(",") || s.contains("\"") || s.contains(" ")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
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

    private static void validate(EvaluationRequest request) {
        requireNonBlank(request.sourceModuleHome, "sourceModuleHome");
        requireNonBlank(request.resultModuleHome, "resultModuleHome");
        requireNonBlank(request.targetClassName, "targetClassName");

        if (!Files.isDirectory(Paths.get(request.sourceModuleHome))) {
            throw new IllegalArgumentException("sourceModuleHome is not a directory: " + request.sourceModuleHome);
        }
        if (!Files.isDirectory(Paths.get(request.resultModuleHome))) {
            throw new IllegalArgumentException("resultModuleHome is not a directory: " + request.resultModuleHome);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is blank");
        }
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

    private static String safeJavaIdentifier(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "Mutant";
        }

        String s = text.replaceAll("[^A-Za-z0-9_$]+", "_");
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            s = "M_" + s;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }

    private static List<String> toStringList(List<?> input) {
        List<String> out = new ArrayList<String>();
        if (input == null) {
            return out;
        }
        for (Object o : input) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    public static final class EvaluationRequest {
        public String sourceModuleHome;
        public String resultModuleHome;
        public String targetClassName;
        public String methodSignatureFilter;
        public int timeoutMillis = 1000;
        public String configOrExcelPath;
    }

    public static final class ExcelMutantRow {
        public int rowIndex;

        public String mutantName;
        public String methodSignature;
        public String classSimpleName;
        public String targetClassName;
        public String projectName;

        public String rawFilePath;
        public String originalGraphPath;
        public String mutantGraphPath;
        public String isKilled;

        public String resultModuleHome;
        public String sourceRootHome;
        public String sourceModuleHome;

        public String expectedTestSetName;
        public String javaFile;
        public String classFile;
        public boolean compiled;
    }

    private static final class MutationIndex {
        int totalMutants;
        final LinkedHashMap<String, List<String>> methodToMutants = new LinkedHashMap<String, List<String>>();
        final LinkedHashMap<String, List<String>> mutantToMethods = new LinkedHashMap<String, List<String>>();
        final LinkedHashSet<String> methodMutantPairs = new LinkedHashSet<String>();
    }

    public static final class LlmTestEntry {
        public int rowIndex;
        public String targetClassName;
        public String methodSignature;
        public String mutantName;
        public String safeMutantName;
        public String testSetName;
        public String javaFile;
        public String classFile;
        public boolean compiled;
        public String mappingStatus = "UNKNOWN";

        public String projectName;
        public String rawFilePath;
        public String originalGraphPath;
        public String mutantGraphPath;
        public String excelIsKilled;

        public boolean originalPassed;
        public boolean mutantExecuted;
        public boolean targetKilled;
        public String targetStatus = "NOT_RUN";
        public String targetFailureReason = "";

        public final LinkedHashMap<String, String> originalResults = new LinkedHashMap<String, String>();
        public final LinkedHashMap<String, String> mutantResults = new LinkedHashMap<String, String>();

        public boolean executable() {
            return methodSignature != null && !methodSignature.trim().isEmpty()
                    && mutantName != null && !mutantName.trim().isEmpty()
                    && testSetName != null && !testSetName.trim().isEmpty();
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("rowIndex", rowIndex);
            o.put("targetClassName", targetClassName);
            o.put("methodSignature", methodSignature);
            o.put("mutantName", mutantName);
            o.put("safeMutantName", safeMutantName);
            o.put("testSetName", testSetName);
            o.put("javaFile", javaFile);
            o.put("classFile", classFile);
            o.put("compiled", compiled);
            o.put("mappingStatus", mappingStatus);
            o.put("projectName", projectName);
            o.put("rawFilePath", rawFilePath);
            o.put("originalGraphPath", originalGraphPath);
            o.put("mutantGraphPath", mutantGraphPath);
            o.put("excelIsKilled", excelIsKilled);
            o.put("originalPassed", originalPassed);
            o.put("mutantExecuted", mutantExecuted);
            o.put("targetKilled", targetKilled);
            o.put("targetStatus", targetStatus);
            o.put("targetFailureReason", targetFailureReason);
            o.put("originalResults", new JSONObject(originalResults));
            o.put("mutantResults", new JSONObject(mutantResults));
            return o;
        }
    }

    private static final class TestFileIndex {
        final Path classesRoot;
        final Path srcRoot;

        final Map<String, Path> classByFqn = new LinkedHashMap<String, Path>();
        final Map<String, Path> javaByFqn = new LinkedHashMap<String, Path>();

        final Map<String, List<Path>> classBySimpleName = new LinkedHashMap<String, List<Path>>();
        final Map<String, List<Path>> javaBySimpleName = new LinkedHashMap<String, List<Path>>();

        TestFileIndex(Path classesRoot, Path srcRoot) {
            this.classesRoot = classesRoot;
            this.srcRoot = srcRoot;
        }

        Path findClassByFqn(String className) {
            if (className == null) {
                return null;
            }
            return classByFqn.get(className.trim());
        }

        Path findJavaByFqn(String className) {
            if (className == null) {
                return null;
            }
            return javaByFqn.get(className.trim());
        }

        Path findClassBySimpleName(String simpleFileName, String preferredPackage) {
            return chooseBest(classBySimpleName.get(simpleFileName), classesRoot, preferredPackage);
        }

        Path findJavaBySimpleName(String simpleFileName, String preferredPackage) {
            return chooseBest(javaBySimpleName.get(simpleFileName), srcRoot, preferredPackage);
        }

        private static Path chooseBest(List<Path> candidates, Path root, String preferredPackage) {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }

            String pkg = preferredPackage == null ? "" : preferredPackage.trim();
            if (!pkg.isEmpty()) {
                String pkgPath = pkg.replace('.', File.separatorChar);
                for (Path p : candidates) {
                    try {
                        Path rel = root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize());
                        String relStr = rel.toString();
                        if (relStr.startsWith(pkgPath + File.separator)) {
                            return p;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                String pkgPathUnix = pkg.replace('.', '/');
                for (Path p : candidates) {
                    String normalized = p.toString().replace('\\', '/');
                    if (normalized.contains("/" + pkgPathUnix + "/")) {
                        return p;
                    }
                }
            }

            // 如果存在重复 simpleName，且没有包名能判断，返回第一个。
            // 正常情况下你的 类名_变异体名_Test 是类内唯一，不会走到复杂歧义。
            return candidates.get(0);
        }
    }

    public static final class EvaluationResult {
        public String sourceModuleHome;
        public String resultModuleHome;
        public String targetClassName;
        public String methodSignatureFilter;
        public String methodSignature;
        public int timeoutMillis;

        public int methodCount;
        public int generatedMutantCount;
        public int generatedTestCount;
        public int compiledTestCount;
        public int executableCompiledTestCount;

        public int targetExecutedTestCount;
        public int targetKilledCount;
        public double targetKillScore;

        public String suiteStatus = "NOT_RUN";
        public String suiteFailureReason = "";
        public int suiteKilledCount;
        public int suiteLiveCount;
        public double suiteMutantScore;

        public int suitePrimaryRuns;
        public int suiteFallbackRuns;
        public int suiteOriginalSuccessCount;
        public int suiteOriginalFailureCount;
        public int suiteMutantsWithoutMappedTest;
        public long suiteElapsedMillis;

        public int suiteTimeoutRuns;
        public int suiteTimeoutMutants;

        public final List<LlmTestEntry> testEntries = new ArrayList<LlmTestEntry>();
        public final List<String> suiteTestSetNames = new ArrayList<String>();
        public List<String> suiteKilledMutants = new ArrayList<String>();
        public List<String> suiteLiveMutants = new ArrayList<String>();
        public final LinkedHashMap<String, String> suiteTestResults = new LinkedHashMap<String, String>();
        public final LinkedHashMap<String, String> suiteMutantResults = new LinkedHashMap<String, String>();

        public JSONObject toJson() {
            JSONObject o = toSuiteJson();
            JSONArray arr = new JSONArray();
            for (LlmTestEntry e : testEntries) {
                arr.put(e.toJson());
            }
            o.put("targetLevelEntries", arr);
            return o;
        }

        public JSONObject toSuiteJson() {
            JSONObject o = new JSONObject();
            o.put("sourceModuleHome", sourceModuleHome);
            o.put("resultModuleHome", resultModuleHome);
            o.put("targetClassName", targetClassName);
            o.put("methodSignature", methodSignatureFilter);
            o.put("executionGranularity", EXECUTION_GRANULARITY);
            o.put("sameMethodFallback", SAME_METHOD_FALLBACK_BY_DEFAULT);
            o.put("timeoutMillis", timeoutMillis);

            o.put("methodCount", methodCount);
            o.put("generatedMutantCount", generatedMutantCount);
            o.put("generatedTestCount", generatedTestCount);
            o.put("compiledTestCount", compiledTestCount);
            o.put("executableCompiledTestCount", executableCompiledTestCount);

            o.put("targetExecutedTestCount", targetExecutedTestCount);
            o.put("targetKilledCount", targetKilledCount);
            o.put("targetKillScore", targetKillScore);

            o.put("suiteStatus", suiteStatus);
            o.put("suiteFailureReason", suiteFailureReason);
            o.put("suiteTestSetNames", new JSONArray(suiteTestSetNames));
            o.put("suiteKilledCount", suiteKilledCount);
            o.put("suiteLiveCount", suiteLiveCount);
            o.put("suiteMutantScore", suiteMutantScore);
            o.put("suiteKilledMutants", new JSONArray(suiteKilledMutants));
            o.put("suiteLiveMutants", new JSONArray(suiteLiveMutants));
            o.put("suiteTestResults", new JSONObject(suiteTestResults));
            o.put("suiteMutantResults", new JSONObject(suiteMutantResults));

            o.put("suitePrimaryRuns", suitePrimaryRuns);
            o.put("suiteFallbackRuns", suiteFallbackRuns);
            o.put("suiteOriginalSuccessCount", suiteOriginalSuccessCount);
            o.put("suiteOriginalFailureCount", suiteOriginalFailureCount);
            o.put("suiteMutantsWithoutMappedTest", suiteMutantsWithoutMappedTest);
            o.put("suiteElapsedMillis", suiteElapsedMillis);

            o.put("suiteTimeoutRuns", suiteTimeoutRuns);
            o.put("suiteTimeoutMutants", suiteTimeoutMutants);

            return o;
        }

        public String toConsoleString() {
            StringBuilder sb = new StringBuilder();
            sb.append("==================================================\n");
            sb.append("[LLM EXECUTION SUMMARY]\n");
            sb.append("targetClassName             = ").append(targetClassName).append('\n');
            sb.append("sourceModuleHome            = ").append(sourceModuleHome).append('\n');
            sb.append("resultModuleHome            = ").append(resultModuleHome).append('\n');
            sb.append("methodCount                 = ").append(methodCount).append('\n');
            sb.append("generatedMutantCount        = ").append(generatedMutantCount).append('\n');
            sb.append("generatedTestCount          = ").append(generatedTestCount).append('\n');
            sb.append("compiledTestCount           = ").append(compiledTestCount).append('\n');
            sb.append("executableCompiledTestCount = ").append(executableCompiledTestCount).append('\n');
            sb.append("targetExecutedTestCount     = ").append(targetExecutedTestCount).append('\n');
            sb.append("targetKilledCount           = ").append(targetKilledCount).append('\n');
            sb.append("targetKillScore             = ").append(String.format(Locale.ROOT, "%.2f", targetKillScore)).append("%\n");
            sb.append("suiteStatus                 = ").append(suiteStatus).append('\n');
            if (suiteFailureReason != null && !suiteFailureReason.trim().isEmpty()) {
                sb.append("suiteFailureReason          = ").append(suiteFailureReason).append('\n');
            }
            sb.append("suiteKilledCount            = ").append(suiteKilledCount).append('\n');
            sb.append("suiteLiveCount              = ").append(suiteLiveCount).append('\n');
            sb.append("suiteMutantScore            = ").append(String.format(Locale.ROOT, "%.2f", suiteMutantScore)).append("%\n");

            sb.append("suitePrimaryRuns            = ").append(suitePrimaryRuns).append('\n');
            sb.append("suiteFallbackRuns           = ").append(suiteFallbackRuns).append('\n');
            sb.append("suiteOriginalSuccessCount   = ").append(suiteOriginalSuccessCount).append('\n');
            sb.append("suiteOriginalFailureCount   = ").append(suiteOriginalFailureCount).append('\n');
            sb.append("suiteMutantsWithoutMappedTest= ").append(suiteMutantsWithoutMappedTest).append('\n');
            sb.append("suiteTimeoutRuns           = ").append(suiteTimeoutRuns).append('\n');
            sb.append("suiteTimeoutMutants        = ").append(suiteTimeoutMutants).append('\n');
            sb.append("suiteElapsedMillis          = ").append(suiteElapsedMillis).append('\n');
            sb.append("reportDir                   = ")
                    .append(LLMTestExecutor.getReportDir(this).toAbsolutePath().normalize())
                    .append('\n');

            return sb.toString();
        }
    }
}
