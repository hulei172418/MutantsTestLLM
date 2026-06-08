package org.astjimple;

// Modified AstToJimpleBridge.java
// Extension: starting from impacted statements, walk the CFG backward to the method entry and forward to \
// the method exit, and output full context paths

import soot.*;
import soot.jimple.DefinitionStmt;
import soot.options.Options;
import soot.tagkit.LineNumberTag;
import soot.tagkit.SourceLnPosTag;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.SimpleLocalDefs;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.utils.MethodSignature;

public class AstToJimpleBridge {
    private static final int depthLimit = 1024; // Maximum path depth
    private static final int pathLimit = 4; // Maximum number of paths

    // --- Configuration: adjust these variables for your project ---
    // Mutant ID
    private static final String mID = "m4";
    // Source file of original program p
    private static final String SRC_FILE_P = "src/main/java/demo/origin/ConstantPoolEntry.java";
    // Source file of mutant program m
    private static final String SRC_FILE_M = "src/main/java/demo/" + mID + "/ConstantPoolEntry.java";
    // Fully-qualified class name of p (compiled)
    private static final String CLASS_NAME_P = "ConstantPoolEntry";
    // Fully-qualified class name of m (usually same as p)
    private static final String CLASS_NAME_M = "ConstantPoolEntry";
    // Only analyze methods whose names contain this substring
    private static final String METHOD_NAME_SUBSTR = "boolean_PoolEntry(int,int)";
    // Output directory of .class files for p
    private static final String CLASSES_DIR_P = "target/classes/demo/origin";
    // Output directory of .class files for m
    private static final String CLASSES_DIR_M = "target/classes/demo/" + mID;
    // -------------------------------------------------------------

    public static void main(String[] args) throws Exception {

        List<ChangeRange> ranges = DiffWithLineRanges.diffWithLineRanges(SRC_FILE_P, SRC_FILE_M);
        // System.out.println("=== AST diffs (with line numbers) ===");
        ranges.forEach(System.out::println);

        analyzeWithSoot(CLASSES_DIR_P, CLASS_NAME_P, METHOD_NAME_SUBSTR, ranges, true);
        analyzeWithSoot(CLASSES_DIR_M, CLASS_NAME_M, METHOD_NAME_SUBSTR, ranges, false);
    }

    public static void initSoot(String clsDir) {
        G.reset();

        String absolutePath = Paths.get(clsDir).toAbsolutePath().toString();

        Options.v().set_whole_program(false);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_keep_line_number(true);
        Options.v().set_prepend_classpath(true);
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_process_dir(Collections.singletonList(absolutePath));
        Options.v().set_soot_classpath(
                absolutePath + File.pathSeparator + System.getProperty("java.class.path"));
        Options.v().setPhaseOption("jb", "use-original-names:true");

        Scene.v().loadBasicClasses();
    }

    public static Body getBody(String clsName, String methodNameSubstr) throws IOException {
        try {
            return getBodyFast(clsName, methodNameSubstr);
        } catch (Throwable fastEx) {
            Scene.v().loadNecessaryClasses();
            return getBodyFast(clsName, methodNameSubstr);
        }
    }

    private static Body getBodyFast(String clsName, String methodNameSubstr) throws IOException {
        SootClass sc = Scene.v().forceResolve(clsName, SootClass.BODIES);
        sc.setApplicationClass();

        for (SootMethod m : sc.getMethods()) {
            if (!m.isConcrete() || !MethodSignature.matchesSootLikeSignature(m, methodNameSubstr)) {
                continue;
            }
            return m.retrieveActiveBody();
        }

        throw new IOException("Method not found: " + clsName + " :: " + methodNameSubstr);
    }

