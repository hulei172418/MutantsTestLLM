package org.rip;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.*;
import soot.tagkit.LineNumberTag;

import java.util.*;
import java.util.stream.Collectors;

import org.utils.MapUtils;

public class RipExtractor {

    // ====== Difference summary (optional, useful for training/comparison) ======
    @SuppressWarnings("unchecked")
    public static Map<String, Object> diffModules(Map<String, Object> A, Map<String, Object> B) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> cfgA = (Map<String, Object>) A.get("CFG");
        Map<String, Object> cfgB = (Map<String, Object>) B.get("CFG");
        Map<String, Object> dfgA = (Map<String, Object>) A.get("DFG");
        Map<String, Object> dfgB = (Map<String, Object>) B.get("DFG");

        out.put("CFG_diff", MapUtils.of(
                "path_predicates_only_in_origin",
                minus((List<String>) cfgA.get("path_predicates"), (List<String>) cfgB.get("path_predicates")),
                "path_predicates_only_in_mutated",
                minus((List<String>) cfgB.get("path_predicates"), (List<String>) cfgA.get("path_predicates")),
                "control_deps_changed", !new HashSet<>((List<String>) cfgA.get("control_deps_from"))
                        .equals(new HashSet<>((List<String>) cfgB.get("control_deps_from")))));
        out.put("DFG_diff", MapUtils.of(
                "uses_toward_output_changed", ((List<?>) ((Map<?, ?>) dfgA).get("uses_toward_output"))
                        .size() != ((List<?>) ((Map<?, ?>) dfgB).get("uses_toward_output")).size()));
        return out;
    }

    // ====== Utilities ======
    public static String autoClassPath(String classesDir) {
        // You may include JRE/jmods/3rd-party dependencies too; here we only return
        // classesDir for demonstration
        return classesDir;
    }

    public static Unit locateByText(Body body, String unitText) {
        if (unitText == null || unitText.isEmpty())
            return null;
        for (Unit u : body.getUnits())
            if (u.toString().equals(unitText))
                return u;
        for (Unit u : body.getUnits())
            if (u.toString().contains(unitText))
                return u;
        return null;
    }

    private static Block findBlockOf(BlockGraph bg, Unit u) {
        for (Block b : bg)
            for (Unit uu : b)
                if (uu == u)
                    return b;
        return bg.getHeads().get(0);
    }

    private static String lineStr(Unit u) {
        try {
            // Try to get line number info from Unit tags
            if (u.hasTag("LineNumberTag")) {
                LineNumberTag tag = (LineNumberTag) u.getTag("LineNumberTag");
                return String.valueOf(tag.getLineNumber());
            }

            // If tags are absent, try parsing from Unit.toString
            String unitStr = u.toString();
            if (unitStr.contains("line")) {
                // Simple line number extraction logic
                String[] parts = unitStr.split("line");
                if (parts.length > 1) {
                    String linePart = parts[1].trim();
                    String[] lineParts = linePart.split("\\s+");
                    if (lineParts.length > 0) {
                        try {
                            return String.valueOf(Integer.parseInt(lineParts[0]));
                        } catch (NumberFormatException e) {
                            // Ignore parsing errors
                        }
                    }
                }
            }

            return String.valueOf(-1); // Cannot retrieve line number
        } catch (Exception e) {
            // If any exception occurs, return -1
            return String.valueOf(-1);
        }
    }

    private static String blockSummary(Block b) {
        Unit head = b.getHead(), tail = b.getTail();
        return "BB[head=" + head + " (" + lineStr(head) + "), tail=" + tail + " (" + lineStr(tail) + ")]";
    }

    public static List<String> unitsToBlockSummaries(BlockGraph bg, List<Unit> units) {
        return units.stream().map(u -> blockSummary(findBlockOf(bg, u))).distinct().collect(Collectors.toList());
    }

    public static List<String> computeControlDepsText(BlockGraph bg,
            DominatorsFinder<Unit> pdom,
            Unit mut) {
        List<String> res = new ArrayList<>();
        if (mut == null)
            return res;

        for (Block b : bg) {
            Unit tail = b.getTail();
            if (!(tail instanceof IfStmt))
                continue;

            List<Block> succs = bg.getSuccsOf(b);
            if (succs.isEmpty())
                continue;

            boolean some = false, all = true;

            for (Block s : succs) {
                Unit su = s.getHead();
                // Key fix: determine whether "mut" post-dominates this successor entry
                boolean pd = pdom.getDominators(su).contains(mut);
                some |= pd;
                all &= pd;
            }

            // At least one branch must go through "mut" and one must not ⇒ mut has control
            // dependency on this if
            if (some && !all) {
                String condText = ((IfStmt) tail).getCondition().toString();
                res.add("If(" + condText + ") @" + lineStr(tail));
            }
        }
        return res;
    }

    public static List<String> computeControlDepsText1(BlockGraph bg, DominatorsFinder<Unit> pdom, Unit mut) {
        List<String> res = new ArrayList<>();
        for (Block b : bg) {
            Unit tail = b.getTail();
            if (tail instanceof IfStmt) {
                List<Block> succs = bg.getSuccsOf(b);
                boolean some = false, all = true;
                for (Block s : succs) {
                    Unit su = s.getHead();
                    boolean pd = pdom.getDominators(mut).contains(su);
                    some |= pd;
                    all &= pd;
                }
                if (some && !all)
                    res.add("If(" + ((IfStmt) tail).getCondition() + ") @" + lineStr(tail));
            }
        }
        return res;
    }

    public static boolean isSink(Unit u, List<String> specObserved) {
        if (specObserved.contains("return") && u instanceof ReturnStmt)
            return true;
        if (specObserved.contains("exception") && u instanceof ThrowStmt)
            return true;
        // "state" sink depends on your project definition (externally visible state
        // write)
        return false;
    }

    public static String sinkKind(Unit u) {
        if (u instanceof ReturnStmt)
            return "return";
        if (u instanceof ThrowStmt)
            return "exception";
        return "state";
    }

    private static List<String> minus(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.removeAll(b);
        return r;
    }
}
