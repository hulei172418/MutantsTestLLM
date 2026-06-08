package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Selects a stage-aware and error-aware input/output budget.
 *
 * This version uses ratio-based scaling instead of hard-coded repair budgets:
 *
 *   concreteBudget = initialBudget * stageRatio * errorRatio
 *
 * The result is capped by ratio-derived hard ceilings. This keeps the budget distribution
 * adjustable from llm.properties without changing Java code.
 */
public final class PromptBudgetPolicy {
    private PromptBudgetPolicy() {
    }

    public static PromptBudgetProfile resolve(LlmRuntimeConfig cfg,
                                              int attempt,
                                              String compileError,
                                              LlmCallResult previousLlmResult) {
        if (cfg == null) {
            return defaultStaticProfile();
        }
        if (!cfg.isDynamicBudgetEnabled()) {
            return new PromptBudgetProfile(
                    "static",
                    cfg.getInitialMaxInputTokens(),
                    cfg.getInitialMaxInputChars(),
                    cfg.getInitialMaxOutputTokens(),
                    cfg.getHardMaxInputTokens(),
                    cfg.getHardMaxInputChars(),
                    cfg.getHardMaxOutputTokens(),
                    cfg.getOversizePromptPolicy(),
                    false,
                    1.0,
                    1.0
            );
        }

        double inputScale = stageInputRatio(cfg, attempt);
        double outputScale = stageOutputRatio(cfg, attempt);
        StringBuilder name = new StringBuilder(stageName(attempt));

        String err = compileError == null ? "" : compileError.toLowerCase(Locale.ROOT);

        if (attempt > 0) {
            if (containsAny(err, "constructor", "cannot be applied", "csvformat", "builder", "private access")) {
                inputScale *= cfg.getConstructorInputRatio();
                outputScale *= cfg.getConstructorOutputRatio();
                name.append("*constructor_or_builder");
            }
            if (containsAny(err, "cannot find symbol", "method")) {
                inputScale *= cfg.getMethodNotFoundInputRatio();
                outputScale *= cfg.getMethodNotFoundOutputRatio();
                name.append("*method_not_found");
            }
            if (containsAny(err, "abstract", "does not override", "overridden method is final", "super must be first statement")) {
                inputScale *= cfg.getStubAbstractInputRatio();
                outputScale *= cfg.getStubAbstractOutputRatio();
                name.append("*stub_or_abstract");
            }
            if (err.length() > 6_000 || containsAny(err, "[compilecp]", "[javac output]")) {
                inputScale *= cfg.getLongJavacInputRatio();
                outputScale *= cfg.getLongJavacOutputRatio();
                name.append("*long_javac");
            }
            if (isReasoningOnly(previousLlmResult)) {
                outputScale *= cfg.getReasoningOnlyOutputRatio();
                name.append("*reasoning_only_retry");
            }
        }

        int maxInputTokens = cfg.capInputTokens(LlmRuntimeConfig.scaleCeil(cfg.getInitialMaxInputTokens(), inputScale));
        int maxInputChars = cfg.capInputChars(LlmRuntimeConfig.scaleCeil(cfg.getInitialMaxInputChars(), inputScale));
        int maxOutputTokens = cfg.capOutputTokens(LlmRuntimeConfig.scaleCeil(cfg.getInitialMaxOutputTokens(), outputScale));

        return new PromptBudgetProfile(
                name.toString(),
                maxInputTokens,
                maxInputChars,
                maxOutputTokens,
                cfg.getHardMaxInputTokens(),
                cfg.getHardMaxInputChars(),
                cfg.getHardMaxOutputTokens(),
                cfg.getOversizePromptPolicy(),
                true,
                inputScale,
                outputScale
        );
    }

    private static PromptBudgetProfile defaultStaticProfile() {
        int hardInTokens = LlmRuntimeConfig.scaleCeil(GeneratorDefaults.DEFAULT_INITIAL_MAX_INPUT_TOKENS,
                GeneratorDefaults.DEFAULT_HARD_INPUT_RATIO);
        int hardInChars = LlmRuntimeConfig.scaleCeil(GeneratorDefaults.DEFAULT_INITIAL_MAX_INPUT_CHARS,
                GeneratorDefaults.DEFAULT_HARD_INPUT_RATIO);
        int hardOut = LlmRuntimeConfig.scaleCeil(GeneratorDefaults.DEFAULT_INITIAL_MAX_OUTPUT_TOKENS,
                GeneratorDefaults.DEFAULT_HARD_OUTPUT_RATIO);
        return new PromptBudgetProfile(
                "static-default",
                GeneratorDefaults.DEFAULT_INITIAL_MAX_INPUT_TOKENS,
                GeneratorDefaults.DEFAULT_INITIAL_MAX_INPUT_CHARS,
                GeneratorDefaults.DEFAULT_INITIAL_MAX_OUTPUT_TOKENS,
                hardInTokens,
                hardInChars,
                hardOut,
                GeneratorDefaults.DEFAULT_OVERSIZE_PROMPT_POLICY,
                false,
                1.0,
                1.0
        );
    }

    private static String stageName(int attempt) {
        if (attempt <= 0) {
            return "initial";
        }
        if (attempt == 1) {
            return "repair1";
        }
        return "repair2plus";
    }

    private static double stageInputRatio(LlmRuntimeConfig cfg, int attempt) {
        if (attempt <= 0) {
            return 1.0;
        }
        if (attempt == 1) {
            return cfg.getRepair1InputRatio();
        }
        return cfg.getRepair2InputRatio();
    }

    private static double stageOutputRatio(LlmRuntimeConfig cfg, int attempt) {
        if (attempt <= 0) {
            return 1.0;
        }
        if (attempt == 1) {
            return cfg.getRepair1OutputRatio();
        }
        return cfg.getRepair2OutputRatio();
    }

    public static boolean isReasoningOnly(LlmCallResult result) {
        if (result == null || result.response == null || result.response.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(result.response);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return false;
            }
            JSONObject choice0 = choices.optJSONObject(0);
            if (choice0 == null) {
                return false;
            }
            JSONObject msg = choice0.optJSONObject("message");
            if (msg == null) {
                return false;
            }
            String content = msg.optString("content", "").trim();
            String reasoning = msg.optString("reasoning_content", "").trim();
            if (content.isEmpty() && !reasoning.isEmpty()) {
                return true;
            }
            JSONObject usage = root.optJSONObject("usage");
            JSONObject details = usage == null ? null : usage.optJSONObject("completion_tokens_details");
            int reasoningTokens = details == null ? 0 : details.optInt("reasoning_tokens", 0);
            int completionTokens = usage == null ? 0 : usage.optInt("completion_tokens", 0);
            return completionTokens > 0 && reasoningTokens >= completionTokens && content.length() < 64;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean containsAny(String s, String... needles) {
        if (s == null || needles == null) {
            return false;
        }
        for (String n : needles) {
            if (n != null && !n.isEmpty() && s.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
