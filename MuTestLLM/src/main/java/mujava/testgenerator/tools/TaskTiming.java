package mujava.testgenerator.tools;

/**
 * Stage-level timing and counters for one LLM test-generation task.
 */
public final class TaskTiming {
    public long evidenceMillis;
    public long promptMillis;
    public long llmMillis;
    public long compileMillis;
    public long repairPromptMillis;
    public long totalMillis;

    public int llmCalls;
    public int compileCalls;
    public int repairRounds;
    public boolean llmCacheHit;
    public boolean skippedExistingCompiled;

    public String toLogLine() {
        return "evidence=" + evidenceMillis + "ms"
                + ", prompt=" + promptMillis + "ms"
                + ", llm=" + llmMillis + "ms"
                + ", compile=" + compileMillis + "ms"
                + ", repairPrompt=" + repairPromptMillis + "ms"
                + ", total=" + totalMillis + "ms"
                + ", llmCalls=" + llmCalls
                + ", compileCalls=" + compileCalls
                + ", repairRounds=" + repairRounds
                + ", llmCacheHit=" + llmCacheHit
                + ", skippedExistingCompiled=" + skippedExistingCompiled;
    }
}
