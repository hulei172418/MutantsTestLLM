package mujava.testgenerator.tools;

/**
 * Centralized defaults used by the LLM-based test generation pipeline.
 */
public final class GeneratorDefaults {
    private GeneratorDefaults() {
    }

    public static final int DEFAULT_TIMEOUT_MILLIS = 1_000;
    public static final int DEFAULT_MAX_REPAIR_ROUNDS = 2;
    public static final int DEFAULT_MAX_ITEMS_IN_PROMPT = 5;
    public static final int DEFAULT_MAX_CODE_CHARS = 4_000;
    public static final int DEFAULT_MAX_EVIDENCE_STRING_CHARS = 1024 * 8;

    /** Number of mutant/test-generation tasks submitted concurrently. */
    public static final int DEFAULT_LLM_THREADS = 4;

    /** Maximum HTTP attempts for one uncached LLM prompt. */
    public static final int DEFAULT_MAX_LLM_API_ATTEMPTS = 2;

    /** Skip the whole task when the generated .java and compiled .class already exist. */
    public static final boolean DEFAULT_SKIP_EXISTING_COMPILED_TESTS = true;

    /** Force a fresh LLM call instead of reusing existing compiled tests, response files, or API cache. */
    public static final boolean DEFAULT_FORCE_REGENERATE = false;

    /** Enable stage-aware and error-aware input/output budgets. */
    public static final boolean DEFAULT_DYNAMIC_BUDGET_ENABLED = true;

    /** Oversize prompt policy: truncate or fail. */
    public static final String DEFAULT_OVERSIZE_PROMPT_POLICY = "truncate";

    /** Base budget for initial generation. Repair budgets are derived from this by ratios. */
    public static final int DEFAULT_INITIAL_MAX_INPUT_TOKENS = 10_000;
    public static final int DEFAULT_INITIAL_MAX_INPUT_CHARS = 30_000;
    public static final int DEFAULT_INITIAL_MAX_OUTPUT_TOKENS = 3_500;

    /** Convert token budget to character budget when a character budget is not explicitly provided. */
    public static final double DEFAULT_BUDGET_CHARS_PER_TOKEN = 3.0;

    /** Stage scaling ratios. These replace hard-coded repair budgets. */
    public static final double DEFAULT_REPAIR1_INPUT_RATIO = 1.80;
    public static final double DEFAULT_REPAIR1_OUTPUT_RATIO = 1.45;
    public static final double DEFAULT_REPAIR2_INPUT_RATIO = 2.60;
    public static final double DEFAULT_REPAIR2_OUTPUT_RATIO = 2.30;

    /** Hard ceilings, also derived by ratio from the initial budget. */
    public static final double DEFAULT_HARD_INPUT_RATIO = 3.00;
    public static final double DEFAULT_HARD_OUTPUT_RATIO = 3.00;

    /** Error-aware scaling ratios applied on top of the stage ratio. */
    public static final double DEFAULT_CONSTRUCTOR_INPUT_RATIO = 1.20;
    public static final double DEFAULT_CONSTRUCTOR_OUTPUT_RATIO = 1.20;
    public static final double DEFAULT_METHOD_NOT_FOUND_INPUT_RATIO = 1.10;
    public static final double DEFAULT_METHOD_NOT_FOUND_OUTPUT_RATIO = 1.10;
    public static final double DEFAULT_STUB_ABSTRACT_INPUT_RATIO = 1.20;
    public static final double DEFAULT_STUB_ABSTRACT_OUTPUT_RATIO = 1.30;
    public static final double DEFAULT_LONG_JAVAC_INPUT_RATIO = 1.25;
    public static final double DEFAULT_LONG_JAVAC_OUTPUT_RATIO = 1.25;

    /** Output-only expansion when the previous response spent its whole budget on reasoning. */
    public static final double DEFAULT_REASONING_ONLY_OUTPUT_RATIO = 2.00;
}
