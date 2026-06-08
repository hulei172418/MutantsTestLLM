package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Adds compiler-specific diagnosis text to repair prompts without duplicating full evidence sections.
 */
public final class CompilerDiagnosisAppender {
    private CompilerDiagnosisAppender() {
    }

    public static void append(StringBuilder sb, PromptEvidence e, String compileError) {
        String err = compileError == null ? "" : compileError.toLowerCase(Locale.ROOT);
        JSONObject receiver = e == null ? null : e.invocation.optJSONObject("receiver");
        String strategy = receiver == null ? "" : receiver.optString("strategy", "");

        CompileErrorKind kind = CompileErrorClassifier.classify(compileError);
        sb.append("Compiler-specific diagnosis:\n");

        boolean wrote = false;

        if (kind == CompileErrorKind.ABSTRACT_STUB_INCOMPLETE) {
            sb.append("- The generated test stub/subclass is incomplete. Use the provided testStubClassTemplate when present, or implement every entry in receiver.abstractMethodsToImplement / receiver.allowedOverrides in one pass. Do not only add the single method reported by javac.\n");
            appendReceiverTemplateHints(sb, receiver);
            wrote = true;
        }

        if (kind == CompileErrorKind.OVERRIDE_FORBIDDEN_OR_SIGNATURE
                || kind == CompileErrorKind.OVERRIDE_ACCESS_WEAKENING) {
            sb.append("- The failure is caused by an invalid override. Override only methods explicitly listed in receiver.allowedOverrides. Remove overrides matching receiver.forbiddenOverrides. Keep the exact access modifier from the provided template.\n");
            appendReceiverTemplateHints(sb, receiver);
            wrote = true;
        }

        if (kind == CompileErrorKind.UNDEFINED_VARIABLE_OR_SETUP_MISMATCH) {
            sb.append("- The previous code referenced a variable that was not declared in setup/call. Keep variable names consistent across EXECUTABLE_TEST_PLAN.requiredSetup, INVOCATION.setup, INVOCATION.call, OBSERVABLE_PLAN, and ASSERTIONS. If an assertion references an undefined variable, either declare it from the evidence setup or use the observablePlan variable instead.\n");
            wrote = true;
        }

        if (kind == CompileErrorKind.INVENTED_GETTER_OR_API
                || (err.contains("cannot find symbol") && err.contains("method"))) {
            sb.append("- The previous code called a method that does not exist on the receiver. Use exact method names from PUBLIC_API.availablePublicMethods / availableSetupMethods. Do not guess JavaBean names such as getX(), isX(), or setX().\n");
            if (e != null && e.observablePlan.length() > 0) {
                sb.append("- If OBSERVABLE_PLAN.observableCall is present, prefer that observable. If OBSERVABLE_PLAN.kind is REFLECTION_FIELD_READ_AFTER_SETTER, use the reflection setup and do not replace it with a guessed getter.\n");
            }
            wrote = true;
        }

        if (kind == CompileErrorKind.DATAINPUT_ANONYMOUS_STUB) {
            sb.append("- Do not hand-write an anonymous java.io.DataInput implementation. Use java.io.DataInputStream over java.io.ByteArrayInputStream, or the concrete DataInput setup from the evidence.\n");
            wrote = true;
        }

        if ("STATIC_FACTORY_BUILDER".equals(strategy) || CompileErrorClassifier.looksLikeFactoryBuilderError(err)) {
            String setup = e == null ? "" : firstSetupStatement(e.invocation);
            String terminal = receiver == null ? "" : receiver.optString("builderTerminalMethod", "");
            String factory = receiver == null ? "" : receiver.optString("factoryMethod", "");
            sb.append("- The previous code used an invalid constructor or builder terminal method.\n");
            if (!factory.isEmpty()) {
                sb.append("- Use the static factory method from evidence: ").append(factory).append(".\n");
            }
            if (!terminal.isEmpty()) {
                sb.append("- The builder terminal method from evidence is ").append(terminal).append("(); use it exactly.\n");
            }
            if (!setup.isEmpty()) {
                sb.append("- Use this setup statement unless syntax requires line wrapping: ").append(setup).append("\n");
            }
            sb.append("- Do not call new TargetClass(), new Builder(), or builder.build() unless those exact forms appear in INVOCATION.setup.\n");
            wrote = true;
        }

        if (err.contains("must be first statement") || err.contains("super must be first statement")) {
            sb.append("- In a constructor of a nested test stub, super(...) must be the first statement. Move any local declarations after super(...).\n");
            wrote = true;
        }

        if (!wrote) {
            sb.append("- Use the compact evidence below as the source of truth. Make the smallest changes necessary for javac while preserving a mutation-sensitive assertion.\n");
        }
        sb.append('\n');
    }

    private static void appendReceiverTemplateHints(StringBuilder sb, JSONObject receiver) {
        if (receiver == null) {
            return;
        }
        String stub = receiver.optString("testStubClassTemplate", "").trim();
        if (!stub.isEmpty()) {
            sb.append("- receiver.testStubClassTemplate is available; prefer copying that template rather than creating a new subclass.\n");
        }
        JSONArray abstractMethods = receiver.optJSONArray("abstractMethodsToImplement");
        if (abstractMethods != null && abstractMethods.length() > 0) {
            sb.append("- Implement all receiver.abstractMethodsToImplement entries, not only the method named in the current javac error.\n");
        }
        JSONArray allowed = receiver.optJSONArray("allowedOverrides");
        if (allowed != null && allowed.length() > 0) {
            sb.append("- allowedOverrides count=").append(allowed.length()).append("; do not add other @Override methods.\n");
        }
    }

    private static String firstSetupStatement(JSONObject invocation) {
        if (invocation == null) {
            return "";
        }
        JSONArray arr = invocation.optJSONArray("setup");
        if (arr == null || arr.length() == 0) {
            return "";
        }
        return arr.optString(0, "").trim();
    }
}
