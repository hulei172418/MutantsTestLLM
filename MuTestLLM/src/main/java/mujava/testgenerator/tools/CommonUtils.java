package mujava.testgenerator.tools;

/**
 * Small string and validation helpers shared by the generator pipeline.
 */
public final class CommonUtils {
    private CommonUtils() {
    }

    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    public static void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
