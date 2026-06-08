package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Extracts and normalizes Java source from an OpenAI-compatible response.
 */
public final class GeneratedCodeExtractor {
    private GeneratedCodeExtractor() {
    }

    public static String extractJavaCode(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(rawResponse);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice0 = choices.optJSONObject(0);
                if (choice0 != null) {
                    JSONObject msg = choice0.optJSONObject("message");
                    if (msg != null) {
                        String content = msg.optString("content", "");
                        if (!content.trim().isEmpty()) {
                            return stripMarkdownCodeFence(content);
                        }
                        // Some reasoning models may return reasoning_content but empty visible content.
                        // Do not fall back to raw JSON in that case, otherwise the JSON response is written as .java.
                        if (msg.has("reasoning_content")) {
                            return "";
                        }
                    }
                    String text = choice0.optString("text", "");
                    if (!text.trim().isEmpty()) {
                        return stripMarkdownCodeFence(text);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return stripMarkdownCodeFence(rawResponse);
    }

    public static boolean containsExpectedTypeDeclaration(String code, String testFqn) {
        if (code == null || code.trim().isEmpty() || testFqn == null || testFqn.trim().isEmpty()) {
            return false;
        }

        String simple = simpleNameOf(testFqn);
        Pattern p = Pattern.compile(
                "\\b(class|interface|enum)\\s+" + Pattern.quote(simple) + "\\b"
        );
        return p.matcher(code).find();
    }

    public static String finishReason(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "";
        }

        try {
            JSONObject root = new JSONObject(rawResponse);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice0 = choices.optJSONObject(0);
                if (choice0 != null) {
                    return choice0.optString("finish_reason", "");
                }
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    public static String stripMarkdownCodeFence(String text) {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("```")) {
            Matcher m = Pattern.compile("^```[A-Za-z0-9_+-]*\\s*(.*?)\\s*```$", Pattern.DOTALL).matcher(s);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return s;
    }

    public static String normalizeGeneratedTestCode(String code, String testFqn) {
        if (code == null) {
            return "";
        }
        String s = stripMarkdownCodeFence(code).trim();
        String pkg = packageNameOf(testFqn);
        String simple = simpleNameOf(testFqn);

        s = removeInvalidDefaultPackageImports(s);

        if (!pkg.isEmpty() && !Pattern.compile("(?m)^\\s*package\\s+" + Pattern.quote(pkg) + "\\s*;").matcher(s).find()) {
            s = s.replaceFirst("(?m)^\\s*package\\s+[^;]+;\\s*", "");
            s = "package " + pkg + ";\n\n" + s;
        }

        Matcher m = Pattern.compile("public\\s+class\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(s);
        if (m.find() && !simple.equals(m.group(1))) {
            s = m.replaceFirst("public class " + simple);
        }
        return s;
    }

    private static String removeInvalidDefaultPackageImports(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Pattern invalidSingleNameImport = Pattern.compile("^\\s*import\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*;\\s*$");

        for (String line : lines) {
            if (invalidSingleNameImport.matcher(line).matches()) {
                // Java does not allow importing classes from the default package, e.g. import Vector3D;
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }
}
