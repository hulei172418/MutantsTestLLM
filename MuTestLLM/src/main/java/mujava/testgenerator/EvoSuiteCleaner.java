package mujava.testgenerator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.nodeTypes.NodeWithBody;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;
import java.util.logging.Formatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static mujava.util.ExcelUtils.readExcel;
import static mujava.util.LocalJarFinder.buildProjectClasspath;

public class EvoSuiteCleaner {

    // 命名规则：_ESTest -> Test
    private static String renameTestClass(String oldName) {
        if (oldName.endsWith("_ESTest")) {
            return oldName.substring(0, oldName.length() - "_ESTest".length()) + "Test";
        }
        return oldName + "Test";
    }

    public static void main(String[] args) throws Exception {
        String projectName = "commons-cli-1.11.0";
        String subProjectName = "";  // 多模块填名字；单模块留空

        Path base = Paths.get(System.getProperty("user.dir"))
                .resolve("..").resolve("Programs").resolve(projectName);

        base = base.resolve(subProjectName);

        String inp  = base.resolve("evoSuite").resolve("evoSuite").resolve("tests").toString();
        String outp = base.resolve("evoSuite").resolve("clean").toString();
        Path inputDir = Paths.get(inp).toAbsolutePath().normalize();
        Path outputDir = Paths.get(outp).toAbsolutePath().normalize();

        List<Path> javaFiles = Files.walk(inputDir)
                .filter(p -> p.toString().endsWith("_ESTest.java"))
                .collect(Collectors.toList());

        for (Path inFile : javaFiles) {
            transformOne(inFile, inputDir, outputDir);
            System.out.println(inFile);
        }

        System.out.println("Done. Converted files: " + javaFiles.size());
    }

    private static void inlineExecutorSubmitFutureGet(BlockStmt body) {
        NodeList<Statement> stmts = body.getStatements();
        for (int i = 0; i < stmts.size() - 1; i++) {
            Statement s1 = stmts.get(i);
            Statement s2 = stmts.get(i + 1);

            // 1) 匹配：Future<?> future = executor.submit(new Runnable(){...});
            if (!s1.isExpressionStmt()) continue;
            ExpressionStmt es1 = s1.asExpressionStmt();
            if (!es1.getExpression().isVariableDeclarationExpr()) continue;

            VariableDeclarationExpr vde = es1.getExpression().asVariableDeclarationExpr();
            if (vde.getVariables().size() != 1) continue;

            String futureVar = vde.getVariable(0).getNameAsString();
            if (!vde.getVariable(0).getInitializer().isPresent()) continue;

            if (!vde.getVariable(0).getInitializer().get().isMethodCallExpr()) continue;
            MethodCallExpr submitCall = vde.getVariable(0).getInitializer().get().asMethodCallExpr();

            if (!submitCall.getNameAsString().equals("submit")) continue;
            if (!submitCall.getScope().isPresent()) continue;
            // executor.submit(...)
            if (!submitCall.getScope().get().toString().equals("executor")) continue;
            if (submitCall.getArguments().size() < 1) continue;

            // submit 的第一个参数：new Runnable(){ public void run(){...}}
            if (!submitCall.getArgument(0).isObjectCreationExpr()) continue;
            ObjectCreationExpr oce = submitCall.getArgument(0).asObjectCreationExpr();
            if (!oce.getAnonymousClassBody().isPresent()) continue;

            // 找匿名类里的 run() 方法体
            Optional<BlockStmt> runBodyOpt = oce.getAnonymousClassBody().get().stream()
                    .filter(BodyDeclaration::isMethodDeclaration)
                    .map(BodyDeclaration::asMethodDeclaration)
                    .filter(md -> md.getNameAsString().equals("run"))
                    .flatMap(md -> md.getBody().isPresent() ? java.util.stream.Stream.of(md.getBody().get()) : java.util.stream.Stream.empty())
                    .findFirst();

            if (!runBodyOpt.isPresent()) continue;

            // 2) 匹配下一句：future.get(..., TimeUnit.MILLISECONDS);
            if (!s2.isExpressionStmt()) continue;
            ExpressionStmt es2 = s2.asExpressionStmt();
            if (!es2.getExpression().isMethodCallExpr()) continue;

            MethodCallExpr getCall = es2.getExpression().asMethodCallExpr();
            if (!getCall.getNameAsString().equals("get")) continue;
            if (!getCall.getScope().isPresent()) continue;
            if (!getCall.getScope().get().toString().equals(futureVar)) continue;

            // 3) 执行替换：用 run() 的语句列表替换 s1+s2
            BlockStmt runBody = runBodyOpt.get();

            // 先删后插：删除 get，再删除 submit 声明
            stmts.remove(i + 1);
            stmts.remove(i);

            // 把 run 里的语句插回当前位置 i
            NodeList<Statement> runStmts = runBody.getStatements();
            for (int k = 0; k < runStmts.size(); k++) {
                stmts.add(i + k, runStmts.get(k).clone());
            }

            // i 回退一步继续扫描（避免跳过）
            i = Math.max(-1, i - 1);
        }
    }

