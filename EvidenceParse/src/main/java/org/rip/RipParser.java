package org.rip;

import java.util.*;
import java.util.stream.Collectors;

import static org.astjimple.MethodContent.*;
import static org.rip.RipExtractor.*;

import org.astjimple.*;
import org.graph.ASTVisualizer;
import org.graph.CFGVisualizer;
import org.graph.DFGVisualizer;
import org.model.Bundle;
import org.model.DFG;
import org.model.Info.InfoItem;
import org.utils.DotToImageConverter;
import org.model.MutationConfig;
import org.utils.PathSanitizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static org.astjimple.AstToJimpleBridge.*;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

import java.io.File;
import java.nio.file.Paths;

public class RipParser {
    // ==== Main input (examples consistent with prompts, can also pass from
    // main) ====
    private String mID = "m6"; // Mutant ID
    private String OID = "p"; // Original ID
    private String Operator = "COR"; // operator
    private String Diff = "bucket256(sample) == (targetBucket & 0xFF) && sample.getKey() < min && sample.getValue() > 0"; // Differing
                                                                                                                          // statement
    private String SRC_FILE_P = "src/main/java/demo/origin/Bucket.java"; // Source code of p
    private String SRC_FILE_M = "src/main/java/demo/" + mID + "/Bucket.java"; // Source code of m

    private String CLASS_NAME_P = "Bucket"; // Fully qualified class name of p (include package if any)
    private String CLASS_NAME_M = "Bucket"; // Fully qualified class name of m (usually same)
    private String METHOD_NAME_SUBSTR = "double_getSupportLowerBound(double,int)"; // Only analyze methods whose names
                                                                                   // contain this
    // substring

    // Test-generation entry metadata. METHOD_NAME_SUBSTR must remain the real
    // mutation method A; TEST_ENTRY_METHOD_SUBSTR is the callable public entry B
    // that generated unit tests should target.
    private String TEST_ENTRY_CLASS_NAME_P = CLASS_NAME_P;
    private String TEST_ENTRY_CLASS_NAME_M = CLASS_NAME_M;
    private String TEST_ENTRY_METHOD_SUBSTR = METHOD_NAME_SUBSTR;
    private String TEST_ENTRY_KIND = "DIRECT_OR_UNRESOLVED";
    private String TEST_CALL_CHAIN = "";
    private String TEST_ENTRY_NOTES = "";
    private String TEST_GENERATION_PACKAGE = "";
    private boolean USE_REFLECTION_FALLBACK = false;

    // Receiver construction metadata for callable test entry B.
    // DependencyContextBuilder
    // can infer these again from source, but these fields allow resolver decisions
    // to be
    // persisted into output.json when available.
    private String TEST_ENTRY_OWNER_KIND = "";
    private boolean TEST_ENTRY_OWNER_ABSTRACT = false;
    private boolean TEST_ENTRY_OWNER_INTERFACE = false;
    private boolean TEST_ENTRY_OWNER_INSTANTIABLE = false;
    private String TEST_RECEIVER_STRATEGY = "";
    private String TEST_RECEIVER_CONSTRUCTION = "";
    private String TEST_RECEIVER_NOTES = "";
    private String ENTRY_INVOCATION_KIND = "";
    private String TEST_RECEIVER_RUNTIME_CLASS = "";
    private String TEST_RECEIVER_RUNTIME_SOOT_CLASS = "";
    private String TEST_RECEIVER_DECLARING_CLASS = "";
    private String TEST_RECEIVER_DISPATCH_TARGET = "";
    private boolean TEST_RECEIVER_DISPATCHES_TO_MUTATION = false;
    private boolean TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION = false;
    private String TEST_RECEIVER_SETUP_TEMPLATE = "";
    private String TEST_RECEIVER_INVOCATION_TEMPLATE = "";
    private String TEST_RECEIVER_RESOLUTION_REASON = "";
    private String TEST_RECEIVER_FACTORY_METHOD = "";
    private String TEST_RECEIVER_BUILDER_CLASS = "";
    private String TEST_RECEIVER_BUILDER_TERMINAL_METHOD = "";
    private String TEST_RECEIVER_BUILDER_SETUP_CHAIN = "";
    private String TEST_RECEIVER_ANTI_PATTERNS = "";
    private String AVAILABLE_PUBLIC_METHODS = "";
    private String AVAILABLE_SETUP_METHODS = "";
    private String STATE_SETUP_PLAN = "";
    private String OBSERVABLE_PLAN_KIND = "";
    private String OBSERVABLE_SETUP = "";
    private String OBSERVABLE_CALL = "";
    private String OBSERVABLE_EXPECTED_ORIGINAL = "";
    private String OBSERVABLE_REASON = "";
    private String OBSERVABLE_ANTI_PATTERNS = "";
    private String BRANCH_REACHABILITY_KIND = "";
    private String BRANCH_REACHABILITY_CONDITION = "";
    private String BRANCH_REACHABILITY_SETUP = "";
    private String BRANCH_REACHABILITY_REASON = "";
    private String ABSTRACT_METHODS_TO_IMPLEMENT = "";
    private String ALLOWED_OVERRIDES = "";
    private String FORBIDDEN_OVERRIDES = "";
    private String TEST_STUB_CLASS_TEMPLATE = "";
    private String TEST_STUB_CONSTRUCTOR_TEMPLATE = "";
    private boolean SKIP_TEST_GENERATION = false;
    private String SKIP_REASON = "";

    private String CLASSES_DIR_P = "target/classes/demo/" + "origin"; // p's .class directory
    private String CLASSES_DIR_M = "target/classes/demo/" + mID; // m's .class directory

    // Output path for strategy dataset
    private String outputPath = CLASSES_DIR_M + "/graph/output.json"; // Output JSON path
    private String codeEmbeddingPath = CLASSES_DIR_M + "/graph/codeEmbedding.json"; // Fine-tuned code embedding
                                                                                    // strategy
    private String zeroShotPath = CLASSES_DIR_M + "/graph/zeroShot.json"; // zero-shot strategy
    private String fewShotPath = CLASSES_DIR_M + "/graph/fewShot.json"; // few-shot strategy
    private String fineTuningPath = CLASSES_DIR_M + "/graph/fineTuning.json"; // fine-tuning strategy

    private List<String> SPEC_OBSERVED = Arrays.asList("return", "exception", "state");
    private List<String> DOMAIN_ASSUMPTIONS = List.of();

    private List<ChangeRange> ranges;

    /**
     * Only cache origin-side fields that are independent of the current mutant.
     * Do NOT cache affected/paths/cpg here, because they depend on the current
     * ranges
     * computed from the current original-mutant pair.
     */
    private static final Map<String, OriginStaticSnapshot> ORIGIN_STATIC_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Safe text cache for method source extraction.
     * The key includes the concrete file path, so using it on mutant files is safe.
     */
    private static final Map<String, String> METHOD_TEXT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public RipParser() {
    }

