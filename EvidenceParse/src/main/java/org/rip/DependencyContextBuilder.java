package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Build a prompt-facing dependency context for LLM-based JUnit generation.
 *
 * This builder is intentionally separated from RIP/CPG construction:
 * - RIP/CFG/DFG evidence should stay Soot/Jimple-based;
 * - dependency context is source-structure information needed to generate
 *   compilable tests: package, imports, receiver construction, constructors,
 *   checked exceptions, field types, and referenced types.
 */
public final class DependencyContextBuilder {

    private DependencyContextBuilder() {
    }

    public static final class Context {
        /** Original Java file that contains the callable test entry B. */
        public String originJavaFile;
        /** Callable test entry B class, source-level or Soot-level name. */
        public String entryClassName;
        /** Callable test entry B signature, e.g. FilterHelpAppendable_append(char). */
        public String entryMethodSig;
        /** Real mutation method A class. */
        public String mutationClassName;
        /** Real mutation method A signature. */
        public String mutationMethodSig;
        /** Package where generated test should be placed. */
        public String testGenerationPackage;
        /** Entry kind resolved by MethodEntryResolver. */
        public String testEntryKind;
        /** Whether reflection fallback is required. */
        public boolean useReflectionFallback;
        /** Resolved call chain from B to A. */
        public String testCallChain;
        /** Optional receiver fields already resolved by MethodEntryResolver. */
        public String receiverOwnerKind;
        public boolean receiverOwnerAbstract;
        public boolean receiverOwnerInterface;
        public boolean receiverOwnerInstantiable;
        public String receiverStrategy;
        public String receiverConstruction;
        public String receiverNotes;
        public String receiverRuntimeClassName;
        public String receiverRuntimeSootClassName;
        public String receiverDeclaringClassName;
        public String receiverDispatchTarget;
        public boolean receiverDispatchesToMutationMethod;
        public boolean receiverSubclassOverridesMutationMethod;
        public String receiverSetupTemplate;
        public String receiverInvocationTemplate;
        public String receiverResolutionReason;
        public String receiverFactoryMethod;
        public String receiverBuilderClassName;
        public String receiverBuilderTerminalMethod;
        public String receiverBuilderSetupChain;
        public String receiverAntiPatterns;
        public String availablePublicMethods;
        public String availableSetupMethods;
        public String stateSetupPlan;
        public String observablePlanKind;
        public String observableSetup;
        public String observableCall;
        public String observableExpectedOriginal;
        public String observableReason;
        public String observableAntiPatterns;
        public String branchReachabilityKind;
        public String branchReachabilityCondition;
        public String branchReachabilitySetup;
        public String branchReachabilityReason;
        public String abstractMethodsToImplement;
        public String allowedOverrides;
        public String forbiddenOverrides;
        public String testStubClassTemplate;
        public String testStubConstructorTemplate;
        public boolean skipTestGeneration;
        public String skipReason;
    }

    public static Map<String, Object> build(Context ctx) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        try {
            CompilationUnit cu = parse(ctx.originJavaFile);
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String testPackage = firstNonBlank(ctx.testGenerationPackage, packageName);
            ImportIndex importIndex = ImportIndex.from(cu, packageName);

            // B: the callable test entry that generated JUnit tests should invoke.
            CallableDeclaration<?> entryCallable = findCallable(cu, ctx.entryClassName, ctx.entryMethodSig)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No callable matched entry method B: " + ctx.entryClassName + "#" + ctx.entryMethodSig));
            TypeDeclaration<?> entryOwner = findAncestorType(entryCallable)
                    .orElseThrow(() -> new IllegalStateException("Entry callable has no declaring type: " + ctx.entryMethodSig));

            DependencyModel entryModel = createModel(packageName, testPackage, entryOwner, entryCallable, importIndex, ctx);

            // A: the real mutation method. It may be the same as B, or a private/inaccessible method reached through B.
            Optional<CallableDeclaration<?>> mutationCallableOpt = findCallable(cu, ctx.mutationClassName, ctx.mutationMethodSig);
            DependencyModel mutationModel = null;
            if (mutationCallableOpt.isPresent()) {
                CallableDeclaration<?> mutationCallable = mutationCallableOpt.get();
                TypeDeclaration<?> mutationOwner = findAncestorType(mutationCallable).orElse(null);
                if (mutationOwner != null) {
                    mutationModel = createModel(packageName, testPackage, mutationOwner, mutationCallable, importIndex, ctx);
                }
            }

            boolean sameEntryAndMutation = sameMethod(ctx.entryClassName, ctx.entryMethodSig,
                    ctx.mutationClassName, ctx.mutationMethodSig);
            boolean needEntryLiftedEvidence = !sameEntryAndMutation && !ctx.useReflectionFallback;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("relationship", commented(
                    "Relationship between real mutation method A and callable test entry method B",
                    buildRelationship(ctx, sameEntryAndMutation, needEntryLiftedEvidence)));
            item.put("testEntryContext", commented(
                    "Detailed dependency context for callable test entry method B; this context tells the LLM how to construct the receiver and invoke the method under test",
                    buildTestEntryContext(entryModel)));
            item.put("mutationContext", commented(
                    "Lightweight dependency context for real mutation method A; this context helps the LLM understand where the mutation occurs, but tests should call B unless A and B are the same",
                    buildMutationContext(mutationModel, entryModel, ctx, sameEntryAndMutation)));
            item.put("compileClasspathPolicy", commented(
                    "Policy for classpath handling. The LLM should rely on type-level hints, while the local compiler/test runner uses the actual project classpath",
                    "Do not invent external libraries. Use only JDK, JUnit4, and project/third-party types listed in DependencyContext. Full jar paths are handled by the local compiler and MuJava runner."));

            wrapper.put("comment", "Dependency and type context required by the LLM to generate compilable JUnit4 tests; B is detailed for invocation, A is lightweight for mutation semantics");
            wrapper.put("item", item);
            return wrapper;
        } catch (Throwable t) {
            Map<String, Object> item = new LinkedHashMap<>();
            boolean sameEntryAndMutation = sameMethod(ctx.entryClassName, ctx.entryMethodSig,
                    ctx.mutationClassName, ctx.mutationMethodSig);
            boolean needEntryLiftedEvidence = !sameEntryAndMutation && !ctx.useReflectionFallback;

            item.put("enabled", commented("Whether dependency context was generated successfully", false));
            item.put("error", commented("Dependency context construction error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
            item.put("relationship", commented("Fallback relationship between A and B",
                    buildRelationship(ctx, sameEntryAndMutation, needEntryLiftedEvidence)));
            item.put("testEntryContext", commented("Fallback dependency context for callable test entry B",
                    buildFallbackContext(ctx.testGenerationPackage, ctx.entryClassName, ctx.entryMethodSig)));
            item.put("mutationContext", commented("Fallback dependency context for real mutation method A",
                    buildFallbackContext(ctx.testGenerationPackage, ctx.mutationClassName, ctx.mutationMethodSig)));

            wrapper.put("comment", "Dependency and type context required by the LLM to generate compilable JUnit4 tests; B is detailed for invocation, A is lightweight for mutation semantics");
            wrapper.put("item", item);
            return wrapper;
        }
    }

    private static DependencyModel createModel(String packageName,
                                               String testPackage,
                                               TypeDeclaration<?> owner,
                                               CallableDeclaration<?> callable,
                                               ImportIndex importIndex,
                                               Context ctx) {
        DependencyModel model = new DependencyModel();
        model.packageName = packageName;
        model.testPackage = testPackage;
        model.owner = owner;
        model.callable = callable;
        model.importIndex = importIndex;
        model.ctx = ctx;
        return model;
    }

    private static Map<String, Object> buildRelationship(Context ctx,
                                                          boolean sameEntryAndMutation,
                                                          boolean needEntryLiftedEvidence) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sameEntryAndMutation", commented("Whether callable test entry B is the same method as real mutation method A", sameEntryAndMutation));
        out.put("needEntryLiftedEvidence", commented("Whether B-side EntryLiftedRIP evidence should be provided to the LLM", needEntryLiftedEvidence));
        out.put("recommendedTestTarget", commented("Recommended invocation target for generated tests", recommendedTestTarget(ctx.testEntryKind, ctx.useReflectionFallback)));
        out.put("entryInvocationKind", commented("How generated tests should invoke callable test entry B", entryInvocationKindFromSignature(ctx.entryMethodSig, ctx.useReflectionFallback, ctx.receiverStrategy)));
        out.put("testEntryKind", commented("Entry resolution kind", safe(ctx.testEntryKind)));
        out.put("useReflectionFallback", commented("Whether generated tests should use reflection fallback instead of normal invocation", ctx.useReflectionFallback));
        out.put("mutationClass", commented("Class containing real mutation method A", safe(ctx.mutationClassName)));
        out.put("mutationMethod", commented("Real mutation method A used for mutation-point RIP evidence", PromptSignatureFormatter.method(ctx.mutationMethodSig)));
        out.put("testEntryClass", commented("Class containing callable test entry method B", safe(ctx.entryClassName)));
        out.put("testEntryMethod", commented("Callable method B that generated tests should invoke", PromptSignatureFormatter.method(ctx.entryMethodSig)));
        out.put("callChain", commentedList("Resolved call chain from test entry B to mutation method A", displayCallChainItems(ctx.testCallChain)));
        out.put("skipTestGeneration", commented("Whether LLM test generation should be skipped for this target", ctx.skipTestGeneration));
        out.put("skipReason", commented("Reason for skipping LLM test generation when skipTestGeneration is true", safe(ctx.skipReason)));
        return out;
    }

