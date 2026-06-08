package org;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;

import org.rip.RipParser;
import org.model.MutationConfig;
import org.rip.MethodEntryResolver;
import org.utils.PathSanitizer;

import static org.utils.ExcelUtils.readExcel;

public class DataGenerator {

    private static final Logger logger = Logger.getLogger(DataGenerator.class.getName());
    private static String operatorName;
    private static String lineNo;
    private static String methodName;
    private static String className;
    private static String className_F;
    private static String mutationStatement;
    private static String packageName;
    private static String projectName;
    private static String filepath;
    private static String graphPath;
    private static boolean rewrite = true; // Whether to overwrite existing graph files
    private static String logType;
    private static final Set<String> stringSet = new HashSet<>();
    private static boolean logAppend;
    private static Path entryReportPath;

    public static void main(String[] args) {
        logAppend = false;
        String mutantPath = "../MutantParse/data/mutant_statistic_1.xlsx";
        mutantPath = "../MutantParse/data/commons-cli-1.11.0_oot.xlsx";
        sootMutants(mutantPath);
        // test();
    }

    private static void sootMutants(String filePath) {
        try {
            String fileName = new File(filePath).getName();
            int dotIndex = fileName.lastIndexOf('.');
            String baseName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
            String logFile = "logs/" + baseName + ".log";
            setupLogger(logFile);
            setupEntryReport("logs/" + baseName + "_entry_resolution_report.tsv");

            List<List<Object>> excelData = readExcelFile(filePath);
            for (int i = 1; i < excelData.size(); i++) {
                MutationConfig config = new MutationConfig();
                config.operator = (String) excelData.get(i).get(0);
                Double d = (Double) excelData.get(i).get(1);
                config.lineNo = (d % 1 == 0) ? String.valueOf(d.longValue()) : String.valueOf(d);
                config.methodName = (String) excelData.get(i).get(2);
                config.className = (String) excelData.get(i).get(3);
                config.classNameF = (String) excelData.get(i).get(4);
                config.mutationStatement = (String) excelData.get(i).get(5);
                config.packageName = (String) excelData.get(i).get(6);
                config.projectName = (String) excelData.get(i).get(7);
                String sourcePath = PathSanitizer.clean((String) excelData.get(i).get(8));
                String parent = new File(sourcePath).getParent();
                if (parent == null || parent.trim().isEmpty()) {
                    parent = sourcePath;
                }
                config.filepath = normalizeMutantDirectory(parent, config.operator);
                rewrite = true;
                System.out.print(i + " th: ");
                System.out.println(config.filepath);
                // sootOriginalProgram(config.filepath, config);
                long start = System.nanoTime();
                if (rewrite || graphNotExist(graphPath)) {
                    run(i, config);
                }
                long end = System.nanoTime();
                double duration1Ms = (end - start) / 1_000_000.0;
                // System.out.println("Total: " + duration1Ms + " ms");
            }
        } catch (IOException e) {
            System.err.println("Failed to set up logger: " + e.getMessage());
        }
    }

    private static String normalizeMutantDirectory(String dir, String operator) {
        if (dir == null || dir.trim().isEmpty()) {
            return "";
        }
        Path base = PathSanitizer.path(dir);
        if (base.getFileName() != null && base.getFileName().toString().endsWith(".java")) {
            base = base.getParent();
        }
        String op = operator == null ? "" : operator.trim();
        if (!op.isEmpty() && (base.getFileName() == null || !op.equals(base.getFileName().toString()))) {
            Path byOperator = base.resolve(op);
            if (Files.isDirectory(byOperator)) {
                base = byOperator;
            }
        }
        return PathSanitizer.normalizeForJson(base);
    }

    private static void sootOriginalProgram(String filePath, MutationConfig config) {
        filepath = PathSanitizer.path(filePath).getParent().getParent().getParent().toString() + "/original";
        // Prevent duplicate generation
        String curHash = filepath + "/" + methodName;
        if (!stringSet.contains(curHash)) {
            graphPath = filepath + "/" + methodName + "/graph";
            logType = "mutant";
            String absolutePath = new File(filepath + "/" + methodName).getAbsolutePath().replace("\\", "/");
            ;
            System.out.println("Soot for: " + absolutePath);
            if (rewrite || graphNotExist(graphPath)) {
                run(-1, config);
                stringSet.add(curHash);
            }
        }
        logType = "original";
        filepath = filePath; // Restore mutant file path
        graphPath = filepath + "/graph"; // Restore graph output path
        String absolutePath = new File(filepath).getAbsolutePath().replace("\\", "/");
        ;
        System.out.println("Soot for: " + absolutePath);
    }

