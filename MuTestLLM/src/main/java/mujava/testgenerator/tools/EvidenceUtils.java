package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

import static mujava.testgenerator.tools.GeneratorDefaults.*;

/**
 * Shared JSON wrapper readers and Java-snippet cleanup helpers used while
 * compressing output.json into prompt evidence.
 */
public final class EvidenceUtils {
    private EvidenceUtils() {
    }

public static List<String> jsonArrayToStringList(JSONArray arr) {
    List<String> out = new ArrayList<String>();
    if (arr == null) {
        return out;
    }
    for (int i = 0; i < arr.length(); i++) {
        Object v = arr.opt(i);
        if (v != null) {
            String x = String.valueOf(v).trim();
            if (!x.isEmpty()) {
                out.add(x);
            }
        }
    }
    return out;
}

public static String sanitizeJavaSnippet(String s) {
    if (s == null) {
        return "";
    }
    String x = s.trim();
    x = x.replace("{ },;", "{};");
    x = x.replace("{ };", "{};");
    x = x.replace("{ },", "{},");
    x = x.replace(" ,", ",");
    return x;
}

public static List<String> cleanJavaStatements(List<String> input) {
    List<String> out = new ArrayList<String>();
    LinkedHashSet<String> seen = new LinkedHashSet<String>();
    if (input == null) {
        return out;
    }
    for (String s : input) {
        String x = sanitizeJavaSnippet(s);
        if (x.isEmpty()) {
            continue;
        }
        if (seen.add(x)) {
            out.add(x);
        }
    }
    return out;
}

public static List<String> assertionTemplates(JSONObject wrapper) {
    List<String> out = new ArrayList<String>();
    JSONArray arr = itemsArray(wrapper);
    for (int i = 0; i < arr.length(); i++) {
        Object v = arr.opt(i);
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            String template = fieldString(o, "template");
            if (!template.isEmpty()) {
                out.add(template);
            } else {
                out.add(o.toString());
            }
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
    }
    return out;
}

public static List<String> observableExpressions(JSONObject wrapper) {
    List<String> out = new ArrayList<String>();
    JSONArray arr = itemsArray(wrapper);
    for (int i = 0; i < arr.length(); i++) {
        Object v = arr.opt(i);
        if (v instanceof JSONObject) {
            String expr = fieldString((JSONObject) v, "expression");
            if (!expr.isEmpty()) {
                out.add(expr);
            }
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
    }
    return out;
}

public static String inferPropagationHint(JSONObject root) {
    String diff = wrapperString(root.optJSONObject("Diff")).toLowerCase(Locale.ROOT);
    JSONObject entryRip = itemObject(root.optJSONObject("EntryLiftedRIP"));
    JSONObject entryRelation = itemObject(childObject(entryRip, "entryRelation"));
    String invocationKind = fieldString(entryRip, "entryInvocationKind");
    String mutationMethod = fieldString(entryRelation, "mutationMethod").toLowerCase(Locale.ROOT);

    if (diff.contains("append") && (mutationMethod.contains("message") || invocationKind.contains("CONSTRUCTOR"))) {
        return "The mutation changes string/message construction. Prefer assertions on the public message or returned string, such as getMessage() or toString-derived output.";
    }
    if (diff.contains("output.append") || diff.contains("append(ch") || diff.contains("appendable")) {
        return "The mutation changes an external Appendable/StringBuilder write. Create a visible collaborator and assert its final text/state.";
    }
    if (diff.contains("return")) {
        return "The mutation may affect the returned value. Assert the return value or a public observable derived from it.";
    }
    return "Use the diff, affected statements, and assertion plan to choose a mutation-sensitive observable output.";
}

public static List<String> ensureBasicJUnitImports(List<String> imports) {
    LinkedHashSet<String> set = new LinkedHashSet<String>();
    if (imports != null) {
        set.addAll(imports);
    }
    set.add("org.junit.Test");
    set.add("static org.junit.Assert.*");
    return new ArrayList<String>(set);
}

public static List<String> cleanCallChain(List<String> callChain) {
    List<String> out = new ArrayList<String>();
    if (callChain == null) {
        return out;
    }
    for (String s : callChain) {
        if (s == null || s.trim().isEmpty()) {
            continue;
        }
        out.add(s.trim());
    }
    return out;
}

public static JSONObject itemObject(JSONObject obj) {
    if (obj == null) {
        return new JSONObject();
    }
    Object item = obj.opt("item");
    if (item instanceof JSONObject) {
        return (JSONObject) item;
    }
    return obj;
}

public static JSONObject childObject(JSONObject parent, String key) {
    if (parent == null) {
        return new JSONObject();
    }
    JSONObject child = parent.optJSONObject(key);
    return itemObject(child);
}

public static String wrapperString(JSONObject wrapper) {
    if (wrapper == null) {
        return "";
    }
    Object item = wrapper.opt("item");
    if (item != null) {
        return String.valueOf(item);
    }
    return wrapper.optString("", "");
}

public static List<String> wrapperItems(JSONObject wrapper) {
    JSONArray arr = itemsArray(wrapper);
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < arr.length(); i++) {
        Object v = arr.opt(i);
        if (v == null) {
            continue;
        }
        if (v instanceof JSONObject) {
            out.add(((JSONObject) v).toString());
        } else {
            out.add(String.valueOf(v));
        }
    }
    return out;
}

public static String fieldString(JSONObject obj, String key) {
    if (obj == null || key == null) {
        return "";
    }
    Object v = obj.opt(key);
    if (v instanceof JSONObject) {
        JSONObject o = (JSONObject) v;
        Object item = o.opt("item");
        if (item != null) {
            return String.valueOf(item).trim();
        }
        return o.toString();
    }
    if (v == null) {
        return "";
    }
    return String.valueOf(v).trim();
}

public static Boolean fieldBoolean(JSONObject obj, String key, Boolean fallback) {
    if (obj == null || key == null) {
        return fallback;
    }
    Object v = obj.opt(key);
    if (v instanceof JSONObject) {
        Object item = ((JSONObject) v).opt("item");
        if (item instanceof Boolean) {
            return (Boolean) item;
        }
        if (item != null) {
            return Boolean.valueOf(String.valueOf(item));
        }
        return fallback;
    }
    if (v instanceof Boolean) {
        return (Boolean) v;
    }
    if (v != null) {
        return Boolean.valueOf(String.valueOf(v));
    }
    return fallback;
}

public static boolean firstBoolean(boolean fallback, Boolean... values) {
    if (values == null) {
        return fallback;
    }
    for (Boolean b : values) {
        if (b != null) {
            return b;
        }
    }
    return fallback;
}

public static List<String> fieldItems(JSONObject obj, String key) {
    if (obj == null) {
        return Collections.emptyList();
    }
    JSONObject wrapper = obj.optJSONObject(key);
    if (wrapper != null) {
        return wrapperItems(wrapper);
    }
    JSONArray arr = obj.optJSONArray(key);
    if (arr != null) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v != null) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }
    String one = obj.optString(key, "");
    if (one.trim().isEmpty()) {
        return Collections.emptyList();
    }
    return Collections.singletonList(one.trim());
}