    public RipParser(MutationConfig config) {
        mID = config.operator.replaceAll("\\D+", "");
        OID = "";
        Operator = config.operator.replaceAll("_\\d+$", "");
        Diff = config.mutationStatement;

        String mutationOwnerClass = firstNonBlank(
                config.mutationClassName,
                config.className,
                config.classNameF);
        String mutationOwnerSootClass = firstNonBlank(
                config.mutationSootClassName,
                sourceClassPathToSootBinaryName(mutationOwnerClass));
        // Some spreadsheets store the method-level directory as filepath, e.g.
        // .../traditional_mutants/ConstantPool(java.io.DataInput), while the
        // actual mutant source/class files are under .../AOIS_5.  Normalize it
        // before deriving source files, class directories, and output.json path.
        config.filepath = normalizeMutantDirectory(
                config.filepath,
                config.operator,
                mutationOwnerClass,
                config.classNameF,
                config.className);
        String originalDir = resolveOriginalDirectory(config.filepath);
        String sourceFileOwnerClass = firstNonBlank(
                config.classNameF,
                config.className,
                mutationOwnerClass);

        SRC_FILE_P = resolveJavaSourceFile(originalDir, sourceFileOwnerClass, mutationOwnerClass);
        SRC_FILE_M = resolveJavaSourceFile(config.filepath, config.className, sourceFileOwnerClass, mutationOwnerClass);

        // classNameF is often the public file owner.  For package-private sibling
        // classes in the same file, e.g. ClassNameReader.java containing
        // package-private ConstantPool, the executable body is in config.className /
        // config.mutationClassName, not in classNameF.  Use the real mutation owner
        // for Soot/Jimple and method extraction, while SRC_FILE_* may still point to
        // the public file owner source file.
        CLASS_NAME_P = mutationOwnerSootClass;
        CLASS_NAME_M = mutationOwnerSootClass;

        // Keep this as the real mutation method A. The diff, affected Jimple,
        // CFG/DFG and RIP evidence are still computed for this method.
        METHOD_NAME_SUBSTR = config.methodName;

        // Separately store the test-generation entry B resolved by DataGenerator.
        TEST_ENTRY_CLASS_NAME_P = (config.testEntryClassName == null || config.testEntryClassName.isBlank())
                ? CLASS_NAME_P
                : config.testEntryClassName;
        TEST_ENTRY_CLASS_NAME_M = TEST_ENTRY_CLASS_NAME_P;
        TEST_ENTRY_METHOD_SUBSTR = (config.testEntryMethodName == null || config.testEntryMethodName.isBlank())
                ? METHOD_NAME_SUBSTR
                : config.testEntryMethodName;
        TEST_ENTRY_KIND = (config.testEntryKind == null || config.testEntryKind.isBlank())
                ? "DIRECT_OR_UNRESOLVED"
                : config.testEntryKind;
        TEST_CALL_CHAIN = config.testCallChain == null ? "" : config.testCallChain;
        TEST_ENTRY_NOTES = config.testEntryNotes == null ? "" : config.testEntryNotes;
        TEST_GENERATION_PACKAGE = config.testGenerationPackage == null ? config.packageName
                : config.testGenerationPackage;
        if (TEST_GENERATION_PACKAGE == null) {
            TEST_GENERATION_PACKAGE = "";
        }
        USE_REFLECTION_FALLBACK = config.useReflectionFallback;

        TEST_ENTRY_OWNER_KIND = config.testEntryOwnerKind == null ? "" : config.testEntryOwnerKind;
        TEST_ENTRY_OWNER_ABSTRACT = config.testEntryOwnerAbstract;
        TEST_ENTRY_OWNER_INTERFACE = config.testEntryOwnerInterface;
        TEST_ENTRY_OWNER_INSTANTIABLE = config.testEntryOwnerInstantiable;
        TEST_RECEIVER_STRATEGY = config.testReceiverStrategy == null ? "" : config.testReceiverStrategy;
        TEST_RECEIVER_CONSTRUCTION = config.testReceiverConstruction == null ? "" : config.testReceiverConstruction;
        TEST_RECEIVER_NOTES = config.testReceiverNotes == null ? "" : config.testReceiverNotes;
        ENTRY_INVOCATION_KIND = config.entryInvocationKind == null ? "" : config.entryInvocationKind;
        TEST_RECEIVER_RUNTIME_CLASS = config.testReceiverRuntimeClassName == null ? "" : config.testReceiverRuntimeClassName;
        TEST_RECEIVER_RUNTIME_SOOT_CLASS = config.testReceiverRuntimeSootClassName == null ? "" : config.testReceiverRuntimeSootClassName;
        TEST_RECEIVER_DECLARING_CLASS = config.testReceiverDeclaringClassName == null ? "" : config.testReceiverDeclaringClassName;
        TEST_RECEIVER_DISPATCH_TARGET = config.testReceiverDispatchTarget == null ? "" : config.testReceiverDispatchTarget;
        TEST_RECEIVER_DISPATCHES_TO_MUTATION = config.testReceiverDispatchesToMutationMethod;
        TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION = config.testReceiverSubclassOverridesMutationMethod;
        TEST_RECEIVER_SETUP_TEMPLATE = config.testReceiverSetupTemplate == null ? "" : config.testReceiverSetupTemplate;
        TEST_RECEIVER_INVOCATION_TEMPLATE = config.testReceiverInvocationTemplate == null ? "" : config.testReceiverInvocationTemplate;
        TEST_RECEIVER_RESOLUTION_REASON = config.testReceiverResolutionReason == null ? "" : config.testReceiverResolutionReason;
        TEST_RECEIVER_FACTORY_METHOD = config.testReceiverFactoryMethod == null ? "" : config.testReceiverFactoryMethod;
        TEST_RECEIVER_BUILDER_CLASS = config.testReceiverBuilderClassName == null ? "" : config.testReceiverBuilderClassName;
        TEST_RECEIVER_BUILDER_TERMINAL_METHOD = config.testReceiverBuilderTerminalMethod == null ? "" : config.testReceiverBuilderTerminalMethod;
        TEST_RECEIVER_BUILDER_SETUP_CHAIN = config.testReceiverBuilderSetupChain == null ? "" : config.testReceiverBuilderSetupChain;
        TEST_RECEIVER_ANTI_PATTERNS = config.testReceiverAntiPatterns == null ? "" : config.testReceiverAntiPatterns;
        AVAILABLE_PUBLIC_METHODS = config.availablePublicMethods == null ? "" : config.availablePublicMethods;
        AVAILABLE_SETUP_METHODS = config.availableSetupMethods == null ? "" : config.availableSetupMethods;
        STATE_SETUP_PLAN = config.stateSetupPlan == null ? "" : config.stateSetupPlan;
        OBSERVABLE_PLAN_KIND = config.observablePlanKind == null ? "" : config.observablePlanKind;
        OBSERVABLE_SETUP = config.observableSetup == null ? "" : config.observableSetup;
        OBSERVABLE_CALL = config.observableCall == null ? "" : config.observableCall;
        OBSERVABLE_EXPECTED_ORIGINAL = config.observableExpectedOriginal == null ? "" : config.observableExpectedOriginal;
        OBSERVABLE_REASON = config.observableReason == null ? "" : config.observableReason;
        OBSERVABLE_ANTI_PATTERNS = config.observableAntiPatterns == null ? "" : config.observableAntiPatterns;
        BRANCH_REACHABILITY_KIND = config.branchReachabilityKind == null ? "" : config.branchReachabilityKind;
        BRANCH_REACHABILITY_CONDITION = config.branchReachabilityCondition == null ? "" : config.branchReachabilityCondition;
        BRANCH_REACHABILITY_SETUP = config.branchReachabilitySetup == null ? "" : config.branchReachabilitySetup;
        BRANCH_REACHABILITY_REASON = config.branchReachabilityReason == null ? "" : config.branchReachabilityReason;
        ABSTRACT_METHODS_TO_IMPLEMENT = config.abstractMethodsToImplement == null ? "" : config.abstractMethodsToImplement;
        ALLOWED_OVERRIDES = config.allowedOverrides == null ? "" : config.allowedOverrides;
        FORBIDDEN_OVERRIDES = config.forbiddenOverrides == null ? "" : config.forbiddenOverrides;
        TEST_STUB_CLASS_TEMPLATE = config.testStubClassTemplate == null ? "" : config.testStubClassTemplate;
        TEST_STUB_CONSTRUCTOR_TEMPLATE = config.testStubConstructorTemplate == null ? "" : config.testStubConstructorTemplate;
        SKIP_TEST_GENERATION = config.skipTestGeneration;
        SKIP_REASON = config.skipReason == null ? "" : config.skipReason;

        String fileDirP = PathSanitizer.path(config.filepath).getParent().getParent().getParent().toString();
        CLASSES_DIR_P = fileDirP + "/original/";
        CLASSES_DIR_M = config.filepath + "/";

        // Output path for strategy dataset
        outputPath = CLASSES_DIR_M + "/graph/output.json"; // Output JSON path
        codeEmbeddingPath = CLASSES_DIR_M + "/graph/codeEmbedding.json"; // Fine-tuned code embedding
                                                                         // strategy
        zeroShotPath = CLASSES_DIR_M + "/graph/zeroShot.json"; // zero-shot strategy
        fewShotPath = CLASSES_DIR_M + "/graph/fewShot.json"; // few-shot strategy
        fineTuningPath = CLASSES_DIR_M + "/graph/fineTuning.json"; // fine-tuning strategy
    }

