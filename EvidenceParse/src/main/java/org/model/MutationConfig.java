package org.model;

public class MutationConfig {
    public String operator;
    public String lineNo;
    /** Real mutation method A, used to build RIP/Jimple/CFG/DFG evidence. */
    public String methodName;
    public String className;
    public String classNameF;
    public String mutationStatement;
    public String packageName;
    public String projectName;
    public String filepath;

    /** Real mutation location after entry resolution. */
    public String mutationClassName;
    public String mutationSootClassName;
    public String mutationMethodName;

    /** Callable that generated tests should target. */
    public String testEntryClassName;
    public String testEntrySootClassName;
    public String testEntryMethodName;

    /**
     * Entry type, e.g. PUBLIC_DIRECT_ENTRY, PACKAGE_PRIVATE_DIRECT_ENTRY,
     * ASCENDED_PUBLIC_CALLER.
     */
    public String testEntryKind;
    public boolean useReflectionFallback;

    /** Human-readable evidence for the chosen entry. */
    public String testCallChain;
    public String testEntryNotes;

    /**
     * Package that generated test class should use when same-package access is
     * needed.
     */
    public String testGenerationPackage;

    /** Receiver/owner information for callable test entry B. */
    public String testEntryOwnerKind;
    public boolean testEntryOwnerAbstract;
    public boolean testEntryOwnerInterface;
    public boolean testEntryOwnerInstantiable;
    public String testReceiverStrategy;
    public String testReceiverConstruction;
    public String testReceiverNotes;

    /** Prompt-facing invocation kind for B: CONSTRUCTOR/INSTANCE/STATIC/REFLECTION. */
    public String entryInvocationKind;

    /**
     * Receiver resolution produced by static analysis.
     * When B is declared in an abstract class or interface, static analysis may
     * choose a concrete runtime receiver class instead of asking the LLM to guess.
     */
    public String testReceiverRuntimeClassName;
    public String testReceiverRuntimeSootClassName;
    public String testReceiverDeclaringClassName;
    public String testReceiverDispatchTarget;
    public boolean testReceiverDispatchesToMutationMethod;
    public boolean testReceiverSubclassOverridesMutationMethod;
    public String testReceiverSetupTemplate;
    public String testReceiverInvocationTemplate;
    public String testReceiverResolutionReason;

    /** Static factory / builder construction details when direct constructor is unavailable. */
    public String testReceiverFactoryMethod;
    public String testReceiverBuilderClassName;
    public String testReceiverBuilderTerminalMethod;
    public String testReceiverBuilderSetupChain;
    public String testReceiverAntiPatterns;

    /** Public API and observable behavior evidence resolved before LLM prompting. */
    public String availablePublicMethods;
    public String availableSetupMethods;
    public String stateSetupPlan;
    public String observablePlanKind;
    public String observableSetup;
    public String observableCall;
    public String observableExpectedOriginal;
    public String observableReason;
    public String observableAntiPatterns;

    /** Branch reachability evidence that turns CFG predicates into concrete setup. */
    public String branchReachabilityKind;
    public String branchReachabilityCondition;
    public String branchReachabilitySetup;
    public String branchReachabilityReason;

    /** Test-stub guidance for abstract classes or subclass-based tests. */
    public String abstractMethodsToImplement;
    public String allowedOverrides;
    public String forbiddenOverrides;
    public String testStubClassTemplate;
    public String testStubConstructorTemplate;

    /** Whether LLM test generation should be skipped for this mutant. */
    public boolean skipTestGeneration;
    public String skipReason;
}