public static JSONArray itemsArray(JSONObject wrapper) {
    if (wrapper == null) {
        return new JSONArray();
    }
    JSONArray arr = wrapper.optJSONArray("items");
    if (arr != null) {
        return arr;
    }
    Object item = wrapper.opt("item");
    JSONArray out = new JSONArray();
    if (item instanceof JSONArray) {
        return (JSONArray) item;
    }
    if (item != null) {
        out.put(item);
    }
    return out;
}

public static void copyObject(JSONObject target, JSONObject src) {
    if (src == null) {
        return;
    }
    for (String key : src.keySet()) {
        target.put(key, src.get(key));
    }
}

public static void putIfNotEmpty(JSONObject obj, String key, String value) {
    if (value != null && !value.trim().isEmpty()) {
        obj.put(key, value.trim());
    }
}

public static void putBooleanIfPresent(JSONObject obj, String key, Boolean value) {
    if (value != null) {
        obj.put(key, value.booleanValue());
    }
}

public static String trimEvidence(String text, int limit) {
    if (text == null) {
        return "";
    }
    String s = text.trim();
    if (s.length() <= limit) {
        return s;
    }
    return s.substring(0, limit) + "\n...<truncated>...";
}

public static List<String> limitList(List<String> input, int max) {
    if (input == null || input.isEmpty()) {
        return Collections.emptyList();
    }
    List<String> clean = new ArrayList<String>();
    for (String s : input) {
        if (s == null || s.trim().isEmpty()) {
            continue;
        }
        clean.add(trimEvidence(s, DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        if (clean.size() >= max) {
            break;
        }
    }
    return clean;
}
}
