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
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import org.model.MutationConfig;

/**
 * Resolve the callable that generated tests should target.
 *
 * Key rule for method-level mutation testing:
 * 1) If the mutation method itself can be legally called by a generated test,
 * use it directly. This includes package-private/protected members when the
 * generated test is placed in the same package.
 * 2) Only if the mutation method is not directly testable, e.g. private method
 * or method inside a private nested class, walk the reverse call chain to find
 * the nearest directly testable caller.
 * 3) If no accessible caller is found, mark it as reflection fallback.
 *
 * This implementation is source-level and same-file oriented. It is
 * conservative
 * but suitable for deciding whether a generated JUnit test will compile without
 * reflection.
 */
public final class MethodEntryResolver {

    private MethodEntryResolver() {
    }

    public static Resolution resolve(MutationConfig config) {
        try {
            String originJava = originJavaFile(config);
            File f = new File(originJava);
            if (!f.isFile()) {
                return ReceiverResolver.enrich(config, Resolution.reflectionFallback(config, "original java file not found: " + originJava), originJava);
            }

            CompilationUnit cu = parse(originJava);
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString())
                    .orElse(config.packageName == null ? "" : config.packageName);

            List<CallableNode> callables = collectCallables(cu, packageName);
            if (callables.isEmpty()) {
                return ReceiverResolver.enrich(config, Resolution.reflectionFallback(config, "no callable declarations found in: " + originJava), originJava);
            }

            CallableNode target = findTarget(callables, config.classNameF, config.methodName,
                    config.lineNo, config.mutationStatement);
            if (target == null) {
                return ReceiverResolver.enrich(config, Resolution.reflectionFallback(config,
                        "target method not found by signature: " + config.classNameF + "#" + config.methodName), originJava);
            }

            // First-level classification: can a generated test legally call the mutation
            // method itself?
            // Package-private/protected entries are legal as long as the generated test
            // uses the same package.
            if (isDirectlyTestableFromSamePackage(target)) {
                return ReceiverResolver.enrich(config, Resolution.directAccessible(config, target), originJava);
            }

            // Only private/inaccessible targets need caller ascent.
            Map<CallableNode, LinkedHashSet<CallableNode>> reverseCallers = buildReverseCallers(callables);
            Resolution r = bfsNearestAccessibleEntry(config, target, reverseCallers);
            if (r != null) {
                return ReceiverResolver.enrich(config, r, originJava);
            }

            String access = accessOf(target.callable) + " in " + ownerAccessPath(target.owner);
            Resolution fallback = Resolution.reflectionFallback(config,
                    "no directly testable caller found in same source file; target access = " + access);
            fallback.mutationClassName = target.classPath;
            fallback.mutationSootClassName = target.classPath;
            fallback.mutationMethodName = target.signature;
            fallback.testEntryClassName = target.classPath;
            fallback.testEntrySootClassName = target.classPath;
            fallback.testEntryMethodName = target.signature;
            fallback.testGenerationPackage = target.packageName;
            fallback.callChain = List.of(target.display());
            return ReceiverResolver.enrich(config, fallback, originJava);
        } catch (Throwable t) {
            return Resolution.reflectionFallback(config, "resolver failed: " + t.getClass().getSimpleName() + ": "
                    + String.valueOf(t.getMessage()));
        }
    }

    private static String originJavaFile(MutationConfig config) {
        return Paths.get(config.filepath).getParent().getParent().getParent().toString()
                + "/original/" + config.classNameF + ".java";
    }

    private static CompilationUnit parse(String javaFile) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
        JavaParser parser = new JavaParser(cfg);
        com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(Path.of(javaFile));
        if (result.getResult().isEmpty()) {
            throw new IllegalArgumentException("Parse failed: " + result.getProblems());
        }
        CompilationUnit cu = result.getResult().get();
        cu.getAllContainedComments().forEach(Comment::remove);
        return cu;
    }

    private static List<CallableNode> collectCallables(CompilationUnit cu, String packageName) {
        List<CallableNode> out = new ArrayList<>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            TypeDeclaration<?> owner = md.findAncestor(TypeDeclaration.class).orElse(null);
            if (owner != null) {
                out.add(new CallableNode(packageName, owner, md));
            }
        }
        for (ConstructorDeclaration cd : cu.findAll(ConstructorDeclaration.class)) {
            TypeDeclaration<?> owner = cd.findAncestor(TypeDeclaration.class).orElse(null);
            if (owner != null) {
                out.add(new CallableNode(packageName, owner, cd));
            }
        }
        return out;
    }

    private static CallableNode findTarget(List<CallableNode> callables, String classPathOrNull, String signature,
            String lineNo, String mutationStatement) {
        List<CallableNode> hits = callables.stream()
                .filter(c -> sameClass(c.classPath, classPathOrNull))
                .filter(c -> signatureEquals(c.signature, signature))
                .collect(Collectors.toList());

        if (!hits.isEmpty()) {
            CallableNode first = hits.get(0);
            // Important nested-class case: StrMatcher declares an abstract
            // isMatch(char[],int,int,int), while the actual mutant is often in a
            // concrete nested class such as StrMatcher.CharMatcher.  If the direct
            // hit is abstract/no-body, prefer the concrete implementation whose
            // source range contains the mutation line.
            if (isAbstractOrBodyless(first)) {
                CallableNode concrete = findConcreteImplementationByLineOrText(
                        callables, signature, lineNo, mutationStatement, classPathOrNull);
                if (concrete != null) {
                    return concrete;
                }
            }
            return first;
        }

        // Loose fallback: if the class name in config is not source-level class path,
        // search by signature in the whole file. Prefer the callable whose range
        // contains the mutation line; otherwise only accept a unique hit.
        hits = callables.stream()
                .filter(c -> signatureEquals(c.signature, signature))
                .collect(Collectors.toList());
        CallableNode byLine = pickByLine(hits, lineNo);
        if (byLine != null) {
            return byLine;
        }
        CallableNode byText = pickByMutationText(hits, mutationStatement);
        if (byText != null) {
            return byText;
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static boolean isAbstractOrBodyless(CallableNode c) {
        if (c == null) {
            return true;
        }
        if (c.callable instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) c.callable;
            return md.isAbstract() || md.getBody().isEmpty();
        }
        return false;
    }

    private static CallableNode findConcreteImplementationByLineOrText(
            List<CallableNode> callables, String signature, String lineNo, String mutationStatement, String outerClass) {
        List<CallableNode> concrete = callables.stream()
                .filter(c -> signatureEquals(c.signature, signature))
                .filter(c -> !isAbstractOrBodyless(c))
                .filter(c -> outerClass == null || outerClass.isBlank()
                        || sameClass(c.classPath, outerClass)
                        || c.classPath.replace('$', '.').startsWith(outerClass.replace('$', '.') + ".")
                        || outerClass.replace('$', '.').endsWith("." + c.classPath.replace('$', '.')))
                .collect(Collectors.toList());
        CallableNode byLine = pickByLine(concrete, lineNo);
        if (byLine != null) {
            return byLine;
        }
        CallableNode byText = pickByMutationText(concrete, mutationStatement);
        if (byText != null) {
            return byText;
        }
        return concrete.size() == 1 ? concrete.get(0) : null;
    }

    private static CallableNode pickByLine(List<CallableNode> candidates, String lineNo) {
        int line = parseLine(lineNo);
        if (line <= 0 || candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<CallableNode> hits = candidates.stream()
                .filter(c -> c.callable.getRange().isPresent())
                .filter(c -> {
                    com.github.javaparser.Range r = c.callable.getRange().get();
                    return line >= r.begin.line && line <= r.end.line;
                })
                .collect(Collectors.toList());
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static int parseLine(String lineNo) {
        if (lineNo == null || lineNo.isBlank()) {
            return -1;
        }
        try {
            String s = lineNo.trim();
            int dot = s.indexOf('.');
            if (dot >= 0) {
                s = s.substring(0, dot);
            }
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static CallableNode pickByMutationText(List<CallableNode> candidates, String mutationStatement) {
        if (mutationStatement == null || mutationStatement.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String needle = normalizeMutationText(mutationStatement);
        if (needle.isEmpty()) {
            return null;
        }
        List<CallableNode> hits = candidates.stream()
                .filter(c -> normalizeMutationText(c.callable.toString()).contains(needle)
                        || anyMutationSideMatches(c.callable.toString(), mutationStatement))
                .collect(Collectors.toList());
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static boolean anyMutationSideMatches(String body, String mutationStatement) {
        if (body == null || mutationStatement == null) {
            return false;
        }
        String b = normalizeMutationText(body);
        for (String part : mutationStatement.split("=>|->|→")) {
            String p = normalizeMutationText(part);
            if (!p.isEmpty() && b.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMutationText(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "");
    }

    private static Map<CallableNode, LinkedHashSet<CallableNode>> buildReverseCallers(List<CallableNode> callables) {
        Map<CallableNode, LinkedHashSet<CallableNode>> reverse = new LinkedHashMap<>();
        for (CallableNode c : new ArrayList<>(callables)) {
            reverse.put(c, new LinkedHashSet<>());
        }

        Map<String, List<CallableNode>> byNameArity = new HashMap<>();
        for (CallableNode callee : new ArrayList<>(callables)) {
            String key = refKey(callee.isConstructor, callee.name, callee.arity);
            byNameArity.computeIfAbsent(key, k -> new ArrayList<>()).add(callee);
        }

        for (CallableNode caller : new ArrayList<>(callables)) {
            List<CallRef> refs = collectRefs(caller.callable);
            for (CallRef ref : refs) {
                List<CallableNode> candidates = byNameArity.getOrDefault(refKey(ref.constructor, ref.name, ref.arity),
                        List.of());
                for (CallableNode callee : new ArrayList<>(candidates)) {
                    if (caller == callee) {
                        continue;
                    }
                    if (explicitScopeCompatible(caller, callee, ref)) {
                        reverse.get(callee).add(caller);
                    }
                }
            }
        }
        return reverse;
    }

    private static List<CallRef> collectRefs(CallableDeclaration<?> callable) {
        List<CallRef> refs = new ArrayList<>();

        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String scope = call.getScope().map(Object::toString).orElse("");
            refs.add(new CallRef(false, call.getNameAsString(), call.getArguments().size(), scope));
        }

        for (ObjectCreationExpr oce : callable.findAll(ObjectCreationExpr.class)) {
            refs.add(new CallRef(true, oce.getType().getNameAsString(), oce.getArguments().size(),
                    oce.getScope().map(Object::toString).orElse("")));
        }

        for (ExplicitConstructorInvocationStmt ecis : callable.findAll(ExplicitConstructorInvocationStmt.class)) {
            // this(...) calls another constructor of the same class. super(...) is kept as
            // a
            // loose ref but will usually not match a same-file constructor.
            String name = callable.findAncestor(TypeDeclaration.class)
                    .map(td -> td.getNameAsString())
                    .orElse("");
            refs.add(new CallRef(true, name, ecis.getArguments().size(), ecis.isThis() ? "this" : "super"));
        }
        return refs;
    }

    private static boolean explicitScopeCompatible(CallableNode caller, CallableNode callee, CallRef ref) {
        if (ref.constructor) {
            return true;
        }
        if (ref.scope == null || ref.scope.isBlank()) {
            return true;
        }
        String s = ref.scope.replace(" ", "");
        String calleeOwner = callee.owner.getNameAsString();
        String callerOwner = caller.owner.getNameAsString();

        if (s.equals("this") || s.endsWith(".this")) {
            return true;
        }
        if (s.equals(calleeOwner) || s.endsWith("." + calleeOwner)) {
            return true;
        }
        if (s.equals(callerOwner) || s.endsWith("." + callerOwner)) {
            return true;
        }

        // For variable-scoped calls such as helper.foo(), JavaParser without symbol
        // solving
        // cannot know the receiver type. Keep the edge because the call is explicit in
        // the
        // same file; downstream logs should treat it as syntactic evidence.
        return true;
    }

    private static Resolution bfsNearestAccessibleEntry(
            MutationConfig config,
            CallableNode target,
            Map<CallableNode, LinkedHashSet<CallableNode>> reverseCallers) {

        Deque<List<CallableNode>> q = new ArrayDeque<>();
        Set<CallableNode> visited = new HashSet<>();
        q.add(List.of(target));
        visited.add(target);

        while (!q.isEmpty()) {
            List<CallableNode> suffix = q.poll();
            CallableNode cur = suffix.get(0);
            for (CallableNode caller : new ArrayList<>(reverseCallers.getOrDefault(cur, new LinkedHashSet<>()))) {
                if (!visited.add(caller)) {
                    continue;
                }
                List<CallableNode> chain = new ArrayList<>();
                chain.add(caller);
                chain.addAll(suffix);

                if (isDirectlyTestableFromSamePackage(caller)) {
                    return Resolution.ascendedAccessible(config, target, caller, chain);
                }
                q.add(chain);
            }
        }
        return null;
    }

    /**
     * Directly testable means generated test source can legally call this callable.
     * We assume generated tests are placed in the same package as the target class.
     * Therefore package-private and protected are allowed. Private method or any
     * private enclosing class is not allowed.
     */
    private static boolean isDirectlyTestableFromSamePackage(CallableNode c) {
        if (hasModifier(c.callable, Modifier.Keyword.PRIVATE)) {
            return false;
        }
        Node n = c.owner;
        while (n instanceof TypeDeclaration<?>) {
            TypeDeclaration<?> td = (TypeDeclaration<?>) n;
            if (hasModifier(td, Modifier.Keyword.PRIVATE)) {
                return false;
            }
            Optional<Node> parent = td.getParentNode();
            if (parent.isEmpty() || !(parent.get() instanceof TypeDeclaration<?>)) {
                break;
            }
            n = parent.get();
        }
        return true;
    }

    private static boolean isExternallyCallablePublic(CallableNode c) {
        if (!hasModifier(c.callable, Modifier.Keyword.PUBLIC)) {
            return false;
        }
        Node n = c.owner;
        while (n instanceof TypeDeclaration<?>) {
            TypeDeclaration<?> td = (TypeDeclaration<?>) n;
            if (!hasModifier(td, Modifier.Keyword.PUBLIC)) {
                return false;
            }
            Optional<Node> parent = td.getParentNode();
            if (parent.isEmpty() || !(parent.get() instanceof TypeDeclaration<?>)) {
                break;
            }
            n = parent.get();
        }
        return true;
    }

    private static String directEntryKind(CallableNode c) {
        if (isExternallyCallablePublic(c)) {
            return "PUBLIC_DIRECT_ENTRY";
        }
        if (hasModifier(c.callable, Modifier.Keyword.PROTECTED)) {
            return "PROTECTED_SAME_PACKAGE_DIRECT_ENTRY";
        }
        return "PACKAGE_PRIVATE_DIRECT_ENTRY";
    }

    private static String ascendedEntryKind(CallableNode entry) {
        if (isExternallyCallablePublic(entry)) {
            return "ASCENDED_PUBLIC_CALLER";
        }
        if (hasModifier(entry.callable, Modifier.Keyword.PROTECTED)) {
            return "ASCENDED_PROTECTED_SAME_PACKAGE_CALLER";
        }
        return "ASCENDED_PACKAGE_PRIVATE_CALLER";
    }

    private static String recommendedTargetForKind(String kind, boolean reflection) {
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

    private static String ownerAccessPath(TypeDeclaration<?> owner) {
        List<String> parts = new ArrayList<>();
        Node n = owner;
        while (n instanceof TypeDeclaration<?>) {
            TypeDeclaration<?> td = (TypeDeclaration<?>) n;
            parts.add(accessOf(td) + " " + td.getNameAsString());
            Optional<Node> p = td.getParentNode();
            if (p.isEmpty() || !(p.get() instanceof TypeDeclaration<?>)) {
                break;
            }
            n = p.get();
        }
        Collections.reverse(parts);
        return String.join(" -> ", parts);
    }

    private static boolean hasModifier(NodeWithModifiers<?> n, Modifier.Keyword kw) {
        return n.getModifiers().stream().anyMatch(m -> m.getKeyword() == kw);
    }

    private static String accessOf(NodeWithModifiers<?> n) {
        if (hasModifier(n, Modifier.Keyword.PUBLIC)) {
            return "public";
        }
        if (hasModifier(n, Modifier.Keyword.PROTECTED)) {
            return "protected";
        }
        if (hasModifier(n, Modifier.Keyword.PRIVATE)) {
            return "private";
        }
        return "package-private";
    }

    private static String displayModifiers(CallableDeclaration<?> cd) {
        StringBuilder sb = new StringBuilder(accessOf(cd));
        if (cd instanceof MethodDeclaration && hasModifier(cd, Modifier.Keyword.STATIC)) {
            sb.append(" static");
        }
        return sb.toString();
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

    private static boolean sameClass(String actualSourcePath, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String e = expected.replace('$', '.');
        if (actualSourcePath.equals(e)) {
            return true;
        }
        if (e.endsWith("." + actualSourcePath)) {
            return true;
        }
        String actualSimple = actualSourcePath.substring(actualSourcePath.lastIndexOf('.') + 1);
        String expectedSimple = e.substring(e.lastIndexOf('.') + 1);
        return actualSimple.equals(expectedSimple);
    }

    private static boolean signatureEquals(String a, String b) {
        return normalizeSignature(a).equals(normalizeSignature(b));
    }

    private static String normalizeSignature(String s) {
        Sig sig = Sig.parse(s);
        return sig.toNormalizedString();
    }

    private static String refKey(boolean constructor, String name, int arity) {
        return (constructor ? "<init>" : "<method>") + "#" + name + "#" + arity;
    }

    private static final class CallableNode {
        final String packageName;
        final TypeDeclaration<?> owner;
        final CallableDeclaration<?> callable;
        final String classPath;
        final String signature;
        final String name;
        final int arity;
        final boolean isConstructor;

        CallableNode(String packageName, TypeDeclaration<?> owner, CallableDeclaration<?> callable) {
            this.packageName = packageName == null ? "" : packageName;
            this.owner = owner;
            this.callable = callable;
            this.classPath = classPathOf(owner);
            this.isConstructor = callable instanceof ConstructorDeclaration;
            this.name = callable.getNameAsString();
            this.arity = callable.getParameters().size();
            this.signature = toSootLikeSignature(owner, callable);
        }

        String display() {
            return displayModifiers(callable) + " " + classPath + "#"
                    + PromptSignatureFormatter.method(signature);
        }
    }

    private static final class CallRef {
        final boolean constructor;
        final String name;
        final int arity;
        final String scope;

        CallRef(boolean constructor, String name, int arity, String scope) {
            this.constructor = constructor;
            this.name = name;
            this.arity = arity;
            this.scope = scope == null ? "" : scope;
        }
    }

    private static String toSootLikeSignature(TypeDeclaration<?> owner, CallableDeclaration<?> cd) {
        String params = cd.getParameters().stream()
                .map(p -> canonicalSourceType(p.getType().asString(), p.isVarArgs()))
                .collect(Collectors.joining(","));
        if (cd instanceof ConstructorDeclaration) {
            return owner.getNameAsString() + "(" + params + ")";
        }
        MethodDeclaration md = (MethodDeclaration) cd;
        return canonicalSourceType(md.getType().asString(), false) + "_" + md.getNameAsString() + "(" + params + ")";
    }

    /**
     * Internal signature used by Soot/JavaParser matching.
     * Keep the returnType_method(params) form, but strip package names from types.
     */
    /**
     * Internal signature used by Soot/JavaParser/MethodContent matching.
     * Keep the returnType_method(params) form and DO NOT convert it into the
     * prompt-facing display form. Also keep package-qualified source types when
     * they are present, so java.lang.String / java.util.Collection can be matched
     * by source extraction. Loose comparison is handled separately by
     * canonicalMatchType().
     */
    private static String canonicalSourceType(String raw, boolean varArgs) {
        if (raw == null) {
            return "";
        }
        String t = raw.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        t = stripGenerics(t);
        t = t.replaceAll("\\s+", "").replace("$", ".");
        if (varArgs && !t.endsWith("[]")) {
            t = t.replace("...", "") + "[]";
        }
        return t;
    }

    /**
     * Loose matching form. Strip package names so java.util.Collection and
     * Collection still match.
     */
    private static String canonicalMatchType(String raw, boolean varArgs) {
        String t = canonicalSourceType(raw, varArgs);
        int dims = 0;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < dims; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private static String stripGenerics(String type) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
                continue;
            }
            if (c == '>') {
                depth--;
                continue;
            }
            if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
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
            if (lp < 0 || rp < lp) {
                throw new IllegalArgumentException("bad signature: " + sig);
            }
            int us = sig.indexOf('_');
            String inside = sig.substring(lp + 1, rp).trim();
            List<String> params = inside.isEmpty() ? List.of()
                    : Arrays.stream(inside.split(","))
                            .map(s -> canonicalMatchType(s.trim(), false))
                            .collect(Collectors.toList());
            if (us > 0 && us < lp) {
                return new Sig(false, canonicalMatchType(sig.substring(0, us), false), sig.substring(us + 1, lp).trim(),
                        params);
            }
            return new Sig(true, null, sig.substring(0, lp).trim(), params);
        }

        String toNormalizedString() {
            return (constructor ? "" : returnType + "_") + name + "(" + String.join(",", params) + ")";
        }
    }


    private static void fillReceiverInfo(Resolution r, CallableNode entry) {
        if (r == null || entry == null) {
            return;
        }
        boolean isStatic = entry.callable instanceof MethodDeclaration && hasModifier(entry.callable, Modifier.Keyword.STATIC);
        String ownerKind = ownerKindOf(entry.owner);
        String strategy = inferReceiverStrategy(entry, ownerKind, isStatic);

        r.testEntryOwnerKind = ownerKind;
        r.testEntryOwnerAbstract = "ABSTRACT_CLASS".equals(ownerKind);
        r.testEntryOwnerInterface = "INTERFACE".equals(ownerKind);
        r.testEntryOwnerInstantiable = inferOwnerInstantiable(strategy);
        r.testReceiverStrategy = strategy;
        r.entryInvocationKind = entryInvocationKind(entry, strategy);
        r.testReceiverConstruction = inferReceiverConstruction(entry, ownerKind, strategy);
        r.testReceiverNotes = inferReceiverNotes(ownerKind, strategy);
    }

    private static String ownerKindOf(TypeDeclaration<?> owner) {
        if (owner instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) owner;
            if (c.isInterface()) {
                return "INTERFACE";
            }
            if (c.isAbstract()) {
                return "ABSTRACT_CLASS";
            }
        }
        return "CONCRETE_CLASS";
    }

    private static String inferReceiverStrategy(CallableNode entry, String ownerKind, boolean isStatic) {
        if (isStatic) {
            return "STATIC_NO_RECEIVER";
        }
        if ("INTERFACE".equals(ownerKind)) {
            return "INTERFACE_IMPLEMENTATION_REQUIRED";
        }
        if ("ABSTRACT_CLASS".equals(ownerKind)) {
            return hasAccessibleConstructor(entry.owner) ? "ANONYMOUS_SUBCLASS" : "CONCRETE_SUBCLASS_REQUIRED";
        }
        return hasAccessibleConstructor(entry.owner) ? "DIRECT_CONSTRUCTOR" : "FACTORY_OR_REFLECTION_REQUIRED";
    }

    private static boolean inferOwnerInstantiable(String strategy) {
        return "STATIC_NO_RECEIVER".equals(strategy)
                || "DIRECT_CONSTRUCTOR".equals(strategy)
                || "ANONYMOUS_SUBCLASS".equals(strategy);
    }

    private static String entryInvocationKind(CallableNode entry, String strategy) {
        if (entry == null) {
            return "REFLECTION_INVOCATION";
        }
        if (entry.isConstructor) {
            return "CONSTRUCTOR_INVOCATION";
        }
        if ("STATIC_NO_RECEIVER".equals(strategy)) {
            return "STATIC_METHOD_INVOCATION";
        }
        return "INSTANCE_METHOD_INVOCATION";
    }

    private static String inferReceiverConstruction(CallableNode entry, String ownerKind, String strategy) {
        String type = entry.owner.getNameAsString();
        String args = bestConstructorArgs(entry.owner);
        if (entry != null && entry.isConstructor) {
            return "The callable test entry is a constructor; invoke it directly as new " + type + "(" + args + "). No separate receiver is needed before invocation.";
        }
        if ("STATIC_NO_RECEIVER".equals(strategy)) {
            return "No receiver is required; call the static entry method on " + type + ".";
        }
        if ("ANONYMOUS_SUBCLASS".equals(strategy)) {
            return "Create a same-package anonymous subclass, e.g. new " + type + "(" + args + ") { }, then call the entry method on that receiver.";
        }
        if ("DIRECT_CONSTRUCTOR".equals(strategy)) {
            return "Create the receiver directly using an accessible constructor, e.g. new " + type + "(" + args + ").";
        }
        if ("INTERFACE_IMPLEMENTATION_REQUIRED".equals(strategy)) {
            return "Create a minimal test implementation of interface " + type + " or use an existing project implementation, then call the entry method if it is a default method.";
        }
        if ("CONCRETE_SUBCLASS_REQUIRED".equals(strategy)) {
            return "The declaring class is abstract and no accessible constructor was found; use an existing concrete subclass or reflection fallback.";
        }
        return "Use an existing factory/concrete instance if available; otherwise use reflection fallback.";
    }

    private static String inferReceiverNotes(String ownerKind, String strategy) {
        if ("ANONYMOUS_SUBCLASS".equals(strategy)) {
            return "The declaring class is abstract, so it cannot be instantiated directly; use an anonymous subclass with an accessible constructor.";
        }
        if ("INTERFACE_IMPLEMENTATION_REQUIRED".equals(strategy)) {
            return "The declaring type is an interface; generated tests need a concrete implementation unless the method is static/default and can be invoked through an implementation.";
        }
        if ("DIRECT_CONSTRUCTOR".equals(strategy)) {
            return "The declaring class is concrete and has a non-private constructor usable from the generated same-package test.";
        }
        if ("STATIC_NO_RECEIVER".equals(strategy)) {
            return "The callable test entry is static; no receiver construction is needed.";
        }
        return "Receiver construction may require an existing project subtype, factory, or reflection.";
    }

    private static boolean hasAccessibleConstructor(TypeDeclaration<?> owner) {
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) owner;
        if (c.isInterface()) {
            return false;
        }
        List<ConstructorDeclaration> ctors = c.getConstructors();
        if (ctors.isEmpty()) {
            return true;
        }
        for (ConstructorDeclaration ctor : ctors) {
            if (!hasModifier(ctor, Modifier.Keyword.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    private static String bestConstructorArgs(TypeDeclaration<?> owner) {
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return "";
        }
        ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) owner;
        List<ConstructorDeclaration> candidates = c.getConstructors().stream()
                .filter(ctor -> !hasModifier(ctor, Modifier.Keyword.PRIVATE))
                .sorted(Comparator.comparingInt(ctor -> ctor.getParameters().size()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return "";
        }
        List<String> args = new ArrayList<>();
        for (Parameter p : candidates.get(0).getParameters()) {
            args.add(exampleValueForType(p.getType().asString()));
        }
        return String.join(", ", args);
    }

    private static String exampleValueForType(String rawType) {
        String t = canonicalMatchType(rawType, false);
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
                if (t.endsWith("[]")) {
                    return "new " + t.substring(0, t.length() - 2) + "[0]";
                }
                return "null";
        }
    }

    private static String sourceClassPathToSootBinaryName(String sourceClassPath) {
        if (sourceClassPath == null) {
            return "";
        }
        // The source-level nested path is Outer.Inner; the corresponding class
        // file and Soot class use Outer$Inner.  Keep top-level classes unchanged.
        int firstDot = sourceClassPath.indexOf('.');
        if (firstDot < 0) {
            return sourceClassPath;
        }
        return sourceClassPath.substring(0, firstDot) + "$" + sourceClassPath.substring(firstDot + 1).replace('.', '$');
    }

    public static final class Resolution {
        public String mutationClassName;
        public String mutationSootClassName;
        public String mutationMethodName;
        public String testEntryClassName;
        public String testEntrySootClassName;
        public String testEntryMethodName;
        public String testEntryKind;
        public boolean useReflectionFallback;
        public List<String> callChain = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        public String testGenerationPackage = "";
        public String recommendedTestTarget = "call_test_entry_method";
        public String entryInvocationKind = "INSTANCE_METHOD_INVOCATION";

        public String testEntryOwnerKind = "";
        public boolean testEntryOwnerAbstract = false;
        public boolean testEntryOwnerInterface = false;
        public boolean testEntryOwnerInstantiable = false;
        public String testReceiverStrategy = "";
        public String testReceiverConstruction = "";
        public String testReceiverNotes = "";
        public String testReceiverRuntimeClassName = "";
        public String testReceiverRuntimeSootClassName = "";
        public String testReceiverDeclaringClassName = "";
        public String testReceiverDispatchTarget = "";
        public boolean testReceiverDispatchesToMutationMethod = false;
        public boolean testReceiverSubclassOverridesMutationMethod = false;
        public String testReceiverSetupTemplate = "";
        public String testReceiverInvocationTemplate = "";
        public String testReceiverResolutionReason = "";
        public String testReceiverFactoryMethod = "";
        public String testReceiverBuilderClassName = "";
        public String testReceiverBuilderTerminalMethod = "";
        public String testReceiverBuilderSetupChain = "";
        public String testReceiverAntiPatterns = "";

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

        static Resolution directAccessible(MutationConfig config, CallableNode target) {
            Resolution r = base(config, target, target);
            r.testEntryKind = directEntryKind(target);
            r.callChain = List.of(target.display());
            r.recommendedTestTarget = recommendedTargetForKind(r.testEntryKind, false);
            if ("PUBLIC_DIRECT_ENTRY".equals(r.testEntryKind)) {
                r.notes.add("mutation method is public and can be called directly; no caller ascent needed");
            } else if ("PROTECTED_SAME_PACKAGE_DIRECT_ENTRY".equals(r.testEntryKind)) {
                r.notes.add("target is protected and can be directly tested from a same-package test class");
            } else {
                r.notes.add(
                        "target is package-private or inside a package-private class and can be directly tested from a same-package test class");
            }
            return r;
        }

        static Resolution ascendedAccessible(MutationConfig config, CallableNode target, CallableNode entry,
                List<CallableNode> chain) {
            Resolution r = base(config, target, entry);
            r.testEntryKind = ascendedEntryKind(entry);
            r.callChain = chain.stream().map(CallableNode::display).collect(Collectors.toList());
            r.recommendedTestTarget = recommendedTargetForKind(r.testEntryKind, false);
            r.notes.add(
                    "resolved nearest directly testable caller by reverse call-chain search in the same source file");
            return r;
        }

        static Resolution reflectionFallback(MutationConfig config, String note) {
            Resolution r = new Resolution();
            r.mutationClassName = config.classNameF;
            r.mutationSootClassName = config.classNameF;
            r.mutationMethodName = config.methodName;
            r.testEntryClassName = config.classNameF;
            r.testEntrySootClassName = config.classNameF;
            r.testEntryMethodName = config.methodName;
            r.testEntryKind = "REFLECTION_FALLBACK";
            r.useReflectionFallback = true;
            r.callChain = List.of(config.classNameF + "#" + config.methodName);
            r.notes.add(note);
            r.testGenerationPackage = config.packageName == null ? "" : config.packageName;
            r.recommendedTestTarget = "reflect_mutation_method";
            r.entryInvocationKind = "REFLECTION_INVOCATION";
            r.testEntryOwnerKind = "UNKNOWN";
            r.testEntryOwnerAbstract = false;
            r.testEntryOwnerInterface = false;
            r.testEntryOwnerInstantiable = false;
            r.testReceiverStrategy = "REFLECTION_FALLBACK";
            r.testReceiverConstruction = "Use reflection to access the mutation method if no callable entry B is available.";
            r.testReceiverNotes = note;
            return r;
        }

        private static Resolution base(MutationConfig config, CallableNode target, CallableNode entry) {
            Resolution r = new Resolution();
            r.mutationClassName = target.classPath;
            r.mutationSootClassName = sourceClassPathToSootBinaryName(target.classPath);
            r.mutationMethodName = target.signature;
            r.testEntryClassName = entry.classPath;
            r.testEntrySootClassName = sourceClassPathToSootBinaryName(entry.classPath);
            r.testEntryMethodName = entry.signature;
            r.testEntryKind = "UNKNOWN";
            r.useReflectionFallback = false;
            r.testGenerationPackage = entry.packageName;
            fillReceiverInfo(r, entry);
            return r;
        }

        public void applyTo(MutationConfig config) {
            config.mutationClassName = mutationClassName;
            config.mutationSootClassName = mutationSootClassName;
            config.mutationMethodName = mutationMethodName;
            config.testEntryClassName = testEntryClassName;
            config.testEntrySootClassName = testEntrySootClassName;
            config.testEntryMethodName = testEntryMethodName;
            config.testEntryKind = testEntryKind;
            config.useReflectionFallback = useReflectionFallback;
            config.testCallChain = String.join(" -> ", callChain);
            config.testEntryNotes = String.join(" | ", notes);
            config.testGenerationPackage = testGenerationPackage;
            config.testEntryOwnerKind = testEntryOwnerKind;
            config.testEntryOwnerAbstract = testEntryOwnerAbstract;
            config.testEntryOwnerInterface = testEntryOwnerInterface;
            config.testEntryOwnerInstantiable = testEntryOwnerInstantiable;
            config.testReceiverStrategy = testReceiverStrategy;
            config.testReceiverConstruction = testReceiverConstruction;
            config.testReceiverNotes = testReceiverNotes;
            config.entryInvocationKind = entryInvocationKind;
            config.testReceiverRuntimeClassName = testReceiverRuntimeClassName;
            config.testReceiverRuntimeSootClassName = testReceiverRuntimeSootClassName;
            config.testReceiverDeclaringClassName = testReceiverDeclaringClassName;
            config.testReceiverDispatchTarget = testReceiverDispatchTarget;
            config.testReceiverDispatchesToMutationMethod = testReceiverDispatchesToMutationMethod;
            config.testReceiverSubclassOverridesMutationMethod = testReceiverSubclassOverridesMutationMethod;
            config.testReceiverSetupTemplate = testReceiverSetupTemplate;
            config.testReceiverInvocationTemplate = testReceiverInvocationTemplate;
            config.testReceiverResolutionReason = testReceiverResolutionReason;
            config.testReceiverFactoryMethod = testReceiverFactoryMethod;
            config.testReceiverBuilderClassName = testReceiverBuilderClassName;
            config.testReceiverBuilderTerminalMethod = testReceiverBuilderTerminalMethod;
            config.testReceiverBuilderSetupChain = testReceiverBuilderSetupChain;
            config.testReceiverAntiPatterns = testReceiverAntiPatterns;
            config.availablePublicMethods = availablePublicMethods;
            config.availableSetupMethods = availableSetupMethods;
            config.stateSetupPlan = stateSetupPlan;
            config.observablePlanKind = observablePlanKind;
            config.observableSetup = observableSetup;
            config.observableCall = observableCall;
            config.observableExpectedOriginal = observableExpectedOriginal;
            config.observableReason = observableReason;
            config.observableAntiPatterns = observableAntiPatterns;
            config.branchReachabilityKind = branchReachabilityKind;
            config.branchReachabilityCondition = branchReachabilityCondition;
            config.branchReachabilitySetup = branchReachabilitySetup;
            config.branchReachabilityReason = branchReachabilityReason;
            config.abstractMethodsToImplement = abstractMethodsToImplement;
            config.allowedOverrides = allowedOverrides;
            config.forbiddenOverrides = forbiddenOverrides;
            config.testStubClassTemplate = testStubClassTemplate;
            config.testStubConstructorTemplate = testStubConstructorTemplate;
            config.skipTestGeneration = skipTestGeneration;
            config.skipReason = skipReason;
        }
    }
}
