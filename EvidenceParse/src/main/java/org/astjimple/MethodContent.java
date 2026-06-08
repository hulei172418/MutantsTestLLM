package org.astjimple;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.Range;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class MethodContent {
        private static final String mID = "m4"; // Mutant ID
        // Source code of mutant m
        private static final String SRC_FILE_M = "src/main/java/demo/" + mID + "/ConstantPoolEntry.java";
        // Fully-qualified class name of m (usually same)
        private static final String CLASS_NAME_M = "ConstantPoolEntry";
        // Only analyze methods whose names contain this substring
        private static final String METHOD_NAME_SUBSTR = "boolean_PoolEntry(int,int)";

        public static void main(String[] args) throws Exception {
                String className = CLASS_NAME_M; // 如: Demo 或 Outer.Inner.MoreInner
                String methodName = METHOD_NAME_SUBSTR;
                boolean includeSignature = Boolean.parseBoolean("true");
                String oneLine = extractMethodAsOneLine(SRC_FILE_M, className, methodName, includeSignature);
                System.out.println(oneLine);
        }

        public static void test() throws Exception {
                String className = CLASS_NAME_M; // e.g., Demo or Outer.Inner.MoreInner
                String methodName = "PoolEntry";
                boolean includeSignature = Boolean.parseBoolean("true");
                String paramsStr = "int, int"; // Comma-separated parameter type list; empty means ignore overloads
                List<String> wantedParams = Arrays.stream(paramsStr.split(",")).map(String::trim)
                                .collect(Collectors.toList());
                String oneLine1 = extractMethodAsOneLine1(SRC_FILE_M, className, methodName,
                                includeSignature,
                                wantedParams);
                System.out.println(oneLine1);
        }

        public static String extractMethodAsOneLine(
                        String javaFile,
                        String classPathOrNull, // Can be null; if set, only search within this class; supports
                                                // "Outer.Inner"
                        String sootSignature, // Like "boolean_PoolEntry(int,int)"
                        boolean includeSignature // true=include signature; false=method body only
        ) throws Exception {
                Path file = Paths.get(javaFile);

                ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
                JavaParser parser = new JavaParser(cfg);
                var result = parser.parse(file);
                if (!result.getResult().isPresent()) {
                        throw new Exception("Parse failed: " + result.getProblems());
                }
                CompilationUnit cu = result.getResult().get();

                cu.getAllContainedComments().forEach(Comment::remove);

                Optional<CallableDeclaration<?>> cdOpt = findCallableBySignature(cu, sootSignature, classPathOrNull);

                if (cdOpt.isEmpty()) {
                        throw new IllegalArgumentException("No callable matched signature: " + sootSignature
                                        + (classPathOrNull == null ? "" : (" within class " + classPathOrNull)));
                }

                CallableDeclaration<?> cd = cdOpt.get();

                if (includeSignature) {
                        return cd.toString();
                }

                if (cd instanceof MethodDeclaration md) {
                        return md.getBody().map(Object::toString).orElse("");
                } else if (cd instanceof ConstructorDeclaration ctor) {
                        return ctor.getBody().toString();
                } else {
                        return "";
                }
        }

        /**
         * Find method by Soot-style signature in CompilationUnit; can restrict to
         * specific class including inner paths
         */
        public static Optional<CallableDeclaration<?>> findCallableBySignature(
                        CompilationUnit cu,
                        String sig,
                        String classPathOrNull) {

                Sig s = parseCallableSig(sig);

                List<CallableDeclaration<?>> candidates = new ArrayList<>();

                List<TypeDeclaration<?>> targetTypes = resolveCandidateTypesForSignature(cu, s, classPathOrNull);
                if (!targetTypes.isEmpty()) {
                        for (TypeDeclaration<?> type : targetTypes) {
                                for (BodyDeclaration<?> m : type.getMembers()) {
                                        if (m instanceof MethodDeclaration md) {
                                                candidates.add(md);
                                        } else if (m instanceof ConstructorDeclaration cd) {
                                                candidates.add(cd);
                                        }
                                }
                        }
                } else if (classPathOrNull == null || classPathOrNull.isBlank()) {
                        candidates.addAll(cu.findAll(MethodDeclaration.class));
                        candidates.addAll(cu.findAll(ConstructorDeclaration.class));
                } else {
                        return Optional.empty();
                }

                // First pass: exact source-level parameter matching.
                // This prevents a real overload foo(int) from being hidden by foo(int...) / foo(int[]).
                Optional<CallableDeclaration<?>> exact = candidates.stream()
                                .filter(cd -> callableMatches(cd, s, false))
                                .findFirst();
                if (exact.isPresent()) {
                        return exact;
                }

                // Second pass: source/mutant metadata may record a varargs parameter by
                // its element type, e.g. int_toUni(int), while JavaParser/Soot sees
                // the declaration as int... / int[].  Allow this only as fallback.
                return candidates.stream()
                                .filter(cd -> callableMatches(cd, s, true))
                                .findFirst();
        }


        /**
         * Resolve which source type should be searched for the requested signature.
         *
         * Important case: a Java file may contain multiple top-level package-private
         * classes.  Mutation metadata sometimes reports the public file class as the
         * context class, while the actual mutated callable is a sibling constructor,
         * e.g. ClassNameReader.java contains package-private ConstantPool and the
         * target signature is ConstantPool(java.io.DataInput).  In that case searching
         * only within ClassNameReader will fail; constructor signatures carry their
         * real owner name, so prefer that owner when it exists in the same CU.
         */
        private static List<TypeDeclaration<?>> resolveCandidateTypesForSignature(
                        CompilationUnit cu,
                        Sig s,
                        String classPathOrNull) {
                List<TypeDeclaration<?>> out = new ArrayList<>();

                if (s.isConstructor) {
                        String ctorOwner = simpleNameNoPackage(s.name);
                        Optional<TypeDeclaration<?>> ctorOwnerType = findTypeByPath(cu, ctorOwner);
                        if (ctorOwnerType.isPresent()) {
                                out.add(ctorOwnerType.get());
                                return out;
                        }
                }

                if (classPathOrNull != null && !classPathOrNull.isBlank()) {
                        Optional<TypeDeclaration<?>> typeOpt = findTypeByPath(cu, classPathOrNull);
                        typeOpt.ifPresent(out::add);
                        return out;
                }

                return out;
        }

        private static boolean callableMatches(CallableDeclaration<?> cd, Sig s, boolean allowVarargsElementCompatibility) {
                if (s.isConstructor != (cd instanceof ConstructorDeclaration)) {
                        return false;
                }

                String actualName = cd.getNameAsString();
                String expectedName = s.isConstructor ? simpleNameNoPackage(s.name) : s.name;
                if (!actualName.equals(expectedName)) {
                        return false;
                }

                List<String> actualParams = cd.getParameters().stream()
                                .map(p -> canonicalFromRawTypeString(
                                                p.getType().asString(),
                                                p.isVarArgs()))
                                .collect(Collectors.toList());

                if (!typesListEquals(actualParams, s.paramTypes, allowVarargsElementCompatibility)) {
                        return false;
                }

                if (cd instanceof MethodDeclaration md) {
                        String actualRet = canonicalFromRawTypeString(md.getType().asString(), false);
                        return typeEqualsLoose(actualRet, s.returnType);
                }

                // Constructors have no return type.
                return true;
        }
        /*
         * === Signature Parsing, Normalization & Utilities ===
         */

        /** Parse "ReturnType_MethodName(T1,T2,...)" -> Sig */
        private static Sig parseCallableSig(String sig) {
                int lp = sig.indexOf('(');
                int rp = sig.lastIndexOf(')');
                if (lp < 0 || rp < lp) {
                        throw new IllegalArgumentException("Bad signature: " + sig);
                }

                int us = sig.indexOf('_');

                // 方法签名：ReturnType_MethodName(T1,T2)
                if (us > 0 && us < lp) {
                        String returnType = canonicalFromSignaturePart(sig.substring(0, us));
                        String name = sig.substring(us + 1, lp).trim();
                        String inside = sig.substring(lp + 1, rp).trim();
                        List<String> params = inside.isEmpty()
                                        ? List.of()
                                        : Arrays.stream(inside.split(","))
                                                        .map(MethodContent::canonicalFromSignaturePart)
                                                        .collect(Collectors.toList());
                        return new Sig(false, returnType, name, params);
                }

                // Prompt-facing method signature accidentally used as internal input:
                // ReturnType methodName(T1,T2). Accept it conservatively so that
                // EntryLiftedRIP can still be generated.
                String head = sig.substring(0, lp).trim();
                String[] headParts = head.split("\\s+");
                if (headParts.length >= 2) {
                        String returnType = canonicalFromSignaturePart(headParts[headParts.length - 2]);
                        String name = headParts[headParts.length - 1].trim();
                        String inside = sig.substring(lp + 1, rp).trim();
                        List<String> params = inside.isEmpty()
                                        ? List.of()
                                        : Arrays.stream(inside.split(","))
                                                        .map(MethodContent::canonicalFromSignaturePart)
                                                        .collect(Collectors.toList());
                        return new Sig(false, returnType, name, params);
                }

                // 构造函数签名：ClassName(T1,T2)
                String name = sig.substring(0, lp).trim();
                String inside = sig.substring(lp + 1, rp).trim();
                List<String> params = inside.isEmpty()
                                ? List.of()
                                : Arrays.stream(inside.split(","))
                                                .map(MethodContent::canonicalFromSignaturePart)
                                                .collect(Collectors.toList());
                return new Sig(true, null, name, params);
        }

        /**
         * Normalize type in signature: remove annotations/generics/whitespace; varargs
         * -> []; keep array notation []
         */
        private static String canonicalFromSignaturePart(String raw) {
                String t = stripTypeAnnotations(raw);
                t = stripGenerics(t);
                t = t.replaceAll("\\s+", " ").trim();
                t = t.replace("...", "[]"); // 兜底
                return t;
        }

        /**
         * Normalize raw type string from AST (no symbol resolution, no classpath
         * dependency)
         */
        private static String canonicalFromRawTypeString(String raw, boolean isVarArgs) {
                String t = stripTypeAnnotations(raw);
                t = stripGenerics(t);
                t = t.replaceAll("\\s+", " ").trim();
                if (isVarArgs && !t.endsWith("[]")) {
                        t = t.replace("...", "");
                        t = t + "[]";
                }
                // Extract and restore array dimensions to ensure consistency
                int dims = 0;
                while (t.endsWith("[]")) {
                        dims++;
                        t = t.substring(0, t.length() - 2);
                }
                StringBuilder sb = new StringBuilder(t);
                for (int i = 0; i < dims; i++)
                        sb.append("[]");
                return sb.toString();
        }

        private static boolean typesListEquals(List<String> actual, List<String> expected, boolean allowVarargsElementCompatibility) {
                if (actual.size() != expected.size()) {
                        return false;
                }
                for (int i = 0; i < actual.size(); i++) {
                        if (!typeEqualsLoose(actual.get(i), expected.get(i))) {
                                if (!allowVarargsElementCompatibility
                                                || !varargsElementTypeCompatible(actual.get(i), expected.get(i))) {
                                        return false;
                                }
                        }
                }
                return true;
        }

        private static boolean typeEqualsLoose(String a, String b) {
                return normalizeTypeForLooseMatch(a).equals(normalizeTypeForLooseMatch(b));
        }

        /**
         * Java source varargs are bytecode arrays. Some mutation metadata records
         * int... as int, so int_toUni(int) should match source declaration
         * toUni(int... c), normalized as int[].
         */
        private static boolean varargsElementTypeCompatible(String actual, String expected) {
                String a = normalizeTypeForLooseMatch(actual);
                String e = normalizeTypeForLooseMatch(expected);
                if (a.endsWith("[]") && !e.endsWith("[]")) {
                        return a.substring(0, a.length() - 2).equals(e);
                }
                if (e.endsWith("[]") && !a.endsWith("[]")) {
                        return e.substring(0, e.length() - 2).equals(a);
                }
                return false;
        }

        private static String normalizeTypeForLooseMatch(String raw) {
                if (raw == null) {
                        return "";
                }
                String t = stripTypeAnnotations(raw);
                t = stripGenerics(t);
                t = t.replace("...", "[]").replace('$', '.').replaceAll("\\s+", "").trim();
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

        private static String simpleNameNoPackage(String raw) {
                return normalizeTypeForLooseMatch(raw);
        }

        /** Remove all generic angle-bracket content (supports nested) */
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
                        if (depth == 0)
                                out.append(c);
                }
                return out.toString();
        }

        /** Remove type annotations (e.g., @Nonnull, @A(@B)) */
        private static String stripTypeAnnotations(String type) {
                return type.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        }

        /** Simple data structure to store method signature */
        private static class Sig {
                final boolean isConstructor;
                final String returnType; // 构造函数时为 null
                final String name;
                final List<String> paramTypes;

                Sig(boolean isConstructor, String returnType, String name, List<String> paramTypes) {
                        this.isConstructor = isConstructor;
                        this.returnType = returnType;
                        this.name = name;
                        this.paramTypes = paramTypes;
                }
        }

        public static class MethodSlice {
                public final String text;
                public final int startLine;
                public final int endLine;
                public final String wrapperClassName;

                public MethodSlice(String text, int startLine, int endLine, String wrapperClassName) {
                        this.text = text;
                        this.startLine = startLine;
                        this.endLine = endLine;
                        this.wrapperClassName = wrapperClassName;
                }
        }

        public static MethodSlice extractMethodSlice(
                        String javaFile,
                        String classPathOrNull,
                        String sootSignature,
                        boolean includeSignature) throws Exception {
                Path file = Paths.get(javaFile);

                ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
                JavaParser parser = new JavaParser(cfg);
                var result = parser.parse(file);
                if (!result.getResult().isPresent()) {
                        throw new Exception("Parse failed: " + result.getProblems());
                }
                CompilationUnit cu = result.getResult().get();

                cu.getAllContainedComments().forEach(Comment::remove);

                Optional<CallableDeclaration<?>> cdOpt = findCallableBySignature(cu, sootSignature, classPathOrNull);
                if (cdOpt.isEmpty()) {
                        throw new IllegalArgumentException("No callable matched signature: " + sootSignature
                                        + (classPathOrNull == null ? "" : (" within class " + classPathOrNull)));
                }

                CallableDeclaration<?> cd = cdOpt.get();
                if (!cd.getRange().isPresent()) {
                        throw new IllegalStateException("Matched callable has no source range: " + sootSignature);
                }

                Range range = cd.getRange().get();
                int startLine = range.begin.line;
                int endLine = range.end.line;

                String rawSource = Files.readString(file, StandardCharsets.UTF_8);

                String text;
                if (includeSignature) {
                        text = extractSourceByRange(rawSource, range);
                } else if (cd instanceof MethodDeclaration md && md.getBody().isPresent()
                                && md.getBody().get().getRange().isPresent()) {
                        text = extractSourceByRange(rawSource, md.getBody().get().getRange().get());
                } else if (cd instanceof ConstructorDeclaration ctor && ctor.getBody().getRange().isPresent()) {
                        text = extractSourceByRange(rawSource, ctor.getBody().getRange().get());
                } else {
                        text = "";
                }

                String wrapperClassName = resolveWrapperClassName(classPathOrNull, cd);

                return new MethodSlice(text, startLine, endLine, wrapperClassName);
        }

        private static String extractSourceByRange(String source, Range range) {
                int start = positionToOffset(source, range.begin.line, range.begin.column);
                int endInclusive = positionToOffset(source, range.end.line, range.end.column);
                int endExclusive = Math.min(source.length(), endInclusive + 1);

                if (start < 0 || endExclusive < start || start > source.length()) {
                        throw new IllegalArgumentException("Invalid source range: " + range);
                }

                return source.substring(start, endExclusive);
        }

        private static int positionToOffset(String source, int targetLine, int targetColumn) {
                int line = 1;
                int column = 1;

                for (int i = 0; i < source.length(); i++) {
                        if (line == targetLine && column == targetColumn) {
                                return i;
                        }

                        char c = source.charAt(i);
                        if (c == '\n') {
                                line++;
                                column = 1;
                        } else {
                                column++;
                        }
                }

                if (line == targetLine && column == targetColumn) {
                        return source.length();
                }

                throw new IllegalArgumentException(
                                "Position out of range: line=" + targetLine + ", column=" + targetColumn);
        }

        private static String resolveWrapperClassName(String classPathOrNull, CallableDeclaration<?> cd) {
                if (cd instanceof ConstructorDeclaration ctor) {
                        return ctor.getNameAsString();
                }

                if (classPathOrNull != null && !classPathOrNull.isBlank()) {
                        String[] parts = classPathOrNull.split("\\.");
                        return parts[parts.length - 1];
                }

                return "DummyWrapper";
        }

        public static String extractMethodAsOneLine1(String filePath, String className, String methodName,
                        boolean includeSignature, List<String> wantedParams) throws Exception {
                Path file = Paths.get(filePath);

                ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
                JavaParser parser = new JavaParser(cfg);
                var result = parser.parse(file);

                if (!result.getResult().isPresent()) {
                        throw new Exception("Parse failed: " + result.getProblems());
                }
                CompilationUnit cu = result.getResult().get();

                // Remove all comments
                cu.getAllContainedComments().forEach(Comment::remove);

                // Find target class (supports inner class like Outer.Inner)
                Optional<TypeDeclaration<?>> typeOpt = findTypeByPath(cu, className);
                if (typeOpt.isEmpty()) {
                        throw new Exception("Class not found: " + className);
                }
                TypeDeclaration<?> targetType = typeOpt.get();

                // Find methods in this class (not subclasses; use findAll for inner-class
                // variants)
                List<MethodDeclaration> methods = targetType.getMembers().stream()
                                .filter(m -> m instanceof MethodDeclaration)
                                .map(m -> (MethodDeclaration) m)
                                .filter(md -> md.getNameAsString().equals(methodName))
                                .collect(Collectors.toList());

                if (methods.isEmpty()) {
                        throw new Exception("No method named '" + methodName + "' found in class " + className);
                }

                MethodDeclaration picked = pickOverload(methods, wantedParams)
                                .orElse(null);

                if (picked == null) {
                        throw new Exception("No overload matched for params: "
                                        + (wantedParams == null ? "[]" : wantedParams));
                }

                // Generate single line text
                String code = includeSignature
                                ? picked.toString() // Comments removed
                                : picked.getBody().map(Object::toString).orElse("");
                return code;
        }

        /**
         * Find type in CompilationUnit by class path. Supports dot-separated inner
         * class paths: Outer.Inner.MoreInner
         */
        private static Optional<TypeDeclaration<?>> findTypeByPath(CompilationUnit cu, String classPath) {
                if (classPath == null) {
                        return Optional.empty();
                }
                // Soot uses binary names for nested classes (Outer$Inner), whereas
                // JavaParser source paths use Outer.Inner.  Accept both.
                classPath = classPath.replace('$', '.');
                String[] parts = classPath.split("\\.");
                // Start from top-level TypeDeclarations in CU
                List<TypeDeclaration<?>> currentLevel = cu.getTypes();

                TypeDeclaration<?> current = null;
                for (int i = 0; i < parts.length; i++) {
                        String want = parts[i];
                        Optional<TypeDeclaration<?>> next = currentLevel.stream()
                                        .filter(td -> td.getNameAsString().equals(want))
                                        .findFirst();

                        if (next.isEmpty()) {
                                // Looser match: allow single-segment (e.g., "Inner") if uniquely found in file
                                if (parts.length == 1) {
                                        List<TypeDeclaration<?>> allTypes = getAllTypes(cu);
                                        List<TypeDeclaration<?>> hits = allTypes.stream()
                                                        .filter(td -> td.getNameAsString().equals(want))
                                                        .collect(Collectors.toList());
                                        if (hits.size() == 1)
                                                return Optional.of(hits.get(0));
                                }
                                return Optional.empty();
                        }

                        current = next.get();
                        // Next level: go into nested type
                        currentLevel = getNestedTypes(current);
                }
                return Optional.ofNullable(current);
        }

        /** Get all nested types under a node */
        private static List<TypeDeclaration<?>> getNestedTypes(TypeDeclaration<?> td) {
                return td.getMembers().stream()
                                .filter(m -> m instanceof TypeDeclaration<?>)
                                .map(m -> (TypeDeclaration<?>) m)
                                .collect(Collectors.toList());
        }

        /** Get all types in the file (including nested ones) */
        private static List<TypeDeclaration<?>> getAllTypes(CompilationUnit cu) {
                List<TypeDeclaration<?>> out = new ArrayList<>();
                cu.findAll(TypeDeclaration.class).forEach(out::add);
                return out;
        }

        /** Select overload matching parameter list; if none provided, pick first */
        private static Optional<MethodDeclaration> pickOverload(List<MethodDeclaration> methods,
                        List<String> wantedParams) {
                if (wantedParams == null) {
                        return methods.stream().findFirst();
                }
                List<String> wanted = wantedParams.stream().map(
                                MethodContent::normalizeType)
                                .collect(Collectors.toList());
                return methods.stream()
                                .filter(md -> {
                                        List<String> actual = md.getParameters().stream()
                                                        .map(p -> normalizeType(p.getType()))
                                                        .collect(Collectors.toList());
                                        return actual.equals(wanted);
                                })
                                .findFirst();
        }

        public static String getSimpleSignature(MethodDeclaration md) {
                String returnType = md.getType().asString(); // Return type
                String methodName = md.getNameAsString(); // Method name
                String paramTypes = md.getParameters().stream()
                                .map(p -> p.getType().asString())
                                .collect(Collectors.joining(","));
                return returnType + " " + methodName + "(" + paramTypes + ")";
        }

        // ---- Normalization tools: follow JavaParser Type rules ----
        private static String normalizeType(Type t) {
                return t.asString().replaceAll("\\s+", "");
        }

        private static String normalizeType(String s) {
                return s.replaceAll("\\s+", "");
        }
}
