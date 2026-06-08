package mujava.testgenerator;

import mujava.MutationSystem;
import mujava.testgenerator.tools.FileTextUtils;
import mujava.testgenerator.tools.GeneratedCodeExtractor;
import mujava.testgenerator.tools.GeneratedTestCompiler;
import mujava.testgenerator.tools.ProjectClasspathCache;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.cmd.TestRunner9_MultiProcess_batched.bootstrapMuJavaConfigProperty;
import static mujava.cmd.TestRunner9_MultiProcess_batched.getCurr;
import static mujava.cmd.TestRunner9_MultiProcess_batched.readExcelFile;
import static mujava.cmd.TestRunner9_MultiProcess_batched.resolveSourceModuleHome;
import static mujava.cmd.TestRunner9_MultiProcess_batched.resolveSourceRootHome;
import static mujava.cmd.TestRunner9_MultiProcess_batched.setTestSetMode;
import static mujava.testgenerator.tools.FileTextUtils.writeText;
import static mujava.testgenerator.tools.TestNameUtils.buildGeneratedTestFqn;
import static mujava.testgenerator.tools.TestNameUtils.fileBaseName;
import static mujava.testgenerator.tools.TestNameUtils.toJavaFile;

/**
 * Restore compiled LLM test artifacts from historical successful generation logs.
 *
 * This tool does NOT call the LLM.
 *
 * Recovery order:
 * 1) Read llm/report/llm_generation_results.jsonl.
 * 2) Keep unique testSetName with compiled=true.
 * 3) For each selected Excel request:
 *      - If llm/src/.../*.java exists, use it.
 *      - If .java is missing, restore it from llm/report/logs/*__response_*.json.
 *      - If llm/classes/.../*.class exists, skip by default.
 *      - Otherwise compile .java into llm/classes.
 */
public final class LLMCompiledTestRestorer {

    private LLMCompiledTestRestorer() {
    }

    private static final String DEFAULT_EXCEL_PATH =
            "../MutantParse/data/mutant_statistic_total_graph_llm.xlsx";

    /**
     * true: existing .class will be skipped.
     * false: recompile all historical compiled=true test sources.
     */
    private static final boolean SKIP_EXISTING_CLASSES =
            Boolean.parseBoolean(System.getProperty("restore.skipExistingClasses", "true"));

    /**
     * true: if .java is missing, try to restore from __response_*.json.
     */
    private static final boolean RESTORE_MISSING_JAVA_FROM_RESPONSE =
            Boolean.parseBoolean(System.getProperty("restore.missingJavaFromResponse", "true"));

    /**
     * true: rewrite .java from response even when .java already exists.
     * Default false, because llm/src is usually the final normalized Java source.
     */
    private static final boolean FORCE_RESTORE_JAVA_FROM_RESPONSE =
            Boolean.parseBoolean(System.getProperty("restore.forceRewriteJavaFromResponse", "false"));

