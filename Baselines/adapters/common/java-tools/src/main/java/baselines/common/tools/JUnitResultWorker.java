package baselines.common.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;

/**
 * Executes one JUnit 4 test class and emits machine-readable per-test results.
 *
 * <p>This class deliberately performs no mutant loading.  The parent Python
 * process starts a fresh JVM for the original program and for every mutant,
 * placing that mutant's isolated overlay directory first on the classpath.
 * Consequently a mutant can never leak into another execution.</p>
 */
public final class JUnitResultWorker {
    private JUnitResultWorker() {
    }

    private static final class TestRecord {
        final String id;
        final String className;
        final String methodName;
        String status = "PASS";
        String exceptionClass = "";
        String message = "";
        String trace = "";
        long startedEpochMs = 0L;
        long finishedEpochMs = 0L;
        long startedNanos = 0L;
        long durationNanos = 0L;

        TestRecord(Description description) {
            this.id = testId(description);
            this.className = nullToEmpty(description.getClassName());
            this.methodName = nullToEmpty(description.getMethodName());
        }

        JSONObject toJson() {
            return new JSONObject()
                    .put("id", id)
                    .put("class_name", className)
                    .put("method_name", methodName)
                    .put("status", status)
                    .put("exception_class", exceptionClass)
                    .put("message", message)
                    .put("trace", trace)
                    .put("started_epoch_ms", startedEpochMs)
                    .put("finished_epoch_ms", finishedEpochMs)
                    .put("duration_nanos", durationNanos)
                    .put("duration_ms", durationNanos / 1000000.0d);
        }
    }

    private static final class RecordingListener extends RunListener {
        final Map<String, TestRecord> records = new LinkedHashMap<String, TestRecord>();
        final File progressFile;
        final boolean failFast;
        RunNotifier notifier;
        boolean stopRequested = false;
        boolean stoppedEarly = false;
        long sequence = 0L;

        RecordingListener(File progressFile, boolean failFast) {
            this.progressFile = progressFile;
            this.failFast = failFast;
        }

        void attachNotifier(RunNotifier notifier) {
            this.notifier = notifier;
        }

        private TestRecord record(Description description) {
            String id = testId(description);
            TestRecord existing = records.get(id);
            if (existing == null) {
                existing = new TestRecord(description);
                records.put(id, existing);
            }
            return existing;
        }