    private static Map<String, Object> buildTestEntryContext(DependencyModel model) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("testPackage", commented(
                "Package declaration that the generated JUnit test should use; same-package tests are required for package-private/protected access",
                model.testPackage));
        item.put("entryInvocationKind", commented(
                "How generated tests should invoke callable test entry B",
                entryInvocationKind(model)));
        item.put("invocationPlan", commented(
                "Concrete invocation plan for the selected test entry B, including constructor/static/instance invocation distinctions",
                buildInvocationPlan(model)));
        item.put("suggestedTestValues", commented(
                "Suggested argument values and assertion hints for generating a compilable and mutation-revealing JUnit test",
                buildSuggestedTestValues(model)));
        item.put("requiredImports", commentedList(
                "Imports likely required by the generated JUnit4 test source, excluding java.lang types",
                buildRequiredImports(model)));
        item.put("targetEntry", commented(
                "Callable test entry selected for test generation",
                buildTargetEntry(model)));
        item.put("receiver", commented(
                "Receiver construction information for invoking callable test entry B",
                buildReceiver(model)));
        item.put("publicApi", commented(
                "Static-analysis extracted public API, setup methods, and observable plan. Generated tests should use exact method names from this evidence.",
                buildPublicApiAndObservablePlan(model)));
        item.put("constructors", commentedList(
                "Constructors declared in the entry owner type and their accessibility for generated tests",
                buildConstructors(model)));
        item.put("methodSignature", commented(
                "Detailed signature of callable test entry method B",
                buildMethodSignature(model)));
        item.put("fieldTypes", commentedList(
                "Fields declared in the entry owner type; useful for understanding observable state and constructor dependencies",
                buildFields(model)));
        item.put("referencedTypes", commented(
                "Types referenced by package/imports/fields/constructors/method signature/method body of B, grouped by source",
                buildReferencedTypes(model)));
        item.put("internalCalls", commentedList(
                "Method calls and object creations appearing in B's method body; useful for identifying collaborators needed by the test",
                buildInternalCalls(model)));
        return item;
    }

    private static Map<String, Object> buildPublicApiAndObservablePlan(DependencyModel m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availablePublicMethods", commentedItems("Public/package-visible methods found by static analysis; use exact names only", splitItems(m.ctx.availablePublicMethods)));
        out.put("availableSetupMethods", commentedItems("Setter/setup methods found by static analysis; use exact names only", splitItems(m.ctx.availableSetupMethods)));
        out.put("compilationGuardrails", commentedItems("Rules that prevent common javac failures observed after first-round repair", buildCompilationGuardrails(m)));
        out.put("stateSetupPlan", commentedItems("Suggested state setup statements before invocation/observable calls", splitItems(m.ctx.stateSetupPlan)));
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("kind", commented("Observable plan kind", safe(m.ctx.observablePlanKind)));
        obs.put("setup", commented("Additional setup needed before observing mutation effect", safe(m.ctx.observableSetup)));
        obs.put("observableCall", commented("Public call that exposes mutation effect", safe(m.ctx.observableCall)));
        obs.put("expectedOriginal", commented("Expected original value when inferred", safe(m.ctx.observableExpectedOriginal)));
        obs.put("reason", commented("Why this observable was selected or why generation should be skipped", safe(m.ctx.observableReason)));
        obs.put("antiPatterns", commentedItems("API/observable patterns to avoid", splitItems(m.ctx.observableAntiPatterns)));
        out.put("observablePlan", commented("How to observe the mutation through public behavior", obs));
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("kind", commented("Branch reachability plan kind", safe(m.ctx.branchReachabilityKind)));
        branch.put("condition", commented("CFG/path condition that should be satisfied to reach the mutation-sensitive branch", safe(m.ctx.branchReachabilityCondition)));
        branch.put("setup", commented("Static-analysis generated setup intended to satisfy the branch condition", safe(m.ctx.branchReachabilitySetup)));
        branch.put("reason", commented("Why this setup reaches the target branch", safe(m.ctx.branchReachabilityReason)));
        out.put("branchReachabilityPlan", commented("How to make the mutation-sensitive path reachable before assertion", branch));
        return out;
    }

    private static List<String> buildCompilationGuardrails(DependencyModel m) {
        List<String> out = new ArrayList<>();
        out.add("Keep the generated test in package '" + safe(m.testPackage) + "' so package-private/protected members remain accessible when allowed by Java.");
        out.add("Do not invent project APIs. Use only methods listed in availablePublicMethods/availableSetupMethods, or use reflection only when an explicit reflection plan is provided.");
        out.add("Do not define helper classes with the same simple name as the production class under test; this can shadow the real class and cause cannot-find-symbol or wrong-target errors.");
        out.add("If a test stub subclasses the owner type, override only methods listed in receiver.allowedOverrides and copy the exact access level; never reduce public to protected/private.");
        out.add("Never override final, private, concrete, or non-existent methods. Remove @Override unless the method is explicitly listed as an allowed override.");
        out.add("For void methods, call the method as a statement and observe state/return through observablePlan; never assign a void call to result.");
        out.add("For package-private classes in the same package, reference the simple class name directly from the same test package; do not import the class from a parent or sibling package.");
        out.add("If no public getter exists for a setter-updated field, do not invent getXxx(); use observablePlan/reflectionFieldReadPlan when provided.");

        boolean hasDataInput = false;
        for (Parameter p : m.callable.getParameters()) {
            String t = canonicalType(p.getType().asString(), p.isVarArgs(), false);
            if ("DataInput".equals(simpleType(t)) || "java.io.DataInput".equals(t)) {
                hasDataInput = true;
                break;
            }
        }
        if (hasDataInput) {
            out.add("For java.io.DataInput parameters, prefer new DataInputStream(new ByteArrayInputStream(bytes)); do not hand-write an anonymous DataInput implementation unless every JDK DataInput method is implemented exactly.");
        }

        if (m.callable instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) m.callable;
            if (md.getType().isVoidType()) {
                out.add("The entry method returns void: use 'subject." + md.getNameAsString() + "(...)' as a statement, then assert via observablePlan.");
            }
        } else if (m.callable instanceof ConstructorDeclaration) {
            out.add("The entry is a constructor: invoke it with 'new ...(...)'; constructors cannot be called like instance methods.");
        }

        if (m.owner instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) m.owner;
            if (cd.isInterface()) {
                out.add("The owner is an interface: use an existing concrete implementation or a complete test implementation; do not instantiate the interface directly.");
            } else if (cd.isAbstract()) {
                out.add("The owner is abstract: use receiver.testStubClassTemplate or an existing concrete subclass; implement all abstract methods listed in receiver.allowedOverrides.");
            }
        }

        return out;
    }

    private static List<String> firstNonEmpty(List<String> primary, List<String> fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        return fallback == null ? List.of() : fallback;
    }

    private static List<String> buildAllowedOverrideStubs(DependencyModel m) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (m == null || m.owner == null) {
            return List.of();
        }
        boolean ownerIsInterface = m.owner instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) m.owner).isInterface();
        for (MethodDeclaration md : m.owner.getMethods()) {
            boolean mustImplement = md.isAbstract() || (ownerIsInterface && !md.isStatic() && !md.isDefault());
            if (!mustImplement) {
                continue;
            }
            out.add(methodStubTemplate(md));
        }
        return new ArrayList<>(out);
    }

    private static String methodStubTemplate(MethodDeclaration md) {
        String access = accessOf(md);
        if (access == null || access.isBlank() || "package-private/default".equals(access)) {
            access = "";
        } else {
            access = access + " ";
        }
        String params = md.getParameters().stream()
                .map(p -> p.getType().asString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        String throwsPart = md.getThrownExceptions().isEmpty() ? "" : " throws " + md.getThrownExceptions().stream()
                .map(Type::asString).collect(Collectors.joining(", "));
        return "@Override " + access + md.getType().asString() + " " + md.getNameAsString() + "(" + params + ")" + throwsPart
                + " { " + defaultReturnStatement(md.getType().asString()) + " }";
    }

    private static String defaultReturnStatement(String type) {
        String t = simpleType(type);
        if ("void".equals(t)) return "";
        if ("boolean".equals(t)) return "return false;";
        if (Set.of("byte", "short", "int", "long", "float", "double", "char").contains(t)) {
            if ("char".equals(t)) return "return '\\0';";
            if ("long".equals(t)) return "return 0L;";
            if ("float".equals(t)) return "return 0.0f;";
            if ("double".equals(t)) return "return 0.0d;";
            return "return 0;";
        }
        return "return null;";
    }

    private static List<String> buildForbiddenOverrideRules(DependencyModel m) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("Do not override final methods.");
        out.add("Do not override private methods.");
        out.add("Do not override concrete methods unless output.json explicitly says to do so.");
        out.add("Do not reduce method visibility: public must remain public, protected must remain protected or public.");
        out.add("Do not add @Override to helper methods that are not declared by a superclass or interface.");
        out.add("Do not invent getters such as getWidth(), getHeight(), getIgnore() unless they are listed in availablePublicMethods.");
        if (m != null && m.owner != null) {
            for (MethodDeclaration md : m.owner.getMethods()) {
                if (md.isFinal()) {
                    out.add(md.getNameAsString() + "(" + md.getParameters().stream()
                            .map(p -> p.getType().asString()).collect(Collectors.joining(",")) + ") is final; do not override it.");
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isSamePackageImport(String imp, String testPackage) {
        if (imp == null || testPackage == null || testPackage.isBlank()) {
            return false;
        }
        String s = imp.trim();
        if (s.startsWith("static ")) {
            s = s.substring("static ".length()).trim();
        }
        if (s.endsWith(".*")) {
            return s.substring(0, s.length() - 2).equals(testPackage);
        }
        int dot = s.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return s.substring(0, dot).equals(testPackage);
    }

    private static Optional<TypeDeclaration<?>> findAncestorType(Node node) {
        if (node == null) {
            return Optional.empty();
        }
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node cur = parent.get();
            if (cur instanceof TypeDeclaration<?>) {
                return Optional.of((TypeDeclaration<?>) cur);
            }
            parent = cur.getParentNode();
        }
        return Optional.empty();
    }

    private static Map<String, Object> buildMutationContext(DependencyModel mutationModel,
                                                             DependencyModel entryModel,
                                                             Context ctx,
                                                             boolean sameEntryAndMutation) {
        Map<String, Object> out = new LinkedHashMap<>();
        DependencyModel effective = mutationModel != null ? mutationModel : (sameEntryAndMutation ? entryModel : null);

        out.put("available", commented("Whether the source declaration of real mutation method A was found for dependency analysis", effective != null));
        out.put("sameAsTestEntryContext", commented("Whether A and B are the same and the detailed B context also describes A", sameEntryAndMutation));
        out.put("mutationClass", commented("Class containing real mutation method A", safe(ctx.mutationClassName)));
        out.put("mutationMethod", commented("Real mutation method A used for mutation-point RIP evidence", PromptSignatureFormatter.method(ctx.mutationMethodSig)));

        if (effective == null) {
            out.put("notes", commented("Notes for missing mutation declaration", "Mutation method A was not found in the same source file by JavaParser; rely on A-side Soot/RIP evidence and TestEntry/EntryLiftedRIP for invocation."));
            return out;
        }

        out.put("ownerClass", commented("Source-level owner class of mutation method A", classPathOf(effective.owner)));
        out.put("ownerKind", commented("Kind of the declaring type of mutation method A", ownerKindOf(effective.owner)));
        out.put("methodSignature", commented("Detailed source signature of mutation method A", buildCallableSignature(effective, ctx.mutationMethodSig)));
        out.put("referencedTypes", commented("Types referenced by mutation method A and its owner context, grouped by source", buildReferencedTypes(effective)));
        out.put("internalCalls", commentedList("Method calls and object creations appearing in mutation method A's body", buildInternalCalls(effective)));
        return out;
    }

    private static Map<String, Object> buildFallbackContext(String testPackage, String className, String methodSig) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("testPackage", commented("Fallback package declaration for generated test", safe(testPackage)));
        out.put("className", commented("Fallback class name", safe(className)));
        out.put("methodSignature", commented("Fallback method signature", PromptSignatureFormatter.method(methodSig)));
        return out;
    }

    private static Map<String, Object> buildCallableSignature(DependencyModel m, String sootLikeSignature) {
        Map<String, Object> out = new LinkedHashMap<>();
        CallableDeclaration<?> cd = m.callable;
        out.put("name", commented("Method or constructor name", cd.getNameAsString()));
        out.put("sourceStyleSignature", commented("Prompt-facing Java-style signature", PromptSignatureFormatter.method(sootLikeSignature)));
        out.put("access", commented("Callable access modifier", accessOf(cd)));
        out.put("isPrivate", commented("Whether callable is private", hasModifier(cd, Modifier.Keyword.PRIVATE)));
        out.put("isStatic", commented("Whether callable is static", cd instanceof MethodDeclaration && hasModifier(cd, Modifier.Keyword.STATIC)));
        out.put("parameters", commentedList("Parameters of callable", cd.getParameters().stream().map(p -> {
            Map<String, Object> pmap = new LinkedHashMap<>();
            pmap.put("name", commented("Parameter name", p.getNameAsString()));
            pmap.put("type", commented("Parameter type", p.getType().asString()));
            return pmap;
        }).collect(Collectors.toList())));
        out.put("throwsTypes", commentedList("Checked exceptions declared by callable", cd.getThrownExceptions().stream()
                .map(Type::asString).collect(Collectors.toList())));
        if (cd instanceof MethodDeclaration) {
            out.put("returnType", commented("Return type of callable", ((MethodDeclaration) cd).getType().asString()));
        } else {
            out.put("returnType", commented("Return type of constructor entry", "<constructor>"));
        }
        return out;
    }

    private static String ownerKindOf(TypeDeclaration<?> owner) {
        if (owner instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) owner;
            if (cd.isInterface()) return "INTERFACE";
            if (cd.isAbstract()) return "ABSTRACT_CLASS";
        }
        return "CONCRETE_CLASS";
    }

    private static boolean sameMethod(String classA, String sigA, String classB, String sigB) {
        return normalizeClassName(classA).equals(normalizeClassName(classB))
                && normalizeSignature(sigA).equals(normalizeSignature(sigB));
    }

    private static String normalizeClassName(String s) {
        if (s == null) return "";
        return s.trim().replace('$', '.');
    }

    private static String normalizeSignature(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").replace('$', '.');
    }

    private static String recommendedTestTarget(String kind, boolean reflection) {
        if (reflection) return "reflect_mutation_method";
        if (kind == null) return "call_test_entry_method";
        switch (kind) {
            case "PUBLIC_DIRECT_ENTRY": return "call_public_method";
            case "PACKAGE_PRIVATE_DIRECT_ENTRY": return "call_package_private_method";
            case "PROTECTED_SAME_PACKAGE_DIRECT_ENTRY": return "call_protected_same_package_method";
            case "ASCENDED_PUBLIC_CALLER":
            case "ASCENDED_PACKAGE_PRIVATE_CALLER":
            case "ASCENDED_PROTECTED_SAME_PACKAGE_CALLER":
                return "call_test_entry_method";
            case "REFLECTION_FALLBACK": return "reflect_mutation_method";
            default: return "call_test_entry_method";
        }
    }

    private static CompilationUnit parse(String javaFile) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
        JavaParser parser = new JavaParser(cfg);
        var result = parser.parse(Path.of(javaFile));
        if (result.getResult().isEmpty()) {
            throw new IllegalArgumentException("Parse failed: " + result.getProblems());
        }
        CompilationUnit cu = result.getResult().get();
        cu.getAllContainedComments().forEach(Comment::remove);
        return cu;
    }

    private static Optional<CallableDeclaration<?>> findCallable(CompilationUnit cu, String classPathOrNull, String sig) {
        Sig s = Sig.parse(sig);
        List<CallableDeclaration<?>> candidates = new ArrayList<>();

        Optional<TypeDeclaration<?>> typeOpt = findTypeByPath(cu, classPathOrNull);
        if (typeOpt.isPresent()) {
            for (BodyDeclaration<?> bd : typeOpt.get().getMembers()) {
                if (bd instanceof MethodDeclaration || bd instanceof ConstructorDeclaration) {
                    candidates.add((CallableDeclaration<?>) bd);
                }
            }
        } else {
            candidates.addAll(cu.findAll(MethodDeclaration.class));
            candidates.addAll(cu.findAll(ConstructorDeclaration.class));
        }

        return candidates.stream().filter(cd -> matches(cd, s)).findFirst();
    }

    private static Optional<TypeDeclaration<?>> findTypeByPath(CompilationUnit cu, String classPathOrNull) {
        if (classPathOrNull == null || classPathOrNull.isBlank()) {
            return Optional.empty();
        }
        String path = classPathOrNull.replace('$', '.');
        String[] parts = path.split("\\.");

        // Try exact nested source path first, using suffix because config may include package.
        List<TypeDeclaration<?>> all = cu.findAll(TypeDeclaration.class)
                .stream()
                .map(td -> (TypeDeclaration<?>) td)
                .collect(Collectors.toList());
        for (TypeDeclaration<?> td : all) {
            String cp = classPathOf(td);
            if (cp.equals(path) || path.endsWith("." + cp)) {
                return Optional.of(td);
            }
        }

        String simple = parts[parts.length - 1];
        List<TypeDeclaration<?>> hits = all.stream()
                .filter(td -> td.getNameAsString().equals(simple))
                .collect(Collectors.toList());
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    private static boolean matches(CallableDeclaration<?> cd, Sig sig) {
        boolean ctor = cd instanceof ConstructorDeclaration;
        if (sig.constructor != ctor) {
            return false;
        }
        if (!cd.getNameAsString().equals(sig.name)) {
            return false;
        }
        List<String> actualParams = cd.getParameters().stream()
                .map(p -> canonicalType(p.getType().asString(), p.isVarArgs(), true))
                .collect(Collectors.toList());
        if (!actualParams.equals(sig.params)) {
            return false;
        }
        if (cd instanceof MethodDeclaration) {
            String ret = canonicalType(((MethodDeclaration) cd).getType().asString(), false, true);
            return ret.equals(sig.returnType);
        }
        return true;
    }

    private static Map<String, Object> buildTargetEntry(DependencyModel m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entryClass", commented("Class containing callable test entry B", classPathOf(m.owner)));
        out.put("entryMethod", commented("Callable method B that generated tests should invoke", PromptSignatureFormatter.method(m.ctx.entryMethodSig)));
        out.put("entryInvocationKind", commented("How generated tests should invoke callable test entry B", entryInvocationKind(m)));
        out.put("mutationClass", commented("Class containing real mutation method A", safe(m.ctx.mutationClassName)));
        out.put("mutationMethod", commented("Real mutation method A used for mutation-point RIP evidence", PromptSignatureFormatter.method(m.ctx.mutationMethodSig)));
        out.put("testEntryKind", commented("Entry resolution kind", safe(m.ctx.testEntryKind)));
        out.put("useReflectionFallback", commented("Whether generated tests should use reflection fallback", m.ctx.useReflectionFallback));
        out.put("callChain", commentedList("Resolved call chain from test entry B to mutation method A", displayCallChainItems(m.ctx.testCallChain)));
        return out;
    }


    private static String entryInvocationKind(DependencyModel m) {
        boolean isStatic = m.callable instanceof MethodDeclaration && hasModifier(m.callable, Modifier.Keyword.STATIC);
        return entryInvocationKindFromSignature(m.ctx.entryMethodSig, m.ctx.useReflectionFallback,
                isStatic ? "STATIC_NO_RECEIVER" : m.ctx.receiverStrategy);
    }

    private static String entryInvocationKindFromSignature(String sig, boolean reflection, String receiverStrategy) {
        if (reflection) {
            return "REFLECTION_INVOCATION";
        }
        try {
            Sig parsed = Sig.parse(sig);
            if (parsed.constructor) {
                return "CONSTRUCTOR_INVOCATION";
            }
        } catch (Throwable ignored) {
            // fall through to receiver strategy
        }
        if ("STATIC_NO_RECEIVER".equals(receiverStrategy)) {
            return "STATIC_METHOD_INVOCATION";
        }
        return "INSTANCE_METHOD_INVOCATION";
    }

    private static Map<String, Object> buildInvocationPlan(DependencyModel m) {
        Map<String, Object> out = new LinkedHashMap<>();
        String kind = entryInvocationKind(m);
        String owner = m.owner.getNameAsString();
        String methodName = m.callable.getNameAsString();
        String args = exampleArgumentsJoined(m);

        out.put("entryInvocationKind", commented("Invocation kind selected for callable test entry B", kind));
        out.put("receiverRequired", commented("Whether generated test needs a receiver object before invoking B",
                "INSTANCE_METHOD_INVOCATION".equals(kind)));

        if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            out.put("setupTemplate", commented("Suggested setup code before assertion",
                    firstNonBlank(m.ctx.receiverSetupTemplate, owner + " subject = new " + owner + "(" + args + ");")));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    firstNonBlank(m.ctx.receiverInvocationTemplate, "new " + owner + "(" + args + ")")));
            out.put("notes", commented("Invocation notes",
                    "B is a constructor, so generated tests should instantiate the object directly. Do not call the constructor as if it were a normal method."));
        } else if ("STATIC_METHOD_INVOCATION".equals(kind)) {
            String retPrefix = m.callable instanceof MethodDeclaration
                    && !"void".equals(((MethodDeclaration) m.callable).getType().asString())
                    ? simpleType(((MethodDeclaration) m.callable).getType().asString()) + " result = " : "";
            out.put("setupTemplate", commented("Suggested setup code before invocation", ""));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    retPrefix + owner + "." + methodName + "(" + args + ");"));
            out.put("notes", commented("Invocation notes", "B is static; no receiver object is required."));
        } else if ("REFLECTION_INVOCATION".equals(kind)) {
            out.put("setupTemplate", commented("Suggested setup code before invocation", ""));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    "Use reflection according to TestEntry because no directly callable entry was resolved."));
            out.put("notes", commented("Invocation notes", "Reflection fallback should be used only when no callable entry B exists."));
        } else {
            String receiverSetup = firstNonBlank(m.ctx.receiverSetupTemplate, receiverSetupTemplate(m));
            String retPrefix = m.callable instanceof MethodDeclaration
                    && !"void".equals(((MethodDeclaration) m.callable).getType().asString())
                    ? simpleType(((MethodDeclaration) m.callable).getType().asString()) + " result = " : "";
            out.put("setupTemplate", commented("Suggested setup code before invocation", receiverSetup));
            out.put("invocationTemplate", commented("How to invoke B in generated JUnit test",
                    firstNonBlank(m.ctx.receiverInvocationTemplate, retPrefix + "subject." + methodName + "(" + args + ");")));
            out.put("notes", commented("Invocation notes", "B is an instance method; create the receiver according to receiver.strategy before invoking it."));
        }
        return out;
    }

    private static Map<String, Object> buildSuggestedTestValues(DependencyModel m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exampleArguments", commentedList("Example argument values matching B's parameters", buildExampleArguments(m)));
        out.put("setupStatements", commentedList("Useful setup statements inferred from receiver/parameter types", buildSetupStatements(m)));
        out.put("assertionHints", commentedList("Mutation-aware assertion hints inferred from Diff, internal calls, fields, and return/side-effect evidence", buildAssertionHints(m)));
        return out;
    }

    private static List<Object> buildExampleArguments(DependencyModel m) {
        List<Object> out = new ArrayList<>();
        for (Parameter p : m.callable.getParameters()) {
            Map<String, Object> arg = new LinkedHashMap<>();
            arg.put("name", commented("Parameter name", p.getNameAsString()));
            arg.put("type", commented("Parameter type", p.getType().asString()));
            arg.put("exampleValue", commented("Suggested Java expression for this parameter", exampleValueForType(p.getType().asString())));
            out.add(arg);
        }
        return out;
    }

    private static List<String> buildSetupStatements(DependencyModel m) {
        List<String> out = new ArrayList<>();
        String kind = entryInvocationKind(m);
        if ("INSTANCE_METHOD_INVOCATION".equals(kind)) {
            out.add(firstNonBlank(m.ctx.receiverSetupTemplate, receiverSetupTemplate(m)));
        } else if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            out.add(firstNonBlank(m.ctx.receiverSetupTemplate, m.owner.getNameAsString() + " subject = new " + m.owner.getNameAsString() + "(" + exampleArgumentsJoined(m) + ");"));
        }
        for (Parameter p : m.callable.getParameters()) {
            String t = simpleType(p.getType().asString());
            if ("Collection".equals(t) || "List".equals(t) || "Iterable".equals(t)) {
                out.add("java.util.Collection<String> " + p.getNameAsString() + " = java.util.Arrays.asList(\"alpha\", \"beta\");");
            }
        }
        return out.stream().filter(x -> x != null && !x.isBlank()).distinct().collect(Collectors.toList());
    }

    private static List<String> buildAssertionHints(DependencyModel m) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        String kind = entryInvocationKind(m);
        if ("CONSTRUCTOR_INVOCATION".equals(kind)) {
            hints.add("Because B is a constructor, assert observable effects on the constructed object, such as getMessage(), public getters, or state exposed by methods.");
        }
        if (m.callable instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) m.callable;
            if (!"void".equals(md.getType().asString())) {
                hints.add("The entry method returns " + md.getType().asString() + "; assert the returned value when it reflects the mutation effect.");
            }
        }
        for (MethodCallExpr call : m.callable.findAll(MethodCallExpr.class)) {
            String text = call.toString();
            if (text.contains("output.append") || text.contains("Appendable") || text.endsWith(".append")) {
                hints.add("If an Appendable/StringBuilder collaborator is used, assert its final text content to observe state propagation.");
            }
            if (text.contains("createMessage")) {
                hints.add("The entry calls a message-building method; assert exception/message text contains mutation-sensitive fragments.");
            }
        }
        return new ArrayList<>(hints);
    }

    private static String receiverSetupTemplate(DependencyModel m) {
        String type = m.owner.getNameAsString();
        String ownerKind = ownerKindOf(m.owner);
        String ctorArgs = bestConstructorArgs(m);
        if ("ABSTRACT_CLASS".equals(ownerKind)) {
            return type + " subject = new " + type + "(" + ctorArgs + ") { };";
        }
        if ("INTERFACE".equals(ownerKind)) {
            return type + " subject = /* provide minimal implementation or existing implementation */ null;";
        }
        return type + " subject = new " + type + "(" + ctorArgs + ");";
    }

    private static String exampleArgumentsJoined(DependencyModel m) {
        return m.callable.getParameters().stream()
                .map(p -> exampleValueForType(p.getType().asString()))
                .collect(Collectors.joining(", "));
    }

    private static Map<String, Object> buildReceiver(DependencyModel m) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean isInterface = m.owner instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) m.owner).isInterface();
        boolean isAbstract = m.owner instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) m.owner).isAbstract();
        boolean isStatic = m.callable instanceof MethodDeclaration && hasModifier(m.callable, Modifier.Keyword.STATIC);
        String ownerKind = isInterface ? "INTERFACE" : (isAbstract ? "ABSTRACT_CLASS" : "CONCRETE_CLASS");

        String strategy = firstNonBlank(m.ctx.receiverStrategy, inferReceiverStrategy(m, ownerKind, isStatic));
        String construction = firstNonBlank(m.ctx.receiverConstruction, inferReceiverConstruction(m, ownerKind, strategy));
        String notes = firstNonBlank(m.ctx.receiverNotes, inferReceiverNotes(m, ownerKind, strategy));
        boolean instantiable = m.ctx.receiverOwnerInstantiable || inferInstantiable(m, strategy);

        out.put("ownerKind", commented("Kind of the declaring type of the callable test entry", firstNonBlank(m.ctx.receiverOwnerKind, ownerKind)));
        out.put("ownerAbstract", commented("Whether the declaring type of the callable test entry is abstract", m.ctx.receiverOwnerAbstract || isAbstract));
        out.put("ownerInterface", commented("Whether the declaring type of the callable test entry is an interface", m.ctx.receiverOwnerInterface || isInterface));
        out.put("ownerInstantiable", commented("Whether a generated test can construct a receiver without reflection", instantiable));
        out.put("strategy", commented("Recommended receiver construction strategy", strategy));
        out.put("construction", commented("Human-readable receiver construction instruction for the LLM", construction));
        out.put("notes", commented("Additional notes about receiver construction", notes));
        out.put("runtimeReceiverClass", commented("Concrete runtime class to instantiate when it differs from the declaring entry class", safe(m.ctx.receiverRuntimeClassName)));
        out.put("runtimeReceiverSootClass", commented("Fully qualified runtime receiver class", safe(m.ctx.receiverRuntimeSootClassName)));
        out.put("declaringClass", commented("Declaring class that contains the mutation/entry method", safe(m.ctx.receiverDeclaringClassName)));
        out.put("dispatchTarget", commented("Method body expected to execute after dynamic dispatch", safe(m.ctx.receiverDispatchTarget)));
        out.put("dispatchesToMutationMethod", commented("Whether the selected receiver dispatches to the mutation method body", m.ctx.receiverDispatchesToMutationMethod));
        out.put("subclassOverridesMutationMethod", commented("Whether the concrete subclass overrides the mutated method", m.ctx.receiverSubclassOverridesMutationMethod));
        out.put("setupTemplate", commented("Static-analysis generated receiver setup code", safe(m.ctx.receiverSetupTemplate)));
        out.put("invocationTemplate", commented("Static-analysis generated invocation code", safe(m.ctx.receiverInvocationTemplate)));
        out.put("factoryMethod", commented("Static factory method selected by receiver resolver, when applicable", safe(m.ctx.receiverFactoryMethod)));
        out.put("builderClass", commented("Builder/helper type selected by receiver resolver, when applicable", safe(m.ctx.receiverBuilderClassName)));
        out.put("builderTerminalMethod", commented("Terminal method that creates the receiver instance, e.g. get/build", safe(m.ctx.receiverBuilderTerminalMethod)));
        out.put("builderSetupChain", commented("Chained builder setup calls selected by static analysis", safe(m.ctx.receiverBuilderSetupChain)));
        out.put("antiPatterns", commentedItems("Receiver construction patterns that generated tests must avoid", splitItems(m.ctx.receiverAntiPatterns)));
        out.put("allowedOverrides", commentedItems("Methods that a generated test stub is allowed/required to override. Override only these methods and copy the exact access level shown here.", firstNonEmpty(splitItems(m.ctx.allowedOverrides), buildAllowedOverrideStubs(m))));
        out.put("forbiddenOverrides", commentedItems("Methods/patterns that generated tests must not override or invent", firstNonEmpty(splitItems(m.ctx.forbiddenOverrides), buildForbiddenOverrideRules(m))));
        out.put("testStubClassTemplate", commented("Complete test-stub class template when TEST_STUB_SUBCLASS is selected", safe(m.ctx.testStubClassTemplate)));
        out.put("testStubConstructorTemplate", commented("Constructor template for the generated test stub", safe(m.ctx.testStubConstructorTemplate)));
        out.put("resolutionReason", commented("Why this receiver strategy was selected", safe(m.ctx.receiverResolutionReason)));
        return out;
    }

    private static String inferReceiverStrategy(DependencyModel m, String ownerKind, boolean isStatic) {
        if (isStatic) {
            return "STATIC_NO_RECEIVER";
        }
        if ("INTERFACE".equals(ownerKind)) {
            return "INTERFACE_IMPLEMENTATION_REQUIRED";
        }
        if ("ABSTRACT_CLASS".equals(ownerKind)) {
            return hasAccessibleConstructor(m) ? "ANONYMOUS_SUBCLASS" : "CONCRETE_SUBCLASS_REQUIRED";
        }
        return hasAccessibleConstructor(m) ? "DIRECT_CONSTRUCTOR" : "FACTORY_OR_REFLECTION_REQUIRED";
    }

    private static String inferReceiverConstruction(DependencyModel m, String ownerKind, String strategy) {
        String type = m.owner.getNameAsString();
        String ctorArgs = bestConstructorArgs(m);
        switch (strategy) {
            case "STATIC_NO_RECEIVER":
                return "No receiver is required; call the static entry method on " + type + ".";
            case "ANONYMOUS_SUBCLASS":
                return "Create a same-package anonymous subclass, e.g. new " + type + "(" + ctorArgs + ") { }, then call the entry method on that receiver.";
            case "INTERFACE_IMPLEMENTATION_REQUIRED":
                return "Create a minimal test implementation of interface " + type + " or use an existing project implementation, then call the entry method if it is a default method.";
            case "DIRECT_CONSTRUCTOR":
                return "Create the receiver directly using an accessible constructor, e.g. new " + type + "(" + ctorArgs + ").";
            case "CONCRETE_SUBCLASS_REQUIRED":
                return "The declaring class is abstract and no accessible constructor was found; use an existing concrete subclass or reflection fallback.";
            default:
                return "Use an existing factory/concrete instance if available; otherwise use reflection fallback.";
        }
    }

    private static String inferReceiverNotes(DependencyModel m, String ownerKind, String strategy) {
        if ("ANONYMOUS_SUBCLASS".equals(strategy)) {
            return "The declaring class is abstract, so it cannot be instantiated directly; use an anonymous subclass with an accessible constructor.";
        }
        if ("INTERFACE_IMPLEMENTATION_REQUIRED".equals(strategy)) {
            return "The declaring type is an interface; generated tests need a concrete implementation unless the method is static.";
        }
        if ("DIRECT_CONSTRUCTOR".equals(strategy)) {
            return "The declaring class is concrete and has a non-private constructor usable from the generated test package.";
        }
        return "Receiver construction may require an existing project subtype, factory, or reflection.";
    }

    private static boolean inferInstantiable(DependencyModel m, String strategy) {
        return "STATIC_NO_RECEIVER".equals(strategy)
                || "DIRECT_CONSTRUCTOR".equals(strategy)
                || "ANONYMOUS_SUBCLASS".equals(strategy);
    }

    private static List<Object> buildConstructors(DependencyModel m) {
        List<Object> out = new ArrayList<>();
        if (!(m.owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) m.owner;
        if (cd.isInterface()) {
            return out;
        }
        List<ConstructorDeclaration> ctors = cd.getConstructors();
        if (ctors.isEmpty()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("signature", commented("Constructor signature", cd.getNameAsString() + "()"));
            c.put("access", commented("Constructor access", "package-private/default"));
            c.put("accessibleFromGeneratedTest", commented("Whether generated test can call this constructor", true));
            c.put("parameterTypes", commentedList("Constructor parameter types", List.of()));
            out.add(c);
            return out;
        }
        for (ConstructorDeclaration ctor : ctors) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("signature", commented("Constructor signature", constructorSignature(ctor)));
            c.put("access", commented("Constructor access", accessOf(ctor)));
            c.put("accessibleFromGeneratedTest", commented("Whether generated test can call this constructor", isAccessibleFromSamePackage(ctor)));
            c.put("parameterTypes", commentedList("Constructor parameter types", ctor.getParameters().stream()
                    .map(p -> p.getType().asString()).collect(Collectors.toList())));
            out.add(c);
        }
        return out;
    }

    private static Map<String, Object> buildMethodSignature(DependencyModel m) {
        return buildCallableSignature(m, m.ctx.entryMethodSig);
    }

    private static List<Object> buildFields(DependencyModel m) {
        List<Object> out = new ArrayList<>();
        for (FieldDeclaration fd : m.owner.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", commented("Field name", v.getNameAsString()));
                f.put("type", commented("Field type", v.getType().asString()));
                f.put("access", commented("Field access", accessOf(fd)));
                f.put("modifiers", commentedList("Field modifiers", fd.getModifiers().stream()
                        .map(mod -> mod.getKeyword().asString()).collect(Collectors.toList())));
                out.add(f);
            }
        }
        return out;
    }

    private static Map<String, Object> buildReferencedTypes(DependencyModel m) {
        LinkedHashSet<String> raw = new LinkedHashSet<>();

        raw.add(classPathOf(m.owner));
        if (m.callable instanceof MethodDeclaration) {
            raw.add(((MethodDeclaration) m.callable).getType().asString());
        }
        for (Parameter p : m.callable.getParameters()) {
            raw.add(p.getType().asString());
        }
        m.callable.getThrownExceptions().forEach(t -> raw.add(t.asString()));
        for (FieldDeclaration fd : m.owner.getFields()) {
            fd.getVariables().forEach(v -> raw.add(v.getType().asString()));
        }
        for (ConstructorDeclaration ctor : m.owner.getConstructors()) {
            ctor.getParameters().forEach(p -> raw.add(p.getType().asString()));
            ctor.getThrownExceptions().forEach(t -> raw.add(t.asString()));
        }
        for (ObjectCreationExpr oce : m.callable.findAll(ObjectCreationExpr.class)) {
            raw.add(oce.getType().asString());
        }
        for (String imp : m.importIndex.imports) {
            raw.add(imp);
        }

        LinkedHashSet<String> jdk = new LinkedHashSet<>();
        LinkedHashSet<String> project = new LinkedHashSet<>();
        LinkedHashSet<String> third = new LinkedHashSet<>();
        for (String r : raw) {
            String q = m.importIndex.qualifyType(r);
            if (q == null || q.isBlank() || isPrimitiveOrVoid(q)) {
                continue;
            }
            if (isJdkType(q)) {
                jdk.add(q);
            } else if (m.importIndex.isProjectType(q)) {
                project.add(q);
            } else {
                third.add(q);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jdkTypes", commentedList("JDK types referenced by entry method, constructors, fields, or exceptions", new ArrayList<>(jdk)));
        out.put("projectTypes", commentedList("Project-local types referenced by entry method, constructors, fields, or exceptions", new ArrayList<>(project)));
        out.put("thirdPartyTypes", commentedList("Third-party library types referenced by entry method, constructors, fields, or exceptions", new ArrayList<>(third)));
        return out;
    }

    private static List<Object> buildInternalCalls(DependencyModel m) {
        List<Object> out = new ArrayList<>();
        for (MethodCallExpr call : m.callable.findAll(MethodCallExpr.class)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("kind", commented("Call kind", "method_call"));
            c.put("text", commented("Call expression text", call.toString()));
            c.put("name", commented("Called method name", call.getNameAsString()));
            c.put("scope", commented("Call scope expression if present", call.getScope().map(Object::toString).orElse("")));
            c.put("arity", commented("Number of arguments", call.getArguments().size()));
            out.add(c);
        }
        for (ObjectCreationExpr oce : m.callable.findAll(ObjectCreationExpr.class)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("kind", commented("Call kind", "object_creation"));
            c.put("text", commented("Object creation expression text", oce.toString()));
            c.put("type", commented("Created type", oce.getType().asString()));
            c.put("arity", commented("Number of constructor arguments", oce.getArguments().size()));
            out.add(c);
        }
        return out;
    }

    private static List<String> buildRequiredImports(DependencyModel m) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("org.junit.Test");
        out.add("static org.junit.Assert.*");

        // Simpler and safer: reuse explicit source imports, then add JUnit.
        // Do not dump full classpath or guessed jar paths into the prompt.
        for (String imp : m.importIndex.imports) {
            if (!isJavaLangType(imp) && !isSamePackageImport(imp, m.testPackage)) {
                out.add(imp);
            }
        }
        for (Parameter p : m.callable.getParameters()) {
            String t = simpleType(p.getType().asString());
            if ("Collection".equals(t) || "List".equals(t) || "Iterable".equals(t)) {
                out.add("java.util.Arrays");
            }
            if ("Set".equals(t)) {
                out.add("java.util.Arrays");
                out.add("java.util.LinkedHashSet");
            }
            if ("Map".equals(t)) {
                out.add("java.util.LinkedHashMap");
            }
        }
        out.removeIf(DependencyContextBuilder::isJavaLangType);
        return new ArrayList<>(out);
    }

    private static boolean hasAccessibleConstructor(DependencyModel m) {
        if (!(m.owner instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) m.owner;
        if (cd.isInterface()) {
            return false;
        }
        List<ConstructorDeclaration> ctors = cd.getConstructors();
        if (ctors.isEmpty()) {
            return true;
        }
        return ctors.stream().anyMatch(DependencyContextBuilder::isAccessibleFromSamePackage);
    }

    private static String bestConstructorArgs(DependencyModel m) {
        if (!(m.owner instanceof ClassOrInterfaceDeclaration)) {
            return "";
        }
        List<ConstructorDeclaration> ctors = ((ClassOrInterfaceDeclaration) m.owner).getConstructors().stream()
                .filter(DependencyContextBuilder::isAccessibleFromSamePackage)
                .sorted(Comparator.comparingInt(c -> c.getParameters().size()))
                .collect(Collectors.toList());
        if (ctors.isEmpty()) {
            return "";
        }
        List<String> args = new ArrayList<>();
        for (Parameter p : ctors.get(0).getParameters()) {
            args.add(exampleValueForType(p.getType().asString()));
        }
        return String.join(", ", args);
    }

    private static String exampleValueForType(String type) {
        String t = simpleType(type);
        switch (t) {
            case "boolean": return "false";
            case "byte": return "(byte) 0";
            case "short": return "(short) 0";
            case "int": return "0";
            case "long": return "0L";
            case "float": return "0.0f";
            case "double": return "0.0";
            case "char": return "'x'";
            case "String":
            case "CharSequence": return "\"x\"";
            case "Appendable": return "new StringBuilder()";
            default:
                if (t.endsWith("[]")) return "new " + t.substring(0, t.length() - 2) + "[0]";
                return "null";
        }
    }

    private static String constructorSignature(ConstructorDeclaration ctor) {
        String params = ctor.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
        return ctor.getNameAsString() + "(" + params + ")";
    }

    private static boolean isAccessibleFromSamePackage(NodeWithModifiers<?> n) {
        return !hasModifier(n, Modifier.Keyword.PRIVATE);
    }

    private static String accessOf(NodeWithModifiers<?> n) {
        if (hasModifier(n, Modifier.Keyword.PUBLIC)) return "public";
        if (hasModifier(n, Modifier.Keyword.PROTECTED)) return "protected";
        if (hasModifier(n, Modifier.Keyword.PRIVATE)) return "private";
        return "package-private";
    }

    private static boolean hasModifier(NodeWithModifiers<?> n, Modifier.Keyword kw) {
        return n.getModifiers().stream().anyMatch(m -> m.getKeyword() == kw);
    }

    private static String classPathOf(TypeDeclaration<?> td) {
        LinkedList<String> names = new LinkedList<>();
        Node n = td;
        while (n instanceof TypeDeclaration<?>) {
            TypeDeclaration<?> t = (TypeDeclaration<?>) n;
            names.addFirst(t.getNameAsString());
            Optional<Node> p = t.getParentNode();
            if (p.isEmpty() || !(p.get() instanceof TypeDeclaration<?>)) {
                break;
            }
            n = p.get();
        }
        return String.join(".", names);
    }

    private static List<String> displayCallChainItems(String s) {
        if (s == null || s.isBlank()) return List.of();
        return splitCallChain(s).stream()
                .map(PromptSignatureFormatter::callChain)
                .collect(Collectors.toList());
    }

    private static List<String> splitCallChain(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split("\\s*->\\s*"))
                .map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String simpleType(String raw) {
        String t = canonicalType(raw, false, false);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static String canonicalType(String raw, boolean varArgs, boolean stripPackage) {
        if (raw == null) return "";
        String t = raw.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        t = stripGenerics(t).replaceAll("\\s+", "").replace('$', '.');
        if (varArgs && !t.endsWith("[]")) {
            t = t.replace("...", "") + "[]";
        }
        if (stripPackage) {
            int dims = 0;
            while (t.endsWith("[]")) {
                dims++;
                t = t.substring(0, t.length() - 2);
            }
            int dot = t.lastIndexOf('.');
            if (dot >= 0) t = t.substring(dot + 1);
            for (int i = 0; i < dims; i++) t += "[]";
        }
        return t;
    }

    private static String stripGenerics(String type) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') { depth++; continue; }
            if (c == '>') { depth--; continue; }
            if (depth == 0) out.append(c);
        }
        return out.toString();
    }

    private static boolean isPrimitiveOrVoid(String t) {
        String s = simpleType(t);
        return Set.of("byte", "short", "int", "long", "float", "double", "char", "boolean", "void").contains(s);
    }

    private static boolean isJdkType(String q) {
        return q.startsWith("java.") || q.startsWith("javax.") || q.startsWith("jdk.") || isJavaLangSimple(q);
    }

    private static boolean isJavaLangType(String q) {
        return q != null && (q.startsWith("java.lang.") || isJavaLangSimple(q));
    }

    private static boolean isJavaLangSimple(String q) {
        String s = simpleType(q);
        return Set.of("String", "Object", "Class", "Throwable", "Exception", "RuntimeException",
                "Error", "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double",
                "Character", "CharSequence", "Appendable", "StringBuilder", "StringBuffer", "Iterable").contains(s);
    }

    private static Map<String, Object> commented(String comment, Object item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("item", item);
        return m;
    }

    private static Map<String, Object> commentedList(String comment, List<?> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("items", items == null ? List.of() : items);
        return m;
    }

    private static Map<String, Object> commentedItems(String comment, List<?> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("items", items == null ? List.of() : items);
        return m;
    }

    private static List<String> splitItems(String s) {
        if (s == null || s.trim().isEmpty()) return List.of();
        return Arrays.stream(s.split("\\s*\\|\\|\\s*"))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    private static final class DependencyModel {
        String packageName;
        String testPackage;
        TypeDeclaration<?> owner;
        CallableDeclaration<?> callable;
        ImportIndex importIndex;
        Context ctx;
    }

    private static final class ImportIndex {
        final String packageName;
        final Set<String> imports = new LinkedHashSet<>();
        final Map<String, String> bySimple = new LinkedHashMap<>();

        private ImportIndex(String packageName) {
            this.packageName = packageName == null ? "" : packageName;
        }

        static ImportIndex from(CompilationUnit cu, String packageName) {
            ImportIndex idx = new ImportIndex(packageName);
            cu.getImports().forEach(im -> {
                if (im.isAsterisk()) {
                    idx.imports.add(im.getNameAsString() + ".*");
                } else {
                    String q = im.getNameAsString();
                    idx.imports.add(q);
                    int dot = q.lastIndexOf('.');
                    idx.bySimple.put(dot >= 0 ? q.substring(dot + 1) : q, q);
                }
            });
            return idx;
        }

        String qualifyType(String raw) {
            if (raw == null || raw.isBlank()) return "";
            String t = stripGenerics(raw).replace("...", "[]").replace('$', '.').trim();
            while (t.endsWith("[]")) t = t.substring(0, t.length() - 2);
            if (t.contains(".")) return t;
            if (isJavaLangSimple(t)) return "java.lang." + t;
            String imported = bySimple.get(t);
            if (imported != null) return imported;
            if (isPrimitiveOrVoid(t)) return t;
            return packageName.isEmpty() ? t : packageName + "." + t;
        }

        boolean isProjectType(String q) {
            return !packageName.isEmpty() && q.startsWith(packageName.substring(0, packageName.indexOf('.') > 0 ? packageName.indexOf('.') : packageName.length()));
        }
    }

    private static final class Sig {
        final boolean constructor;
        final String returnType;
        final String name;
        final List<String> params;

        Sig(boolean constructor, String returnType, String name, List<String> params) {
            this.constructor = constructor;
            this.returnType = returnType;
            this.name = name;
            this.params = params;
        }

        static Sig parse(String sig) {
            int lp = sig.indexOf('(');
            int rp = sig.lastIndexOf(')');
            if (lp < 0 || rp < lp) throw new IllegalArgumentException("Bad signature: " + sig);
            int us = sig.indexOf('_');
            String inside = sig.substring(lp + 1, rp).trim();
            List<String> params = inside.isEmpty() ? List.of()
                    : Arrays.stream(inside.split(","))
                    .map(s -> canonicalType(s.trim(), false, true))
                    .collect(Collectors.toList());
            if (us > 0 && us < lp) {
                return new Sig(false,
                        canonicalType(sig.substring(0, us), false, true),
                        sig.substring(us + 1, lp).trim(),
                        params);
            }
            return new Sig(true, null, sig.substring(0, lp).trim(), params);
        }
    }
}
