package mujava.testgenerator;

import mujava.MutationSystem;
import org.json.JSONObject;

import mujava.testgenerator.tools.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static mujava.cmd.TestRunner9_MultiProcess_batched.*;
import static mujava.cmd.TestRunner9_MultiProcess_batched.resolveSourceModuleHome;
import static mujava.testgenerator.tools.CommonUtils.oneLine;
import static mujava.testgenerator.tools.FileTextUtils.writeText;
import static mujava.testgenerator.tools.TestNameUtils.*;

/**
 * LLM-based JUnit4 test generator with prompt-side evidence compression.
 *
 * This class only orchestrates the closed loop:
 * 1) read output.json;
 * 2) build compact PromptEvidence;
 * 3) build initial or repair prompt;
 * 4) call an OpenAI-compatible LLM with file-cache and bounded retry;
 * 5) write Java test source;
 * 6) javac compile;
 * 7) retry with a targeted repair prompt when compilation fails.
 */
public final class LLMTestGeneratorBatch {

    private LLMTestGeneratorBatch() {
    }

    private static final int DEFAULT_TIMEOUT_MILLIS = GeneratorDefaults.DEFAULT_TIMEOUT_MILLIS;
    private static final int DEFAULT_MAX_REPAIR_ROUNDS = GeneratorDefaults.DEFAULT_MAX_REPAIR_ROUNDS;

    public static void main(String[] args) throws Exception {
        String excelPath = "../MutantParse/data/commons-cli-1.11.0_oot.xlsx";
        excelPath = "../MutantParse/data/missing_llm_class_rows.xlsx";
        List<List<Object>> excelData = readExcelFile(excelPath);
        if (excelData.isEmpty()) {
            System.err.println("[ERROR] Excel 数据为空: " + excelPath);
            return;
        }

        String testMode = "llm"; // origin、evosuite、llm等测试集合
        setTestSetMode(testMode);

        bootstrapMuJavaConfigProperty(excelPath);

        ModelConfig modelConfig = ModelConfigLoader.load();
        LlmRuntimeConfig runtimeConfig = LlmRuntimeConfigLoader.load();
        System.out.println("[LLM-CONFIG] provider=" + modelConfig.getProvider()
                + ", model=" + modelConfig.getModel()
                + ", threads=" + runtimeConfig.getThreadCount()
                + ", apiMaxAttempts=" + runtimeConfig.getMaxApiAttempts()
                + ", skipExistingCompiled=" + runtimeConfig.isSkipExistingCompiledTests()
                + ", forceRegenerate=" + runtimeConfig.isForceRegenerate()
                + ", dynamicBudget=" + runtimeConfig.isDynamicBudgetEnabled()
                + ", initialBudget=" + runtimeConfig.getInitialMaxInputTokens() + "/" + runtimeConfig.getInitialMaxOutputTokens()
                + ", repair1Ratio=" + runtimeConfig.getRepair1InputRatio() + "/" + runtimeConfig.getRepair1OutputRatio()
                + ", repair1Budget=" + runtimeConfig.getRepair1MaxInputTokens() + "/" + runtimeConfig.getRepair1MaxOutputTokens()
                + ", repair2Ratio=" + runtimeConfig.getRepair2InputRatio() + "/" + runtimeConfig.getRepair2OutputRatio()
                + ", repair2Budget=" + runtimeConfig.getRepair2MaxInputTokens() + "/" + runtimeConfig.getRepair2MaxOutputTokens()
                + ", hardBudget=" + runtimeConfig.getHardMaxInputTokens() + "/" + runtimeConfig.getHardMaxOutputTokens()
                + ", oversizePolicy=" + runtimeConfig.getOversizePromptPolicy());

        List<Request> requests = buildRequestsFromExcel(excelData);
        runRequestsConcurrently(requests, modelConfig, runtimeConfig);
    }

