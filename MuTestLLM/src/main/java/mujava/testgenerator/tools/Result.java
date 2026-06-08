package mujava.testgenerator.tools;

/**
 * Output summary for one generation/compile task.
 */
public final class Result {
    public String taskId;
    public String targetClassName;
    public String methodSignature;
    public String mutantName;
    public String testSetName;
    public String testJavaFile;
    public String reportDir;
    public String resultJsonlFile;
    public boolean compiled;
    public int compileRounds;
    public String failureReason;
    public boolean skippedExistingCompiledTest;
    public final TaskTiming timing = new TaskTiming();
}