        private void writeProgress(String state, TestRecord record) {
            JSONObject progress = new JSONObject()
                    .put("schema_version", 1)
                    .put("state", state)
                    .put("sequence", ++sequence)
                    .put("test_id", record.id)
                    .put("class_name", record.className)
                    .put("method_name", record.methodName)
                    .put("event_epoch_ms", System.currentTimeMillis());
            Exception lastError = null;
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    writeJsonAtomically(progressFile, progress);
                    return;
                } catch (Exception error) {
                    lastError = error;
                    try {
                        Thread.sleep(attempt * 10L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Progress is watchdog metadata, not test behavior. On Windows the
            // parent may briefly hold the destination while polling it; a
            // failed observation write must never become a synthetic JUnit
            // failure and change mutation results.
            System.err.println(
                    "Warning: cannot publish JUnit test progress after retries: "
                            + (lastError == null ? "unknown error" : lastError));
        }

        @Override
        public void testStarted(Description description) {
            TestRecord value = record(description);
            value.startedEpochMs = System.currentTimeMillis();
            value.startedNanos = System.nanoTime();
            writeProgress("TEST_STARTED", value);
        }

        @Override
        public void testFinished(Description description) {
            TestRecord value = record(description);
            value.finishedEpochMs = System.currentTimeMillis();
            if (value.startedNanos > 0L) {
                value.durationNanos = Math.max(0L, System.nanoTime() - value.startedNanos);
            }
            writeProgress("TEST_FINISHED", value);
        }

        @Override
        public void testFailure(Failure failure) {
            TestRecord value = record(failure.getDescription());
            value.status = "FAIL";
            Throwable error = failure.getException();
            if (error != null) {
                value.exceptionClass = error.getClass().getName();
                value.message = nullToEmpty(error.getMessage());
            }
            value.trace = nullToEmpty(failure.getTrace());
            if (failFast && notifier != null && !stopRequested) {
                stopRequested = true;
                notifier.pleaseStop();
            }
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            TestRecord value = record(failure.getDescription());
            value.status = "ASSUMPTION_SKIPPED";
            Throwable error = failure.getException();
            if (error != null) {
                value.exceptionClass = error.getClass().getName();
                value.message = nullToEmpty(error.getMessage());
            }
        }

        @Override
        public void testIgnored(Description description) {
            TestRecord value = record(description);
            value.status = "IGNORED";
            value.startedEpochMs = System.currentTimeMillis();
            value.finishedEpochMs = value.startedEpochMs;
            writeProgress("TEST_IGNORED", value);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String testId(Description description) {
        String className = nullToEmpty(description.getClassName());
        String methodName = nullToEmpty(description.getMethodName());
        if (!className.isEmpty() || !methodName.isEmpty()) {
            return className + "#" + methodName;
        }
        return nullToEmpty(description.getDisplayName());
    }

    private static boolean isInfrastructureFailure(TestRecord record) {
        String type = record.exceptionClass;
        String text = (record.message + "\n" + record.trace).toLowerCase();
        if (type.equals("java.lang.ClassNotFoundException")
                || type.equals("java.lang.NoClassDefFoundError")
                || type.equals("java.lang.UnsupportedClassVersionError")
                || type.equals("java.lang.ClassFormatError")
                || type.equals("java.lang.VerifyError")
                || type.equals("java.lang.UnsatisfiedLinkError")) {
            return true;
        }
        return text.contains("no runnable methods")
                || text.contains("could not find class")
                || text.contains("initializationerror") && text.contains("invalid test class")
                || text.contains("loader constraint violation")
                || text.contains("wrong name:");
    }

    private static void writeJson(File output, JSONObject value) throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output directory: " + parent);
        }
        Writer writer = new OutputStreamWriter(
                new FileOutputStream(output), StandardCharsets.UTF_8);
        try {
            writer.write(value.toString(2));
            writer.write(System.lineSeparator());
        } finally {
            writer.close();
        }
    }

    private static void writeJsonAtomically(File output, JSONObject value) throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output directory: " + parent);
        }
        File temporary = new File(output.getPath() + ".tmp");
        writeJson(temporary, value);
        try {
            Files.move(
                    temporary.toPath(), output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporary.toPath(), output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) {
            System.err.println(
                    "Usage: JUnitResultWorker <test-class> <result-json> "
                            + "<execution-started-json> <test-progress-json> [fail-fast]");
            System.exit(2);
        }
        boolean failFast = args.length == 5 && Boolean.parseBoolean(args[4]);
        long started = System.currentTimeMillis();
        JSONObject output = new JSONObject()
                .put("schema_version", 1)
                .put("test_class", args[0])
                .put("fail_fast", failFast)
                .put("worker_started_epoch_ms", started);
        int exitCode = 2;
        try {
            // Load and verify the test class without running its static
            // initializer. JVM startup, classpath lookup, and class-file load
            // are deliberately outside the behavioral execution timeout.
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> testClass = Class.forName(args[0], false, loader);
            RecordingListener listener = new RecordingListener(new File(args[3]), failFast);
            long executionStarted = System.currentTimeMillis();
            output.put("execution_started_epoch_ms", executionStarted);
            writeJsonAtomically(
                    new File(args[2]),
                    new JSONObject()
                            .put("schema_version", 1)
                            .put("state", "JUNIT_EXECUTION_STARTED")
                            .put("test_class", args[0])
                            .put("worker_started_epoch_ms", started)
                            .put("execution_started_epoch_ms", executionStarted));
            Result result;
            if (failFast) {
                result = runFailFast(testClass, listener);
            } else {
                JUnitCore core = new JUnitCore();
                core.addListener(listener);
                result = core.run(testClass);
            }
            JSONArray tests = new JSONArray();
            boolean infrastructureError = false;
            for (TestRecord record : listener.records.values()) {
                tests.put(record.toJson());
                infrastructureError |= isInfrastructureFailure(record);
            }
            if (result.getRunCount() == 0) {
                infrastructureError = true;
            }
            output.put("tests", tests)
                    .put("run_count", result.getRunCount())
                    .put("failure_count", result.getFailureCount())
                    .put("ignore_count", result.getIgnoreCount())
                    .put("run_time_ms", result.getRunTime())
                    .put("successful", result.wasSuccessful())
                    .put("infrastructure_error", infrastructureError)
                    .put("fail_fast_stop_requested", listener.stopRequested)
                    .put("stopped_early", listener.stoppedEarly);
            exitCode = infrastructureError ? 2 : (result.wasSuccessful() ? 0 : 1);
        } catch (Throwable error) {
            output.put("tests", new JSONArray())
                    .put("run_count", 0)
                    .put("failure_count", 0)
                    .put("ignore_count", 0)
                    .put("run_time_ms", 0)
                    .put("successful", false)
                    .put("infrastructure_error", true)
                    .put("worker_error_class", error.getClass().getName())
                    .put("worker_error_message", nullToEmpty(error.getMessage()));
            error.printStackTrace(System.err);
            exitCode = 2;
        } finally {
            output.put("worker_finished_epoch_ms", System.currentTimeMillis())
                    .put("exit_code", exitCode);
            writeJson(new File(args[1]), output);
        }
        System.exit(exitCode);
    }

    /**
     * Runs a mutant test suite until its first observable JUnit failure.
     *
     * <p>The original program never uses this path.  A mutant is already
     * killed by its first assertion failure, exception, or resource error, so
     * executing later tests cannot change the mutation score.  Calling
     * {@link RunNotifier#pleaseStop()} lets the current test finish and stops
     * before the next test starts.</p>
     */
    private static Result runFailFast(
            Class<?> testClass, RecordingListener listener) throws Exception {
        Runner runner = Request.aClass(testClass).getRunner();
        RunNotifier notifier = new RunNotifier();
        Result result = new Result();
        RunListener resultListener = result.createListener();
        notifier.addFirstListener(resultListener);
        notifier.addListener(listener);
        listener.attachNotifier(notifier);
        notifier.fireTestRunStarted(runner.getDescription());
        try {
            runner.run(notifier);
        } catch (StoppedByUserException stopped) {
            if (!listener.stopRequested) {
                throw stopped;
            }
            listener.stoppedEarly = true;
        } finally {
            notifier.fireTestRunFinished(result);
            notifier.removeListener(listener);
            notifier.removeListener(resultListener);
        }
        return result;
    }
}
