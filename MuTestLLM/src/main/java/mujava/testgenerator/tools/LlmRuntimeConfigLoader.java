package mujava.testgenerator.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Loads runtime controls from environment variables, JVM properties, and llm.properties.
 */
public final class LlmRuntimeConfigLoader {
    private LlmRuntimeConfigLoader() {
    }

    public static LlmRuntimeConfig load() {
        Properties props = loadProperties("llm.properties");
        LlmRuntimeConfig cfg = new LlmRuntimeConfig();

        cfg.threadCount = parsePositiveInt(firstNonBlank(
                System.getenv("LLM_THREADS"),
                System.getProperty("llm.threads"),
                props.getProperty("llm.threads"),
                String.valueOf(GeneratorDefaults.DEFAULT_LLM_THREADS)
        ), "llm.threads");

        cfg.maxApiAttempts = parsePositiveInt(firstNonBlank(
                System.getenv("LLM_API_MAX_ATTEMPTS"),
                System.getProperty("llm.api.max.attempts"),
                props.getProperty("llm.api.max.attempts"),
                String.valueOf(GeneratorDefaults.DEFAULT_MAX_LLM_API_ATTEMPTS)
        ), "llm.api.max.attempts");

        cfg.skipExistingCompiledTests = parseBoolean(firstNonBlank(
                System.getenv("LLM_SKIP_EXISTING_COMPILED"),
                System.getProperty("llm.skip.existing.compiled"),
                props.getProperty("llm.skip.existing.compiled"),
                String.valueOf(GeneratorDefaults.DEFAULT_SKIP_EXISTING_COMPILED_TESTS)
        ), "llm.skip.existing.compiled");

        cfg.forceRegenerate = parseBoolean(firstNonBlank(
                System.getenv("LLM_FORCE_REGENERATE"),
                System.getProperty("llm.force.regenerate"),
                props.getProperty("llm.force.regenerate"),
                String.valueOf(GeneratorDefaults.DEFAULT_FORCE_REGENERATE)
        ), "llm.force.regenerate");

        cfg.dynamicBudgetEnabled = parseBoolean(firstNonBlank(
                System.getenv("LLM_DYNAMIC_BUDGET_ENABLED"),
                System.getProperty("llm.dynamic.budget.enabled"),
                props.getProperty("llm.dynamic.budget.enabled"),
                String.valueOf(GeneratorDefaults.DEFAULT_DYNAMIC_BUDGET_ENABLED)
        ), "llm.dynamic.budget.enabled");

        cfg.oversizePromptPolicy = firstNonBlank(
                System.getenv("LLM_OVERSIZE_PROMPT_POLICY"),
                System.getProperty("llm.oversize.prompt.policy"),
                props.getProperty("llm.oversize.prompt.policy"),
                GeneratorDefaults.DEFAULT_OVERSIZE_PROMPT_POLICY
        );

        cfg.initialMaxInputTokens = parsePositiveInt(firstNonBlank(
                System.getenv("LLM_INITIAL_MAX_INPUT_TOKENS"),
                System.getProperty("llm.initial.max.input.tokens"),
                props.getProperty("llm.initial.max.input.tokens"),
                props.getProperty("llm.max.input.tokens"),
                String.valueOf(GeneratorDefaults.DEFAULT_INITIAL_MAX_INPUT_TOKENS)
        ), "llm.initial.max.input.tokens");

        cfg.budgetCharsPerToken = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_CHARS_PER_TOKEN"),
                System.getProperty("llm.budget.chars.per.token"),
                props.getProperty("llm.budget.chars.per.token"),
                String.valueOf(GeneratorDefaults.DEFAULT_BUDGET_CHARS_PER_TOKEN)
        ), "llm.budget.chars.per.token");

        cfg.initialMaxInputChars = parseNonNegativeInt(firstNonBlank(
                System.getenv("LLM_INITIAL_MAX_INPUT_CHARS"),
                System.getProperty("llm.initial.max.input.chars"),
                props.getProperty("llm.initial.max.input.chars"),
                props.getProperty("llm.max.input.chars"),
                String.valueOf(GeneratorDefaults.DEFAULT_INITIAL_MAX_INPUT_CHARS)
        ), "llm.initial.max.input.chars");

        cfg.initialMaxOutputTokens = parsePositiveInt(firstNonBlank(
                System.getenv("LLM_INITIAL_MAX_OUTPUT_TOKENS"),
                System.getProperty("llm.initial.max.tokens"),
                props.getProperty("llm.initial.max.tokens"),
                props.getProperty("llm.max.tokens"),
                String.valueOf(GeneratorDefaults.DEFAULT_INITIAL_MAX_OUTPUT_TOKENS)
        ), "llm.initial.max.tokens");

        cfg.repair1InputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_REPAIR1_INPUT_RATIO"),
                System.getProperty("llm.repair1.input.ratio"),
                props.getProperty("llm.repair1.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_REPAIR1_INPUT_RATIO)
        ), "llm.repair1.input.ratio");
        cfg.repair1OutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_REPAIR1_OUTPUT_RATIO"),
                System.getProperty("llm.repair1.output.ratio"),
                props.getProperty("llm.repair1.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_REPAIR1_OUTPUT_RATIO)
        ), "llm.repair1.output.ratio");

        cfg.repair2InputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_REPAIR2_INPUT_RATIO"),
                System.getProperty("llm.repair2.input.ratio"),
                props.getProperty("llm.repair2.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_REPAIR2_INPUT_RATIO)
        ), "llm.repair2.input.ratio");
        cfg.repair2OutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_REPAIR2_OUTPUT_RATIO"),
                System.getProperty("llm.repair2.output.ratio"),
                props.getProperty("llm.repair2.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_REPAIR2_OUTPUT_RATIO)
        ), "llm.repair2.output.ratio");

        cfg.hardInputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_HARD_INPUT_RATIO"),
                System.getProperty("llm.hard.input.ratio"),
                props.getProperty("llm.hard.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_HARD_INPUT_RATIO)
        ), "llm.hard.input.ratio");
        cfg.hardOutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_HARD_OUTPUT_RATIO"),
                System.getProperty("llm.hard.output.ratio"),
                props.getProperty("llm.hard.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_HARD_OUTPUT_RATIO)
        ), "llm.hard.output.ratio");

        // Optional absolute overrides. Leave blank or 0 to keep ratio-derived ceilings.
        cfg.hardMaxInputTokensOverride = parseNonNegativeInt(firstNonBlank(
                System.getenv("LLM_HARD_MAX_INPUT_TOKENS"),
                System.getProperty("llm.hard.max.input.tokens"),
                props.getProperty("llm.hard.max.input.tokens"),
                "0"
        ), "llm.hard.max.input.tokens");
        cfg.hardMaxInputCharsOverride = parseNonNegativeInt(firstNonBlank(
                System.getenv("LLM_HARD_MAX_INPUT_CHARS"),
                System.getProperty("llm.hard.max.input.chars"),
                props.getProperty("llm.hard.max.input.chars"),
                "0"
        ), "llm.hard.max.input.chars");
        cfg.hardMaxOutputTokensOverride = parseNonNegativeInt(firstNonBlank(
                System.getenv("LLM_HARD_MAX_OUTPUT_TOKENS"),
                System.getProperty("llm.hard.max.tokens"),
                props.getProperty("llm.hard.max.tokens"),
                "0"
        ), "llm.hard.max.tokens");

        cfg.constructorInputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_CONSTRUCTOR_INPUT_RATIO"),
                System.getProperty("llm.budget.constructor.input.ratio"),
                props.getProperty("llm.budget.constructor.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_CONSTRUCTOR_INPUT_RATIO)
        ), "llm.budget.constructor.input.ratio");
        cfg.constructorOutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_CONSTRUCTOR_OUTPUT_RATIO"),
                System.getProperty("llm.budget.constructor.output.ratio"),
                props.getProperty("llm.budget.constructor.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_CONSTRUCTOR_OUTPUT_RATIO)
        ), "llm.budget.constructor.output.ratio");

        cfg.methodNotFoundInputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_METHOD_INPUT_RATIO"),
                System.getProperty("llm.budget.method.input.ratio"),
                props.getProperty("llm.budget.method.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_METHOD_NOT_FOUND_INPUT_RATIO)
        ), "llm.budget.method.input.ratio");
        cfg.methodNotFoundOutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_METHOD_OUTPUT_RATIO"),
                System.getProperty("llm.budget.method.output.ratio"),
                props.getProperty("llm.budget.method.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_METHOD_NOT_FOUND_OUTPUT_RATIO)
        ), "llm.budget.method.output.ratio");

        cfg.stubAbstractInputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_STUB_INPUT_RATIO"),
                System.getProperty("llm.budget.stub.input.ratio"),
                props.getProperty("llm.budget.stub.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_STUB_ABSTRACT_INPUT_RATIO)
        ), "llm.budget.stub.input.ratio");
        cfg.stubAbstractOutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_STUB_OUTPUT_RATIO"),
                System.getProperty("llm.budget.stub.output.ratio"),
                props.getProperty("llm.budget.stub.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_STUB_ABSTRACT_OUTPUT_RATIO)
        ), "llm.budget.stub.output.ratio");

        cfg.longJavacInputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_LONG_JAVAC_INPUT_RATIO"),
                System.getProperty("llm.budget.long.javac.input.ratio"),
                props.getProperty("llm.budget.long.javac.input.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_LONG_JAVAC_INPUT_RATIO)
        ), "llm.budget.long.javac.input.ratio");
        cfg.longJavacOutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_LONG_JAVAC_OUTPUT_RATIO"),
                System.getProperty("llm.budget.long.javac.output.ratio"),
                props.getProperty("llm.budget.long.javac.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_LONG_JAVAC_OUTPUT_RATIO)
        ), "llm.budget.long.javac.output.ratio");

        cfg.reasoningOnlyOutputRatio = parsePositiveDouble(firstNonBlank(
                System.getenv("LLM_BUDGET_REASONING_ONLY_OUTPUT_RATIO"),
                System.getProperty("llm.budget.reasoning.only.output.ratio"),
                props.getProperty("llm.budget.reasoning.only.output.ratio"),
                String.valueOf(GeneratorDefaults.DEFAULT_REASONING_ONLY_OUTPUT_RATIO)
        ), "llm.budget.reasoning.only.output.ratio");

        return cfg;
    }

    private static Properties loadProperties(String resourceName) {
        Properties props = new Properties();
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file: " + resourceName, e);
        }
        return props;
    }

    private static int parsePositiveInt(String value, String configName) {
        try {
            int n = Integer.parseInt(value);
            if (n <= 0) {
                throw new IllegalArgumentException("Config must be positive: " + configName + " = " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer config: " + configName + " = " + value, e);
        }
    }

    private static int parseNonNegativeInt(String value, String configName) {
        try {
            int n = Integer.parseInt(value);
            if (n < 0) {
                throw new IllegalArgumentException("Config must be non-negative: " + configName + " = " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer config: " + configName + " = " + value, e);
        }
    }

    private static double parsePositiveDouble(String value, String configName) {
        try {
            double n = Double.parseDouble(value);
            if (n <= 0.0d) {
                throw new IllegalArgumentException("Config must be positive: " + configName + " = " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid double config: " + configName + " = " + value, e);
        }
    }

    private static boolean parseBoolean(String value, String configName) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "n".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean config: " + configName + " = " + value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
