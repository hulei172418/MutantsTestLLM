package mujava.testgenerator.tools;

/**
 * Runtime controls for concurrent LLM access and retry behavior.
 *
 * Budget design:
 * - The initial budget is the base budget.
 * - Repair budgets are derived by ratios instead of fixed absolute numbers.
 * - Error-aware ratios are applied on top of the stage ratio.
 * - Hard ceilings are also ratio-derived from the initial budget, unless explicitly overridden.
 */
public final class LlmRuntimeConfig {
    int threadCount;
    int maxApiAttempts;
    boolean skipExistingCompiledTests;
    boolean forceRegenerate;

    boolean dynamicBudgetEnabled;
    String oversizePromptPolicy;

    int initialMaxInputTokens;
    int initialMaxInputChars;
    int initialMaxOutputTokens;

    double budgetCharsPerToken;

    double repair1InputRatio;
    double repair1OutputRatio;
    double repair2InputRatio;
    double repair2OutputRatio;

    double hardInputRatio;
    double hardOutputRatio;
    int hardMaxInputTokensOverride;
    int hardMaxInputCharsOverride;
    int hardMaxOutputTokensOverride;

    double constructorInputRatio;
    double constructorOutputRatio;
    double methodNotFoundInputRatio;
    double methodNotFoundOutputRatio;
    double stubAbstractInputRatio;
    double stubAbstractOutputRatio;
    double longJavacInputRatio;
    double longJavacOutputRatio;
    double reasoningOnlyOutputRatio;

    public int getThreadCount() { return threadCount; }
    public int getMaxApiAttempts() { return maxApiAttempts; }
    public boolean isSkipExistingCompiledTests() { return skipExistingCompiledTests; }
    public boolean isForceRegenerate() { return forceRegenerate; }

    public boolean isDynamicBudgetEnabled() { return dynamicBudgetEnabled; }
    public String getOversizePromptPolicy() { return oversizePromptPolicy; }

    public int getInitialMaxInputTokens() { return initialMaxInputTokens; }
    public int getInitialMaxInputChars() { return effectiveInputChars(initialMaxInputTokens, initialMaxInputChars); }
    public int getInitialMaxOutputTokens() { return initialMaxOutputTokens; }

    public double getBudgetCharsPerToken() { return budgetCharsPerToken; }

    public double getRepair1InputRatio() { return repair1InputRatio; }
    public double getRepair1OutputRatio() { return repair1OutputRatio; }
    public double getRepair2InputRatio() { return repair2InputRatio; }
    public double getRepair2OutputRatio() { return repair2OutputRatio; }

    public double getHardInputRatio() { return hardInputRatio; }
    public double getHardOutputRatio() { return hardOutputRatio; }

    public double getConstructorInputRatio() { return constructorInputRatio; }
    public double getConstructorOutputRatio() { return constructorOutputRatio; }
    public double getMethodNotFoundInputRatio() { return methodNotFoundInputRatio; }
    public double getMethodNotFoundOutputRatio() { return methodNotFoundOutputRatio; }
    public double getStubAbstractInputRatio() { return stubAbstractInputRatio; }
    public double getStubAbstractOutputRatio() { return stubAbstractOutputRatio; }
    public double getLongJavacInputRatio() { return longJavacInputRatio; }
    public double getLongJavacOutputRatio() { return longJavacOutputRatio; }
    public double getReasoningOnlyOutputRatio() { return reasoningOnlyOutputRatio; }

    public int getHardMaxInputTokens() {
        if (hardMaxInputTokensOverride > 0) {
            return hardMaxInputTokensOverride;
        }
        return scaleCeil(initialMaxInputTokens, hardInputRatio);
    }

    public int getHardMaxInputChars() {
        if (hardMaxInputCharsOverride > 0) {
            return hardMaxInputCharsOverride;
        }
        return scaleCeil(getInitialMaxInputChars(), hardInputRatio);
    }

    public int getHardMaxOutputTokens() {
        if (hardMaxOutputTokensOverride > 0) {
            return hardMaxOutputTokensOverride;
        }
        return scaleCeil(initialMaxOutputTokens, hardOutputRatio);
    }

    public int getRepair1MaxInputTokens() {
        return capInputTokens(scaleCeil(initialMaxInputTokens, repair1InputRatio));
    }

    public int getRepair1MaxInputChars() {
        return capInputChars(scaleCeil(getInitialMaxInputChars(), repair1InputRatio));
    }

    public int getRepair1MaxOutputTokens() {
        return capOutputTokens(scaleCeil(initialMaxOutputTokens, repair1OutputRatio));
    }

    public int getRepair2MaxInputTokens() {
        return capInputTokens(scaleCeil(initialMaxInputTokens, repair2InputRatio));
    }

    public int getRepair2MaxInputChars() {
        return capInputChars(scaleCeil(getInitialMaxInputChars(), repair2InputRatio));
    }

    public int getRepair2MaxOutputTokens() {
        return capOutputTokens(scaleCeil(initialMaxOutputTokens, repair2OutputRatio));
    }

    int effectiveInputChars(int tokens, int configuredChars) {
        if (configuredChars > 0) {
            return configuredChars;
        }
        return scaleCeil(tokens, budgetCharsPerToken);
    }

    int capInputTokens(int tokens) {
        int hard = getHardMaxInputTokens();
        return hard > 0 ? Math.min(tokens, hard) : tokens;
    }

    int capInputChars(int chars) {
        int hard = getHardMaxInputChars();
        return hard > 0 ? Math.min(chars, hard) : chars;
    }

    int capOutputTokens(int tokens) {
        int hard = getHardMaxOutputTokens();
        return hard > 0 ? Math.min(tokens, hard) : tokens;
    }

    static int scaleCeil(int base, double ratio) {
        if (base <= 0) {
            return 0;
        }
        if (ratio <= 0) {
            ratio = 1.0;
        }
        return Math.max(1, (int) Math.ceil(base * ratio));
    }
}