    public static void analyzeWithSoot(String clsDir, String clsName, String methodNameSubstr,
            List<ChangeRange> lineRanges,
            boolean isP) throws IOException {

        initSoot(clsDir);

        Body body = getBody(clsName, methodNameSubstr);

        List<Unit> affected = analyzeAffectedUnits(body, lineRanges);
        if (affected.isEmpty()) {
            System.out.println(
                    "(Impacted Jimple is empty; the change might be in a different method or line info is missing)");
        }
        for (Unit u : affected) {
            int[] lr = unitLineRange(u);
            System.out.printf("JIMPLE [%d-%d] %s%n", lr[0], lr[1], u);
        }

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, lineRanges, affected);
        int count = 0;
        for (List<Unit> path : validePaths) {
            System.out.println("-- Path #" + ++count + " (through affected node) --");
            System.out.println(" " + pathToString(path));
        }

        UnitGraph cfg = new ExceptionalUnitGraph(body);

        Set<String> cfgEdges = createCFG(affected, body, cfg);
        System.out.println("CFG edges (u -> v)：");
        if (cfgEdges.isEmpty()) {
            System.out.println("Impacted CFG is empty");
        }
        cfgEdges.forEach(System.out::println);

        Set<String> dfgEdges = createDFG(affected, body, cfg);
        System.out.println("DFG edges (def -> use)：");
        if (dfgEdges.isEmpty()) {
            System.out.println("Impacted DFG is empty");
        }
        dfgEdges.forEach(System.out::println);

