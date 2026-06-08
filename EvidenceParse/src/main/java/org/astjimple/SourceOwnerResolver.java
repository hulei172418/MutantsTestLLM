package org.astjimple;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves the real source owner for mutants whose spreadsheet owner is an
 * abstract/interface outer type while the changed executable body is in a nested
 * concrete class.
 *
 * Examples handled here:
 *   DateTimeFieldType#getField       -> DateTimeFieldType$StandardDateTimeFieldType#getField
 *   DurationFieldType#getField       -> DurationFieldType$StandardDurationFieldType#getField
 *   InputAccessor#nextByte           -> InputAccessor$Std#nextByte
 *   ImpreciseDateTimeField#add       -> ImpreciseDateTimeField$LinkedDurationField#add
 *   Angle#toDeg                      -> Angle$Deg#toDeg / Angle$Rad#toDeg / Angle$Turn#toDeg
 */
public final class SourceOwnerResolver {
    private SourceOwnerResolver() {}

    public static final class Resolution {
        public final String ownerBinaryName;
        public final String callableSignature;
        public final boolean changed;
        public final String reason;

        Resolution(String ownerBinaryName, String callableSignature, boolean changed, String reason) {
            this.ownerBinaryName = ownerBinaryName;
            this.callableSignature = callableSignature;
            this.changed = changed;
            this.reason = reason;
        }
    }

