package mujava.kill.cmd;

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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

final class JUnitExecutionSupport {

    private JUnitExecutionSupport() {}

    static WorkerTestExecutor.WorkerExecutionResult executeJUnit(Class<?> execClass,
                                                                String testSetName,
                                                                List<String> selectedTestIds) {
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

    static List<String> discoverTestIds(Class<?> execClass, String testSetName) {
        Set<String> names = new LinkedHashSet<String>();
        for (Class<?> c = execClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                if (isJUnit3TestMethod(m) || isJUnit4TestMethod(m) || isJUnit5TestMethod(m)) {
                    names.add(MuJavaRuntimeSupport.testId(testSetName, m.getName()));
                }
            }
        }
        List<String> testOrder = new ArrayList<String>(names);
        Collections.sort(testOrder);
        return testOrder;
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

    private static WorkerTestExecutor.WorkerExecutionResult executeWithPlatform(Class<?> execClass,
                                                                                String testSetName,
                                                                                List<String> selectedTestIds) {
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

                if (testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED) {
                    Throwable t = testExecutionResult.getThrowable().orElse(null);
                    failuresByTest.put(testId, buildFailureTextFromThrowable(t, testSetName, testId));
                }
            }
        };

        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        for (Map.Entry<String, String> entry : failuresByTest.entrySet()) {
            resultMap.put(entry.getKey(), entry.getValue());
        }

        WorkerTestExecutor.WorkerExecutionResult result = new WorkerTestExecutor.WorkerExecutionResult();
        result.testOrder = testOrder;
        result.results = resultMap;
        return result;
    }

    private static WorkerTestExecutor.WorkerExecutionResult executeWithJUnitCore(Class<?> execClass,
                                                                                 String testSetName,
                                                                                 List<String> selectedTestIds) {
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
                        ? MuJavaRuntimeSupport.testId(testSetName, "UNKNOWN")
                        : MuJavaRuntimeSupport.testId(testSetName, methodName);

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

        WorkerTestExecutor.WorkerExecutionResult result = new WorkerTestExecutor.WorkerExecutionResult();
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
            uniqueIdToTestId.put(node.getUniqueId(), MuJavaRuntimeSupport.testId(testSetName, ms.getMethodName()));
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
        return MuJavaRuntimeSupport.testId(testSetName, sanitizeDisplayName(display));
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

    private enum TestFramework {
        LEGACY_OR_NONE,
        JUNIT5,
        MIXED
    }
}