    public static void main(String[] args) throws Exception {
        RipParser parser = new RipParser();
        String json = parser.analyzePairToJson();
        System.out.println(json);
    }

    public void test() throws Exception {
        String json = analyzePairToJson();
        System.out.println(json);
    }

    public String analyzePairToJson() throws Exception {
        long start = System.nanoTime();
        Bundle bundle = buildCommonInfo();
        long end = System.nanoTime();
        double duration1Ms = (end - start) / 1_000_000.0;
        // System.out.println("CommonInfo: " + duration1Ms + " ms");

        start = end;
        buildOriginSide(bundle);
        end = System.nanoTime();
        duration1Ms = (end - start) / 1_000_000.0;
        // System.out.println("OriginSide: " + duration1Ms + " ms");

        start = end;
        buildMutantSide(bundle);
        end = System.nanoTime();
        duration1Ms = (end - start) / 1_000_000.0;
        // System.out.println("MutantSide: " + duration1Ms + " ms");

        Map<String, Object> out = assembleOutput(bundle);

        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(new File(outputPath), out);
        return om.writeValueAsString(out);
    }


    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        String s = className.trim();
        if (s.isEmpty()) {
            return s;
        }
        int dollar = s.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < s.length()) {
            s = s.substring(dollar + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) {
            s = s.substring(dot + 1);
        }
        return s;
    }

    private static String sourceClassPathToSootBinaryName(String sourceClassPath) {
        if (sourceClassPath == null) {
            return "";
        }
        String s = sourceClassPath.trim().replace('$', '.');
        if (s.isEmpty()) {
            return s;
        }
        // In this codebase class names are usually simple source paths without the
        // package.  Convert nested source paths such as StrMatcher.CharMatcher into
        // the binary/Soot name StrMatcher$CharMatcher.  Top-level names remain as-is.
        int firstDot = s.indexOf('.');
        if (firstDot < 0) {
            return s;
        }
        return s.substring(0, firstDot) + "$" + s.substring(firstDot + 1).replace('.', '$');
    }

    private static String normalizeMutantDirectory(String dir, String operator, String... classCandidates) {
        if (dir == null || dir.trim().isEmpty()) {
            return dir;
        }
        java.nio.file.Path base = PathSanitizer.path(dir);
        if (base.getFileName() != null && base.getFileName().toString().endsWith(".java")) {
            base = base.getParent();
        }

        // Already points at the concrete operator directory, e.g. .../AOIS_5.
        if (containsAnyJavaFile(base)) {
            return normalizePath(base);
        }

        // Excel rows sometimes point at the method-level directory.  Append the
        // operator id when the operator subdirectory exists.
        String op = operator == null ? "" : operator.trim();
        if (!op.isEmpty()) {
            java.nio.file.Path byOperator = base.resolve(op);
            if (java.nio.file.Files.isDirectory(byOperator)) {
                return normalizePath(byOperator);
            }
        }

        // Fallback: choose a child directory that declares the requested class.
        if (classCandidates != null) {
            try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(base)) {
                java.util.List<java.nio.file.Path> dirs = st
                        .filter(java.nio.file.Files::isDirectory)
                        .collect(java.util.stream.Collectors.toList());
                for (java.nio.file.Path d : dirs) {
                    for (String c : classCandidates) {
                        String simple = simpleName(c);
                        if (!simple.isEmpty() && findJavaFileDeclaringType(d, simple) != null) {
                            return normalizePath(d);
                        }
                    }
                    if (containsAnyJavaFile(d)) {
                        // Keep as last-chance within the loop; most mutant operator dirs
                        // contain exactly the generated source file.
                        return normalizePath(d);
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return normalizePath(base);
    }

    private static String resolveOriginalDirectory(String mutantDir) {
        if (mutantDir == null || mutantDir.trim().isEmpty()) {
            return mutantDir;
        }
        java.nio.file.Path p = PathSanitizer.path(mutantDir);
        for (java.nio.file.Path cur = p; cur != null; cur = cur.getParent()) {
            java.nio.file.Path candidate = cur.resolve("original");
            if (java.nio.file.Files.isDirectory(candidate)) {
                return normalizePath(candidate);
            }
        }
        // Preserve old layout assumption as a deterministic fallback.
        try {
            return normalizePath(PathSanitizer.path(mutantDir).getParent().getParent().getParent().resolve("original"));
        } catch (Exception ex) {
            return mutantDir;
        }
    }

    private static boolean containsAnyJavaFile(java.nio.file.Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return false;
        }
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(dir)) {
            return st.anyMatch(p -> p.getFileName().toString().endsWith(".java"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizePath(java.nio.file.Path p) {
        return PathSanitizer.normalizeForJson(p);
    }

    private static String resolveJavaSourceFile(String dir, String... classCandidates) {
        if (dir == null || dir.isBlank()) {
            return dir;
        }
        java.nio.file.Path base = PathSanitizer.path(dir);

        // 1) Try the conventional file names first.  This preserves the old behavior
        // for normal one-public-class-one-file cases.
        if (classCandidates != null) {
            for (String c : classCandidates) {
                String simple = simpleName(c);
                if (simple.isEmpty()) {
                    continue;
                }
                java.nio.file.Path candidate = base.resolve(simple + ".java");
                if (java.nio.file.Files.exists(candidate)) {
                    return candidate.toString();
                }
            }
        }

        // 2) If the target type is a package-private sibling class, its source file
        // is named after another public class.  Search Java files in the mutant/origin
        // directory and choose the one that declares the requested type.
        if (classCandidates != null) {
            for (String c : classCandidates) {
                String simple = simpleName(c);
                if (simple.isEmpty()) {
                    continue;
                }
                java.nio.file.Path found = findJavaFileDeclaringType(base, simple);
                if (found != null) {
                    return found.toString();
                }
            }
        }

        // 3) Last resort: many mutant directories contain exactly one Java file.
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(base)) {
            java.util.List<java.nio.file.Path> javaFiles = st
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
            if (javaFiles.size() == 1) {
                return javaFiles.get(0).toString();
            }
        } catch (Exception ignored) {
            // fall through to deterministic fallback
        }

        // Keep a deterministic path for the final error message if nothing exists.
        String fallback = classCandidates == null || classCandidates.length == 0
                ? "Unknown"
                : simpleName(firstNonBlank(classCandidates));
        return base.resolve(fallback + ".java").toString();
    }

    private static java.nio.file.Path findJavaFileDeclaringType(java.nio.file.Path dir, String simpleTypeName) {
        if (dir == null || simpleTypeName == null || simpleTypeName.isBlank()) {
            return null;
        }
        if (!java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        java.util.regex.Pattern decl = java.util.regex.Pattern.compile(
                "(?m)(?:^|\\s)(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|static\\s+|strictfp\\s+)*"
                        + "(?:class|interface|enum|record)\\s+"
                        + java.util.regex.Pattern.quote(simpleTypeName)
                        + "(?:\\s|<|\\{|extends|implements)");
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(dir)) {
            java.util.List<java.nio.file.Path> javaFiles = st
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
            for (java.nio.file.Path p : javaFiles) {
                try {
                    String text = java.nio.file.Files.readString(p);
                    if (decl.matcher(text).find()) {
                        return p;
                    }
                } catch (Exception ignored) {
                    // try next file
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Bundle buildCommonInfo() throws Exception {
        Bundle bundle = new Bundle();
        bundle.operator.item = Operator;
        bundle.diff.item = Diff;
        bundle.domainAssumptions.items = DOMAIN_ASSUMPTIONS;
        bundle.specObserved.items = SPEC_OBSERVED;
        ranges = DiffWithLineRanges.diffWithLineRangesForMethod(
                SRC_FILE_P,
                SRC_FILE_M,
                CLASS_NAME_P,
                METHOD_NAME_SUBSTR);

        // Some mutation spreadsheets point to an abstract/interface outer method,
        // while the actual changed executable body is in a nested concrete class.
        // Retarget after diff ranges are known, so Soot/Jimple body lookup uses
        // the real owner and signature.  Examples: DateTimeFieldType$Standard...,
        // DurationFieldType$Standard..., InputAccessor$Std, Angle$Deg, etc.
        retargetChangedNestedCallableIfNeeded();

        // ranges = DiffWithLineRanges.diffWithLineRanges(SRC_FILE_P, SRC_FILE_M);
        bundle.jimpleChanges.items = ranges.stream().map(ChangeRange::toString).collect(Collectors.toList());

        return bundle;
    }


    private void retargetChangedNestedCallableIfNeeded() {
        try {
            Optional<SourceOwnerResolver.Resolution> opt = SourceOwnerResolver.resolveChangedCallableOwner(
                    SRC_FILE_M,
                    CLASS_NAME_M,
                    METHOD_NAME_SUBSTR,
                    ranges);
            if (opt.isEmpty()) {
                return;
            }
            SourceOwnerResolver.Resolution r = opt.get();
            String oldClassP = CLASS_NAME_P;
            String oldClassM = CLASS_NAME_M;
            String oldMethod = METHOD_NAME_SUBSTR;

            CLASS_NAME_P = r.ownerBinaryName;
            CLASS_NAME_M = r.ownerBinaryName;
            METHOD_NAME_SUBSTR = r.callableSignature;

            // If B was the same as A before retargeting, keep B aligned with the
            // real executable mutation method.  This prevents EntryLiftedRIP from
            // trying to read an abstract/interface body.
            if (TEST_ENTRY_CLASS_NAME_P == null || TEST_ENTRY_CLASS_NAME_P.isBlank()
                    || TEST_ENTRY_CLASS_NAME_P.equals(oldClassP)
                    || TEST_ENTRY_CLASS_NAME_P.equals(oldClassM)) {
                TEST_ENTRY_CLASS_NAME_P = CLASS_NAME_P;
                TEST_ENTRY_CLASS_NAME_M = CLASS_NAME_M;
            }
            if (TEST_ENTRY_METHOD_SUBSTR == null || TEST_ENTRY_METHOD_SUBSTR.isBlank()
                    || TEST_ENTRY_METHOD_SUBSTR.equals(oldMethod)) {
                TEST_ENTRY_METHOD_SUBSTR = METHOD_NAME_SUBSTR;
            }
            TEST_ENTRY_NOTES = appendNote(TEST_ENTRY_NOTES,
                    "retargeted mutation owner by changed source line: " + r.reason);
        } catch (Exception ignored) {
            // Retargeting is best-effort; keep legacy behavior if anything goes wrong.
        }
    }

    private static String appendNote(String old, String note) {
        if (note == null || note.isBlank()) {
            return old == null ? "" : old;
        }
        if (old == null || old.isBlank()) {
            return note;
        }
        return old + " | " + note;
    }

    private void buildOriginSide(Bundle bundle) throws Exception {
        String cacheKey = originStaticCacheKey();
        OriginStaticSnapshot cached = ORIGIN_STATIC_CACHE.get(cacheKey);

        Body body;

        if (cached == null) {
            cached = new OriginStaticSnapshot();
            cached.id = OID;
            cached.content = cachedExtractMethod(SRC_FILE_P, CLASS_NAME_P, METHOD_NAME_SUBSTR, true);

            AstToJimpleBridge.initSoot(CLASSES_DIR_P);
            body = AstToJimpleBridge.getBody(CLASS_NAME_P, METHOD_NAME_SUBSTR);
            cached.ir = body.toString();

            ORIGIN_STATIC_CACHE.put(cacheKey, cached);
        } else {
            AstToJimpleBridge.initSoot(CLASSES_DIR_P);
            body = AstToJimpleBridge.getBody(CLASS_NAME_P, METHOD_NAME_SUBSTR);
        }

        bundle.origin.item.id.item = cached.id;
        bundle.origin.item.content.item = cached.content;
        bundle.origin.item.IR.item = cached.ir;

        // These fields depend on the current pair-specific ranges, so they must be
        // recomputed for each mutant.
        List<Unit> affected = analyzeAffectedUnits(body, ranges);
        bundle.origin.item.Affected.items = formatAffectedUnits(affected);

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, ranges, affected);
        bundle.origin.item.Paths.items = validePaths.stream()
                .map(AstToJimpleBridge::pathToString)
                .collect(Collectors.toList());

        for (List<Unit> path : validePaths) {
            bundle.origin.item.CPG.add(buildInfoItem(path, body, affected));
        }
    }

    private void buildMutantSide(Bundle bundle) throws Exception {
        bundle.mutant.item.id.item = mID;
        bundle.mutant.item.content.item = cachedExtractMethod(SRC_FILE_M, CLASS_NAME_M, METHOD_NAME_SUBSTR, true);

        AstToJimpleBridge.initSoot(CLASSES_DIR_M);
        Body body = AstToJimpleBridge.getBody(CLASS_NAME_M, METHOD_NAME_SUBSTR);
        bundle.mutant.item.IR.item = body.toString();

        List<Unit> affected = analyzeAffectedUnits(body, ranges);
        bundle.mutant.item.Affected.items = formatAffectedUnits(affected);

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, ranges, affected);
        bundle.mutant.item.Paths.items = validePaths.stream().map(AstToJimpleBridge::pathToString)
                .collect(Collectors.toList());

        for (List<Unit> path : validePaths) {
            InfoItem item = buildInfoItem(path, body, affected);
            bundle.mutant.item.CPG.add(item);
        }
        // saveCPG(CLASS_NAME_M, METHOD_NAME_SUBSTR, CLASSES_DIR_M);
    }

    // ===== Construct InfoItem based on affected path (textual CFG/DFG) =====
    private InfoItem buildInfoItem(List<Unit> path, Body body, List<Unit> affectedList) {
        InfoItem item = new InfoItem();
        item.Path = pathToString(path);

        UnitGraph ug = new ExceptionalUnitGraph(body);
        BlockGraph bg = new BriefBlockGraph(body);
        DominatorsFinder<Unit> dom = new MHGDominatorsFinder<>(ug);
        DominatorsFinder<Unit> pdom = new MHGPostDominatorsFinder<>(ug);

        // Aggregate and deduplicate (affected nodes may be >1)
        LinkedHashSet<String> domSummaries = new LinkedHashSet<>();
        LinkedHashSet<String> pathPreds = new LinkedHashSet<>();
        LinkedHashSet<String> ctrlDeps = new LinkedHashSet<>();

        for (Unit affected : affectedList) {
            Unit mutUnit = locateByText(body, affected.toString());
            if (mutUnit == null)
                continue;

            // ---- CFG.dom: Dominator chain summary for basic blocks ----
            domSummaries.addAll(unitsToBlockSummaries(bg, dom.getDominators(mutUnit)));

            // ---- CFG.path_predicates: If conditions along dominator chain (Top-K) ----
            for (Unit u : dom.getDominators(mutUnit)) {
                if (u instanceof IfStmt) {
                    pathPreds.add(((IfStmt) u).getCondition().toString());
                }
            }

            // ---- CFG.control_deps_out: Control dependencies based on post-dominators ----
            ctrlDeps.addAll(computeControlDepsText(bg, pdom, mutUnit));

            // ---- DFG: Build from the affected point ----
            buildDFG(item, mutUnit, ug, pdom, SPEC_OBSERVED);
        }

        // Write back (clip Top-K to avoid excessive length)
        item.CFG.dom.items = new ArrayList<>(domSummaries);
        item.CFG.path_predicates.items = pathPreds.stream().limit(8).collect(Collectors.toList());
        item.CFG.control_deps_out.items = new ArrayList<>(ctrlDeps);

        return item;
    }

    // ===== Build DFG (defs_at_mut / uses_toward_output / kill_set etc.) =====
    private static void buildDFG(InfoItem item, Unit mutUnit, UnitGraph ug,
            DominatorsFinder<Unit> pdom, List<String> specObserved) {

        SimpleLocalDefs sdefs = new SimpleLocalDefs(ug);
        SimpleLocalUses suses = new SimpleLocalUses(ug, sdefs);

        // ---------- 1) Identify true variables carrying Δ (tracked set) ----------
        LinkedHashSet<String> tracked = new LinkedHashSet<>();

        if (mutUnit instanceof AssignStmt) {
            AssignStmt as = (AssignStmt) mutUnit;
            Value lhs = as.getLeftOp();

            if (lhs instanceof Local && !isTemp((Local) lhs)) {
                // Mutation point directly defines a real variable (e.g., numEntries =
                // numEntries + entries)
                DFG.KV def = new DFG.KV();
                def.var = lhs.toString();
                def.unit = mutUnit.toString();
                item.DFG.defs_point.add(def);
                tracked.add(def.var);
            } else {
                // Mutation point defines a temp var (e.g., $stack5 = neg entries), find next
                // real assignment via uses
                for (UnitValueBoxPair use : suses.getUsesOf(mutUnit)) {
                    if (use.unit instanceof AssignStmt) {
                        Value realLhs = ((AssignStmt) use.unit).getLeftOp();
                        if (realLhs instanceof Local && !isTemp((Local) realLhs)) {
                            DFG.KV def = new DFG.KV();
                            def.var = realLhs.toString();
                            def.unit = use.unit.toString();
                            item.DFG.defs_point.add(def);
                            tracked.add(def.var);
                        }
                    }
                }
            }
        }
        if (tracked.isEmpty()) {
            // For pure control flow changes, may consider pseudo-vars; here we return
            // directly to avoid noise
            return;
        }

        // ---------- 2) uses_toward_output: Forward slice until sink ----------
        Set<Unit> visited = new HashSet<>();
        Deque<Unit> work = new ArrayDeque<>();
        work.add(mutUnit);

        while (!work.isEmpty()) {
            Unit u = work.poll();
            if (!visited.add(u))
                continue;
            if (u instanceof IfStmt && specObserved.contains("return")) {
                if (guardsReturn(u, ug, specObserved)) {
                    // Locals used in condition and tracked (carrying Δ) → count as observable usage
                    // at return (via control)
                    for (ValueBox vb : u.getUseBoxes()) {
                        Value v = vb.getValue();
                        if (v instanceof Local) {
                            String name = v.toString();
                            if (tracked.contains(name)) {
                                DFG.SinkUse su = new DFG.SinkUse();
                                su.var = name;
                                su.unit = u.toString();
                                su.sink = "return";
                                item.DFG.uses_toward_output.add(su);
                            }
                        }
                    }
                }
            }

            if (isSink(u, specObserved)) {
                // Only record tracked variable usages at sink
                for (ValueBox vb : u.getUseBoxes()) {
                    Value v = vb.getValue();
                    if (v instanceof Local) {
                        String name = v.toString();
                        if (tracked.contains(name)) {
                            DFG.SinkUse use = new DFG.SinkUse();
                            use.var = name;
                            use.unit = u.toString();
                            use.sink = sinkKind(u);
                            item.DFG.uses_toward_output.add(use);
                        }
                    }
                }
                continue; // Stop expanding branch once sink is reached
            }

            for (UnitValueBoxPair p : suses.getUsesOf(u)) {
                work.add(p.unit);
            }
        }

        // --- 3) kill_set: Overwriting definitions of tracked vars before any sink ---
        LinkedHashSet<String> killSig = new LinkedHashSet<>();
        for (Unit u : ug) {
            if (!(u instanceof AssignStmt))
                continue;
            Value lhs = ((AssignStmt) u).getLeftOp();
            if (!(lhs instanceof Local))
                continue;
            String var = lhs.toString();
            if (isTemp((Local) lhs))
                continue; // Skip temporaries
            if (!tracked.contains(var))
                continue; // Focus only on Δ-carrying vars
            if (u == mutUnit)
                continue; // Don’t count mutation point itself as a kill

            boolean pdByAnySink = postdominatedByAnySink(u, ug, pdom, specObserved);
            String sig = var + "|" + u.toString() + "|" + pdByAnySink;
            if (killSig.add(sig)) {
                DFG.Kill k = new DFG.Kill();
                k.var = var;
                k.unit = u.toString();
                k.postdominated_by_sink = pdByAnySink;
                item.DFG.kill_set.add(k);
            }
        }

        // ---------- 4) Deduplicate (defs / uses) ----------
        dedupDFGLists(item);
    }

    // ====== Utilities ======
    private static boolean isTemp(Local l) {
        // return l.getName().startsWith("$");
        return false; // Not filtering yet; allow tracking temporaries
    }

    private static boolean postdominatedByAnySink(Unit u, UnitGraph ug,
            DominatorsFinder<Unit> pdom,
            List<String> specObserved) {
        for (Unit cand : ug) {
            if (isSink(cand, specObserved)) {
                if (pdom.getDominators(u).contains(cand))
                    return true;
            }
        }
        return false;
    }

    private static boolean isSink(Unit u, List<String> specObserved) {
        if (specObserved.contains("return") && u instanceof ReturnStmt)
            return true;
        if (specObserved.contains("exception") && u instanceof ThrowStmt)
            return true;
        // "state" sink depends on project definition (e.g., external state writes); not
        // handled here yet
        return false;
    }

    private static String sinkKind(Unit u) {
        if (u instanceof ReturnStmt)
            return "return";
        if (u instanceof ThrowStmt)
            return "exception";
        return "state";
    }

    // Whether If guards a return: any successor must reach return sink within
    // bounded steps
    private static boolean guardsReturn(Unit ifUnit, UnitGraph ug, List<String> specObserved) {
        if (!(ifUnit instanceof IfStmt))
            return false;
        // First, check if direct successor is return
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (isSink(s, specObserved))
                return true;
        }
        // Otherwise, do shallow forward search (to avoid full graph cost)
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (reachesSinkWithin(s, ug, specObserved, 12))
                return true; // Step count can be adjusted
        }
        return false;
    }

    private static boolean reachesSinkWithin(Unit start, UnitGraph ug,
            List<String> specObserved, int maxSteps) {
        Set<Unit> seen = new HashSet<>();
        Deque<Unit> q = new ArrayDeque<>();
        q.add(start);
        int steps = 0;
        while (!q.isEmpty() && steps++ < maxSteps) {
            Unit cur = q.poll();
            if (!seen.add(cur))
                continue;
            if (isSink(cur, specObserved))
                return true;
            for (Unit nxt : ug.getSuccsOf(cur))
                q.add(nxt);
        }
        return false;
    }

    private static void dedupDFGLists(InfoItem item) {
        // Deduplicate defs
        LinkedHashSet<String> sig = new LinkedHashSet<>();
        item.DFG.defs_point.items.removeIf(kv -> !sig.add(kv.var + "|" + kv.unit));

        // Deduplicate uses
        sig.clear();
        item.DFG.uses_toward_output.items.removeIf(u -> !sig.add(u.var + "|" + u.unit + "|" + u.sink));

        // Deduplicate kill_set
        sig.clear();
        item.DFG.kill_set.items.removeIf(u -> !sig.add(u.var + "|" + u.unit + "|" + u.postdominated_by_sink));
    }

    private static List<String> formatAffectedUnits(List<Unit> units) {
        return units.stream().map(u -> {
            int[] lr = unitLineRange(u);
            return String.format("JIMPLE [%d-%d] %s%n", lr[0], lr[1], u);
        }).collect(Collectors.toList());
    }

    private Map<String, Object> assembleOutput(Bundle bundle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", bundle.operator);
        out.put("Diff", bundle.diff);
        out.put("DomainAssumptions", bundle.domainAssumptions);
        out.put("SpecObserved", bundle.specObserved);
        out.put("JimpleChanges", bundle.jimpleChanges);

        // TestEntry is intentionally not emitted as a top-level field.
        // All prompt-facing entry invocation guidance is embedded in EntryLiftedRIP.

        // DependencyContext is prompt-facing type/receiver context. It is layered:
        // relationship(A-B), detailed testEntryContext(B), lightweight
        // mutationContext(A).
        out.put("DependencyContext", buildDependencyContextOutput());

        // EntryLiftedRIP is Soot/Jimple graph evidence for how B reaches A. It keeps
        // both origin and mutated branches; mutated may reuse origin when B != A.
        out.put("EntryLiftedRIP", buildEntryLiftedRipOutput());

        out.put("origin", bundle.origin);
        out.put("mutated", bundle.mutant);
        return out;
    }

    private Map<String, Object> buildDependencyContextOutput() {
        DependencyContextBuilder.Context ctx = new DependencyContextBuilder.Context();

        // In the current same-file resolver, SRC_FILE_P contains both A and B.
        ctx.originJavaFile = SRC_FILE_P;

        // B: callable test entry selected by MethodEntryResolver.
        ctx.entryClassName = TEST_ENTRY_CLASS_NAME_P;
        ctx.entryMethodSig = TEST_ENTRY_METHOD_SUBSTR;

        // A: real mutation method used for A-side RIP/Jimple evidence.
        ctx.mutationClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;

        ctx.testGenerationPackage = TEST_GENERATION_PACKAGE;
        ctx.testEntryKind = TEST_ENTRY_KIND;
        ctx.useReflectionFallback = USE_REFLECTION_FALLBACK;
        ctx.testCallChain = TEST_CALL_CHAIN;

        ctx.receiverOwnerKind = TEST_ENTRY_OWNER_KIND;
        ctx.receiverOwnerAbstract = TEST_ENTRY_OWNER_ABSTRACT;
        ctx.receiverOwnerInterface = TEST_ENTRY_OWNER_INTERFACE;
        ctx.receiverOwnerInstantiable = TEST_ENTRY_OWNER_INSTANTIABLE;
        ctx.receiverStrategy = TEST_RECEIVER_STRATEGY;
        ctx.receiverConstruction = TEST_RECEIVER_CONSTRUCTION;
        ctx.receiverNotes = TEST_RECEIVER_NOTES;
        ctx.receiverRuntimeClassName = TEST_RECEIVER_RUNTIME_CLASS;
        ctx.receiverRuntimeSootClassName = TEST_RECEIVER_RUNTIME_SOOT_CLASS;
        ctx.receiverDeclaringClassName = TEST_RECEIVER_DECLARING_CLASS;
        ctx.receiverDispatchTarget = TEST_RECEIVER_DISPATCH_TARGET;
        ctx.receiverDispatchesToMutationMethod = TEST_RECEIVER_DISPATCHES_TO_MUTATION;
        ctx.receiverSubclassOverridesMutationMethod = TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION;
        ctx.receiverSetupTemplate = TEST_RECEIVER_SETUP_TEMPLATE;
        ctx.receiverInvocationTemplate = TEST_RECEIVER_INVOCATION_TEMPLATE;
        ctx.receiverResolutionReason = TEST_RECEIVER_RESOLUTION_REASON;
        ctx.receiverFactoryMethod = TEST_RECEIVER_FACTORY_METHOD;
        ctx.receiverBuilderClassName = TEST_RECEIVER_BUILDER_CLASS;
        ctx.receiverBuilderTerminalMethod = TEST_RECEIVER_BUILDER_TERMINAL_METHOD;
        ctx.receiverBuilderSetupChain = TEST_RECEIVER_BUILDER_SETUP_CHAIN;
        ctx.receiverAntiPatterns = TEST_RECEIVER_ANTI_PATTERNS;
        ctx.availablePublicMethods = AVAILABLE_PUBLIC_METHODS;
        ctx.availableSetupMethods = AVAILABLE_SETUP_METHODS;
        ctx.stateSetupPlan = STATE_SETUP_PLAN;
        ctx.observablePlanKind = OBSERVABLE_PLAN_KIND;
        ctx.observableSetup = OBSERVABLE_SETUP;
        ctx.observableCall = OBSERVABLE_CALL;
        ctx.observableExpectedOriginal = OBSERVABLE_EXPECTED_ORIGINAL;
        ctx.observableReason = OBSERVABLE_REASON;
        ctx.observableAntiPatterns = OBSERVABLE_ANTI_PATTERNS;
        ctx.branchReachabilityKind = BRANCH_REACHABILITY_KIND;
        ctx.branchReachabilityCondition = BRANCH_REACHABILITY_CONDITION;
        ctx.branchReachabilitySetup = BRANCH_REACHABILITY_SETUP;
        ctx.branchReachabilityReason = BRANCH_REACHABILITY_REASON;
        ctx.abstractMethodsToImplement = ABSTRACT_METHODS_TO_IMPLEMENT;
        ctx.allowedOverrides = ALLOWED_OVERRIDES;
        ctx.forbiddenOverrides = FORBIDDEN_OVERRIDES;
        ctx.testStubClassTemplate = TEST_STUB_CLASS_TEMPLATE;
        ctx.testStubConstructorTemplate = TEST_STUB_CONSTRUCTOR_TEMPLATE;
        ctx.skipTestGeneration = SKIP_TEST_GENERATION;
        ctx.skipReason = SKIP_REASON;

        return DependencyContextBuilder.build(ctx);
    }

    private Map<String, Object> buildEntryLiftedRipOutput() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        try {
            EntryLiftedRipBuilder.EntryContext ctx = new EntryLiftedRipBuilder.EntryContext();

            ctx.originId = OID;
            ctx.mutantId = mID;

            ctx.originJavaFile = SRC_FILE_P;
            ctx.mutantJavaFile = SRC_FILE_M;
            ctx.originClassesDir = CLASSES_DIR_P;
            ctx.mutantClassesDir = CLASSES_DIR_M;

            // A: real mutation method used for mutation-point RIP evidence.
            ctx.mutationClassName = CLASS_NAME_P;
            ctx.mutationMethodSig = METHOD_NAME_SUBSTR;

            // B: callable test entry resolved by MethodEntryResolver.
            ctx.entryClassName = TEST_ENTRY_CLASS_NAME_P;
            ctx.entryMethodSig = TEST_ENTRY_METHOD_SUBSTR;

            ctx.testEntryKind = TEST_ENTRY_KIND;
            ctx.useReflectionFallback = USE_REFLECTION_FALLBACK;
            ctx.testCallChain = TEST_CALL_CHAIN;
            ctx.testEntryNotes = TEST_ENTRY_NOTES;
            ctx.testGenerationPackage = TEST_GENERATION_PACKAGE;

            ctx.entryInvocationKind = entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK,
                    TEST_RECEIVER_STRATEGY, ENTRY_INVOCATION_KIND);
            ctx.diff = Diff == null ? "" : Diff;
            ctx.receiverOwnerKind = TEST_ENTRY_OWNER_KIND;
            ctx.receiverOwnerAbstract = TEST_ENTRY_OWNER_ABSTRACT;
            ctx.receiverOwnerInterface = TEST_ENTRY_OWNER_INTERFACE;
            ctx.receiverOwnerInstantiable = TEST_ENTRY_OWNER_INSTANTIABLE;
            ctx.receiverStrategy = TEST_RECEIVER_STRATEGY;
            ctx.receiverConstruction = TEST_RECEIVER_CONSTRUCTION;
            ctx.receiverNotes = TEST_RECEIVER_NOTES;
        ctx.receiverRuntimeClassName = TEST_RECEIVER_RUNTIME_CLASS;
        ctx.receiverRuntimeSootClassName = TEST_RECEIVER_RUNTIME_SOOT_CLASS;
        ctx.receiverDeclaringClassName = TEST_RECEIVER_DECLARING_CLASS;
        ctx.receiverDispatchTarget = TEST_RECEIVER_DISPATCH_TARGET;
        ctx.receiverDispatchesToMutationMethod = TEST_RECEIVER_DISPATCHES_TO_MUTATION;
        ctx.receiverSubclassOverridesMutationMethod = TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION;
        ctx.receiverSetupTemplate = TEST_RECEIVER_SETUP_TEMPLATE;
        ctx.receiverInvocationTemplate = TEST_RECEIVER_INVOCATION_TEMPLATE;
        ctx.receiverResolutionReason = TEST_RECEIVER_RESOLUTION_REASON;
        ctx.receiverFactoryMethod = TEST_RECEIVER_FACTORY_METHOD;
        ctx.receiverBuilderClassName = TEST_RECEIVER_BUILDER_CLASS;
        ctx.receiverBuilderTerminalMethod = TEST_RECEIVER_BUILDER_TERMINAL_METHOD;
        ctx.receiverBuilderSetupChain = TEST_RECEIVER_BUILDER_SETUP_CHAIN;
        ctx.receiverAntiPatterns = TEST_RECEIVER_ANTI_PATTERNS;
        ctx.availablePublicMethods = AVAILABLE_PUBLIC_METHODS;
        ctx.availableSetupMethods = AVAILABLE_SETUP_METHODS;
        ctx.stateSetupPlan = STATE_SETUP_PLAN;
        ctx.observablePlanKind = OBSERVABLE_PLAN_KIND;
        ctx.observableSetup = OBSERVABLE_SETUP;
        ctx.observableCall = OBSERVABLE_CALL;
        ctx.observableExpectedOriginal = OBSERVABLE_EXPECTED_ORIGINAL;
        ctx.observableReason = OBSERVABLE_REASON;
        ctx.observableAntiPatterns = OBSERVABLE_ANTI_PATTERNS;
        ctx.branchReachabilityKind = BRANCH_REACHABILITY_KIND;
        ctx.branchReachabilityCondition = BRANCH_REACHABILITY_CONDITION;
        ctx.branchReachabilitySetup = BRANCH_REACHABILITY_SETUP;
        ctx.branchReachabilityReason = BRANCH_REACHABILITY_REASON;
        ctx.abstractMethodsToImplement = ABSTRACT_METHODS_TO_IMPLEMENT;
        ctx.allowedOverrides = ALLOWED_OVERRIDES;
        ctx.forbiddenOverrides = FORBIDDEN_OVERRIDES;
        ctx.testStubClassTemplate = TEST_STUB_CLASS_TEMPLATE;
        ctx.testStubConstructorTemplate = TEST_STUB_CONSTRUCTOR_TEMPLATE;
        ctx.skipTestGeneration = SKIP_TEST_GENERATION;
        ctx.skipReason = SKIP_REASON;

            ctx.specObserved = SPEC_OBSERVED;
            ctx.mutationRanges = ranges == null ? List.of() : ranges;

            return EntryLiftedRipBuilder.build(ctx);
        } catch (Throwable t) {
            boolean sameEntryAndMutation = sameMethodForEntryLiftedOutput(
                    TEST_ENTRY_CLASS_NAME_P,
                    TEST_ENTRY_METHOD_SUBSTR,
                    CLASS_NAME_P,
                    METHOD_NAME_SUBSTR);
            boolean needEntryLiftedEvidence = !sameEntryAndMutation && !USE_REFLECTION_FALLBACK;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", commented("Whether entry-lifted RIP evidence was generated successfully", false));
            item.put("error", commented("Entry-lifted RIP construction error",
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
            item.put("sameEntryAndMutation", commented(
                    "Whether callable test entry B is the same method as real mutation method A",
                    sameEntryAndMutation));
            item.put("needEntryLiftedEvidence", commented(
                    "Whether B-side EntryLiftedRIP evidence should be provided to the LLM",
                    needEntryLiftedEvidence));
            item.put("mutationClass", commented("Class containing real mutation method A", CLASS_NAME_P));
            item.put("mutationMethod",
                    commented("Real mutation method A", PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
            item.put("testEntryClass",
                    commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
            item.put("testEntryMethod", commented("Callable test entry method B",
                    PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
            item.put("testEntryKind", commented("Entry resolution kind", TEST_ENTRY_KIND));
            item.put("useReflectionFallback",
                    commented("Whether reflection fallback is required", USE_REFLECTION_FALLBACK));
            item.put("callChain",
                    commented("Resolved call chain from B to A", PromptSignatureFormatter.callChain(TEST_CALL_CHAIN)));
            item.put("notes", commented("Entry resolution notes", TEST_ENTRY_NOTES));
            item.put("testGenerationPackage", commented("Package used by generated tests", TEST_GENERATION_PACKAGE));
            String entryKind = entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK,
                    TEST_RECEIVER_STRATEGY, ENTRY_INVOCATION_KIND);
            item.put("entryInvocationKind", commented("How generated tests should invoke callable test entry B", entryKind));
            item.put("entryGenerationPlan", commented("Invocation plan and suggested test values for callable test entry B",
                    buildFallbackEntryGenerationPlan(entryKind)));

            // Structural guarantee: EntryLiftedRIP.item.origin and
            // EntryLiftedRIP.item.mutated must always exist. When real Soot/Jimple
            // construction fails, provide explicit fallback side objects instead of
            // dropping the two branches.
            String errorText = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            item.put("origin", commented("Fallback entry-side evidence for original program; real Soot/Jimple construction failed",
                    buildFallbackEntrySide(OID, false, "", errorText, sameEntryAndMutation)));
            item.put("mutated", commented("Fallback entry-side evidence for mutated program; real Soot/Jimple construction failed",
                    buildFallbackEntrySide(mID, !sameEntryAndMutation,
                            sameEntryAndMutation ? "" : "B-side graph would normally reuse origin because the mutation is located in A, but construction failed before graph materialization.",
                            errorText, sameEntryAndMutation)));

            fallback.put("comment",
                    "Soot/Jimple evidence for callable test entry B; construction failed, but origin/mutated fallback branches are still provided");
            fallback.put("item", item);
            return fallback;
        }
    }


    private Map<String, Object> buildFallbackEntryGenerationPlan(String entryKind) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("entryInvocationKind", commented("How generated tests should invoke callable test entry B", entryKind));
        plan.put("recommendedTestTarget", commented("Recommended invocation target for generated tests",
                recommendedTestTarget(TEST_ENTRY_KIND, USE_REFLECTION_FALLBACK)));
        plan.put("receiver", commented("Receiver construction metadata resolved for callable entry B", buildReceiverMetadataMap()));
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("notes", "Use DependencyContext.testEntryContext and A-side origin/mutated RIP evidence to generate the test.");
        plan.put("invocationPlan", commented("Fallback invocation plan; real entry-side graph construction failed before a richer plan could be generated", invocation));
        plan.put("suggestedTestValues", commented("Fallback suggested test values", Map.of("items", List.of())));
        plan.put("assertionPlan", commented("Fallback assertion plan",
                buildFallbackAssertionPlan(entryKind)));
        return plan;
    }

    private Map<String, Object> buildFallbackAssertionPlan(String entryKind) {
        Map<String, Object> plan = new LinkedHashMap<>();
        List<Object> targets = new ArrayList<>();
        List<Object> assertions = new ArrayList<>();
        List<String> anti = new ArrayList<>();

        String all = (Diff == null ? "" : Diff) + "\n" + METHOD_NAME_SUBSTR + "\n" + TEST_ENTRY_METHOD_SUBSTR + "\n" + TEST_ENTRY_CLASS_NAME_P;
        boolean exceptionLike = TEST_ENTRY_CLASS_NAME_P != null && TEST_ENTRY_CLASS_NAME_P.contains("Exception");
        boolean messageLike = all.toLowerCase(Locale.ROOT).contains("message") || all.contains("could be") || all.contains("Ambiguous option");
        boolean appendableLike = all.contains("append") || all.contains("Appendable");

        if (exceptionLike && messageLike) {
            targets.add(Map.of(
                    "kind", commented("Observable target kind", "exception_message"),
                    "expression", commented("Java expression to observe", "subject.getMessage()"),
                    "confidence", commented("Confidence", "medium")));
            assertions.add(Map.of(
                    "priority", commented("Priority", "high"),
                    "template", commented("JUnit assertion template", all.contains("could be")
                            ? "assertTrue(subject.getMessage().contains(\"could be\"));"
                            : "assertNotNull(subject.getMessage());"),
                    "reason", commented("Reason", "The mutation appears to affect exception/message construction.")));
            anti.add("Do not compare getMatchingOptions() with a message string; it returns a Collection, not the exception message.");
        }
        if (appendableLike) {
            targets.add(Map.of(
                    "kind", commented("Observable target kind", "external_mutable_state"),
                    "expression", commented("Java expression to observe", "output.toString()"),
                    "confidence", commented("Confidence", "medium")));
            assertions.add(Map.of(
                    "priority", commented("Priority", "high"),
                    "template", commented("JUnit assertion template", "assertEquals(\"x\", output.toString());"),
                    "reason", commented("Reason", "The mutation may affect Appendable/StringBuilder state.")));
            anti.add("Do not assert only returned receiver identity when the mutation changes an external side effect.");
        }
        if (targets.isEmpty()) {
            targets.add(Map.of(
                    "kind", commented("Observable target kind", "fallback"),
                    "expression", commented("Java expression to observe", "public return/getter/state/exception output"),
                    "confidence", commented("Confidence", "low")));
        }

        plan.put("comparisonPolicy", commented("General comparison policy for generated tests",
                "Prefer mutation-sensitive public observable sinks over arbitrary object equality."));
        plan.put("primaryObservableTargets", commentedList("Fallback observable targets", targets));
        plan.put("recommendedAssertions", commentedList("Fallback assertion templates", assertions));
        plan.put("antiPatterns", commentedList("Fallback anti-patterns", anti));
        return plan;
    }

    private Map<String, Object> buildReceiverMetadataMap() {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("ownerKind", TEST_ENTRY_OWNER_KIND);
        receiver.put("ownerAbstract", TEST_ENTRY_OWNER_ABSTRACT);
        receiver.put("ownerInterface", TEST_ENTRY_OWNER_INTERFACE);
        receiver.put("ownerInstantiable", TEST_ENTRY_OWNER_INSTANTIABLE);
        receiver.put("strategy", TEST_RECEIVER_STRATEGY);
        receiver.put("construction", TEST_RECEIVER_CONSTRUCTION);
        receiver.put("notes", TEST_RECEIVER_NOTES);
        return receiver;
    }

    private Map<String, Object> buildTestEntryOutput() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", TEST_ENTRY_KIND);
        entry.put("useReflectionFallback", USE_REFLECTION_FALLBACK);
        entry.put("mutationClass", CLASS_NAME_P);
        entry.put("mutationMethod", PromptSignatureFormatter.method(METHOD_NAME_SUBSTR));
        entry.put("testEntryClass", TEST_ENTRY_CLASS_NAME_P);
        entry.put("testEntryMethod", PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR));
        entry.put("callChain", PromptSignatureFormatter.callChain(TEST_CALL_CHAIN));
        entry.put("notes", TEST_ENTRY_NOTES);
        entry.put("testGenerationPackage", TEST_GENERATION_PACKAGE);
        entry.put("recommendedTestTarget", recommendedTestTarget(TEST_ENTRY_KIND, USE_REFLECTION_FALLBACK));
        entry.put("entryInvocationKind", entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK, TEST_RECEIVER_STRATEGY, ENTRY_INVOCATION_KIND));

        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("ownerKind", TEST_ENTRY_OWNER_KIND);
        receiver.put("ownerAbstract", TEST_ENTRY_OWNER_ABSTRACT);
        receiver.put("ownerInterface", TEST_ENTRY_OWNER_INTERFACE);
        receiver.put("ownerInstantiable", TEST_ENTRY_OWNER_INSTANTIABLE);
        receiver.put("strategy", TEST_RECEIVER_STRATEGY);
        receiver.put("construction", TEST_RECEIVER_CONSTRUCTION);
        receiver.put("notes", TEST_RECEIVER_NOTES);
        entry.put("receiver", receiver);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("originEntryContent",
                safeExtractForEntry(SRC_FILE_P, TEST_ENTRY_CLASS_NAME_P, TEST_ENTRY_METHOD_SUBSTR));
        source.put("mutantEntryContent",
                safeExtractForEntry(SRC_FILE_M, TEST_ENTRY_CLASS_NAME_M, TEST_ENTRY_METHOD_SUBSTR));
        entry.put("entrySource", source);

        return entry;
    }

    private static String entryInvocationKind(String sig, boolean reflection, String receiverStrategy, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (reflection) {
            return "REFLECTION_INVOCATION";
        }
        if (sig != null) {
            int lp = sig.indexOf('(');
            int us = sig.indexOf('_');
            if (lp > 0 && !(us > 0 && us < lp)) {
                return "CONSTRUCTOR_INVOCATION";
            }
        }
        if ("STATIC_NO_RECEIVER".equals(receiverStrategy)) {
            return "STATIC_METHOD_INVOCATION";
        }
        return "INSTANCE_METHOD_INVOCATION";
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

    private static String safeExtractForEntry(String javaFile, String className, String methodSig) {
        try {
            return cachedExtractMethod(javaFile, className, methodSig, true);
        } catch (Exception e) {
            return "<entry source extraction failed: " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()) + ">";
        }
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

    private Map<String, Object> buildFallbackEntrySide(String id,
                                                       boolean reusedFromOrigin,
                                                       String reuseReason,
                                                       String errorText,
                                                       boolean sameEntryAndMutation) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("id", commented("Program ID", id == null ? "" : id));
        side.put("entryClass", commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
        side.put("entryMethod", commented("Callable test entry method B", PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
        side.put("mutationClass", commented("Class containing the real mutation method A", CLASS_NAME_P));
        side.put("mutationMethod", commented("Real mutation method A reached from entry B", PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
        side.put("sameEntryAndMutation", commented("Whether B is the same method as A", sameEntryAndMutation));
        side.put("reusedFromOrigin", commented("Whether this entry-side evidence reuses the origin-side Soot/Jimple graph", reusedFromOrigin));
        side.put("reuseReason", commented("Reason for reusing origin-side entry evidence", reuseReason == null ? "" : reuseReason));
        side.put("focusMode", commented("How focus units are selected: affected mutation units if B equals A, otherwise call sites from B to A",
                sameEntryAndMutation ? "MUTATION_AFFECTED_UNITS" : "ENTRY_CALL_SITES_TO_MUTATION"));
        side.put("content", commented("Source text of callable test entry method B", safeExtractForEntry(SRC_FILE_P, TEST_ENTRY_CLASS_NAME_P, TEST_ENTRY_METHOD_SUBSTR)));
        side.put("IR", commented("Jimple intermediate representation of callable test entry method B", ""));
        side.put("focusUnits", commentedList("Jimple units in entry method B used as the focus of entry-lifted RIP evidence", List.of()));
        side.put("callSitesToMutation", commentedList("Invoke statements in entry method B that directly call the real mutation method A", List.of()));
        side.put("Paths", commentedList("Control-flow paths in entry method B that pass through the selected focus units", List.of()));
        side.put("CPG", commentedList("CFG and DFG information related to entry-side paths, forming an entry-level Code Property Graph", List.of()));
        side.put("warnings", commentedList("Non-fatal warnings collected during entry-lifted evidence construction",
                List.of("EntryLiftedRIP real graph construction failed: " + errorText)));
        return side;
    }

    private static boolean sameMethodForEntryLiftedOutput(
            String entryClassName,
            String entryMethodSig,
            String mutationClassName,
            String mutationMethodSig) {
        return normalizeClassNameForEntryLifted(entryClassName)
                .equals(normalizeClassNameForEntryLifted(mutationClassName))
                && normalizeSigForEntryLifted(entryMethodSig)
                        .equals(normalizeSigForEntryLifted(mutationMethodSig));
    }

    private static String normalizeClassNameForEntryLifted(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replace('$', '.');
    }

    private static String normalizeSigForEntryLifted(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "").replace('$', '.');
    }

    public static void saveCPG(String clsName, String methodName, String outputDir) throws Exception {

        // Load target class
        Scene.v().loadNecessaryClasses();
        SootClass sc = Scene.v().forceResolve(clsName, SootClass.BODIES);
        sc.setApplicationClass();
        Scene.v().loadClassAndSupport(clsName);

        // Perform AST analysis
        ASTVisualizer ast = new ASTVisualizer(clsName, methodName, outputDir);
        ast.visualize();

        // Perform CFG analysis
        CFGVisualizer cfg = new CFGVisualizer(clsName, methodName, outputDir);
        cfg.visualize();

        // Perform DFG analysis
        DFGVisualizer dfg = new DFGVisualizer(clsName, methodName, outputDir);
        dfg.analyze();

        // // Convert all generated .dot files to images
        // DotToImageConverter.convertDotFilesInDirectory(new File(outputDir +
        // "/graph"));
    }

    /**
     * Cache only origin-side fields that are invariant for the same original
     * method.
     * Do not cache affected/paths/cpg here, because they depend on the current
     * pair-specific ranges.
     */
    private static final class OriginStaticSnapshot {
        String id;
        String content;
        String ir;
    }

    private String originStaticCacheKey() {
        return SRC_FILE_P + "##" + CLASS_NAME_P + "##" + METHOD_NAME_SUBSTR + "##" + CLASSES_DIR_P;
    }

    private static String methodTextKey(String javaFile, String className, String methodSig, boolean includeSignature) {
        return javaFile + "##" + className + "##" + methodSig + "##" + includeSignature;
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
}