package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;
import org.model.MutationConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolve the concrete runtime receiver for callable entry B before output.json is written.
 *
 * This is intentionally a static-analysis step rather than an LLM decision:
 * - If B is declared in a concrete class, keep normal receiver construction.
 * - If B is declared in an abstract class but B itself is concrete, prefer an existing concrete subclass that does not override B.
 * - If no concrete subclass is available, fall back to a test stub subclass strategy.
 * - If the target has no executable body, mark it as not suitable for direct test generation.
 */
public final class ReceiverResolver {

    private ReceiverResolver() {
    }

    public static MethodEntryResolver.Resolution enrich(MutationConfig config,
                                                        MethodEntryResolver.Resolution r,
                                                        String originJavaFile) {
        if (r == null) {
            return null;
        }
        try {
            if (r.useReflectionFallback || isBlank(r.testEntryClassName) || isBlank(r.testEntryMethodName)) {
                return r;
            }
            if (isStaticInvocation(r)) {
                return r;
            }

            CompilationUnit ownerCu = parse(originJavaFile);
            String ownerPackage = packageName(ownerCu, config.packageName);
            Optional<TypeDeclaration<?>> ownerOpt = findType(ownerCu, r.testEntryClassName);
            if (ownerOpt.isEmpty()) {
                r.notes.add("receiver resolver: entry owner not found in source; keep existing receiver strategy");
                return r;
            }
            TypeDeclaration<?> owner = ownerOpt.get();
            Optional<CallableDeclaration<?>> entryCallableOpt = findCallable(owner, r.testEntryMethodName);
            if (entryCallableOpt.isEmpty()) {
                r.notes.add("receiver resolver: entry callable not found in owner; keep existing receiver strategy");
                return r;
            }
            CallableDeclaration<?> entryCallable = entryCallableOpt.get();
            MethodDeclaration entryMethod = entryCallable instanceof MethodDeclaration ? (MethodDeclaration) entryCallable : null;

            if (entryMethod != null && (entryMethod.isAbstract() || entryMethod.getBody().isEmpty())) {
                r.skipTestGeneration = true;
                r.skipReason = "SKIP_NO_EXECUTABLE_BODY: entry/mutation method has no executable body";
                r.testReceiverStrategy = "SKIP_NO_EXECUTABLE_BODY";
                r.testReceiverNotes = r.skipReason;
                r.notes.add(r.skipReason);
                return r;
            }

            applyApiAndObservableEvidence(config, r, owner, entryCallable);
            applyGeneralCompileGuardrails(config, r, owner, entryCallable);

            String ownerKind = ownerKind(owner);
            if ("ABSTRACT_CLASS".equals(ownerKind)) {
                // For abstract declaring classes, prefer a test stub subclass by default.
                // This avoids unstable evidence such as new Days(), new BuddhistChronology(),
                // or other concrete subclasses whose constructors/factories were not verified.
                applyStubFallback(r, owner, r.testEntryMethodName, entryCallable, config);
                return r;
            }
            if ("INTERFACE".equals(ownerKind)) {
                r.skipTestGeneration = true;
                r.skipReason = "SKIP_INTERFACE_ENTRY_NO_BODY: entry owner is an interface; static analysis must resolve a concrete implementation first.";
                r.testReceiverStrategy = "SKIP_INTERFACE_ENTRY_NO_BODY";
                r.testReceiverNotes = r.skipReason;
                r.notes.add(r.skipReason);
                return r;
            }
            if (!"ABSTRACT_CLASS".equals(ownerKind) && !"INTERFACE".equals(ownerKind)) {
                if (applyKnownCollaboratorConstruction(r, owner, entryCallable)) {
                    return r;
                }
                // Concrete class with no accessible constructor, e.g. TextStyle:
                // static analysis should resolve factory/builder construction instead of
                // asking the LLM to guess new Target(), new Builder(), or build().
                if ("FACTORY_OR_REFLECTION_REQUIRED".equals(r.testReceiverStrategy)
                        || !r.testEntryOwnerInstantiable) {
                    if (applyStaticFactoryBuilder(r, ownerCu, owner, entryMethod)) {
                        return r;
                    }
                }
                return r;
            }

            List<Path> sourceRoots = findSourceRoots(config, originJavaFile);
            if (!sourceRoots.isEmpty()) {
                List<TypeInfo> types = parseProjectTypes(sourceRoots);
                TypeInfo ownerInfo = TypeInfo.from(ownerCu, owner, originJavaFile);
                Candidate best = findBestConcreteReceiver(types, ownerInfo, r.testEntryMethodName);
                if (best != null) {
                    applyConcreteSubclass(config, r, ownerInfo, best);
                    return r;
                }
            }

            applyStubFallback(r, owner, r.testEntryMethodName, entryCallable, config);
            return r;
        } catch (Throwable t) {
            r.notes.add("receiver resolver failed: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            return r;
        }
    }

    private static void applyApiAndObservableEvidence(MutationConfig config,
                                                      MethodEntryResolver.Resolution r,
                                                      TypeDeclaration<?> owner,
                                                      CallableDeclaration<?> entryCallable) {
        List<MethodDeclaration> methods = owner.getMethods();
        List<String> publicMethods = new ArrayList<>();
        List<String> setupMethods = new ArrayList<>();
        for (MethodDeclaration m : methods) {
            if (m.isPrivate()) {
                continue;
            }
            String sig = methodDisplaySignature(m);
            publicMethods.add(sig);
            if (m.getNameAsString().startsWith("set") && m.getParameters().size() == 1) {
                setupMethods.add(sig);
            }
        }
        r.availablePublicMethods = String.join(" || ", publicMethods);
        r.availableSetupMethods = String.join(" || ", setupMethods);

        List<String> stateSetup = inferStateSetupStatements(owner, entryCallable);
        if (!stateSetup.isEmpty()) {
            r.stateSetupPlan = String.join(" || ", stateSetup);
        }

        BranchPlan bp = inferBranchReachabilityPlan(owner, entryCallable, config);
        if (bp != null) {
            r.branchReachabilityKind = bp.kind;
            r.branchReachabilityCondition = bp.condition;
            r.branchReachabilitySetup = bp.setup;
            r.branchReachabilityReason = bp.reason;
        }

        ObservablePlan op = inferObservablePlan(owner, entryCallable, stateSetup, config, bp);
        if (op != null) {
            r.observablePlanKind = op.kind;
            r.observableSetup = op.setup;
            r.observableCall = op.call;
            r.observableExpectedOriginal = op.expectedOriginal;
            r.observableReason = op.reason;
            r.observableAntiPatterns = String.join(" || ", op.antiPatterns);
            if ("NO_PUBLIC_OBSERVABLE".equals(op.kind)) {
                r.skipTestGeneration = true;
                r.skipReason = firstNonBlank(r.skipReason, op.reason);
                r.notes.add(op.reason);
            }
        }
    }

    private static String methodDisplaySignature(MethodDeclaration m) {
        String params = m.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(", "));
        return m.getType().asString() + " " + m.getNameAsString() + "(" + params + ")";
    }

