package mujava.testgenerator.tools;

import java.io.File;
import java.nio.file.Path;

/**
 * Utilities for generated test names, file names, package names, and paths.
 */
public final class TestNameUtils {
    private TestNameUtils() {
    }

    public static String buildGeneratedTestFqn(String targetClassName, String mutantName) {
        String normalized = normalizeTargetClassName(targetClassName);
        int idx = normalized.lastIndexOf('.');
        String pkg = idx >= 0 ? normalized.substring(0, idx) : "";
        String simple = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        String suffix = safeJavaIdentifier(mutantName);
        String testSimple = simple + "_" + suffix + "_Test";
        return pkg.isEmpty() ? testSimple : pkg + "." + testSimple;
    }

    public static String normalizeTargetClassName(String targetClassName) {
        return targetClassName == null ? "" : targetClassName.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "").trim();
    }

    public static String safeJavaIdentifier(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "Mutant";
        }
        String s = text.replaceAll("[^A-Za-z0-9_$]+", "_");
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            s = "M_" + s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }

    public static String fileBaseName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    public static String packageNameOf(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(0, idx) : "";
    }

    public static String simpleNameOf(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    public static String normalizePath(String path) {
        return new File(path).getAbsoluteFile().toPath().normalize().toString();
    }

    public static Path toJavaFile(Path srcRoot, String fqn) {
        return srcRoot.resolve(fqn.replace('.', File.separatorChar) + ".java");
    }
}