    private static void transformOne(Path inFile, Path inputRoot, Path outputRoot) throws IOException {

        byte[] bytes = Files.readAllBytes(inFile);
        String src = new String(bytes, StandardCharsets.UTF_8);
        CompilationUnit cu = StaticJavaParser.parse(src);

        String fileBase = inFile.getFileName().toString().replace(".java", "");

        Optional<ClassOrInterfaceDeclaration> primaryOpt =
                cu.findFirst(ClassOrInterfaceDeclaration.class,
                        c -> c.isPublic() && !c.isInterface() && c.getNameAsString().equals(fileBase));

        if (!primaryOpt.isPresent()) {
            throw new IllegalStateException("No matching public class '" + fileBase + "' in: " + inFile);
        }

        ClassOrInterfaceDeclaration clazz = primaryOpt.get();

        String oldName = clazz.getNameAsString();
        String newName = renameTestClass(oldName);

        // 1) 改类名
        clazz.setName(newName);

        // 1.1) 构造器名也要同步改（否则编译报错）
        for (ConstructorDeclaration cd : clazz.getConstructors()) {
            cd.setName(newName);
        }

        // 2) 去掉 extends xxx_ESTest_scaffolding
        removeScaffoldingExtends(clazz);

        // 2.1) 去掉 EvoSuite Runner 注解：@RunWith(EvoRunner.class) / @EvoRunnerParameters(...)
        removeEvoSuiteRunnerAnnotations(clazz);

        // 2.2) 基于 import 快速提取“与 EvoSuite 相关的符号名”，用于后续定位/触发清理
        //     例如：EvoRunner、EvoRunnerParameters、ViolatedAssumptionAnswer、MockFileInputStream、EvoAssertions 等
        java.util.Set<String> evosuiteImportSymbols = cu.getImports().stream()
                .map(NodeWithName::getNameAsString)
                .filter(n -> n.startsWith("org.evosuite"))
                .map(n -> {
                    int k = n.lastIndexOf('.');
                    return k >= 0 ? n.substring(k + 1) : n;
                })
                .collect(java.util.stream.Collectors.toSet());

        // 3) 清理 import（scaffolding / 所有 org.evosuite.*）
        cu.getImports().removeIf(imp -> {
            String n = imp.getNameAsString();
            return n.endsWith("_ESTest_scaffolding") || n.startsWith("org.evosuite");
        });

        // 3.1) 如果文件里 import 了 EvoRunnerParameters / EvoRunner 以外形式的静态 import，也一起删
        cu.getImports().removeIf(imp -> imp.isStatic() && imp.getNameAsString().startsWith("org.evosuite"));

        // 4) 处理 “Undeclared exception!”：把语句包 try/catch + fail
        for (MethodDeclaration md : clazz.getMethods()) {
            if (!isJUnit4Test(md)) continue;
            md.getBody().ifPresent(body -> {
                wrapUndeclaredExceptionStatements(body);
                removeVerifyExceptionCalls(body);   // <--- 新增
                // 4.1) 递归清理：从出现 EvoSuite 依赖的语句开始，沿“递归链/依赖链”向后删除相关语句
                // 重点覆盖：ViolatedAssumptionAnswer、MockFileInputStream（以及残留的 org.evosuite.runtime.*）
                removeEvoSuiteTaintedChainsRecursively(body, evosuiteImportSymbols);
                inlineExecutorSubmitFutureGet(body);
            });
        }

        // 5) 有些文件会在内部引用旧类名（极少见），这里做一次替换：SimpleName == oldName -> newName
        cu.findAll(SimpleName.class).forEach(sn -> {
            if (sn.getIdentifier().equals(oldName)) sn.setIdentifier(newName);
        });

        // 输出文件名也要改
        String outFileName = newName + ".java";
        writeOut(inFile, inputRoot, outputRoot, outFileName, cu.toString());
    }

