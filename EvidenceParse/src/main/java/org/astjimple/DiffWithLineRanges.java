package org.astjimple;

import com.github.gumtreediff.actions.ActionGenerator;
import com.github.gumtreediff.actions.model.Action;
import com.github.gumtreediff.actions.model.Insert;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.optimal.zs.ZsMatcher;
import com.github.gumtreediff.tree.ITree;
import com.github.gumtreediff.tree.Tree;
import com.github.gumtreediff.tree.TreeContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.astjimple.MethodContent.MethodSlice;

/**
 * ------- Line number differences between original and mutant programs ------
 * ---------- GumTree section: Change -> Line Number
 *
 * Design notes:
 * 1) Only cache the parsing result of the original file (fileP);
 * 2) Always parse the mutant file (fileM) directly, without caching;
 * 3) Remove the pair cache to avoid the extra memory overhead caused by caching
 * diff results for each original-mutant pair.
 */
public class DiffWithLineRanges {

    /**
     * Keep only the original-file cache:
     * key = original file state
     * value = parsed result of the original file (AST / source text / line-start
     * offsets)
     */
    private static final Map<String, ParsedJavaFile> ORIGINAL_FILE_CACHE = new ConcurrentHashMap<>();

    /**
     * Parsed result of a single file.
     */
    private static class ParsedJavaFile {
        final TreeContext treeContext;
        final Tree root;
        final String text;
        final int[] lineStarts;

        ParsedJavaFile(TreeContext treeContext, Tree root, String text, int[] lineStarts) {
            this.treeContext = treeContext;
            this.root = root;
            this.text = text;
            this.lineStarts = lineStarts;
        }
    }

    /**
     * Keep the original public method name unchanged.
     * Pair-level caching is no longer used, so the diff is computed directly each
     * time.
     * However, the original file may still hit ORIGINAL_FILE_CACHE.
     */
    public static List<ChangeRange> diffWithLineRanges(String fileP, String fileM) throws Exception {
        return computeDiffWithLineRanges(fileP, fileM);
    }

    /**
     * Method-level diff.
     * This is recommended for mutation analysis, where only one target method is
     * relevant to the downstream Soot alignment.
     */
    public static List<ChangeRange> diffWithLineRangesForMethod(
            String fileP,
            String fileM,
            String classPathOrNull,
            String sootSignature) throws Exception {
        return computeDiffWithLineRangesForMethod(fileP, fileM, classPathOrNull, sootSignature);
    }

    /**
     * Actual GumTree diff logic for whole-file diff.
     * The original file uses caching, while the mutant file is always parsed
     * directly.
     */
    private static List<ChangeRange> computeDiffWithLineRanges(String fileP, String fileM) throws Exception {
        ParsedJavaFile p = parseOriginalFileCached(fileP);
        ParsedJavaFile m = parseFileDirect(fileM);

        MappingStore store = new MappingStore();
        new ZsMatcher(p.root, m.root, store).match();

        ActionGenerator gen = new ActionGenerator(p.root, m.root, store);
        List<Action> actions = gen.generate();

        List<ChangeRange> ranges = new ArrayList<>();
        for (Action a : actions) {
            ITree n = a.getNode();
            int pos = n.getPos();
            int len = n.getLength();
            if (pos < 0 || len < 0) {
                continue;
            }

            boolean onSrcSide = !(a instanceof Insert); // Insert applies to dst; others default to src
            int start, end;
            if (onSrcSide) {
                start = offsetToLine(pos, p.lineStarts);
                end = offsetToLine(Math.max(pos + len - 1, pos), p.lineStarts);
            } else {
                start = offsetToLine(pos, m.lineStarts);
                end = offsetToLine(Math.max(pos + len - 1, pos), m.lineStarts);
            }

            String kind = a.getClass().getSimpleName();
            String where = n.getLabel();
            String snippet = safeSnippet(onSrcSide ? p.text : m.text, pos, len);
            ranges.add(new ChangeRange(kind, onSrcSide, start, end, where + " :: " + snippet));
        }

        return ranges;
    }

