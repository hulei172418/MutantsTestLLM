package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.testgenerator.tools.CommonUtils.firstNonBlank;
import static mujava.testgenerator.tools.EvidenceUtils.*;
import static mujava.testgenerator.tools.GeneratorDefaults.*;

/**
 * Compact prompt evidence extracted from the full RIP/DataGenerator output.json.
 *
 * This class owns the evidence-construction pipeline: mutation metadata, entry
 * relation, invocation plan, assertion plan, A-side CPG evidence, and optional
 * B-side entry-lifted evidence.
 */
public final class PromptEvidence {
    final JSONObject mutation = new JSONObject();
    final JSONObject entry = new JSONObject();
    final JSONObject executableTestPlan = new JSONObject();
    final JSONObject invocation = new JSONObject();
    final JSONObject assertions = new JSONObject();
    final JSONObject mutationEvidence = new JSONObject();
    final JSONObject entryEvidence = new JSONObject();
    final JSONObject mutationGraphEvidence = new JSONObject();
    final JSONObject entryGraphEvidence = new JSONObject();
    final JSONObject publicApiEvidence = new JSONObject();
    final JSONObject observablePlan = new JSONObject();

    boolean needEntryLiftedEvidence;
    public boolean skipTestGeneration;
    public String skipReason = "";

