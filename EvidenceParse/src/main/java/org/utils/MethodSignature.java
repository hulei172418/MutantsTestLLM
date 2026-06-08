package org.utils;

import soot.SootMethod;
import soot.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MethodSignature {

    public static String getMethodSignature(SootMethod method) {
        String methodName;
        String temp;
        if ("<init>".equals(method.getName())) {
            methodName = simpleTypeName(method.getDeclaringClass().toString());
            temp = methodName;
        } else {
            temp = simpleTypeName(method.getReturnType().toString());
            methodName = method.getName();
            temp = temp + "_" + methodName;
        }

        StringBuilder str = new StringBuilder(temp + "(");
        List<Type> pars = method.getParameterTypes();

        for (int i = 0; i < pars.size(); i++) {
            String tempParameter = simpleTypeName(pars.get(i).toString());
            str.append(tempParameter);

            if (i != (pars.size() - 1)) {
                str.append(",");
            }
        }
        str.append(")");
        return str.toString();
    }

    /**
     * Match a Soot method against the internal method signature forms used by this
     * project:
     *   returnType_methodName(paramTypes)
     *   ConstructorName(paramTypes)
     * and, defensively, prompt-facing Java-like forms such as:
     *   public <T extends Constant> T getConstant(int, Class<T>)
     *
     * Generic type variables are handled according to bytecode erasure.  Soot sees
     * erased types, while source/mutant metadata may still contain T/E/R, etc.
     * Method name and parameter arity remain strict.  Parameter types are also
     * strict except when one side is a source-level type variable.
     */
    public static boolean matchesSootLikeSignature(SootMethod method, String expectedSig) {
        Sig sig = parseSig(expectedSig);

        boolean isCtor = "<init>".equals(method.getName());
        if (sig.isConstructor != isCtor) {
            return false;
        }

        if (isCtor) {
            String actualCtorName = simpleTypeName(method.getDeclaringClass().toString());
            if (!Objects.equals(actualCtorName, sig.name)) {
                return false;
            }
        } else {
            if (!Objects.equals(method.getName(), sig.name)) {
                return false;
            }
            String actualRet = simpleTypeName(method.getReturnType().toString());
            if (!returnTypeCompatible(actualRet, sig.returnType)) {
                return false;
            }
        }

        List<Type> actualParams = method.getParameterTypes();
        List<String> actualParamNames = new ArrayList<>();
        for (Type t : actualParams) {
            actualParamNames.add(simpleTypeName(t.toString()));
        }

        if (paramsCompatible(actualParamNames, sig.paramTypes)) {
            return true;
        }

        // Enum constructors have two synthetic bytecode parameters before the
        // source-declared parameters: java.lang.String name and int ordinal.
        // Source/mutant metadata usually records only JsonEncoding(String,boolean,int)
        // or JsonToken(String,int).  Soot sees <init>(String,int,String,boolean,int)
        // / <init>(String,int,String,int).  Match by skipping that synthetic prefix.
        if (isCtor && actualParamNames.size() == sig.paramTypes.size() + 2
                && "String".equals(actualParamNames.get(0))
                && "int".equals(actualParamNames.get(1))
                && paramsCompatible(actualParamNames.subList(2, actualParamNames.size()), sig.paramTypes)) {
            return true;
        }

        return false;
    }


    private static boolean paramsCompatible(List<String> actualParams, List<String> expectedParams) {
        if (actualParams.size() != expectedParams.size()) {
            return false;
        }
        for (int i = 0; i < actualParams.size(); i++) {
            String actual = simpleTypeName(actualParams.get(i));
            String expected = simpleTypeName(expectedParams.get(i));
            if (!paramTypeCompatible(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean returnTypeCompatible(String actualReturnType, String expectedReturnType) {
        String actual = simpleTypeName(actualReturnType);
        String expected = simpleTypeName(expectedReturnType);
        if (Objects.equals(actual, expected)) {
            return true;
        }

        // Java source/bytecode method dispatch is determined by name + parameter
        // types, not by return type.  In our input metadata the return prefix can be
        // imprecise for several real cases: generic erasure (T -> Constant/Object),
        // bridge methods, or hand-written mutant metadata around Object overrides
        // such as int_hashCode().  Once the method name and parameter list have
        // matched, allowing a return-type mismatch is safe because Java cannot
        // overload two methods by return type alone.
        if (!"void".equals(actual) && !"void".equals(expected)) {
            return true;
        }

        // Keep void strict: a void method must not be matched to a non-void method
        // because invocation-template generation depends on this distinction.
        return false;
    }

    private static boolean paramTypeCompatible(String actualParamType, String expectedParamType) {
        String actual = simpleTypeName(actualParamType);
        String expected = simpleTypeName(expectedParamType);
        if (Objects.equals(actual, expected)) {
            return true;
        }
        // Java varargs are represented as arrays in bytecode/Soot.  Some mutant
        // metadata records int... as int, e.g. int_toUni(int), while Soot sees
        // int toUni(int[]).  Allow array element compatibility only after method
        // name and parameter arity have already matched.
        if (varargsElementTypeCompatible(actual, expected)) {
            return true;
        }

        // Allows source signatures like T_setValue(T) to match erased Object.
        if (isTypeVariableCompatible(actual, expected)) {
            return true;
        }

        // Allows generic arrays after erasure, e.g. T[]_fill(T[],T) to match
        // Object[] fill(Object[], Object).  Dimensions must still match.
        if (isGenericArrayCompatible(actual, expected)) {
            return true;
        }

        return false;
    }

    private static boolean varargsElementTypeCompatible(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.endsWith("[]") && !expected.endsWith("[]")) {
            return actual.substring(0, actual.length() - 2).equals(expected);
        }
        if (expected.endsWith("[]") && !actual.endsWith("[]")) {
            return expected.substring(0, expected.length() - 2).equals(actual);
        }
        return false;
    }

    private static boolean isTypeVariableCompatible(String actual, String expected) {
        if (isLikelyTypeVariable(actual) || isLikelyTypeVariable(expected)) {
            return true;
        }
        return false;
    }

    private static boolean isGenericArrayCompatible(String actual, String expected) {
        int ad = arrayDims(actual);
        int ed = arrayDims(expected);
        if (ad == 0 || ed == 0 || ad != ed) {
            return false;
        }
        String ab = stripArraySuffix(actual);
        String eb = stripArraySuffix(expected);
        return isLikelyTypeVariable(ab) || isLikelyTypeVariable(eb);
    }

    private static int arrayDims(String t) {
        if (t == null) {
            return 0;
        }
        int n = 0;
        while (t.endsWith("[]")) {
            n++;
            t = t.substring(0, t.length() - 2);
        }
        return n;
    }

    private static String stripArraySuffix(String t) {
        if (t == null) {
            return "";
        }
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2);
        }
        return t;
    }

    private static boolean isLikelyTypeVariable(String t) {
        if (t == null) {
            return false;
        }
        t = t.trim();
        if (t.isEmpty() || t.contains(".") || t.contains("[") || t.contains("]")) {
            return false;
        }
        if (isPrimitiveOrVoid(t) || isKnownJavaType(t)) {
            return false;
        }
        if (t.length() <= 3) {
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (!(c == '_' || Character.isUpperCase(c) || Character.isDigit(c))) {
                    return false;
                }
            }
            return Character.isUpperCase(t.charAt(0));
        }
        return false;
    }

    private static boolean isPrimitiveOrVoid(String t) {
        return "void".equals(t) || "boolean".equals(t) || "byte".equals(t) || "short".equals(t)
                || "char".equals(t) || "int".equals(t) || "long".equals(t)
                || "float".equals(t) || "double".equals(t);
    }

    private static boolean isKnownJavaType(String t) {
        return "String".equals(t) || "Object".equals(t) || "Class".equals(t)
                || "Integer".equals(t) || "Long".equals(t) || "Boolean".equals(t)
                || "Byte".equals(t) || "Short".equals(t) || "Character".equals(t)
                || "Float".equals(t) || "Double".equals(t) || "Void".equals(t)
                || "List".equals(t) || "Set".equals(t) || "Map".equals(t)
                || "Collection".equals(t) || "Iterable".equals(t) || "Optional".equals(t);
    }

    private static String simpleTypeName(String s) {
        if (s == null) {
            return null;
        }

        s = s.trim();
        s = stripTypeAnnotations(s);
        s = stripLeadingTypeParameters(s);
        s = stripWildcardOrBound(s);
        s = stripGenerics(s);
        s = s.replace('$', '.');
        s = s.replace("...", "[]");

        int arrayPos = s.indexOf('[');
        String arraySuffix = arrayPos >= 0 ? s.substring(arrayPos).replaceAll("\\s+", "") : "";
        String base = arrayPos >= 0 ? s.substring(0, arrayPos) : s;
        base = base.trim();

        int lastDot = base.lastIndexOf('.');
        if (lastDot >= 0) {
            base = base.substring(lastDot + 1);
        }

        return base + arraySuffix;
    }

    private static Sig parseSig(String sig) {
        if (sig == null) {
            throw new IllegalArgumentException("Bad soot-like signature: null");
        }
        sig = stripLeadingTypeParameters(sig.trim());

        int lp = sig.indexOf('(');
        int rp = sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) {
            throw new IllegalArgumentException("Bad soot-like signature: " + sig);
        }

        int us = sig.indexOf('_');
        String inside = sig.substring(lp + 1, rp).trim();

        List<String> params = new ArrayList<>();
        if (!inside.isEmpty()) {
            for (String p : splitTopLevel(inside)) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    params.add(t);
                }
            }
        }

        if (us > 0 && us < lp) {
            String returnType = sig.substring(0, us).trim();
            String name = sig.substring(us + 1, lp).trim();
            return new Sig(false, returnType, name, params);
        }

        String head = sig.substring(0, lp).trim();
        head = stripModifiers(head);
        head = stripLeadingTypeParameters(head);
        String[] parts = head.split("\\s+");
        if (parts.length >= 2) {
            String returnType = parts[parts.length - 2].trim();
            String name = parts[parts.length - 1].trim();
            return new Sig(false, returnType, name, params);
        }

        String name = sig.substring(0, lp).trim();
        return new Sig(true, null, name, params);
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int genericDepth = 0;
        int parenDepth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                genericDepth++;
                cur.append(c);
            } else if (c == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
                cur.append(c);
            } else if (c == '(') {
                parenDepth++;
                cur.append(c);
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
                cur.append(c);
            } else if (c == ',' && genericDepth == 0 && parenDepth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
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
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripTypeAnnotations(String type) {
        return type.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
    }

    private static String stripWildcardOrBound(String type) {
        String t = type.trim();
        if (t.startsWith("? extends ")) {
            return t.substring("? extends ".length()).trim();
        }
        if (t.startsWith("? super ")) {
            return t.substring("? super ".length()).trim();
        }
        int extendsPos = t.indexOf(" extends ");
        if (extendsPos > 0) {
            return t.substring(extendsPos + " extends ".length()).trim();
        }
        return t;
    }

    private static String stripLeadingTypeParameters(String text) {
        String s = text.trim();
        if (!s.startsWith("<")) {
            return s;
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    return s.substring(i + 1).trim();
                }
            }
        }
        return s;
    }

    private static String stripModifiers(String head) {
        String s = head.trim();
        boolean changed;
        do {
            changed = false;
            for (String mod : new String[] {"public", "protected", "private", "static", "final", "abstract", "synchronized", "native", "strictfp", "default"}) {
                if (s.startsWith(mod + " ")) {
                    s = s.substring(mod.length()).trim();
                    changed = true;
                }
            }
        } while (changed);
        return s;
    }

    private static class Sig {
        final boolean isConstructor;
        final String returnType;
        final String name;
        final List<String> paramTypes;

        Sig(boolean isConstructor, String returnType, String name, List<String> paramTypes) {
            this.isConstructor = isConstructor;
            this.returnType = returnType;
            this.name = name;
            this.paramTypes = paramTypes;
        }
    }
}