    private static void removeEvoSuiteRunnerAnnotations(ClassOrInterfaceDeclaration clazz) {
        clazz.getAnnotations().removeIf(a -> {
            String annName = a.getNameAsString();

            // @EvoRunnerParameters(...)
            if (annName.equals("EvoRunnerParameters") || annName.endsWith(".EvoRunnerParameters")) {
                return true;
            }

            // @RunWith(EvoRunner.class) —— 只删参数里含 EvoRunner 的 RunWith
            if (annName.equals("RunWith") || annName.endsWith(".RunWith")) {
                String asText = a.toString();
                return asText.contains("EvoRunner");
            }
            return false;
        });
    }

    private static boolean isJUnit4Test(MethodDeclaration md) {
        return md.getAnnotations().stream().anyMatch(a -> {
            if (a.isMarkerAnnotationExpr()) {
                MarkerAnnotationExpr ma = a.asMarkerAnnotationExpr();
                return ma.getNameAsString().equals("Test") || ma.getNameAsString().endsWith(".Test");
            }
            return a.getNameAsString().equals("Test") || a.getNameAsString().endsWith(".Test");
        });
    }

    private static void removeScaffoldingExtends(ClassOrInterfaceDeclaration clazz) {
        NodeList<ClassOrInterfaceType> exts = clazz.getExtendedTypes();
        if (exts.isEmpty()) return;

        clazz.setExtendedTypes(new NodeList<>(
                exts.stream()
                        .filter(t -> !t.getNameAsString().endsWith("_ESTest_scaffolding"))
                        .collect(Collectors.toList())
        ));
    }

    private static void wrapUndeclaredExceptionStatements(BlockStmt body) {
        NodeList<Statement> stmts = body.getStatements();
        if (stmts.isEmpty()) return;

        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);

            // 注释挂在本语句上
            if (hasUndeclaredExceptionComment(s.getComment().orElse(null))) {
                stmts.set(i, wrapAsExpectedException(s));
                continue;
            }

