package org.rip;

import org.astjimple.AstToJimpleBridge;
import org.astjimple.ChangeRange;
import org.model.DFG;
import org.utils.MethodSignature;

import soot.Body;
import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.jimple.DefinitionStmt;
import soot.jimple.IfStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.ThrowStmt;
import soot.jimple.ReturnStmt;
import soot.toolkits.graph.BlockGraph;
import soot.toolkits.graph.BriefBlockGraph;
import soot.toolkits.graph.DominatorsFinder;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.MHGDominatorsFinder;
import soot.toolkits.graph.MHGPostDominatorsFinder;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.SimpleLocalDefs;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.astjimple.MethodContent.extractMethodAsOneLine;
import static org.rip.RipExtractor.computeControlDepsText;
import static org.rip.RipExtractor.unitsToBlockSummaries;

/**
 * Build entry-lifted RIP evidence for the callable test entry method B.
 *
 * Design intent:
 * 1) MethodEntryResolver only resolves the callable entry B.
 * 2) This builder uses Soot/Jimple, not source-only AST parsing, to construct
 * the entry-side graph evidence for B, so the evidence style is consistent
 * with RipParser.buildOriginSide/buildMutantSide.
 * 3) If B != A, the mutated-side B graph is normally identical to the
 * origin-side
 * B graph, because the mutation is located in A. In that case the mutated
 * EntryLiftedRIP reuses the origin graph and explicitly records the reuse.
 * 4) All output fields follow the comment/item or comment/items JSON style.
 */
public final class EntryLiftedRipBuilder {

    private static final int ENTRY_DEPTH_LIMIT = 1024;
    private static final int ENTRY_PATH_LIMIT = 4;
    private static final int ENTRY_K_BOUNDED = 1;

    /**
     * Cache complete origin-side entry graph snapshots.
     * This is safe when the focus is the entry call site B -> A, because it is
     * invariant across multiple mutants sharing the same original program.
     */
    private static final Map<String, EntrySideSnapshot> ORIGIN_ENTRY_GRAPH_CACHE = new ConcurrentHashMap<>();

    /** Source method text cache. Key includes the concrete java file path. */
    private static final Map<String, String> METHOD_TEXT_CACHE = new ConcurrentHashMap<>();

    private EntryLiftedRipBuilder() {
    }

    public static final class EntryContext {
        public String originId = "p";
        public String mutantId = "m";

        public String originJavaFile;
        public String mutantJavaFile;
        public String originClassesDir;
        public String mutantClassesDir;

        /** A: real mutation method. */
        public String mutationClassName;
        public String mutationMethodSig;

        /** B: callable test entry method. */
        public String entryClassName;
        public String entryMethodSig;

        public String testEntryKind = "DIRECT_OR_UNRESOLVED";
        public boolean useReflectionFallback = false;
        public String testCallChain = "";
        public String testEntryNotes = "";
        public String testGenerationPackage = "";

        /**
         * Prompt-facing entry invocation guidance, moved from TestEntry into
         * EntryLiftedRIP.
         */
        public String entryInvocationKind = "";
        /**
         * Prompt-facing mutation diff text, used only for assertion/observation
         * planning.
         */
        public String diff = "";
        public String receiverOwnerKind = "";
        public boolean receiverOwnerAbstract = false;
        public boolean receiverOwnerInterface = false;
        public boolean receiverOwnerInstantiable = false;
        public String receiverStrategy = "";
        public String receiverConstruction = "";
        public String receiverNotes = "";
        public String receiverRuntimeClassName = "";
        public String receiverRuntimeSootClassName = "";
        public String receiverDeclaringClassName = "";
        public String receiverDispatchTarget = "";
        public boolean receiverDispatchesToMutationMethod = false;
        public boolean receiverSubclassOverridesMutationMethod = false;
        public String receiverSetupTemplate = "";
        public String receiverInvocationTemplate = "";
        public String receiverResolutionReason = "";
        public String receiverFactoryMethod = "";
        public String receiverBuilderClassName = "";
        public String receiverBuilderTerminalMethod = "";
        public String receiverBuilderSetupChain = "";
        public String receiverAntiPatterns = "";
        public String availablePublicMethods = "";
        public String availableSetupMethods = "";
        public String stateSetupPlan = "";
        public String observablePlanKind = "";
        public String observableSetup = "";
        public String observableCall = "";
        public String observableExpectedOriginal = "";
        public String observableReason = "";
        public String observableAntiPatterns = "";
        public String branchReachabilityKind = "";
        public String branchReachabilityCondition = "";
        public String branchReachabilitySetup = "";
        public String branchReachabilityReason = "";
        public String abstractMethodsToImplement = "";
        public String allowedOverrides = "";
        public String forbiddenOverrides = "";
        public String testStubClassTemplate = "";
        public String testStubConstructorTemplate = "";
        public boolean skipTestGeneration = false;
        public String skipReason = "";

        public List<String> specObserved = List.of("return", "exception", "state");
        public List<ChangeRange> mutationRanges = List.of();
    }

    public static Map<String, Object> build(EntryContext ctx) throws Exception {
        boolean sameEntryAndMutation = sameMethod(ctx.entryClassName, ctx.entryMethodSig,
                ctx.mutationClassName, ctx.mutationMethodSig);

        EntrySideSnapshot origin = buildOrGetOriginEntry(ctx, sameEntryAndMutation);

        EntrySideSnapshot mutated;
        if (!sameEntryAndMutation) {
            mutated = origin.copyForMutantReuse(ctx.mutantId,
                    "The mutation is located in method A, while test entry B is only the caller; "
                            + "therefore the mutated-side entry graph reuses the origin-side Soot/Jimple graph.");
        } else {
            mutated = buildEntrySide(
                    ctx.mutantId,
                    ctx.mutantJavaFile,
                    ctx.mutantClassesDir,
                    ctx.entryClassName,
                    ctx.entryMethodSig,
                    ctx.mutationClassName,
                    ctx.mutationMethodSig,
                    ctx.specObserved,
                    ctx.mutationRanges,
                    true,
                    false,
                    "");
        }

        boolean needEntryLiftedEvidence = !sameEntryAndMutation && !ctx.useReflectionFallback;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("enabled", commented("Whether entry-lifted RIP evidence was generated successfully", true));
        item.put("sameEntryAndMutation", commented(
                "Whether callable test entry B is the same method as the real mutation method A",
                sameEntryAndMutation));
        item.put("needEntryLiftedEvidence", commented(
                "Whether callable test entry B is different from real mutation method A and B-side entry evidence should be provided to the LLM",
                needEntryLiftedEvidence));
        item.put("entryInvocationKind", commented(
                "How generated tests should invoke callable test entry B",
                entryInvocationKind(ctx)));
        item.put("entryGenerationPlan", commented(
                "Prompt-facing invocation plan and suggested test values for callable test entry B",
                buildEntryGenerationPlan(ctx, origin, mutated)));
        item.put("entryRelation", commented(
                "Relationship between real mutation method A and callable test entry method B",
                buildEntryRelation(ctx)));
        item.put("origin", commented("Entry-side Soot/Jimple evidence for original program",
                origin.toCommentedMap()));
        item.put("mutated", commented("Entry-side Soot/Jimple evidence for mutated program",
                mutated.toCommentedMap()));

        return commented(
                "Soot/Jimple evidence for the callable test entry B, used to show how generated tests can reach the real mutation method A",
                item);
    }

    private static EntrySideSnapshot buildOrGetOriginEntry(EntryContext ctx, boolean sameEntryAndMutation)
            throws Exception {
        // If B == A, focus units depend on current mutationRanges, so do not cache the
        // complete entry graph. If B != A, focus units are call sites B -> A and are
        // stable for the same original entry method.
        if (sameEntryAndMutation) {
            return buildEntrySide(
                    ctx.originId,
                    ctx.originJavaFile,
                    ctx.originClassesDir,
                    ctx.entryClassName,
                    ctx.entryMethodSig,
                    ctx.mutationClassName,
                    ctx.mutationMethodSig,
                    ctx.specObserved,
                    ctx.mutationRanges,
                    true,
                    false,
                    "");
        }

        String key = originEntryCacheKey(ctx);
        EntrySideSnapshot cached = ORIGIN_ENTRY_GRAPH_CACHE.get(key);
        if (cached != null) {
            return cached.copyWithId(ctx.originId);
        }

        EntrySideSnapshot built = buildEntrySide(
                ctx.originId,
                ctx.originJavaFile,
                ctx.originClassesDir,
                ctx.entryClassName,
                ctx.entryMethodSig,
                ctx.mutationClassName,
                ctx.mutationMethodSig,
                ctx.specObserved,
                ctx.mutationRanges,
                false,
                false,
                "");
        ORIGIN_ENTRY_GRAPH_CACHE.put(key, built.copyWithId(ctx.originId));
        return built;
    }