    public static PromptEvidence fromFullOutput(JSONObject root) {
        PromptEvidence e = new PromptEvidence();
        if (root.has("mutation") && root.has("entry") && root.has("invocation") && root.has("assertions")) {
            copyObject(e.mutation, root.optJSONObject("mutation"));
            copyObject(e.entry, root.optJSONObject("entry"));
            copyObject(e.executableTestPlan, root.optJSONObject("executableTestPlan"));
            copyObject(e.invocation, root.optJSONObject("invocation"));
            copyObject(e.assertions, root.optJSONObject("assertions"));
            copyObject(e.mutationEvidence, root.optJSONObject("mutationEvidence"));
            copyObject(e.entryEvidence, root.optJSONObject("entryEvidence"));
            copyObject(e.mutationGraphEvidence, root.optJSONObject("mutationGraphEvidence"));
            copyObject(e.entryGraphEvidence, root.optJSONObject("entryGraphEvidence"));
            copyObject(e.publicApiEvidence, root.optJSONObject("publicApiEvidence"));
            copyObject(e.observablePlan, root.optJSONObject("observablePlan"));
            e.needEntryLiftedEvidence = e.entry.optBoolean("needEntryLiftedEvidence", false);
            e.skipTestGeneration = e.entry.optBoolean("skipTestGeneration", false);
            e.skipReason = e.entry.optString("skipReason", "");
            EvidencePostProcessor.postProcess(e);
            return e;
        }

        JSONObject dep = itemObject(root.optJSONObject("DependencyContext"));
        JSONObject depRel = itemObject(childObject(dep, "relationship"));
        JSONObject testEntryContext = itemObject(childObject(dep, "testEntryContext"));
        JSONObject entryRip = itemObject(root.optJSONObject("EntryLiftedRIP"));
        JSONObject entryRelation = itemObject(childObject(entryRip, "entryRelation"));
        JSONObject entryGenPlan = itemObject(childObject(entryRip, "entryGenerationPlan"));

        buildMutation(root, e.mutation);
        buildEntry(depRel, entryRip, entryRelation, testEntryContext, e.entry);
        buildInvocation(testEntryContext, entryGenPlan, e.invocation);
        buildPublicApiAndObservableEvidence(entryGenPlan, testEntryContext, e);
        buildAssertions(entryGenPlan, e.assertions);
        buildExecutableTestPlan(root, testEntryContext, entryGenPlan, entryRelation, depRel, e);
        buildMutationEvidence(root, e.mutationEvidence);
        buildMutationGraphEvidence(root, e.mutationGraphEvidence);
        e.needEntryLiftedEvidence = e.entry.optBoolean("needEntryLiftedEvidence", false);
        e.skipTestGeneration = e.entry.optBoolean("skipTestGeneration", false);
        e.skipReason = e.entry.optString("skipReason", "");
        if (e.needEntryLiftedEvidence) {
            buildEntryEvidence(entryRip, e.entryEvidence);
            buildEntryGraphEvidence(entryRip, e.entryGraphEvidence);
        }
        EvidencePostProcessor.postProcess(e);
        return e;
    }
    private static void buildExecutableTestPlan(JSONObject root,
                                                JSONObject testEntryContext,
                                                JSONObject entryGenPlan,
                                                JSONObject entryRelation,
                                                JSONObject depRel,
                                                PromptEvidence e) {
        JSONObject explicit = itemObject(root.optJSONObject("executableTestPlan"));
        if (explicit.length() == 0) {
            explicit = itemObject(childObject(entryGenPlan, "executableTestPlan"));
        }

        JSONObject plan = new JSONObject();

        String testPackage = firstNonBlank(
                fieldString(explicit, "testPackage"),
                e.invocation.optString("package", ""),
                fieldString(entryRelation, "testGenerationPackage"),
                fieldString(testEntryContext, "testPackage")
        );
        putIfNotEmpty(plan, "testPackage", testPackage);

        List<String> supportClasses = new ArrayList<String>();
        supportClasses.addAll(fieldItems(explicit, "supportClasses"));

        JSONObject receiver = e.invocation.optJSONObject("receiver");
        if (receiver != null) {
            String stub = sanitizeJavaSnippet(receiver.optString("testStubClassTemplate", ""));
            if (!stub.isEmpty()) {
                supportClasses.add(stub);
            }
        }

        if (!supportClasses.isEmpty()) {
            plan.put("supportClasses", new JSONArray(
                    limitList(cleanJavaSnippets(supportClasses), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        List<String> setup = new ArrayList<String>();
        setup.addAll(fieldItems(explicit, "requiredSetup"));
        setup.addAll(fieldItems(explicit, "setup"));
        setup.addAll(jsonArrayToStringList(e.invocation.optJSONArray("setup")));

        JSONObject branch = firstNonEmptyObject(
                itemObject(childObject(explicit, "branchReachability")),
                itemObject(childObject(entryGenPlan, "branchReachabilityPlan")),
                itemObject(childObject(itemObject(childObject(entryGenPlan, "publicApi")), "branchReachabilityPlan")),
                itemObject(childObject(itemObject(childObject(testEntryContext, "publicApi")), "branchReachabilityPlan"))
        );

        JSONObject branchOut = new JSONObject();
        putIfNotEmpty(branchOut, "kind", fieldString(branch, "kind"));
        putIfNotEmpty(branchOut, "condition", fieldString(branch, "condition"));
        putIfNotEmpty(branchOut, "setup", sanitizeJavaSnippet(fieldString(branch, "setup")));
        putIfNotEmpty(branchOut, "reason", fieldString(branch, "reason"));

        List<String> branchSetup = new ArrayList<String>();
        branchSetup.addAll(fieldItems(branch, "setupStatements"));
        String branchSetupText = sanitizeJavaSnippet(fieldString(branch, "setup"));
        if (!branchSetupText.isEmpty()) {
            branchSetup.add(branchSetupText);
        }

        if (!branchSetup.isEmpty()) {
            branchOut.put("setupStatements", new JSONArray(
                    limitList(cleanJavaStatements(branchSetup), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
            setup.addAll(branchSetup);
        }

        if (branchOut.length() > 0) {
            plan.put("branchReachability", branchOut);
        }

        String call = firstNonBlank(
                sanitizeJavaSnippet(fieldString(explicit, "entryCall")),
                e.invocation.optString("call", "")
        );
        putIfNotEmpty(plan, "entryCall", call);

        JSONObject observable = firstNonEmptyObject(
                itemObject(childObject(explicit, "observable")),
                e.observablePlan
        );

        JSONObject observableOut = new JSONObject();
        putIfNotEmpty(observableOut, "kind", fieldString(observable, "kind"));
        putIfNotEmpty(observableOut, "setup", sanitizeJavaSnippet(fieldString(observable, "setup")));
        putIfNotEmpty(observableOut, "observableCall", sanitizeJavaSnippet(fieldString(observable, "observableCall")));
        putIfNotEmpty(observableOut, "expectedOriginal", fieldString(observable, "expectedOriginal"));
        putIfNotEmpty(observableOut, "assertion", sanitizeJavaSnippet(fieldString(observable, "assertion")));
        putIfNotEmpty(observableOut, "reason", fieldString(observable, "reason"));

        if (observableOut.length() > 0) {
            plan.put("observable", observableOut);
            String obsSetup = observableOut.optString("setup", "").trim();
            if (!obsSetup.isEmpty()) {
                setup.add(obsSetup);
            }
        }

        List<String> forbidden = new ArrayList<String>();
        forbidden.addAll(fieldItems(explicit, "forbidden"));
        forbidden.addAll(fieldItems(explicit, "forbiddenDirectCalls"));
        forbidden.addAll(fieldItems(explicit, "antiPatterns"));

        JSONObject access = buildAccessConstraints(entryRelation, depRel, testEntryContext, entryGenPlan, e);
        if (access.length() > 0) {
            plan.put("accessConstraints", access);
            forbidden.addAll(jsonArrayToStringList(access.optJSONArray("forbiddenDirectCalls")));
            forbidden.addAll(jsonArrayToStringList(access.optJSONArray("forbiddenConstructions")));
        }

        JSONObject receiverOut = e.invocation.optJSONObject("receiver");
        enrichReceiverWithCompilationEvidence(receiver, receiverOut);
        // Do not duplicate the full receiver object inside EXECUTABLE_TEST_PLAN.
        // INVOCATION_WITH_RECEIVER_AND_STUB_RULES already carries receiver details.
        // Keeping only supportClasses/requiredSetup/entryCall prevents prompt bloat
        // and avoids LLM_OUTPUT_TRUNCATED for abstract-stub cases.
        if (receiverOut != null && receiverOut.length() > 0) {
            JSONObject receiverSummary = new JSONObject();
            putIfNotEmpty(receiverSummary, "strategy", receiverOut.optString("strategy", ""));
            putIfNotEmpty(receiverSummary, "runtimeReceiverClass", receiverOut.optString("runtimeReceiverClass", ""));
            putIfNotEmpty(receiverSummary, "resolutionReason", receiverOut.optString("resolutionReason", ""));
            if (receiverSummary.length() > 0) {
                plan.put("receiverSummary", receiverSummary);
            }
        }

        if (!setup.isEmpty()) {
            setup = cleanJavaStatements(setup);
            plan.put("requiredSetup", new JSONArray(
                    limitList(setup, DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
            e.invocation.put("setup", new JSONArray(setup));
        }

        if (!forbidden.isEmpty()) {
            plan.put("forbidden", new JSONArray(
                    limitList(uniqueNonBlank(forbidden), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        String status = firstNonBlank(fieldString(explicit, "status"), "");
        if (status.isEmpty()) {
            status = inferPlanStatus(plan, e);
        }
        putIfNotEmpty(plan, "status", status);

        String reason = firstNonBlank(
                fieldString(explicit, "reason"),
                branchOut.optString("reason", ""),
                observableOut.optString("reason", "")
        );
        putIfNotEmpty(plan, "reason", reason);

        copyObject(e.executableTestPlan, plan);
    }
    private static void enrichReceiverWithCompilationEvidence(JSONObject receiver,
                                                              JSONObject receiverOut) {
        if (receiver == null || receiver.length() == 0 || receiverOut == null) {
            return;
        }

        List<String> antiPatterns = fieldItems(receiver, "antiPatterns");
        if (!antiPatterns.isEmpty()) {
            receiverOut.put("antiPatterns",
                    new JSONArray(limitList(antiPatterns, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        List<String> allowedOverrides = fieldItems(receiver, "allowedOverrides");
        if (!allowedOverrides.isEmpty()) {
            receiverOut.put("allowedOverrides",
                    new JSONArray(limitList(cleanJavaSnippets(allowedOverrides), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        List<String> forbiddenOverrides = fieldItems(receiver, "forbiddenOverrides");
        if (!forbiddenOverrides.isEmpty()) {
            receiverOut.put("forbiddenOverrides",
                    new JSONArray(compactForbiddenOverrides(forbiddenOverrides, receiverOut)));
        }

        List<String> abstractMethods = fieldItems(receiver, "abstractMethodsToImplement");
        if (!abstractMethods.isEmpty()) {
            receiverOut.put("abstractMethodsToImplement",
                    new JSONArray(limitList(cleanJavaSnippets(abstractMethods), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        String stubClassTemplate = firstNonBlank(
                fieldString(receiver, "testStubClassTemplate"),
                fieldStringCompat(receiver, "testStubClassTemplate")
        );
        if (!stubClassTemplate.trim().isEmpty()) {
            receiverOut.put("testStubClassTemplate", sanitizeJavaSnippet(stubClassTemplate));
        }

        String stubConstructorTemplate = firstNonBlank(
                fieldString(receiver, "testStubConstructorTemplate"),
                fieldStringCompat(receiver, "testStubConstructorTemplate")
        );
        if (!stubConstructorTemplate.trim().isEmpty()) {
            receiverOut.put("testStubConstructorTemplate", sanitizeJavaSnippet(stubConstructorTemplate));
        }

        String reason = fieldString(receiver, "resolutionReason");
        if (!reason.trim().isEmpty()) {
            receiverOut.put("resolutionReason", reason);
        }
    }

    private static List<String> compactForbiddenOverrides(List<String> raw, JSONObject receiverOut) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<String>();
        }
        boolean hasStub = receiverOut != null
                && receiverOut.optString("testStubClassTemplate", "").trim().length() > 0;
        if (hasStub) {
            out.add("Only use the generated testStubClassTemplate; do not add extra @Override methods.");
            out.add("Do not override final/private/concrete/nonexistent methods.");
            out.add("Do not reduce access privileges when overriding.");
            return new ArrayList<String>(out);
        }
        for (String x : raw) {
            if (x == null) continue;
            String v = x.trim();
            if (v.isEmpty()) continue;
            out.add(v);
            if (out.size() >= Math.min(5, DEFAULT_MAX_ITEMS_IN_PROMPT)) {
                break;
            }
        }
        return new ArrayList<String>(out);
    }

    private static String fieldStringCompat(JSONObject obj, String name) {
        JSONObject child = childObject(obj, name);
        if (child == null || child.length() == 0) {
            return "";
        }

        String v = child.optString("value", "");
        if (!v.trim().isEmpty()) {
            return v.trim();
        }

        v = child.optString("item", "");
        return v == null ? "" : v.trim();
    }

    private static JSONObject buildAccessConstraints(JSONObject entryRelation,
                                                     JSONObject depRel,
                                                     JSONObject testEntryContext,
                                                     JSONObject entryGenPlan,
                                                     PromptEvidence e) {
        JSONObject out = new JSONObject();

        boolean useReflection = firstBoolean(false,
                fieldBoolean(entryRelation, "useReflectionFallback", null),
                fieldBoolean(depRel, "useReflectionFallback", null));

        out.put("reflectionAllowed", useReflection);

        String mutationMethod = firstNonBlank(
                e.entry.optString("mutationMethod", ""),
                fieldString(entryRelation, "mutationMethod"),
                fieldString(depRel, "mutationMethod")
        );

        String entryMethod = firstNonBlank(
                e.entry.optString("entryMethod", ""),
                fieldString(entryRelation, "testEntryMethod"),
                fieldString(depRel, "testEntryMethod")
        );

        boolean sameEntry = e.entry.optBoolean("sameEntryAndMutation", false);
        boolean mutationLooksPrivate = mutationMethod.toLowerCase(Locale.ROOT).contains("private ");
        boolean entryLooksPrivate = entryMethod.toLowerCase(Locale.ROOT).contains("private ");

        out.put("directPrivateCallAllowed", useReflection || (!mutationLooksPrivate && !entryLooksPrivate));
        out.put("mustUseEntryMethod", !sameEntry || mutationLooksPrivate || entryLooksPrivate);

        List<String> forbiddenCalls = new ArrayList<String>();

        List<String> callChain = jsonArrayToStringList(e.entry.optJSONArray("callChain"));
        for (String c : callChain) {
            String x = c == null ? "" : c.trim();
            if (x.toLowerCase(Locale.ROOT).contains("private ")) {
                forbiddenCalls.add(x);
            }
        }

        JSONObject internal = itemObject(childObject(testEntryContext, "internalCalls"));
        JSONArray calls = itemsArray(internal);
        for (int i = 0; i < calls.length(); i++) {
            Object v = calls.opt(i);
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                String text = o.optString("text", "").trim();
                if (looksInternalForbiddenCall(text)) {
                    forbiddenCalls.add(text);
                }
            }
        }

        if (!forbiddenCalls.isEmpty()) {
            out.put("forbiddenDirectCalls", new JSONArray(
                    limitList(uniqueNonBlank(forbiddenCalls), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        List<String> forbiddenConstructions = new ArrayList<String>();
        JSONObject recv = e.invocation.optJSONObject("receiver");
        if (recv != null) {
            String strategy = recv.optString("strategy", "");
            if ("TEST_STUB_SUBCLASS".equals(strategy) || "ANONYMOUS_SUBCLASS".equals(strategy)) {
                forbiddenConstructions.add("Do not instantiate the abstract owner directly; use the provided test stub/subclass template.");
            }
            if ("STATIC_FACTORY_BUILDER".equals(strategy)) {
                forbiddenConstructions.add("Do not call new TargetClass() or new Builder(); use the provided factory/builder setup chain.");
            }
        }

        if (!forbiddenConstructions.isEmpty()) {
            out.put("forbiddenConstructions", new JSONArray(
                    limitList(forbiddenConstructions, DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        return out;
    }

    private static boolean looksInternalForbiddenCall(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String t = text.trim();
        return t.contains("doPredicate(")
                || t.contains("doPredicateIndex(")
                || t.contains("isCollectionElement(")
                || t.contains("getNodeIterator(");
    }

    private static JSONObject firstNonEmptyObject(JSONObject... objects) {
        if (objects == null) {
            return new JSONObject();
        }
        for (JSONObject o : objects) {
            if (o != null && o.length() > 0) {
                return o;
            }
        }
        return new JSONObject();
    }

    private static String inferPlanStatus(JSONObject plan, PromptEvidence e) {
        JSONArray setup = plan.optJSONArray("requiredSetup");
        String call = plan.optString("entryCall", "");
        JSONArray support = plan.optJSONArray("supportClasses");

        boolean hasSetup = setup != null && setup.length() > 0;
        boolean hasCall = call != null && !call.trim().isEmpty();
        boolean hasStub = support != null && support.length() > 0;

        JSONObject receiver = e.invocation.optJSONObject("receiver");
        String strategy = receiver == null ? "" : receiver.optString("strategy", "");

        if (hasCall && (hasSetup || hasStub || "STATIC_NO_RECEIVER".equals(strategy))) {
            return "READY";
        }
        if (hasCall) {
            return "PARTIAL";
        }
        return "PARTIAL";
    }

    private static List<String> uniqueNonBlank(List<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.trim().isEmpty()) {
                    set.add(v.trim());
                }
            }
        }
        return new ArrayList<String>(set);
    }

    private static List<String> cleanJavaSnippets(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String v : values) {
                String s = sanitizeJavaSnippet(v);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        out.put("mutation", mutation);
        out.put("entry", entry);

        if (executableTestPlan.length() > 0) {
            out.put("executableTestPlan", executableTestPlan);
        }

        out.put("invocation", invocation);
        out.put("assertions", assertions);

        if (publicApiEvidence.length() > 0) {
            out.put("publicApiEvidence", publicApiEvidence);
        }
        if (observablePlan.length() > 0) {
            out.put("observablePlan", observablePlan);
        }

        out.put("mutationEvidence", mutationEvidence);

        if (mutationGraphEvidence.length() > 0) {
            out.put("mutationGraphEvidence", mutationGraphEvidence);
        }
        if (needEntryLiftedEvidence && entryEvidence.length() > 0) {
            out.put("entryEvidence", entryEvidence);
        }
        if (needEntryLiftedEvidence && entryGraphEvidence.length() > 0) {
            out.put("entryGraphEvidence", entryGraphEvidence);
        }

        return out;
    }

    private static void buildMutation(JSONObject root, JSONObject out) {
        out.put("operator", wrapperString(root.optJSONObject("operator")));
        out.put("diff", trimEvidence(wrapperString(root.optJSONObject("Diff")), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        List<String> changes = wrapperItems(root.optJSONObject("JimpleChanges"));
        out.put("affected", new JSONArray(limitList(changes, DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static void buildEntry(JSONObject depRel,
                                   JSONObject entryRip,
                                   JSONObject entryRelation,
                                   JSONObject testEntryContext,
                                   JSONObject out) {
        boolean same = firstBoolean(false,
                fieldBoolean(entryRip, "sameEntryAndMutation", null),
                fieldBoolean(entryRelation, "sameEntryAndMutation", null),
                fieldBoolean(depRel, "sameEntryAndMutation", null));
        boolean need = firstBoolean(false,
                fieldBoolean(entryRip, "needEntryLiftedEvidence", null),
                fieldBoolean(entryRelation, "needEntryLiftedEvidence", null),
                fieldBoolean(depRel, "needEntryLiftedEvidence", null));

        out.put("sameEntryAndMutation", same);
        out.put("needEntryLiftedEvidence", need);
        out.put("recommendedTarget", firstNonBlank(
                fieldString(entryRelation, "recommendedTestTarget"),
                fieldString(depRel, "recommendedTestTarget")));
        out.put("invocationKind", firstNonBlank(
                fieldString(entryRip, "entryInvocationKind"),
                fieldString(entryRelation, "entryInvocationKind"),
                fieldString(depRel, "entryInvocationKind"),
                fieldString(testEntryContext, "entryInvocationKind")));
        out.put("testPackage", firstNonBlank(
                fieldString(entryRelation, "testGenerationPackage"),
                fieldString(testEntryContext, "testPackage")));
        out.put("mutationMethod", firstNonBlank(
                fieldString(entryRelation, "mutationMethod"),
                fieldString(depRel, "mutationMethod")));
        out.put("entryMethod", firstNonBlank(
                fieldString(entryRelation, "testEntryMethod"),
                fieldString(depRel, "testEntryMethod")));
        out.put("mutationClass", firstNonBlank(
                fieldString(entryRelation, "mutationClass"),
                fieldString(depRel, "mutationClass")));
        out.put("entryClass", firstNonBlank(
                fieldString(entryRelation, "testEntryClass"),
                fieldString(depRel, "testEntryClass")));

        List<String> callChain = fieldItems(entryRelation, "callChain");
        if (callChain.isEmpty()) {
            callChain = fieldItems(depRel, "callChain");
        }
        out.put("callChain", new JSONArray(cleanCallChain(callChain)));
        out.put("skipTestGeneration", firstBoolean(false,
                fieldBoolean(entryRelation, "skipTestGeneration", null),
                fieldBoolean(depRel, "skipTestGeneration", null)));
        out.put("skipReason", firstNonBlank(
                fieldString(entryRelation, "skipReason"),
                fieldString(depRel, "skipReason")));
    }

    private static void buildPublicApiAndObservableEvidence(JSONObject entryGenPlan,
                                                            JSONObject testEntryContext,
                                                            PromptEvidence e) {
        JSONObject publicApi = itemObject(childObject(entryGenPlan, "publicApi"));
        if (publicApi.length() == 0) {
            publicApi = itemObject(childObject(testEntryContext, "publicApi"));
        }

        JSONObject observable = itemObject(childObject(entryGenPlan, "observablePlan"));
        if (observable.length() == 0) {
            observable = itemObject(childObject(publicApi, "observablePlan"));
        }

        JSONObject branch = itemObject(childObject(entryGenPlan, "branchReachabilityPlan"));
        if (branch.length() == 0) {
            branch = itemObject(childObject(publicApi, "branchReachabilityPlan"));
        }

        List<String> availablePublic = fieldItems(publicApi, "availablePublicMethods");
        List<String> availableSetup = fieldItems(publicApi, "availableSetupMethods");
        List<String> stateSetup = fieldItems(publicApi, "stateSetupPlan");
        List<String> apiAnti = fieldItems(publicApi, "antiPatterns");

        // v35: 全局编译安全规则，必须作为硬约束保留
        List<String> compilationGuardrails = fieldItems(publicApi, "compilationGuardrails");

        JSONObject api = new JSONObject();

        if (!availablePublic.isEmpty()) {
            api.put("availablePublicMethods",
                    new JSONArray(limitList(availablePublic, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!availableSetup.isEmpty()) {
            api.put("availableSetupMethods",
                    new JSONArray(limitList(availableSetup, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!stateSetup.isEmpty()) {
            api.put("stateSetupPlan",
                    new JSONArray(limitList(cleanJavaStatements(stateSetup), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!apiAnti.isEmpty()) {
            api.put("antiPatterns",
                    new JSONArray(limitList(apiAnti, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!compilationGuardrails.isEmpty()) {
            api.put("compilationGuardrails",
                    new JSONArray(limitList(compilationGuardrails, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        JSONObject branchOut = new JSONObject();
        putIfNotEmpty(branchOut, "kind", fieldString(branch, "kind"));
        putIfNotEmpty(branchOut, "condition", fieldString(branch, "condition"));
        putIfNotEmpty(branchOut, "setup", sanitizeJavaSnippet(fieldString(branch, "setup")));
        putIfNotEmpty(branchOut, "reason", fieldString(branch, "reason"));
        if (branchOut.length() > 0) {
            api.put("branchReachabilityPlan", branchOut);
        }

        copyObject(e.publicApiEvidence, api);

        JSONObject obs = new JSONObject();
        putIfNotEmpty(obs, "kind", fieldString(observable, "kind"));
        putIfNotEmpty(obs, "setup", sanitizeJavaSnippet(fieldString(observable, "setup")));
        putIfNotEmpty(obs, "observableCall", sanitizeJavaSnippet(fieldString(observable, "observableCall")));
        putIfNotEmpty(obs, "expectedOriginal", fieldString(observable, "expectedOriginal"));
        putIfNotEmpty(obs, "reason", fieldString(observable, "reason"));

        List<String> obsAnti = fieldItems(observable, "antiPatterns");
        if (!obsAnti.isEmpty()) {
            obs.put("antiPatterns", new JSONArray(limitList(obsAnti, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        copyObject(e.observablePlan, obs);
    }

    private static void buildInvocation(JSONObject testEntryContext,
                                        JSONObject entryGenPlan,
                                        JSONObject out) {
        JSONObject invocationPlan = itemObject(childObject(entryGenPlan, "invocationPlan"));
        JSONObject suggested = itemObject(childObject(entryGenPlan, "suggestedTestValues"));
        JSONObject receiver = itemObject(childObject(entryGenPlan, "receiver"));
        if (receiver.length() == 0) {
            receiver = itemObject(childObject(testEntryContext, "receiver"));
        }

        String pkg = fieldString(testEntryContext, "testPackage");
        out.put("package", pkg);

        List<String> imports = fieldItems(testEntryContext, "requiredImports");
        imports = ensureBasicJUnitImports(imports);
        out.put("imports", new JSONArray(imports));

        JSONObject recv = new JSONObject();
        putIfNotEmpty(recv, "strategy", fieldString(receiver, "strategy"));
        putIfNotEmpty(recv, "construction", sanitizeJavaSnippet(fieldString(receiver, "construction")));
        putIfNotEmpty(recv, "ownerKind", fieldString(receiver, "ownerKind"));
        putBooleanIfPresent(recv, "ownerAbstract", fieldBoolean(receiver, "ownerAbstract", null));
        putBooleanIfPresent(recv, "ownerInterface", fieldBoolean(receiver, "ownerInterface", null));
        putBooleanIfPresent(recv, "ownerInstantiable", fieldBoolean(receiver, "ownerInstantiable", null));
        putIfNotEmpty(recv, "runtimeReceiverClass", fieldString(receiver, "runtimeReceiverClass"));
        putIfNotEmpty(recv, "runtimeReceiverSootClass", fieldString(receiver, "runtimeReceiverSootClass"));
        putIfNotEmpty(recv, "declaringClass", fieldString(receiver, "declaringClass"));
        putIfNotEmpty(recv, "dispatchTarget", fieldString(receiver, "dispatchTarget"));
        putBooleanIfPresent(recv, "dispatchesToMutationMethod", fieldBoolean(receiver, "dispatchesToMutationMethod", null));
        putBooleanIfPresent(recv, "subclassOverridesMutationMethod", fieldBoolean(receiver, "subclassOverridesMutationMethod", null));
        putIfNotEmpty(recv, "setupTemplate", sanitizeJavaSnippet(fieldString(receiver, "setupTemplate")));
        putIfNotEmpty(recv, "invocationTemplate", sanitizeJavaSnippet(fieldString(receiver, "invocationTemplate")));
        putIfNotEmpty(recv, "resolutionReason", fieldString(receiver, "resolutionReason"));
        putIfNotEmpty(recv, "notes", fieldString(receiver, "notes"));
        putIfNotEmpty(recv, "factoryMethod", fieldString(receiver, "factoryMethod"));
        putIfNotEmpty(recv, "factoryMethodName", fieldString(receiver, "factoryMethodName"));
        putIfNotEmpty(recv, "builderClass", fieldString(receiver, "builderClass"));
        putIfNotEmpty(recv, "builderTerminalMethod", fieldString(receiver, "builderTerminalMethod"));
        putIfNotEmpty(recv, "builderSetupChain", fieldString(receiver, "builderSetupChain"));
        putIfNotEmpty(recv, "factoryBuilderReason", fieldString(receiver, "factoryBuilderReason"));
        List<String> receiverAntiPatterns = fieldItems(receiver, "antiPatterns");
        List<String> abstractMethods = fieldItems(receiver, "abstractMethodsToImplement");
        List<String> allowedOverrides = fieldItems(receiver, "allowedOverrides");
        List<String> forbiddenOverrides = fieldItems(receiver, "forbiddenOverrides");
        if (!abstractMethods.isEmpty()) {
            recv.put("abstractMethodsToImplement", new JSONArray(limitList(abstractMethods, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        if (!allowedOverrides.isEmpty()) {
            recv.put("allowedOverrides", new JSONArray(limitList(allowedOverrides, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        if (!forbiddenOverrides.isEmpty()) {
            recv.put("forbiddenOverrides", new JSONArray(compactForbiddenOverrides(forbiddenOverrides, recv)));
        }
        String compileReadyStub = firstNonBlank(
                fieldString(receiver, "compileReadyStubTemplate"),
                fieldString(receiver, "testStubClassTemplate"));
        String compileReadyCtor = firstNonBlank(
                fieldString(receiver, "compileReadyConstructorTemplate"),
                fieldString(receiver, "testStubConstructorTemplate"));
        putIfNotEmpty(recv, "testStubClassTemplate", sanitizeJavaSnippet(compileReadyStub));
        putIfNotEmpty(recv, "testStubConstructorTemplate", sanitizeJavaSnippet(compileReadyCtor));
        putIfNotEmpty(recv, "stubCompleteness", fieldString(receiver, "stubCompleteness"));
        if (!receiverAntiPatterns.isEmpty()) {
            recv.put("antiPatterns", new JSONArray(limitList(receiverAntiPatterns, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        out.put("receiver", recv);

        List<String> setup = fieldItems(suggested, "setupStatements");
        String receiverSetupTemplate = sanitizeJavaSnippet(firstNonBlank(
                fieldString(receiver, "compileReadySetupTemplate"),
                fieldString(receiver, "setupTemplate")));
        String setupTemplate = sanitizeJavaSnippet(firstNonBlank(
                fieldString(invocationPlan, "setupTemplate"),
                receiverSetupTemplate));
        if (!receiverSetupTemplate.isEmpty()) {
            setup.add(0, receiverSetupTemplate);
        }
        if (!setupTemplate.isEmpty()) {
            setup.add(0, setupTemplate);
        }
        setup = cleanJavaStatements(setup);
        out.put("setup", new JSONArray(setup));

        String call = sanitizeJavaSnippet(firstNonBlank(
                fieldString(receiver, "compileReadyInvocationTemplate"),
                fieldString(receiver, "invocationTemplate"),
                fieldString(invocationPlan, "invocationTemplate")));
        putIfNotEmpty(out, "call", call);
        putIfNotEmpty(out, "notes", firstNonBlank(fieldString(invocationPlan, "notes"), fieldString(receiver, "notes")));
    }

    private static void buildAssertions(JSONObject entryGenPlan, JSONObject out) {
        JSONObject assertionPlan = itemObject(childObject(entryGenPlan, "assertionPlan"));
        JSONObject recommended = itemObject(childObject(assertionPlan, "recommendedAssertions"));

        List<String> required = assertionTemplates(childObject(recommended, "requiredToKill"));
        List<String> optional = assertionTemplates(childObject(recommended, "optionalSanityChecks"));
        List<String> sensitive = observableExpressions(childObject(assertionPlan, "mutationSensitiveObservables"));
        List<String> auxiliary = observableExpressions(childObject(assertionPlan, "auxiliaryObservables"));
        List<String> avoid = fieldItems(assertionPlan, "antiPatterns");

        out.put("requiredToKill", new JSONArray(limitList(cleanJavaStatements(required), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("optionalSanityChecks", new JSONArray(limitList(cleanJavaStatements(optional), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("mutationSensitiveObservables", new JSONArray(limitList(sensitive, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("auxiliaryObservables", new JSONArray(limitList(auxiliary, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("avoid", new JSONArray(limitList(avoid, DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static void buildMutationEvidence(JSONObject root, JSONObject out) {
        JSONObject origin = itemObject(root.optJSONObject("origin"));
        JSONObject mutated = itemObject(root.optJSONObject("mutated"));
        String originCode = fieldString(origin, "content");
        String mutantCode = fieldString(mutated, "content");
        List<String> originAffected = fieldItems(origin, "Affected");
        List<String> mutatedAffected = fieldItems(mutated, "Affected");

        putIfNotEmpty(out, "originCode", trimEvidence(originCode, DEFAULT_MAX_CODE_CHARS));
        putIfNotEmpty(out, "mutantCode", trimEvidence(mutantCode, DEFAULT_MAX_CODE_CHARS));
        out.put("originAffected", new JSONArray(limitList(originAffected, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("mutantAffected", new JSONArray(limitList(mutatedAffected, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        putIfNotEmpty(out, "propagationHint", inferPropagationHint(root));
    }


    private static void buildMutationGraphEvidence(JSONObject root, JSONObject out) {
        JSONObject origin = itemObject(root.optJSONObject("origin"));
        JSONObject mutated = itemObject(root.optJSONObject("mutated"));

        out.put("role", "A-side CPG/RIP evidence. Use this to reason from the real mutation point A to observable sinks and to design mutation-sensitive assertions.");

        JSONObject originGraph = summarizeCpgSide(origin);
        JSONObject mutantGraph = summarizeCpgSide(mutated);
        if (originGraph.length() > 0) {
            out.put("originGraph", originGraph);
        }
        if (mutantGraph.length() > 0) {
            out.put("mutantGraph", mutantGraph);
        }
        JSONArray available = unionAvailableEvidenceKinds(originGraph, mutantGraph);
        if (available.length() > 0) {
            out.put("availableEvidenceKinds", available);
        }

        List<String> originPaths = fieldItems(origin, "Paths");
        List<String> mutantPaths = fieldItems(mutated, "Paths");
        if (!originPaths.isEmpty()) {
            out.put("originPathSummary", trimEvidence(originPaths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        if (!mutantPaths.isEmpty()) {
            out.put("mutantPathSummary", trimEvidence(mutantPaths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
    }

    private static void buildEntryGraphEvidence(JSONObject entryRip, JSONObject out) {
        JSONObject origin = itemObject(childObject(entryRip, "origin"));
        JSONObject mutated = itemObject(childObject(entryRip, "mutated"));

        out.put("role", "B-side CPG/RIP evidence. Use this only to understand how callable entry B reaches real mutation method A; design assertions mainly from A-side mutationGraphEvidence.");

        JSONObject originGraph = summarizeCpgSide(origin);
        if (originGraph.length() > 0) {
            out.put("originEntryGraph", originGraph);
        }
        JSONArray available = unionAvailableEvidenceKinds(originGraph);
        if (available.length() > 0) {
            out.put("availableEvidenceKinds", available);
        }

        List<String> callSites = fieldItems(origin, "callSitesToMutation");
        if (!callSites.isEmpty()) {
            out.put("callSiteToMutation", trimEvidence(callSites.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        List<String> paths = fieldItems(origin, "Paths");
        if (!paths.isEmpty()) {
            out.put("entryPathSummary", trimEvidence(paths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        out.put("mutatedReusesOriginEntryGraph", fieldBoolean(mutated, "reusedFromOrigin", false));
    }

    private static JSONArray unionAvailableEvidenceKinds(JSONObject... graphs) {
        LinkedHashSet<String> kinds = new LinkedHashSet<String>();
        if (graphs != null) {
            for (JSONObject g : graphs) {
                if (g == null) {
                    continue;
                }
                JSONArray arr = g.optJSONArray("availableEvidenceKinds");
                if (arr == null) {
                    continue;
                }
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) {
                        kinds.add(s);
                    }
                }
            }
        }
        return new JSONArray(new ArrayList<String>(kinds));
    }

    private static JSONObject summarizeCpgSide(JSONObject side) {
        JSONObject out = new JSONObject();
        JSONObject cpg = childObject(side, "CPG");
        JSONArray arr = itemsArray(cpg);
        if (arr.length() == 0) {
            return out;
        }

        LinkedHashSet<String> availableKinds = new LinkedHashSet<String>();

        Object first = arr.opt(0);
        if (!(first instanceof JSONObject)) {
            out.put("raw", trimEvidence(String.valueOf(first), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            availableKinds.add("raw");
            out.put("availableEvidenceKinds", new JSONArray(new ArrayList<String>(availableKinds)));
            return out;
        }

        JSONObject item = (JSONObject) first;
        String path = fieldString(item, "Path");
        if (!path.isEmpty()) {
            out.put("path", trimEvidence(path, DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            availableKinds.add("path");
        }

        JSONObject cfgRaw = itemObject(childObject(item, "CFG"));
        JSONObject cfg = new JSONObject();
        putNonEmptyArray(cfg, "dom", fieldItems(cfgRaw, "dom"), availableKinds, "cfg.dom");
        putNonEmptyArray(cfg, "pathPredicates", fieldItems(cfgRaw, "path_predicates"), availableKinds, "cfg.pathPredicates");
        putNonEmptyArray(cfg, "controlDepsOut", fieldItems(cfgRaw, "control_deps_out"), availableKinds, "cfg.controlDepsOut");
        if (cfg.length() > 0) {
            out.put("cfg", cfg);
        }

        JSONObject dfgRaw = itemObject(childObject(item, "DFG"));
        JSONObject dfg = new JSONObject();
        putNonEmptyArray(dfg, "defsAtPoint", compactDfgItems(childObject(dfgRaw, "defs_point")), availableKinds, "dfg.defsAtPoint");
        putNonEmptyArray(dfg, "usesTowardOutput", compactDfgItems(childObject(dfgRaw, "uses_toward_output")), availableKinds, "dfg.usesTowardOutput");
        putNonEmptyArray(dfg, "killSet", compactDfgItems(childObject(dfgRaw, "kill_set")), availableKinds, "dfg.killSet");
        putNonEmptyArray(dfg, "heapAccess", compactDfgItems(childObject(dfgRaw, "heap_access")), availableKinds, "dfg.heapAccess");
        putNonEmptyArray(dfg, "mayThrow", compactDfgItems(childObject(dfgRaw, "may_throw")), availableKinds, "dfg.mayThrow");
        putNonEmptyArray(dfg, "aliasGroups", compactDfgItems(childObject(dfgRaw, "alias_groups")), availableKinds, "dfg.aliasGroups");
        if (dfg.length() > 0) {
            out.put("dfg", dfg);
        }

        if (!availableKinds.isEmpty()) {
            out.put("availableEvidenceKinds", new JSONArray(new ArrayList<String>(availableKinds)));
        }
        return out;
    }

    private static void putNonEmptyArray(JSONObject target,
                                         String key,
                                         List<String> values,
                                         LinkedHashSet<String> availableKinds,
                                         String evidenceKind) {
        if (values == null || values.isEmpty()) {
            return;
        }
        target.put(key, new JSONArray(limitList(values, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        availableKinds.add(evidenceKind);
    }

    private static List<String> compactDfgItems(JSONObject wrapper) {
        JSONArray arr = itemsArray(wrapper);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v == null) {
                continue;
            }
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                String var = o.optString("var", "");
                String unit = o.optString("unit", "");
                String sink = o.optString("sink", "");
                StringBuilder sb = new StringBuilder();
                if (!var.isEmpty()) {
                    sb.append(var);
                }
                if (!unit.isEmpty()) {
                    if (sb.length() > 0) sb.append(" <- ");
                    sb.append(unit);
                }
                if (!sink.isEmpty()) {
                    sb.append(" -> sink=").append(sink);
                }
                if (sb.length() == 0) {
                    sb.append(o.toString());
                }
                out.add(trimEvidence(sb.toString(), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            } else {
                out.add(trimEvidence(String.valueOf(v), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            }
        }
        return out;
    }
    private static void buildEntryEvidence(JSONObject entryRip, JSONObject out) {
        JSONObject origin = itemObject(childObject(entryRip, "origin"));
        JSONObject mutated = itemObject(childObject(entryRip, "mutated"));
        String entryCode = fieldString(origin, "content");
        List<String> callSites = fieldItems(origin, "callSitesToMutation");
        List<String> paths = fieldItems(origin, "Paths");
        boolean reused = fieldBoolean(mutated, "reusedFromOrigin", false);

        putIfNotEmpty(out, "entryCode", trimEvidence(entryCode, DEFAULT_MAX_CODE_CHARS));
        if (!callSites.isEmpty()) {
            out.put("callSiteToMutation", trimEvidence(callSites.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        if (!paths.isEmpty()) {
            out.put("pathSummary", trimEvidence(paths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        out.put("mutatedReusesOriginEntryGraph", reused);
    }
}

