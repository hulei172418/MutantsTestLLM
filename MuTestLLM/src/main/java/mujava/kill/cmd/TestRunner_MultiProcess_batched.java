package mujava.kill.cmd;

import mujava.MutationSystem;
import mujava.test.TestResult;

import static mujava.util.ExcelUtils.readExcel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.OutputStreamWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多进程批量执行版本：主进程只负责调度，original / inspect / mutant 都在 worker 子进程中执行，
 * 每个 worker 拥有独立 JVM，以隔离静态状态与类加载器污染，适合批量实验。
 */
public class TestRunner_MultiProcess_batched {

    /**
     * 统一在这里改参数，不再依赖 VM options / -D 命令行参数。
     * 以后只需要修改这一个配置块，然后直接运行 main 即可。
     */
    private static final class InCodeConfig {
        private static final String EXCEL_PATH = "../MutantParse/data/mutant_statistic_total - 副本2.xlsx";
        private static final int BASE_TIMEOUT_MILLIS = 1 * 1000;
        private static final String TEST_MODE = "evosuite"; // origin / evosuite / llm ...

        private static final String CONFIG_PATH_OVERRIDE = ""; // 例如: "E:/PHD/testJava/mujava.config"，留空则自动寻找
        private static final boolean AUTO_DISCOVER_CONFIG_PATH = true;

        private static final String WORKER_XMS = "128m";
        private static final String WORKER_XMX = "768m";
        private static final long KILL_WAIT_MILLIS = 2000L;

        private static final int MAX_WORKERS = 2;
        private static final int WORKER_RECYCLE_EVERY_OVERRIDE = 0;
        private static final int GLOBAL_WORKER_RECYCLE_EVERY = 20;
        private static final int MAX_IN_FLIGHT_MULTIPLIER = 1;
        private static final long WORKER_ACQUIRE_TIMEOUT_MILLIS = 120000L;
        private static final long STALL_ABORT_MILLIS = 1L * 10L * 1000L;
        private static final int COMPLETION_POLL_SECONDS = 30;
        private static final int METHOD_PROGRESS_EVERY = 25;
        private static final int METHOD_BATCH_SIZE_OVERRIDE = 10;
        private static final boolean RECYCLE_POOL_EVERY_BATCH = true;
        private static final int SUITE_TIMEOUT_MAX_MILLIS = 120000;
        private static final long MUTANT_TOTAL_TIMEOUT_MILLIS = 90L * 1000L;

        private static final int ORIGINAL_BASELINE_TIMEOUT_MULTIPLIER = 3;
        private static final int ORIGINAL_BASELINE_TIMEOUT_BUFFER_MILLIS = 2000;
        private static final int ORIGINAL_BASELINE_TIMEOUT_MIN_MILLIS = 4000;
        private static final int ORIGINAL_BASELINE_TIMEOUT_MAX_MILLIS = 15000;
    }

    private static final String WORKER_CLASS = "mujava.cmd.MutantWorker8_MultiProcess";
    private static final String WORKER_XMS = InCodeConfig.WORKER_XMS;
    private static final String WORKER_XMX = InCodeConfig.WORKER_XMX;
    private static final long KILL_WAIT_MILLIS = InCodeConfig.KILL_WAIT_MILLIS;
    private static final int MAX_WORKERS = Math.max(1, InCodeConfig.MAX_WORKERS);
    private static final int WORKER_RECYCLE_EVERY_OVERRIDE = Math.max(0, InCodeConfig.WORKER_RECYCLE_EVERY_OVERRIDE);
    private static final int GLOBAL_WORKER_RECYCLE_EVERY = Math.max(0, InCodeConfig.GLOBAL_WORKER_RECYCLE_EVERY);
    private static final int MAX_IN_FLIGHT_MULTIPLIER = Math.max(1, InCodeConfig.MAX_IN_FLIGHT_MULTIPLIER);
    private static final long WORKER_ACQUIRE_TIMEOUT_MILLIS = Math.max(1000L, InCodeConfig.WORKER_ACQUIRE_TIMEOUT_MILLIS);
    private static final long STALL_ABORT_MILLIS = Math.max(60000L, InCodeConfig.STALL_ABORT_MILLIS);
    private static final int COMPLETION_POLL_SECONDS = Math.max(5, InCodeConfig.COMPLETION_POLL_SECONDS);
    private static final int METHOD_PROGRESS_EVERY = Math.max(1, InCodeConfig.METHOD_PROGRESS_EVERY);
    private static final int METHOD_BATCH_SIZE_OVERRIDE = Math.max(0, InCodeConfig.METHOD_BATCH_SIZE_OVERRIDE);
    private static final boolean RECYCLE_POOL_EVERY_BATCH = InCodeConfig.RECYCLE_POOL_EVERY_BATCH;
    private static final int SUITE_TIMEOUT_MAX_MILLIS = Math.max(0, InCodeConfig.SUITE_TIMEOUT_MAX_MILLIS);
    private static final long MUTANT_TOTAL_TIMEOUT_MILLIS = Math.max(1000L, InCodeConfig.MUTANT_TOTAL_TIMEOUT_MILLIS);
    private static final int ORIGINAL_BASELINE_TIMEOUT_MULTIPLIER = Math.max(1, InCodeConfig.ORIGINAL_BASELINE_TIMEOUT_MULTIPLIER);
    private static final int ORIGINAL_BASELINE_TIMEOUT_BUFFER_MILLIS = Math.max(0, InCodeConfig.ORIGINAL_BASELINE_TIMEOUT_BUFFER_MILLIS);
    private static final int ORIGINAL_BASELINE_TIMEOUT_MIN_MILLIS = Math.max(1000, InCodeConfig.ORIGINAL_BASELINE_TIMEOUT_MIN_MILLIS);
    private static final int ORIGINAL_BASELINE_TIMEOUT_MAX_MILLIS = Math.max(ORIGINAL_BASELINE_TIMEOUT_MIN_MILLIS, InCodeConfig.ORIGINAL_BASELINE_TIMEOUT_MAX_MILLIS);

    private static final String LOG_DIR_NAME = "mujava_logs";
    private static final String ERROR_LOG_NAME = "test_runner_errors.log";
    private static final String WORKER_ROOT_NAME = "worker_runs";
    private static final String CHECKPOINT_DIR_NAME = "checkpoints";
    private static final Map<String, Integer> SUITE_TIMEOUT_CACHE = Collections.synchronizedMap(new LinkedHashMap<String, Integer>());
    private static final Map<String, Long> ORIGINAL_SUITE_ELAPSED_MS = Collections.synchronizedMap(new LinkedHashMap<String, Long>());
    private static final String WORKER_PROTOCOL_EOF = "__MUJAVA_WORKER_PROTOCOL_EOF__";

    private static final Set<WorkerServerPool> ACTIVE_WORKER_POOLS =
            Collections.synchronizedSet(new LinkedHashSet<WorkerServerPool>());
    private static final Set<ExecutorService> ACTIVE_EXECUTORS =
            Collections.synchronizedSet(new LinkedHashSet<ExecutorService>());
    private static volatile boolean SHUTDOWN_HOOK_INSTALLED = false;

    static {
        installShutdownHookIfNeeded();
    }

    public static TestResult runTraditionalTest(String targetClassName,
                                                String testSetName,
                                                int timeoutMillis,
                                                String sourceModuleHome,
                                                String resultModuleHome) {
        List<String> testSetNames = new ArrayList<String>();
        testSetNames.add(testSetName);
        return runTraditionalTest(targetClassName, testSetNames, timeoutMillis, sourceModuleHome, resultModuleHome);
    }