    private static EntrySideSnapshot buildEntrySide(
            String id,
            String javaFile,
            String classesDir,
            String entryClassName,
            String entryMethodSig,
            String mutationClassName,
            String mutationMethodSig,
            List<String> specObserved,
            List<ChangeRange> mutationRanges,
            boolean sameEntryAndMutation,
            boolean reusedFromOrigin,
            String reuseReason) throws Exception {

        EntrySideSnapshot s = new EntrySideSnapshot();
        s.id = safe(id);
        s.entryClassName = safe(entryClassName);
        s.entryMethodSig = safe(entryMethodSig);
        s.mutationClassName = safe(mutationClassName);
        s.mutationMethodSig = safe(mutationMethodSig);
        s.sameEntryAndMutation = sameEntryAndMutation;
        s.reusedFromOrigin = reusedFromOrigin;
        s.reuseReason = safe(reuseReason);
        s.focusMode = sameEntryAndMutation ? "MUTATION_AFFECTED_UNITS" : "ENTRY_CALL_SITES_TO_MUTATION";

        // Source extraction is useful for the prompt, but it must not decide whether
        // EntryLiftedRIP.origin/mutated exist. If JavaParser/MethodContent fails to
        // match a constructor or a display-style signature, continue with Soot/Jimple
        // and record the issue as a warning.
        try {
            s.content = cachedExtractMethod(javaFile, entryClassName, entryMethodSig, true);
        } catch (Throwable ex) {
            s.content = "";
            s.warnings.add("Entry source extraction failed but Soot/Jimple construction continues: "
                    + ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
        }

        AstToJimpleBridge.initSoot(classesDir);
        Body body = AstToJimpleBridge.getBody(entryClassName, entryMethodSig);
        s.ir = body.toString();

        List<Unit> focusUnits;
        if (sameEntryAndMutation) {
            focusUnits = AstToJimpleBridge.analyzeAffectedUnits(body,
                    mutationRanges == null ? List.of() : mutationRanges);
            if (focusUnits.isEmpty()) {
                s.warnings.add(
                        "No affected Jimple unit was found in entry method B, although B equals mutation method A.");
            }
        } else {
            focusUnits = findCallSitesToMutation(body, mutationClassName, mutationMethodSig);
            if (focusUnits.isEmpty()) {
                s.warnings.add("No direct Jimple invoke site from entry method B to mutation method A was found.");
                s.warnings.add(
                        "This may happen when A is reached through another intermediate method, dynamic dispatch, inheritance, or cross-file calls not visible in the resolved entry body.");
            }
        }

        s.focusUnits = formatUnits(focusUnits);
        s.callSitesToMutation = sameEntryAndMutation ? List.of() : formatUnits(focusUnits);

        List<List<Unit>> paths = enumeratePathsThroughFocus(body, focusUnits, sameEntryAndMutation);
        s.paths = paths.stream().map(AstToJimpleBridge::pathToString).collect(Collectors.toList());

        for (List<Unit> path : paths) {
            s.cpgItems.add(buildEntryInfoItem(path, body, focusUnits, specObserved));
        }

        return s;
    }

    private static Map<String, Object> buildEntryRelation(EntryContext ctx) {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("mutationClass", commented("Class containing the real mutation method A", ctx.mutationClassName));
        relation.put("mutationMethod", commented("Real mutation method A used for mutation-point RIP evidence",
                PromptSignatureFormatter.method(ctx.mutationMethodSig)));
        relation.put("testEntryClass", commented("Class containing callable test entry method B", ctx.entryClassName));
        relation.put("testEntryMethod", commented("Callable method B that generated tests should target",
                PromptSignatureFormatter.method(ctx.entryMethodSig)));
        relation.put("testEntryKind", commented("Entry type resolved by MethodEntryResolver", ctx.testEntryKind));
        relation.put("useReflectionFallback", commented("Whether generated tests should use reflection fallback",
                ctx.useReflectionFallback));
        relation.put("testGenerationPackage",
                commented("Package used by generated test class when same-package access is needed",
                        ctx.testGenerationPackage));
        relation.put("recommendedTestTarget", commented("Recommended strategy for generated tests",
                recommendedTestTarget(ctx.testEntryKind, ctx.useReflectionFallback)));
        relation.put("entryInvocationKind", commented("How generated tests should invoke callable test entry B",
                entryInvocationKind(ctx)));
        relation.put("receiver", commented("Receiver construction metadata for callable test entry B",
                buildReceiverMap(ctx)));
        relation.put("callChain", commentedList("Resolved call chain from test entry B to mutation method A",
                splitCallChain(ctx.testCallChain)));
        relation.put("notes", commentedList("Human-readable notes produced during entry resolution",
                splitNotes(ctx.testEntryNotes)));
        relation.put("skipTestGeneration",
                commented("Whether LLM test generation should be skipped for this target", ctx.skipTestGeneration));
        relation.put("skipReason", commented("Reason for skipping LLM test generation when skipTestGeneration is true",
                safe(ctx.skipReason)));
        return relation;
    }

    private static Map<String, Object> buildEntryGenerationPlan(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated) {
        Map<String, Object> out = new LinkedHashMap<>();
        String kind = entryInvocationKind(ctx);
        out.put("entryInvocationKind", commented("Invocation kind selected for callable test entry B", kind));
        out.put("recommendedTestTarget", commented("Recommended invocation target for generated tests",
                recommendedTestTarget(ctx.testEntryKind, ctx.useReflectionFallback)));
        out.put("receiver", commented("Receiver construction metadata for callable test entry B",
                buildReceiverMap(ctx)));
        out.put("invocationPlan", commented("Concrete invocation plan for callable test entry B",
                buildInvocationPlan(ctx, kind)));
        out.put("suggestedTestValues",
                commented("Suggested argument values and setup statements for LLM-generated JUnit tests",
                        buildSuggestedTestValues(ctx)));
        out.put("publicApi", commented(
                "Static-analysis extracted public API and setup methods. Generated tests must use exact names from this list and must not guess JavaBean-style getters/setters.",
                buildPublicApiPlan(ctx)));
        out.put("observablePlan", commented(
                "Static-analysis plan for observing the mutation effect through public behavior; if kind is NO_PUBLIC_OBSERVABLE, test generation should be skipped.",
                buildObservablePlan(ctx)));
        out.put("branchReachabilityPlan",
                commented("Static-analysis plan for making the mutation-sensitive branch/path reachable.",
                        buildBranchReachabilityPlan(ctx)));
        out.put("assertionPlan", commented(
                "Mutation-aware observation and assertion plan. This tells the LLM what to compare and what not to compare.",
                buildAssertionPlan(ctx, origin, mutated)));
        return out;
    }

    private static String entryInvocationKind(EntryContext ctx) {
        if (ctx.entryInvocationKind != null && !ctx.entryInvocationKind.isBlank()) {
            return ctx.entryInvocationKind;
        }
        if (ctx.useReflectionFallback) {
            return "REFLECTION_INVOCATION";
        }
        if (isConstructorSignature(ctx.entryMethodSig)) {
            return "CONSTRUCTOR_INVOCATION";
        }
        if ("STATIC_NO_RECEIVER".equals(ctx.receiverStrategy)) {
            return "STATIC_METHOD_INVOCATION";
        }
        return "INSTANCE_METHOD_INVOCATION";
    }

    private static boolean isConstructorSignature(String sig) {
        if (sig == null) {
            return false;
        }
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        return lp > 0 && !(us > 0 && us < lp);
    }

    private static Map<String, Object> buildReceiverMap(EntryContext ctx) {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("ownerKind",
                commented("Kind of the declaring type of callable entry B", safe(ctx.receiverOwnerKind)));
        receiver.put("ownerAbstract",
                commented("Whether the declaring type of callable entry B is abstract", ctx.receiverOwnerAbstract));
        receiver.put("ownerInterface", commented("Whether the declaring type of callable entry B is an interface",
                ctx.receiverOwnerInterface));
        receiver.put("ownerInstantiable", commented(
                "Whether generated tests can construct a receiver without reflection", ctx.receiverOwnerInstantiable));
        receiver.put("strategy", commented("Recommended receiver construction strategy", safe(ctx.receiverStrategy)));
        receiver.put("construction", commented("Human-readable receiver construction instruction for the LLM",
                safe(ctx.receiverConstruction)));
        receiver.put("notes", commented("Additional notes about receiver construction", safe(ctx.receiverNotes)));
        receiver.put("runtimeReceiverClass",
                commented("Concrete runtime class to instantiate when it differs from the declaring entry class",
                        safe(ctx.receiverRuntimeClassName)));
        receiver.put("runtimeReceiverSootClass",
                commented("Fully qualified runtime receiver class", safe(ctx.receiverRuntimeSootClassName)));
        receiver.put("declaringClass", commented("Declaring class that contains the mutation/entry method",
                safe(ctx.receiverDeclaringClassName)));
        receiver.put("dispatchTarget",
                commented("Method body expected to execute after dynamic dispatch", safe(ctx.receiverDispatchTarget)));
        receiver.put("dispatchesToMutationMethod",
                commented("Whether the selected receiver dispatches to the mutation method body",
                        ctx.receiverDispatchesToMutationMethod));
        receiver.put("subclassOverridesMutationMethod",
                commented("Whether the concrete subclass overrides the mutated method",
                        ctx.receiverSubclassOverridesMutationMethod));
        receiver.put("setupTemplate",
                commented("Static-analysis generated receiver setup code", safe(ctx.receiverSetupTemplate)));
        receiver.put("invocationTemplate",
                commented("Static-analysis generated invocation code", safe(ctx.receiverInvocationTemplate)));
        receiver.put("factoryMethod", commented("Static factory method selected by receiver resolver, when applicable",
                safe(ctx.receiverFactoryMethod)));
        receiver.put("builderClass", commented("Builder/helper type selected by receiver resolver, when applicable",
                safe(ctx.receiverBuilderClassName)));
        receiver.put("builderTerminalMethod",
                commented("Terminal method that creates the receiver instance, e.g. get/build",
                        safe(ctx.receiverBuilderTerminalMethod)));
        receiver.put("builderSetupChain", commented("Chained builder setup calls selected by static analysis",
                safe(ctx.receiverBuilderSetupChain)));
        receiver.put("antiPatterns", commentedItems("Receiver construction patterns that generated tests must avoid",
                splitItems(ctx.receiverAntiPatterns)));
        receiver.put("abstractMethodsToImplement",
                commentedItems("Abstract methods that a generated test stub must implement",
                        splitItems(ctx.abstractMethodsToImplement)));
        receiver.put("allowedOverrides", commentedItems("Methods that a generated test subclass may override",
                splitItems(ctx.allowedOverrides)));
        receiver.put("forbiddenOverrides", commentedItems("Methods that must not be overridden, e.g. final methods",
                splitItems(ctx.forbiddenOverrides)));
        receiver.put("testStubClassTemplate",
                commented("Static-analysis generated test stub class template", safe(ctx.testStubClassTemplate)));
        receiver.put("testStubConstructorTemplate", commented(
                "Static-analysis generated test stub constructor template", safe(ctx.testStubConstructorTemplate)));
        receiver.put("resolutionReason",
                commented("Why this receiver strategy was selected", safe(ctx.receiverResolutionReason)));
        return receiver;
    }

    private static Map<String, Object> buildInvocationPlan(EntryContext ctx, String kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        String cls = simpleClassName(ctx.entryClassName);
        String method = methodNameFromSootLikeSignature(ctx.entryMethodSig);
        String args = exampleArgumentsJoined(ctx.entryMethodSig);

        out.put("receiverRequired", commented("Whether generated test needs a receiver object before invoking B",
                "INSTANCE_METHOD_INVOCATION".equals(kind)));
        if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            out.put("setupTemplate", commented("Suggested setup code before assertion",
                    firstNonBlank(ctx.receiverSetupTemplate, cls + " subject = new " + cls + "(" + args + ");")));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    firstNonBlank(ctx.receiverInvocationTemplate, "new " + cls + "(" + args + ")")));
            out.put("notes", commented("Invocation notes",
                    "B is a constructor, so instantiate the object directly. Do not call the constructor as if it were a normal method."));
        } else if ("STATIC_METHOD_INVOCATION".equals(kind)) {
            out.put("setupTemplate", commented("Suggested setup code before invocation", ""));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    cls + "." + method + "(" + args + ");"));
            out.put("notes", commented("Invocation notes", "B is static; no receiver object is required."));
        } else if ("REFLECTION_INVOCATION".equals(kind)) {
            out.put("setupTemplate", commented("Suggested setup code before invocation", ""));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    "Use reflection because no directly callable entry B was resolved."));
            out.put("notes", commented("Invocation notes",
                    "Reflection fallback should be used only when no callable entry B exists."));
        } else {
            String setup = firstNonBlank(ctx.receiverSetupTemplate, receiverSetupTemplate(ctx, cls));
            out.put("setupTemplate", commented("Suggested setup code before invocation", setup));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    firstNonBlank(ctx.receiverInvocationTemplate, "subject." + method + "(" + args + ");")));
            out.put("notes", commented("Invocation notes",
                    "B is an instance method; create the receiver according to receiver.strategy before invoking it."));
        }
        return out;
    }

    private static Map<String, Object> buildSuggestedTestValues(EntryContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exampleArguments", commentedList("Example argument values matching B's parameters",
                buildExampleArguments(ctx.entryMethodSig)));
        out.put("setupStatements", commentedList("Useful setup statements inferred from receiver/parameter types",
                buildSetupStatements(ctx)));
        out.put("assertionHints",
                commentedList(
                        "Mutation-aware assertion hints inferred from entry relationship and common observable sinks",
                        buildAssertionHints(ctx)));
        return out;
    }

    private static Map<String, Object> buildPublicApiPlan(EntryContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availablePublicMethods",
                commentedItems("Public/package-visible methods found by static analysis; use exact names only",
                        splitItems(ctx.availablePublicMethods)));
        out.put("availableSetupMethods",
                commentedItems("Setter/setup methods found by static analysis; use exact names only",
                        splitItems(ctx.availableSetupMethods)));
        out.put("stateSetupPlan",
                commentedItems("Suggested state setup statements before invoking B or observable methods",
                        splitItems(ctx.stateSetupPlan)));
        out.put("antiPatterns",
                commentedItems("API patterns the generated test must avoid", splitItems(ctx.observableAntiPatterns)));
        return out;
    }

    private static Map<String, Object> buildBranchReachabilityPlan(EntryContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", commented("Branch reachability plan kind", safe(ctx.branchReachabilityKind)));
        out.put("condition", commented("CFG/path condition that must hold to reach the mutation-sensitive branch",
                safe(ctx.branchReachabilityCondition)));
        out.put("setup", commented("Static-analysis generated setup intended to satisfy the branch condition",
                safe(ctx.branchReachabilitySetup)));
        out.put("reason", commented("Why this setup reaches the target branch", safe(ctx.branchReachabilityReason)));
        return out;
    }

    private static Map<String, Object> buildObservablePlan(EntryContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", commented("Observable plan kind", safe(ctx.observablePlanKind)));
        out.put("setup",
                commented("Additional setup needed before observing the mutation effect", safe(ctx.observableSetup)));
        out.put("observableCall",
                commented("Public call that should expose the mutation effect", safe(ctx.observableCall)));
        out.put("expectedOriginal",
                commented("Expected original value when it can be inferred", safe(ctx.observableExpectedOriginal)));
        out.put("reason", commented("Why this observable was selected or why generation should be skipped",
                safe(ctx.observableReason)));
        out.put("antiPatterns",
                commentedItems("Observable/API patterns to avoid", splitItems(ctx.observableAntiPatterns)));
        return out;
    }

    private static Map<String, Object> buildAssertionPlan(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated) {
        Map<String, Object> plan = new LinkedHashMap<>();
        String kind = entryInvocationKind(ctx);
        plan.put("comparisonPolicy", commented("General comparison policy for generated tests",
                "Use at least one mutation-sensitive assertion to distinguish original and mutant. "
                        + "Auxiliary assertions are allowed and useful for checking object construction or public state, "
                        + "but they may not kill the current mutant alone. Do not assertEquals on arbitrary objects; "
                        + "choose a type-compatible observable sink such as return value, exception/message, public getter/state, "
                        + "external mutable collaborator, or thrown exception."));
        plan.put("mutationSensitiveObservables", commentedList(
                "Observable targets likely affected by the current mutation and therefore useful for killing this mutant",
                buildMutationSensitiveObservables(ctx, origin, mutated, kind)));
        plan.put("auxiliaryObservables", commentedList(
                "Observable targets that are compilable and useful as sanity checks, but may not kill this mutant alone",
                buildAuxiliaryObservables(ctx, origin, mutated, kind)));
        plan.put("recommendedAssertions", commented(
                "Recommended assertions grouped by purpose. Generated tests should include at least one requiredToKill assertion when possible, and may also include optionalSanityChecks.",
                buildRecommendedAssertionGroups(ctx, origin, mutated, kind)));
        plan.put("antiPatterns", commentedList(
                "Assertion patterns that are likely to compile incorrectly or fail to observe the mutation",
                buildAssertionAntiPatterns(ctx, origin, mutated, kind)));
        plan.put("notes", commentedList(
                "Additional assertion-generation notes",
                buildAssertionNotes(ctx, origin, mutated, kind)));
        return plan;
    }

    private static List<Map<String, Object>> buildMutationSensitiveObservables(EntryContext ctx,
            EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (isExceptionLike(ctx) && isMessageSensitive(ctx, origin, mutated)) {
            out.add(observableTarget("exception_message", "subject.getMessage()",
                    "The mutation affects message construction, and the callable entry passes the constructed message to an exception/superclass constructor.",
                    "high"));
        }
        if (isAppendableSideEffect(ctx, origin, mutated)) {
            out.add(observableTarget("external_mutable_state", "output.toString()",
                    "The mutation removes or changes an Appendable/StringBuilder write; the external collaborator content is the observable sink.",
                    "high"));
        }
        String ret = returnTypeFromSignature(ctx.entryMethodSig);
        if (!ret.isBlank() && !"void".equals(ret) && !"<constructor>".equals(ret)
                && !isReturnOnlyLikelyInsufficient(ctx, origin, mutated)) {
            out.add(observableTarget("return_value", "result",
                    "The callable entry returns " + PromptSignatureFormatter.simplifyType(ret)
                            + ". Use this only when the mutation changes the returned value.",
                    "medium"));
        }
        if (out.isEmpty()) {
            out.add(observableTarget("fallback_public_behavior", "public return/getter/state/exception output",
                    "No strong mutation-sensitive observable was inferred automatically. Generated tests should still include a public behavior assertion that can differ between original and mutant.",
                    "medium"));
        }
        return out;
    }

    private static List<Map<String, Object>> buildAuxiliaryObservables(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        String all = evidenceText(ctx, origin, mutated);
        if (all.contains("getMatchingOptions") || all.contains("matchingOptions")) {
            out.add(observableTargetWithExpected("public_getter_state", "subject.getMatchingOptions()",
                    "matchingOptions",
                    "getMatchingOptions() returns the stored matchingOptions field. This is valid and compilable, but the current mutation may affect message construction rather than this field assignment.",
                    "high"));
        }
        String ret = returnTypeFromSignature(ctx.entryMethodSig);
        if (!ret.isBlank() && !"void".equals(ret) && !"<constructor>".equals(ret)
                && isReturnOnlyLikelyInsufficient(ctx, origin, mutated)) {
            out.add(observableTarget("return_identity", "result",
                    "The entry returns the receiver. This can check fluent API behavior, but may not expose mutations in external state.",
                    "high"));
        }
        if (out.isEmpty()) {
            out.add(observableTarget("object_construction_sanity", "subject",
                    "The constructed or invoked object can be checked for non-null sanity, but this is rarely sufficient to kill a mutant.",
                    "medium"));
        }
        return out;
    }

    private static Map<String, Object> buildRecommendedAssertionGroups(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredToKill", commentedList(
                "Assertions likely needed to distinguish original and mutant. Include at least one of these when possible.",
                buildRequiredToKillAssertions(ctx, origin, mutated, kind)));
        out.put("optionalSanityChecks", commentedList(
                "Assertions that should compile and validate normal object state or setup, but may not kill this mutant alone.",
                buildOptionalSanityAssertions(ctx, origin, mutated, kind)));
        return out;
    }

    private static List<Map<String, Object>> buildRequiredToKillAssertions(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (isExceptionLike(ctx) && isMessageSensitive(ctx, origin, mutated)) {
            String fragment = mutationSensitiveMessageFragment(ctx);
            if (!fragment.isBlank()) {
                out.add(assertionTemplate("high", "exception_message",
                        "assertTrue(\"message should contain mutation-sensitive text\", subject.getMessage().contains(\""
                                + escapeJava(fragment) + "\"));",
                        "The mutation changes message construction; assert the public exception message fragment instead of only checking unrelated getters."));
            }
            out.add(assertionTemplate("medium", "exception_message",
                    "assertNotNull(subject.getMessage());",
                    "Fallback message assertion. Prefer a stronger contains/equals assertion when a mutation-sensitive fragment is known."));
        }
        if (isAppendableSideEffect(ctx, origin, mutated)) {
            out.add(assertionTemplate("high", "external_mutable_state", "assertEquals(\"x\", output.toString());",
                    "The mutation removes or changes an Appendable write; the StringBuilder/Appendable content is the mutation-sensitive observable sink."));
        }
        String ret = returnTypeFromSignature(ctx.entryMethodSig);
        if (!ret.isBlank() && !"void".equals(ret) && !"<constructor>".equals(ret)
                && !isReturnOnlyLikelyInsufficient(ctx, origin, mutated)) {
            out.add(assertionTemplate("medium", "return_value", "assertEquals(expected, result);",
                    "Use when the mutation changes the returned value. Replace expected with the source-level expected value."));
        }
        if (out.isEmpty()) {
            out.add(assertionTemplate("medium", "fallback_public_behavior",
                    "// Assert a public behavior that is affected by the mutation, not only object construction.",
                    "No specific mutation-sensitive assertion was inferred automatically."));
        }
        return out;
    }

    private static List<Map<String, Object>> buildOptionalSanityAssertions(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        String all = evidenceText(ctx, origin, mutated);
        if (all.contains("getMatchingOptions") || all.contains("matchingOptions")) {
            out.add(assertionTemplate("high", "public_getter_state",
                    "assertEquals(matchingOptions, subject.getMatchingOptions());",
                    "This is type-compatible and validates stored constructor state, but it may not expose message-construction mutations."));
        }
        if (isAppendableSideEffect(ctx, origin, mutated)
                && isReturnOnlyLikelyInsufficient(ctx, origin, mutated)) {
            out.add(assertionTemplate("medium", "return_identity", "assertSame(subject, result);",
                    "This checks fluent return identity, but it is usually insufficient alone because both original and mutant may still return this."));
        }
        if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            out.add(assertionTemplate("low", "object_construction_sanity", "assertNotNull(subject);",
                    "Constructor invocation should produce an object, but non-null alone is not mutation-sensitive."));
        }
        return out;
    }

    private static List<String> buildAssertionAntiPatterns(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("Do not use only auxiliary assertions when the goal is to kill a mutant; include at least one mutation-sensitive assertion when possible.");
        if (isExceptionLike(ctx) && isMessageSensitive(ctx, origin, mutated)) {
            out.add("Do not compare a Collection returned by getMatchingOptions() with an expected message String; they are different observable types.");
            out.add("Do not rely only on getMatchingOptions() for message-construction mutations; it observes stored state, not the constructed exception message.");
            out.add("Do not hard-code the full exception message unless exact punctuation and spacing are known; prefer contains(...) on mutation-sensitive fragments when appropriate.");
            out.add("Do not call a constructor as if it were a normal method. For constructor entry B, instantiate the object and assert its public observable state/message.");
        }
        if (isAppendableSideEffect(ctx, origin, mutated)) {
            out.add("Do not assert only the returned receiver identity. The mutant may still return this; assert the external StringBuilder/Appendable content as well.");
            out.add("Do not use assertEquals on the receiver object unless equals() is known to encode the mutated behavior.");
        }
        if ("void".equals(returnTypeFromSignature(ctx.entryMethodSig))) {
            out.add("Do not use assertEquals on a void result; assert side effects, thrown exceptions, or public state changes instead.");
        }
        return new ArrayList<>(out);
    }

    private static List<String> buildAssertionNotes(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated, String kind) {
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        notes.add(
                "A generated test should compile in the package listed by EntryLiftedRIP.entryRelation.testGenerationPackage and DependencyContext.testEntryContext.testPackage.");
        notes.add("A valid auxiliary assertion can be kept, but it should not replace a mutation-sensitive assertion.");
        if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            notes.add(
                    "Because B is a constructor, use the constructed variable, e.g., subject, as the observation object after new B(...). ");
        }
        if (isExceptionLike(ctx)) {
            notes.add(
                    "Exception classes inherit getMessage() from Throwable; use subject.getMessage() when the mutation changes message construction.");
        }
        if (isAppendableSideEffect(ctx, origin, mutated)) {
            notes.add(
                    "For Appendable mutations, create a StringBuilder before invocation and assert output.toString() after invocation.");
        }
        return new ArrayList<>(notes);
    }

    private static Map<String, Object> observableTarget(String kind, String expression, String reason,
            String confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", commented("Observable target kind", kind));
        m.put("expression", commented("Java expression to observe in the generated test", expression));
        m.put("reason", commented("Why this target may expose or validate behavior", reason));
        m.put("confidence", commented("Confidence of this observable target", confidence));
        return m;
    }

    private static Map<String, Object> observableTargetWithExpected(String kind, String expression,
            String expectedExpression, String reason, String confidence) {
        Map<String, Object> m = observableTarget(kind, expression, reason, confidence);
        m.put("expectedExpression",
                commented("Expected value expression for type-compatible comparison", expectedExpression));
        return m;
    }

    private static Map<String, Object> assertionTemplate(String priority, String observable, String template,
            String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priority", commented("Priority of this assertion template", priority));
        m.put("observable", commented("Observable sink targeted by this assertion", observable));
        m.put("template", commented("JUnit assertion template", template));
        m.put("reason", commented("Why this assertion is recommended", reason));
        return m;
    }

    private static boolean isExceptionLike(EntryContext ctx) {
        String c = simpleClassName(ctx.entryClassName);
        String m = simpleClassName(ctx.mutationClassName);
        return c.endsWith("Exception") || m.endsWith("Exception") || safe(ctx.entryClassName).contains("Exception")
                || safe(ctx.mutationClassName).contains("Exception");
    }

    private static boolean isMessageSensitive(EntryContext ctx, EntrySideSnapshot origin, EntrySideSnapshot mutated) {
        String text = evidenceText(ctx, origin, mutated).toLowerCase(Locale.ROOT);
        return text.contains("createmessage") || text.contains("getmessage") || text.contains("message")
                || text.contains("could be") || text.contains("ambiguous option");
    }

    private static boolean isAppendableSideEffect(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated) {
        String text = evidenceText(ctx, origin, mutated);
        return text.contains("Appendable") || text.contains("output.append") || text.contains("java.lang.Appendable")
                || text.contains("StringBuilder") || text.contains(" append(char)");
    }

    private static boolean isReturnOnlyLikelyInsufficient(EntryContext ctx, EntrySideSnapshot origin,
            EntrySideSnapshot mutated) {
        String ret = PromptSignatureFormatter.simplifyType(returnTypeFromSignature(ctx.entryMethodSig));
        String cls = simpleClassName(ctx.entryClassName);
        return !ret.isBlank() && ret.equals(cls) && isAppendableSideEffect(ctx, origin, mutated);
    }

    private static String mutationSensitiveMessageFragment(EntryContext ctx) {
        String d = safe(ctx.diff);
        if (d.contains("could be"))
            return "could be";
        if (d.contains("Ambiguous option"))
            return "Ambiguous option";
        return "";
    }

    private static String evidenceText(EntryContext ctx, EntrySideSnapshot origin, EntrySideSnapshot mutated) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(ctx.diff)).append('\n');
        sb.append(safe(ctx.entryClassName)).append('\n').append(safe(ctx.entryMethodSig)).append('\n');
        sb.append(safe(ctx.mutationClassName)).append('\n').append(safe(ctx.mutationMethodSig)).append('\n');
        sb.append(safe(ctx.receiverConstruction)).append('\n').append(safe(ctx.receiverNotes)).append('\n');
        if (origin != null) {
            sb.append(safe(origin.content)).append('\n').append(safe(origin.ir)).append('\n');
            sb.append(origin.focusUnits).append('\n').append(origin.callSitesToMutation).append('\n');
        }
        if (mutated != null) {
            sb.append(safe(mutated.content)).append('\n').append(safe(mutated.ir)).append('\n');
            sb.append(mutated.focusUnits).append('\n').append(mutated.callSitesToMutation).append('\n');
        }
        return sb.toString();
    }

    private static String returnTypeFromSignature(String sig) {
        if (sig == null)
            return "";
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        if (lp < 0)
            return "";
        if (us > 0 && us < lp)
            return sig.substring(0, us).trim();
        return "<constructor>";
    }

    private static String escapeJava(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<Map<String, Object>> buildExampleArguments(String methodSig) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> params = parameterTypes(methodSig);
        for (int i = 0; i < params.size(); i++) {
            Map<String, Object> arg = new LinkedHashMap<>();
            String type = params.get(i);
            arg.put("index", commented("Parameter index", i));
            arg.put("type", commented("Parameter type", PromptSignatureFormatter.simplifyType(type)));
            arg.put("exampleValue",
                    commented("Suggested Java expression for this parameter", exampleValueForType(type)));
            out.add(arg);
        }
        return out;
    }

    private static List<String> buildSetupStatements(EntryContext ctx) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String kind = entryInvocationKind(ctx);
        String cls = simpleClassName(ctx.entryClassName);
        String args = exampleArgumentsJoined(ctx.entryMethodSig);
        if ("INSTANCE_METHOD_INVOCATION".equals(kind)) {
            out.add(firstNonBlank(ctx.receiverSetupTemplate, receiverSetupTemplate(ctx, cls)));
        } else if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            out.add(cls + " subject = new " + cls + "(" + args + ");");
        }
        for (String s : splitItems(ctx.stateSetupPlan)) {
            if (s != null && !s.isBlank())
                out.add(s);
        }
        for (String p : parameterTypes(ctx.entryMethodSig)) {
            String t = PromptSignatureFormatter.simplifyType(p);
            if ("Collection".equals(t) || "List".equals(t) || "Iterable".equals(t)) {
                out.add("java.util.Collection<String> values = java.util.Arrays.asList(\"alpha\", \"beta\");");
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> buildAssertionHints(EntryContext ctx) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        String kind = entryInvocationKind(ctx);
        if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            hints.add(
                    "Because B is a constructor, assert observable effects on the constructed object, such as getMessage(), public getters, or state exposed by methods.");
        }
        if (sameMethod(ctx.entryClassName, ctx.entryMethodSig, ctx.mutationClassName, ctx.mutationMethodSig)) {
            hints.add("A and B are the same method; assertions should focus on A-side origin/mutated RIP differences.");
        } else {
            hints.add(
                    "A and B are different; invoke B and assert observable behavior caused by the private/internal mutation method A.");
        }
        if (ctx.specObserved != null && ctx.specObserved.contains("state")) {
            hints.add(
                    "If the mutation affects an object/field/Appendable state, assert the visible state after invoking B.");
        }
        if (ctx.specObserved != null && ctx.specObserved.contains("return")) {
            hints.add(
                    "If the mutation reaches a return value, assert the returned value or derived public getter/output.");
        }
        if (ctx.specObserved != null && ctx.specObserved.contains("exception")) {
            hints.add(
                    "If the mutation reaches exception construction or message text, assert getMessage() or thrown exception properties.");
        }
        return new ArrayList<>(hints);
    }

    private static String receiverSetupTemplate(EntryContext ctx, String cls) {
        if ("ANONYMOUS_SUBCLASS".equals(ctx.receiverStrategy) || ctx.receiverOwnerAbstract) {
            String fromInstruction = extractNewExpression(ctx.receiverConstruction);
            if (!fromInstruction.isBlank()) {
                return cls + " subject = " + fromInstruction + ";";
            }
            return cls + " subject = new " + cls + "() { };";
        }
        if (ctx.receiverOwnerInterface) {
            return cls + " subject = /* provide minimal implementation or existing implementation */ null;";
        }
        String fromInstruction = extractNewExpression(ctx.receiverConstruction);
        if (!fromInstruction.isBlank()) {
            return cls + " subject = " + fromInstruction + ";";
        }
        return cls + " subject = new " + cls + "();";
    }

    private static String extractNewExpression(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return "";
        }
        int idx = instruction.indexOf("new ");
        if (idx < 0) {
            return "";
        }
        String tail = instruction.substring(idx).trim();
        int end = tail.indexOf(" then ");
        if (end > 0) {
            tail = tail.substring(0, end).trim();
        }
        if (tail.endsWith(".")) {
            tail = tail.substring(0, tail.length() - 1);
        }
        return tail;
    }

    private static String exampleArgumentsJoined(String methodSig) {
        return parameterTypes(methodSig).stream()
                .map(EntryLiftedRipBuilder::exampleValueForType)
                .collect(Collectors.joining(", "));
    }

    private static List<String> parameterTypes(String methodSig) {
        if (methodSig == null) {
            return List.of();
        }
        int lp = methodSig.indexOf('(');
        int rp = methodSig.lastIndexOf(')');
        if (lp < 0 || rp < lp) {
            return List.of();
        }
        String inside = methodSig.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(inside.split(","))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .collect(Collectors.toList());
    }

    private static String exampleValueForType(String rawType) {
        String t = PromptSignatureFormatter.simplifyType(rawType);
        switch (t) {
            case "boolean":
                return "false";
            case "byte":
                return "(byte) 0";
            case "short":
                return "(short) 0";
            case "int":
                return "0";
            case "long":
                return "0L";
            case "float":
                return "0.0f";
            case "double":
                return "0.0";
            case "char":
                return "'x'";
            case "String":
            case "CharSequence":
                return "\"x\"";
            case "Appendable":
                return "new StringBuilder()";
            case "Collection":
            case "List":
            case "Iterable":
                return "java.util.Arrays.asList(\"alpha\", \"beta\")";
            default:
                if (t.endsWith("[]"))
                    return "new " + t.substring(0, t.length() - 2) + "[0]";
                return "null";
        }
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        String s = className.replace('$', '.');
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static Map<String, Object> buildEntryInfoItem(
            List<Unit> path,
            Body body,
            List<Unit> focusUnits,
            List<String> specObserved) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Path", commented("Control-flow path in entry method B that crosses the focus unit",
                AstToJimpleBridge.pathToString(path)));
        item.put("CFG", commented("Control-flow evidence around entry focus units", buildCfgMap(body, focusUnits)));
        item.put("DFG",
                commented("Data-flow evidence around entry focus units", buildDfgMap(body, focusUnits, specObserved)));
        return item;
    }

    private static Map<String, Object> buildCfgMap(Body body, List<Unit> focusUnits) {
        UnitGraph ug = new ExceptionalUnitGraph(body);
        BlockGraph bg = new BriefBlockGraph(body);
        DominatorsFinder<Unit> dom = new MHGDominatorsFinder<>(ug);
        DominatorsFinder<Unit> pdom = new MHGPostDominatorsFinder<>(ug);

        LinkedHashSet<String> domSummaries = new LinkedHashSet<>();
        LinkedHashSet<String> pathPreds = new LinkedHashSet<>();
        LinkedHashSet<String> ctrlDeps = new LinkedHashSet<>();

        for (Unit focus : focusUnits) {
            Unit resolved = locateByText(body, focus.toString());
            if (resolved == null) {
                continue;
            }
            domSummaries.addAll(unitsToBlockSummaries(bg, dom.getDominators(resolved)));
            for (Unit u : dom.getDominators(resolved)) {
                if (u instanceof IfStmt) {
                    pathPreds.add(((IfStmt) u).getCondition().toString());
                }
            }
            ctrlDeps.addAll(computeControlDepsText(bg, pdom, resolved));
        }

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("dom", commentedList(
                "Summary of dominator chain reaching the entry focus unit, used for reachability analysis",
                new ArrayList<>(domSummaries)));
        cfg.put("path_predicates", commentedList(
                "Conditional predicates that must be satisfied to reach the entry focus unit along this path",
                pathPreds.stream().limit(8).collect(Collectors.toList())));
        cfg.put("control_deps_out", commentedList(
                "If-conditions that control the reachability of the entry focus unit, computed from post-dominator evidence",
                new ArrayList<>(ctrlDeps)));
        return cfg;
    }

    private static Map<String, Object> buildDfgMap(Body body, List<Unit> focusUnits, List<String> specObserved) {
        UnitGraph ug = new ExceptionalUnitGraph(body);
        DominatorsFinder<Unit> pdom = new MHGPostDominatorsFinder<>(ug);
        SimpleLocalDefs sdefs = new SimpleLocalDefs(ug);
        SimpleLocalUses suses = new SimpleLocalUses(ug, sdefs);

        List<Map<String, Object>> defs = new ArrayList<>();
        List<Map<String, Object>> uses = new ArrayList<>();
        List<Map<String, Object>> kills = new ArrayList<>();
        List<Map<String, Object>> heapAccess = new ArrayList<>();
        List<String> mayThrow = new ArrayList<>();
        List<List<String>> aliasGroups = new ArrayList<>();

        LinkedHashSet<String> tracked = new LinkedHashSet<>();

        for (Unit focus : focusUnits) {
            Unit u = locateByText(body, focus.toString());
            if (u == null) {
                continue;
            }

            if (u instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) u;
                Value lhs = as.getLeftOp();
                String var = lhs.toString();
                tracked.add(var);
                defs.add(kv("var", var, "unit", u.toString()));
            }

            if (containsInvoke(u)) {
                mayThrow.add("invoke may throw @ " + unitLineText(u) + " : " + u.toString());
            }

            // If the focus itself does not define a local, track locals used by it as
            // control/data carriers, e.g., an if condition or a void invoke.
            if (!(u instanceof AssignStmt)) {
                for (ValueBox vb : u.getUseBoxes()) {
                    Value v = vb.getValue();
                    if (v instanceof Local) {
                        tracked.add(v.toString());
                    }
                }
            }
        }

        // Forward uses toward observable sinks.
        for (Unit focus : focusUnits) {
            Unit start = locateByText(body, focus.toString());
            if (start == null) {
                continue;
            }
            collectUsesTowardOutput(start, ug, suses, tracked, specObserved, uses);
        }

        // Kill set: later redefinitions of tracked variables before/around sinks.
        LinkedHashSet<String> killSig = new LinkedHashSet<>();
        for (Unit u : ug) {
            if (!(u instanceof AssignStmt)) {
                continue;
            }
            Value lhs = ((AssignStmt) u).getLeftOp();
            if (!(lhs instanceof Local)) {
                continue;
            }
            String var = lhs.toString();
            if (!tracked.contains(var)) {
                continue;
            }
            if (containsSameUnit(focusUnits, u)) {
                continue;
            }
            boolean pdByAnySink = postdominatedByAnySink(u, ug, pdom, specObserved);
            String sig = var + "|" + u + "|" + pdByAnySink;
            if (killSig.add(sig)) {
                kills.add(kv("var", var, "unit", u.toString(), "postdominated_by_sink", pdByAnySink));
            }
        }

        Map<String, Object> dfg = new LinkedHashMap<>();
        dfg.put("defs_point", commentedList(
                "Set of variable definitions related to the entry focus unit, including call-return variables when B calls A",
                dedupMapList(defs)));
        dfg.put("uses_toward_output", commentedList(
                "Explicit evidence chain showing whether data/control impact at the entry focus unit propagates to observable sinks such as return/exception/state",
                dedupMapList(uses)));
        dfg.put("kill_set", commentedList(
                "Set of overwriting redefinitions of tracked variables before reaching observable sinks",
                dedupMapList(kills)));
        dfg.put("heap_access", commentedList(
                "Summary of heap/array/field accesses near the entry focus unit; reserved for externally visible state propagation evidence",
                dedupMapList(heapAccess)));
        dfg.put("may_throw", commentedList(
                "Potential exception-producing invoke or throw sites near the entry focus unit",
                mayThrow.stream().distinct().collect(Collectors.toList())));
        dfg.put("alias_groups", commentedList(
                "Alias sets used to determine indirect impact and overwrite relations; currently conservative and left empty unless alias evidence is available",
                aliasGroups));
        return dfg;
    }

    private static void collectUsesTowardOutput(
            Unit start,
            UnitGraph ug,
            SimpleLocalUses suses,
            Set<String> tracked,
            List<String> specObserved,
            List<Map<String, Object>> out) {
        Set<Unit> visited = new HashSet<>();
        Deque<Unit> work = new ArrayDeque<>();
        work.add(start);

        while (!work.isEmpty()) {
            Unit u = work.poll();
            if (!visited.add(u)) {
                continue;
            }

            if (u instanceof IfStmt && specObserved.contains("return")) {
                if (guardsReturn(u, ug, specObserved)) {
                    for (ValueBox vb : u.getUseBoxes()) {
                        Value v = vb.getValue();
                        if (v instanceof Local && tracked.contains(v.toString())) {
                            out.add(kv("var", v.toString(), "unit", u.toString(), "sink", "return"));
                        }
                    }
                }
            }

            if (isSink(u, specObserved)) {
                for (ValueBox vb : u.getUseBoxes()) {
                    Value v = vb.getValue();
                    if (v instanceof Local && tracked.contains(v.toString())) {
                        out.add(kv("var", v.toString(), "unit", u.toString(), "sink", sinkKind(u)));
                    }
                }
                continue;
            }

            for (UnitValueBoxPair p : suses.getUsesOf(u)) {
                work.add(p.unit);
            }
        }
    }

    private static List<List<Unit>> enumeratePathsThroughFocus(Body body, List<Unit> focusUnits,
            boolean requireAllFocusUnits) {
        if (focusUnits == null || focusUnits.isEmpty()) {
            return List.of();
        }
        ExceptionalUnitGraph cfg = new ExceptionalUnitGraph(body);
        List<List<Unit>> all = AstToJimpleBridge.enumerateAllHeadToTailPathsK(
                cfg, cfg.getHeads(), cfg.getTails(), ENTRY_DEPTH_LIMIT, ENTRY_PATH_LIMIT, ENTRY_K_BOUNDED);

        List<List<Unit>> selected = new ArrayList<>();
        for (List<Unit> path : all) {
            if (requireAllFocusUnits) {
                if (containsAllUnits(path, focusUnits)) {
                    selected.add(path);
                }
            } else {
                if (containsAnyUnit(path, focusUnits)) {
                    selected.add(path);
                }
            }
        }
        return selected;
    }

    private static List<Unit> findCallSitesToMutation(Body body, String mutationClassName, String mutationMethodSig) {
        List<Unit> sites = new ArrayList<>();
        String mutationSimpleName = methodNameFromSootLikeSignature(mutationMethodSig);

        for (Unit u : body.getUnits()) {
            if (!(u instanceof Stmt)) {
                continue;
            }
            Stmt st = (Stmt) u;
            if (!st.containsInvokeExpr()) {
                continue;
            }
            InvokeExpr ie = st.getInvokeExpr();
            SootMethod target = ie.getMethod();

            boolean methodMatches = false;
            try {
                methodMatches = MethodSignature.matchesSootLikeSignature(target, mutationMethodSig);
            } catch (Throwable ignored) {
                methodMatches = Objects.equals(target.getName(), mutationSimpleName);
            }

            boolean classMatches = classMatches(target.getDeclaringClass().getName(), mutationClassName);
            if (methodMatches && classMatches) {
                sites.add(u);
            }
        }
        return sites;
    }

    private static boolean containsInvoke(Unit u) {
        return u instanceof Stmt && ((Stmt) u).containsInvokeExpr();
    }

    private static Unit locateByText(Body body, String unitText) {
        if (unitText == null || unitText.isEmpty()) {
            return null;
        }
        for (Unit u : body.getUnits()) {
            if (u.toString().equals(unitText)) {
                return u;
            }
        }
        for (Unit u : body.getUnits()) {
            if (u.toString().contains(unitText) || unitText.contains(u.toString())) {
                return u;
            }
        }
        return null;
    }

    private static boolean postdominatedByAnySink(Unit u, UnitGraph ug,
            DominatorsFinder<Unit> pdom,
            List<String> specObserved) {
        for (Unit cand : ug) {
            if (isSink(cand, specObserved) && pdom.getDominators(u).contains(cand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSink(Unit u, List<String> specObserved) {
        if (specObserved == null) {
            return false;
        }
        if (specObserved.contains("return") && u instanceof ReturnStmt) {
            return true;
        }
        if (specObserved.contains("exception") && u instanceof ThrowStmt) {
            return true;
        }
        return false;
    }

    private static String sinkKind(Unit u) {
        if (u instanceof ReturnStmt) {
            return "return";
        }
        if (u instanceof ThrowStmt) {
            return "exception";
        }
        return "state";
    }

    private static boolean guardsReturn(Unit ifUnit, UnitGraph ug, List<String> specObserved) {
        if (!(ifUnit instanceof IfStmt)) {
            return false;
        }
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (isSink(s, specObserved)) {
                return true;
            }
        }
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (reachesSinkWithin(s, ug, specObserved, 12)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reachesSinkWithin(Unit start, UnitGraph ug, List<String> specObserved, int maxSteps) {
        Set<Unit> seen = new HashSet<>();
        Deque<Unit> q = new ArrayDeque<>();
        q.add(start);
        int steps = 0;
        while (!q.isEmpty() && steps++ < maxSteps) {
            Unit cur = q.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (isSink(cur, specObserved)) {
                return true;
            }
            q.addAll(ug.getSuccsOf(cur));
        }
        return false;
    }

    private static boolean containsAllUnits(List<Unit> path, List<Unit> targets) {
        for (Unit t : targets) {
            if (!containsSameUnit(path, t)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAnyUnit(List<Unit> path, List<Unit> targets) {
        for (Unit t : targets) {
            if (containsSameUnit(path, t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSameUnit(Collection<Unit> units, Unit target) {
        for (Unit u : units) {
            if (u == target || u.toString().equals(target.toString())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> formatUnits(List<Unit> units) {
        if (units == null) {
            return List.of();
        }
        return units.stream().map(u -> {
            int[] lr = AstToJimpleBridge.unitLineRange(u);
            return String.format("JIMPLE [%d-%d] %s", lr[0], lr[1], u);
        }).collect(Collectors.toList());
    }

    private static String unitLineText(Unit u) {
        int[] lr = AstToJimpleBridge.unitLineRange(u);
        return lr[0] + "-" + lr[1];
    }

    private static String originEntryCacheKey(EntryContext ctx) {
        return safe(ctx.originJavaFile) + "##"
                + safe(ctx.originClassesDir) + "##"
                + safe(ctx.entryClassName) + "##"
                + safe(ctx.entryMethodSig) + "##"
                + safe(ctx.mutationClassName) + "##"
                + safe(ctx.mutationMethodSig);
    }

    private static String methodTextKey(String javaFile, String className, String methodSig, boolean includeSignature) {
        return safe(javaFile) + "##" + safe(className) + "##" + safe(methodSig) + "##" + includeSignature;
    }

    private static String cachedExtractMethod(String javaFile, String className, String methodSig,
            boolean includeSignature) throws Exception {
        String key = methodTextKey(javaFile, className, methodSig, includeSignature);
        String hit = METHOD_TEXT_CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        String value = extractMethodAsOneLine(javaFile, className, methodSig, includeSignature);
        METHOD_TEXT_CACHE.put(key, value);
        return value;
    }

    private static boolean sameMethod(String classA, String sigA, String classB, String sigB) {
        return classMatches(classA, classB)
                && normalizeSootLikeSignature(sigA).equals(normalizeSootLikeSignature(sigB));
    }

    private static boolean classMatches(String actual, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (actual == null || actual.isBlank()) {
            return false;
        }
        String a = actual.replace('$', '.');
        String e = expected.replace('$', '.');
        if (a.equals(e)) {
            return true;
        }
        if (a.endsWith("." + e) || e.endsWith("." + a)) {
            return true;
        }
        String as = a.substring(a.lastIndexOf('.') + 1);
        String es = e.substring(e.lastIndexOf('.') + 1);
        return as.equals(es);
    }

    private static String normalizeSootLikeSignature(String sig) {
        if (sig == null) {
            return "";
        }
        String s = sig.trim().replace(" ", "").replace('$', '.');
        int lp = s.indexOf('(');
        int rp = s.lastIndexOf(')');
        if (lp < 0 || rp < lp) {
            return s;
        }
        int us = s.indexOf('_');
        String namePart = us > 0 && us < lp ? simpleType(s.substring(0, us)) + "_" + s.substring(us + 1, lp)
                : s.substring(0, lp);
        String inside = s.substring(lp + 1, rp);
        if (inside.isBlank()) {
            return namePart + "()";
        }
        String params = java.util.Arrays.stream(inside.split(","))
                .map(EntryLiftedRipBuilder::simpleType)
                .collect(Collectors.joining(","));
        return namePart + "(" + params + ")";
    }

    private static String simpleType(String t) {
        if (t == null) {
            return "";
        }
        String s = t.trim().replace('$', '.');
        int dims = 0;
        while (s.endsWith("[]")) {
            dims++;
            s = s.substring(0, s.length() - 2);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < dims; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private static String methodNameFromSootLikeSignature(String sig) {
        if (sig == null) {
            return "";
        }
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        if (lp < 0) {
            return sig;
        }
        if (us > 0 && us < lp) {
            return sig.substring(us + 1, lp).trim();
        }
        return sig.substring(0, lp).trim();
    }

    private static List<String> splitCallChain(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(s.split("\\s*->\\s*"))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .map(PromptSignatureFormatter::callChain)
                .collect(Collectors.toList());
    }

    private static List<String> splitNotes(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(s.split("\\s*\\|\\s*"))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .collect(Collectors.toList());
    }

    private static String recommendedTestTarget(String kind, boolean reflection) {
        if (reflection) {
            return "reflect_mutation_method";
        }
        if (kind == null) {
            return "call_test_entry_method";
        }
        switch (kind) {
            case "PUBLIC_DIRECT_ENTRY":
                return "call_public_method";
            case "PACKAGE_PRIVATE_DIRECT_ENTRY":
                return "call_package_private_method";
            case "PROTECTED_SAME_PACKAGE_DIRECT_ENTRY":
                return "call_protected_same_package_method";
            case "ASCENDED_PUBLIC_CALLER":
            case "ASCENDED_PACKAGE_PRIVATE_CALLER":
            case "ASCENDED_PROTECTED_SAME_PACKAGE_CALLER":
                return "call_test_entry_method";
            default:
                return "call_test_entry_method";
        }
    }

    private static List<Map<String, Object>> dedupMapList(List<Map<String, Object>> input) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : input) {
            String sig = String.valueOf(m);
            if (seen.add(sig)) {
                out.add(m);
            }
        }
        return out;
    }

    private static Map<String, Object> kv(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> commented(String comment, Object item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("item", item);
        return m;
    }

    private static Map<String, Object> commentedList(String comment, Collection<?> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("items", items == null ? List.of() : items);
        return m;
    }

    private static Map<String, Object> commentedItems(String comment, Collection<?> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("items", items == null ? List.of() : items);
        return m;
    }

    private static List<String> splitItems(String s) {
        if (s == null || s.trim().isEmpty())
            return List.of();
        return Arrays.stream(s.split("\\s*\\|\\|\\s*"))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null)
            return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty())
                return v.trim();
        }
        return "";
    }

    private static final class EntrySideSnapshot {
        String id;
        String entryClassName;
        String entryMethodSig;
        String mutationClassName;
        String mutationMethodSig;
        boolean sameEntryAndMutation;
        boolean reusedFromOrigin;
        String reuseReason;
        String focusMode;
        String content;
        String ir;
        List<String> focusUnits = new ArrayList<>();
        List<String> callSitesToMutation = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<Map<String, Object>> cpgItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        EntrySideSnapshot copyWithId(String newId) {
            EntrySideSnapshot c = shallowCopy();
            c.id = safe(newId);
            return c;
        }

        EntrySideSnapshot copyForMutantReuse(String mutantId, String reason) {
            EntrySideSnapshot c = shallowCopy();
            c.id = safe(mutantId);
            c.reusedFromOrigin = true;
            c.reuseReason = safe(reason);
            return c;
        }

        private EntrySideSnapshot shallowCopy() {
            EntrySideSnapshot c = new EntrySideSnapshot();
            c.id = id;
            c.entryClassName = entryClassName;
            c.entryMethodSig = entryMethodSig;
            c.mutationClassName = mutationClassName;
            c.mutationMethodSig = mutationMethodSig;
            c.sameEntryAndMutation = sameEntryAndMutation;
            c.reusedFromOrigin = reusedFromOrigin;
            c.reuseReason = reuseReason;
            c.focusMode = focusMode;
            c.content = content;
            c.ir = ir;
            c.focusUnits = new ArrayList<>(focusUnits);
            c.callSitesToMutation = new ArrayList<>(callSitesToMutation);
            c.paths = new ArrayList<>(paths);
            c.cpgItems = new ArrayList<>(cpgItems);
            c.warnings = new ArrayList<>(warnings);
            return c;
        }

        Map<String, Object> toCommentedMap() {
            Map<String, Object> side = new LinkedHashMap<>();
            side.put("id", commented("Program ID", id));
            side.put("entryClass", commented("Class containing callable test entry method B", entryClassName));
            side.put("entryMethod",
                    commented("Callable test entry method B", PromptSignatureFormatter.method(entryMethodSig)));
            side.put("mutationClass", commented("Class containing the real mutation method A", mutationClassName));
            side.put("mutationMethod", commented("Real mutation method A reached from entry B",
                    PromptSignatureFormatter.method(mutationMethodSig)));
            side.put("sameEntryAndMutation", commented("Whether B is the same method as A", sameEntryAndMutation));
            side.put("reusedFromOrigin", commented(
                    "Whether this entry-side evidence reuses the origin-side Soot/Jimple graph", reusedFromOrigin));
            side.put("reuseReason", commented("Reason for reusing origin-side entry evidence", reuseReason));
            side.put("focusMode", commented(
                    "How focus units are selected: affected mutation units if B equals A, otherwise call sites from B to A",
                    focusMode));
            side.put("content", commented("Source text of callable test entry method B", content));
            side.put("IR", commented("Jimple intermediate representation of callable test entry method B", ir));
            side.put("focusUnits", commentedList(
                    "Jimple units in entry method B used as the focus of entry-lifted RIP evidence",
                    focusUnits));
            side.put("callSitesToMutation", commentedList(
                    "Invoke statements in entry method B that directly call the real mutation method A",
                    callSitesToMutation));
            side.put("Paths", commentedList(
                    "Control-flow paths in entry method B that pass through the selected focus units",
                    paths));
            side.put("CPG", commentedList(
                    "CFG and DFG information related to entry-side paths, forming an entry-level Code Property Graph",
                    cpgItems));
            side.put("warnings", commentedList("Non-fatal warnings collected during entry-lifted evidence construction",
                    warnings));
            return side;
        }
    }
}