    /**
     * Actual GumTree diff logic for method-level diff.
     * We extract only the target method from each file, wrap it into a minimal
     * compilation unit, run GumTree on those two wrapped snippets, and then map the
     * wrapper-relative line numbers back to absolute source-file line numbers.
     */
    private static List<ChangeRange> computeDiffWithLineRangesForMethod(
            String fileP,
            String fileM,
            String classPathOrNull,
            String sootSignature) throws Exception {

        MethodSlice pSlice = MethodContent.extractMethodSlice(fileP, classPathOrNull, sootSignature, true);
        MethodSlice mSlice = MethodContent.extractMethodSlice(fileM, classPathOrNull, sootSignature, true);

        String wrappedP = wrapMethodAsCompilationUnit(pSlice.wrapperClassName, pSlice.text);
        String wrappedM = wrapMethodAsCompilationUnit(mSlice.wrapperClassName, mSlice.text);

        ParsedJavaFile p = parseTextDirect(wrappedP);
        ParsedJavaFile m = parseTextDirect(wrappedM);

        MappingStore store = new MappingStore();
        new ZsMatcher(p.root, m.root, store).match();

        ActionGenerator gen = new ActionGenerator(p.root, m.root, store);
        List<Action> actions = gen.generate();

        List<ChangeRange> ranges = new ArrayList<>();
        for (Action a : actions) {
            ITree n = a.getNode();
            int pos = n.getPos();
            int len = n.getLength();
            if (pos < 0 || len < 0) {
                continue;
            }

            boolean onSrcSide = !(a instanceof Insert); // Insert applies to dst; others default to src

            int wrapperStartLine;
            int wrapperEndLine;
            if (onSrcSide) {
                wrapperStartLine = offsetToLine(pos, p.lineStarts);
                wrapperEndLine = offsetToLine(Math.max(pos + len - 1, pos), p.lineStarts);
            } else {
                wrapperStartLine = offsetToLine(pos, m.lineStarts);
                wrapperEndLine = offsetToLine(Math.max(pos + len - 1, pos), m.lineStarts);
            }

            int start;
            int end;
            if (onSrcSide) {
                start = wrapperLineToAbsoluteLine(wrapperStartLine, pSlice.startLine);
                end = wrapperLineToAbsoluteLine(wrapperEndLine, pSlice.startLine);
            } else {
                start = wrapperLineToAbsoluteLine(wrapperStartLine, mSlice.startLine);
                end = wrapperLineToAbsoluteLine(wrapperEndLine, mSlice.startLine);
            }

            String kind = a.getClass().getSimpleName();
            String where = n.getLabel();
            String snippet = safeSnippet(onSrcSide ? p.text : m.text, pos, len);
            ranges.add(new ChangeRange(kind, onSrcSide, start, end, where + " :: " + snippet));
        }

        return ranges;
    }

    /**
     * Entry point for original-file caching.
     * Only fileP goes through this method; if the same original file appears across
     * multiple mutants, the cache hit rate can be significant.
     */
    private static ParsedJavaFile parseOriginalFileCached(String file) throws Exception {
        String fileKey = buildSingleFileKey(file);

        ParsedJavaFile hit = ORIGINAL_FILE_CACHE.get(fileKey);
        if (hit != null) {
            return hit;
        }

        ParsedJavaFile parsed = parseFileDirect(file);
        ORIGINAL_FILE_CACHE.put(fileKey, parsed);
        return parsed;
    }

    /**
     * Parse a file directly without using any cache.
     * This is intended for mutant files only; the original file may also go through
     * this path on its first load, and will then be cached afterward.
     */
    private static ParsedJavaFile parseFileDirect(String file) throws Exception {
        TreeContext tc = new JdtTreeGenerator().generateFromFile(new File(file));
        Tree root = (Tree) tc.getRoot();
        String text = readUtf8(file);
        int[] lineStarts = lineStartOffsets(text);
        return new ParsedJavaFile(tc, root, text, lineStarts);
    }

