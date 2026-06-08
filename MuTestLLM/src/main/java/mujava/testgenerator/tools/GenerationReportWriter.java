package mujava.testgenerator.tools;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static mujava.testgenerator.tools.CommonUtils.nullToEmpty;

/**
 * Writes generation summaries and JSONL records.
 */
public final class GenerationReportWriter {
    private GenerationReportWriter() {
    }

    public static void writeSummary(Path path, Result result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("generatedAt=").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append('\n');
        sb.append("taskId=").append(nullToEmpty(result.taskId)).append('\n');
        sb.append("targetClassName=").append(nullToEmpty(result.targetClassName)).append('\n');
        sb.append("methodSignature=").append(nullToEmpty(result.methodSignature)).append('\n');
        sb.append("mutantName=").append(nullToEmpty(result.mutantName)).append('\n');
        sb.append("testSetName=").append(nullToEmpty(result.testSetName)).append('\n');
        sb.append("testJavaFile=").append(nullToEmpty(result.testJavaFile)).append('\n');
        sb.append("reportDir=").append(nullToEmpty(result.reportDir)).append('\n');
        sb.append("resultJsonlFile=").append(nullToEmpty(result.resultJsonlFile)).append('\n');
        sb.append("compiled=").append(result.compiled).append('\n');
        sb.append("compileRounds=").append(result.compileRounds).append('\n');
        sb.append("failureReason=").append(nullToEmpty(result.failureReason)).append('\n');
        sb.append("skippedExistingCompiledTest=").append(result.skippedExistingCompiledTest).append('\n');
        if (result.timing != null) {
            sb.append("evidenceMillis=").append(result.timing.evidenceMillis).append('\n');
            sb.append("promptMillis=").append(result.timing.promptMillis).append('\n');
            sb.append("llmMillis=").append(result.timing.llmMillis).append('\n');
            sb.append("compileMillis=").append(result.timing.compileMillis).append('\n');
            sb.append("repairPromptMillis=").append(result.timing.repairPromptMillis).append('\n');
            sb.append("totalMillis=").append(result.timing.totalMillis).append('\n');
            sb.append("llmCalls=").append(result.timing.llmCalls).append('\n');
            sb.append("compileCalls=").append(result.timing.compileCalls).append('\n');
            sb.append("repairRounds=").append(result.timing.repairRounds).append('\n');
            sb.append("llmCacheHit=").append(result.timing.llmCacheHit).append('\n');
            sb.append("skippedExistingCompiled=").append(result.timing.skippedExistingCompiled).append('\n');
        }
        FileTextUtils.writeText(path, sb.toString());
    }

    public static synchronized void appendResultJsonl(Path path, Result result) throws IOException {
        Files.createDirectories(path.getParent());
        JSONObject obj = new JSONObject();
        obj.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        obj.put("taskId", nullToEmpty(result.taskId));
        obj.put("targetClassName", nullToEmpty(result.targetClassName));
        obj.put("methodSignature", nullToEmpty(result.methodSignature));
        obj.put("mutantName", nullToEmpty(result.mutantName));
        obj.put("testSetName", nullToEmpty(result.testSetName));
        obj.put("testJavaFile", nullToEmpty(result.testJavaFile));
        obj.put("reportDir", nullToEmpty(result.reportDir));
        obj.put("compiled", result.compiled);
        obj.put("compileRounds", result.compileRounds);
        obj.put("failureReason", nullToEmpty(result.failureReason));
        obj.put("skippedExistingCompiledTest", result.skippedExistingCompiledTest);
        if (result.timing != null) {
            obj.put("evidenceMillis", result.timing.evidenceMillis);
            obj.put("promptMillis", result.timing.promptMillis);
            obj.put("llmMillis", result.timing.llmMillis);
            obj.put("compileMillis", result.timing.compileMillis);
            obj.put("repairPromptMillis", result.timing.repairPromptMillis);
            obj.put("totalMillis", result.timing.totalMillis);
            obj.put("llmCalls", result.timing.llmCalls);
            obj.put("compileCalls", result.timing.compileCalls);
            obj.put("repairRounds", result.timing.repairRounds);
            obj.put("llmCacheHit", result.timing.llmCacheHit);
            obj.put("skippedExistingCompiled", result.timing.skippedExistingCompiled);
        }
        String line = obj.toString() + System.lineSeparator();
        Files.write(path, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
