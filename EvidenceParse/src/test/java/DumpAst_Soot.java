import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;

import soot.*;
import soot.options.Options;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.jimple.*;

public class DumpAst_Soot {

    public static void main(String[] args) {
        // 配置：classPath 指向你的 classes 目录；className 用二进制名
        String classesDir = "target/classes/demo/origin";                // e.g. target/classes
        String className  = "ConstantPoolEntry";
        String methodNameSubstr = "PoolEntry";                   // 为空=全部方法

        initSoot(classesDir);

        // 2) 加载
        Scene.v().loadNecessaryClasses();
        SootClass sc = Scene.v().forceResolve(className, SootClass.BODIES);
        sc.setApplicationClass();
        Scene.v().loadClassAndSupport(className);

        for (SootMethod m : sc.getMethods()) {
            if (!m.isConcrete()) continue;
            if (!methodNameSubstr.isEmpty() && !m.getName().contains(methodNameSubstr)) continue;

            Body body = m.retrieveActiveBody();
            System.out.println("\n=== " + m.getSignature() + " ===");

            // 1) 打印“AST-like”语句/表达式树（含源码行号）
            for (Unit u : body.getUnits()) {
                Stmt s = (Stmt) u;
                int line = u.getJavaSourceStartLineNumber();
                System.out.printf("[%d] %s%n", line, s.getClass().getSimpleName());
                printStmtTree(s, "  ");
            }

            // 2) 打印 CFG 边
            ExceptionalUnitGraph g = new ExceptionalUnitGraph(body);
            System.out.println("-- CFG edges --");
            for (Unit n : body.getUnits()) {
                for (Unit succ : g.getSuccsOf(n)) {
                    System.out.printf("  (%s) -> (%s)%n",
                            shortUnit(n), shortUnit(succ));
                }
            }
        }
    }

    static void initSoot(String classesDir) {
        G.reset();
        String absolutePath = Paths.get(classesDir).toAbsolutePath().toString(); // 相对→绝对
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_keep_line_number(true);
        Options.v().set_prepend_classpath(true);
        Options.v().set_process_dir(Collections.singletonList(absolutePath));
        Options.v().set_soot_classpath(absolutePath + File.pathSeparator + System.getProperty("java.class.path"));
        Options.v().setPhaseOption("jb", "use-original-names:true"); // 禁用原始名更保险
    }

    // —— 把 Stmt / Value 递归打印成“树”，相当于 Soot 的 AST 观感 ——

    static void printStmtTree(Stmt s, String ind) {
        if (s instanceof AssignStmt as) {
            System.out.println(ind + "Assign");
            printValueTree(as.getLeftOp(), ind + "  ");
            printValueTree(as.getRightOp(), ind + "  ");
        } else if (s instanceof IfStmt is) {
            System.out.println(ind + "If");
            printValueTree(is.getCondition(), ind + "  ");
        } else if (s instanceof InvokeStmt ivs) {
            System.out.println(ind + "Invoke");
            printInvokeTree(ivs.getInvokeExpr(), ind + "  ");
        } else if (s instanceof ReturnStmt rs) {
            System.out.println(ind + "Return");
            printValueTree(rs.getOp(), ind + "  ");
        } else if (s instanceof ReturnVoidStmt) {
            System.out.println(ind + "ReturnVoid");
        } else if (s instanceof GotoStmt gs) {
            System.out.println(ind + "Goto -> " + shortUnit(gs.getTarget()));
        } else if (s instanceof IdentityStmt ids) {
            System.out.println(ind + "Identity");
            printValueTree(ids.getLeftOp(), ind + "  ");
            printValueTree(ids.getRightOp(), ind + "  ");
        } else {
            System.out.println(ind + s.getClass().getSimpleName() + "  " + s);
        }
    }

    static void printValueTree(Value v, String ind) {
        if (v instanceof Local l) {
            System.out.println(ind + "Local " + l.getName() + " : " + l.getType());
        } else if (v instanceof Constant c) {
            System.out.println(ind + "Const " + c);
        } else if (v instanceof BinopExpr b) {
            System.out.println(ind + b.getClass().getSimpleName());
            printValueTree(b.getOp1(), ind + "  ");
            printValueTree(b.getOp2(), ind + "  ");
        } else if (v instanceof UnopExpr u) {
            System.out.println(ind + u.getClass().getSimpleName());
            printValueTree(u.getOp(), ind + "  ");
        } else if (v instanceof InstanceFieldRef fr) {
            System.out.println(ind + "InstanceField " + fr.getField().getSignature());
            printValueTree(fr.getBase(), ind + "  ");
        } else if (v instanceof StaticFieldRef sfr) {
            System.out.println(ind + "StaticField " + sfr.getField().getSignature());
        } else if (v instanceof ArrayRef ar) {
            System.out.println(ind + "ArrayRef");
            printValueTree(ar.getBase(), ind + "  ");
            printValueTree(ar.getIndex(), ind + "  ");
        } else if (v instanceof NewExpr ne) {
            System.out.println(ind + "New " + ne.getBaseType());
        } else if (v instanceof NewArrayExpr nae) {
            System.out.println(ind + "NewArray " + nae.getType());
            printValueTree(nae.getSize(), ind + "  ");
        } else if (v instanceof NewMultiArrayExpr nma) {
            System.out.println(ind + "NewMultiArray " + nma.getType());
            for (Value size : nma.getSizes()) printValueTree(size, ind + "  ");
        } else if (v instanceof InvokeExpr ie) {
            printInvokeTree(ie, ind);
        } else if (v instanceof CastExpr ce) {
            System.out.println(ind + "Cast -> " + ce.getType());
            printValueTree(ce.getOp(), ind + "  ");
        } else if (v instanceof InstanceOfExpr io) {
            System.out.println(ind + "InstanceOf " + io.getCheckType());
            printValueTree(io.getOp(), ind + "  ");
        } else {
            System.out.println(ind + v.getClass().getSimpleName() + "  " + v);
        }
    }

    static void printInvokeTree(InvokeExpr ie, String ind) {
        System.out.println(ind + "Invoke " + ie.getMethod().getSignature());
        if (ie instanceof InstanceInvokeExpr iie) {
            printValueTree(iie.getBase(), ind + "  ");
        }
        for (Value arg : ie.getArgs()) {
            printValueTree(arg, ind + "  ");
        }
    }

    static String shortUnit(Unit u) {
        String s = u.toString();
        s = s.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