    private static final int DEFAULT_THREADS =
            Integer.getInteger("restore.threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    private static final int PROGRESS_EVERY =
            Integer.getInteger("restore.progressEvery", 200);

    private static final Object JSONL_LOCK = new Object();

    private static final Set<String> PROJECTS = new HashSet<String>(Arrays.asList(
            // "ant-1.10.12",
            "bcel-6.10.0",
            "commons-codec-1.10",
            "commons-csv-1.2",
            "commons-jxpath-1.3",
            "commons-lang3-3.17.0",
            "jackson-core-2.9.9",
            "joda-time-2.14.0",
            "commons-cli-1.11.0",
            "oot",
            // && !p.equals("commons-math-legacy")
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
        String excelPath = args != null && args.length > 0 && !isBlank(args[0])
                ? args[0]
                : System.getProperty("restore.excelPath", DEFAULT_EXCEL_PATH);

        List<List<Object>> excelData = readExcelFile(excelPath);
        if (excelData == null || excelData.isEmpty()) {
            System.err.println("[ERROR] Excel 数据为空: " + excelPath);
            return;
        }

        setTestSetMode(MutationSystem.TESTSET_MODE_LLMS);
        bootstrapMuJavaConfigProperty(excelPath);

        System.out.println("[RESTORE-CONFIG] excelPath=" + excelPath
                + ", threads=" + DEFAULT_THREADS
                + ", skipExistingClasses=" + SKIP_EXISTING_CLASSES
                + ", restoreMissingJavaFromResponse=" + RESTORE_MISSING_JAVA_FROM_RESPONSE
                + ", forceRewriteJavaFromResponse=" + FORCE_RESTORE_JAVA_FROM_RESPONSE);

        List<Request> requests = buildRequestsFromExcel(excelData);
        if (requests.isEmpty()) {
            System.out.println("[RESTORE] no request after project filter.");
            return;
        }

        Map<String, Map<String, SuccessRecord>> successByHome =
                loadHistoricalCompiledSuccessRecords(requests);

        List<RestoreTask> tasks = buildRestoreTasks(requests, successByHome);
        runRestoreTasksConcurrently(tasks);
    }

    private static List<Request> buildRequestsFromExcel(List<List<Object>> excelData) {
        List<Request> requests = new ArrayList<Request>();

        for (int i = 1; i < excelData.size(); i++) {
            try {
                List<Object> row = excelData.get(i);
                if (row == null || row.size() <= 10) {
                    continue;
                }

                String projectName = String.valueOf(row.get(7)).trim();
                if (!PROJECTS.contains(projectName)) {
                    continue;
                }

                String operator = String.valueOf(row.get(0)).trim();
                String method = String.valueOf(row.get(2)).trim();
                String packageName = String.valueOf(row.get(6)).trim();
                String rawFilePath = String.valueOf(row.get(8)).trim();

                String filepath = new File(rawFilePath)
                        .getParent()
                        .replace("\\", "/")
                        .replace("//?/", "");

                String workDir = getCurr(filepath);
                String resultModuleHome = Paths.get(workDir).normalize().toString();

                Path resultPath = Paths.get(resultModuleHome).normalize();
                Path outerRoot;
                String pathStr = resultPath.toString().replace('\\', '/');

                if (pathStr.matches(".*(?:commons-math|commons-numbers).*")) {
                    outerRoot = resultPath.getParent();
                } else {
                    outerRoot = resultPath.getFileName();
                }

                String sourceRootHome = resolveSourceRootHome(
                        resultModuleHome,
                        outerRoot.getFileName().toString()
                );
                String sourceModuleHome = resolveSourceModuleHome(resultModuleHome, sourceRootHome);

                Request request = new Request();
                request.taskId = "row-" + i;
                request.projectName = projectName;
                request.sourceModuleHome = sourceModuleHome;
                request.resultModuleHome = resultModuleHome;
                request.targetClassName = packageName;
                request.methodSignature = method;
                request.mutantName = operator;
                request.testSetName = buildGeneratedTestFqn(packageName, operator);

                requests.add(request);

            } catch (Throwable t) {
                System.err.println("[RESTORE-TASK-BUILD-FAIL] row=" + i
                        + " reason=" + oneLine(String.valueOf(t.getMessage())));
            }
        }

        System.out.println("[RESTORE-TASK-BUILD] requests=" + requests.size());
        return requests;
    }

    private static Map<String, Map<String, SuccessRecord>> loadHistoricalCompiledSuccessRecords(List<Request> requests) {
        LinkedHashSet<String> homes = new LinkedHashSet<String>();
        for (Request r : requests) {
            if (r != null && !isBlank(r.resultModuleHome)) {
                homes.add(r.resultModuleHome);
            }
        }

        Map<String, Map<String, SuccessRecord>> out =
                new LinkedHashMap<String, Map<String, SuccessRecord>>();

        for (String resultModuleHome : homes) {
            Path jsonl = Paths.get(
                    resultModuleHome,
                    MutationSystem.TESTSET_MODE_LLMS,
                    "report",
                    "llm_generation_results.jsonl"
            ).toAbsolutePath().normalize();

            Map<String, SuccessRecord> map = new LinkedHashMap<String, SuccessRecord>();
            int lines = 0;
            int compiledLines = 0;

            if (!Files.isRegularFile(jsonl)) {
                System.out.println("[RESTORE-JSONL-MISSING] " + jsonl);
                out.put(resultModuleHome, map);
                continue;
            }

            try (BufferedReader br = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines++;
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    try {
                        JSONObject obj = new JSONObject(line);
                        if (!obj.optBoolean("compiled", false)) {
                            continue;
                        }

                        String testSetName = obj.optString("testSetName", "").trim();
                        if (testSetName.isEmpty()) {
                            testSetName = inferTestSetNameFromJavaFile(
                                    resultModuleHome,
                                    obj.optString("testJavaFile", "")
                            );
                        }

                        if (testSetName.isEmpty()) {
                            continue;
                        }

                        compiledLines++;

                        SuccessRecord record = new SuccessRecord();
                        record.testSetName = testSetName;
                        record.testJavaFile = obj.optString("testJavaFile", "").trim();
                        record.skippedExistingCompiledTest =
                                obj.optBoolean("skippedExistingCompiledTest", false);
                        record.compileRounds = obj.optInt("compileRounds", -1);
                        record.rawLineIndex = lines;

                        /*
                         * Keep the latest compiled=true record for the same testSetName.
                         * jsonl is append-only, so later records are usually newer.
                         */
                        map.put(testSetName, record);

                    } catch (Throwable ignored) {
                    }
                }
            } catch (IOException e) {
                System.err.println("[RESTORE-JSONL-READ-FAIL] " + jsonl
                        + " reason=" + e.getMessage());
            }

            System.out.println("[RESTORE-JSONL] home=" + resultModuleHome
                    + " lines=" + lines
                    + " compiledLines=" + compiledLines
                    + " uniqueCompiledTests=" + map.size());

            out.put(resultModuleHome, map);
        }

        return out;
    }

    private static List<RestoreTask> buildRestoreTasks(List<Request> requests,
                                                       Map<String, Map<String, SuccessRecord>> successByHome) {
        List<RestoreTask> tasks = new ArrayList<RestoreTask>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();

        int notInHistoricalSuccess = 0;

        for (Request r : requests) {
            if (r == null) {
                continue;
            }

            Map<String, SuccessRecord> successMap = successByHome.get(r.resultModuleHome);
            if (successMap == null) {
                notInHistoricalSuccess++;
                continue;
            }

            SuccessRecord record = successMap.get(r.testSetName);
            if (record == null) {
                notInHistoricalSuccess++;
                continue;
            }

            String key = r.resultModuleHome + "##" + r.testSetName;
            if (!seen.add(key)) {
                continue;
            }

            RestoreTask task = new RestoreTask();
            task.request = r;
            task.successRecord = record;
            tasks.add(task);
        }

        System.out.println("[RESTORE-PLAN] excelRequests=" + requests.size()
                + ", historicalSuccessSelected=" + tasks.size()
                + ", notInHistoricalSuccess=" + notInHistoricalSuccess);

        return tasks;
    }

    private static void runRestoreTasksConcurrently(List<RestoreTask> tasks) throws Exception {
        if (tasks == null || tasks.isEmpty()) {
            System.out.println("[RESTORE] no historical compiled=true task to restore.");
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, DEFAULT_THREADS));
        CompletionService<RestoreResult> completion =
                new ExecutorCompletionService<RestoreResult>(pool);

        int submitted = 0;
        for (final RestoreTask task : tasks) {
            completion.submit(new Callable<RestoreResult>() {
                @Override
                public RestoreResult call() {
                    return restoreOneSafely(task);
                }
            });
            submitted++;
        }

        int classSkipped = 0;
        int classCompiled = 0;
        int javaRestored = 0;
        int javaExists = 0;
        int sourceMissing = 0;
        int failed = 0;

        try {
            for (int done = 1; done <= submitted; done++) {
                Future<RestoreResult> future = completion.take();
                RestoreResult r = future.get();

                if (r.javaRestored) {
                    javaRestored++;
                }
                if (r.javaExists) {
                    javaExists++;
                }

                if ("SKIP_EXISTING_CLASS".equals(r.status)) {
                    classSkipped++;
                } else if ("COMPILED".equals(r.status)) {
                    classCompiled++;
                } else if ("SOURCE_MISSING".equals(r.status)) {
                    sourceMissing++;
                } else if (!"OK".equals(r.status)) {
                    failed++;
                }

                if (done == 1 || done % PROGRESS_EVERY == 0 || done == submitted) {
                    System.out.println("[RESTORE-PROG] done=" + done + "/" + submitted
                            + " compiled=" + classCompiled
                            + " skippedClass=" + classSkipped
                            + " javaRestored=" + javaRestored
                            + " sourceMissing=" + sourceMissing
                            + " failed=" + failed);
                }

                if (!"COMPILED".equals(r.status) && !"SKIP_EXISTING_CLASS".equals(r.status)) {
                    System.out.println("[RESTORE-FAILURE] taskId=" + r.taskId
                            + " status=" + r.status
                            + " test=" + r.testSetName
                            + " reason=" + oneLine(r.message));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println("==================================================");
        System.out.println("[RESTORE SUMMARY]");
        System.out.println("submitted       = " + submitted);
        System.out.println("javaExists      = " + javaExists);
        System.out.println("javaRestored    = " + javaRestored);
        System.out.println("classCompiled   = " + classCompiled);
        System.out.println("classSkipped    = " + classSkipped);
        System.out.println("sourceMissing   = " + sourceMissing);
        System.out.println("failed          = " + failed);
    }

    private static RestoreResult restoreOneSafely(RestoreTask task) {
        try {
            return restoreOne(task);
        } catch (Throwable t) {
            RestoreResult r = new RestoreResult();
            if (task != null && task.request != null) {
                r.taskId = task.request.taskId;
                r.projectName = task.request.projectName;
                r.resultModuleHome = task.request.resultModuleHome;
                r.testSetName = task.request.testSetName;
            }
            r.status = "ERROR";
            r.message = oneLine(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
            writeRestoreResultQuietly(r);
            return r;
        }
    }

    private static boolean hasExpectedTypeDeclaration(Path javaFile, String testSetName) {
        if (javaFile == null || !Files.isRegularFile(javaFile) || isBlank(testSetName)) {
            return false;
        }

        try {
            String code = FileTextUtils.readUtf8(javaFile);
            String simpleName = fileBaseName(testSetName);

            Pattern p = Pattern.compile(
                    "\\b(class|interface|enum)\\s+" + Pattern.quote(simpleName) + "\\b"
            );

            return p.matcher(code).find();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean restoreJavaFromResponse(Path javaFile,
                                                   Path logRoot,
                                                   String testSetName,
                                                   int compileRounds,
                                                   RestoreResult result) {
        try {
            Path responseFile = findBestResponseFile(logRoot, testSetName, compileRounds);
            if (responseFile == null || !Files.isRegularFile(responseFile)) {
                return false;
            }

            String raw = FileTextUtils.readUtf8(responseFile);
            String code = GeneratedCodeExtractor.extractJavaCode(raw);
            code = GeneratedCodeExtractor.normalizeGeneratedTestCode(code, testSetName);

            if (code == null || code.trim().isEmpty()) {
                return false;
            }

            writeText(javaFile, code);

            if (result != null) {
                result.javaRestored = true;
                result.responseFile = responseFile.toAbsolutePath().normalize().toString();
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static RestoreResult restoreOne(RestoreTask task) throws Exception {
        Request request = task.request;
        SuccessRecord record = task.successRecord;

        RestoreResult result = new RestoreResult();
        result.taskId = request.taskId;
        result.projectName = request.projectName;
        result.sourceModuleHome = request.sourceModuleHome;
        result.resultModuleHome = request.resultModuleHome;
        result.targetClassName = request.targetClassName;
        result.methodSignature = request.methodSignature;
        result.mutantName = request.mutantName;
        result.testSetName = request.testSetName;

        Path llmRoot = Paths.get(request.resultModuleHome, MutationSystem.TESTSET_MODE_LLMS)
                .toAbsolutePath()
                .normalize();

        Path srcRoot = llmRoot.resolve("src");
        Path classesRoot = llmRoot.resolve("classes");
        Path reportRoot = llmRoot.resolve("report").resolve("restore_artifact_logs");
        Path logRoot = llmRoot.resolve("report").resolve("logs");

        Files.createDirectories(srcRoot);
        Files.createDirectories(classesRoot);
        Files.createDirectories(reportRoot);

        Path javaFile = resolveJavaFile(srcRoot, request.testSetName, record);
        Path classFile = expectedClassFile(classesRoot, request.testSetName);

        result.javaFile = javaFile.toAbsolutePath().normalize().toString();
        result.classFile = classFile.toAbsolutePath().normalize().toString();

        boolean javaExistsBefore = Files.isRegularFile(javaFile);
        boolean javaHasExpectedType = hasExpectedTypeDeclaration(javaFile, request.testSetName);

        /*
         * 三种情况要从 response 恢复 Java：
         * 1. 强制从 response 覆盖；
         * 2. java 文件不存在；
         * 3. java 文件存在，但里面没有预期的测试类定义。
         *
         * 第 3 种就是当前 BuildEvent_AOIS_6_Test.java 的情况：
         * 文件只有 package 行，javac 成功但不会产生 class。
         */
        boolean needRestoreJava =
                FORCE_RESTORE_JAVA_FROM_RESPONSE
                        || (!javaExistsBefore && RESTORE_MISSING_JAVA_FROM_RESPONSE)
                        || (javaExistsBefore && !javaHasExpectedType && RESTORE_MISSING_JAVA_FROM_RESPONSE);

        if (needRestoreJava) {
            boolean restored = restoreJavaFromResponse(
                    javaFile,
                    logRoot,
                    request.testSetName,
                    record.compileRounds,
                    result
            );

            if (restored) {
                javaHasExpectedType = hasExpectedTypeDeclaration(javaFile, request.testSetName);
            }
        }

        result.javaExists = Files.isRegularFile(javaFile);

        if (result.javaExists && !javaHasExpectedType) {
            result.status = "INVALID_JAVA_NO_EXPECTED_TYPE";
            result.message = "Java source exists but does not contain expected type declaration: "
                    + fileBaseName(request.testSetName);
            writeRestoreSummary(reportRoot, result);
            writeRestoreResult(result);
            return result;
        }

        /*
         * 已存在 .class 时默认跳过，但是 .java 仍然会在前面先补齐。
         */
        if (SKIP_EXISTING_CLASSES && Files.isRegularFile(classFile)) {
            result.classExists = true;
            result.status = "SKIP_EXISTING_CLASS";
            result.message = "";
            writeRestoreSummary(reportRoot, result);
            writeRestoreResult(result);
            return result;
        }

        if (!Files.isRegularFile(javaFile)) {
            result.status = "SOURCE_MISSING";
            result.message = "Source java not found and cannot be restored from response: "
                    + javaFile.toAbsolutePath().normalize();
            writeRestoreSummary(reportRoot, result);
            writeRestoreResult(result);
            return result;
        }

        long startedAt = System.currentTimeMillis();

        try {
            String projectCp = ProjectClasspathCache.getProjectClasspath(request.sourceModuleHome);

            GeneratedTestCompiler.compile(
                    request.sourceModuleHome,
                    javaFile,
                    classesRoot,
                    projectCp
            );

            result.compileMillis = System.currentTimeMillis() - startedAt;

            if (!Files.isRegularFile(classFile)) {
                throw new IllegalStateException(
                        "javac returned success but expected class file not found: "
                                + classFile.toAbsolutePath().normalize()
                );
            }

            result.classExists = true;
            result.status = "COMPILED";
            result.message = "";

        } catch (Throwable t) {
            result.compileMillis = System.currentTimeMillis() - startedAt;
            result.status = "COMPILE_FAILED";
            result.message = oneLine(String.valueOf(t.getMessage() == null ? t : t.getMessage()));

            Path errorFile = reportRoot.resolve(fileBaseName(request.testSetName) + "__restore_compile_error.txt");
            writeText(errorFile,
                    "testSetName=" + request.testSetName + System.lineSeparator()
                            + "javaFile=" + javaFile.toAbsolutePath().normalize() + System.lineSeparator()
                            + "classFile=" + classFile.toAbsolutePath().normalize() + System.lineSeparator()
                            + "sourceModuleHome=" + request.sourceModuleHome + System.lineSeparator()
                            + "resultModuleHome=" + request.resultModuleHome + System.lineSeparator()
                            + "error=" + String.valueOf(t.getMessage()));
        }

        writeRestoreSummary(reportRoot, result);
        writeRestoreResult(result);
        return result;
    }

    private static Path resolveJavaFile(Path srcRoot, String testSetName, SuccessRecord record) {
        if (record != null && !isBlank(record.testJavaFile)) {
            try {
                Path p = Paths.get(record.testJavaFile).toAbsolutePath().normalize();
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (Throwable ignored) {
            }
        }
        return toJavaFile(srcRoot, testSetName);
    }

    private static Path expectedClassFile(Path classesRoot, String testSetName) {
        return classesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class");
    }

    private static Path findBestResponseFile(Path logRoot, String testSetName, int compileRounds) {
        if (logRoot == null || !Files.isDirectory(logRoot) || isBlank(testSetName)) {
            return null;
        }

        String simple = fileBaseName(testSetName);

        /*
         * If compileRounds is known:
         * attempt = compileRounds - 1.
         * In the generator, rounds++ happens before each attempt.
         */
        if (compileRounds > 0) {
            Path exact = logRoot.resolve(simple + "__response_" + (compileRounds - 1) + ".json");
            if (Files.isRegularFile(exact)) {
                return exact;
            }
        }

        final Pattern p = Pattern.compile(Pattern.quote(simple) + "__response_(\\d+)\\.json");
        List<Path> candidates = new ArrayList<Path>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logRoot, simple + "__response_*.json")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    candidates.add(file);
                }
            }
        } catch (IOException ignored) {
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Collections.sort(candidates, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return Integer.compare(responseAttemptOf(p, b), responseAttemptOf(p, a));
            }
        });

        return candidates.get(0);
    }

    private static int responseAttemptOf(Pattern pattern, Path p) {
        if (p == null || p.getFileName() == null) {
            return -1;
        }
        Matcher m = pattern.matcher(p.getFileName().toString());
        if (!m.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String inferTestSetNameFromJavaFile(String resultModuleHome, String testJavaFile) {
        if (isBlank(resultModuleHome) || isBlank(testJavaFile)) {
            return "";
        }

        try {
            Path srcRoot = Paths.get(
                    resultModuleHome,
                    MutationSystem.TESTSET_MODE_LLMS,
                    "src"
            ).toAbsolutePath().normalize();

            Path javaFile = Paths.get(testJavaFile).toAbsolutePath().normalize();
            if (!javaFile.startsWith(srcRoot)) {
                return "";
            }

            String rel = srcRoot.relativize(javaFile).toString();
            if (rel.endsWith(".java")) {
                rel = rel.substring(0, rel.length() - ".java".length());
            }

            return rel.replace(File.separatorChar, '.')
                    .replace('/', '.')
                    .replace('\\', '.');

        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void writeRestoreSummary(Path reportRoot, RestoreResult r) {
        try {
            Files.createDirectories(reportRoot);
            Path file = reportRoot.resolve(fileBaseName(r.testSetName) + "__restore_summary.txt");

            String text = "taskId=" + safe(r.taskId) + System.lineSeparator()
                    + "projectName=" + safe(r.projectName) + System.lineSeparator()
                    + "targetClassName=" + safe(r.targetClassName) + System.lineSeparator()
                    + "methodSignature=" + safe(r.methodSignature) + System.lineSeparator()
                    + "mutantName=" + safe(r.mutantName) + System.lineSeparator()
                    + "testSetName=" + safe(r.testSetName) + System.lineSeparator()
                    + "status=" + safe(r.status) + System.lineSeparator()
                    + "javaExists=" + r.javaExists + System.lineSeparator()
                    + "javaRestored=" + r.javaRestored + System.lineSeparator()
                    + "classExists=" + r.classExists + System.lineSeparator()
                    + "compileMillis=" + r.compileMillis + System.lineSeparator()
                    + "javaFile=" + safe(r.javaFile) + System.lineSeparator()
                    + "classFile=" + safe(r.classFile) + System.lineSeparator()
                    + "responseFile=" + safe(r.responseFile) + System.lineSeparator()
                    + "message=" + safe(r.message) + System.lineSeparator();

            writeText(file, text);

        } catch (Throwable ignored) {
        }
    }

    private static void writeRestoreResultQuietly(RestoreResult r) {
        try {
            writeRestoreResult(r);
        } catch (Throwable ignored) {
        }
    }

    private static void writeRestoreResult(RestoreResult r) throws IOException {
        if (r == null || isBlank(r.resultModuleHome)) {
            return;
        }

        Path jsonl = Paths.get(
                r.resultModuleHome,
                MutationSystem.TESTSET_MODE_LLMS,
                "report",
                "llm_restore_artifact_results.jsonl"
        ).toAbsolutePath().normalize();

        Files.createDirectories(jsonl.getParent());

        JSONObject obj = new JSONObject();
        obj.put("taskId", safe(r.taskId));
        obj.put("projectName", safe(r.projectName));
        obj.put("sourceModuleHome", safe(r.sourceModuleHome));
        obj.put("resultModuleHome", safe(r.resultModuleHome));
        obj.put("targetClassName", safe(r.targetClassName));
        obj.put("methodSignature", safe(r.methodSignature));
        obj.put("mutantName", safe(r.mutantName));
        obj.put("testSetName", safe(r.testSetName));
        obj.put("status", safe(r.status));
        obj.put("javaExists", r.javaExists);
        obj.put("javaRestored", r.javaRestored);
        obj.put("classExists", r.classExists);
        obj.put("compileMillis", r.compileMillis);
        obj.put("javaFile", safe(r.javaFile));
        obj.put("classFile", safe(r.classFile));
        obj.put("responseFile", safe(r.responseFile));
        obj.put("message", safe(r.message));

        synchronized (JSONL_LOCK) {
            Files.write(
                    jsonl,
                    (obj.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static final class Request {
        String taskId;
        String projectName;
        String sourceModuleHome;
        String resultModuleHome;
        String targetClassName;
        String methodSignature;
        String mutantName;
        String testSetName;
    }

    private static final class SuccessRecord {
        String testSetName;
        String testJavaFile;
        boolean skippedExistingCompiledTest;
        int compileRounds = -1;
        int rawLineIndex;
    }

    private static final class RestoreTask {
        Request request;
        SuccessRecord successRecord;
    }

    private static final class RestoreResult {
        String taskId = "";
        String projectName = "";
        String sourceModuleHome = "";
        String resultModuleHome = "";
        String targetClassName = "";
        String methodSignature = "";
        String mutantName = "";
        String testSetName = "";

        String status = "NOT_RUN";
        String message = "";

        boolean javaExists;
        boolean javaRestored;
        boolean classExists;

        long compileMillis;

        String javaFile = "";
        String classFile = "";
        String responseFile = "";
    }
}