    private static List<Request> buildRequestsFromExcel(List<List<Object>> excelData) throws Exception {
        List<Request> requests = new ArrayList<Request>();
        Set<String> classSet = new HashSet<String>();
        Set<Integer> targetRows = new HashSet<>(
                Arrays.asList(232640));
                // Arrays.asList(405, 2570, 3791, 3347, 5555, 12931, 12943, 13737, 13971, 15764, 16400, 20898));

        for (int i = 1; i < excelData.size(); i++) {
            // if (!targetRows.contains(i))
            //     continue;
            // if(!"ant-1.10.12".equals(String.valueOf(excelData.get(i).get(7)).trim()))
            //     continue;
            String p = String.valueOf(excelData.get(i).get(7)).trim();
            if (!p.equals("ant-1.10.12")
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

            String resultModuleHome;
            String sourceRootHome;
            String sourceModuleHome;
            String operator = String.valueOf(excelData.get(i).get(0)).trim();
            String method = String.valueOf(excelData.get(i).get(2)).trim();
            String class_f = String.valueOf(excelData.get(i).get(4)).trim();
            String packageName = String.valueOf(excelData.get(i).get(6)).trim();
            String rawFilePath = String.valueOf(excelData.get(i).get(8)).trim();
            String original_graph_path = String.valueOf(excelData.get(i).get(9)).trim();
            String originJavaPath = new File(original_graph_path).getParent().replace("\\", "/").replace("//?/", "");
            String mutant_graph_path = String.valueOf(excelData.get(i).get(10)).trim();
            String outputJsonPath = new File(mutant_graph_path).getParent().replace("\\", "/").replace("//?/", "");
            String mutantJavaPath = new File(mutant_graph_path).getParent().replace("\\", "/").replace("//?/", "");

            String filepath = new File(rawFilePath).getParent().replace("\\", "/").replace("//?/", "");
            String workDir = getCurr(filepath);

            resultModuleHome = Paths.get(workDir).normalize().toString();
            Path resultPath = Paths.get(resultModuleHome).normalize();
            Path outerRoot;
            String pathStr = resultPath.toString().replace('\\', '/');

            String tag = resultModuleHome + "/result/" + packageName;
            // if (classSet.contains(tag)) {
            //     continue;
            // }
            // classSet.add(tag);

            if (pathStr.matches(".*(?:commons-math|commons-numbers).*")) {
                outerRoot = resultPath.getParent();
            } else {
                outerRoot = resultPath.getFileName();
            }

            sourceRootHome = resolveSourceRootHome(resultModuleHome, outerRoot.getFileName().toString());
            sourceModuleHome = resolveSourceModuleHome(resultModuleHome, sourceRootHome);

            Request request = new Request();
            request.taskId = "row-" + i;
            request.sourceModuleHome = sourceModuleHome;
            request.resultModuleHome = resultModuleHome;
            request.targetClassName = packageName;
            request.methodSignature = method;
            request.mutantName = operator;
            request.outputJsonPath = normalizePath(outputJsonPath + "/graph/output.json");
            request.originJavaPath = normalizePath(originJavaPath + "/../original/" + class_f + ".java");
            request.mutatedJavaPath = normalizePath(mutantJavaPath + "/" + class_f + ".java");
            request.timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
            request.maxRepairRounds = DEFAULT_MAX_REPAIR_ROUNDS;

            System.out.println("[LLM-TASK-BUILD] " + request.taskId + ": " + mutant_graph_path);
            requests.add(request);
        }

        return requests;
    }

    private static void runRequestsConcurrently(List<Request> requests,
                                                final ModelConfig modelConfig,
                                                final LlmRuntimeConfig runtimeConfig) throws Exception {
        if (requests == null || requests.isEmpty()) {
            System.out.println("[LLM-TASK] no request to run.");
            return;
        }

        int threads = Math.max(1, runtimeConfig.getThreadCount());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<Result> completion = new ExecutorCompletionService<Result>(pool);

        int submitted = 0;
        int compiledSucceed = 0;
        for (final Request request : requests) {
            request.maxLlmApiAttempts = runtimeConfig.getMaxApiAttempts();
            request.skipIfCompiledExists = runtimeConfig.isSkipExistingCompiledTests()
                    && !runtimeConfig.isForceRegenerate();
            completion.submit(new Callable<Result>() {
                @Override
                public Result call() {
                    return generateAndCompileSafely(request, modelConfig, runtimeConfig);
                }
            });
            submitted++;
        }

        try {
            for (int done = 1; done <= submitted; done++) {
                Future<Result> future = completion.take();
                Result result = future.get();
                String status = result.compiled ? "compiled" : "failed";
                compiledSucceed += result.compiled ? 1 : 0;
                if (result.skippedExistingCompiledTest) {
                    status = "skipped-existing";
                }
                System.out.println("[LLM-TASK-DONE] done=" + done + "/" + submitted
                        + " taskId=" + result.taskId
                        + " status=" + status
                        + " test=" + result.testSetName
                        + " " + result.timing.toLogLine());
                if (result.failureReason != null && !result.failureReason.trim().isEmpty()) {
                    System.out.println("[LLM-TASK-FAILURE] taskId=" + result.taskId
                            + " reason=" + result.failureReason);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        String compiledRateStr = String.format("%.2f%%", submitted == 0 ? 0.0 : compiledSucceed * 100.0 / submitted);
        System.out.println("Compiled Succeed: " + compiledSucceed +"("+compiledRateStr+")");
    }

    private static Result generateAndCompileSafely(Request request,
                                                   ModelConfig modelConfig,
                                                   LlmRuntimeConfig runtimeConfig) {
        try {
            return generateAndCompile(request, modelConfig, runtimeConfig);
        } catch (Exception e) {
            Result result = new Result();
            result.taskId = request == null ? "" : request.taskId;
            result.targetClassName = request == null ? "" : request.targetClassName;
            result.methodSignature = request == null ? "" : request.methodSignature;
            result.mutantName = request == null ? "" : request.mutantName;
            result.compiled = false;
            result.compileRounds = 0;
            result.failureReason = "Task crashed: " + oneLine(String.valueOf(e.getMessage()));
            return result;
        }
    }

    /**
     * Generate a JUnit4 test with LLM and compile it.
     *
     * This is the stable API you can call from your Excel loop.
     * It intentionally does NOT execute MuJava/TestRunner9 and does NOT fill killed/live fields.
     */
    public static Result generateAndCompile(Request request) throws Exception {
        return generateAndCompile(request, ModelConfigLoader.load(), LlmRuntimeConfigLoader.load());
    }

    public static Result generateAndCompile(Request request,
                                            ModelConfig modelConfig,
                                            LlmRuntimeConfig runtimeConfig) throws Exception {
        RequestValidator.validate(request);
        if (modelConfig == null) {
            modelConfig = ModelConfigLoader.load();
        }
        if (runtimeConfig == null) {
            runtimeConfig = LlmRuntimeConfigLoader.load();
        }

        Result result = new Result();
        result.taskId = request.taskId;
        result.targetClassName = request.targetClassName;
        result.methodSignature = request.methodSignature;
        result.mutantName = request.mutantName;

        long t0 = System.nanoTime();
        JSONObject fullJson = new JSONObject(FileTextUtils.readUtf8(Paths.get(request.outputJsonPath)));
        PromptEvidence evidence = PromptEvidence.fromFullOutput(fullJson);
        result.timing.evidenceMillis += elapsedMillis(t0);

        String projectCp = ProjectClasspathCache.getProjectClasspath(request.sourceModuleHome);

        Path llmsRoot = Paths.get(request.resultModuleHome, MutationSystem.TESTSET_MODE_LLMS);
        Path testSrcRoot = llmsRoot.resolve("src");
        Path testClassesRoot = llmsRoot.resolve("classes");
        Path reportRoot = llmsRoot.resolve("report").resolve("logs");
        Path apiCacheRoot = reportRoot.resolve("llm_api_cache");
        Files.createDirectories(testSrcRoot);
        Files.createDirectories(testClassesRoot);
        Files.createDirectories(reportRoot);
        if (!runtimeConfig.isForceRegenerate()) {
            Files.createDirectories(apiCacheRoot);
        }

        result.reportDir = reportRoot.toAbsolutePath().normalize().toString();

        String testSetName = buildGeneratedTestFqn(request.targetClassName, request.mutantName);
        result.testSetName = testSetName;
        Path javaFile = toJavaFile(testSrcRoot, testSetName);
        result.testJavaFile = javaFile.toAbsolutePath().normalize().toString();

        Path compactEvidenceFile = reportRoot.resolve(fileBaseName(testSetName) + "__compact_evidence.json");
        Path promptFile = reportRoot.resolve(fileBaseName(testSetName) + "__prompt.txt");
        Path resultJsonlFile = reportRoot.resolve("..").resolve("llm_generation_results.jsonl");
        result.resultJsonlFile = resultJsonlFile.toAbsolutePath().normalize().toString();

        if (!runtimeConfig.isForceRegenerate()
                && request.skipIfCompiledExists
                && generatedTestAlreadyCompiled(javaFile, testClassesRoot, testSetName)) {
            result.compiled = true;
            result.compileRounds = 0;
            result.skippedExistingCompiledTest = true;
            result.timing.skippedExistingCompiled = true;
            result.failureReason = "";
            finishTiming(result);
            GenerationReportWriter.writeSummary(reportRoot.resolve(fileBaseName(testSetName) + "__generation_summary.txt"), result);
            GenerationReportWriter.appendResultJsonl(resultJsonlFile, result);
            return result;
        }

        writeText(compactEvidenceFile, evidence.toJson().toString(2));

        if (evidence.skipTestGeneration) {
            result.compiled = false;
            result.compileRounds = 0;
            result.failureReason = "Skipped by output.json: " + oneLine(evidence.skipReason);
            finishTiming(result);
            GenerationReportWriter.writeSummary(reportRoot.resolve(fileBaseName(testSetName) + "__generation_summary.txt"), result);
            GenerationReportWriter.appendResultJsonl(resultJsonlFile, result);
            return result;
        }

        CachedLlmClient client = new CachedLlmClient(
                new LlmClient(modelConfig),
                apiCacheRoot,
                modelConfig,
                Math.max(1, request.maxLlmApiAttempts)
        );

        t0 = System.nanoTime();
        PromptBudgetProfile initialProfile = PromptBudgetPolicy.resolve(
                runtimeConfig,
                0,
                "",
                null
        );
        String prompt = InitialPromptBuilder.build(request, evidence, testSetName);
        prompt = EvidenceAwarePromptBudgeter.enforce(
                prompt,
                initialProfile,
                result.taskId,
                "initial"
        );
        result.timing.promptMillis += elapsedMillis(t0);
        writeText(promptFile, prompt);

        String candidateCode = null;
        String lastCompileError = "";
        LlmCallResult lastLlmResult = null;
        int rounds = 0;

        for (int attempt = 0; attempt <= request.maxRepairRounds; attempt++) {
            rounds++;
            String rawResponse;
            LlmCallResult llmResult;
            Path responseFile = reportRoot.resolve(fileBaseName(testSetName) + "__response_" + attempt + ".json");
            if (attempt == 0) {
                llmResult = generateOrReuseLlmResponse(
                        client,
                        prompt,
                        responseFile,
                        runtimeConfig.isForceRegenerate(),
                        initialProfile.getMaxOutputTokens()
                );
            } else {
                t0 = System.nanoTime();
                PromptBudgetProfile repairProfile = PromptBudgetPolicy.resolve(
                        runtimeConfig,
                        attempt,
                        lastCompileError,
                        lastLlmResult
                );
                String repairPrompt = RepairPromptBuilder.build(
                        request,
                        evidence,
                        testSetName,
                        candidateCode,
                        lastCompileError
                );
                repairPrompt = EvidenceAwarePromptBudgeter.enforce(
                        repairPrompt,
                        repairProfile,
                        result.taskId,
                        "repair-" + attempt
                );
                result.timing.repairPromptMillis += elapsedMillis(t0);
                writeText(reportRoot.resolve(fileBaseName(testSetName) + "__repair_" + attempt + "_prompt.txt"), repairPrompt);
                llmResult = generateFreshLlmResponse(
                        client,
                        repairPrompt,
                        responseFile,
                        repairProfile.getMaxOutputTokens()
                );
            }
            rawResponse = llmResult.response;
            result.timing.llmMillis += llmResult.elapsedMillis;
            result.timing.llmCalls++;
            result.timing.llmCacheHit = result.timing.llmCacheHit || llmResult.cacheHit;
            lastLlmResult = llmResult;

            String finishReason = GeneratedCodeExtractor.finishReason(rawResponse);
            if ("length".equalsIgnoreCase(finishReason)) {
                lastCompileError = "LLM output truncated: finish_reason=length. "
                        + "Increase max_tokens or disable thinking.";
                writeText(
                        reportRoot.resolve(fileBaseName(testSetName) + "__compile_error_" + attempt + ".txt"),
                        lastCompileError
                );
                continue;
            }

            String extractedCode = GeneratedCodeExtractor.extractJavaCode(rawResponse);

            if (extractedCode == null || extractedCode.trim().isEmpty()) {
                lastCompileError = "LLM returned empty visible content. "
                        + "The response may contain reasoning_content only.";
                writeText(
                        reportRoot.resolve(fileBaseName(testSetName) + "__compile_error_" + attempt + ".txt"),
                        lastCompileError
                );
                continue;
            }

            candidateCode = GeneratedCodeExtractor.normalizeGeneratedTestCode(extractedCode, testSetName);

            if (!GeneratedCodeExtractor.containsExpectedTypeDeclaration(candidateCode, testSetName)) {
                lastCompileError = "LLM code does not contain expected test class: "
                        + fileBaseName(testSetName);
                writeText(
                        reportRoot.resolve(fileBaseName(testSetName) + "__compile_error_" + attempt + ".txt"),
                        lastCompileError
                );
                continue;
            }

            writeText(javaFile, candidateCode);

            try {
                t0 = System.nanoTime();
                GeneratedTestCompiler.compile(
                        request.sourceModuleHome,
                        javaFile,
                        testClassesRoot,
                        projectCp
                );

                Path expectedClassFile = testClassesRoot.resolve(
                        testSetName.replace('.', File.separatorChar) + ".class"
                );

                if (!Files.isRegularFile(expectedClassFile)) {
                    throw new IllegalStateException(
                            "javac returned success but expected class file not found: "
                                    + expectedClassFile.toAbsolutePath().normalize()
                    );
                }

                result.timing.compileMillis += elapsedMillis(t0);
                result.timing.compileCalls++;
                result.compiled = true;
                result.failureReason = "";
                break;
            } catch (Exception compileFailure) {
                result.timing.compileMillis += elapsedMillis(t0);
                result.timing.compileCalls++;
                lastCompileError = String.valueOf(compileFailure.getMessage());
                writeText(
                        reportRoot.resolve(fileBaseName(testSetName) + "__compile_error_" + attempt + ".txt"),
                        lastCompileError
                );
            }
        }

        result.compileRounds = rounds;
        result.timing.repairRounds = Math.max(0, rounds - 1);
        finishTiming(result);
        if (!result.compiled) {
            result.failureReason = "Compilation failed after repair rounds. Last error: " + oneLine(lastCompileError);
        }

        GenerationReportWriter.writeSummary(reportRoot.resolve(fileBaseName(testSetName) + "__generation_summary.txt"), result);
        GenerationReportWriter.appendResultJsonl(resultJsonlFile, result);
        return result;
    }



    private static LlmCallResult generateFreshLlmResponse(CachedLlmClient client,
                                                          String prompt,
                                                          Path responseFile,
                                                          int maxOutputTokens) throws Exception {
        /*
         * Repair rounds must not reuse stale response files or prompt-hash cache.
         * A repair prompt is produced because the previous code failed javac.
         * Reusing an old repair response can repeat the same compile error forever.
         */
        LlmCallResult result = client.generate(prompt, false, maxOutputTokens);
        writeText(responseFile, result.response);
        return result;
    }

    /**
     * The displayed total is the sum of the explicitly measured stages.
     * It is intentionally NOT wall-clock time, because wall-clock time in concurrent mode
     * may include scheduling, waiting, cache locking, and other hidden overheads.
     */
    private static void finishTiming(Result result) {
        if (result == null || result.timing == null) {
            return;
        }
        result.timing.totalMillis = result.timing.evidenceMillis
                + result.timing.promptMillis
                + result.timing.llmMillis
                + result.timing.compileMillis
                + result.timing.repairPromptMillis;
    }

    private static LlmCallResult generateOrReuseLlmResponse(CachedLlmClient client,
                                                          String prompt,
                                                          Path responseFile,
                                                          boolean forceRegenerate,
                                                          int maxOutputTokens) throws Exception {
        if (!forceRegenerate && Files.isRegularFile(responseFile)) {
            LlmCallResult result = new LlmCallResult();
            result.response = FileTextUtils.readUtf8(responseFile);
            result.cacheHit = true;
            result.attempts = 0;
            result.elapsedMillis = 0L;
            return result;
        }

        LlmCallResult result = client.generate(prompt, !forceRegenerate, maxOutputTokens);
        writeText(responseFile, result.response);
        return result;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean generatedTestAlreadyCompiled(Path javaFile,
                                                        Path testClassesRoot,
                                                        String testSetName) {
        if (!Files.isRegularFile(javaFile)) {
            return false;
        }
        Path classFile = testClassesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class");
        return Files.isRegularFile(classFile);
    }

    /**
     * Backward-compatible name for older callers. Despite the name, this method now only generates and compiles.
     */
    public static Result generateCompileAndRun(Request request) throws Exception {
        return generateAndCompile(request);
    }
}