    private static List<String> inferStateSetupStatements(TypeDeclaration<?> owner, CallableDeclaration<?> entryCallable) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!(entryCallable instanceof MethodDeclaration)) {
            return new ArrayList<>(out);
        }
        String body = entryCallable.toString();
        List<FieldDeclaration> fields = owner.getFields();
        for (FieldDeclaration fd : fields) {
            for (VariableDeclarator v : fd.getVariables()) {
                String field = v.getNameAsString();
                if (!body.contains(field)) {
                    continue;
                }
                MethodDeclaration setter = findSetterForField(owner, field);
                if (setter != null) {
                    String arg = exampleValueForTypeForSetter(setter.getParameter(0).getType().asString(), field, setter.getNameAsString());
                    if (!arg.isEmpty()) {
                        out.add("subject." + setter.getNameAsString() + "(" + arg + ");");
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static MethodDeclaration findSetterForField(TypeDeclaration<?> owner, String field) {
        String normalized = ("set" + field).toLowerCase(Locale.ROOT);
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.isPrivate() || m.getParameters().size() != 1) {
                continue;
            }
            if (m.getNameAsString().toLowerCase(Locale.ROOT).equals(normalized)) {
                return m;
            }
        }
        return null;
    }

    private static String exampleValueForTypeForSetter(String raw, String field, String setterName) {
        String t = simpleType(raw);
        String f = (field + " " + setterName).toLowerCase(Locale.ROOT);
        if ("boolean".equals(t) || "Boolean".equals(t)) {
            return "false";
        }
        if ("long".equals(t) || "Long".equals(t)) {
            return f.contains("millis") || f.contains("unit") ? "1000L" : "1L";
        }
        if ("int".equals(t) || "Integer".equals(t)) {
            if (f.contains("width") || f.contains("height") || f.contains("size")) return "5";
            return "1";
        }
        if ("String".equals(t) || "CharSequence".equals(t)) {
            if (f.contains("pattern")) return "\"a.b\"";
            if (f.contains("string")) return "\"a\\nb\"";
            return "\"x\"";
        }
        if ("long".equals(t) || "Long".equals(t)) return "1L";
        if ("float".equals(t) || "Float".equals(t)) return "1.0f";
        if ("double".equals(t) || "Double".equals(t)) return "1.0";
        if ("char".equals(t) || "Character".equals(t)) return "'x'";
        return "";
    }

    private static ObservablePlan inferObservablePlan(TypeDeclaration<?> owner,
                                                      CallableDeclaration<?> entryCallable,
                                                      List<String> stateSetup,
                                                      MutationConfig config,
                                                      BranchPlan branchPlan) {
        String ownerSimple = owner.getNameAsString();
        String diff = config == null || config.mutationStatement == null ? "" : config.mutationStatement;
        if (entryCallable instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) entryCallable;
            if (ownerSimple.equals("JsonGeneratorDelegate") && md.toString().contains("delegate.")) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "FORWARDING_CALL_TO_CONCRETE_COLLABORATOR";
                op.setup = "java.io.StringWriter out = new java.io.StringWriter(); com.fasterxml.jackson.core.JsonGenerator delegate = new com.fasterxml.jackson.core.JsonFactory().createGenerator(out); JsonGeneratorDelegate subject = new JsonGeneratorDelegate(delegate);";
                if (md.getNameAsString().equals("writeString") && md.getParameters().size() == 3) {
                    op.call = "subject.writeString(new char[] {'x', 'y', 'z'}, 1, 1); delegate.flush(); String output = out.toString();";
                    op.expectedOriginal = "\"\\\"y\\\"\"";
                } else {
                    op.call = "Invoke the forwarding method on subject, flush delegate if needed, and observe out.toString().";
                }
                op.reason = "The target method forwards to an abstract JsonGenerator collaborator; use JsonFactory.createGenerator(StringWriter) instead of hand-writing a JsonGenerator subclass.";
                op.antiPatterns.add("Do not instantiate JsonGeneratorDelegate with null delegate when testing forwarding behavior.");
                op.antiPatterns.add("Do not hand-write a subclass of JsonGenerator unless a complete abstract-method template is provided.");
                return op;
            }
            String entryBody = md.toString();
            List<String> touchedFields = touchedFields(owner, entryBody);

            if (!"void".equals(md.getType().asString())) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "ENTRY_RETURN_VALUE";
                String args = exampleArgsForCallable(md, config);
                op.call = simpleType(md.getType().asString()) + " result = subject." + md.getNameAsString() + "(" + args + ");";
                if (branchPlan != null && !branchPlan.setup.isBlank()) {
                    op.setup = branchPlan.setup;
                }
                if (ownerSimple.equals("AssembledChronology") && md.getNameAsString().equals("getDateTimeMillis")) {
                    op.expectedOriginal = "base.getDateTimeMillis(" + args + ")";
                    op.reason = "ENTRY_RETURN_VALUE_WITH_BASE_DELEGATION: use a non-zero hourOfDay and compare subject result with base.getDateTimeMillis using the original positive hourOfDay.";
                } else {
                    op.reason = "Entry method returns a value; assert the returned value as the mutation-sensitive observable.";
                }
                return op;
            }

            for (MethodDeclaration m : owner.getMethods()) {
                if (m == md || m.isPrivate() || "void".equals(m.getType().asString()) || m.getParameters().size() > 2) {
                    continue;
                }
                String mt = m.toString();
                for (String f : touchedFields) {
                    if (mt.contains(f)) {
                        ObservablePlan op = new ObservablePlan();
                        op.kind = "PUBLIC_METHOD_DEPENDS_ON_MUTATED_FIELD";
                        String actionArgs = exampleArgsForCallable(md, config);
                        String observableArgs = exampleArgsForCallable(m, config);
                        op.setup = "subject." + md.getNameAsString() + "(" + actionArgs + ");";
                        op.call = m.getType().asString() + " result = subject." + m.getNameAsString() + "(" + observableArgs + ");";
                        op.reason = "Entry method updates field '" + f + "'; public method " + m.getNameAsString()
                                + " reads that field and returns an observable value.";
                        if (diff.contains("--") || diff.contains("++") || diff.contains("=> -") || diff.contains("-")) {
                            String expected = firstArgValue(md, config);
                            if (!expected.isBlank()) {
                                op.expectedOriginal = expected;
                                op.reason += " The mutation changes the assigned value; assert the original argument value after the setter/action.";
                            }
                        }
                        return op;
                    }
                }
            }

            ObservablePlan reflective = inferReflectionFieldObservable(owner, md, touchedFields, config);
            if (reflective != null) {
                return reflective;
            }
        }
        if (entryCallable instanceof ConstructorDeclaration) {
            String text = entryCallable.toString();
            if (text.contains("for (") && (diff.contains("i--") || diff.contains("--") || diff.contains("i++"))) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "CONSTRUCTOR_COMPLETES_VS_EXCEPTION";
                op.setup = "Use non-null concrete parameters generated by parameterPlans, e.g. org.joda.time.Partial for ReadablePartial, so the loop body is reached.";
                op.call = "Construct the TEST_STUB_SUBCLASS receiver with mutation-sensitive non-null arguments.";
                op.expectedOriginal = "constructor completes normally";
                op.reason = "Loop-update mutation in constructor is observable as normal construction versus exception/non-termination; do not skip as no-public-observable.";
                return op;
            }
            boolean stateWrite = text.contains("set") || text.contains("=");
            if (stateWrite) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "NO_PUBLIC_OBSERVABLE";
                op.reason = "NO_PUBLIC_OBSERVABLE_FOR_CONSTRUCTOR_STATE_MUTATION: constructor mutates internal/inherited state, but no public getter or public behavior depending on that state was found by static analysis.";
                op.antiPatterns.add("Do not invent getters such as getRestrict() or isRestrict(); only use methods listed in availablePublicMethods.");
                return op;
            }
        }
        return null;
    }

    private static List<String> touchedFields(TypeDeclaration<?> owner, String text) {
        List<String> out = new ArrayList<>();
        for (FieldDeclaration fd : owner.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                String n = v.getNameAsString();
                if (text.contains(n)) out.add(n);
            }
        }
        return out;
    }


    private static ObservablePlan inferReflectionFieldObservable(TypeDeclaration<?> owner,
                                                                 MethodDeclaration md,
                                                                 List<String> touchedFields,
                                                                 MutationConfig config) {
        if (touchedFields.isEmpty() || md.getParameters().isEmpty()) {
            return null;
        }
        String methodName = md.getNameAsString();
        if (!methodName.startsWith("set") || md.getParameters().size() != 1) {
            return null;
        }
        String likelyField = fieldNameFromSetter(methodName);
        String field = touchedFields.contains(likelyField) ? likelyField : touchedFields.get(0);
        String value = exampleValueForParameter(md.getParameter(0).getType().asString(), md.getParameter(0).getNameAsString(), 0, config);
        String getter = findGetterName(owner, field);
        if (!getter.isBlank()) {
            return null;
        }
        ObservablePlan op = new ObservablePlan();
        op.kind = "REFLECTION_FIELD_READ_AFTER_SETTER";
        op.setup = "subject." + methodName + "(" + value + "); java.lang.reflect.Field f = " + owner.getNameAsString() + ".class.getDeclaredField(\"" + field + "\"); f.setAccessible(true);";
        op.call = "Object result = f.get(subject);";
        op.expectedOriginal = value;
        op.reason = "The entry method writes field '" + field + "' but no public getter was found. Use reflection to read the declared field; do not invent get" + capitalize(field) + "().";
        op.antiPatterns.add("Do not invent getters such as get" + capitalize(field) + "(); they are not listed in availablePublicMethods.");
        op.antiPatterns.add("Use java.lang.reflect.Field on " + owner.getNameAsString() + ".class when no public observable method exists.");
        return op;
    }

    private static String findGetterName(TypeDeclaration<?> owner, String field) {
        String suffix = capitalize(field);
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.getParameters().isEmpty() && !m.isPrivate()
                    && (m.getNameAsString().equals("get" + suffix) || m.getNameAsString().equals("is" + suffix))) {
                return m.getNameAsString();
            }
        }
        return "";
    }

    private static String fieldNameFromSetter(String setter) {
        if (setter == null || !setter.startsWith("set") || setter.length() <= 3) return "";
        String s = setter.substring(3);
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void applyGeneralCompileGuardrails(MutationConfig config,
                                                      MethodEntryResolver.Resolution r,
                                                      TypeDeclaration<?> owner,
                                                      CallableDeclaration<?> entryCallable) {
        List<String> rules = new ArrayList<>();
        rules.add("Use the exact testGenerationPackage. Do not import the target class from a parent/sibling package; same-package tests can refer to package-private classes by simple name.");
        rules.add("Do not override final, private, concrete, or nonexistent methods. Override only methods listed in abstractMethodsToImplement/allowedOverrides.");
        rules.add("Do not reduce access privileges when overriding: if the superclass method is public, the override must be public.");
        rules.add("Do not invent getters such as getWidth/getHeight/getIgnore unless they are listed in availablePublicMethods; use observablePlan instead.");
        rules.add("For java.io.DataInput, use java.io.DataInputStream over java.io.ByteArrayInputStream; do not hand-write anonymous DataInput implementations.");
        r.testReceiverAntiPatterns = joinNonBlank(" || ", r.testReceiverAntiPatterns, String.join(" || ", rules));
        r.forbiddenOverrides = joinNonBlank(" || ", r.forbiddenOverrides, generalForbiddenOverrideRules(owner));
    }

    private static String generalForbiddenOverrideRules(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<>();
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.isFinal()) {
                out.add("FINAL: do not override " + methodDisplaySignature(m));
            } else if (!m.isAbstract()) {
                out.add("CONCRETE: do not override " + methodDisplaySignature(m) + "; call it instead unless javac requires a compatible override");
            }
        }
        out.add("NO_INVENTED_OVERRIDE: do not add @Override to helper methods unless the exact signature appears in allowedOverrides.");
        return String.join(" || ", out);
    }

    private static final class ObservablePlan {
        String kind = "";
        String setup = "";
        String call = "";
        String expectedOriginal = "";
        String reason = "";
        List<String> antiPatterns = new ArrayList<>();
    }

    private static final class BranchPlan {
        String kind = "";
        String condition = "";
        String setup = "";
        String reason = "";
    }

    private static BranchPlan inferBranchReachabilityPlan(TypeDeclaration<?> owner,
                                                          CallableDeclaration<?> entryCallable,
                                                          MutationConfig config) {
        if (entryCallable == null) return null;
        String text = entryCallable.toString();
        String ownerSimple = owner.getNameAsString();
        if (ownerSimple.equals("AssembledChronology") && text.contains("iBase") && text.contains("iBaseFlags") && text.contains("& 5")) {
            BranchPlan p = new BranchPlan();
            p.kind = "BASE_DELEGATION_FLAGS";
            p.condition = "iBase != null && (iBaseFlags & 5) == 5";
            p.setup = "org.joda.time.Chronology base = org.joda.time.chrono.ISOChronology.getInstanceUTC(); TestAssembledChronology subject = new TestAssembledChronology(base);";
            p.reason = "A TEST_STUB_SUBCLASS with no-op assemble(fields) keeps fields copied from the ISO base chronology, making AssembledChronology delegate date/time calculation to iBase.";
            return p;
        }
        return null;
    }

    private static void applyConcreteSubclass(MutationConfig config,
                                              MethodEntryResolver.Resolution r,
                                              TypeInfo owner,
                                              Candidate c) {
        String simpleRuntime = c.type.simpleName;
        String setup = buildConcreteSubclassSetup(c);
        String method = methodName(r.testEntryMethodName);
        String args = exampleArgumentsJoined(r.testEntryMethodName, config);
        String invocation = invocationStatement(r.testEntryMethodName, "subject", method, args, owner.simpleName);

        r.testReceiverStrategy = "CONCRETE_SUBCLASS_INHERITED_METHOD";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";
        r.testReceiverRuntimeClassName = simpleRuntime;
        r.testReceiverRuntimeSootClassName = c.type.fqn;
        r.testReceiverDeclaringClassName = owner.simpleName;
        r.testReceiverDispatchTarget = owner.simpleName + "." + displayMethod(r.testEntryMethodName);
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverConstruction = "Use existing concrete subclass " + simpleRuntime
                + " to instantiate the abstract entry owner " + owner.simpleName + ". Setup: " + setup;
        r.testReceiverResolutionReason = simpleRuntime + " is a concrete subclass of " + owner.simpleName
                + " and does not override " + displayMethod(r.testEntryMethodName)
                + "; therefore subject." + method + "(...) dispatches to the mutation method in " + owner.simpleName + ".";
        r.testReceiverNotes = r.testReceiverResolutionReason;
        r.notes.add("receiver resolver selected concrete subclass: " + r.testReceiverResolutionReason);
    }

    private static void applyStubFallback(MethodEntryResolver.Resolution r,
                                          TypeDeclaration<?> owner,
                                          String methodSig,
                                          CallableDeclaration<?> entryCallable,
                                          MutationConfig config) {
        String ownerSimple = owner.getNameAsString();
        String testClass = "Test" + ownerSimple;
        boolean constructorTarget = entryCallable instanceof ConstructorDeclaration || isConstructorSignature(methodSig);
        String method = methodName(methodSig);

        ConstructorPlan ctorPlan = chooseStubConstructor(owner, entryCallable, config);
        String setup;
        String invocation;
        if (constructorTarget) {
            String args = exampleArgsForCallable(entryCallable, config);
            setup = ctorPlan.prefixSetup + testClass + " subject = new " + testClass + "(" + args + ");";
            invocation = setup;
        } else {
            setup = ctorPlan.prefixSetup + testClass + " subject = new " + testClass + "(" + ctorPlan.callArgs + ");";
            if (r.branchReachabilitySetup != null && !r.branchReachabilitySetup.isBlank()) {
                setup = r.branchReachabilitySetup;
            }
            String args = exampleArgsForCallable(entryCallable, config);
            invocation = invocationStatement(methodSig, "subject", method, args, ownerSimple);
        }

        r.testReceiverStrategy = "TEST_STUB_SUBCLASS";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = constructorTarget ? "CONSTRUCTOR_INVOCATION" : "INSTANCE_METHOD_INVOCATION";
        r.testReceiverDeclaringClassName = ownerSimple;
        r.testReceiverRuntimeClassName = testClass;
        r.testReceiverRuntimeSootClassName = testClass;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverDispatchTarget = ownerSimple + "." + displayMethod(methodSig);
        r.testReceiverConstruction = "Generate a private static test stub subclass extending "
                + ownerSimple + "; call the protected constructor/method from the same package test and implement all abstract methods.";
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverResolutionReason = "Use TEST_STUB_SUBCLASS for abstract owner " + ownerSimple
                + " because concrete subclass construction was not proven safe by static analysis.";
        r.abstractMethodsToImplement = String.join(" || ", abstractMethodStubs(owner));
        r.allowedOverrides = r.abstractMethodsToImplement;
        r.forbiddenOverrides = joinNonBlank(" || ", String.join(" || ", finalMethods(owner)), generalForbiddenOverrideRules(owner));
        r.testStubClassTemplate = buildTestStubClassTemplate(owner, testClass, ctorPlan);
        r.testStubConstructorTemplate = ctorPlan.constructorTemplate;
        r.testReceiverNotes = r.testReceiverResolutionReason;
        r.testReceiverAntiPatterns = joinNonBlank(" || ",
                r.testReceiverAntiPatterns,
                "Do not replace TEST_STUB_SUBCLASS with an unverified concrete subclass such as Days or BuddhistChronology.",
                "Do not call a constructor as if it were a normal method.");
        r.notes.add(r.testReceiverResolutionReason);
    }

    private static final class ConstructorPlan {
        String declarationParams = "";
        String superArgs = "";
        String callArgs = "";
        String prefixSetup = "";
        String constructorTemplate = "";
    }

    private static ConstructorPlan chooseStubConstructor(TypeDeclaration<?> owner,
                                                         CallableDeclaration<?> entryCallable,
                                                         MutationConfig config) {
        ConstructorPlan plan = new ConstructorPlan();
        String ownerSimple = owner.getNameAsString();
        String testClass = "Test" + ownerSimple;
        CallableDeclaration<?> ctor = null;
        if ("AssembledChronology".equals(ownerSimple)) {
            plan.declarationParams = "org.joda.time.Chronology base";
            plan.superArgs = "base, null";
            plan.callArgs = "org.joda.time.chrono.ISOChronology.getInstanceUTC()";
            plan.constructorTemplate = testClass + "(" + plan.declarationParams + ") { super(" + plan.superArgs + "); } // super(...) must be first statement";
            return plan;
        }
        if ("ImpreciseDateTimeField".equals(ownerSimple)) {
            // Compile-ready and run-safe constructor for Joda-Time's abstract field.
            // Do NOT emit null/0L here: the production constructor dereferences
            // type.getDurationType(), and getDifferenceAsLong divides by iUnitMillis.
            plan.declarationParams = "";
            plan.superArgs = "org.joda.time.DateTimeFieldType.secondOfMinute(), 1000L";
            plan.callArgs = "";
            plan.constructorTemplate = testClass + "() { super(" + plan.superArgs + "); } // super(...) must be first statement";
            return plan;
        }
        if (entryCallable instanceof ConstructorDeclaration) {
            ctor = entryCallable;
        } else if (owner instanceof ClassOrInterfaceDeclaration) {
            List<ConstructorDeclaration> ctors = new ArrayList<>(((ClassOrInterfaceDeclaration) owner).getConstructors());
            ctors.removeIf(ConstructorDeclaration::isPrivate);
            ctors.sort(Comparator.comparingInt(c -> c.getParameters().size()));
            if (!ctors.isEmpty()) ctor = ctors.get(0);
        }
        if (ctor == null) {
            plan.constructorTemplate = testClass + "() { super(); }";
            return plan;
        }
        List<String> decl = new ArrayList<>();
        List<String> superArgs = new ArrayList<>();
        List<String> callArgs = new ArrayList<>();
        int i = 0;
        for (Parameter p : ctor.getParameters()) {
            String type = p.getType().asString();
            String name = p.getNameAsString();
            if (name == null || name.isBlank()) name = "arg" + i;
            decl.add(type + " " + name);
            superArgs.add(name);
            callArgs.add(exampleValueForParameter(type, name, i, config));
            i++;
        }
        plan.declarationParams = String.join(", ", decl);
        plan.superArgs = String.join(", ", superArgs);
        plan.callArgs = String.join(", ", callArgs);
        plan.constructorTemplate = testClass + "(" + plan.declarationParams + ") { super(" + plan.superArgs + "); } // super(...) must be first statement";
        return plan;
    }

    private static String buildTestStubClassTemplate(TypeDeclaration<?> owner, String testClass, ConstructorPlan ctorPlan) {
        List<String> parts = new ArrayList<>();
        parts.add("private static final class " + testClass + " extends " + owner.getNameAsString() + " {");
        parts.add("  " + ctorPlan.constructorTemplate);
        for (String stub : abstractMethodStubs(owner)) {
            parts.add("  " + stub);
        }
        parts.add("}");
        return String.join(" ", parts);
    }

    private static List<String> abstractMethodStubs(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<>();
        String ownerSimple = owner == null ? "" : owner.getNameAsString();
        List<String> special = specialCompileReadyAbstractStubs(ownerSimple);
        if (!special.isEmpty()) {
            return special;
        }
        for (MethodDeclaration m : owner.getMethods()) {
            if (!m.isAbstract()) continue;
            String access = m.isProtected() ? "protected " : (m.isPublic() ? "public " : "");
            String params = m.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .collect(Collectors.joining(", "));
            String type = m.getType().asString();
            String body;
            if ("void".equals(simpleType(type))) {
                body = " { }";
            } else {
                body = " { return " + defaultReturnExpression(type, m.getNameAsString()) + "; }";
            }
            out.add("@Override " + access + type + " " + m.getNameAsString() + "(" + params + ")" + body);
        }
        return out;
    }

    /**
     * Compile-ready closure stubs for abstract owners that repeatedly failed javac
     * because only the current class's abstract methods were emitted.  These
     * templates intentionally include inherited abstract methods required by
     * the project bytecode.  The LLM should copy the generated testStubClassTemplate
     * instead of trying to infer the abstract closure itself.
     */
    private static List<String> specialCompileReadyAbstractStubs(String ownerSimple) {
        List<String> out = new ArrayList<>();
        if ("ImpreciseDateTimeField".equals(ownerSimple)) {
            out.add("@Override public int get(long instant) { return (int) (instant / getDurationUnitMillis()); }");
            out.add("@Override public long set(long instant, int value) { return value * getDurationUnitMillis(); }");
            out.add("@Override public long add(long instant, int value) { return instant + ((long) value) * getDurationUnitMillis(); }");
            out.add("@Override public long add(long instant, long value) { return instant + value * getDurationUnitMillis(); }");
            out.add("@Override public org.joda.time.DurationField getRangeDurationField() { return null; }");
            out.add("@Override public long roundFloor(long instant) { long unit = getDurationUnitMillis(); return (instant / unit) * unit; }");
            out.add("@Override public int getMinimumValue() { return Integer.MIN_VALUE; }");
            out.add("@Override public int getMaximumValue() { return Integer.MAX_VALUE; }");
            return out;
        }
        if ("AssembledChronology".equals(ownerSimple)) {
            out.add("@Override protected void assemble(org.joda.time.chrono.AssembledChronology.Fields fields) { }");
            out.add("@Override public String toString() { return \"TestAssembledChronology\"; }");
            return out;
        }
        return out;
    }

    private static List<String> finalMethods(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<>();
        for (MethodDeclaration m : owner.getMethods()) {
            if (!m.isFinal()) continue;
            out.add(methodDisplaySignature(m));
        }
        return out;
    }


    private static boolean applyKnownCollaboratorConstruction(MethodEntryResolver.Resolution r,
                                                             TypeDeclaration<?> owner,
                                                             CallableDeclaration<?> entryCallable) {
        String ownerSimple = owner.getNameAsString();
        if (!"JsonGeneratorDelegate".equals(ownerSimple)) {
            return false;
        }
        String method = methodName(r.testEntryMethodName);
        String args = entryCallable == null ? "" : exampleArgsForCallable(entryCallable, null);
        if (method.equals("writeString") && entryCallable != null && entryCallable.getParameters().size() == 3) {
            args = "new char[] {'x', 'y', 'z'}, 1, 1";
        }
        String setup = "java.io.StringWriter out = new java.io.StringWriter(); "
                + "com.fasterxml.jackson.core.JsonGenerator delegate = new com.fasterxml.jackson.core.JsonFactory().createGenerator(out); "
                + "JsonGeneratorDelegate subject = new JsonGeneratorDelegate(delegate);";
        String invocation = invocationStatement(r.testEntryMethodName, "subject", method, args, ownerSimple);
        r.testReceiverStrategy = "CONCRETE_COLLABORATOR_FACTORY";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";
        r.testReceiverRuntimeClassName = ownerSimple;
        r.testReceiverRuntimeSootClassName = ownerSimple;
        r.testReceiverDeclaringClassName = ownerSimple;
        r.testReceiverDispatchTarget = ownerSimple + "." + displayMethod(r.testEntryMethodName);
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverFactoryMethod = "new com.fasterxml.jackson.core.JsonFactory().createGenerator(java.io.Writer)";
        r.testReceiverAntiPatterns = joinNonBlank(" || ", r.testReceiverAntiPatterns,
                "Do not use new JsonGeneratorDelegate(null) for forwarding tests.",
                "Do not hand-write a JsonGenerator subclass; use JsonFactory.createGenerator(StringWriter).");
        r.testReceiverResolutionReason = "JsonGeneratorDelegate forwards to an abstract JsonGenerator collaborator; static analysis selected a concrete JsonFactory/StringWriter collaborator.";
        r.testReceiverConstruction = r.testReceiverResolutionReason + " Setup: " + setup;
        r.testReceiverNotes = r.testReceiverResolutionReason;
        r.notes.add("receiver resolver selected concrete collaborator factory for JsonGeneratorDelegate");
        return true;
    }

    private static boolean applyStaticFactoryBuilder(MethodEntryResolver.Resolution r,
                                                     CompilationUnit ownerCu,
                                                     TypeDeclaration<?> owner,
                                                     MethodDeclaration entryMethod) {
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ClassOrInterfaceDeclaration ownerClass = (ClassOrInterfaceDeclaration) owner;
        if (ownerClass.isInterface() || ownerClass.isAbstract()) {
            return false;
        }

        BuilderFactoryPlan plan = findBuilderFactoryPlan(ownerCu, ownerClass, entryMethod);
        if (plan == null) {
            return false;
        }

        String ownerSimple = ownerClass.getNameAsString();
        String setup = ownerSimple + " subject = " + plan.setupExpression + ";";
        String method = methodName(r.testEntryMethodName);
        String args = exampleArgumentsJoinedForMethod(r.testEntryMethodName, method, null);
        String invocation = invocationStatement(r.testEntryMethodName, "subject", method, args, ownerSimple);

        r.testReceiverStrategy = "STATIC_FACTORY_BUILDER";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";
        r.testReceiverRuntimeClassName = ownerSimple;
        r.testReceiverRuntimeSootClassName = qualifiedName(ownerCu, ownerSimple);
        r.testReceiverDeclaringClassName = ownerSimple;
        r.testReceiverDispatchTarget = ownerSimple + "." + displayMethod(r.testEntryMethodName);
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverFactoryMethod = plan.factoryCall;
        r.testReceiverBuilderClassName = plan.builderClassName;
        r.testReceiverBuilderTerminalMethod = plan.terminalMethodName;
        r.testReceiverBuilderSetupChain = plan.builderChain;
        r.testReceiverAntiPatterns = String.join(" || ", plan.antiPatterns);

        r.testReceiverConstruction = "Use static factory/builder construction: " + setup
                + " Do not call new " + ownerSimple + "(), new " + simpleType(plan.builderClassName)
                + "(), or build() unless the terminal method is build.";
        r.testReceiverResolutionReason = "The declaring class " + ownerSimple
                + " is concrete but not directly instantiable. Static analysis found factory "
                + plan.factoryCall + " and builder terminal method " + plan.terminalMethodName + "().";
        r.testReceiverNotes = r.testReceiverResolutionReason + " " + String.join(" ", plan.antiPatterns);
        r.notes.add("receiver resolver selected static factory builder: " + r.testReceiverResolutionReason);
        return true;
    }

    private static BuilderFactoryPlan findBuilderFactoryPlan(CompilationUnit cu,
                                                             ClassOrInterfaceDeclaration owner,
                                                             MethodDeclaration entryMethod) {
        String ownerSimple = owner.getNameAsString();

        // Direct factory: static method returning the owner itself.
        for (MethodDeclaration m : owner.getMethods()) {
            if (!m.isStatic() || hasPrivateModifier(m) || !m.getParameters().isEmpty()) {
                continue;
            }
            if (simpleType(m.getType().asString()).equals(ownerSimple)) {
                BuilderFactoryPlan plan = new BuilderFactoryPlan();
                plan.factoryCall = ownerSimple + "." + m.getNameAsString() + "()";
                plan.builderClassName = "";
                plan.terminalMethodName = "";
                plan.builderChain = "";
                plan.setupExpression = plan.factoryCall;
                plan.antiPatterns.add("Do not call new " + ownerSimple + "() when a static factory is required.");
                return plan;
            }
        }

        // Builder factory: static method returning a builder/helper type, whose terminal
        // method returns the owner type. TextStyle.builder().get() is the motivating case.
        for (MethodDeclaration factory : owner.getMethods()) {
            if (!factory.isStatic() || hasPrivateModifier(factory) || !factory.getParameters().isEmpty()) {
                continue;
            }
            String builderType = simpleType(factory.getType().asString());
            if (builderType.equals(ownerSimple)) {
                continue;
            }

            Optional<ClassOrInterfaceDeclaration> builderOpt = findClassLike(cu, builderType);
            if (builderOpt.isEmpty()) {
                continue;
            }
            ClassOrInterfaceDeclaration builder = builderOpt.get();
            Optional<MethodDeclaration> terminalOpt = findBuilderTerminal(builder, ownerSimple);
            if (terminalOpt.isEmpty()) {
                continue;
            }

            MethodDeclaration terminal = terminalOpt.get();
            List<String> chainCalls = builderSetupCalls(cu, owner, builder, entryMethod);
            String chain = chainCalls.isEmpty() ? "" : "." + String.join(".", chainCalls);

            BuilderFactoryPlan plan = new BuilderFactoryPlan();
            plan.factoryCall = ownerSimple + "." + factory.getNameAsString() + "()";
            plan.builderClassName = ownerSimple + "." + builder.getNameAsString();
            plan.terminalMethodName = terminal.getNameAsString();
            plan.builderChain = chainCalls.isEmpty() ? "" : String.join(".", chainCalls);
            plan.setupExpression = plan.factoryCall + chain + "." + plan.terminalMethodName + "()";
            plan.antiPatterns.add("Do not call new " + ownerSimple + "(); use " + plan.factoryCall + ".");
            plan.antiPatterns.add("Do not call new " + plan.builderClassName + "(); the builder constructor may be private.");
            if (!"build".equals(plan.terminalMethodName)) {
                plan.antiPatterns.add("Do not call builder.build(); this builder exposes "
                        + plan.terminalMethodName + "() as the terminal method.");
            }
            return plan;
        }

        return null;
    }

    private static Optional<ClassOrInterfaceDeclaration> findClassLike(CompilationUnit cu, String simpleName) {
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (c.getNameAsString().equals(simpleName)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static Optional<MethodDeclaration> findBuilderTerminal(ClassOrInterfaceDeclaration builder, String ownerSimple) {
        List<String> preferred = Arrays.asList("get", "build", "create", "newInstance");
        List<MethodDeclaration> candidates = new ArrayList<>();
        for (MethodDeclaration m : builder.getMethods()) {
            if (m.isStatic() || hasPrivateModifier(m) || !m.getParameters().isEmpty()) {
                continue;
            }
            if (simpleType(m.getType().asString()).equals(ownerSimple)) {
                candidates.add(m);
            }
        }
        candidates.sort(Comparator.comparingInt(m -> {
            int i = preferred.indexOf(m.getNameAsString());
            return i < 0 ? 100 : i;
        }));
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private static List<String> builderSetupCalls(CompilationUnit cu,
                                                  ClassOrInterfaceDeclaration owner,
                                                  ClassOrInterfaceDeclaration builder,
                                                  MethodDeclaration entryMethod) {
        List<String> out = new ArrayList<>();
        String builderSimple = builder.getNameAsString();
        String entryText = entryMethod == null ? "" : entryMethod.toString();

        // Prefer setters that are likely to make the mutation reachable and observable.
        List<String> preferredNames = new ArrayList<>();
        if (entryText.contains("case CENTER") || entryText.contains("Alignment.CENTER")) {
            preferredNames.add("setAlignment");
        }
        preferredNames.addAll(Arrays.asList("setMaxWidth", "setIndent", "setLeftPad", "setMinWidth", "setScalable"));

        for (String wanted : preferredNames) {
            MethodDeclaration setter = null;
            for (MethodDeclaration m : builder.getMethods()) {
                if (!m.getNameAsString().equals(wanted) || m.getParameters().size() != 1) {
                    continue;
                }
                if (!simpleType(m.getType().asString()).equals(builderSimple)) {
                    continue;
                }
                String arg = builderSetterArgument(cu, owner, m, entryText);
                if (arg == null || arg.trim().isEmpty()) {
                    continue;
                }
                setter = m;
                out.add(m.getNameAsString() + "(" + arg + ")");
                break;
            }
            if (setter != null && out.size() >= 4) {
                break;
            }
        }

        return out;
    }

    private static String builderSetterArgument(CompilationUnit cu,
                                                ClassOrInterfaceDeclaration owner,
                                                MethodDeclaration setter,
                                                String entryText) {
        if (setter.getParameters().isEmpty()) {
            return "";
        }
        Type type = setter.getParameter(0).getType();
        String st = simpleType(type.asString());
        if ("boolean".equals(st) || "Boolean".equals(st)) {
            return "true";
        }
        if ("int".equals(st) || "Integer".equals(st)) {
            String n = setter.getNameAsString().toLowerCase(Locale.ROOT);
            if (n.contains("maxwidth") || n.contains("width")) {
                return "10";
            }
            return "0";
        }
        if ("long".equals(st) || "Long".equals(st)) return "0L";
        if ("float".equals(st) || "Float".equals(st)) return "0.0f";
        if ("double".equals(st) || "Double".equals(st)) return "0.0";
        if ("char".equals(st) || "Character".equals(st)) return "'x'";
        if ("String".equals(st) || "CharSequence".equals(st)) return "\"Hello\"";

        Optional<String> enumConst = enumConstantFor(cu, st, entryText);
        if (enumConst.isPresent()) {
            return owner.getNameAsString() + "." + st + "." + enumConst.get();
        }

        // Avoid recursive builder setters such as setTextStyle(TextStyle style).
        if (st.equals(owner.getNameAsString())) {
            return "";
        }
        return "";
    }

    private static Optional<String> enumConstantFor(CompilationUnit cu, String enumSimpleName, String entryText) {
        for (EnumDeclaration e : cu.findAll(EnumDeclaration.class)) {
            if (!e.getNameAsString().equals(enumSimpleName)) {
                continue;
            }
            if (entryText != null && entryText.contains("case CENTER")) {
                for (EnumConstantDeclaration c : e.getEntries()) {
                    if ("CENTER".equals(c.getNameAsString())) {
                        return Optional.of("CENTER");
                    }
                }
            }
            if (!e.getEntries().isEmpty()) {
                return Optional.of(e.getEntries().get(0).getNameAsString());
            }
        }
        return Optional.empty();
    }

    private static boolean hasPrivateModifier(NodeWithModifiers<?> n) {
        return n != null && n.hasModifier(Modifier.Keyword.PRIVATE);
    }

    private static String qualifiedName(CompilationUnit cu, String simpleName) {
        String pkg = packageName(cu, "");
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }

    private static String exampleArgumentsJoinedForMethod(String sig, String methodName, MutationConfig config) {
        List<String> ps = params(sig);
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            args.add(exampleValueForParameter(ps.get(i), "arg" + i, i, config));
        }
        return String.join(", ", args);
    }

    private static String exampleValueForTypeForMethod(String raw, String methodName) {
        String t = simpleType(raw);
        if ("CharSequence".equals(t) && "pad".equals(methodName)) {
            return "\"Hello\"";
        }
        return exampleValueForType(raw);
    }

    private static final class BuilderFactoryPlan {
        String factoryCall = "";
        String builderClassName = "";
        String terminalMethodName = "";
        String builderChain = "";
        String setupExpression = "";
        List<String> antiPatterns = new ArrayList<>();
    }

    private static Candidate findBestConcreteReceiver(List<TypeInfo> types, TypeInfo owner, String methodSig) {
        List<Candidate> candidates = new ArrayList<>();
        Map<String, TypeInfo> bySimple = new HashMap<>();
        Map<String, TypeInfo> byFqn = new HashMap<>();
        for (TypeInfo t : types) {
            bySimple.put(t.simpleName, t);
            byFqn.put(t.fqn, t);
        }
        for (TypeInfo t : types) {
            if (t.isInterface || t.isAbstract) {
                continue;
            }
            if (!isSubtypeOf(t, owner, bySimple, byFqn, new HashSet<String>())) {
                continue;
            }
            if (overridesMethod(t, methodSig)) {
                continue;
            }
            ConstructorInfo ctor = bestConstructor(t);
            if (ctor == null) {
                continue;
            }
            candidates.add(new Candidate(t, ctor, scoreCandidate(t, ctor, owner)));
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.score));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int scoreCandidate(TypeInfo t, ConstructorInfo ctor, TypeInfo owner) {
        int score = 0;
        score += ctor.paramTypes.size() * 10;
        if (!t.packageName.equals(owner.packageName)) {
            score += 5;
        }
        for (String p : ctor.paramTypes) {
            String s = simpleType(p);
            if ("Appendable".equals(s) || "StringBuilder".equals(s)) {
                score -= 20;
            }
            if ("String".equals(s) || "CharSequence".equals(s) || "Collection".equals(s) || "List".equals(s)) {
                score -= 5;
            }
        }
        return score;
    }

    private static ConstructorInfo bestConstructor(TypeInfo t) {
        List<ConstructorInfo> list = new ArrayList<>();
        for (ConstructorInfo c : t.constructors) {
            if (!"private".equals(c.access)) {
                list.add(c);
            }
        }
        if (list.isEmpty()) {
            list.add(new ConstructorInfo(t.simpleName, Collections.emptyList(), "package"));
        }
        list.sort(Comparator.comparingInt(c -> c.paramTypes.size()));
        return list.get(0);
    }

    private static String buildConcreteSubclassSetup(Candidate c) {
        List<String> prefixes = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (String p : c.ctor.paramTypes) {
            String st = simpleType(p);
            if ("Appendable".equals(st) || "StringBuilder".equals(st)) {
                prefixes.add("java.lang.StringBuilder output = new java.lang.StringBuilder();");
                args.add("output");
            } else if ("String".equals(st) || "CharSequence".equals(st)) {
                args.add("\"x\"");
            } else if ("Collection".equals(st) || "List".equals(st) || "Iterable".equals(st)) {
                prefixes.add("java.util.Collection<String> values = java.util.Arrays.asList(\"alpha\", \"beta\");");
                args.add("values");
            } else {
                args.add(exampleValueForType(p));
            }
        }
        prefixes.add(c.type.simpleName + " subject = new " + c.type.simpleName + "(" + String.join(", ", args) + ");");
        return String.join(" ", dedupe(prefixes));
    }

    private static boolean isSubtypeOf(TypeInfo t, TypeInfo owner,
                                       Map<String, TypeInfo> bySimple,
                                       Map<String, TypeInfo> byFqn,
                                       Set<String> seen) {
        if (t == null || owner == null || !seen.add(t.fqn)) {
            return false;
        }
        for (String p : t.parents) {
            String ps = simpleType(p);
            if (ps.equals(owner.simpleName) || p.equals(owner.fqn)) {
                return true;
            }
            TypeInfo pt = byFqn.get(p);
            if (pt == null) pt = bySimple.get(ps);
            if (pt != null && isSubtypeOf(pt, owner, bySimple, byFqn, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overridesMethod(TypeInfo t, String sig) {
        String name = methodName(sig);
        List<String> params = params(sig).stream().map(ReceiverResolver::simpleType).collect(Collectors.toList());
        for (MethodInfo m : t.methods) {
            if (!m.name.equals(name)) {
                continue;
            }
            if (m.params.size() != params.size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < params.size(); i++) {
                if (!simpleType(m.params.get(i)).equals(params.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static List<TypeInfo> parseProjectTypes(List<Path> sourceRoots) {
        List<TypeInfo> out = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setAttributeComments(false));
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> st = Files.walk(root)) {
                List<Path> files = st.filter(p -> p.toString().endsWith(".java"))
                        .limit(5000)
                        .collect(Collectors.toList());
                for (Path file : files) {
                    try {
                        com.github.javaparser.ParseResult<CompilationUnit> pr = parser.parse(file);
                        if (pr.getResult().isEmpty()) continue;
                        CompilationUnit cu = pr.getResult().get();
                        cu.getAllContainedComments().forEach(Comment::remove);
                        String pkg = packageName(cu, "");
                        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                            if (c.findAncestor(ClassOrInterfaceDeclaration.class).isPresent()) {
                                continue;
                            }
                            out.add(TypeInfo.from(cu, c, file.toString(), pkg));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static List<Path> findSourceRoots(MutationConfig config, String originJavaFile) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfDir(roots, sourceRootFromPath(originJavaFile));
        addIfDir(roots, sourceRootFromPath(config.filepath));

        Path p = Paths.get(config.filepath).toAbsolutePath().normalize();
        List<Path> ancestors = new ArrayList<>();
        for (Path x = p; x != null; x = x.getParent()) ancestors.add(x);
        String project = config.projectName == null ? "" : config.projectName.trim();
        for (Path a : ancestors) {
            if (!project.isEmpty() && a.getFileName() != null && a.getFileName().toString().equals(project)) {
                addIfDir(roots, a.resolve(Paths.get("src", "main", "java")));
                addIfDir(roots, a.resolve(Paths.get(project, "src", "main", "java")));
                try (Stream<Path> st = Files.list(a)) {
                    st.filter(Files::isDirectory).forEach(d -> addIfDir(roots, d.resolve(Paths.get("src", "main", "java"))));
                } catch (Throwable ignored) {
                }
            }
            addIfDir(roots, a.resolve(Paths.get("src", "main", "java")));
        }
        return new ArrayList<>(roots);
    }

    private static Path sourceRootFromPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        Path p = Paths.get(path).toAbsolutePath().normalize();
        String s = p.toString().replace('\\', '/');
        int idx = s.indexOf("/src/main/java/");
        if (idx >= 0) {
            return Paths.get(s.substring(0, idx + "/src/main/java".length()));
        }
        return null;
    }

    private static void addIfDir(Set<Path> roots, Path p) {
        if (p != null && Files.isDirectory(p)) {
            roots.add(p.toAbsolutePath().normalize());
        }
    }

    private static CompilationUnit parse(String file) throws Exception {
        JavaParser parser = new JavaParser(new ParserConfiguration().setAttributeComments(false));
        com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(Paths.get(file));
        if (result.getResult().isEmpty()) {
            throw new IllegalArgumentException("Parse failed: " + result.getProblems());
        }
        CompilationUnit cu = result.getResult().get();
        cu.getAllContainedComments().forEach(Comment::remove);
        return cu;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<TypeDeclaration<?>> findType(CompilationUnit cu, String className) {
        if (cu == null || className == null || className.trim().isEmpty()) {
            return Optional.empty();
        }
        String want = simpleType(className);
        List<TypeDeclaration> all = cu.findAll(TypeDeclaration.class);
        for (TypeDeclaration t : all) {
            if (t != null && t.getNameAsString().equals(want)) {
                return Optional.of((TypeDeclaration<?>) t);
            }
        }
        return Optional.empty();
    }

    private static Optional<CallableDeclaration<?>> findCallable(TypeDeclaration<?> owner, String sig) {
        if (isConstructorSignature(sig)) {
            String ownerSimple = owner.getNameAsString();
            List<String> ps = params(sig).stream().map(ReceiverResolver::simpleType).collect(Collectors.toList());
            if (owner instanceof ClassOrInterfaceDeclaration) {
                for (ConstructorDeclaration c : ((ClassOrInterfaceDeclaration) owner).getConstructors()) {
                    if (!c.getNameAsString().equals(ownerSimple) || c.getParameters().size() != ps.size()) {
                        continue;
                    }
                    boolean same = true;
                    for (int i = 0; i < ps.size(); i++) {
                        if (!simpleType(c.getParameter(i).getType().asString()).equals(ps.get(i))) {
                            same = false;
                            break;
                        }
                    }
                    if (same) return Optional.of(c);
                }
            }
            return Optional.empty();
        }
        return findMethod(owner, sig).map(m -> (CallableDeclaration<?>) m);
    }

    private static Optional<MethodDeclaration> findMethod(TypeDeclaration<?> owner, String sig) {
        String name = methodName(sig);
        List<String> ps = params(sig).stream().map(ReceiverResolver::simpleType).collect(Collectors.toList());
        return owner.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(name))
                .filter(m -> m.getParameters().size() == ps.size())
                .filter(m -> {
                    for (int i = 0; i < ps.size(); i++) {
                        if (!simpleType(m.getParameter(i).getType().asString()).equals(ps.get(i))) return false;
                    }
                    return true;
                })
                .findFirst();
    }

    private static String packageName(CompilationUnit cu, String fallback) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(fallback == null ? "" : fallback);
    }

    private static String ownerKind(TypeDeclaration<?> owner) {
        if (owner instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) owner;
            if (c.isInterface()) return "INTERFACE";
            if (c.isAbstract()) return "ABSTRACT_CLASS";
        }
        return "CONCRETE_CLASS";
    }

    private static boolean isStaticInvocation(MethodEntryResolver.Resolution r) {
        return "STATIC_METHOD_INVOCATION".equals(r.entryInvocationKind)
                || "STATIC_NO_RECEIVER".equals(r.testReceiverStrategy);
    }

    private static boolean isConstructorSignature(String sig) {
        if (sig == null) return false;
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        return lp > 0 && !(us > 0 && us < lp);
    }

    private static String methodName(String sig) {
        if (sig == null) return "";
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        if (lp < 0) return sig;
        if (us > 0 && us < lp) return sig.substring(us + 1, lp).trim();
        return sig.substring(0, lp).trim();
    }

    private static String returnTypeForInvocation(String sig, String ownerSimple) {
        if (sig == null) return "Object";
        int us = sig.indexOf('_');
        int lp = sig.indexOf('(');
        if (us > 0 && us < lp) {
            String ret = simpleType(sig.substring(0, us));
            return "void".equals(ret) ? "" : ret;
        }
        return ownerSimple;
    }

    private static String displayMethod(String sig) {
        try {
            return PromptSignatureFormatter.method(sig);
        } catch (Throwable ignored) {
            return sig;
        }
    }

    private static List<String> params(String sig) {
        int lp = sig == null ? -1 : sig.indexOf('(');
        int rp = sig == null ? -1 : sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) return Collections.emptyList();
        String inside = sig.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) return Collections.emptyList();
        return Arrays.stream(inside.split(",")).map(String::trim).collect(Collectors.toList());
    }

    private static String exampleArgumentsJoined(String sig, MutationConfig config) {
        List<String> ps = params(sig);
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            args.add(exampleValueForParameter(ps.get(i), "arg" + i, i, config));
        }
        return String.join(", ", args);
    }

    private static String firstArgValue(CallableDeclaration<?> c, MutationConfig config) {
        if (c == null || c.getParameters().isEmpty()) return "";
        Parameter p = c.getParameter(0);
        return exampleValueForParameter(p.getType().asString(), p.getNameAsString(), 0, config);
    }

    private static String exampleArgsForCallable(CallableDeclaration<?> c, MutationConfig config) {
        if (c == null) return "";
        List<String> args = new ArrayList<>();
        for (int i = 0; i < c.getParameters().size(); i++) {
            Parameter p = c.getParameter(i);
            args.add(exampleValueForParameter(p.getType().asString(), p.getNameAsString(), i, config));
        }
        return String.join(", ", args);
    }

    private static String exampleValueForParameter(String raw, String name, int index, MutationConfig config) {
        String t = simpleType(raw);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("ReadablePartial".equals(t)) {
            if (n.contains("end") || index == 1) {
                return "new org.joda.time.Partial(new org.joda.time.DateTimeFieldType[] { org.joda.time.DateTimeFieldType.year(), org.joda.time.DateTimeFieldType.monthOfYear(), org.joda.time.DateTimeFieldType.dayOfMonth() }, new int[] {2001, 1, 1})";
            }
            return "new org.joda.time.Partial(new org.joda.time.DateTimeFieldType[] { org.joda.time.DateTimeFieldType.year(), org.joda.time.DateTimeFieldType.monthOfYear(), org.joda.time.DateTimeFieldType.dayOfMonth() }, new int[] {2000, 1, 1})";
        }
        if ("PeriodType".equals(t)) return "org.joda.time.PeriodType.yearMonthDay()";
        if ("DataInput".equals(t)) return "new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0}))";
        if ("InputStream".equals(t)) return "new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0})";
        if ("Chronology".equals(t)) return "org.joda.time.chrono.ISOChronology.getInstanceUTC()";
        if ("DateTimeZone".equals(t)) return "org.joda.time.DateTimeZone.UTC";
        if ("long".equals(t) || "Long".equals(t)) {
            if (n.contains("minuend")) return "2500L";
            if (n.contains("subtrahend")) return "1000L";
            if (n.contains("instant")) return index == 0 ? "2500L" : "1000L";
            if (n.contains("unit")) return "1000L";
            if (n.contains("value") || n.contains("duration") || n.contains("millis")) return "1000L";
            return "1000L";
        }
        if ("int".equals(t) || "Integer".equals(t)) {
            if (n.contains("year")) return "2004";
            if (n.contains("month")) return "6";
            if (n.contains("day")) return "9";
            if (n.contains("hour")) return "13";
            if (n.contains("minute")) return "14";
            if (n.contains("second")) return "15";
            if (n.contains("millis")) return "16";
            if (n.contains("value")) return "7";
            String diff = config == null || config.mutationStatement == null ? "" : config.mutationStatement;
            if (diff.contains("--") || diff.contains("++") || diff.contains("=> -") || diff.contains("-")) return "7";
            return "0";
        }
        return exampleValueForType(raw);
    }

    private static String invocationStatement(String sig, String receiver, String method, String args, String ownerSimple) {
        String resultType = returnTypeForInvocation(sig, ownerSimple);
        if (resultType == null || resultType.isBlank()) {
            return receiver + "." + method + "(" + args + ");";
        }
        return resultType + " result = " + receiver + "." + method + "(" + args + ");";
    }

    private static String defaultReturnExpression(String rawType, String methodName) {
        String t = simpleType(rawType);
        if ("boolean".equals(t) || "Boolean".equals(t)) return "false";
        if ("byte".equals(t)) return "(byte) 0";
        if ("short".equals(t)) return "(short) 0";
        if ("int".equals(t) || "Integer".equals(t)) return "0";
        if ("long".equals(t) || "Long".equals(t)) return "0L";
        if ("float".equals(t) || "Float".equals(t)) return "0.0f";
        if ("double".equals(t) || "Double".equals(t)) return "0.0";
        if ("char".equals(t) || "Character".equals(t)) return "'x'";
        if ("String".equals(t)) return "\"\"";
        if ("DurationFieldType".equals(t)) return "org.joda.time.DurationFieldType.days()";
        if ("DateTimeFieldType".equals(t)) return "org.joda.time.DateTimeFieldType.dayOfMonth()";
        if ("PeriodType".equals(t)) return "org.joda.time.PeriodType.days()";
        if ("Chronology".equals(t)) return "org.joda.time.chrono.ISOChronology.getInstanceUTC()";
        if ("DateTimeZone".equals(t)) return "org.joda.time.DateTimeZone.UTC";
        return "null";
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        List<String> out = new ArrayList<>();
        if (parts != null) {
            for (String p : parts) {
                if (p != null && !p.trim().isEmpty()) out.add(p.trim());
            }
        }
        return String.join(delimiter, out);
    }

    private static String exampleValueForType(String raw) {
        String t = simpleType(raw);
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
            case "Appendable": return "new java.lang.StringBuilder()";
            case "DataInput": return "new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0}))";
            case "InputStream": return "new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0})";
            case "Collection":
            case "List":
            case "Iterable": return "java.util.Arrays.asList(\"alpha\", \"beta\")";
            default:
                if (t.endsWith("[]")) return "new " + t.substring(0, t.length() - 2) + "[0]";
                return "null";
        }
    }

    private static String simpleType(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace("$", ".");
        s = s.replaceAll("<.*>", "");
        s = s.replace("...", "[]");
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : (b == null ? "" : b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private static final class TypeInfo {
        String packageName;
        String simpleName;
        String fqn;
        boolean isAbstract;
        boolean isInterface;
        List<String> parents = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<ConstructorInfo> constructors = new ArrayList<>();

        static TypeInfo from(CompilationUnit cu, TypeDeclaration<?> td, String file) {
            return from(cu, td, file, packageName(cu, ""));
        }

        static TypeInfo from(CompilationUnit cu, TypeDeclaration<?> td, String file, String pkg) {
            TypeInfo t = new TypeInfo();
            t.packageName = pkg == null ? "" : pkg;
            t.simpleName = td.getNameAsString();
            t.fqn = t.packageName.isEmpty() ? t.simpleName : t.packageName + "." + t.simpleName;
            if (td instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
                t.isInterface = c.isInterface();
                t.isAbstract = c.isAbstract() || c.isInterface();
                c.getExtendedTypes().forEach(x -> t.parents.add(x.getNameAsString()));
                c.getImplementedTypes().forEach(x -> t.parents.add(x.getNameAsString()));
            }
            for (MethodDeclaration m : td.getMethods()) {
                t.methods.add(new MethodInfo(m.getNameAsString(), m.getParameters().stream()
                        .map(p -> p.getType().asString()).collect(Collectors.toList()), m.isAbstract()));
            }
            if (td instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
                for (ConstructorDeclaration ctor : c.getConstructors()) {
                    t.constructors.add(new ConstructorInfo(t.simpleName, ctor.getParameters().stream()
                            .map(p -> p.getType().asString()).collect(Collectors.toList()), accessOf(ctor)));
                }
            }
            return t;
        }
    }

    private static String accessOf(NodeWithModifiers<?> n) {
        if (n.hasModifier(Modifier.Keyword.PUBLIC)) return "public";
        if (n.hasModifier(Modifier.Keyword.PROTECTED)) return "protected";
        if (n.hasModifier(Modifier.Keyword.PRIVATE)) return "private";
        return "package";
    }

    private static final class MethodInfo {
        final String name;
        final List<String> params;
        final boolean isAbstract;
        MethodInfo(String name, List<String> params, boolean isAbstract) {
            this.name = name;
            this.params = params;
            this.isAbstract = isAbstract;
        }
    }

    private static final class ConstructorInfo {
        final String name;
        final List<String> paramTypes;
        final String access;
        ConstructorInfo(String name, List<String> paramTypes, String access) {
            this.name = name;
            this.paramTypes = paramTypes;
            this.access = access;
        }
    }

    private static final class Candidate {
        final TypeInfo type;
        final ConstructorInfo ctor;
        final int score;
        Candidate(TypeInfo type, ConstructorInfo ctor, int score) {
            this.type = type;
            this.ctor = ctor;
            this.score = score;
        }
    }
}