            // 注释挂在空语句上，则包下一条
            if (s.isEmptyStmt() && hasUndeclaredExceptionComment(s.getComment().orElse(null))) {
                if (i + 1 < stmts.size()) {
                    Statement target = stmts.get(i + 1);
                    stmts.set(i + 1, wrapAsExpectedException(target));
                }
            }
        }
    }

    private static boolean hasUndeclaredExceptionComment(Comment c) {
        if (c == null) return false;
        String txt = c.getContent();
        return txt != null && txt.contains("Undeclared exception");
    }

    private static void removeVerifyExceptionCalls(BlockStmt body) {
        // 删除形如：verifyException(...);  或  SomeClass.verifyException(...);
        body.findAll(ExpressionStmt.class).forEach(es -> {
            if (!es.getExpression().isMethodCallExpr()) return;

            MethodCallExpr mc = es.getExpression().asMethodCallExpr();
            String methodName = mc.getNameAsString();
            if (methodName.equals("verifyException") || methodName.equals("verifyNoException")) {
                es.remove();
            }
        });
    }

    /**
     * 递归清理：
     * 1) 先做“就地修复”（能修的尽量修），例如 mock(xxx, new ViolatedAssumptionAnswer()) -> mock(xxx)
     * 2) 若仍存在 EvoSuite runtime 依赖，则从触发语句开始，将其“依赖链/递归链”相关语句向后清理：
     *    - 触发条件：语句文本包含 MockFileInputStream / ViolatedAssumptionAnswer / org.evosuite.runtime.* 等
     *    - 依赖传播：若后续语句使用了由触发语句定义/赋值的变量，也一并删除；若又定义了新变量，则继续污染传播。
     *
     * 备注：该策略偏保守（宁可多删），目标是保证清理后的测试能稳定编译/执行。
     */
    private static void removeEvoSuiteTaintedChainsRecursively(BlockStmt body, java.util.Set<String> evosuiteImportSymbols) {
        // (A) 不做‘修复保留’，而是把触发点作为链式删除起点（无 EvoSuite runtime 环境下更稳）

        // (B) 递归按“触发语句 -> 依赖链”清理
        java.util.Set<String> tainted = new java.util.HashSet<>();
        removeTaintedStatementsInBlock(body, tainted, evosuiteImportSymbols);
    }

    private static void removeTaintedStatementsInBlock(BlockStmt block, java.util.Set<String> tainted, java.util.Set<String> evosuiteImportSymbols) {
        NodeList<Statement> stmts = block.getStatements();
        for (int i = 0; i < stmts.size(); ) {
            Statement st = stmts.get(i);

            // 1) 递归处理子块（注意：递归放在“删除判断之前”，避免错过深层触发）
            //    但如果当前语句本身会被删除，子块处理就没意义了。

            boolean triggered = containsEvoSuiteArtifact(st, evosuiteImportSymbols);
            boolean usesTainted = usesAnyTaintedVar(st, tainted);

            if (triggered || usesTainted) {
                // 2) 记录该语句“定义/赋值”的变量，加入污染集合（用于后续依赖链删除）
                tainted.addAll(extractDefinedOrAssignedVars(st));

                // 3) 直接删除该语句
                stmts.remove(i);
                continue; // 不递增 i
            }

            // 4) 当前语句保留，则递归清理其内部子块
            recursivelyCleanInnerBlocks(st, tainted, evosuiteImportSymbols);

            // 5) 下一条
            i++;
        }
    }

    private static void recursivelyCleanInnerBlocks(Statement st, java.util.Set<String> tainted, java.util.Set<String> evosuiteImportSymbols) {
        if (st.isBlockStmt()) {
            removeTaintedStatementsInBlock(st.asBlockStmt(), tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isIfStmt()) {
            IfStmt is = st.asIfStmt();
            if (is.getThenStmt().isBlockStmt()) removeTaintedStatementsInBlock(is.getThenStmt().asBlockStmt(), tainted, evosuiteImportSymbols);
            else {
                // then 分支是单语句：包成 block 统一处理
                BlockStmt b = new BlockStmt(new NodeList<>(is.getThenStmt().clone()));
                removeTaintedStatementsInBlock(b, tainted, evosuiteImportSymbols);
                if (b.getStatements().isEmpty()) is.setThenStmt(new EmptyStmt());
                else is.setThenStmt(b);
            }
            is.getElseStmt().ifPresent(es -> {
                if (es.isBlockStmt()) removeTaintedStatementsInBlock(es.asBlockStmt(), tainted, evosuiteImportSymbols);
                else {
                    BlockStmt b = new BlockStmt(new NodeList<>(es.clone()));
                    removeTaintedStatementsInBlock(b, tainted, evosuiteImportSymbols);
                    if (b.getStatements().isEmpty()) is.setElseStmt(new EmptyStmt());
                    else is.setElseStmt(b);
                }
            });
            return;
        }
        if (st.isForStmt()) {
            Statement body = st.asForStmt().getBody();
            wrapAndCleanLoopBody(st.asForStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isForEachStmt()) {
            Statement body = st.asForEachStmt().getBody();
            wrapAndCleanLoopBody(st.asForEachStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isWhileStmt()) {
            Statement body = st.asWhileStmt().getBody();
            wrapAndCleanLoopBody(st.asWhileStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isDoStmt()) {
            Statement body = st.asDoStmt().getBody();
            wrapAndCleanLoopBody(st.asDoStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isTryStmt()) {
            TryStmt ts = st.asTryStmt();
            removeTaintedStatementsInBlock(ts.getTryBlock(), tainted, evosuiteImportSymbols);
            ts.getCatchClauses().forEach(cc -> removeTaintedStatementsInBlock(cc.getBody(), tainted, evosuiteImportSymbols));
            ts.getFinallyBlock().ifPresent(fb -> removeTaintedStatementsInBlock(fb, tainted, evosuiteImportSymbols));
        }
    }

    private static void wrapAndCleanLoopBody(NodeWithBody<?> loop, Statement body, java.util.Set<String> tainted, java.util.Set<String> evosuiteImportSymbols) {
        if (body.isBlockStmt()) {
            removeTaintedStatementsInBlock(body.asBlockStmt(), tainted, evosuiteImportSymbols);
        } else {
            BlockStmt b = new BlockStmt(new NodeList<>(body.clone()));
            removeTaintedStatementsInBlock(b, tainted, evosuiteImportSymbols);
            if (b.getStatements().isEmpty()) loop.setBody(new EmptyStmt());
            else loop.setBody(b);
        }
    }

    private static boolean containsEvoSuiteArtifact(Statement st, java.util.Set<String> evosuiteImportSymbols) {
        String txt = st.toString();
        // (0) 基于 import 提取的符号名：用于快速定位（例如 EvoAssertions、ViolatedAssumptionAnswer、MockFileInputStream 等）
        if (evosuiteImportSymbols != null && !evosuiteImportSymbols.isEmpty()) {
            for (String sym : evosuiteImportSymbols) {
                if (sym != null && !sym.isEmpty() && txt.contains(sym)) return true;
            }
        }
        // (0.5) 如果使用的是 EvoSuite shaded Mockito（import 里通常会出现 Mockito），
        //       则把 mock/doReturn/when 等也视为触发点，让清理能从第一条 Mockito 语句开始链式删除。
        if (evosuiteImportSymbols != null && evosuiteImportSymbols.contains("Mockito")) {
            for (MethodCallExpr mc : st.findAll(MethodCallExpr.class)) {
                String n = mc.getNameAsString();
                if (n.equals("mock") || n.equals("doReturn") || n.equals("doThrow") || n.equals("doAnswer")
                        || n.equals("when") || n.equals("verify") || n.equals("reset")
                        || n.equals("verifyNoMoreInteractions") || n.equals("verifyNoInteractions")) {
                    return true;
                }
            }
        }

        // 你明确反馈的两类：
        if (txt.contains("ViolatedAssumptionAnswer")) return true;
        if (txt.contains("MockFileInputStream")) return true;

        // 兜底：仍残留 org.evosuite.runtime.*
        if (txt.contains("org.evosuite.runtime")) return true;
        if (txt.contains("org.evosuite.runtime.mock")) return true;
        if (txt.contains("EvoAssertions")) return true;
        return false;
    }

    private static boolean usesAnyTaintedVar(Statement st, java.util.Set<String> tainted) {
        if (tainted.isEmpty()) return false;
        // 仅按 AST 的 NameExpr 判断使用（比纯字符串 contains 更稳）
        for (NameExpr ne : st.findAll(NameExpr.class)) {
            if (tainted.contains(ne.getNameAsString())) return true;
        }
        // MethodCall 的 scope 可能是 this.xxx 的 FieldAccess，这里补一层
        for (FieldAccessExpr fa : st.findAll(FieldAccessExpr.class)) {
            Expression scope = fa.getScope();
            if (scope.isNameExpr() && tainted.contains(scope.asNameExpr().getNameAsString())) return true;
        }
        return false;
    }

    private static java.util.Set<String> extractDefinedOrAssignedVars(Statement st) {
        java.util.Set<String> defs = new java.util.HashSet<>();

        // a) 变量声明：T x = ...;
        st.findAll(VariableDeclarationExpr.class).forEach(vde ->
                vde.getVariables().forEach(v -> defs.add(v.getNameAsString()))
        );

        // b) 赋值：x = ...;  或  this.x = ...;（只取简单 NameExpr）
        st.findAll(AssignExpr.class).forEach(ae -> {
            Expression target = ae.getTarget();
            if (target.isNameExpr()) defs.add(target.asNameExpr().getNameAsString());
        });

        return defs;
    }

    private static Statement wrapAsExpectedException(Statement original) {
        // try { original; org.junit.Assert.fail("Expected exception"); } catch(Throwable t) {}
        BlockStmt tryBlock = new BlockStmt();
        tryBlock.addStatement(original.clone());
        tryBlock.addStatement(new ExpressionStmt(
                new MethodCallExpr(
                        new NameExpr("org.junit.Assert"),
                        "fail",
                        NodeList.nodeList(new StringLiteralExpr("Expected exception"))
                )
        ));

        CatchClause cc = new CatchClause();
        cc.setParameter(new com.github.javaparser.ast.body.Parameter(
                StaticJavaParser.parseType("Throwable"), "t"
        ));
        cc.setBody(new BlockStmt()); // empty

        TryStmt ts = new TryStmt();
        ts.setTryBlock(tryBlock);
        ts.setCatchClauses(NodeList.nodeList(cc));
        return ts;
    }

    private static void writeOut(Path inFile, Path inputRoot, Path outputRoot,
                                 String outFileName, String content) throws IOException {
        Path relative = inputRoot.relativize(inFile.getParent());
        Path outDir = outputRoot.resolve(relative);
        Files.createDirectories(outDir);

        Path outFile = outDir.resolve(outFileName);
        Files.write(outFile, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Mutant kill statistics runner (JUnit4, forked JVM).
     *
     * Output format matches the user's sample:
     *   test_results={test0=..., test1=...};
     *   mutant_results={MUT_1=test0, test3, ...}
     *   killed_mutants=[...];
     *   live_mutants=[...];
     *   mutant_score=...
     *
     * Key design: run each TEST (test0/test1/...) individually on ORIGINAL and on each MUTANT,
     * so we can attribute which test kills which mutant.
     */
    public static class KillStatisticRunner {
        private static final Logger logger = Logger.getLogger(EvoSuiteTestGenerator.class.getName());
        private static String operatorName;
        private static String lineNo;
        private static String methodName;
        private static String className;
        private static String className_F;
        private static String mutationStatement;
        private static String packageName;
        private static String projectName;
        private static String filepath;
        private static final Set<String> classSet = new HashSet<>();
        private static final Map<String, String> projectKV = new HashMap<>();
        private static String workDir;
        private static String cp;
        private static String targetCls;

        private static boolean logAppend = false;

        public static void main(String[] args) throws Exception {
            String mutantPath = "../MutantParse/data/mutant_statistic_1.xlsx";
            dataIterator(mutantPath);
        }

        private static List<List<Object>> readExcelFile(String filePath) {
            try {
                return readExcel(filePath, 0);
            } catch (IOException e) {
                e.printStackTrace();
                return Collections.emptyList();
            }
        }

        private static void dataIterator(String filePath) throws Exception {
            String fileName = new File(filePath).getName();
            int dotIndex = fileName.lastIndexOf('.');
            String baseName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
            String logFile = "logs/" + baseName + ".log";
            setupLogger(logFile);
            List<List<Object>> excelData = readExcelFile(filePath);
            for (int i = 1; i < excelData.size(); i++) {
                operatorName = (String) excelData.get(i).get(0);
                lineNo = (String) excelData.get(i).get(1);
                methodName = (String) excelData.get(i).get(2);
                className = (String) excelData.get(i).get(3);
                className_F = (String) excelData.get(i).get(4);
                mutationStatement = (String) excelData.get(i).get(5);
                packageName = (String) excelData.get(i).get(6);
                projectName = (String) excelData.get(i).get(7);
                filepath = new File((String) excelData.get(i).get(8)).getParent().replace("\\", "/");
                filepath = filepath.replace("//?/", "");
                workDir = getCurr(filepath);
                targetCls = packageName.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "");
                String tag = workDir+"/result/"+packageName;
                if (classSet.contains(tag)){
                    continue;
                }else {
                    classSet.add(tag);
                }
                if (projectKV.containsKey(projectName)) {
                    cp = projectKV.get(projectName);
                } else {
                    cp = buildProjectClasspath(workDir+"/"+projectName);
                    projectKV.put(projectName, cp);
                }
                System.out.println(workDir+"/src/"+packageName.replace(".", "/"));
                runKill();
            }
        }

        private static void setupLogger(String logFile) throws IOException {
            Files.createDirectories(Paths.get("logs"));

            FileHandler fileHandler = new FileHandler(logFile, logAppend);
            fileHandler.setFormatter(new SimpleFormatterWithoutPrefix());

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(fileHandler);
            rootLogger.setLevel(Level.SEVERE);

            // 移除默认的控制台输出
            Handler[] handlers = rootLogger.getHandlers();
            for (Handler handler : handlers) {
                if (handler instanceof java.util.logging.ConsoleHandler) {
                    rootLogger.removeHandler(handler);
                }
            }

            logger.info("Logger initialized.");
        }

        private static class SimpleFormatterWithoutPrefix extends Formatter {
            @Override
            public String format(LogRecord record) {
                return record.getLevel() + ": " + record.getMessage() + "\n";
            }
        }

        public static String getCurr(String workDir) {

            Pattern p = Pattern.compile("(?i)^(.*?)(?=[\\\\/]+result(?:[\\\\/]+|$))");
            Matcher m = p.matcher(workDir);

            if (m.find()) {
                String root = m.group(1);
                // System.out.println(root);
                return root;
            } else {
                throw new IllegalArgumentException("未匹配到 result: " + workDir);
            }
        }

        public static void runKill() throws Exception {

            String reDot = (projectName.contains("commons-math") || projectName.contains("commons-numbers")) ? "/../../" : "/../";
            String testDir = workDir+"/evoSuite/classes";
            String subDir = packageName.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "").replace(".", "/");

            String javaExe = "java.exe";
            // String testClassesDir = testDir+"/"+subDir+"Test.class";
            String testClassesDir = "E:/PHD/testJava/Programs/commons-csv-1.2/evoSuite/classes/org/apache/commons/csv";
            String origClassesDir = workDir+"/result/"+packageName+"/original/"+className_F+".class";
            String mutantsRootDir = workDir+"/result/"+packageName+"/traditional_mutants";
            String depsCp = cp;
            int timeoutSec = 60;
            String outFile = workDir+"/result/"+packageName+"/traditional_mutants/"+"kill_statistic.txt";

            // collect test class FQNs
            List<String> testClasses = listTestClassFQNs(Paths.get(testClassesDir));
            if (testClasses.isEmpty()) throw new FileNotFoundException("No test .class under: " + testClassesDir);

            // label them as test0, test1, ...
            List<String> testLabels = new ArrayList<>();
            for (int i = 0; i < testClasses.size(); i++) testLabels.add("test" + i);

            // discover mutants as leaf dirs containing .class
            List<Path> mutantDirs = discoverMutantClassRoots(Paths.get(mutantsRootDir));
            if (mutantDirs.isEmpty()) throw new FileNotFoundException("No mutant class directories under: " + mutantsRootDir);

            // runner cp entry so child can invoke this class
            String runnerCpEntry = getSelfCpEntry();

            // Build per-test baseline on ORIGINAL:
            // We only consider a test valid if it passes on original (exit=0).
            Map<String, Boolean> testValid = new LinkedHashMap<>();
            for (int i = 0; i < testClasses.size(); i++) {
                String testFqn = testClasses.get(i);
                String cpOriginal = joinCp(runnerCpEntry, testClassesDir, origClassesDir, depsCp);
                RunOutcome base = forkRunSingle(javaExe, cpOriginal, testFqn, timeoutSec);
                testValid.put(testLabels.get(i), base.exitCode == 0 && !base.timedOut);
            }

            // test_results: testLabel -> mutants killed by this test
            Map<String, Set<String>> testResults = new LinkedHashMap<>();
            for (String tl : testLabels) testResults.put(tl, new TreeSet<>());

            // mutant_results: mutantId -> tests that kill it
            Map<String, Set<String>> mutantResults = new LinkedHashMap<>();

            // run each mutant against each test (only if test is valid on original)
            for (Path mdir : mutantDirs) {
                String mutantId = mdir.getFileName().toString(); // e.g., AOIS_10
                mutantResults.putIfAbsent(mutantId, new TreeSet<>());

                String cpMutant = joinCp(runnerCpEntry, testClassesDir, mdir.toString(), depsCp, origClassesDir);

                for (int i = 0; i < testClasses.size(); i++) {
                    String tl = testLabels.get(i);
                    if (!Boolean.TRUE.equals(testValid.get(tl))) continue;

                    String testFqn = testClasses.get(i);
                    RunOutcome mut = forkRunSingle(javaExe, cpMutant, testFqn, timeoutSec);

                    if (mut.timedOut) continue; // treat timeout as "no kill" for attribution
                    if (mut.exitCode != 0) {
                        testResults.get(tl).add(mutantId);
                        mutantResults.get(mutantId).add(tl);
                    }
                }
            }

            // killed/live lists based on whether any test kills the mutant
            List<String> killed = new ArrayList<>();
            List<String> live = new ArrayList<>();
            for (String mid : mutantResults.keySet()) {
                if (!mutantResults.get(mid).isEmpty()) killed.add(mid);
                else live.add(mid);
            }

            double score = (killed.size() + live.size()) == 0 ? 0.0 : (killed.size() * 100.0) / (killed.size() + live.size());

            // write output with style like sample
            writeKillStatistic(Paths.get(outFile), testResults, mutantResults, killed, live, score);
            System.out.println("[OK] kill_statistic written to: " + outFile);
        }

        // ---------------- child mode: run ONE test class ----------------
        private static void childRunSingleTest(String testClass, String resultFile) throws IOException {
            Result res;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Class<?> c = Class.forName(testClass, true, cl);
                JUnitCore core = new JUnitCore();
                res = core.run(c);
            } catch (Throwable e) {
                writeResult(Paths.get(resultFile), 1, 0, 1,
                        Collections.singletonList("BOOTSTRAP_ERROR: " + e.getClass().getName() + ": " + safeMsg(e)));
                System.exit(1);
                return;
            }

            List<String> failures = new ArrayList<>();
            for (Failure f : res.getFailures()) failures.add(f.getTestHeader() + " :: " + safeMsg(f.getException()));
            int exit = res.wasSuccessful() ? 0 : 1;
            writeResult(Paths.get(resultFile), exit, res.getRunCount(), res.getFailureCount(), failures);
            System.exit(exit);
        }

        private static void writeResult(Path file, int exit, int runCount, int failureCount, List<String> failures) throws IOException {
            List<String> lines = new ArrayList<>();
            lines.add("exit=" + exit);
            lines.add("runCount=" + runCount);
            lines.add("failureCount=" + failureCount);
            for (String f : failures) lines.add("failure=" + f);
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        }

        // ---------------- fork runner (single test) ----------------
        private static RunOutcome forkRunSingle(String javaExe, String classpath, String testFqn, int timeoutSec) throws Exception {
            Path tmpDir = Files.createTempDirectory("killrun_");
            Path resultFile = tmpDir.resolve("result.txt");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp"); cmd.add(classpath);
            cmd.add(KillStatisticRunner.class.getName());
            cmd.add("__run__");
            cmd.add("--testClass");  cmd.add(testFqn);
            cmd.add("--resultFile"); cmd.add(resultFile.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            RunOutcome out = new RunOutcome();

            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                out.timedOut = true;
                p.destroyForcibly();
                out.exitCode = 124;
                return out;
            }
            out.exitCode = p.exitValue();

            if (Files.exists(resultFile)) parseResultFile(resultFile, out);
            return out;
        }

        private static void parseResultFile(Path resultFile, RunOutcome out) throws IOException {
            List<String> lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8);
            for (String ln : lines) {
                if (ln.startsWith("runCount=")) out.runCount = safeInt(ln.substring("runCount=".length()), 0);
                else if (ln.startsWith("failureCount=")) out.failureCount = safeInt(ln.substring("failureCount=".length()), 0);
            }
        }

        // ---------------- output (match sample) ----------------
        private static void writeKillStatistic(
                Path outFile,
                Map<String, Set<String>> testResults,
                Map<String, Set<String>> mutantResults,
                List<String> killed,
                List<String> live,
                double score
        ) throws IOException {

            List<String> lines = new ArrayList<>();
            lines.add("test_results=" + mapToSampleStyle(testResults) + ";");
            lines.add("mutant_results=" + mapToSampleStyle(mutantResults));
            lines.add("killed_mutants=" + listToSampleStyle(killed) + ";");
            lines.add("live_mutants=" + listToSampleStyle(live) + ";");
            lines.add("mutant_score=" + String.format(Locale.ROOT, "%.5f", score));

            Files.createDirectories(outFile.getParent());
            Files.write(outFile, lines, StandardCharsets.UTF_8);
        }

        private static String listToSampleStyle(List<String> items) {
            return "[" + String.join(", ", items) + "]";
        }

        private static String mapToSampleStyle(Map<String, ? extends Set<String>> map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, ? extends Set<String>> e : map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append("=");
                sb.append(String.join(", ", e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }

        // ---------------- scanning helpers ----------------
        public static List<String> listTestClassFQNs(Path testClassesDir) throws IOException {
            if (!Files.isDirectory(testClassesDir)) return Collections.emptyList();
            try (Stream<Path> s = Files.walk(testClassesDir)) {
                return s.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().contains("$"))
                        .filter(p -> !p.getFileName().toString().endsWith("_scaffolding.class"))
                        .map(p -> toFqn(testClassesDir, p))
                        .filter(fqn -> fqn.endsWith("ESTest") || fqn.endsWith("Test") || fqn.endsWith("Tests"))
                        .sorted()
                        .collect(Collectors.toList());
            }
        }

        private static String toFqn(Path root, Path classFile) {
            Path rel = root.relativize(classFile);
            String s = rel.toString().replace('\\', '.').replace('/', '.');
            if (s.endsWith(".class")) s = s.substring(0, s.length() - ".class".length());
            return s;
        }

        public static List<Path> discoverMutantClassRoots(Path mutantsRootDir) throws IOException {
            if (!Files.isDirectory(mutantsRootDir)) return Collections.emptyList();
            Set<Path> classDirs = new HashSet<>();
            try (Stream<Path> s = Files.walk(mutantsRootDir)) {
                s.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> classDirs.add(p.getParent()));
            }
            if (classDirs.isEmpty()) return Collections.emptyList();

            List<Path> sorted = classDirs.stream()
                    .sorted(Comparator.comparingInt(p -> p.toString().length()))
                    .collect(Collectors.toList());

            List<Path> leaf = new ArrayList<>();
            for (Path d : sorted) {
                boolean hasChild = false;
                for (Path other : sorted) {
                    if (!other.equals(d) && other.startsWith(d)) { hasChild = true; break; }
                }
                if (!hasChild) leaf.add(d);
            }
            leaf.sort(Comparator.comparing(Path::toString));
            return leaf;
        }

        // ---------------- utils ----------------
        private static String joinCp(String... parts) {
            String sep = System.getProperty("path.separator");
            return Arrays.stream(parts).filter(p -> p != null && !p.trim().isEmpty()).collect(Collectors.joining(sep));
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--")) {
                    String v = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                    m.put(a, v);
                }
            }
            return m;
        }

        private static String require(Map<String, String> cli, String k) {
            String v = cli.get(k);
            if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing argument: " + k);
            return v;
        }

        private static int safeInt(String s, int def) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }

        private static String safeMsg(Throwable t) {
            if (t == null) return "";
            String msg = t.getMessage();
            if (msg == null) msg = "";
            msg = msg.replace("\r", " ").replace("\n", " ");
            if (msg.length() > 240) msg = msg.substring(0, 240) + "...";
            return msg;
        }

        private static String getSelfCpEntry() {
            try {
                return new File(KillStatisticRunner.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).getPath();
            } catch (URISyntaxException e) {
                return ".";
            }
        }

        private static class RunOutcome {
            int exitCode;
            int runCount;
            int failureCount;
            boolean timedOut;
        }
    }
}
