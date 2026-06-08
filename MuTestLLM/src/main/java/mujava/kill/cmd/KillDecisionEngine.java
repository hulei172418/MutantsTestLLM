package mujava.kill.cmd;

import java.util.Locale;

/**
 * Centralized kill decision rules.
 *
 * <p>Design goal: keep the existing execution model unchanged, but make the
 * semantic comparison rules explicit and reusable.</p>
 */
public final class KillDecisionEngine {

    private KillDecisionEngine() {}

    public enum OutcomeType {
        PASS,
        EXCEPTION,
        TIMEOUT,
        INFRA_ERROR,
        UNKNOWN
    }

    public enum KillReason {
        SAME_RESULT,
        OUTPUT_DIFFERENT,
        EXCEPTION_MISMATCH,
        TIMEOUT_ASYMMETRY,
        BOTH_TIMEOUT,
        BOTH_INFRA_SAME,
        INFRA_UNCERTAIN,
        UNKNOWN
    }

    public static final class ExecutionOutcome {
        private final OutcomeType type;
        private final String rawText;
        private final String normalizedText;

        public ExecutionOutcome(OutcomeType type, String rawText, String normalizedText) {
            this.type = type == null ? OutcomeType.UNKNOWN : type;
            this.rawText = rawText == null ? "" : rawText;
            this.normalizedText = normalizedText == null ? "" : normalizedText;
        }

        public OutcomeType getType() {
            return type;
        }

        public String getRawText() {
            return rawText;
        }

        public String getNormalizedText() {
            return normalizedText;
        }
    }

    public static final class KillDecision {
        private final boolean killed;
        private final KillReason reason;

        public KillDecision(boolean killed, KillReason reason) {
            this.killed = killed;
            this.reason = reason == null ? KillReason.UNKNOWN : reason;
        }

        public boolean isKilled() {
            return killed;
        }

        public KillReason getReason() {
            return reason;
        }
    }

    public static KillDecision decide(String original, String mutant) {
        return decide(parse(original), parse(mutant));
    }

    public static boolean isKilled(String original, String mutant) {
        return decide(original, mutant).isKilled();
    }

    public static boolean sameSemanticResult(String a, String b) {
        ExecutionOutcome oa = parse(a);
        ExecutionOutcome ob = parse(b);

        if (oa.getType() != ob.getType()) {
            return false;
        }

        if (oa.getType() == OutcomeType.TIMEOUT) {
            return true;
        }

        if (oa.getType() == OutcomeType.INFRA_ERROR || oa.getType() == OutcomeType.EXCEPTION) {
            return oa.getNormalizedText().equals(ob.getNormalizedText());
        }

        return oa.getNormalizedText().equals(ob.getNormalizedText());
    }

    public static ExecutionOutcome parse(String text) {
        String raw = text == null ? "" : text.trim();
        String normalized = normalizeFailureText(raw);

        if (raw.isEmpty() || "pass".equalsIgnoreCase(raw)) {
            return new ExecutionOutcome(OutcomeType.PASS, raw, normalized);
        }
        if (isTimeoutText(raw)) {
            return new ExecutionOutcome(OutcomeType.TIMEOUT, raw, normalized);
        }
        if (isInfrastructureFailureText(raw)) {
            return new ExecutionOutcome(OutcomeType.INFRA_ERROR, raw, normalized);
        }
        return new ExecutionOutcome(OutcomeType.EXCEPTION, raw, normalized);
    }

    public static KillDecision decide(ExecutionOutcome original, ExecutionOutcome mutant) {
        if (sameSemanticResult(original.getRawText(), mutant.getRawText())) {
            return new KillDecision(false, KillReason.SAME_RESULT);
        }

        OutcomeType o = original.getType();
        OutcomeType m = mutant.getType();

        if (o == OutcomeType.TIMEOUT && m == OutcomeType.TIMEOUT) {
            return new KillDecision(false, KillReason.BOTH_TIMEOUT);
        }
        if (o == OutcomeType.TIMEOUT || m == OutcomeType.TIMEOUT) {
            return new KillDecision(true, KillReason.TIMEOUT_ASYMMETRY);
        }

        if (o == OutcomeType.INFRA_ERROR || m == OutcomeType.INFRA_ERROR) {
            if (o == OutcomeType.INFRA_ERROR && m == OutcomeType.INFRA_ERROR
                    && original.getNormalizedText().equals(mutant.getNormalizedText())) {
                return new KillDecision(false, KillReason.BOTH_INFRA_SAME);
            }
            return new KillDecision(false, KillReason.INFRA_UNCERTAIN);
        }

        if (o == OutcomeType.EXCEPTION || m == OutcomeType.EXCEPTION) {
            return new KillDecision(true, KillReason.EXCEPTION_MISMATCH);
        }

        return new KillDecision(true, KillReason.OUTPUT_DIFFERENT);
    }

    public static boolean isTimeoutText(String text) {
        String normalized = safeLower(text);
        return normalized.contains("time_out")
                || normalized.contains("timed out")
                || normalized.contains("timeout")
                || normalized.contains("more than ") && normalized.contains(" milliseconds")
                || normalized.startsWith("timeout:")
                || normalized.startsWith("timeout ");
    }

    public static boolean isInfrastructureFailureText(String text) {
        String normalized = safeLower(text);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.contains("initializationerror")
                || normalized.contains("classnotfoundexception")
                || normalized.contains("noclassdeffounderror")
                || normalized.contains("cannot create launcher without at least one testengine")
                || normalized.contains("preconditionviolationexception")
                || normalized.contains("worker protocol error")
                || normalized.contains("worker exited with code")
                || normalized.contains("worker timed out")
                || normalized.contains("could not initialize class")
                || normalized.contains("linkageerror")
                || normalized.contains("loader constraint violation")
                || normalized.contains("classformaterror")
                || normalized.contains("unsupportedclassversionerror")
                || normalized.contains("incompatibleclasschangeerror")
                || normalized.contains("bootstrapmethoderror")
                || normalized.contains("no such method error")
                || normalized.contains("nosuchmethoderror")
                || normalized.contains("no such field error")
                || normalized.contains("nosuchfielderror")
                || normalized.contains("verifyerror")
                || normalized.contains("serviceconfigurationerror");
    }

    public static String normalizeFailureText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        normalized = normalized.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        normalized = normalized.replaceAll("\\bline[:= ]+\\d+\\b", "line=N")
                .replaceAll("\\b:[ ]*\\d+\\b", ":N")
                .replaceAll("@[0-9a-fA-F]+", "@HEX")
                .replaceAll("more than \\d+ milliseconds", "more than N milliseconds")
                .replaceAll("timeout[: ]+\\d+", "timeout N")
                .replaceAll("\\$Lambda\\$\\d+/0x[0-9a-fA-F]+", "Lambda/0xHEX");

        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String safeLower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
