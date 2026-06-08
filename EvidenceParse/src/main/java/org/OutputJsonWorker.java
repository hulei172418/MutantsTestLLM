package org;

import org.model.MutationConfig;
import org.rip.MethodEntryResolver;
import org.rip.RipParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public final class OutputJsonWorker {
    private static volatile Path STOP_FILE = null;
    private static volatile long PARENT_PID = -1L;

    /**
     * Soot uses global singletons (Scene/G/Options).  In a single JVM, never run
     * two Soot analyses in parallel.  The safe fast path is process mode, where
     * each row owns a separate JVM and therefore a separate Soot global state.
     */
    private static final Object SOOT_ANALYSIS_LOCK = new Object();

    private OutputJsonWorker() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java org.OutputJsonWorker <config.properties> | --batch <batch.lst> [--stop <STOP_FILE>] [--parentPid <pid>]");
            System.exit(64);
        }
        int code;
        try {
            ParsedArgs parsedArgs = parseArgs(args);
            STOP_FILE = parsedArgs.stopFile;
            PARENT_PID = parsedArgs.parentPid;
            if ("--batch".equals(parsedArgs.mode)) {
                if (parsedArgs.batchFile == null) {
                    System.err.println("Usage: java org.OutputJsonWorker --batch <batch.lst> [--stop <STOP_FILE>] [--parentPid <pid>]");
                    System.exit(64);
                    return;
                }
                code = runBatch(parsedArgs.batchFile);
            } else if ("--stdin".equals(parsedArgs.mode)) {
                code = runStdin();
            } else {
                MutationConfigCodec.WorkerInput input = MutationConfigCodec.read(parsedArgs.configFile);
                long start = System.nanoTime();
                code = runOne(input.rowIndex, input.config, true);
                long ms = (System.nanoTime() - start) / 1_000_000L;
                printRowResult(input.rowIndex, code, ms, input.config);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            code = 2;
        }
        System.exit(code);
    }

    private static int runStdin() throws Exception {
        int worst = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (stopRequested()) {
                    System.out.println("WORKER_STOP_REQUESTED	" + safe(STOP_FILE == null ? null : STOP_FILE.toString()));
                    return worst == 0 ? 130 : worst;
                }
                MutationConfigCodec.WorkerInput input = MutationConfigCodec.decodeLine(line);
                long start = System.nanoTime();
                int code = runOne(input.rowIndex, input.config, true);
                long ms = (System.nanoTime() - start) / 1_000_000L;
                printRowResult(input.rowIndex, code, ms, input.config);
                if (code != 0) {
                    worst = code;
                }
            }
        }
        return worst;
    }

    private static int runBatch(Path batchList) throws Exception {
        List<String> configFiles = Files.readAllLines(batchList);
        int worst = 0;
        for (String line : configFiles) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (stopRequested()) {
                System.out.println("WORKER_STOP_REQUESTED	" + safe(STOP_FILE == null ? null : STOP_FILE.toString()));
                return worst == 0 ? 130 : worst;
            }
            MutationConfigCodec.WorkerInput input = MutationConfigCodec.read(Paths.get(line.trim()));
            long start = System.nanoTime();
            int code = runOne(input.rowIndex, input.config, true);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            printRowResult(input.rowIndex, code, ms, input.config);
            if (code != 0) {
                worst = code;
            }
        }
        return worst;
    }

    private static void printRowResult(int rowIndex, int code, long ms, MutationConfig config) {
        System.out.println("ROW_RESULT	" + rowIndex + "	" + code + "	" + ms + "	" + safe(config == null ? null : config.filepath));
    }

    public static int runOne(int rowIndex, MutationConfig config, boolean resetSoot) {
        synchronized (SOOT_ANALYSIS_LOCK) {
            if (resetSoot) {
                resetSootQuietly();
            }
            try {
                MethodEntryResolver.Resolution resolution = MethodEntryResolver.resolve(config);
                resolution.applyTo(config);
                RipParser parser = new RipParser(config);
                parser.analyzePairToJson();
                return 0;
            } catch (Throwable t) {
                System.err.println("Analysis failed for " + safe(config.className) + "####" + safe(config.methodName)
                        + "####" + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
                t.printStackTrace(System.err);
                return 2;
            } finally {
                if (resetSoot) {
                    resetSootQuietly();
                }
            }
        }
    }

    private static void resetSootQuietly() {
        try {
            soot.G.reset();
        } catch (Throwable ignored) {
            // Some embedding environments may not expose soot.G at test time.
            // In the real pipeline Soot is present and this clears Scene/Options.
        }
    }


    private static boolean stopRequested() {
        try {
            if (STOP_FILE != null && Files.exists(STOP_FILE)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (PARENT_PID > 0) {
                Optional<ProcessHandle> parent = ProcessHandle.of(PARENT_PID);
                if (parent.isEmpty() || !parent.get().isAlive()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static ParsedArgs parseArgs(String[] args) {
        ParsedArgs out = new ParsedArgs();
        if (args == null || args.length == 0) {
            return out;
        }
        if ("--batch".equals(args[0])) {
            out.mode = "--batch";
            if (args.length > 1) {
                out.batchFile = Paths.get(args[1]);
            }
        } else if ("--stdin".equals(args[0])) {
            out.mode = "--stdin";
        } else {
            out.mode = "single";
            out.configFile = Paths.get(args[0]);
        }
        for (int i = 1; i < args.length; i++) {
            String k = args[i];
            String v = (i + 1 < args.length) ? args[i + 1] : null;
            if ("--stop".equals(k) && v != null) {
                out.stopFile = Paths.get(v);
                i++;
            } else if ("--parentPid".equals(k) && v != null) {
                try {
                    out.parentPid = Long.parseLong(v.trim());
                } catch (NumberFormatException ignored) {
                    out.parentPid = -1L;
                }
                i++;
            }
        }
        return out;
    }

    private static final class ParsedArgs {
        String mode;
        Path configFile;
        Path batchFile;
        Path stopFile;
        long parentPid = -1L;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