    public static boolean graphNotExist(String graphPath) {
        String[] filesToCheck = { "output.json" };

        boolean allFilesExist = false;

        // Check if each file exists
        for (String fileName : filesToCheck) {
            Path filePath = PathSanitizer.path(graphPath, fileName);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                allFilesExist = true;
                break;
            }
        }
        return allFilesExist;
    }

    private static List<List<Object>> readExcelFile(String filePath) {
        try {
            return readExcel(filePath, 0);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private static void run(int rowIndex, MutationConfig config) {
        try {
            // Resolve the callable test entry before building output.json.
            // Important: config.methodName remains the real mutation method A.
            // config.testEntryMethodName is the public caller B that tests should call.
            MethodEntryResolver.Resolution resolution = MethodEntryResolver.resolve(config);
            resolution.applyTo(config);

            // System.out.println("[ENTRY] kind=" + config.testEntryKind
            // + " mutation=" + config.mutationClassName + "#" + config.mutationMethodName
            // + " testEntry=" + config.testEntryClassName + "#" +
            // config.testEntryMethodName
            // + " reflection=" + config.useReflectionFallback);
            // System.out.println("[CHAIN] " + config.testCallChain);
            if (config.testEntryNotes != null && !config.testEntryNotes.isBlank()) {
                // System.out.println("[ENTRY-NOTE] " + config.testEntryNotes);
            }

            appendEntryReport(rowIndex, config, "OK", "");

            // Analyze the real mutation method A for RIP/Jimple evidence, while also
            // writing the resolved test entry B into output.json for test generation.
            RipParser parser = new RipParser(config);
            String json = parser.analyzePairToJson(); // Instance method (not static)
            // System.out.println(json);
        } catch (Exception e) {
            appendEntryReport(rowIndex, config, "FAILED", e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            System.err.println(
                    "Analysis failed for " + config.className + "####" + config.methodName + "####" + e.getMessage());
            logger.log(Level.SEVERE, "Analysis failed for ####"
                    + "mutant" + "####" + config.operator + "####" + config.lineNo + "####" + config.methodName + "####"
                    + config.mutationStatement + "####" + config.className + "####" + config.classNameF + "####"
                    + config.packageName + "####" + config.projectName + "####" + config.filepath + "####"
                    + "entryKind=" + config.testEntryKind + "####"
                    + "entry=" + config.testEntryClassName + "#" + config.testEntryMethodName + "####"
                    + e.getMessage());
            e.printStackTrace();
        } finally {
            // Reset Soot state
        }
    }

    private static void setupEntryReport(String reportFile) throws IOException {
        entryReportPath = PathSanitizer.path(reportFile);
        Path parent = entryReportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(entryReportPath)) {
            writer.write(String.join("\t",
                    "rowIndex",
                    "status",
                    "operator",
                    "lineNo",
                    "projectName",
                    "packageName",
                    "filepath",
                    "className",
                    "classNameF",
                    "originalMethodName",
                    "mutationClassName",
                    "mutationSootClassName",
                    "mutationMethodName",
                    "testEntryKind",
                    "useReflectionFallback",
                    "testEntryClassName",
                    "testEntrySootClassName",
                    "testEntryMethodName",
                    "testGenerationPackage",
                    "testCallChain",
                    "testEntryNotes",
                    "error"));
            writer.newLine();
        }
    }

    private static void appendEntryReport(int rowIndex, MutationConfig config, String status, String error) {
        if (entryReportPath == null) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(entryReportPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(String.join("\t",
                    tsv(String.valueOf(rowIndex)),
                    tsv(status),
                    tsv(config.operator),
                    tsv(config.lineNo),
                    tsv(config.projectName),
                    tsv(config.packageName),
                    tsv(config.filepath),
                    tsv(config.className),
                    tsv(config.classNameF),
                    tsv(config.methodName),
                    tsv(config.mutationClassName),
                    tsv(config.mutationSootClassName),
                    tsv(config.mutationMethodName),
                    tsv(config.testEntryKind),
                    tsv(String.valueOf(config.useReflectionFallback)),
                    tsv(config.testEntryClassName),
                    tsv(config.testEntrySootClassName),
                    tsv(config.testEntryMethodName),
                    tsv(config.testGenerationPackage),
                    tsv(config.testCallChain),
                    tsv(config.testEntryNotes),
                    tsv(error)));
            writer.newLine();
        } catch (IOException ioe) {
            System.err.println("Failed to append entry report: " + ioe.getMessage());
        }
    }

    private static String tsv(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\t", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void setupLogger(String logFile) throws IOException {
        Files.createDirectories(PathSanitizer.path("logs"));

        FileHandler fileHandler = new FileHandler(logFile, logAppend);
        fileHandler.setFormatter(new SimpleFormatterWithoutPrefix());

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(fileHandler);
        rootLogger.setLevel(Level.SEVERE);

        // Remove default console output
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
}
