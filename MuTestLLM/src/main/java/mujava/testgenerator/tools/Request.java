package mujava.testgenerator.tools;

/**
 * Input parameters for one LLM-based test-generation task.
 */
public final class Request {
    public String sourceModuleHome;
    public String resultModuleHome;
    public String targetClassName;
    public String methodSignature;
    public String mutantName;
    public String outputJsonPath;
    public String originJavaPath;
    public String mutatedJavaPath;
    public int timeoutMillis = GeneratorDefaults.DEFAULT_TIMEOUT_MILLIS;
    public int maxRepairRounds = GeneratorDefaults.DEFAULT_MAX_REPAIR_ROUNDS;

    /** Optional row/task label used only for logs and JSONL reporting. */
    public String taskId;

    /** Maximum HTTP attempts for one uncached LLM prompt. */
    public int maxLlmApiAttempts = GeneratorDefaults.DEFAULT_MAX_LLM_API_ATTEMPTS;

    /** When true, a task with an existing generated .java and .class is skipped. */
    public boolean skipIfCompiledExists = GeneratorDefaults.DEFAULT_SKIP_EXISTING_COMPILED_TESTS;
}