    public static TestResult runTraditionalTest(String targetClassName,
                                                List<String> testSetNames,
                                                int timeoutMillis,
                                                String sourceModuleHome,
                                                String resultModuleHome) {
        ExecutorService pool = null;
        WorkerServerPool serverPool = null;
        String oldResultHomeProp = System.getProperty("mujava.result.module.home");
        try {
            setTestSetMode(currentTestSetMode());

            if (resultModuleHome != null && !resultModuleHome.trim().isEmpty()) {
                System.setProperty("mujava.result.module.home", resultModuleHome);
            } else {
                System.clearProperty("mujava.result.module.home");
            }

            MuJavaRuntimeSupport.initializeProject(sourceModuleHome, resultModuleHome, targetClassName);

            LinkedHashMap<String, String> originalResults = new LinkedHashMap<String, String>();
            List<String> junitTests = new ArrayList<String>();

            boolean hasAnyOriginalSuccess = false;
            StringBuilder originalFailMsg = new StringBuilder();

            for (String testSetName : testSetNames) {
                WorkerRunResult originalRun = runOriginalWorker(
                        sourceModuleHome, resultModuleHome, targetClassName, testSetName, timeoutMillis);
                if (!originalRun.success) {
                    if (isIgnorableCandidateFailure(originalRun)) {
                        System.out.println("[SKIP  ] original candidate ignored: " + testSetName
                                + " ; reason=" + oneLine(originalRun.message));
                        continue;
                    }

                    originalFailMsg.append("[")
                            .append(testSetName)
                            .append("] ")
                            .append(originalRun.message)
                            .append(" ; logFile=")
                            .append(originalRun.logFile)
                            .append(System.lineSeparator());
                    continue;
                }

                hasAnyOriginalSuccess = true;
                mergeOriginalRun(originalRun, originalResults, junitTests);
            }

            if (!hasAnyOriginalSuccess) {
                throw new IllegalStateException(
                        "Original test execution failed for all candidate test sets of target "
                                + targetClassName + System.lineSeparator()
                                + originalFailMsg.toString()
                );
            }

            Map<String, List<String>> testsByTestSet = buildTestsByTestSet(junitTests, testSetNames);

            TestResult tr = new TestResult();
            tr.setMutants();

            LinkedHashMap<String, StringBuilder> finalTestResults = new LinkedHashMap<String, StringBuilder>();
            LinkedHashMap<String, StringBuilder> finalMutantResults = new LinkedHashMap<String, StringBuilder>();
            for (String testName : junitTests) {
                finalTestResults.put(testName, new StringBuilder());
            }

            List<String> methodSignatures =
                    MuJavaRuntimeSupport.readMethodSignatures(MutationSystem.TRADITIONAL_MUTANT_PATH);

            if (methodSignatures.isEmpty()) {
                String msg = "[WARN] method_list.txt 为空或不存在: " + MutationSystem.TRADITIONAL_MUTANT_PATH;
                logError(resultModuleHome, msg, null);
                System.err.println(msg);
            }

            pool = createTrackedFixedThreadPool(MAX_WORKERS);

            for (String methodSignature : methodSignatures) {
                MutationSystem.METHOD_SIGNATURE = methodSignature;
                String methodPath = MutationSystem.TRADITIONAL_MUTANT_PATH + File.separator + methodSignature;
                List<String> mutants = MuJavaRuntimeSupport.listMutantDirectories(methodPath);

                if (mutants.isEmpty()) {
                    String msg = "No mutants have been generated for the method "
                            + methodSignature + " of the class " + MutationSystem.CLASS_NAME;
                    logError(resultModuleHome, msg, null);
                    System.err.println(msg);
                    continue;
                }

                final int recycleEveryForMethod = computeRecycleEveryForMethod(mutants.size(), MAX_WORKERS);
                final int batchSize = computeMethodBatchSize(mutants.size());
                final int batchCount = Math.max(1, (mutants.size() + batchSize - 1) / batchSize);

                System.out.println("\n[Method] " + methodSignature + "  mutants=" + mutants.size()
                        + "  persistentWorkers=" + MAX_WORKERS
                        + "  recycleEvery=" + recycleEveryForMethod
                        + "  globalRecycleEvery=" + GLOBAL_WORKER_RECYCLE_EVERY
                        + "  batchSize=" + batchSize
                        + "  batchCount=" + batchCount);

                long methodStartedAt = System.currentTimeMillis();
                int methodCompleted = 0;

                for (int batchStart = 0, batchIndex = 0; batchStart < mutants.size(); batchStart += batchSize, batchIndex++) {
                    int batchEnd = Math.min(mutants.size(), batchStart + batchSize);
                    List<String> batchMutants = new ArrayList<String>(mutants.subList(batchStart, batchEnd));

                    for (String mutantName : batchMutants) {
                        tr.mutants.add(mutantName);
                    }

                    if (serverPool == null || RECYCLE_POOL_EVERY_BATCH) {
                        if (serverPool != null) {
                            serverPool.close();
                            unregisterWorkerPool(serverPool);
                        }
                        serverPool = startPersistentWorkerPool(sourceModuleHome, resultModuleHome, targetClassName);
                    }

                    final int maxInFlight = Math.max(1, Math.min(batchMutants.size(), MAX_WORKERS * MAX_IN_FLIGHT_MULTIPLIER));
                    CompletionService<MutantTaskResult> completionService =
                            new ExecutorCompletionService<MutantTaskResult>(pool);

                    System.out.println("  [BATCH ] method=" + methodSignature
                            + " index=" + (batchIndex + 1) + "/" + batchCount
                            + " size=" + batchMutants.size()
                            + " range=" + (batchStart + 1) + "-" + batchEnd
                            + " maxInFlight=" + maxInFlight);

                    int submitted = 0;
                    int completed = 0;
                    long lastProgressAt = System.currentTimeMillis();
                    boolean batchRecoveredFromStall = false;
                    LinkedHashMap<Future<MutantTaskResult>, String> pendingFutures = new LinkedHashMap<Future<MutantTaskResult>, String>();
                    writeMethodCheckpoint(resultModuleHome, targetClassName, methodSignature, batchIndex + 1, batchCount,
                            mutants.size(), methodCompleted, "BATCH_START", "range=" + (batchStart + 1) + "-" + batchEnd);

                    while (submitted < maxInFlight) {
                        final String mutantName = batchMutants.get(submitted++);
                        Future<MutantTaskResult> future = submitMutantTask(completionService,
                                serverPool,
                                sourceModuleHome,
                                resultModuleHome,
                                targetClassName,
                                testSetNames,
                                methodSignature,
                                mutantName,
                                timeoutMillis,
                                originalResults,
                                testsByTestSet,
                                junitTests,
                                recycleEveryForMethod);
                        pendingFutures.put(future, mutantName);
                    }

                    while (completed < batchMutants.size()) {
                        Future<MutantTaskResult> future;
                        try {
                            future = completionService.poll(COMPLETION_POLL_SECONDS, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while polling mutant workers", e);
                        }

                        if (future == null) {
                            long stallMillis = System.currentTimeMillis() - lastProgressAt;
                            if (stallMillis >= STALL_ABORT_MILLIS) {
                                batchRecoveredFromStall = true;
                                System.out.println("  [STALL ] method=" + methodSignature
                                        + " batch=" + (batchIndex + 1)
                                        + " completed=" + completed + "/" + batchMutants.size()
                                        + " pending=" + pendingFutures.size()
                                        + " stallMs=" + stallMillis
                                        + " ; mark unfinished mutants as timeout and continue");
                                writeMethodCheckpoint(resultModuleHome, targetClassName, methodSignature, batchIndex + 1, batchCount,
                                        mutants.size(), methodCompleted, "BATCH_STALL_RECOVER",
                                        "pending=" + pendingFutures.size() + ", stallMs=" + stallMillis);

                                List<Map.Entry<Future<MutantTaskResult>, String>> stuckEntries =
                                        new ArrayList<Map.Entry<Future<MutantTaskResult>, String>>(pendingFutures.entrySet());
                                for (Map.Entry<Future<MutantTaskResult>, String> entry : stuckEntries) {
                                    Future<MutantTaskResult> stuckFuture = entry.getKey();
                                    String stuckMutantName = entry.getValue();
                                    if (stuckFuture != null) {
                                        stuckFuture.cancel(true);
                                    }

                                    MutantTaskResult timeoutTaskResult = buildTimedOutTaskResult(
                                            stuckMutantName,
                                            "batch_stall_abort: no mutant completed in " + stallMillis
                                                    + " ms for method " + methodSignature
                                                    + " batch=" + (batchIndex + 1));

                                    completed++;
                                    methodCompleted++;
                                    mergeTaskResult(timeoutTaskResult, tr, finalTestResults, finalMutantResults, originalResults, junitTests);
                                }
                                pendingFutures.clear();
                                lastProgressAt = System.currentTimeMillis();

                                if (serverPool != null) {
                                    serverPool.close();
                                    unregisterWorkerPool(serverPool);
                                    serverPool = null;
                                }
                                if (pool != null) {
                                    shutdownExecutor(pool);
                                }
                                pool = createTrackedFixedThreadPool(MAX_WORKERS);
                                break;
                            }
                            continue;
                        }

                        String completedMutantName = pendingFutures.remove(future);

                        MutantTaskResult taskResult;
                        try {
                            taskResult = future.get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while waiting for mutant workers", e);
                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause() == null ? e : e.getCause();
                            logError(resultModuleHome,
                                    "[EXECUTION-FAIL] mutant future failed: " + cause.getMessage(), cause);
                            taskResult = buildFailedTaskResult(
                                    completedMutantName == null ? ("UNKNOWN_" + methodCompleted) : completedMutantName,
                                    cause,
                                    junitTests,
                                    originalResults,
                                    timeoutMillis);
                        }

                        completed++;
                        methodCompleted++;
                        lastProgressAt = System.currentTimeMillis();
                        mergeTaskResult(taskResult, tr, finalTestResults, finalMutantResults, originalResults, junitTests);

                        if (methodCompleted % METHOD_PROGRESS_EVERY == 0 || methodCompleted == mutants.size()) {
                            System.out.println("  [PROG  ] method=" + methodSignature
                                    + " completed=" + methodCompleted + "/" + mutants.size()
                                    + " submitted=" + (batchStart + submitted));
                            writeMethodCheckpoint(resultModuleHome, targetClassName, methodSignature, batchIndex + 1, batchCount,
                                    mutants.size(), methodCompleted, "RUNNING", "submitted=" + (batchStart + submitted));
                        }

                        if (submitted < batchMutants.size()) {
                            final String mutantName = batchMutants.get(submitted++);
                            Future<MutantTaskResult> nextFuture = submitMutantTask(completionService,
                                    serverPool,
                                    sourceModuleHome,
                                    resultModuleHome,
                                    targetClassName,
                                    testSetNames,
                                    methodSignature,
                                    mutantName,
                                    timeoutMillis,
                                    originalResults,
                                    testsByTestSet,
                                    junitTests,
                                    recycleEveryForMethod);
                            pendingFutures.put(nextFuture, mutantName);
                        }
                    }

                    writeMethodCheckpoint(resultModuleHome, targetClassName, methodSignature, batchIndex + 1, batchCount,
                            mutants.size(), methodCompleted,
                            batchRecoveredFromStall ? "BATCH_DONE_WITH_TIMEOUTS" : "BATCH_DONE",
                            "range=" + (batchStart + 1) + "-" + batchEnd);

                    if (RECYCLE_POOL_EVERY_BATCH && serverPool != null) {
                        serverPool.close();
                        unregisterWorkerPool(serverPool);
                        serverPool = null;
                    }
                }

                System.out.println("  [METHOD-DONE] method=" + methodSignature
                        + " completed=" + methodCompleted + "/" + mutants.size()
                        + " elapsedMs=" + (System.currentTimeMillis() - methodStartedAt));
                writeMethodCheckpoint(resultModuleHome, targetClassName, methodSignature, batchCount, batchCount,
                        mutants.size(), methodCompleted, "DONE", "elapsedMs=" + (System.currentTimeMillis() - methodStartedAt));
            }

            deduplicateMutantLists(tr);

            int killedNum = tr.killed_mutants.size();
            int liveNum = tr.live_mutants.size();
            int total = killedNum + liveNum;
            tr.mutant_score = (total == 0) ? 0.0 : (killedNum * 100.0) / total;
            tr.test_results = toFinalResultMap(finalTestResults, junitTests);
            tr.mutant_results = toFinalResultMap(finalMutantResults, tr.mutants);

            System.out.println("test report: " + tr.test_results);
            System.out.println("mutant report: " + tr.mutant_results);
            return tr;
        } catch (Exception e) {
            logError(resultModuleHome, "[ERROR] runTraditionalTest failed: " + e.getMessage(), e);
            System.err.println("[ERROR] runTraditionalTest failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            if (oldResultHomeProp != null && !oldResultHomeProp.trim().isEmpty()) {
                System.setProperty("mujava.result.module.home", oldResultHomeProp);
            } else {
                System.clearProperty("mujava.result.module.home");
            }

            if (pool != null) {
                shutdownExecutor(pool);
            }

            if (serverPool != null) {
                serverPool.close();
                unregisterWorkerPool(serverPool);
            }
        }
    }

    private static boolean isIgnorableCandidateFailure(WorkerRunResult run) {
        if (run == null || run.success) {
            return false;
        }

        String msg = run.message;
        if (msg == null || msg.trim().isEmpty()) {
            return false;
        }

        String s = oneLine(msg).toLowerCase(Locale.ROOT);

        return s.contains("classnotfoundexception")
                || s.contains("filenotfoundexception")
                || s.contains("noclassdeffounderror")
                || s.contains("loadtestclass")
                || s.contains("no tests discovered");
    }

    private static void mergeOriginalRun(WorkerRunResult originalRun,
                                         Map<String, String> originalResults,
                                         List<String> junitTests) {
        for (String testName : originalRun.testOrder) {
            if (!junitTests.contains(testName)) {
                junitTests.add(testName);
            }
        }
        originalResults.putAll(originalRun.results);
    }


    private static int defaultWorkerCount() {
        int cpu = Runtime.getRuntime().availableProcessors();
        if (cpu <= 2) {
            return 1;
        }
        return Math.min(6, Math.max(2, cpu / 2));
    }

    private static WorkerServerPool startPersistentWorkerPool(String sourceModuleHome,
                                                              String resultModuleHome,
                                                              String targetClassName) throws Exception {
        WorkerServerPool pool = new WorkerServerPool(sourceModuleHome, resultModuleHome, targetClassName, MAX_WORKERS);
        pool.start();
        registerWorkerPool(pool);
        return pool;
    }

    private static Future<MutantTaskResult> submitMutantTask(CompletionService<MutantTaskResult> completionService,
                                                             WorkerServerPool serverPool,
                                                             String sourceModuleHome,
                                                             String resultModuleHome,
                                                             String targetClassName,
                                                             List<String> testSetNames,
                                                             String methodSignature,
                                                             final String mutantName,
                                                             int timeoutMillis,
                                                             Map<String, String> originalResults,
                                                             Map<String, List<String>> testsByTestSet,
                                                             List<String> junitTests,
                                                             int recycleEveryForMethod) {
        final WorkerServerPool finalServerPool = serverPool;
        final String finalMethodSignature = methodSignature;
        return completionService.submit(new Callable<MutantTaskResult>() {
            @Override
            public MutantTaskResult call() {
                try {
                    return executeOneMutant(
                            finalServerPool,
                            sourceModuleHome,
                            resultModuleHome,
                            targetClassName,
                            testSetNames,
                            finalMethodSignature,
                            mutantName,
                            timeoutMillis,
                            originalResults,
                            testsByTestSet,
                            junitTests,
                            recycleEveryForMethod
                    );
                } catch (Throwable t) {
                    logError(resultModuleHome,
                            "[TASK-FAIL] method=" + finalMethodSignature + ", mutant=" + mutantName
                                    + ", message=" + (t == null ? "unknown" : oneLine(String.valueOf(t.getMessage()))),
                            t);
                    return buildFailedTaskResult(mutantName, t, junitTests, originalResults, timeoutMillis);
                }
            }
        });
    }


    private static MutantTaskResult executeOneMutant(WorkerServerPool serverPool,
                                                     String sourceModuleHome,
                                                     String resultModuleHome,
                                                     String targetClassName,
                                                     List<String> testSetNames,
                                                     String methodSignature,
                                                     String mutantName,
                                                     int timeoutMillis,
                                                     Map<String, String> originalResults,
                                                     Map<String, List<String>> testsByTestSet,
                                                     List<String> junitTests,
                                                     int recycleEveryForMethod) throws Exception {
        LinkedHashMap<String, String> mergedResults = new LinkedHashMap<String, String>();

        WorkerRunResult lastRun = null;
        WorkerRunResult displayRun = null;
        boolean killed = false;
        long mutantStartedAt = System.currentTimeMillis();

        for (String testSetName : testSetNames) {
            List<String> perClassTests = testsByTestSet.get(testSetName);
            if (perClassTests == null || perClassTests.isEmpty()) {
                continue;
            }

            long elapsedMillis = System.currentTimeMillis() - mutantStartedAt;
            long remainingTotalMillis = MUTANT_TOTAL_TIMEOUT_MILLIS - elapsedMillis;
            if (remainingTotalMillis <= 0L) {
                WorkerRunResult totalTimeoutRun = buildMutantTotalTimedOutRunResult(
                        mutantName,
                        testSetName,
                        MUTANT_TOTAL_TIMEOUT_MILLIS,
                        elapsedMillis
                );
                lastRun = totalTimeoutRun;
                displayRun = totalTimeoutRun;
                System.out.println("    [SKIP-WORKER] " + mutantName
                        + " / " + testSetName
                        + " ; reason=" + oneLine(totalTimeoutRun.message));

                Map<String, String> partialResults = normalizeMutantResults(totalTimeoutRun, perClassTests, timeoutMillis);
                mergedResults.putAll(partialResults);
                if (hasKillingDifference(originalResults, mergedResults, junitTests)) {
                    killed = true;
                }
                break;
            }

            WorkerRunResult mutantRun = runMutantWorkerPersistent(
                    serverPool,
                    sourceModuleHome,
                    resultModuleHome,
                    targetClassName,
                    testSetName,
                    methodSignature,
                    mutantName,
                    timeoutMillis,
                    remainingTotalMillis,
                    perClassTests,
                    recycleEveryForMethod
            );
            lastRun = mutantRun;

            if (!mutantRun.success) {
                if (isIgnorableCandidateFailure(mutantRun)) {
                    System.out.println("    [SKIP-CANDIDATE] " + mutantName
                            + " / " + testSetName
                            + " ; reason=" + oneLine(mutantRun.message));
                    continue;
                }

                if (displayRun == null || mutantRun.timedOut) {
                    displayRun = mutantRun;
                }
                System.out.println("    [SKIP-WORKER] " + mutantName
                        + " / " + testSetName
                        + " ; reason=" + oneLine(mutantRun.message));

                Map<String, String> partialResults = normalizeMutantResults(mutantRun, perClassTests, timeoutMillis);
                mergedResults.putAll(partialResults);
                if (hasKillingDifference(originalResults, mergedResults, junitTests)) {
                    killed = true;
                    break;
                }

                if (mutantRun.timedOut && isMutantTotalTimeout(mutantRun.message)) {
                    break;
                }
                continue;
            }

            displayRun = mutantRun;

            Map<String, String> partialResults = normalizeMutantResults(mutantRun, perClassTests, timeoutMillis);
            mergedResults.putAll(partialResults);

            if (hasKillingDifference(originalResults, mergedResults, junitTests)) {
                killed = true;
                break;
            }
        }

        MutantTaskResult r = new MutantTaskResult();
        r.mutantName = mutantName;
        r.workerRun = displayRun != null ? displayRun : (lastRun == null ? new WorkerRunResult() : lastRun);
        r.mutantResults = mergedResults;
        r.killed = killed;
        return r;
    }

    private static boolean hasKillingDifference(Map<String, String> originalResults,
                                                Map<String, String> currentResults,
                                                List<String> junitTests) {
        for (String testName : junitTests) {
            String original = originalResults.get(testName);
            String current = currentResultOrOriginal(testName, originalResults, currentResults);
            if (isKillingDifference(original, current)) {
                return true;
            }
        }
        return false;
    }

    private static String currentResultOrOriginal(String testName,
                                                  Map<String, String> originalResults,
                                                  Map<String, String> currentResults) {
        if (currentResults != null) {
            String value = currentResults.get(testName);
            if (value != null) {
                return value;
            }
        }
        return originalResults.get(testName);
    }

    private static WorkerRunResult buildMutantTotalTimedOutRunResult(String mutantName,
                                                                     String testSetName,
                                                                     long timeoutMillis,
                                                                     long elapsedMillis) {
        WorkerRunResult result = new WorkerRunResult();
        result.success = false;
        result.timedOut = true;
        result.message = "mutant_total_timeout: more than " + timeoutMillis
                + " milliseconds (elapsed=" + elapsedMillis + ", testSet=" + safeStr(testSetName) + ")";
        result.command = Arrays.asList("mutant-total-timeout", safeStr(mutantName), safeStr(testSetName));
        return result;
    }

    private static boolean isMutantTotalTimeout(String message) {
        if (message == null) {
            return false;
        }
        return oneLine(message).toLowerCase(Locale.ROOT).contains("mutant_total_timeout");
    }

    private static String suiteTimeoutCacheKey(String sourceModuleHome,
                                               String targetClassName,
                                               String testSetName,
                                               int baseTimeoutMillis) {
        return safeStr(sourceModuleHome) + "||" + safeStr(targetClassName) + "||" + safeStr(testSetName)
                + "||" + Math.max(1, baseTimeoutMillis);
    }

    private static String originalSuiteElapsedKey(String targetClassName,
                                                  String testSetName) {
        return safeStr(targetClassName) + "##" + safeStr(testSetName);
    }

    private static int computeBaselineTimeoutFromOriginalElapsed(long originalElapsedMs) {
        long computed = originalElapsedMs * (long) ORIGINAL_BASELINE_TIMEOUT_MULTIPLIER
                + (long) ORIGINAL_BASELINE_TIMEOUT_BUFFER_MILLIS;
        computed = Math.max((long) ORIGINAL_BASELINE_TIMEOUT_MIN_MILLIS, computed);
        computed = Math.min((long) ORIGINAL_BASELINE_TIMEOUT_MAX_MILLIS, computed);
        if (SUITE_TIMEOUT_MAX_MILLIS > 0) {
            computed = Math.min((long) SUITE_TIMEOUT_MAX_MILLIS, computed);
        }
        return (int) Math.max(1000L, computed);
    }

    private static int suiteTimeoutMillis(String sourceModuleHome,
                                          String resultModuleHome,
                                          String targetClassName,
                                          String testSetName,
                                          int baseTimeoutMillis) {
        String cacheKey = suiteTimeoutCacheKey(sourceModuleHome, targetClassName, testSetName, baseTimeoutMillis);
        Integer cached = SUITE_TIMEOUT_CACHE.get(cacheKey);
        if (cached != null && cached.intValue() > 0) {
            return cached.intValue();
        }

        Long originalElapsedMs = ORIGINAL_SUITE_ELAPSED_MS.get(originalSuiteElapsedKey(targetClassName, testSetName));
        if (originalElapsedMs != null && originalElapsedMs.longValue() > 0L) {
            int computed = computeBaselineTimeoutFromOriginalElapsed(originalElapsedMs.longValue());
            SUITE_TIMEOUT_CACHE.put(cacheKey, computed);
            System.out.println("[TIMEOUT-BASELINE] " + testSetName
                    + " originalElapsedMs=" + originalElapsedMs
                    + " => workerTimeout=" + computed);
            return computed;
        }

        int fallbackTimeout = Math.max(10000, baseTimeoutMillis * 10);
        int inspectTimeout = Math.max(10000, Math.min(12000, baseTimeoutMillis * 2));

        Path runDir = null;
        boolean keepDir = true;
        try {
            runDir = createWorkerRunDir(resultModuleHome, "inspect", "INSPECT", testSetName);
            Path outFile = runDir.resolve("inspect.properties");
            Path logFile = runDir.resolve("inspect.log");

            List<String> command = buildWorkerCommand(
                    sourceModuleHome, resultModuleHome,
                    "inspect", sourceModuleHome, targetClassName, testSetName, outFile.toString()
            );

            WorkerRunResult inspectRun = executeWorker(command, inspectTimeout, outFile, logFile,
                    sourceModuleHome, resultModuleHome);

            if (inspectRun.success) {
                int testCount = inspectRun.testOrder == null ? 0 : inspectRun.testOrder.size();
                if (testCount <= 0) {
                    testCount = 1;
                }

                int computed = Math.max(10000, testCount * baseTimeoutMillis + 5000);
                if (SUITE_TIMEOUT_MAX_MILLIS > 0) {
                    computed = Math.min(computed, SUITE_TIMEOUT_MAX_MILLIS);
                }
                SUITE_TIMEOUT_CACHE.put(cacheKey, computed);
                keepDir = false;
                System.out.println("[TIMEOUT] " + testSetName + " tests=" + testCount
                        + " base=" + baseTimeoutMillis + " => workerTimeout=" + computed);
                return computed;
            }

            String msg = "[WARN ] inspect test set failed, fallback timeout used: " + testSetName
                    + " ; reason=" + oneLine(inspectRun.message);
            logError(resultModuleHome, msg, null);
            System.err.println(msg);
        } catch (Throwable t) {
            String msg = "[WARN ] inspect test set exception, fallback timeout used: " + testSetName
                    + " ; reason=" + oneLine(String.valueOf(t.getMessage()));
            logError(resultModuleHome, msg, t);
            System.err.println(msg);
        } finally {
            if (runDir != null && !keepDir) {
                deleteQuietly(runDir);
            }
        }

        if (SUITE_TIMEOUT_MAX_MILLIS > 0) {
            fallbackTimeout = Math.min(fallbackTimeout, SUITE_TIMEOUT_MAX_MILLIS);
        }
        SUITE_TIMEOUT_CACHE.put(cacheKey, fallbackTimeout);
        return fallbackTimeout;
    }

    private static Map<String, List<String>> buildTestsByTestSet(List<String> junitTests,
                                                                 List<String> testSetNames) {
        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<String, List<String>>();
        for (String testSetName : testSetNames) {
            grouped.put(testSetName, new ArrayList<String>());
        }

        for (String testName : junitTests) {
            int idx = testName.indexOf('#');
            if (idx <= 0) {
                continue;
            }
            String testSetName = testName.substring(0, idx);
            List<String> bucket = grouped.get(testSetName);
            if (bucket == null) {
                bucket = new ArrayList<String>();
                grouped.put(testSetName, bucket);
            }
            bucket.add(testName);
        }
        return grouped;
    }

    private static MutantTaskResult buildFailedTaskResult(String mutantName,
                                                          Throwable t,
                                                          List<String> junitTests,
                                                          Map<String, String> originalResults,
                                                          int timeoutMillis) {
        WorkerRunResult run = new WorkerRunResult();
        run.success = false;
        run.timedOut = false;
        run.message = (t == null) ? "unknown worker error" : oneLine(String.valueOf(t.getMessage()));
        if (run.message == null || run.message.trim().isEmpty()) {
            run.message = (t == null) ? "unknown worker error" : oneLine(String.valueOf(t));
        }

        MutantTaskResult r = new MutantTaskResult();
        r.mutantName = mutantName;
        r.workerRun = run;
        r.mutantResults = new LinkedHashMap<String, String>();
        r.killed = false;
        return r;
    }

    private static MutantTaskResult buildTimedOutTaskResult(String mutantName,
                                                            String message) {
        WorkerRunResult run = new WorkerRunResult();
        run.success = false;
        run.timedOut = true;
        run.message = oneLine(message == null ? "batch stall timeout" : message);

        MutantTaskResult r = new MutantTaskResult();
        r.mutantName = mutantName;
        r.workerRun = run;
        r.mutantResults = new LinkedHashMap<String, String>();
        r.killed = false;
        return r;
    }

    private static void mergeTaskResult(MutantTaskResult taskResult,
                                        TestResult tr,
                                        Map<String, StringBuilder> finalTestResults,
                                        Map<String, StringBuilder> finalMutantResults,
                                        Map<String, String> originalResults,
                                        List<String> junitTests) {
        String mutantName = taskResult.mutantName;
        Map<String, String> mutantResults = taskResult.mutantResults;
        boolean killed = taskResult.killed;

        for (String testName : junitTests) {
            String original = originalResults.get(testName);
            String current = currentResultOrOriginal(testName, originalResults, mutantResults);
            if (isKillingDifference(original, current)) {
                appendCsv(finalTestResults, testName, mutantName);
                appendCsv(finalMutantResults, mutantName, testName);
            }
        }

        if (killed) {
            tr.killed_mutants.add(mutantName);
            System.out.println("  [KILLED] " + mutantName + resultSuffix(taskResult.workerRun));
        } else {
            tr.live_mutants.add(mutantName);
            System.out.println("  [LIVE  ] " + mutantName + resultSuffix(taskResult.workerRun));
        }
    }

    private static Map<String, String> normalizeMutantResults(WorkerRunResult run,
                                                              List<String> junitTests,
                                                              int timeoutMillis) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<String, String>();

        if (run.success && run.results != null && !run.results.isEmpty()) {
            for (String testName : junitTests) {
                String value = run.results.get(testName);
                normalized.put(testName, value == null ? "pass" : value);
            }
            return normalized;
        }

        String failureText;
        if (run.timedOut) {
            if (run.message != null && !run.message.trim().isEmpty()) {
                failureText = run.message.trim();
            } else {
                failureText = "time_out: more than " + timeoutMillis + " milliseconds";
            }
        } else if (run.message != null && !run.message.trim().isEmpty()) {
            failureText = "worker_error: " + run.message.trim();
        } else {
            failureText = "worker_error";
        }

        for (String testName : junitTests) {
            normalized.put(testName, failureText);
        }
        return normalized;
    }

