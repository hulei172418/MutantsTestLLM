package mujava.testgenerator.tools;

import org.json.JSONObject;

import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Builds the first-generation prompt from compact PromptEvidence.
 */
public final class InitialPromptBuilder {
    private InitialPromptBuilder() {
    }

    public static String build(Request request, PromptEvidence e, String testSetName) {
        String packageName = packageNameOf(testSetName);
        String simpleClassName = simpleNameOf(testSetName);

        StringBuilder sb = new StringBuilder(16384);
        sb.append("You are generating one Java JUnit4 test class to kill one Java mutant.\n");
        sb.append("Return only one complete Java source file. Do not include markdown fences or explanations.\n\n");

        sb.append("Hard requirements:\n");
        sb.append("1. Use JUnit4 only. Do not use Mockito, AssertJ, Truth, PowerMock, or extra libraries.\n");
        sb.append("2. Generate exactly one public class named ").append(simpleClassName).append(".\n");
        if (!packageName.isEmpty()) {
            sb.append("3. The test must start with: package ").append(packageName).append(";\n");
        } else {
            sb.append("3. Use the default package.\n");
        }
        sb.append("4. Keep the test deterministic, short, and self-contained.\n");
        sb.append("5. Do not call private members directly unless the evidence explicitly provides a reflection plan.\n");
        sb.append("6. Do not assign the result of a void method.\n\n");

        sb.append("Evidence interpretation:\n");
        sb.append("- Treat EXECUTABLE_TEST_PLAN and INVOCATION as the concrete construction/call plan. Use them before raw graph text when they are present and syntactically complete.\n");
        sb.append("- Treat PUBLIC_API_AND_COMPILATION_GUARDRAILS as javac-safety constraints. They filter unsafe choices; they do not replace the mutation/observable evidence.\n");
        sb.append("- Treat OBSERVABLE_PLAN and ASSERTIONS as the preferred observation/assertion plan. Do not invent getters or helper APIs when the plan provides a public call or reflection.\n");
        sb.append("- Treat MUTATION_GRAPH_EVIDENCE_A_CPG as A-side RIP/CPG evidence for why an assertion can kill the mutant.\n");
        sb.append("- Treat ENTRY_GRAPH_EVIDENCE_B_CPG, when present, only as B-side reachability evidence showing how callable entry B reaches mutation method A.\n\n");

        sb.append("Generation priority:\n");
        sb.append("1. Preserve package/class/JUnit4 requirements.\n");
        sb.append("2. If EXECUTABLE_TEST_PLAN.status is READY, use its supportClasses, requiredSetup, entryCall, branchReachability, and observable plan as the primary template.\n");
        sb.append("3. Otherwise use INVOCATION_WITH_RECEIVER_AND_STUB_RULES: imports, receiver strategy, setup, and call. If a testStubClassTemplate is present, place it as a nested static class inside the test class.\n");
        sb.append("4. Use OBSERVABLE_PLAN and ASSERTIONS.requiredToKill for the mutation-sensitive assertion.\n");
        sb.append("5. Apply PUBLIC_API_AND_COMPILATION_GUARDRAILS to avoid invalid APIs, invalid overrides, package-access mistakes, and handwritten complex interface stubs.\n");
        sb.append("6. Use A-side CPG/RIP to choose or justify the assertion, but do not introduce inaccessible internal calls from graph text.\n");
        sb.append("7. If a template is marked PLACEHOLDER, uses null values, or uses empty arrays that cannot expose the mutation, improve only the test values/setup using branchReachability or observable evidence.\n\n");

        sb.append("Target metadata:\n");
        sb.append("targetClassName = ").append(request.targetClassName).append('\n');
        sb.append("methodSignature = ").append(request.methodSignature).append('\n');
        sb.append("mutantName = ").append(request.mutantName).append('\n');
        sb.append("testFqn = ").append(testSetName).append("\n\n");

        appendCompactSection(sb, "ENTRY", e.entry);
        appendCompactSection(sb, "EXECUTABLE_TEST_PLAN", e.executableTestPlan);
        appendCompactSection(sb, "INVOCATION_WITH_RECEIVER_AND_STUB_RULES", e.invocation);
        appendCompactSection(sb, "PUBLIC_API_AND_COMPILATION_GUARDRAILS", e.publicApiEvidence);
        appendCompactSection(sb, "OBSERVABLE_PLAN", e.observablePlan);
        appendCompactSection(sb, "ASSERTIONS", e.assertions);
        appendCompactSection(sb, "MUTATION", e.mutation);
        appendCompactSection(sb, "MUTATION_GRAPH_EVIDENCE_A_CPG", e.mutationGraphEvidence);
        appendCompactSection(sb, "MUTATION_EVIDENCE", e.mutationEvidence);

        if (e.needEntryLiftedEvidence && e.entryEvidence.length() > 0) {
            appendCompactSection(sb, "ENTRY_EVIDENCE", e.entryEvidence);
        }
        if (e.needEntryLiftedEvidence && e.entryGraphEvidence.length() > 0) {
            appendCompactSection(sb, "ENTRY_GRAPH_EVIDENCE_B_CPG", e.entryGraphEvidence);
        }

        sb.append("Final output rule:\n");
        sb.append("Return only the Java source code for the requested test class.\n");

        return sb.toString();
    }

    static void appendCompactSection(StringBuilder sb, String title, JSONObject obj) {
        if (obj == null || obj.length() == 0) {
            return;
        }
        sb.append(title).append(":\n");
        sb.append(obj.toString(2)).append("\n\n");
    }
}
