package org.rip;

import java.util.ArrayList;
import java.util.List;

/** Converts internal Soot-like signatures into prompt-facing Java-style signatures. */
public final class PromptSignatureFormatter {
    private PromptSignatureFormatter() {}

    public static String method(String internalSig) {
        if (internalSig == null) return "";
        String sig = internalSig.trim();
        if (sig.isEmpty()) return "";

        int lp = sig.indexOf('(');
        int rp = sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) return simplifyType(sig);

        String head = sig.substring(0, lp).trim();
        String params = sig.substring(lp + 1, rp).trim();
        String paramsDisplay = displayParams(params);

        // Internal Soot-like method signature: returnType_method(params).
        int sep = head.indexOf('_');
        if (sep > 0) {
            String ret = simplifyType(head.substring(0, sep));
            String name = head.substring(sep + 1).trim();
            return ret + " " + name + "(" + paramsDisplay + ")";
        }

        // Already prompt-facing method signature: returnType methodName(params).
        // This prevents double-formatting from turning "String createMessage"
        // into "StringcreateMessage" when callChain is formatted twice.
        String[] parts = head.split("\\s+");
        if (parts.length >= 2) {
            String name = parts[parts.length - 1].trim();
            String ret = parts[parts.length - 2].trim();
            return simplifyType(ret) + " " + name + "(" + paramsDisplay + ")";
        }

        // Constructor signature: ClassName(params).
        return simplifyType(head) + "(" + paramsDisplay + ")";
    }

    public static String methodWithClass(String className, String internalSig) {
        String cls = simplifyType(className);
        String m = method(internalSig);
        if (cls.isEmpty()) return m;
        if (m.isEmpty()) return cls;
        return cls + "#" + m;
    }

    public static String callChain(String chain) {
        if (chain == null || chain.trim().isEmpty()) return "";
        String[] parts = chain.split("\\s*->\\s*");
        List<String> out = new ArrayList<>();
        for (String p : parts) out.add(callChainNode(p));
        return String.join(" -> ", out);
    }

    private static String callChainNode(String text) {
        if (text == null) return "";
        String s = text.trim();
        int hash = s.lastIndexOf('#');
        if (hash >= 0 && hash + 1 < s.length()) {
            return s.substring(0, hash + 1) + method(s.substring(hash + 1));
        }
        return s;
    }

    private static String displayParams(String params) {
        if (params == null || params.trim().isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (String p : splitTopLevel(params)) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(simplifyType(t));
        }
        return String.join(", ", out);
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { depth++; cur.append(c); }
            else if (c == '>') { depth = Math.max(0, depth - 1); cur.append(c); }
            else if (c == ',' && depth == 0) { out.add(cur.toString()); cur.setLength(0); }
            else { cur.append(c); }
        }
        out.add(cur.toString());
        return out;
    }

    public static String simplifyType(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace('$', '.');
        if (s.isEmpty()) return "";
        s = stripTypeAnnotations(s);
        s = stripGenerics(s);
        s = s.replace("...", "[]").replaceAll("\\s+", "");
        int dims = 0;
        while (s.endsWith("[]")) { dims++; s = s.substring(0, s.length() - 2); }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }

    private static String stripGenerics(String type) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') { depth++; continue; }
            if (c == '>') { depth = Math.max(0, depth - 1); continue; }
            if (depth == 0) out.append(c);
        }
        return out.toString();
    }

    private static String stripTypeAnnotations(String type) {
        return type.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
    }
}
