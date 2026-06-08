package org.graph;

import soot.*;
import soot.jimple.*;
import soot.util.dot.DotGraph;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.utils.MethodSignature;

public class ASTVisualizer {
    private static final int MAX_CODE_LENGTH = 2000;
    private String suffix = "graph";
    private String className = null;
    private String methodName = null;
    private String outputDir = null;

    public ASTVisualizer(String className, String methodName, String outputDir) {
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

            // Generate AST in plain text format
            generateTextAST(body);

            // Generate AST in DOT format
            generateDotAST(body);

            // Save IR intermediate file
            Path irPath = Paths.get(outputDir);
            try {
                Files.createDirectories(irPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                saveIR(body, irPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void generateTextAST(Body body) {
        File demo_path = new File(outputDir);
        if (!demo_path.exists()) {
            boolean created = demo_path.mkdirs();
            if (!created) {
                System.err.println("Failed to create directory: " + demo_path.getAbsolutePath());
            }
        }
        if (methodName.equals("<init>")) {
            methodName = "init";
        }

        String fileName = outputDir + "/AST.txt";
        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            out.println("=== Abstract Syntax Tree for " + className + "." + methodName + " ===");

            // Create statement index mapping
            Map<Unit, Integer> unitToIndex = new HashMap<>();
            int index = 0;

            for (Unit unit : body.getUnits()) {
                unitToIndex.put(unit, index++);
                out.println("\nStatement " + unitToIndex.get(unit) + ":");
                printStmtStructure(unit, out, 1);
            }

            // System.out.println("Text format AST saved to " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void printStmtStructure(Unit unit, PrintWriter out, int indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        String indentStr = sb.toString();

        if (unit instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) unit;
            out.println(indentStr + "IfStmt:");
            out.println(indentStr + "  Condition:");
            printValueStructure(ifStmt.getCondition(), out, indent + 2);
        } else if (unit instanceof AssignStmt) {
            AssignStmt assign = (AssignStmt) unit;
            out.println(indentStr + "AssignStmt:");
            out.println(indentStr + "  LeftOp:");
            printValueStructure(assign.getLeftOp(), out, indent + 2);
            out.println(indentStr + "  RightOp:");
            printValueStructure(assign.getRightOp(), out, indent + 2);
        }
        // System.out.println("Text format AST saved to " + fileName);
        else {
            out.println(indentStr + unit.getClass().getSimpleName() + ": " + unit);
        }
    }

    private void printValueStructure(Value value, PrintWriter out, int indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        String indentStr = sb.toString();

        if (value instanceof BinopExpr) {
            BinopExpr binop = (BinopExpr) value;
            out.println(indentStr + binop.getSymbol() + " Operation:");
            out.println(indentStr + "  Left:");
            printValueStructure(binop.getOp1(), out, indent + 2);
            out.println(indentStr + "  Right:");
            printValueStructure(binop.getOp2(), out, indent + 2);
        } else if (value instanceof Local) {
            out.println(indentStr + "Local: " + value);
        } else if (value instanceof Constant) {
            out.println(indentStr + "Constant: " + value);
        }
        // Handle other statement types...
        else {
            out.println(indentStr + value.getClass().getSimpleName() + ": " + value);
        }
    }

    public void generateDotAST1(Body body, String className, String methodName) {
        DotGraph dotGraph = new DotGraph("AST_" + className + "_" + methodName);
        dotGraph.setNodeShape("box");
        dotGraph.setGraphLabel("Abstract Syntax Tree for " + className + "." + methodName);

        Map<Object, Integer> nodeIds = new HashMap<>();
        int nextId = 0;

        // Add all Unit nodes
        for (Unit unit : body.getUnits()) {
            if (!nodeIds.containsKey(unit)) {
                nodeIds.put(unit, nextId);
                String label = nextId + ": [Unit] " + unit.getClass().getSimpleName() + "\\n"
                        + truncateCode(unit.toString());
                dotGraph.drawNode(String.valueOf(nextId)).setLabel(label);
                nextId++;
            }
        }

        // Add Value nodes and edges
        for (Unit unit : body.getUnits()) {
            int unitId = nodeIds.get(unit);

            for (ValueBox box : unit.getUseAndDefBoxes()) {
                Value value = box.getValue();

                if (!nodeIds.containsKey(value)) {
                    nodeIds.put(value, nextId);
                    String label = nextId + ": [Value] " + value.getClass().getSimpleName() + "\\n"
                            + truncateCode(value.toString());
                    dotGraph.drawNode(String.valueOf(nextId)).setLabel(label);
                    nextId++;
                }

                int valueId = nodeIds.get(value);
                dotGraph.drawEdge(String.valueOf(unitId), String.valueOf(valueId)).setLabel("PARENT_OF");
            }
        }

        // Handle method name
        if (methodName.equals("<init>")) {
            methodName = "init";
        }

        // Handle output path
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String fileName = outputDir + "AST.dot";
        dotGraph.plot(fileName);
        // System.out.println("DOT format AST saved to " + fileName);
    }

    private void generateDotAST(Body body) {
        DotGraph dotGraph = new DotGraph("AST_" + className + "_" + methodName);
        dotGraph.setNodeShape("box");
        dotGraph.setGraphLabel("Abstract Syntax Tree for " + className + "." + methodName);

        // Create statement index mapping
        Map<Unit, Integer> unitToIndex = new HashMap<>();
        int index = 0;

        // First pass: add all nodes
        for (Unit unit : body.getUnits()) {
            unitToIndex.put(unit, index);
            String nodeLabel = index + ": " + unit.getClass().getSimpleName();
            dotGraph.drawNode(String.valueOf(index)).setLabel(nodeLabel);
            index++;
        }

        // Second pass: add structural relationships
        index = 0;
        for (Unit unit : body.getUnits()) {
            if (unit instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) unit;
                addExprStructure(dotGraph, String.valueOf(index), "Condition", ifStmt.getCondition());
            } else if (unit instanceof AssignStmt) {
                AssignStmt assign = (AssignStmt) unit;
                addExprStructure(dotGraph, String.valueOf(index), "LHS", assign.getLeftOp());
                addExprStructure(dotGraph, String.valueOf(index), "RHS", assign.getRightOp());
            }
            index++;
        }

        String fileName = outputDir + "/AST.dot";
        dotGraph.plot(fileName);
        // System.out.println("DOT format AST saved to " + fileName);
    }

    private void addExprStructure(DotGraph dotGraph, String parentId, String relation, Value value) {
        String nodeId = parentId + "_" + relation;

        if (value instanceof BinopExpr) {
            BinopExpr binop = (BinopExpr) value;
            dotGraph.drawNode(nodeId).setLabel(binop.getSymbol());
            dotGraph.drawEdge(parentId, nodeId);

            addExprStructure(dotGraph, nodeId, "Left", binop.getOp1());
            addExprStructure(dotGraph, nodeId, "Right", binop.getOp2());
        } else {
            dotGraph.drawNode(nodeId).setLabel(value.toString());
            dotGraph.drawEdge(parentId, nodeId);
        }
    }

    private String truncateCode(String code) {
        return code.length() > MAX_CODE_LENGTH
                ? code.substring(0, MAX_CODE_LENGTH)
                : code;
    }

    /** Print the IR.jimple file */
    public static void saveIR(Body body, Path outputDir) throws IOException {
        Path irFile = outputDir.resolve("IR.jimple");
        try (BufferedWriter writer = Files.newBufferedWriter(irFile)) {
            writer.write(body.toString());
        }
    }
}