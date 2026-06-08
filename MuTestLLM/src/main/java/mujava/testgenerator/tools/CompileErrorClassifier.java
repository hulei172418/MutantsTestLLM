package mujava.testgenerator.tools;

import java.util.Locale;

/**
 * Classifies javac output so repair prompts can focus on the real failure type.
 */
public final class CompileErrorClassifier {
    private CompileErrorClassifier() {
    }

    public static CompileErrorKind classify(String error) {
        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);

        if (e.contains("finish_reason=length")
                || e.contains("output truncated")) {
            return CompileErrorKind.LLM_OUTPUT_TRUNCATED;
        }

        if (e.contains("empty visible content")
                || e.contains("reasoning_content only")) {
            return CompileErrorKind.EMPTY_LLM_CONTENT;
        }

        if (e.contains("javac returned success but expected class file not found")
                || e.contains("expected class file not found")) {
            return CompileErrorKind.EXPECTED_CLASS_NOT_FOUND;
        }

        if (e.contains("void cannot be converted")
                || e.contains("void type not allowed here")
                || e.contains("incompatible types: void")
                || e.contains("not a statement")) {
            return CompileErrorKind.VOID_VALUE_MISUSE;
        }

        if (e.contains("datainput")
                && (e.contains("does not override")
                || e.contains("readline")
                || e.contains("is not abstract")
                || e.contains("missing"))) {
            return CompileErrorKind.DATAINPUT_ANONYMOUS_STUB;
        }

        if (e.contains("is not abstract and does not override abstract method")
                || e.contains("does not override abstract method")) {
            return CompileErrorKind.ABSTRACT_STUB_INCOMPLETE;
        }

        if (e.contains("attempting to assign weaker access privileges")
                || e.contains("weaker access privileges")) {
            return CompileErrorKind.OVERRIDE_ACCESS_WEAKENING;
        }

        if (e.contains("method does not override or implement a method from a supertype")
                || e.contains("overridden method is final")
                || e.contains("cannot override")) {
            return CompileErrorKind.OVERRIDE_FORBIDDEN_OR_SIGNATURE;
        }

        if (e.contains("cannot find symbol")
                && (e.contains("getwidth(")
                || e.contains("getheight(")
                || e.contains("getignore(")
                || e.matches("(?s).*symbol:\\s+method\\s+get[A-Z].*"))) {
            return CompileErrorKind.INVENTED_GETTER_OR_API;
        }

        if (looksLikeConstructorAsMethod(e)) {
            return CompileErrorKind.CONSTRUCTOR_AS_METHOD;
        }

        if (e.contains("cannot find symbol") && e.matches("(?s).*symbol:\\s+variable\\s+.*")) {
            return CompileErrorKind.UNDEFINED_VARIABLE_OR_SETUP_MISMATCH;
        }

        if (e.contains("private access") || e.contains("has private access")) {
            return CompileErrorKind.PRIVATE_ACCESS;
        }

        if (e.contains("has protected access")) {
            return CompileErrorKind.PROTECTED_ACCESS;
        }

        if (e.contains("is not public")
                || e.contains("cannot be accessed from outside package")) {
            return CompileErrorKind.PACKAGE_PRIVATE_ACCESS;
        }

        if (e.contains("constructor")
                && (e.contains("cannot be applied")
                || e.contains("undefined")
                || e.contains("has private access")
                || e.contains("has protected access"))) {
            return CompileErrorKind.CONSTRUCTOR_MISMATCH;
        }

        if (e.contains("cannot be instantiated")
                || e.contains("abstract; cannot be instantiated")
                || e.contains(" is abstract")) {
            return CompileErrorKind.ABSTRACT_INSTANTIATION;
        }

        if (e.contains("cannot find symbol") && e.contains("method")) {
            return CompileErrorKind.METHOD_NOT_FOUND;
        }

        if (e.contains("cannot find symbol")) {
            return CompileErrorKind.MISSING_CLASS_OR_IMPORT;
        }

        if (e.contains("incompatible types")
                || e.contains("no suitable method found")
                || e.contains("reference to assertequals is ambiguous")) {
            return CompileErrorKind.INCOMPATIBLE_TYPES;
        }

        return CompileErrorKind.OTHER;
    }

    private static boolean looksLikeConstructorAsMethod(String e) {
        if (e == null) {
            return false;
        }

        // 典型错误：subject.ClassName(...)
        return e.contains("cannot find symbol")
                && e.contains("method")
                && e.matches("(?s).*symbol:\\s+method\\s+[A-Z][A-Za-z0-9_]*\\s*\\(.*");
    }

    static boolean looksLikeFactoryBuilderError(String e) {
        if (e == null) {
            return false;
        }

        if (e.contains("builder() has private access")
                || e.contains("builder() is private")) {
            return true;
        }

        if (e.contains("builder()") && e.contains("private access")) {
            return true;
        }

        if (e.contains("builder") && e.contains("private access")) {
            return true;
        }

        if (e.contains("cannot find symbol") && e.contains("method build")) {
            return true;
        }

        if (e.contains("symbol:   method build")
                || e.contains("symbol: method build")) {
            return true;
        }

        if (e.contains("constructor")
                && e.contains("cannot be applied")
                && (e.contains("textstyle") || e.contains("builder"))) {
            return true;
        }

        return false;
    }
}
