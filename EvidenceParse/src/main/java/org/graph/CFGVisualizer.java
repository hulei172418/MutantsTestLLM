package org.graph;

import soot.*;
import soot.toolkits.graph.*;
import soot.jimple.*;
import soot.util.dot.DotGraph;

import java.io.*;
import java.util.*;

import org.utils.MethodSignature;

public class CFGVisualizer {
    private String suffix = "graph";
    private String className = null;
    private String methodName = null;
    private String outputDir = null;

    public CFGVisualizer(String className, String methodName, String outputDir) {
        this.className = className;
        this.methodName = methodName;
        this.outputDir = outputDir + "/" + suffix;
    }

    public void visualize() {
        SootClass sootClass = Scene.v().getSootClass(className);
        SootMethod method = null;

        for (SootMethod m : sootClass.getMethods()) {
            if (!m.isConcrete() || !MethodSignature.matchesSootLikeSignature(m, methodName))
                continue;
            method = m;
            break;
        }
        if (method == null) {
            System.err.println("Method " + methodName + " not found in class " + className);
            return;
        }

        if (method.isConcrete()) {
            Body body = method.retrieveActiveBody();
            UnitGraph cfg = new BriefUnitGraph(body);

            // Generate CFG in simple text format
            generateTextCFG(cfg);

            // Generate CFG in DOT format
            generateDotCFG(cfg);
        }
    }

    private void generateTextCFG(UnitGraph cfg) {
        if (methodName.equals("<init>")) {
            methodName = "init";
        }

        String fileName = outputDir + "/CFG.txt";
        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            // Record processed edges to avoid duplicates
            Set<String> processedEdges = new HashSet<>();

            // Add start node
            if (!cfg.getHeads().isEmpty()) {
                Unit firstUnit = cfg.getHeads().get(0);
                out.println("[start] -> [" + firstUnit + "]");
            }

            // Iterate over all basic blocks
            for (Unit unit : cfg) {
                // Get all successors of the current statement
                List<Unit> successors = cfg.getSuccsOf(unit);

                // If successors exist, output edges
                if (!successors.isEmpty()) {
                    for (Unit succ : successors) {
                        String edgeKey = unit + "->" + succ;
                        if (!processedEdges.contains(edgeKey)) {
                            // Determine edge type (conditional branches may be labeled true/false)
                            String edgeLabel = "";
                            if (unit instanceof IfStmt) {
                                // Method to get target address in newer versions of Soot
                                IfStmt ifStmt = (IfStmt) unit;
                                // Get default successor of conditional (false branch)
                                // Unit defaultTarget = cfg.getSuccsOf(ifStmt).get(0);
                                // Get jump target (true branch)
                                Stmt targetStmt = (Stmt) ifStmt.getTarget();

                                edgeLabel = (succ == targetStmt) ? " (true)" : " (false)";
                            } else if (unit instanceof GotoStmt) {
                                edgeLabel = " (goto)";
                            }

                            out.println("[" + unit + "] -> [" + succ + "]" + edgeLabel);
                            processedEdges.add(edgeKey);
                        }
                    }
                } else {
                    // Node without successors (typically return statement)
                    out.println("[" + unit + "] -> [end]");
                }
            }

            // System.out.println("Text format CFG saved to " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void generateDotCFG(UnitGraph cfg) {
        DotGraph dotGraph = new DotGraph("CFG_" + className + "_" + methodName);

        // Add nodes
        for (Unit unit : cfg) {
            dotGraph.drawNode(unit.toString());
        }

        // Add edges
        for (Unit unit : cfg) {
            for (Unit succ : cfg.getSuccsOf(unit)) {
                String edgeLabel = "";
                if (unit instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) unit;
                    Stmt targetStmt = (Stmt) ifStmt.getTarget();
                    edgeLabel = (succ == targetStmt) ? "true" : "false";
                }
                dotGraph.drawEdge(unit.toString(), succ.toString()).setLabel(edgeLabel);
            }
        }

        // Save to file
        if (methodName.equals("<init>")) {
            methodName = "init";
        }
        String fileName = outputDir + "/CFG.dot";
        dotGraph.plot(fileName);
        // System.out.println("DOT format CFG saved to " + fileName);
    }
}