package mujava.cmd;

import mujava.MutationSystem;
import mujava.test.JMutationLoader8;
import mujava.test.OriginalLoader8;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

/**
 * Worker 子进程执行器：由 TestRunner8_MultiProcess 通过 ProcessBuilder 拉起，
 * 在独立 JVM 中执行 original / inspect / mutant 三种模式，并把结果写入 properties 文件。
 */
public class MutantWorker8_MultiProcess {

    public static void main(String[] args) {
        int exitCode = 0;
        String outFile = null;
        try {
            if (args.length < 1) {
                throw new IllegalArgumentException(
                        "Usage: original <projectHome> <targetClassName> <testSetName> <outFile> | " +
                                "inspect <projectHome> <targetClassName> <testSetName> <outFile> | " +
                                "mutant <projectHome> <targetClassName> <testSetName> <methodSignature> <mutantName> <outFile> | " +
                                "server <sourceModuleHome> <resultModuleHome> <targetClassName>");
            }

            String mode = args[0];

            if ("server".equalsIgnoreCase(mode)) {
                if (args.length < 4) {
                    throw new IllegalArgumentException(
                            "Server mode requires: server <sourceModuleHome> <resultModuleHome> <targetClassName>");
                }
                String sourceModuleHome = args[1];
                String resultModuleHome = args[2];
                String targetClassName = args[3];
                runServer(sourceModuleHome, resultModuleHome, targetClassName);
                return;
            }

            if (args.length < 5) {
                throw new IllegalArgumentException(
                        "Usage: original <projectHome> <targetClassName> <testSetName> <outFile> | " +
                                "inspect <projectHome> <targetClassName> <testSetName> <outFile> | " +
                                "mutant <projectHome> <targetClassName> <testSetName> <methodSignature> <mutantName> <outFile>");
            }

            String projectHome = args[1];
            String targetClassName = args[2];
            String testSetName = args[3];

            if ("original".equalsIgnoreCase(mode)) {
                outFile = args[4];
                runOriginal(projectHome, targetClassName, testSetName, outFile);
            } else if ("inspect".equalsIgnoreCase(mode)) {
                outFile = args[4];
                runInspect(projectHome, targetClassName, testSetName, outFile);
            } else if ("mutant".equalsIgnoreCase(mode)) {
                if (args.length < 7) {
                    throw new IllegalArgumentException(
                            "Mutant mode requires: mutant <projectHome> <targetClassName> <testSetName> " +
                                    "<methodSignature> <mutantName> <outFile>");
                }
                String methodSignature = args[4];
                String mutantName = args[5];
                outFile = args[6];
                runMutant(projectHome, targetClassName, testSetName, methodSignature, mutantName, outFile);
            } else {
                throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        } catch (Throwable t) {
            if (outFile != null) {
                storeError(outFile, t);
            }
            t.printStackTrace(System.err);
            exitCode = 2;
        }
        System.exit(exitCode);
    }

    private static WorkerExecutionResult runMutantInServer(String targetClassName,
                                                           String testSetName,
                                                           String methodSignature,
                                                           String mutantName,
                                                           List<String> selectedTestIds) throws Exception {
        MutationSystem.MUTANT_PATH = MutationSystem.TRADITIONAL_MUTANT_PATH + File.separator + methodSignature;
        MutationSystem.METHOD_SIGNATURE = methodSignature;

        Class<?> execClass = new JMutationLoader8(mutantName, null, null,
                MuJavaRuntimeSupport8.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        return executeJUnit(execClass, testSetName, selectedTestIds);
    }

    private static void runOriginal(String projectHome,
                                    String targetClassName,
                                    String testSetName,
                                    String outFile) throws Exception {
        MuJavaRuntimeSupport8.initializeProject(projectHome, targetClassName);
        Class<?> execClass = new OriginalLoader8(null, null,
                MuJavaRuntimeSupport8.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        WorkerExecutionResult res = executeJUnit(execClass, testSetName, null);
        storeResult(outFile, "ok", "", res.testOrder, res.results);

        // System.out.println("[DEBUG] original loadedFrom=" +
        //         execClass.getResource("/" + testSetName.replace('.', '/') + ".class"));
        // for (Method m : execClass.getDeclaredMethods()) {
        //     System.out.println("[DEBUG] method=" + m.getName());
        // }
    }


    private static void runInspect(String projectHome,
                                   String targetClassName,
                                   String testSetName,
                                   String outFile) throws Exception {
        MuJavaRuntimeSupport8.initializeProject(projectHome, targetClassName);
        Class<?> execClass = new OriginalLoader8(null, null,
                MuJavaRuntimeSupport8.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        List<String> testOrder = discoverTestIds(execClass, testSetName);
        storeResult(outFile, "ok", "inspect", testOrder, new LinkedHashMap<String, String>());
    }

    private static void runMutant(String projectHome,
                                  String targetClassName,
                                  String testSetName,
                                  String methodSignature,
                                  String mutantName,
                                  String outFile) throws Exception {
        MuJavaRuntimeSupport8.initializeProject(projectHome, targetClassName);
        runMutantInitialized(targetClassName, testSetName, methodSignature, mutantName, null, outFile);
    }

    private static void runMutantInitialized(String targetClassName,
                                             String testSetName,
                                             String methodSignature,
                                             String mutantName,
                                             List<String> selectedTestIds,
                                             String outFile) throws Exception {
        MutationSystem.MUTANT_PATH = MutationSystem.TRADITIONAL_MUTANT_PATH + File.separator + methodSignature;
        MutationSystem.METHOD_SIGNATURE = methodSignature;

        Class<?> execClass = new JMutationLoader8(mutantName, null, null,
                MuJavaRuntimeSupport8.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        WorkerExecutionResult res = executeJUnit(execClass, testSetName, selectedTestIds);
        storeResult(outFile, "ok", "", res.testOrder, res.results);

        // System.out.println("[DEBUG] mutants loadedFrom=" +
        //         execClass.getResource("/" + testSetName.replace('.', '/') + ".class"));
        // for (Method m : execClass.getDeclaredMethods()) {
        //     System.out.println("[DEBUG] method=" + m.getName());
        // }
    }


    private static void runServer(String sourceModuleHome,
                                  String resultModuleHome,
                                  String targetClassName) throws Exception {
        PrintWriter protocolWriter = new PrintWriter(
                new java.io.OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8.name()));

        if (resultModuleHome != null && !resultModuleHome.trim().isEmpty()) {
            System.setProperty("mujava.result.module.home", resultModuleHome);
        } else {
            System.clearProperty("mujava.result.module.home");
        }

        MuJavaRuntimeSupport8.initializeProject(sourceModuleHome, resultModuleHome, targetClassName);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("QUIT".equalsIgnoreCase(line)) {
                break;
            }

            String[] parts = line.split("\\t");
            if (parts.length < 6 || !"RUN".equalsIgnoreCase(parts[0])) {
                continue;
            }

            String testSetName = decodeProtocolField(parts[1]);
            String methodSignature = decodeProtocolField(parts[2]);
            String mutantName = decodeProtocolField(parts[3]);
            List<String> selectedTestIds = split(decodeProtocolField(parts[4]));

            try {
                WorkerExecutionResult res = runMutantInServer(targetClassName, testSetName, methodSignature, mutantName, selectedTestIds);
                protocolWriter.println("RESULT\t" + encodeProtocolPayload("ok", "", res.testOrder, res.results));
                protocolWriter.flush();
            } catch (Throwable t) {
                protocolWriter.println("RESULT\t" + encodeProtocolPayload("error", stackTraceToString(t),
                        new ArrayList<String>(), new LinkedHashMap<String, String>()));
                protocolWriter.flush();
            }
        }
    }

    private static String decodeProtocolField(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    private static WorkerExecutionResult executeJUnit(Class<?> execClass, String testSetName, List<String> selectedTestIds) {
        TestFramework framework = detectTestFramework(execClass);

        if (framework == TestFramework.JUNIT5 || framework == TestFramework.MIXED) {
            if (hasPlatformTestEngine()) {
                return executeWithPlatform(execClass, testSetName, selectedTestIds);
            }

            if (framework == TestFramework.JUNIT5) {
                throw new IllegalStateException(
                        "Detected JUnit 5 tests in " + execClass.getName()
                                + " but no JUnit Platform TestEngine is available on the worker classpath. "
                                + "Please add junit-jupiter-engine (and keep junit-platform-launcher)."
                );
            }
        }

        return executeWithJUnitCore(execClass, testSetName, selectedTestIds);
    }

    private static TestFramework detectTestFramework(Class<?> execClass) {
        boolean hasLegacy = false;
        boolean hasJunit5 = false;

        for (Class<?> c = execClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                if (isJUnit3TestMethod(m) || isJUnit4TestMethod(m)) {
                    hasLegacy = true;
                }
                if (isJUnit5TestMethod(m)) {
                    hasJunit5 = true;
                }
            }
        }

        if (hasLegacy && hasJunit5) return TestFramework.MIXED;
        if (hasJunit5) return TestFramework.JUNIT5;
        return TestFramework.LEGACY_OR_NONE;
    }

    private static boolean hasPlatformTestEngine() {
        try {
            java.util.ServiceLoader<org.junit.platform.engine.TestEngine> loader =
                    java.util.ServiceLoader.load(org.junit.platform.engine.TestEngine.class);
            for (org.junit.platform.engine.TestEngine ignored : loader) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static WorkerExecutionResult executeWithPlatform(Class<?> execClass, String testSetName, List<String> selectedTestIds) {
        LinkedHashMap<String, String> resultMap = new LinkedHashMap<String, String>();
        List<String> testOrder = effectiveTestOrder(execClass, testSetName, selectedTestIds);

        for (String testId : testOrder) {
            resultMap.put(testId, "pass");
        }

        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        if (selectedTestIds != null && !selectedTestIds.isEmpty()) {
            List<DiscoverySelector> selectors = new ArrayList<DiscoverySelector>();
            for (String testId : testOrder) {
                selectors.add(selectMethod(execClass, extractMethodNameFromTestId(testId)));
            }
            builder.selectors(selectors);
        } else {
            builder.selectors(selectClass(execClass));
        }
        LauncherDiscoveryRequest request = builder.build();

        Launcher launcher = LauncherFactory.create();

        final Map<String, String> uniqueIdToTestId = new HashMap<String, String>();
        final LinkedHashMap<String, String> failuresByTest = new LinkedHashMap<String, String>();

        TestExecutionListener listener = new TestExecutionListener() {
            @Override
            public void testPlanExecutionStarted(TestPlan testPlan) {
                for (TestIdentifier root : testPlan.getRoots()) {
                    collectMethodIdentifiers(testPlan, root, testSetName, uniqueIdToTestId);
                }
            }

            @Override
            public void executionFinished(TestIdentifier testIdentifier,
                                          TestExecutionResult testExecutionResult) {
                if (!testIdentifier.isTest()) {
                    return;
                }

                String testId = uniqueIdToTestId.get(testIdentifier.getUniqueId());
                if (testId == null || testId.trim().isEmpty()) {
                    testId = fallbackTestId(testSetName, testIdentifier);
                }

                if (!resultMap.containsKey(testId)) {
                    resultMap.put(testId, "pass");
                    testOrder.add(testId);
                }

                if (testExecutionResult.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
                    return;
                }

                Throwable t = testExecutionResult.getThrowable().orElse(null);
                String failText = buildFailureTextFromThrowable(t, testSetName, testId);
                failuresByTest.put(testId, failText);
            }
        };

        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        for (Map.Entry<String, String> e : failuresByTest.entrySet()) {
            resultMap.put(e.getKey(), e.getValue());
        }

        WorkerExecutionResult result = new WorkerExecutionResult();
        result.testOrder = testOrder;
        result.results = resultMap;
        return result;
    }

    private static WorkerExecutionResult executeWithJUnitCore(Class<?> execClass, String testSetName, List<String> selectedTestIds) {
        LinkedHashMap<String, String> resultMap = new LinkedHashMap<String, String>();
        List<String> testOrder = effectiveTestOrder(execClass, testSetName, selectedTestIds);

        for (String testId : testOrder) {
            resultMap.put(testId, "pass");
        }

        if (selectedTestIds == null || selectedTestIds.isEmpty()) {
            Result runResult = new JUnitCore().run(execClass);
            for (Failure failure : runResult.getFailures()) {
                String methodName = null;
                if (failure.getDescription() != null) {
                    methodName = failure.getDescription().getMethodName();
                }
                String testId = (methodName == null || methodName.trim().isEmpty())
                        ? MuJavaRuntimeSupport8.testId(testSetName, "UNKNOWN")
                        : MuJavaRuntimeSupport8.testId(testSetName, methodName);

                if (!resultMap.containsKey(testId)) {
                    resultMap.put(testId, "pass");
                    testOrder.add(testId);
                }

                resultMap.put(testId, buildFailureTextFromThrowable(failure.getException(), testSetName, testId));
            }
        } else {
            JUnitCore core = new JUnitCore();
            for (String testId : testOrder) {
                String methodName = extractMethodNameFromTestId(testId);
                Result singleResult = core.run(Request.method(execClass, methodName));
                for (Failure failure : singleResult.getFailures()) {
                    resultMap.put(testId, buildFailureTextFromThrowable(failure.getException(), testSetName, testId));
                }
            }
        }

        WorkerExecutionResult result = new WorkerExecutionResult();
        result.testOrder = testOrder;
        result.results = resultMap;
        return result;
    }

    private static List<String> effectiveTestOrder(Class<?> execClass,
                                                       String testSetName,
                                                       List<String> selectedTestIds) {
        if (selectedTestIds == null || selectedTestIds.isEmpty()) {
            return discoverTestIds(execClass, testSetName);
        }
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (String testId : selectedTestIds) {
            if (testId != null && !testId.trim().isEmpty()) {
                names.add(testId);
            }
        }
        return new ArrayList<String>(names);
    }

    private static List<String> discoverTestIds(Class<?> execClass, String testSetName) {
        Set<String> names = new LinkedHashSet<String>();

        for (Class<?> c = execClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                if (isJUnit3TestMethod(m) || isJUnit4TestMethod(m) || isJUnit5TestMethod(m)) {
                    names.add(MuJavaRuntimeSupport8.testId(testSetName, m.getName()));
                }
            }
        }

        List<String> testOrder = new ArrayList<String>(names);
        Collections.sort(testOrder);
        return testOrder;
    }

    private static boolean isJUnit3TestMethod(Method m) {
        return Modifier.isPublic(m.getModifiers())
                && m.getParameterCount() == 0
                && m.getReturnType().equals(Void.TYPE)
                && m.getName().startsWith("test");
    }

    private static boolean isJUnit4TestMethod(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if ("org.junit.Test".equals(a.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJUnit5TestMethod(Method m) {
        for (Annotation a : m.getAnnotations()) {
            String n = a.annotationType().getName();
            if ("org.junit.jupiter.api.Test".equals(n)
                    || "org.junit.jupiter.params.ParameterizedTest".equals(n)
                    || "org.junit.jupiter.api.RepeatedTest".equals(n)
                    || "org.junit.jupiter.api.TestTemplate".equals(n)
                    || "org.junit.jupiter.api.TestFactory".equals(n)) {
                return true;
            }
        }
        return false;
    }

    private static void collectMethodIdentifiers(TestPlan plan,
                                                 TestIdentifier node,
                                                 String testSetName,
                                                 Map<String, String> uniqueIdToTestId) {
        Optional<TestSource> sourceOpt = node.getSource();
        if (sourceOpt.isPresent() && sourceOpt.get() instanceof MethodSource) {
            MethodSource ms = (MethodSource) sourceOpt.get();
            uniqueIdToTestId.put(node.getUniqueId(), MuJavaRuntimeSupport8.testId(testSetName, ms.getMethodName()));
        }

        for (TestIdentifier child : plan.getChildren(node)) {
            collectMethodIdentifiers(plan, child, testSetName, uniqueIdToTestId);
        }
    }

    private static String fallbackTestId(String testSetName, TestIdentifier testIdentifier) {
        String display = testIdentifier.getDisplayName();
        if (display == null || display.trim().isEmpty()) {
            display = "UNKNOWN";
        }
        return MuJavaRuntimeSupport8.testId(testSetName, sanitizeDisplayName(display));
    }

    private static String sanitizeDisplayName(String s) {
        return s.replaceAll("\\s+", "_");
    }

    private static String buildFailureTextFromThrowable(Throwable t,
                                                        String testSetName,
                                                        String testId) {
        String lineNumber = "";
        String methodName = extractMethodNameFromTestId(testId);
        String message = "fail";

        if (t != null) {
            if (t.getMessage() != null && !t.getMessage().trim().isEmpty()) {
                message = t.getMessage();
            }

            for (StackTraceElement ste : t.getStackTrace()) {
                if (ste.getClassName() != null
                        && ste.getClassName().equals(testSetName)
                        && (methodName == null || methodName.equals(ste.getMethodName()))) {
                    lineNumber = String.valueOf(ste.getLineNumber());
                    break;
                }
            }
        }

        if (methodName == null || methodName.trim().isEmpty()) {
            return message;
        }
        return methodName + ": " + lineNumber + "; " + message;
    }

    private static String extractMethodNameFromTestId(String testId) {
        if (testId == null) return null;
        int idx = testId.indexOf('#');
        return idx >= 0 ? testId.substring(idx + 1) : testId;
    }

    private static List<String> split(String text) {
        List<String> result = new ArrayList<String>();
        if (text == null || text.isEmpty()) return result;
        String[] arr = text.split("\\u0001", -1);
        for (String s : arr) {
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (t != null) {
            t.printStackTrace(pw);
        }
        pw.flush();
        return sw.toString();
    }

    private static String encodeProtocolPayload(String status,
                                                String message,
                                                List<String> testOrder,
                                                Map<String, String> results) throws Exception {
        Properties props = new Properties();
        props.setProperty("status", status == null ? "" : status);
        props.setProperty("message", message == null ? "" : message);
        props.setProperty("tests", join(testOrder));
        for (Map.Entry<String, String> e : results.entrySet()) {
            props.setProperty("result." + e.getKey(), e.getValue() == null ? "" : e.getValue());
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        props.store(bos, "server-worker-result");
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private static void storeResult(String outFile,
                                    String status,
                                    String message,
                                    List<String> testOrder,
                                    Map<String, String> results) throws Exception {
        Properties props = new Properties();
        props.setProperty("status", status == null ? "" : status);
        props.setProperty("message", message == null ? "" : message);
        props.setProperty("tests", join(testOrder));
        for (Map.Entry<String, String> e : results.entrySet()) {
            props.setProperty("result." + e.getKey(), e.getValue() == null ? "" : e.getValue());
        }

        atomicStoreProperties(outFile, props, "stable-worker-result");
    }

    public static void storeError(String outFile, Throwable t) {
        try {
            Properties props = new Properties();
            props.setProperty("status", "error");
            props.setProperty("message", stackTraceToString(t));

            atomicStoreProperties(outFile, props, "stable-worker-error");
        } catch (Throwable ignored) {
        }
    }

    private static void atomicStoreProperties(String outFile, Properties props, String comment) throws Exception {
        File file = new File(outFile);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp.toFile());
            props.store(fos, comment);
            fos.getFD().sync();
        } finally {
            if (fos != null) fos.close();
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveError) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(item);
        }
        return sb.toString();
    }

    private enum TestFramework {
        LEGACY_OR_NONE,
        JUNIT5,
        MIXED
    }

    private static class WorkerExecutionResult {
        List<String> testOrder;
        LinkedHashMap<String, String> results;
    }
}