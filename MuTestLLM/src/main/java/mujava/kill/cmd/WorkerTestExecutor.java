package mujava.kill.cmd;

import mujava.MutationSystem;
import mujava.kill.test.JMutationLoader;
import mujava.kill.test.OriginalLoader;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Extracted worker-side execution logic.
 * Keeps the same execution model as the original worker: all original / inspect / mutant
 * runs happen in the child JVM, while the parent process only schedules them.
 */
public final class WorkerTestExecutor {

    public void runOriginal(String projectHome,
                            String targetClassName,
                            String testSetName,
                            String outFile) throws Exception {
        MuJavaRuntimeSupport.initializeProject(projectHome, targetClassName);
        Class<?> execClass = new OriginalLoader(null, null,
                MuJavaRuntimeSupport.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        WorkerExecutionResult res = JUnitExecutionSupport.executeJUnit(execClass, testSetName, null);
        WorkerProtocolCodec.storeResult(outFile, "ok", "", res.testOrder, res.results);
    }

    public void runInspect(String projectHome,
                           String targetClassName,
                           String testSetName,
                           String outFile) throws Exception {
        MuJavaRuntimeSupport.initializeProject(projectHome, targetClassName);
        Class<?> execClass = new OriginalLoader(null, null,
                MuJavaRuntimeSupport.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        List<String> testOrder = JUnitExecutionSupport.discoverTestIds(execClass, testSetName);
        WorkerProtocolCodec.storeResult(outFile, "ok", "inspect", testOrder, new LinkedHashMap<String, String>());
    }

    public void runMutant(String projectHome,
                          String targetClassName,
                          String testSetName,
                          String methodSignature,
                          String mutantName,
                          String outFile) throws Exception {
        MuJavaRuntimeSupport.initializeProject(projectHome, targetClassName);
        runMutantInitialized(targetClassName, testSetName, methodSignature, mutantName, null, outFile);
    }

    public void runMutantInitialized(String targetClassName,
                                     String testSetName,
                                     String methodSignature,
                                     String mutantName,
                                     List<String> selectedTestIds,
                                     String outFile) throws Exception {
        MutationSystem.MUTANT_PATH = MutationSystem.TRADITIONAL_MUTANT_PATH + File.separator + methodSignature;
        MutationSystem.METHOD_SIGNATURE = methodSignature;

        Class<?> execClass = new JMutationLoader(mutantName, null, null,
                MuJavaRuntimeSupport.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        WorkerExecutionResult res = JUnitExecutionSupport.executeJUnit(execClass, testSetName, selectedTestIds);
        WorkerProtocolCodec.storeResult(outFile, "ok", "", res.testOrder, res.results);
    }

    public WorkerExecutionResult runMutantInServer(String targetClassName,
                                                   String testSetName,
                                                   String methodSignature,
                                                   String mutantName,
                                                   List<String> selectedTestIds) throws Exception {
        MutationSystem.MUTANT_PATH = MutationSystem.TRADITIONAL_MUTANT_PATH + File.separator + methodSignature;
        MutationSystem.METHOD_SIGNATURE = methodSignature;

        Class<?> execClass = new JMutationLoader(mutantName, null, null,
                MuJavaRuntimeSupport.runtimePackagePrefix(testSetName)).loadTestClass(testSetName);
        return JUnitExecutionSupport.executeJUnit(execClass, testSetName, selectedTestIds);
    }

    public void runServer(String sourceModuleHome,
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

        MuJavaRuntimeSupport.initializeProject(sourceModuleHome, resultModuleHome, targetClassName);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
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

            String testSetName = WorkerProtocolCodec.decodeField(parts[1]);
            String methodSignature = WorkerProtocolCodec.decodeField(parts[2]);
            String mutantName = WorkerProtocolCodec.decodeField(parts[3]);
            List<String> selectedTestIds = WorkerProtocolCodec.split(WorkerProtocolCodec.decodeField(parts[4]));

            try {
                WorkerExecutionResult res = runMutantInServer(
                        targetClassName, testSetName, methodSignature, mutantName, selectedTestIds);
                protocolWriter.println("RESULT\t" + WorkerProtocolCodec.encodePayload(
                        "ok", "", res.testOrder, res.results));
                protocolWriter.flush();
            } catch (Throwable t) {
                protocolWriter.println("RESULT\t" + WorkerProtocolCodec.encodePayload(
                        "error", WorkerProtocolCodec.stackTraceToString(t),
                        new ArrayList<String>(), new LinkedHashMap<String, String>()));
                protocolWriter.flush();
            }
        }
    }

    public static final class WorkerExecutionResult {
        public List<String> testOrder;
        public LinkedHashMap<String, String> results;
    }
}