    public static Optional<Resolution> resolveChangedCallableOwner(
            String javaFile,
            String currentOwner,
            String expectedSignature,
            List<?> changeRanges) {
        try {
            List<Integer> changedLines = changedLines(changeRanges);
            if (changedLines.isEmpty()) {
                return Optional.empty();
            }

            ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
            JavaParser parser = new JavaParser(cfg);
            var result = parser.parse(Paths.get(javaFile));
            if (!result.getResult().isPresent()) {
                return Optional.empty();
            }
            CompilationUnit cu = result.getResult().get();
            Sig expected = parseSig(expectedSignature);

            List<CallableDeclaration<?>> callables = new ArrayList<>();
            callables.addAll(cu.findAll(MethodDeclaration.class));
            callables.addAll(cu.findAll(ConstructorDeclaration.class));

            // First: best case, the changed line is inside a callable matching the expected signature.
            List<CallableDeclaration<?>> hits = callables.stream()
                    .filter(cd -> containsAnyChangedLine(cd, changedLines))
                    .filter(cd -> callableNameMatches(cd, expected))
                    .filter(cd -> paramsCompatible(cd, expected, true))
                    .collect(Collectors.toList());

            // Second: metadata can point to an abstract overload while the actual changed line is in
            // a concrete delegate overload, e.g. long_add(long,long) vs add(long,int).  If the
            // changed line is unambiguously inside a same-name callable, trust the source line.
            if (hits.isEmpty()) {
                hits = callables.stream()
                        .filter(cd -> containsAnyChangedLine(cd, changedLines))
                        .filter(cd -> callableNameMatches(cd, expected))
                        .collect(Collectors.toList());
            }

            if (hits.isEmpty()) {
                return Optional.empty();
            }

            CallableDeclaration<?> picked = pickDeepest(hits);
            TypeDeclaration<?> owner = findAncestorType(picked).orElse(null);
            if (owner == null) {
                return Optional.empty();
            }

            String resolvedOwner = ownerBinaryName(owner);
            String resolvedSig = toInternalSignature(picked);
            String currentSimple = normalizeOwner(currentOwner);
            boolean changed = !Objects.equals(currentSimple, resolvedOwner)
                    || !Objects.equals(normalizeSig(expectedSignature), normalizeSig(resolvedSig));
            if (!changed) {
                return Optional.empty();
            }
            return Optional.of(new Resolution(
                    resolvedOwner,
                    resolvedSig,
                    true,
                    "changed source line is inside " + resolvedOwner + "#" + resolvedSig));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static CallableDeclaration<?> pickDeepest(List<CallableDeclaration<?>> hits) {
        return hits.stream()
                .sorted((a, b) -> Integer.compare(ownerDepth(b), ownerDepth(a)))
                .findFirst()
                .orElse(hits.get(0));
    }

    private static int ownerDepth(CallableDeclaration<?> cd) {
        int d = 0;
        Optional<TypeDeclaration<?>> owner = findAncestorType(cd);
        while (owner.isPresent()) {
            d++;
            owner = findAncestorType(owner.get());
        }
        return d;
    }


    /**
     * JavaParser 3.26.x returns Optional<TypeDeclaration> from
     * findAncestor(TypeDeclaration.class), which causes generic type mismatch
     * and varargs warnings when assigned to Optional<TypeDeclaration<?>>.
     * Walk the parent chain manually to keep the return type precise.
     */
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

    private static boolean containsAnyChangedLine(CallableDeclaration<?> cd, List<Integer> lines) {
        if (cd.getRange().isEmpty()) {
            return false;
        }
        Range r = cd.getRange().get();
        for (Integer line : lines) {
            if (line != null && line >= r.begin.line && line <= r.end.line) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> changedLines(List<?> changeRanges) {
        if (changeRanges == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        Pattern p = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
        for (Object o : changeRanges) {
            if (o == null) continue;
            Matcher m = p.matcher(o.toString());
            while (m.find()) {
                try {
                    int a = Integer.parseInt(m.group(1));
                    int b = Integer.parseInt(m.group(2));
                    int lo = Math.min(a, b);
                    int hi = Math.max(a, b);
                    for (int i = lo; i <= hi; i++) {
                        out.add(i);
                    }
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static boolean callableNameMatches(CallableDeclaration<?> cd, Sig s) {
        if (s.isConstructor) {
            return cd instanceof ConstructorDeclaration
                    && cd.getNameAsString().equals(simpleName(s.name));
        }
        return cd instanceof MethodDeclaration && cd.getNameAsString().equals(s.name);
    }

    private static boolean paramsCompatible(CallableDeclaration<?> cd, Sig s, boolean allowVarargs) {
        List<String> actual = cd.getParameters().stream()
                .map(p -> canonical(p.getType().asString(), p.isVarArgs()))
                .collect(Collectors.toList());
        if (actual.size() != s.params.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            String a = normalizeType(actual.get(i));
            String e = normalizeType(s.params.get(i));
            if (a.equals(e)) continue;
            if (allowVarargs && varargsCompatible(a, e)) continue;
            if (genericCompatible(a, e)) continue;
            return false;
        }
        return true;
    }

    private static String toInternalSignature(CallableDeclaration<?> cd) {
        List<String> params = cd.getParameters().stream()
                .map(p -> canonical(p.getType().asString(), p.isVarArgs()))
                .collect(Collectors.toList());
        String joined = String.join(",", params);
        if (cd instanceof ConstructorDeclaration) {
            return cd.getNameAsString() + "(" + joined + ")";
        }
        MethodDeclaration md = (MethodDeclaration) cd;
        return canonical(md.getType().asString(), false) + "_" + md.getNameAsString() + "(" + joined + ")";
    }

    private static String ownerBinaryName(TypeDeclaration<?> owner) {
        List<String> names = new ArrayList<>();
        TypeDeclaration<?> cur = owner;
        while (cur != null) {
            names.add(cur.getNameAsString());
            Optional<TypeDeclaration<?>> parent = findAncestorType(cur);
            cur = parent.orElse(null);
        }
        Collections.reverse(names);
        return String.join("$", names);
    }

    private static String normalizeOwner(String owner) {
        if (owner == null) return "";
        String s = owner.replace('.', '$');
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        // Drop package-like leading components but keep nested owner.  This is conservative;
        // existing pipeline mostly stores simple names in className.
        String[] parts = s.split("\\$");
        if (parts.length > 1) {
            return String.join("$", parts);
        }
        int dot = owner.lastIndexOf('.');
        return dot >= 0 ? owner.substring(dot + 1) : owner;
    }

    private static String normalizeSig(String sig) {
        return sig == null ? "" : sig.replaceAll("\\s+", "").replace("...", "[]").replace('$', '.');
    }

    private static Sig parseSig(String sig) {
        int lp = sig.indexOf('(');
        int rp = sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) {
            return new Sig(false, "", sig, List.of());
        }
        String head = sig.substring(0, lp).trim();
        String inside = sig.substring(lp + 1, rp).trim();
        List<String> params = inside.isEmpty() ? List.of()
                : splitTopLevel(inside).stream().map(x -> canonical(x, false)).collect(Collectors.toList());
        int us = head.indexOf('_');
        if (us > 0) {
            return new Sig(false, canonical(head.substring(0, us), false), head.substring(us + 1).trim(), params);
        }
        String[] parts = head.split("\\s+");
        if (parts.length >= 2) {
            return new Sig(false, canonical(parts[parts.length - 2], false), parts[parts.length - 1], params);
        }
        return new Sig(true, null, head, params);
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static String canonical(String raw, boolean varArgs) {
        String t = raw == null ? "" : raw.trim();
        t = t.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        t = stripGenerics(t);
        t = t.replaceAll("\\s+", "");
        t = t.replace("...", "[]");
        if (varArgs && !t.endsWith("[]")) {
            t += "[]";
        }
        return t;
    }

    private static String normalizeType(String raw) {
        String t = canonical(raw, false).replace('$', '.');
        int dims = 0;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }

    private static boolean varargsCompatible(String a, String e) {
        if (a.endsWith("[]") && !e.endsWith("[]")) return a.substring(0, a.length() - 2).equals(e);
        if (e.endsWith("[]") && !a.endsWith("[]")) return e.substring(0, e.length() - 2).equals(a);
        return false;
    }

    private static boolean genericCompatible(String a, String e) {
        return isTypeVariable(a) || isTypeVariable(e) || genericArrayCompatible(a, e);
    }

    private static boolean genericArrayCompatible(String a, String e) {
        int ad = dims(a), ed = dims(e);
        if (ad == 0 || ad != ed) return false;
        return isTypeVariable(base(a)) || isTypeVariable(base(e));
    }

    private static int dims(String t) { int n = 0; while (t.endsWith("[]")) { n++; t = t.substring(0, t.length()-2); } return n; }
    private static String base(String t) { while (t.endsWith("[]")) t = t.substring(0, t.length()-2); return t; }
    private static boolean isTypeVariable(String t) { return t != null && t.matches("[A-Z][A-Z0-9_]{0,2}"); }
    private static String simpleName(String s) { String t = normalizeType(s); return t; }

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

    private static final class Sig {
        final boolean isConstructor;
        final String ret;
        final String name;
        final List<String> params;
        Sig(boolean isConstructor, String ret, String name, List<String> params) {
            this.isConstructor = isConstructor;
            this.ret = ret;
            this.name = name;
            this.params = params;
        }
    }
}
