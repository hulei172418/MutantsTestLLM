package mujava.testgenerator.tools;

/**
 * One concrete input/output budget selected for a prompt phase.
 */
public final class PromptBudgetProfile {
    private final String name;
    private final int maxInputTokens;
    private final int maxInputChars;
    private final int maxOutputTokens;
    private final int hardMaxInputTokens;
    private final int hardMaxInputChars;
    private final int hardMaxOutputTokens;
    private final String oversizePolicy;
    private final boolean evidenceAware;
    private final double inputScale;
    private final double outputScale;

    public PromptBudgetProfile(String name,
                               int maxInputTokens,
                               int maxInputChars,
                               int maxOutputTokens,
                               int hardMaxInputTokens,
                               int hardMaxInputChars,
                               int hardMaxOutputTokens,
                               String oversizePolicy,
                               boolean evidenceAware,
                               double inputScale,
                               double outputScale) {
        this.name = name == null ? "" : name;
        this.maxInputTokens = maxInputTokens;
        this.maxInputChars = maxInputChars;
        this.maxOutputTokens = maxOutputTokens;
        this.hardMaxInputTokens = hardMaxInputTokens;
        this.hardMaxInputChars = hardMaxInputChars;
        this.hardMaxOutputTokens = hardMaxOutputTokens;
        this.oversizePolicy = oversizePolicy == null || oversizePolicy.trim().isEmpty()
                ? "truncate"
                : oversizePolicy.trim().toLowerCase();
        this.evidenceAware = evidenceAware;
        this.inputScale = inputScale;
        this.outputScale = outputScale;
    }

    public String getName() { return name; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public int getMaxInputChars() { return maxInputChars; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public int getHardMaxInputTokens() { return hardMaxInputTokens; }
    public int getHardMaxInputChars() { return hardMaxInputChars; }
    public int getHardMaxOutputTokens() { return hardMaxOutputTokens; }
    public String getOversizePolicy() { return oversizePolicy; }
    public boolean isEvidenceAware() { return evidenceAware; }
    public double getInputScale() { return inputScale; }
    public double getOutputScale() { return outputScale; }

    public int effectiveMaxInputChars() {
        int byInput = maxInputChars > 0 ? maxInputChars : maxInputTokens * 3;
        int byHard = hardMaxInputChars > 0 ? hardMaxInputChars : hardMaxInputTokens * 3;
        if (byHard > 0) {
            return Math.min(byInput, byHard);
        }
        return byInput;
    }

    @Override
    public String toString() {
        return "name=" + name
                + ", inputScale=" + String.format("%.2f", inputScale)
                + ", outputScale=" +  String.format("%.2f", outputScale)
                + ", maxInputTokens=" + maxInputTokens
                + ", maxInputChars=" + maxInputChars
                + ", maxOutputTokens=" + maxOutputTokens
                + ", hardMaxInputTokens=" + hardMaxInputTokens
                + ", hardMaxInputChars=" + hardMaxInputChars
                + ", hardMaxOutputTokens=" + hardMaxOutputTokens
                + ", oversizePolicy=" + oversizePolicy
                + ", evidenceAware=" + evidenceAware;
    }
}
