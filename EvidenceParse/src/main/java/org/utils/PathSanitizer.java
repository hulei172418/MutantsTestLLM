package org.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Small Windows-path compatibility helper.
 *
 * Some existing scripts/exported Excel rows use the Windows long-path prefix
 * like "\\\\?\\E:\\...".  java.nio.file.Paths on Windows treats the '?' as an
 * illegal UNC server name in some JDKs, so we strip that prefix before creating
 * a Path.  This keeps the previous behavior of the old generator while leaving
 * normal paths unchanged.
 */
public final class PathSanitizer {
    private PathSanitizer() {}

    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return s;
        }

        // Standard Windows extended-length local path: \\?\C:\path
        if (s.startsWith("\\\\?\\UNC\\")) {
            return "\\\\" + s.substring("\\\\?\\UNC\\".length());
        }
        if (s.startsWith("\\\\?\\")) {
            return s.substring("\\\\?\\".length());
        }

        // Slash-normalized variants sometimes appear after previous replaces.
        if (s.startsWith("//?/UNC/")) {
            return "//" + s.substring("//?/UNC/".length());
        }
        if (s.startsWith("//?/")) {
            return s.substring("//?/".length());
        }

        // Defensive cleanup for partially normalized strings.
        if (s.startsWith("\\\\?/")) {
            return s.substring("\\\\?/".length());
        }
        if (s.startsWith("//?\\")) {
            return s.substring("//?\\".length());
        }
        return s;
    }

    public static Path path(String first, String... more) {
        return Paths.get(clean(first), more);
    }

    public static String normalizeForJson(String raw) {
        String s = clean(raw);
        return s == null ? "" : s.replace("\\", "/");
    }

    public static String normalizeForJson(Path p) {
        return p == null ? "" : p.toString().replace("\\", "/");
    }
}