        String graphPath = clsDir + "/graph";
        Path outputDir = Paths.get(graphPath);
        Files.createDirectories(outputDir);
        saveIR(body, outputDir);
    }

    public static List<Unit> analyzeAffectedUnits(Body body, List<ChangeRange> lineRanges) {
        List<Unit> affected = new ArrayList<>();
        for (Unit u : body.getUnits()) {
            int[] lr = unitLineRange(u);
            if (intersects(lineRanges, lr[0], lr[1])) {
                affected.add(u);
            }
        }
        return affected;
    }

    /**
     * For each impacted statement, enumerate all Head→Tail paths that contain that
     * node (supporting multiple paths).
     */
    public static List<List<Unit>> analyzeAllPathsThroughAffected(Body body,
            List<ChangeRange> changeRanges,
            List<Unit> affected) {
        ExceptionalUnitGraph cfg = new ExceptionalUnitGraph(body);
        List<Unit> heads = cfg.getHeads();
        List<Unit> tails = cfg.getTails();

        // Starting from all heads, enumerate all head→tail paths with k-bounded visits
        int k = 1; // k-bounded: each node can be visited at most k+1 times
        List<List<Unit>> allPaths = enumerateAllHeadToTailPathsK(cfg, heads, tails, depthLimit, pathLimit, k);

        List<List<Unit>> validePaths = new ArrayList<>();

        for (List<Unit> path : allPaths) {
            int count = 0;
            for (Unit target : affected) {
                if (path.contains(target)) {
                    count++;
                }
            }
            if (count == affected.size()) {
                validePaths.add(path);
            }
        }

        return validePaths;
    }

    /**
     * Starting from all heads, enumerate all paths to the tail with an upper bound
     * to avoid explosion.
     */
    public static List<List<Unit>> enumerateAllHeadToTailPathsK(ExceptionalUnitGraph cfg,
            List<Unit> heads, List<Unit> tails,
            int maxDepth, int maxPaths, int k) {
        List<List<Unit>> all = new ArrayList<>();
        for (Unit h : heads) {
            dfsEnumeratePathsK(h, cfg, tails, new HashMap<>(), new ArrayList<>(), all, maxDepth, maxPaths, k);
            if (all.size() >= maxPaths)
                break;
        }
        return all;
    }

    // k-bounded path enumeration: allow each node to be visited at most k+1 times
    // (supports returning to the loop head once)
    // "seen" records how many times a node has been visited along the current path
    static void dfsEnumeratePathsK(Unit cur,
            ExceptionalUnitGraph cfg,
            List<Unit> tails,
            Map<Unit, Integer> seen,
            List<Unit> path,
            List<List<Unit>> out,
            int maxDepth, int maxPaths, int k) {
        if (out.size() >= maxPaths)
            return;
        if (path.size() >= maxDepth)
            return;

        int used = seen.getOrDefault(cur, 0);
        if (used >= k + 1)
            return; // Prune when the node is visited more than the allowed times

        seen.put(cur, used + 1);
        path.add(cur);

        List<Unit> succs = cfg.getSuccsOf(cur);
        boolean isTail = succs.isEmpty() || tails.contains(cur);
        if (isTail) {
            out.add(new ArrayList<>(path));
        } else {
            for (Unit s : succs) {
                // If we go back to an ancestor already on the path, this is a back edge; it is
                // still allowed under k-bounded enumeration but constrained by "seen"
                dfsEnumeratePathsK(s, cfg, tails, seen, path, out, maxDepth, maxPaths, k);
                if (out.size() >= maxPaths)
                    break;
            }
        }

        path.remove(path.size() - 1);
        // Backtracking: restore visit count
        if (used == 0)
            seen.remove(cur);
        else
            seen.put(cur, used);
    }

    public static String pathToString(List<Unit> path) {
        if (path.isEmpty())
            return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Unit u = path.get(i);
            int line = u.getJavaSourceStartLineNumber();
            sb.append("[").append(line).append("] ").append(u.toString().replace('\n', ' '));
            if (i < path.size() - 1)
                sb.append(" -> ");
        }
        return sb.toString();
    }

    static String shortUnit(Unit u) {
        String s = u.toString().replace('\n', ' ');
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    /**
     * Get the start and end line numbers of a Unit, using SourceLnPosTag first and
     * LineNumberTag as fallback.
     */
    public static int[] unitLineRange(Unit u) {
        SourceLnPosTag slp = (SourceLnPosTag) u.getTag("SourceLnPosTag");
        if (slp != null)
            return new int[] { slp.startLn(), slp.endLn() };
        LineNumberTag ln = (LineNumberTag) u.getTag("LineNumberTag");
        if (ln != null)
            return new int[] { ln.getLineNumber(), ln.getLineNumber() };
        return new int[] { -1, -1 };
    }

    private static boolean intersects(List<ChangeRange> rs, int a, int b) {
        if (a < 0 || b < 0)
            return false;
        for (ChangeRange r : rs) {
            if (Math.max(a, r.startLine) <= Math.min(b, r.endLine))
                return true;
        }
        return false;
    }

    public static Set<String> createCFG(List<Unit> affected, Body body, UnitGraph cfg) {
        Set<String> cfgEdges = new LinkedHashSet<>();
        for (Unit u : affected.isEmpty() ? body.getUnits() : affected) {
            for (Unit succ : cfg.getSuccsOf(u)) {
                cfgEdges.add(edgeStr(u, succ));
            }
        }

        return cfgEdges;
    }

    public static Set<String> createDFG(List<Unit> affected, Body body, UnitGraph cfg) {
        SimpleLocalDefs defs = new SimpleLocalDefs(cfg);
        SimpleLocalUses uses = new SimpleLocalUses(cfg, defs);
        Set<String> dfgEdges = new LinkedHashSet<>();
        for (Unit u : affected.isEmpty() ? body.getUnits() : affected) {
            if (u instanceof DefinitionStmt) {
                List<UnitValueBoxPair> pairs = uses.getUsesOf(u);
                for (UnitValueBoxPair p : pairs) {
                    dfgEdges.add(edgeStr(u, p.getUnit()));
                }
            }
        }
        return dfgEdges;
    }

    private static String edgeStr(Unit from, Unit to) {
        int[] l1 = unitLineRange(from);
        int[] l2 = unitLineRange(to);
        return String.format("[%d-%d] %s  ->  [%d-%d] %s",
                l1[0], l1[1], from, l2[0], l2[1], to);
    }

    /** Write the IR.jimple file */
    public static void saveIR(Body body, Path outputDir) throws IOException {
        Path irFile = outputDir.resolve("IR.jimple");
        try (BufferedWriter writer = Files.newBufferedWriter(irFile)) {
            writer.write(body.toString());
        }
    }
}
