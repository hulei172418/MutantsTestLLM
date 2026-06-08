package mujava.testgenerator.tools;

import static mujava.testgenerator.tools.InitialPromptBuilder.appendCompactSection;
import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Builds targeted repair prompts using javac diagnostics and compact evidence.
 */
public final class RepairPromptBuilder {
    private RepairPromptBuilder() {
    }

    public static String build(Request request,
                               PromptEvidence e,
                               String testSetName,
                               String lastCode,
                               String compileError) {
        String packageName = packageNameOf(testSetName);
        String simpleClassName = simpleNameOf(testSetName);
        CompileErrorKind kind = CompileErrorClassifier.classify(compileError);

        StringBuilder sb = new StringBuilder(16384);
        sb.append("Repair the Java JUnit4 test so it compiles in the existing project.\n");
        sb.append("Return only one complete Java source file. Do not include markdown fences or explanations.\n\n");

        sb.append("Non-negotiable constraints:\n");
        sb.append("- public class name must be: ").append(simpleClassName).append("\n");
        if (!packageName.isEmpty()) {
            sb.append("- package declaration must be: package ").append(packageName).append(";\n");
        }
        sb.append("- JUnit4 only; no extra third-party test libraries.\n");
        sb.append("- Preserve intent: kill mutant ").append(request.mutantName)
                .append(" for method ").append(request.methodSignature).append(".\n");
        sb.append("- Prefer EXECUTABLE_TEST_PLAN and INVOCATION templates over guessed code.\n");
        sb.append("- Preserve at least one mutation-sensitive assertion when the evidence makes one available.\n\n");

        sb.append("Javac error category: ").append(kind.name()).append("\n");
        sb.append("Javac error:\n").append(compileError == null ? "" : compileError).append("\n\n");

        CompilerDiagnosisAppender.append(sb, e, compileError);

        sb.append("Repair focus:\n");
        switch (kind) {
            case LLM_OUTPUT_TRUNCATED:
                sb.append("- The previous LLM output was truncated. Return a shorter complete Java file.\n");
                sb.append("- Do not include explanations or reasoning. Return only Java source.\n");
                break;

            case EMPTY_LLM_CONTENT:
                sb.append("- The previous response had no visible Java code. Return one complete Java source file only.\n");
                break;

            case EXPECTED_CLASS_NOT_FOUND:
                sb.append("- javac appeared to run but the expected test .class was not generated.\n");
                sb.append("- Ensure package and public class name exactly match the required test FQN.\n");
                sb.append("- Do not define a different public class name.\n");
                break;

            case VOID_VALUE_MISUSE:
                sb.append("- The previous code treated a void method as a value.\n");
                sb.append("- Do not write result = subject.voidMethod(...).\n");
                sb.append("- Call the void method as a statement, then assert public state, exception, side effect, or OBSERVABLE_PLAN output.\n");
                break;

            case ABSTRACT_STUB_INCOMPLETE:
                sb.append("- The test stub/subclass did not implement all required abstract methods.\n");
                sb.append("- If INVOCATION.receiver.testStubClassTemplate is present, use that nested class template directly.\n");
                sb.append("- Otherwise implement every method listed in INVOCATION.receiver.abstractMethodsToImplement or allowedOverrides in one pass.\n");
                sb.append("- Do not only add the single abstract method mentioned by javac.\n");
                break;

            case UNDEFINED_VARIABLE_OR_SETUP_MISMATCH:
                sb.append("- A variable used by the test was not declared or setup/assertion variable names are inconsistent.\n");
                sb.append("- Rebuild setup from EXECUTABLE_TEST_PLAN.requiredSetup and INVOCATION.setup.\n");
                sb.append("- Use OBSERVABLE_PLAN.observableCall and ASSERTIONS only when their variables are declared.\n");
                sb.append("- Do not reference helper variables that are not created in the generated source.\n");
                break;

            case MISSING_CLASS_OR_IMPORT:
                sb.append("- Fix package/imports/class names using INVOCATION.imports and target package.\n");
                sb.append("- Do not invent classes not listed in the evidence, JDK, JUnit4, or project types.\n");
                sb.append("- If the missing class is a support/stub class, copy EXECUTABLE_TEST_PLAN.supportClasses inside the test class.\n");
                break;

            case TEST_STUB_MISMATCH:
                sb.append("- The generated test subclass/stub is invalid.\n");
                sb.append("- Follow INVOCATION.receiver.testStubClassTemplate and testStubConstructorTemplate if present.\n");
                sb.append("- Put nested stub classes inside the generated test class, outside @Test methods.\n");
                sb.append("- Do not override final methods. Implement every method listed in abstractMethodsToImplement.\n");
                sb.append("- Put super(...) first in any constructor.\n");
                break;

            case FACTORY_BUILDER_MISMATCH:
                sb.append("- The failure is caused by invalid factory/builder construction.\n");
                sb.append("- Use EXECUTABLE_TEST_PLAN.requiredSetup or INVOCATION.setup.\n");
                sb.append("- Do not call new TargetClass() when receiver.strategy says STATIC_FACTORY_BUILDER.\n");
                sb.append("- Do not call new Builder() if the builder constructor is private.\n");
                sb.append("- Use receiver.builderTerminalMethod exactly; do not guess build() when evidence says get().\n");
                break;

            case CONSTRUCTOR_MISMATCH:
                sb.append("- Fix constructor arguments using EXECUTABLE_TEST_PLAN.requiredSetup, INVOCATION.setup, and INVOCATION.call.\n");
                sb.append("- If the entry is a constructor, instantiate with new B(...); do not call it like a normal method.\n");
                sb.append("- If receiver.strategy is STATIC_FACTORY_BUILDER, use the factory/builder setup instead of constructor guessing.\n");
                break;

            case ABSTRACT_INSTANTIATION:
                sb.append("- The receiver/test target is abstract or not directly instantiable.\n");
                sb.append("- Do not instantiate abstract classes directly.\n");
                sb.append("- If receiver.strategy is TEST_STUB_SUBCLASS, use the provided testStubClassTemplate.\n");
                sb.append("- If receiver.strategy is CONCRETE_SUBCLASS_INHERITED_METHOD, instantiate runtimeReceiverClass only if setup evidence gives a valid construction/factory.\n");
                sb.append("- If receiver.strategy is ANONYMOUS_SUBCLASS, implement all required abstract methods supplied by evidence.\n");
                break;

            case METHOD_NOT_FOUND:
                sb.append("- Remove calls to non-existing methods.\n");
                sb.append("- Use exact method names from PUBLIC_API.availablePublicMethods / availableSetupMethods.\n");
                sb.append("- Do not invent getters/setters/readers.\n");
                sb.append("- If OBSERVABLE_PLAN.observableCall exists, use it as the observable.\n");
                sb.append("- If EXECUTABLE_TEST_PLAN.entryCall exists, prefer it over guessed method calls.\n");
                break;

            case INCOMPATIBLE_TYPES:
                sb.append("- Fix operand and assertion types.\n");
                sb.append("- For double/float assertions, use a delta. For arrays, use assertArrayEquals.\n");
                sb.append("- Use ASSERTIONS.requiredToKill only when the variables are defined and types are compatible.\n");
                break;

            case PRIVATE_ACCESS:
                sb.append("- The previous code directly accessed a private member. This is illegal Java.\n");
                sb.append("- Prefer public entry method B from ENTRY/INVOCATION/EXECUTABLE_TEST_PLAN.\n");
                sb.append("- Use reflection only if EXECUTABLE_TEST_PLAN.accessConstraints.reflectionAllowed=true or OBSERVABLE_PLAN explicitly provides reflection.\n");
                break;

            case PROTECTED_ACCESS:
                sb.append("- The previous code directly used a protected constructor/member from an illegal context.\n");
                sb.append("- Prefer same-package access, factory/public setup, or test-stub templates from the evidence.\n");
                sb.append("- Use reflection only if the evidence explicitly allows/requires it.\n");
                break;

            case PACKAGE_PRIVATE_ACCESS:
                sb.append("- Keep the generated test in the exact package shown above.\n");
                sb.append("- Do not import package-private target classes from parent/sibling packages.\n");
                sb.append("- If same-package still cannot access the member, use public entry/stub/template instead of direct access.\n");
                break;

            case INVENTED_GETTER_OR_API:
                sb.append("- The previous test invented a project API that does not exist.\n");
                sb.append("- Remove invented getters/setters/helpers such as getWidth(), getHeight(), getIgnore(), unless they appear in PUBLIC_API.availablePublicMethods.\n");
                sb.append("- If OBSERVABLE_PLAN.kind=REFLECTION_FIELD_READ_AFTER_SETTER, use reflection setup and observableCall.\n");
                break;

            case OVERRIDE_FORBIDDEN_OR_SIGNATURE:
                sb.append("- The previous test generated an invalid @Override.\n");
                sb.append("- Override only methods listed in INVOCATION.receiver.allowedOverrides.\n");
                sb.append("- Never override methods listed in INVOCATION.receiver.forbiddenOverrides.\n");
                sb.append("- Remove @Override from helper methods unless the exact signature is listed in allowedOverrides.\n");
                break;

            case OVERRIDE_ACCESS_WEAKENING:
                sb.append("- The previous override reduced method access privileges.\n");
                sb.append("- Copy the exact access modifier from receiver.allowedOverrides or testStubClassTemplate.\n");
                sb.append("- If the superclass method is public, the override must be public.\n");
                break;

            case DATAINPUT_ANONYMOUS_STUB:
                sb.append("- The previous test hand-wrote an anonymous java.io.DataInput implementation.\n");
                sb.append("- Use new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[] {...})) or the concrete setup from evidence.\n");
                break;

            case CONSTRUCTOR_AS_METHOD:
                sb.append("- The previous test called a constructor as if it were an instance method.\n");
                sb.append("- Constructors must be invoked with new ClassName(...), not subject.ClassName(...).\n");
                sb.append("- Use INVOCATION.setup, receiver.testStubConstructorTemplate, or the constructor evidence.\n");
                break;

            default:
                sb.append("- Make the smallest changes necessary to compile while preserving the mutation-sensitive assertion.\n");
                sb.append("- Prefer EXECUTABLE_TEST_PLAN and INVOCATION over guessed APIs.\n");
                break;
        }
        sb.append('\n');

        sb.append("Repair evidence priority:\n");
        sb.append("1. Fix javac errors using EXECUTABLE_TEST_PLAN, INVOCATION, PUBLIC_API_AND_COMPILATION_GUARDRAILS, OBSERVABLE_PLAN, and ASSERTIONS.\n");
        sb.append("2. Keep receiver/stub templates and guardrails as constraints, but do not add unnecessary code just because a guardrail mentions it.\n");
        sb.append("3. Use mutation evidence only to preserve the killing intent, not to invent inaccessible internal calls.\n\n");

        appendCompactSection(sb, "ENTRY", e.entry);
        appendCompactSection(sb, "EXECUTABLE_TEST_PLAN", e.executableTestPlan);
        appendCompactSection(sb, "INVOCATION_WITH_RECEIVER_AND_STUB_RULES", e.invocation);
        appendCompactSection(sb, "PUBLIC_API_AND_COMPILATION_GUARDRAILS", e.publicApiEvidence);
        appendCompactSection(sb, "OBSERVABLE_PLAN", e.observablePlan);
        appendCompactSection(sb, "ASSERTIONS", e.assertions);

        if (needsMutationContext(kind)) {
            appendCompactSection(sb, "MUTATION", e.mutation);
            appendCompactSection(sb, "MUTATION_EVIDENCE", e.mutationEvidence);
            appendCompactSection(sb, "MUTATION_GRAPH_EVIDENCE_A_CPG", e.mutationGraphEvidence);
            if (e.needEntryLiftedEvidence && e.entryEvidence.length() > 0) {
                appendCompactSection(sb, "ENTRY_EVIDENCE", e.entryEvidence);
            }
            if (e.needEntryLiftedEvidence && e.entryGraphEvidence.length() > 0) {
                appendCompactSection(sb, "ENTRY_GRAPH_EVIDENCE_B_CPG", e.entryGraphEvidence);
            }
        }

        sb.append("Previous test code:\n");
        sb.append(lastCode == null ? "" : lastCode).append("\n\n");
        sb.append("Return only corrected Java source code.\n");
        return sb.toString();
    }

    private static boolean needsMutationContext(CompileErrorKind kind) {
        return kind == CompileErrorKind.METHOD_NOT_FOUND
                || kind == CompileErrorKind.CONSTRUCTOR_MISMATCH
                || kind == CompileErrorKind.INCOMPATIBLE_TYPES
                || kind == CompileErrorKind.PRIVATE_ACCESS
                || kind == CompileErrorKind.PROTECTED_ACCESS
                || kind == CompileErrorKind.PACKAGE_PRIVATE_ACCESS
                || kind == CompileErrorKind.OTHER;
    }
}