    /**
     * Parse source text directly by writing it to a temporary .java file first.
     * This avoids relying on whole-file Java sources when only a method slice is
     * needed.
     */
    private static ParsedJavaFile parseTextDirect(String sourceText) throws Exception {
        File tmp = File.createTempFile("gumtree_method_", ".java");
        try {
            Files.writeString(tmp.toPath(), sourceText, StandardCharsets.UTF_8);
            TreeContext tc = new JdtTreeGenerator().generateFromFile(tmp);
            Tree root = (Tree) tc.getRoot();
            int[] lineStarts = lineStartOffsets(sourceText);
            return new ParsedJavaFile(tc, root, sourceText, lineStarts);
        } finally {
            tmp.delete();
        }
    }

    /**
     * Build the single-file cache key.
     * Uses absolute path + file length + last modified time.
     */
    private static String buildSingleFileKey(String file) {
        File f = new File(file);
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    /**
     * Manually clear all caches.
     * At present, this only clears the original-file cache.
     */
    public static void clearCache() {
        ORIGINAL_FILE_CACHE.clear();
    }

    /**
     * The pair cache has been removed.
     * This method is retained only for backward compatibility and performs no
     * action.
     */
    public static void clearPairCache() {
        // no-op: pair cache removed intentionally
    }

    /**
     * Clear the original-file cache.
     */
    public static void clearFileCache() {
        ORIGINAL_FILE_CACHE.clear();
    }

    /**
     * Return the current number of pair-cache entries.
     * Since the pair cache has been removed, this always returns 0.
     */
    public static int pairCacheSize() {
        return 0;
    }

    /**
     * Return the current number of original-file cache entries.
     */
    public static int fileCacheSize() {
        return ORIGINAL_FILE_CACHE.size();
    }

    /**
     * Retained for compatibility with previous calls.
     * This originally returned the pair-cache size; since the pair cache has been
     * removed, it now always returns 0.
     */
    public static int cacheSize() {
        return 0;
    }

    /**
     * Compute the starting offset of each line.
     * This is used for binary search when converting a character offset to a line
     * number.
     */
    private static int[] lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Get the line number corresponding to a character offset.
     */
    private static int offsetToLine(int offset, int[] lineStarts) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        if (idx >= 0) {
            return idx + 1;
        }
        int ins = -idx - 1;
        return Math.max(1, ins);
    }

    /**
     * Extract the snippet text for the changed code region.
     */
    private static String safeSnippet(String s, int pos, int len) {
        int trimmedLen = 1024;
        int a = Math.max(0, pos);
        int b = Math.min(s.length(), pos + len);
        String t = s.substring(a, b).replace("\n", "⏎").trim();
        return t.length() > trimmedLen ? t.substring(0, trimmedLen) + "…" : t;
    }

    /**
     * Read a file as a UTF-8 string.
     */
    private static String readUtf8(String file) throws Exception {
        return new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Wrap one method into a minimal valid compilation unit so GumTree/JDT can
     * parse it.
     *
     * Wrapper layout:
     * line 1: public class <WrapperName> {
     * line 2..N-1: method text
     * last line: }
     *
     * Therefore, a line inside the wrapper maps back to the original method by:
     * absoluteLine = methodStartLine + wrapperLine - 2
     */
    private static String wrapMethodAsCompilationUnit(String wrapperClassName, String methodText) {
        return "public class " + wrapperClassName + " {\n"
                + methodText + "\n"
                + "}\n";
    }

    /**
     * Convert a line number inside the wrapped method-compilation-unit back to the
     * absolute line number in the original source file.
     */
    private static int wrapperLineToAbsoluteLine(int wrapperLine, int methodStartLine) {
        return methodStartLine + wrapperLine - 2;
    }

    public static void main(String[] args) throws Exception {
        String mID = "m4";
        String fileP = "src/main/java/demo/origin/ConstantPoolEntry.java";
        String fileM = "src/main/java/demo/" + mID + "/ConstantPoolEntry.java";

        System.out.println("=== First call ===");
        List<ChangeRange> changes1 = diffWithLineRanges(fileP, fileM);

        System.out.println("=== Second call (original file cache only) ===");
        List<ChangeRange> changes2 = diffWithLineRanges(fileP, fileM);

        System.out.println("=== AST Changes (with Line Numbers) ===");
        changes1.forEach(System.out::println);

        System.out.println("pair cache size = " + pairCacheSize());
        System.out.println("file cache size = " + fileCacheSize());
        System.out.println("second call result size = " + changes2.size());
    }
}