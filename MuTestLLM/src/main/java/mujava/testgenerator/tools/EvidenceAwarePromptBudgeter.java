package mujava.testgenerator.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Enforces prompt input budgets without blindly cutting head/tail.
 *
 * The prompt produced by InitialPromptBuilder/RepairPromptBuilder is organized into named sections.
 * This budgeter preserves executable/compilation evidence first and compresses graph/path evidence
 * only when necessary.
 */
public final class EvidenceAwarePromptBudgeter {
    private static final List<String> KNOWN_SECTION_TITLES = Arrays.asList(
            "ENTRY",
            "EXECUTABLE_TEST_PLAN",
            "INVOCATION_WITH_RECEIVER_AND_STUB_RULES",
            "INVOCATION",
            "PUBLIC_API_AND_COMPILATION_GUARDRAILS",
            "PUBLIC_API",
            "OBSERVABLE_PLAN",
            "ASSERTIONS",
            "MUTATION",
            "MUTATION_EVIDENCE",
            "MUTATION_GRAPH_EVIDENCE_A_CPG",
            "ENTRY_EVIDENCE",
            "ENTRY_GRAPH_EVIDENCE_B_CPG",
            "Previous test code"
    );

    private EvidenceAwarePromptBudgeter() {
    }

    public static String enforce(String prompt,
                                 PromptBudgetProfile profile,
                                 String taskId,
                                 String phase) {
        if (prompt == null) {
            return "";
        }
        if (profile == null) {
            return prompt;
        }

        int maxChars = profile.effectiveMaxInputChars();
        if (maxChars <= 0 || prompt.length() <= maxChars) {
            logBudget(taskId, phase, profile, prompt.length(), prompt.length(), false);
            return prompt;
        }

        String policy = profile.getOversizePolicy() == null
                ? "truncate"
                : profile.getOversizePolicy().trim().toLowerCase(Locale.ROOT);
        if ("fail".equals(policy)) {
            throw new IllegalArgumentException(
                    "Prompt too large before LLM call: taskId=" + taskId
                            + ", phase=" + phase
                            + ", chars=" + prompt.length()
                            + ", estimatedTokens=" + estimateTokens(prompt)
                            + ", profile={" + profile + "}"
            );
        }

        String reduced = phase != null && phase.toLowerCase(Locale.ROOT).startsWith("repair")
                ? reduceRepairPrompt(prompt, maxChars)
                : reduceInitialPrompt(prompt, maxChars);

        if (reduced.length() > maxChars) {
            reduced = forceFitByPriority(reduced, maxChars);
        }

        logBudget(taskId, phase, profile, prompt.length(), reduced.length(), true);
        return reduced;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 3.0));
    }

    private static String reduceInitialPrompt(String prompt, int maxChars) {
        String prefix = prefixBeforeFirstKnownSection(prompt);
        List<Section> sections = parseSections(prompt);
        StringBuilder out = new StringBuilder(Math.min(prompt.length(), maxChars + 1024));

        appendCapped(out, prefix, Math.max(0, (int) (maxChars * 0.30)), "INITIAL_HEADER_REQUIREMENTS");

        // Concrete executable/compilation plan first: these are necessary for javac success.
        appendSectionByTitle(out, sections, "ENTRY", maxChars, 1.00);
        appendSectionByTitle(out, sections, "EXECUTABLE_TEST_PLAN", maxChars, 1.00);
        appendFirstAvailableSection(out, sections, maxChars, 1.00,
                "INVOCATION_WITH_RECEIVER_AND_STUB_RULES", "INVOCATION");
        appendFirstAvailableSection(out, sections, maxChars, 1.00,
                "PUBLIC_API_AND_COMPILATION_GUARDRAILS", "PUBLIC_API");
        appendSectionByTitle(out, sections, "OBSERVABLE_PLAN", maxChars, 1.00);
        appendSectionByTitle(out, sections, "ASSERTIONS", maxChars, 1.00);

        // Mutation semantics next. A-side graph is important, but compressible after concrete templates.
        appendSectionIfRoom(out, sectionByTitle(sections, "MUTATION"), maxChars, 0.95);
        appendSectionIfRoom(out, sectionByTitle(sections, "MUTATION_GRAPH_EVIDENCE_A_CPG"), maxChars, 0.50);
        appendSectionIfRoom(out, sectionByTitle(sections, "MUTATION_EVIDENCE"), maxChars, 0.45);
        appendSectionIfRoom(out, sectionByTitle(sections, "ENTRY_EVIDENCE"), maxChars, 0.30);
        appendSectionIfRoom(out, sectionByTitle(sections, "ENTRY_GRAPH_EVIDENCE_B_CPG"), maxChars, 0.28);

        String guidance = trailingGuidance(prompt);
        if (!guidance.isEmpty()) {
            appendCapped(out, "\n" + guidance, remaining(out, maxChars), "TRAILING_GUIDANCE");
        }
        return out.toString();
    }

    private static String reduceRepairPrompt(String prompt, int maxChars) {
        String prefix = prefixBeforeFirstKnownSection(prompt);
        List<Section> sections = parseSections(prompt);
        StringBuilder out = new StringBuilder(Math.min(prompt.length(), maxChars + 1024));

        /*
         * Repair prompt priority:
         * 1) prefix includes javac error category, javac output, and repair focus.
         * 2) executable/invocation/public API/observable/assertions are essential for compilation repair.
         * 3) previous code is needed to patch the concrete failure.
         * 4) mutation/graph evidence is useful but should not displace javac repair context.
         */
        appendCapped(out, prefix, Math.max(0, (int) (maxChars * 0.38)), "REPAIR_HEADER_JAVAC_DIAGNOSIS");

        appendSectionByTitle(out, sections, "ENTRY", maxChars, 1.00);
        appendSectionByTitle(out, sections, "EXECUTABLE_TEST_PLAN", maxChars, 1.00);
        appendFirstAvailableSection(out, sections, maxChars, 1.00,
                "INVOCATION_WITH_RECEIVER_AND_STUB_RULES", "INVOCATION");
        appendFirstAvailableSection(out, sections, maxChars, 1.00,
                "PUBLIC_API_AND_COMPILATION_GUARDRAILS", "PUBLIC_API");
        appendSectionByTitle(out, sections, "OBSERVABLE_PLAN", maxChars, 1.00);
        appendSectionByTitle(out, sections, "ASSERTIONS", maxChars, 1.00);

        appendSectionByTitle(out, sections, "Previous test code", maxChars, 1.00);

        appendSectionIfRoom(out, sectionByTitle(sections, "MUTATION"), maxChars, 0.60);
        appendSectionIfRoom(out, sectionByTitle(sections, "MUTATION_EVIDENCE"), maxChars, 0.35);
        appendSectionIfRoom(out, sectionByTitle(sections, "MUTATION_GRAPH_EVIDENCE_A_CPG"), maxChars, 0.25);
        appendSectionIfRoom(out, sectionByTitle(sections, "ENTRY_EVIDENCE"), maxChars, 0.20);
        appendSectionIfRoom(out, sectionByTitle(sections, "ENTRY_GRAPH_EVIDENCE_B_CPG"), maxChars, 0.18);

        if (!out.toString().contains("Return only corrected Java source code.")) {
            append(out, "\nReturn only corrected Java source code.\n");
        }
        return out.toString();
    }

    private static void appendFirstAvailableSection(StringBuilder out,
                                                    List<Section> sections,
                                                    int maxChars,
                                                    double keepRatio,
                                                    String... titles) {
        if (titles == null) {
            return;
        }
        for (String title : titles) {
            Section s = sectionByTitle(sections, title);
            if (s != null) {
                appendSectionIfRoom(out, s, maxChars, keepRatio);
                return;
            }
        }
    }

    private static void appendSectionByTitle(StringBuilder out,
                                             List<Section> sections,
                                             String title,
                                             int maxChars,
                                             double keepRatio) {
        Section s = sectionByTitle(sections, title);
        if (s == null) {
            return;
        }
        appendSectionIfRoom(out, s, maxChars, keepRatio);
    }

    private static void appendSectionIfRoom(StringBuilder out, Section section, int maxChars, double keepRatio) {
        if (section == null) {
            return;
        }
        int rem = remaining(out, maxChars);
        if (rem <= 0) {
            return;
        }
        String text = section.raw;
        if (text.length() <= rem) {
            append(out, text);
            return;
        }
        int cap = Math.max(512, (int) (rem * keepRatio));
        cap = Math.min(cap, rem);
        appendCapped(out, text, cap, section.title);
    }

    private static void appendCapped(StringBuilder out, String text, int cap, String label) {
        if (text == null || text.isEmpty() || cap <= 0) {
            return;
        }
        if (text.length() <= cap) {
            append(out, text);
            return;
        }

        String marker = "\n/* <SECTION_TRUNCATED section=\"" + label + "\">"
                + " evidence compressed by local prompt budget; high-priority executable and repair information was preserved "
                + "</SECTION_TRUNCATED> */\n";

        if (cap <= marker.length() + 64) {
            append(out, text.substring(0, Math.max(0, cap)));
            return;
        }

        int rest = cap - marker.length();
        int head = (int) (rest * 0.70);
        int tail = rest - head;

        String x = text.substring(0, Math.min(head, text.length()))
                + marker
                + text.substring(Math.max(0, text.length() - tail));
        append(out, x);
    }

    private static String forceFitByPriority(String prompt, int maxChars) {
        if (prompt == null || prompt.length() <= maxChars) {
            return prompt == null ? "" : prompt;
        }
        String marker = "\n/* <PROMPT_FORCE_TRUNCATED> final safety cap </PROMPT_FORCE_TRUNCATED> */\n";
        int rest = Math.max(0, maxChars - marker.length());
        int head = (int) (rest * 0.75);
        int tail = rest - head;
        return prompt.substring(0, Math.min(head, prompt.length()))
                + marker
                + prompt.substring(Math.max(0, prompt.length() - tail));
    }

    private static String prefixBeforeFirstKnownSection(String prompt) {
        int first = firstKnownSectionIndex(prompt);
        if (first < 0) {
            return prompt;
        }
        return prompt.substring(0, first);
    }

    private static String trailingGuidance(String prompt) {
        int idx = prompt.indexOf("Generation priority:");
        if (idx >= 0) {
            return prompt.substring(idx);
        }
        idx = prompt.indexOf("Generation guidance:");
        if (idx >= 0) {
            return prompt.substring(idx);
        }
        idx = prompt.indexOf("Final output rule:");
        if (idx >= 0) {
            return prompt.substring(idx);
        }
        return "";
    }

    private static int firstKnownSectionIndex(String prompt) {
        int min = -1;
        for (String t : KNOWN_SECTION_TITLES) {
            int idx = prompt.indexOf(t + ":\n");
            if (idx >= 0 && (min < 0 || idx < min)) {
                min = idx;
            }
        }
        return min;
    }

    private static List<Section> parseSections(String prompt) {
        List<SectionStart> starts = new ArrayList<SectionStart>();
        for (String t : KNOWN_SECTION_TITLES) {
            int pos = 0;
            String needle = t + ":\n";
            while (pos >= 0 && pos < prompt.length()) {
                int idx = prompt.indexOf(needle, pos);
                if (idx < 0) {
                    break;
                }
                starts.add(new SectionStart(t, idx));
                pos = idx + needle.length();
            }
        }
        starts.sort((a, b) -> Integer.compare(a.index, b.index));

        List<Section> out = new ArrayList<Section>();
        for (int i = 0; i < starts.size(); i++) {
            SectionStart st = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1).index : prompt.length();
            if (end > st.index) {
                out.add(new Section(st.title, prompt.substring(st.index, end)));
            }
        }
        return out;
    }

    private static Section sectionByTitle(List<Section> sections, String title) {
        if (sections == null) {
            return null;
        }
        for (Section s : sections) {
            if (s.title.equals(title)) {
                return s;
            }
        }
        return null;
    }

    private static int remaining(StringBuilder sb, int maxChars) {
        return Math.max(0, maxChars - sb.length());
    }

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        sb.append(text);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void logBudget(String taskId,
                                  String phase,
                                  PromptBudgetProfile profile,
                                  int charsBefore,
                                  int charsAfter,
                                  boolean truncated) {
        System.err.println("[LLM-PROMPT-BUDGET] taskId=" + taskId
                + " phase=" + phase
                + " profile={" + profile + "}"
                + " truncated=" + truncated
                + " charsBefore=" + charsBefore
                + " charsAfter=" + charsAfter
                + " estimatedTokensBefore=" + Math.max(1, (int) Math.ceil(charsBefore / 3.0))
                + " estimatedTokensAfter=" + Math.max(1, (int) Math.ceil(charsAfter / 3.0)));
    }

    private static final class SectionStart {
        final String title;
        final int index;

        SectionStart(String title, int index) {
            this.title = title;
            this.index = index;
        }
    }

    private static final class Section {
        final String title;
        final String raw;

        Section(String title, String raw) {
            this.title = title;
            this.raw = raw;
        }
    }
}