    private static void appendCsv(Map<String, StringBuilder> map, String key, String value) {
        if (key == null || value == null || value.isEmpty()) {
            return;
        }
        StringBuilder sb = map.get(key);
        if (sb == null) {
            sb = new StringBuilder();
            map.put(key, sb);
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(value);
    }

    private static LinkedHashMap<String, String> toFinalResultMap(Map<String, StringBuilder> source,
                                                                  List<?> orderedKeys) {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        if (orderedKeys != null) {
            for (Object keyObj : orderedKeys) {
                if (keyObj == null) {
                    continue;
                }
                String key = String.valueOf(keyObj);
                if (!seen.add(key)) {
                    continue;
                }
                StringBuilder sb = source.get(key);
                result.put(key, sb == null ? "" : sb.toString());
            }
        }
        for (Map.Entry<String, StringBuilder> entry : source.entrySet()) {
            if (seen.add(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().toString());
            }
        }
        return result;
    }

    private static int computeMethodBatchSize(int mutantCount) {
        if (METHOD_BATCH_SIZE_OVERRIDE > 0) {
            return Math.max(1, METHOD_BATCH_SIZE_OVERRIDE);
        }
        if (mutantCount <= 100) {
            return mutantCount;
        }
        if (mutantCount <= 500) {
            return 100;
        }
        if (mutantCount <= 1500) {
            return 75;
        }
        return 50;
    }

    private static void writeMethodCheckpoint(String resultModuleHome,
                                              String targetClassName,
                                              String methodSignature,
                                              int batchIndex,
                                              int batchCount,
                                              int totalMutants,
                                              int completedMutants,
                                              String status,
                                              String message) {
        if (resultModuleHome == null || resultModuleHome.trim().isEmpty()) {
            return;
        }
        try {
            Path checkpointDir = getProjectLogDir(resultModuleHome).resolve(CHECKPOINT_DIR_NAME);
            Files.createDirectories(checkpointDir);
            Path checkpointFile = checkpointDir.resolve(
                    sanitizeFileName(targetClassName) + "__" + sanitizeFileName(methodSignature) + ".progress.log");
            String line = System.currentTimeMillis()
                    + "	status=" + safeStr(status)
                    + "	batch=" + batchIndex + "/" + batchCount
                    + "	completed=" + completedMutants + "/" + totalMutants
                    + "	message=" + safeStr(message)
                    + System.lineSeparator();
            Files.write(checkpointFile,
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String resultSuffix(WorkerRunResult run) {
        if (run == null) return "";
        if (run.timedOut) {
            return " (timeout)";
        }
        if (!run.success && run.message != null && !run.message.trim().isEmpty()) {
            return " (" + oneLine(run.message) + ")";
        }
        return "";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean isKillingDifference(String original, String current) {
        return KillDecisionEngine.isKilled(original, current);
    }

    private static boolean sameSemanticResult(String a, String b) {
        return KillDecisionEngine.sameSemanticResult(a, b);
    }

    private static boolean isTimeoutText(String text) {
        return KillDecisionEngine.isTimeoutText(text);
    }

    private static boolean isInfrastructureFailureText(String text) {
        return KillDecisionEngine.isInfrastructureFailureText(text);
    }

    private static String normalizeFailureText(String text) {
        return KillDecisionEngine.normalizeFailureText(text);
    }

    private static void deduplicateMutantLists(TestResult tr) {
        LinkedHashSet<Object> killed = new LinkedHashSet<Object>(tr.killed_mutants);
        LinkedHashSet<Object> live = new LinkedHashSet<Object>(tr.live_mutants);
        live.removeAll(killed);

        tr.killed_mutants.clear();
        tr.killed_mutants.addAll(killed);
        tr.live_mutants.clear();
        tr.live_mutants.addAll(live);
    }


    private static WorkerRunResult runMutantWorkerPersistent(WorkerServerPool serverPool,
                                                             String sourceModuleHome,
                                                             String resultModuleHome,
                                                             String targetClassName,
                                                             String testSetName,
                                                             String methodSignature,
                                                             String mutantName,
                                                             int timeoutMillis,
                                                             long mutantRemainingMillis,
                                                             List<String> selectedTests,
                                                             int recycleEveryForMethod) throws Exception {
        WorkerServerHandle handle = null;
        boolean release = false;
        RecycleDecision recycleDecision = RecycleDecision.none();
        WorkerRunResult result = null;
        String invalidateReason = "unknown";
        try {
            handle = serverPool.acquire();
            result = handle.runMutant(
                    sourceModuleHome,
                    resultModuleHome,
                    targetClassName,
                    testSetName,
                    methodSignature,
                    mutantName,
                    timeoutMillis,
                    mutantRemainingMillis,
                    selectedTests
            );
            release = result.success && !result.timedOut && handle.isAlive();
            if (release) {
                recycleDecision = handle.markSuccessAndShouldRecycle(methodSignature, recycleEveryForMethod);
            } else {
                if (result.timedOut) {
                    invalidateReason = "timeout";
                } else if (!result.success) {
                    invalidateReason = "worker_fail";
                } else if (!handle.isAlive()) {
                    invalidateReason = "worker_dead";
                } else {
                    invalidateReason = "unknown";
                }
            }
            return result;
        } finally {
            if (handle != null) {
                if (release) {
                    if (recycleDecision.shouldRecycle) {
                        serverPool.recycle(handle, recycleDecision);
                    } else {
                        serverPool.release(handle);
                    }
                } else {
                    serverPool.invalidate(handle, invalidateReason);
                }
            }
        }
    }

    private static String encodeProtocolField(String text) {
        String value = text == null ? "" : text;
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeProtocolField(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    private static int computeRecycleEveryForMethod(int mutantCount, int workerCount) {
        if (WORKER_RECYCLE_EVERY_OVERRIDE > 0) {
            return WORKER_RECYCLE_EVERY_OVERRIDE;
        }

        int workers = Math.max(1, workerCount);
        int avgPerWorker = (Math.max(0, mutantCount) + workers - 1) / workers;
        if (avgPerWorker <= 80) {
            return 0;
        }
        if (avgPerWorker <= 300) {
            return 60;
        }
        if (avgPerWorker <= 1000) {
            return 80;
        }
        if (avgPerWorker <= 2500) {
            return 100;
        }
        return 120;
    }

    private static WorkerRunResult runOriginalWorker(String sourceModuleHome,
                                                     String resultModuleHome,
                                                     String targetClassName,
                                                     String testSetName,
                                                     int timeoutMillis) throws Exception {
        Path runDir = createWorkerRunDir(resultModuleHome, "original", "ORIGINAL", "ORIGINAL");
        boolean keepDir = true;
        try {
            Path outFile = runDir.resolve("original.properties");
            Path logFile = runDir.resolve("original.log");
            List<String> command = buildWorkerCommand(
                    sourceModuleHome, resultModuleHome,
                    "original", sourceModuleHome, targetClassName, testSetName, outFile.toString()
            );
            int workerTimeout = suiteTimeoutMillis(sourceModuleHome, resultModuleHome, targetClassName, testSetName, timeoutMillis);
            long startedAt = System.nanoTime();
            WorkerRunResult result = executeWorker(command, workerTimeout, outFile, logFile,
                    sourceModuleHome, resultModuleHome);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (result.success && result.testOrder.isEmpty()) {
                result.success = false;
                result.message = "No tests discovered in " + testSetName;
            }
            if (result.success) {
                ORIGINAL_SUITE_ELAPSED_MS.put(originalSuiteElapsedKey(targetClassName, testSetName), elapsedMs);
                int baselineTimeout = computeBaselineTimeoutFromOriginalElapsed(elapsedMs);
                SUITE_TIMEOUT_CACHE.put(
                        suiteTimeoutCacheKey(sourceModuleHome, targetClassName, testSetName, timeoutMillis),
                        baselineTimeout
                );
                System.out.println("[TIMEOUT-BASELINE] " + testSetName
                        + " originalElapsedMs=" + elapsedMs
                        + " => mutantWorkerTimeout=" + baselineTimeout);
            }
            keepDir = !result.success;
            return result;
        } finally {
            if (!keepDir) {
                deleteQuietly(runDir);
            }
        }
    }

    private static WorkerRunResult runMutantWorker(String sourceModuleHome,
                                                   String resultModuleHome,
                                                   String targetClassName,
                                                   String testSetName,
                                                   String methodSignature,
                                                   String mutantName,
                                                   int timeoutMillis) throws Exception {
        Path runDir = createWorkerRunDir(resultModuleHome, "mutant", methodSignature, mutantName);
        boolean keepDir = true;
        try {
            Path outFile = runDir.resolve("mutant.properties");
            Path logFile = runDir.resolve("mutant.log");
            List<String> command = buildWorkerCommand(
                    sourceModuleHome, resultModuleHome,
                    "mutant",
                    sourceModuleHome,
                    targetClassName,
                    testSetName,
                    methodSignature,
                    mutantName,
                    outFile.toString()
            );
            int workerTimeout = suiteTimeoutMillis(sourceModuleHome, resultModuleHome, targetClassName, testSetName, timeoutMillis);
            WorkerRunResult result = executeWorker(command, workerTimeout, outFile, logFile,
                    sourceModuleHome, resultModuleHome);
            keepDir = !result.success || result.timedOut;
            return result;
        } finally {
            if (!keepDir) {
                deleteQuietly(runDir);
            }
        }
    }

    private static List<String> buildWorkerCommand(String sourceModuleHome,
                                                   String resultModuleHome,
                                                   String... args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(getJavaBin());
        cmd.add("-Xms" + WORKER_XMS);
        cmd.add("-Xmx" + WORKER_XMX);

        passProperty(cmd, "mujava.config.path");
        passProperty(cmd, "mujava.class.dirs");
        passProperty(cmd, "mujava.jar.files");
        passProperty(cmd, "mujava.jar.dirs");
        passProperty(cmd, "mujava.result.module.home");
        passProperty(cmd, "mujava.testset.mode");
        passProperty(cmd, "mujava.include.evosuite.tests");

        cmd.add("-cp");
        cmd.add(buildWorkerClasspath(sourceModuleHome, resultModuleHome));
        cmd.add(WORKER_CLASS);
        Collections.addAll(cmd, args);
        return cmd;
    }

    private static String normalizePath(File f) {
        return f.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean isUnder(File f, String root) {
        if (root == null || root.trim().isEmpty()) {
            return false;
        }
        String fp = normalizePath(f);
        String rp = new File(root).getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
        return fp.equals(rp) || fp.startsWith(rp + "/");
    }

    private static boolean isManagedResultTestClassesDir(File f, String resultModuleHome) {
        if (resultModuleHome == null || resultModuleHome.trim().isEmpty()) {
            return false;
        }

        Path resultRoot = Paths.get(resultModuleHome).toAbsolutePath().normalize();
        Path dir = f.toPath().toAbsolutePath().normalize();
        if (!dir.startsWith(resultRoot)) {
            return false;
        }

        String p = normalizePath(f);
        if (p.endsWith("/origin/target/test-classes")
                || p.contains("/origin/target/test-classes/")) {
            return true;
        }

        Path rel = resultRoot.relativize(dir);
        return rel.getNameCount() == 2
                && "classes".equalsIgnoreCase(rel.getName(1).toString());
    }

    private static boolean shouldSkipWorkerClasspathEntry(File f,
                                                          String sourceModuleHome,
                                                          String resultModuleHome) {
        if (!f.exists()) {
            return false;
        }

        String p = normalizePath(f);

        if (p.endsWith("/target/test-classes")
                || p.contains("/target/test-classes/")
                || p.endsWith("/testset")
                || p.contains("/testset/")
                || isManagedResultTestClassesDir(f, resultModuleHome)) {
            return true;
        }

        return f.isDirectory() && (isUnder(f, sourceModuleHome) || isUnder(f, resultModuleHome));
    }

    private static String buildWorkerClasspath(String sourceModuleHome, String resultModuleHome) {
        LinkedHashSet<String> cp = new LinkedHashSet<String>();
        String sep = File.pathSeparator;

        String text = System.getProperty("java.class.path");
        if (text != null && !text.trim().isEmpty()) {
            String[] arr = text.split(Pattern.quote(File.pathSeparator));
            for (String item : arr) {
                if (item == null) continue;
                item = item.trim();
                if (item.isEmpty()) continue;

                File f = new File(item).getAbsoluteFile();
                if (shouldSkipWorkerClasspathEntry(f, sourceModuleHome, resultModuleHome)) {
                    System.out.println("[WORKER-CP-FILTER] skip " + f.getAbsolutePath());
                    continue;
                }
                cp.add(f.getAbsolutePath());
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String s : cp) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static String buildWorkerClasspath() {
        LinkedHashSet<String> cp = new LinkedHashSet<String>();
        String sep = File.pathSeparator;

        // worker 进程的 app classpath 仅保留 MuJava 自身与测试框架运行时。
        // 业务类与项目依赖通过自定义 ClassLoader 从 mujava.class.dirs / mujava.jar.* 中加载，
        // 避免 AppClassLoader 预先加载项目类，造成与 Original/JMutationLoader2 的双份定义冲突。
        addClasspathEntries(cp, System.getProperty("java.class.path"), false);

        StringBuilder sb = new StringBuilder();
        for (String s : cp) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static void addClasspathEntries(Set<String> out, String text, boolean asWildcardDir) {
        if (text == null || text.trim().isEmpty()) return;

        String[] arr = text.split(Pattern.quote(File.pathSeparator));
        for (String item : arr) {
            if (item == null) continue;
            item = item.trim();
            if (item.isEmpty()) continue;

            File f = new File(item);
            if (asWildcardDir) {
                if (f.isDirectory()) {
                    out.add(new File(f, "*").getAbsolutePath());
                }
            } else {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private static void passProperty(List<String> cmd, String key) {
        String value = System.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            cmd.add("-D" + key + "=" + value);
        }
    }

    private static WorkerRunResult executeWorker(List<String> command,
                                                 int timeoutMillis,
                                                 Path outFile,
                                                 Path logFile,
                                                 String sourceModuleHome,
                                                 String resultModuleHome) throws Exception {
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(sourceModuleHome));
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        Process p = pb.start();
        boolean finished = p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);

        WorkerRunResult result = new WorkerRunResult();
        result.command = command;
        result.logFile = logFile.toString();

        if (!finished) {
            result.success = false;
            result.timedOut = true;
            result.message = "time_out: more than " + timeoutMillis + " milliseconds";
            p.destroyForcibly();
            try {
                p.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            logError(resultModuleHome, "[TIMEOUT] " + result.message + " ; logFile=" + result.logFile, null);
            return result;
        }

        result.exitCode = p.exitValue();

        if (!Files.exists(outFile)) {
            result.success = false;
            result.message = "worker result file not found; exitCode=" + result.exitCode + "; log=" + readTail(logFile);
            logError(resultModuleHome, "[MISSING-RESULT] " + result.message, null);
            return result;
        }

        Properties props = new Properties();
        InputStream in = Files.newInputStream(outFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }

        result.status = props.getProperty("status", "");
        result.message = props.getProperty("message", "");
        result.testOrder = split(props.getProperty("tests", ""));
        result.results = new LinkedHashMap<String, String>();
        for (String testName : result.testOrder) {
            result.results.put(testName, props.getProperty("result." + testName, "pass"));
        }
        result.success = (result.exitCode == 0) && "ok".equalsIgnoreCase(result.status);

        if (!result.success && (result.message == null || result.message.trim().isEmpty())) {
            result.message = "exitCode=" + result.exitCode + "; log=" + readTail(logFile);
        }

        if (!result.success) {
            logError(resultModuleHome,
                    "[WORKER-EXIT] exitCode=" + result.exitCode
                            + ", status=" + result.status
                            + ", message=" + result.message
                            + ", logFile=" + result.logFile,
                    null);
        }
        return result;
    }


    private static WorkerRunResult readWorkerResultFile(Path outFile,
                                                        Path logFile,
                                                        List<String> command) throws Exception {
        WorkerRunResult result = new WorkerRunResult();
        result.exitCode = 0;
        result.command = command == null ? new ArrayList<String>() : new ArrayList<String>(command);
        result.logFile = logFile == null ? "" : logFile.toString();

        if (!Files.exists(outFile)) {
            result.success = false;
            result.message = "worker result file not found";
            return result;
        }

        Properties props = new Properties();
        InputStream in = Files.newInputStream(outFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }

        result.status = props.getProperty("status", "");
        result.message = props.getProperty("message", "");
        result.testOrder = split(props.getProperty("tests", ""));
        result.results = new LinkedHashMap<String, String>();
        for (String testName : result.testOrder) {
            result.results.put(testName, props.getProperty("result." + testName, "pass"));
        }
        result.success = "ok".equalsIgnoreCase(result.status);
        if (!result.success && (result.message == null || result.message.trim().isEmpty())) {
            result.message = "persistent worker failed; log=" + readTail(logFile);
        }
        return result;
    }

    private static WorkerRunResult readWorkerResultFromProtocolLine(String responseLine,
                                                                    Path logFile,
                                                                    List<String> command) throws Exception {
        WorkerRunResult result = new WorkerRunResult();
        result.exitCode = 0;
        result.command = command == null ? new ArrayList<String>() : new ArrayList<String>(command);
        result.logFile = logFile == null ? "" : logFile.toString();

        if (responseLine == null || responseLine.trim().isEmpty()) {
            result.success = false;
            result.message = "persistent worker returned empty response; log=" + readTail(logFile);
            return result;
        }

        String[] parts = responseLine.split("\\t", 2);
        if (parts.length < 2 || !"RESULT".equalsIgnoreCase(parts[0])) {
            result.success = false;
            result.message = "unexpected persistent worker response: " + oneLine(responseLine)
                    + " ; log=" + readTail(logFile);
            return result;
        }

        byte[] payload = Base64.getDecoder().decode(parts[1]);
        Properties props = new Properties();
        ByteArrayInputStream in = new ByteArrayInputStream(payload);
        try {
            props.load(in);
        } finally {
            in.close();
        }

        result.status = props.getProperty("status", "");
        result.message = props.getProperty("message", "");
        result.testOrder = split(props.getProperty("tests", ""));
        result.results = new LinkedHashMap<String, String>();
        for (String testName : result.testOrder) {
            result.results.put(testName, props.getProperty("result." + testName, "pass"));
        }
        result.success = "ok".equalsIgnoreCase(result.status);
        if (!result.success && (result.message == null || result.message.trim().isEmpty())) {
            result.message = "persistent worker failed; log=" + readTail(logFile);
        }
        return result;
    }


    private static List<String> split(String text) {
        List<String> result = new ArrayList<String>();
        if (text == null || text.isEmpty()) return result;
        String[] arr = text.split("\\u0001", -1);
        for (String s : arr) {
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null) {
            return "";
        }
        for (String item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\u0001');
            }
            sb.append(item);
        }
        return sb.toString();
    }

    private static String readTail(Path logFile) {
        if (logFile == null || !Files.exists(logFile)) return "";
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8);
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException ignored) {
            return "";
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }

        int from = Math.max(0, lines.size() - 10);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String getJavaBin() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin" + File.separator + "java";
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        ACTIVE_EXECUTORS.remove(executor);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createTrackedFixedThreadPool(int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ACTIVE_EXECUTORS.add(executor);
        return executor;
    }

    private static void registerWorkerPool(WorkerServerPool pool) {
        if (pool != null) {
            ACTIVE_WORKER_POOLS.add(pool);
        }
    }

    private static void unregisterWorkerPool(WorkerServerPool pool) {
        if (pool != null) {
            ACTIVE_WORKER_POOLS.remove(pool);
        }
    }

    private static void installShutdownHookIfNeeded() {
        if (SHUTDOWN_HOOK_INSTALLED) {
            return;
        }
        synchronized (TestRunner_MultiProcess_batched.class) {
            if (SHUTDOWN_HOOK_INSTALLED) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("[SHUTDOWN-HOOK] begin cleanup of executors and persistent workers");
                    List<ExecutorService> executorsSnapshot;
                    synchronized (ACTIVE_EXECUTORS) {
                        executorsSnapshot = new ArrayList<ExecutorService>(ACTIVE_EXECUTORS);
                    }
                    for (ExecutorService executor : executorsSnapshot) {
                        try {
                            shutdownExecutor(executor);
                        } catch (Throwable t) {
                            System.err.println("[SHUTDOWN-HOOK] executor cleanup failed: " + oneLine(String.valueOf(t.getMessage())));
                        }
                    }

                    List<WorkerServerPool> poolsSnapshot;
                    synchronized (ACTIVE_WORKER_POOLS) {
                        poolsSnapshot = new ArrayList<WorkerServerPool>(ACTIVE_WORKER_POOLS);
                    }
                    for (WorkerServerPool pool : poolsSnapshot) {
                        try {
                            pool.close();
                        } catch (Throwable t) {
                            System.err.println("[SHUTDOWN-HOOK] worker pool cleanup failed: " + oneLine(String.valueOf(t.getMessage())));
                        }
                    }
                    System.out.println("[SHUTDOWN-HOOK] cleanup finished");
                }
            }, "mujava-shutdown-hook"));
            SHUTDOWN_HOOK_INSTALLED = true;
        }
    }


    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static List<List<Object>> readExcelFile(String filePath) {
        try {
            return readExcel(filePath, 0);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    public static String getCurr(String workDir) {
        Pattern p = Pattern.compile("(?i)^(.*?)(?=[\\\\/]+result(?:[\\\\/]+|$))");
        Matcher m = p.matcher(workDir);

        if (m.find()) {
            return m.group(1);
        } else {
            throw new IllegalArgumentException("未匹配到 result: " + workDir);
        }
    }

    private static void writeKillStatistic(Path outFile,
                                           Map<String, String> testResults,
                                           Map<String, String> mutantResults,
                                           List<String> killed,
                                           List<String> live,
                                           double score) throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("test_results=" + mapToSampleStyle(testResults) + ";");
        lines.add("mutant_results=" + mapToSampleStyle(mutantResults));
        lines.add("killed_mutants=" + listToSampleStyle(killed) + ";");
        lines.add("live_mutants=" + listToSampleStyle(live) + ";");
        lines.add("mutant_score=" + String.format(Locale.ROOT, "%.5f", score));

        Files.createDirectories(outFile.getParent());
        Files.write(outFile, lines, StandardCharsets.UTF_8);
    }

    private static String listToSampleStyle(List<String> items) {
        return "[" + String.join(", ", items) + "]";
    }

    private static String mapToSampleStyle(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=");
            sb.append(e.getValue() == null ? "" : e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    public static void runBatchTraditionalFromExcel(String excelPath, int timeoutMillis) {
        List<List<Object>> excelData = readExcelFile(excelPath);
        if (excelData.isEmpty()) {
            System.err.println("[ERROR] Excel 数据为空: " + excelPath);
            return;
        }

        String testMode = currentTestSetMode(); // origin、evosuite、llm等测试集合
        setTestSetMode(testMode);

        bootstrapMuJavaConfigProperty(excelPath);
        Set<String> classSet = new HashSet<String>();

        // for (int i = 44546; i < excelData.size(); i++) {
        for (int i = 38043; i < 44544; i++) {
            String resultModuleHome = null;
            String sourceRootHome = null;
            String sourceModuleHome = null;
            try {
                String packageName = String.valueOf(excelData.get(i).get(6)).trim();
                String projectName = String.valueOf(excelData.get(i).get(7)).trim();
                String rawFilePath = String.valueOf(excelData.get(i).get(8)).trim();

                String filepath = new File(rawFilePath).getParent().replace("\\", "/");
                filepath = filepath.replace("//?/", "");
                String workDir = getCurr(filepath);

                resultModuleHome = Paths.get(workDir).normalize().toString();
                Path resultPath = Paths.get(resultModuleHome).normalize();
                Path outerRoot;
                String pathStr = resultPath.toString().replace('\\', '/');

                String tag = resultModuleHome + "/result/" + packageName;
                if (classSet.contains(tag)) {
                    continue;
                }
                classSet.add(tag);

                if (pathStr.matches(".*(?:commons-math|commons-numbers).*")) {
                    outerRoot = resultPath.getParent();
                } else {
                    outerRoot = resultPath.getFileName();
                }

                sourceRootHome = resolveSourceRootHome(resultModuleHome, outerRoot.getFileName().toString());
                sourceModuleHome = resolveSourceModuleHome(resultModuleHome, sourceRootHome);

                String targetClassName = packageName;
                List<String> testSetNames = MuJavaRuntimeSupport.resolveTestSetNames(
                        sourceModuleHome,
                        resultModuleHome,
                        targetClassName
                );
                if (testSetNames.isEmpty()) {
                    testSetNames.add(MuJavaRuntimeSupport.buildTestSetName(
                            sourceModuleHome,
                            resultModuleHome,
                            targetClassName
                    ));
                }

                System.out.println("\n==================================================");
                System.out.println("[ROW             ] " + i);
                System.out.println("[PROJECT         ] " + projectName);
                System.out.println("[TARGET          ] " + targetClassName);
                System.out.println("[TESTS           ] " + testSetNames);
                System.out.println("[RESULT_MODULE   ] " + resultModuleHome);
                System.out.println("[SOURCE_ROOT     ] " + sourceRootHome);
                System.out.println("[SOURCE_MODULE   ] " + sourceModuleHome);
                System.out.println("[WORKERS         ] " + MAX_WORKERS);
                System.out.println("[TEST_MODE       ] " + testMode);

                TestResult res = runTraditionalTest(
                        targetClassName, testSetNames, timeoutMillis, sourceModuleHome, resultModuleHome);

                if (res == null) {
                    logError(resultModuleHome, "[ERROR] 执行失败，结果为空: " + targetClassName, null);
                    System.err.println("[ERROR] 执行失败，结果为空: " + targetClassName);
                    continue;
                }

                List<String> killed = new ArrayList<String>();
                for (Object o : res.killed_mutants) {
                    killed.add(String.valueOf(o));
                }

                List<String> live = new ArrayList<String>();
                for (Object o : res.live_mutants) {
                    live.add(String.valueOf(o));
                }
                String outFileName = "kill_statistic_" + testMode + ".txt";
                String outFile = Paths.get(
                        resultModuleHome,
                        "result",
                        targetClassName,
                        "traditional_mutants",
                        outFileName
                ).normalize().toString();

                writeKillStatistic(
                        Paths.get(outFile),
                        res.test_results,
                        res.mutant_results,
                        killed,
                        live,
                        res.mutant_score
                );
                System.out.println("[OK] kill_statistic written to: " + outFile);
            } catch (Exception e) {
                logError(resultModuleHome,
                        "[ERROR] 第 " + i + " 行执行失败: " + e.getMessage()
                                + " ; sourceRoot=" + safeStr(sourceRootHome)
                                + " ; sourceModule=" + safeStr(sourceModuleHome),
                        e);
                System.err.println("[ERROR] 第 " + i + " 行执行失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void bootstrapMuJavaConfigProperty(String excelPath) {
        if (InCodeConfig.CONFIG_PATH_OVERRIDE != null && !InCodeConfig.CONFIG_PATH_OVERRIDE.trim().isEmpty()) {
            String resolved = Paths.get(InCodeConfig.CONFIG_PATH_OVERRIDE).toAbsolutePath().normalize().toString();
            System.setProperty("mujava.config.path", resolved);
            System.out.println("[INFO ] mujava.config.path=" + resolved + " (fixed in code)");
            return;
        }

        String existing = System.getProperty("mujava.config.path");
        if (existing != null && !existing.trim().isEmpty()) {
            return;
        }
        if (!InCodeConfig.AUTO_DISCOVER_CONFIG_PATH) {
            System.err.println("[WARN ] mujava.config.path 未设置，且已关闭自动查找。请在 InCodeConfig.CONFIG_PATH_OVERRIDE 中写死路径。");
            return;
        }

        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get("mujava.config"));
        candidates.add(Paths.get("../mujava.config"));

        if (excelPath != null && !excelPath.trim().isEmpty()) {
            Path excelAbs = Paths.get(excelPath).toAbsolutePath().normalize();
            Path parent = excelAbs.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("mujava.config").normalize());
                candidates.add(parent.resolve("..").resolve("mujava.config").normalize());
            }
        }

        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                String resolved = candidate.toAbsolutePath().normalize().toString();
                System.setProperty("mujava.config.path", resolved);
                System.out.println("[INFO ] mujava.config.path=" + resolved);
                return;
            }
        }

        System.err.println("[WARN ] mujava.config.path not found automatically, worker may miss Maven dependencies.");
    }

    private static String resolveSourceRootHome(String resultModuleHome, String projectName) {
        Path resultPath = Paths.get(resultModuleHome).normalize();
        Path outerRoot = resultPath.getParent();

        Path nestedProject = resultPath.resolve(projectName).normalize();
        if (isUsableProjectContainer(nestedProject)) {
            return nestedProject.toString();
        }

        if (outerRoot != null) {
            Path siblingProject = outerRoot.resolve(projectName).normalize();
            if (isUsableProjectContainer(siblingProject)) {
                return siblingProject.toString();
            }
        }

        if (isUsableProjectContainer(resultPath)) {
            return resultPath.toString();
        }

        if (outerRoot != null && isUsableProjectContainer(outerRoot)) {
            return outerRoot.toString();
        }

        return nestedProject.toString();
    }

    private static String resolveSourceModuleHome(String resultModuleHome, String sourceRootHome) {
        Path resultPath = Paths.get(resultModuleHome).normalize();
        String moduleName = resultPath.getFileName() == null ? "" : resultPath.getFileName().toString();
        Path sourceRootPath = Paths.get(sourceRootHome).normalize();

        Path candidate1 = sourceRootPath.resolve(moduleName).normalize();
        if (isUsableModuleHome(candidate1)) {
            return candidate1.toString();
        }

        if (isUsableModuleHome(sourceRootPath)) {
            return sourceRootPath.toString();
        }

        return candidate1.toString();
    }

    private static boolean isUsableProjectContainer(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return Files.exists(dir.resolve("pom.xml"))
                || Files.isDirectory(dir.resolve("src"))
                || Files.isDirectory(dir.resolve("target"));
    }

    private static boolean isUsableModuleHome(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return Files.exists(dir.resolve("pom.xml"))
                || Files.isDirectory(dir.resolve("src"))
                || Files.isDirectory(dir.resolve("target"));
    }

    private static String normalizeTestMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return "evosuite";
        }
        return mode.trim();
    }

    private static String currentTestSetMode() {
        return normalizeTestMode(InCodeConfig.TEST_MODE);
    }

    private static void setTestSetMode(String mode) {
        String normalized = normalizeTestMode(mode);
        System.setProperty("mujava.testset.mode", normalized);
        System.setProperty("mujava.include.evosuite.tests", String.valueOf("evosuite".equalsIgnoreCase(normalized)));
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }


    private static class WorkerServerPool {
        private final String sourceModuleHome;
        private final String resultModuleHome;
        private final String targetClassName;
        private final int workerCount;
        private final BlockingQueue<WorkerServerHandle> idleHandles = new LinkedBlockingQueue<WorkerServerHandle>();
        private final Set<WorkerServerHandle> allHandles = Collections.synchronizedSet(new LinkedHashSet<WorkerServerHandle>());
        private volatile boolean closed = false;
        private int nextWorkerId = 1;

        private WorkerServerPool(String sourceModuleHome,
                                 String resultModuleHome,
                                 String targetClassName,
                                 int workerCount) {
            this.sourceModuleHome = sourceModuleHome;
            this.resultModuleHome = resultModuleHome;
            this.targetClassName = targetClassName;
            this.workerCount = Math.max(1, workerCount);
        }

        private void start() throws Exception {
            for (int i = 0; i < workerCount; i++) {
                idleHandles.offer(newHandle());
            }
        }

        private synchronized WorkerServerHandle newHandle() throws Exception {
            int workerId = nextWorkerId++;
            Path logDir = getProjectLogDir(resultModuleHome).resolve("persistent_workers");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("worker_" + workerId + ".log");

            List<String> command = buildWorkerCommand(
                    sourceModuleHome,
                    resultModuleHome,
                    "server",
                    sourceModuleHome,
                    safeStr(resultModuleHome),
                    targetClassName
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(sourceModuleHome));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            Process process = pb.start();
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
            );
            BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            WorkerServerHandle handle = new WorkerServerHandle(workerId, process, writer, reader, logFile, command);
            allHandles.add(handle);
            return handle;
        }

        private WorkerServerHandle acquire() throws InterruptedException, Exception {
            while (true) {
                WorkerServerHandle handle = idleHandles.poll(WORKER_ACQUIRE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (handle == null) {
                    if (closed) {
                        throw new IllegalStateException("Persistent worker pool has been closed");
                    }
                    throw new IllegalStateException("Timed out waiting for an idle persistent worker");
                }
                if (handle.isAlive()) {
                    return handle;
                }
                int oldWorkerId = handle.getWorkerId();
                System.out.println("[PERSISTENT-WORKER-INVALIDATE] worker=" + oldWorkerId
                        + " reason=worker_dead_on_acquire");
                handle.close();
                allHandles.remove(handle);
                if (closed) {
                    throw new IllegalStateException("Persistent worker pool has been closed");
                }
                WorkerServerHandle replacement = newHandle();
                idleHandles.offer(replacement);
                System.out.println("[PERSISTENT-WORKER-RESTART] old=" + oldWorkerId
                        + " new=" + replacement.getWorkerId());
            }
        }

        private void release(WorkerServerHandle handle) {
            if (handle == null) return;
            if (closed || !handle.isAlive()) {
                handle.close();
                allHandles.remove(handle);
                return;
            }
            idleHandles.offer(handle);
        }

        private synchronized void invalidate(WorkerServerHandle handle, String reason) {
            int oldWorkerId = -1;
            if (handle != null) {
                oldWorkerId = handle.getWorkerId();
                System.out.println("[PERSISTENT-WORKER-INVALIDATE] worker=" + oldWorkerId
                        + " reason=" + safeStr(reason));
                handle.close();
                allHandles.remove(handle);
            }

            if (closed) {
                return;
            }

            try {
                WorkerServerHandle replacement = newHandle();
                idleHandles.offer(replacement);
                System.out.println("[PERSISTENT-WORKER-RESTART] old=" + oldWorkerId
                        + " new=" + replacement.getWorkerId());
            } catch (Exception e) {
                logError(resultModuleHome,
                        "[PERSISTENT-WORKER-RESTART-FAIL] old=" + oldWorkerId
                                + " ; reason=" + oneLine(e.getMessage()), e);
            }
        }

        private synchronized void recycle(WorkerServerHandle handle, RecycleDecision decision) {
            if (handle != null) {
                int oldWorkerId = handle.getWorkerId();
                int totalSuccessRuns = handle.getSuccessRuns();
                int methodSuccessRuns = handle.getSuccessRunsInCurrentMethod();
                String methodSignature = handle.getCurrentMethodSignature();
                handle.close();
                allHandles.remove(handle);
                if (!closed) {
                    try {
                        WorkerServerHandle replacement = newHandle();
                        idleHandles.offer(replacement);
                        System.out.println("[PERSISTENT-WORKER-RECYCLE] reason=" + decision.reason
                                + " method=" + methodSignature
                                + " oldWorker=" + oldWorkerId
                                + " totalSuccessRuns=" + totalSuccessRuns
                                + " methodSuccessRuns=" + methodSuccessRuns
                                + " threshold=" + decision.threshold
                                + " -> newWorker=" + replacement.getWorkerId());
                    } catch (Exception e) {
                        logError(resultModuleHome,
                                "[PERSISTENT-WORKER-RECYCLE-FAIL] oldWorker=" + oldWorkerId
                                        + " ; reason=" + oneLine(e.getMessage()), e);
                    }
                }
            }
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            ACTIVE_WORKER_POOLS.remove(this);

            List<WorkerServerHandle> handles = new ArrayList<WorkerServerHandle>();
            synchronized (allHandles) {
                handles.addAll(allHandles);
                allHandles.clear();
            }
            idleHandles.clear();

            for (WorkerServerHandle handle : handles) {
                if (handle != null) {
                    try {
                        handle.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static class WorkerServerHandle {
        private final int workerId;
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final Path logFile;
        private final List<String> command;
        private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<String>();
        private final Thread responseReaderThread;
        private volatile String readerFailureMessage = "";
        private volatile boolean closed = false;
        private int successRuns = 0;
        private String currentMethodSignature = "";
        private int successRunsInCurrentMethod = 0;

        private WorkerServerHandle(int workerId,
                                   Process process,
                                   BufferedWriter writer,
                                   BufferedReader reader,
                                   Path logFile,
                                   List<String> command) {
            this.workerId = workerId;
            this.process = process;
            this.writer = writer;
            this.reader = reader;
            this.logFile = logFile;
            this.command = command;
            this.responseReaderThread = startResponseReaderThread();
        }

        private Thread startResponseReaderThread() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseQueue.offer(line);
                        }
                    } catch (IOException e) {
                        if (!closed) {
                            readerFailureMessage = oneLine(e.getMessage());
                        }
                    } finally {
                        responseQueue.offer(WORKER_PROTOCOL_EOF);
                    }
                }
            }, "mujava-worker-protocol-reader-" + workerId);
            t.setDaemon(true);
            t.start();
            return t;
        }

        private boolean isAlive() {
            return process != null && process.isAlive();
        }

        private synchronized RecycleDecision markSuccessAndShouldRecycle(String methodSignature, int recycleEveryForMethod) {
            successRuns++;

            String methodKey = methodSignature == null ? "" : methodSignature;
            if (!methodKey.equals(currentMethodSignature)) {
                currentMethodSignature = methodKey;
                successRunsInCurrentMethod = 0;
            }

            successRunsInCurrentMethod++;
            if (recycleEveryForMethod > 0 && successRunsInCurrentMethod >= recycleEveryForMethod) {
                return RecycleDecision.method(recycleEveryForMethod);
            }
            if (GLOBAL_WORKER_RECYCLE_EVERY > 0 && successRuns >= GLOBAL_WORKER_RECYCLE_EVERY) {
                return RecycleDecision.global(GLOBAL_WORKER_RECYCLE_EVERY);
            }
            return RecycleDecision.none();
        }

        private synchronized int getSuccessRuns() {
            return successRuns;
        }

        private synchronized int getSuccessRunsInCurrentMethod() {
            return successRunsInCurrentMethod;
        }

        private synchronized String getCurrentMethodSignature() {
            return currentMethodSignature;
        }

        private int getWorkerId() {
            return workerId;
        }

        private void clearResponseQueue() {
            while (responseQueue.poll() != null) {
                // drain stale responses defensively; one handle only serves one request at a time.
            }
        }

        private WorkerRunResult runMutant(String sourceModuleHome,
                                          String resultModuleHome,
                                          String targetClassName,
                                          String testSetName,
                                          String methodSignature,
                                          String mutantName,
                                          int timeoutMillis,
                                          long mutantRemainingMillis,
                                          List<String> selectedTests) throws Exception {
            int workerTimeout = suiteTimeoutMillis(sourceModuleHome, resultModuleHome, targetClassName, testSetName, timeoutMillis);
            if (mutantRemainingMillis > 0L) {
                workerTimeout = (int) Math.max(1L, Math.min((long) workerTimeout, mutantRemainingMillis));
            }

            String line = "RUN	"
                    + encodeProtocolField(testSetName) + "	"
                    + encodeProtocolField(methodSignature) + "	"
                    + encodeProtocolField(mutantName) + "	"
                    + encodeProtocolField(join(selectedTests)) + "	"
                    + encodeProtocolField(String.valueOf(workerId));

            clearResponseQueue();

            synchronized (writer) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }

            String responseLine = responseQueue.poll(workerTimeout, TimeUnit.MILLISECONDS);
            if (responseLine != null && !WORKER_PROTOCOL_EOF.equals(responseLine)) {
                WorkerRunResult result = readWorkerResultFromProtocolLine(responseLine, logFile, command);
                result.timedOut = false;
                return result;
            }

            if (WORKER_PROTOCOL_EOF.equals(responseLine) || !isAlive()) {
                WorkerRunResult result = new WorkerRunResult();
                result.success = false;
                result.timedOut = false;
                String extra = (readerFailureMessage == null || readerFailureMessage.trim().isEmpty())
                        ? ""
                        : (" ; reader=" + readerFailureMessage);
                result.message = "persistent worker exited unexpectedly; workerId=" + workerId + extra;
                result.logFile = logFile.toString();
                result.command = new ArrayList<String>(command);
                return result;
            }

            WorkerRunResult result = new WorkerRunResult();
            result.success = false;
            result.timedOut = true;
            result.message = "time_out: more than " + workerTimeout + " milliseconds";
            result.logFile = logFile.toString();
            result.command = Arrays.asList(
                    "persistent-worker-" + workerId,
                    testSetName,
                    methodSignature,
                    mutantName,
                    join(selectedTests)
            );
            forceCloseOnTimeout();
            return result;
        }

        private synchronized void forceCloseOnTimeout() {
            if (closed) {
                return;
            }
            closed = true;

            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            try {
                writer.close();
            } catch (IOException ignored) {
            }
            try {
                reader.close();
            } catch (IOException ignored) {
            }

            if (responseReaderThread != null && responseReaderThread.isAlive()) {
                responseReaderThread.interrupt();
            }
            responseQueue.offer(WORKER_PROTOCOL_EOF);
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                synchronized (writer) {
                    if (isAlive()) {
                        writer.write("QUIT");
                        writer.newLine();
                        writer.flush();
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                writer.close();
            } catch (IOException ignored) {
            }
            try {
                reader.close();
            } catch (IOException ignored) {
            }

            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (responseReaderThread != null && responseReaderThread.isAlive()) {
                responseReaderThread.interrupt();
            }
            responseQueue.offer(WORKER_PROTOCOL_EOF);
        }
    }

    private static class RecycleDecision {
        private final boolean shouldRecycle;
        private final String reason;
        private final int threshold;

        private RecycleDecision(boolean shouldRecycle, String reason, int threshold) {
            this.shouldRecycle = shouldRecycle;
            this.reason = reason;
            this.threshold = threshold;
        }

        private static RecycleDecision none() {
            return new RecycleDecision(false, "", 0);
        }

        private static RecycleDecision method(int threshold) {
            return new RecycleDecision(true, "method", threshold);
        }

        private static RecycleDecision global(int threshold) {
            return new RecycleDecision(true, "global", threshold);
        }
    }

    private static class WorkerRunResult {
        boolean success;
        boolean timedOut;
        int exitCode;
        String status = "";
        String message = "";
        String logFile = "";
        List<String> testOrder = new ArrayList<String>();
        Map<String, String> results = new LinkedHashMap<String, String>();
        List<String> command = new ArrayList<String>();
    }

    private static class MutantTaskResult {
        String mutantName;
        WorkerRunResult workerRun;
        Map<String, String> mutantResults = new LinkedHashMap<String, String>();
        boolean killed;
    }

    private static Path getProjectLogDir(String resultModuleHome) throws IOException {
        Path logDir = Paths.get(resultModuleHome, LOG_DIR_NAME);
        Files.createDirectories(logDir);
        return logDir;
    }

    private static Path getProjectErrorLogFile(String resultModuleHome) throws IOException {
        return getProjectLogDir(resultModuleHome).resolve(ERROR_LOG_NAME);
    }

    private static Path createWorkerRunDir(String resultModuleHome,
                                           String mode,
                                           String methodSignature,
                                           String mutantName) throws IOException {
        String safeMethod = sanitizeFileName(methodSignature);
        String safeMutant = sanitizeFileName(mutantName);
        Path root = getProjectLogDir(resultModuleHome).resolve(WORKER_ROOT_NAME);
        Files.createDirectories(root);
        return Files.createTempDirectory(root, mode + "_" + safeMethod + "_" + safeMutant + "_");
    }

    private static String sanitizeFileName(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "NA";
        }
        String v = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (v.length() > 80) {
            v = v.substring(0, 80);
        }
        return v;
    }

    private static void logError(String resultModuleHome, String message, Throwable t) {
        if (resultModuleHome == null || resultModuleHome.trim().isEmpty()) {
            return;
        }
        try {
            Path errorFile = getProjectErrorLogFile(resultModuleHome);
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(System.currentTimeMillis()).append("] ")
                    .append(message == null ? "" : message)
                    .append(System.lineSeparator());
            if (t != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                sb.append(sw.toString());
            }
            sb.append(System.lineSeparator());

            Files.write(
                    errorFile,
                    sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }

    private static void printInCodeConfigBanner() {
        System.out.println("[CONFIG] testMode=" + InCodeConfig.TEST_MODE
                + " ; excelPath=" + InCodeConfig.EXCEL_PATH
                + " ; timeoutMillis=" + InCodeConfig.BASE_TIMEOUT_MILLIS
                + " ; workers=" + MAX_WORKERS
                + " ; workerXmx=" + WORKER_XMX
                + " ; batchSizeOverride=" + METHOD_BATCH_SIZE_OVERRIDE
                + " ; recyclePerBatch=" + RECYCLE_POOL_EVERY_BATCH
                + " ; globalRecycleEvery=" + GLOBAL_WORKER_RECYCLE_EVERY
                + " ; stallAbortMillis=" + STALL_ABORT_MILLIS);
    }

    public static void main(String[] args) throws Exception {
        printInCodeConfigBanner();
        runBatchTraditionalFromExcel(InCodeConfig.EXCEL_PATH, InCodeConfig.BASE_TIMEOUT_MILLIS);
    }
}
