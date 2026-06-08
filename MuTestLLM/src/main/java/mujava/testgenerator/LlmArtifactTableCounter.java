package mujava.testgenerator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static mujava.cmd.TestRunner9_MultiProcess_batched.getCurr;
import static mujava.cmd.TestRunner9_MultiProcess_batched.readExcelFile;

public final class LlmArtifactTableCounter {

    private LlmArtifactTableCounter() {
    }

    private static final String DEFAULT_EXCEL =
            "../MutantParse/data/mutant_statistic_total_graph_llm.xlsx";

    private static final String TEST_MODE = "llm";

    /**
     * Excel 列索引：
     * 7  = projectName
     * 8  = filepath，用于推断 resultModuleHome
     * 10 = mutant_graph_path，用于推断 graph/output.json
     *
     * 如果你的 mutant_graph_path 不在第 10 列，只需要改这里。
     */
    private static final int PROJECT_COL = 7;
    private static final int FILE_PATH_COL = 8;
    private static final int MUTANT_GRAPH_PATH_COL = 10;

    private static final Set<String> TARGET_PROJECTS = new LinkedHashSet<String>(Arrays.asList(
            "oot",
            "commons-csv-1.2",
            "ant-1.10.12",
            "bcel-6.10.0",
            "commons-codec-1.10",
            "commons-jxpath-1.3",
            "commons-lang3-3.17.0",
            "jackson-core-2.9.9",
            "joda-time-2.14.0",
            "commons-cli-1.11.0",
            "commons-math-core",
            // "commons-math-legacy",
            "commons-math-legacy-core",
            "commons-math-legacy-exception",
            "commons-math-neuralnet",
            "commons-math-transform",
            "commons-numbers-angle",
            "commons-numbers-arrays",
            "commons-numbers-combinatorics",
            "commons-numbers-core",
            "commons-numbers-gamma",
            "commons-numbers-primes",
            "commons-numbers-quaternion"
    ));

    public static void main(String[] args) throws Exception {
        String excelPath = args != null && args.length > 0
                ? args[0]
                : System.getProperty("counter.excel", DEFAULT_EXCEL);

        String outcsv = System.getProperty("counter.out",
                "../MutantParse/data/llm_artifact_statistics.csv");

        List<List<Object>> excelData = readExcelFile(excelPath);
        if (excelData == null || excelData.isEmpty()) {
            System.err.println("[ERROR] Excel 数据为空: " + excelPath);
            return;
        }

        Map<String, Stat> stats = buildStatsFromExcel(excelData);

        for (Stat s : stats.values()) {
            if (s.resultModuleHome == null || s.resultModuleHome.trim().isEmpty()) {
                s.javaCount = 0;
                s.classCount = 0;
            } else {
                Path srcRoot = Paths.get(s.resultModuleHome, TEST_MODE, "src");
                Path classRoot = Paths.get(s.resultModuleHome, TEST_MODE, "classes");

                s.javaCount = countFiles(srcRoot, ".java", false);
                s.classCount = countFiles(classRoot, ".class", true);
            }

            s.javaMissing = Math.max(0, s.mutantCount - s.javaCount);
            s.classMissing = Math.max(0, s.mutantCount - s.classCount);
            s.graphDirMissing = Math.max(0, s.mutantCount - s.graphDirCount);
            s.graphOutputJsonMissing = Math.max(0, s.mutantCount - s.graphOutputJsonCount);

            s.javaMissingRatio = ratio(s.javaMissing, s.mutantCount);
            s.classMissingRatio = ratio(s.classMissing, s.mutantCount);
            s.graphDirMissingRatio = ratio(s.graphDirMissing, s.mutantCount);
            s.graphOutputJsonMissingRatio = ratio(s.graphOutputJsonMissing, s.mutantCount);
        }

        printTable(stats);
        writeCsv(stats, Paths.get(outcsv));

        System.out.println();
        System.out.println("[DONE] CSV written to: " + Paths.get(outcsv).toAbsolutePath().normalize());
    }

    private static Map<String, Stat> buildStatsFromExcel(List<List<Object>> excelData) {
        Map<String, Stat> stats = new LinkedHashMap<String, Stat>();

        for (String p : TARGET_PROJECTS) {
            Stat s = new Stat();
            s.project = p;
            stats.put(p, s);
        }

        for (int i = 1; i < excelData.size(); i++) {
            List<Object> row = excelData.get(i);
            if (row == null || row.size() <= FILE_PATH_COL) {
                continue;
            }

            String project = stringCell(row, PROJECT_COL);
            if (!TARGET_PROJECTS.contains(project)) {
                continue;
            }

            String rawFilePath = stringCell(row, FILE_PATH_COL);
            if (rawFilePath.isEmpty()) {
                continue;
            }

            String filepath = new File(rawFilePath)
                    .getParent()
                    .replace("\\", "/")
                    .replace("//?/", "");

            String resultModuleHome = Paths.get(getCurr(filepath)).normalize().toString();

            Stat s = stats.get(project);
            s.mutantCount++;

            if (s.resultModuleHome == null || s.resultModuleHome.trim().isEmpty()) {
                s.resultModuleHome = resultModuleHome;
            }

            s.resultModuleHomes.add(resultModuleHome);

            String mutantGraphPath = stringCell(row, MUTANT_GRAPH_PATH_COL);
            Path graphDir = resolveGraphDir(mutantGraphPath);

            if (graphDir != null) {
                s.graphDirs.add(graphDir.toString());

                if (s.firstGraphDir == null || s.firstGraphDir.trim().isEmpty()) {
                    s.firstGraphDir = graphDir.toString();
                }

                if (Files.isDirectory(graphDir)) {
                    s.graphDirCount++;
                }

                Path outputJson = graphDir.resolve("output.json");
                if (Files.isRegularFile(outputJson)) {
                    s.graphOutputJsonCount++;
                }
            }
        }

        return stats;
    }

    /**
     * graph 路径解析逻辑：
     *
     * 主规则：和 LLMTestGeneratorBatch 中的逻辑保持一致：
     *   new File(mutant_graph_path).getParent() + "/graph"
     *
     * 兼容规则：
     *   如果 Excel 中 mutant_graph_path 本身就是 mutant 目录，则检查：
     *   mutant_graph_path + "/graph"
     */
    private static Path resolveGraphDir(String mutantGraphPath) {
        String normalized = normalizePath(mutantGraphPath);
        if (normalized.isEmpty()) {
            return null;
        }

        File f = new File(normalized);
        File parent = f.getParentFile();

        Path batchStyleGraphDir = null;
        if (parent != null) {
            batchStyleGraphDir = Paths.get(parent.getPath(), "graph").normalize();
        }

        Path directGraphDir = Paths.get(normalized, "graph").normalize();

        if (batchStyleGraphDir != null) {
            Path batchStyleOutputJson = batchStyleGraphDir.resolve("output.json");
            if (Files.isDirectory(batchStyleGraphDir) || Files.isRegularFile(batchStyleOutputJson)) {
                return batchStyleGraphDir;
            }
        }

        Path directOutputJson = directGraphDir.resolve("output.json");
        if (Files.isDirectory(directGraphDir) || Files.isRegularFile(directOutputJson)) {
            return directGraphDir;
        }

        return batchStyleGraphDir != null ? batchStyleGraphDir : directGraphDir;
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.trim()
                .replace("\\", "/")
                .replace("//?/", "");
    }

    private static long countFiles(Path root, final String suffix, final boolean excludeInnerClass) {
        if (root == null || !Files.isDirectory(root)) {
            return 0L;
        }

        final long[] count = new long[]{0L};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName() == null ? "" : file.getFileName().toString();

                    if (!name.endsWith(suffix)) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (excludeInnerClass && name.contains("$")) {
                        return FileVisitResult.CONTINUE;
                    }

                    count[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[WARN] count failed: " + root + " ; " + e.getMessage());
        }

        return count[0];
    }

    private static void printTable(Map<String, Stat> stats) {
        System.out.printf(
                "%-36s %10s %10s %10s %12s %12s %12s %14s %12s %14s %14s %16s %14s %16s%n",
                "项目",
                "变异体数",
                "Java数",
                "Class数",
                "Graph目录数",
                "GraphJson数",
                "Java缺失数",
                "Java缺失比例",
                "Class缺失数",
                "Class缺失比例",
                "Graph目录缺失",
                "Graph目录缺失比例",
                "GraphJson缺失",
                "GraphJson缺失比例"
        );

        long totalMutant = 0;
        long totalJava = 0;
        long totalClass = 0;
        long totalGraphDir = 0;
        long totalGraphOutputJson = 0;

        long totalJavaMissing = 0;
        long totalClassMissing = 0;
        long totalGraphDirMissing = 0;
        long totalGraphOutputJsonMissing = 0;

        for (Stat s : stats.values()) {
            totalMutant += s.mutantCount;
            totalJava += s.javaCount;
            totalClass += s.classCount;
            totalGraphDir += s.graphDirCount;
            totalGraphOutputJson += s.graphOutputJsonCount;

            totalJavaMissing += s.javaMissing;
            totalClassMissing += s.classMissing;
            totalGraphDirMissing += s.graphDirMissing;
            totalGraphOutputJsonMissing += s.graphOutputJsonMissing;

            System.out.printf(
                    "%-36s %10d %10d %10d %12d %12d %12d %13.2f%% %12d %13.2f%% %14d %15.2f%% %14d %15.2f%%%n",
                    s.project,
                    s.mutantCount,
                    s.javaCount,
                    s.classCount,
                    s.graphDirCount,
                    s.graphOutputJsonCount,
                    s.javaMissing,
                    s.javaMissingRatio,
                    s.classMissing,
                    s.classMissingRatio,
                    s.graphDirMissing,
                    s.graphDirMissingRatio,
                    s.graphOutputJsonMissing,
                    s.graphOutputJsonMissingRatio
            );
        }

        System.out.printf(
                "%-36s %10d %10d %10d %12d %12d %12d %13.2f%% %12d %13.2f%% %14d %15.2f%% %14d %15.2f%%%n",
                "合计",
                totalMutant,
                totalJava,
                totalClass,
                totalGraphDir,
                totalGraphOutputJson,
                totalJavaMissing,
                ratio(totalJavaMissing, totalMutant),
                totalClassMissing,
                ratio(totalClassMissing, totalMutant),
                totalGraphDirMissing,
                ratio(totalGraphDirMissing, totalMutant),
                totalGraphOutputJsonMissing,
                ratio(totalGraphOutputJsonMissing, totalMutant)
        );
    }

    private static void writeCsv(Map<String, Stat> stats, Path out) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
        try {
            w.write("项目,"
                    + "变异体数,"
                    + "Java数,"
                    + "Class数,"
                    + "Graph目录数,"
                    + "GraphOutputJson数,"
                    + "Java缺失数,"
                    + "Java缺失比例,"
                    + "Class缺失数,"
                    + "Class缺失比例,"
                    + "Graph目录缺失数,"
                    + "Graph目录缺失比例,"
                    + "GraphOutputJson缺失数,"
                    + "GraphOutputJson缺失比例,"
                    + "resultModuleHome,"
                    + "firstGraphDir");
            w.newLine();

            long totalMutant = 0;
            long totalJava = 0;
            long totalClass = 0;
            long totalGraphDir = 0;
            long totalGraphOutputJson = 0;

            long totalJavaMissing = 0;
            long totalClassMissing = 0;
            long totalGraphDirMissing = 0;
            long totalGraphOutputJsonMissing = 0;

            for (Stat s : stats.values()) {
                totalMutant += s.mutantCount;
                totalJava += s.javaCount;
                totalClass += s.classCount;
                totalGraphDir += s.graphDirCount;
                totalGraphOutputJson += s.graphOutputJsonCount;

                totalJavaMissing += s.javaMissing;
                totalClassMissing += s.classMissing;
                totalGraphDirMissing += s.graphDirMissing;
                totalGraphOutputJsonMissing += s.graphOutputJsonMissing;

                w.write(csv(s.project) + ","
                        + s.mutantCount + ","
                        + s.javaCount + ","
                        + s.classCount + ","
                        + s.graphDirCount + ","
                        + s.graphOutputJsonCount + ","
                        + s.javaMissing + ","
                        + String.format(Locale.ROOT, "%.2f%%", s.javaMissingRatio) + ","
                        + s.classMissing + ","
                        + String.format(Locale.ROOT, "%.2f%%", s.classMissingRatio) + ","
                        + s.graphDirMissing + ","
                        + String.format(Locale.ROOT, "%.2f%%", s.graphDirMissingRatio) + ","
                        + s.graphOutputJsonMissing + ","
                        + String.format(Locale.ROOT, "%.2f%%", s.graphOutputJsonMissingRatio) + ","
                        + csv(s.resultModuleHome) + ","
                        + csv(s.firstGraphDir));
                w.newLine();
            }

            w.write(csv("合计") + ","
                    + totalMutant + ","
                    + totalJava + ","
                    + totalClass + ","
                    + totalGraphDir + ","
                    + totalGraphOutputJson + ","
                    + totalJavaMissing + ","
                    + String.format(Locale.ROOT, "%.2f%%", ratio(totalJavaMissing, totalMutant)) + ","
                    + totalClassMissing + ","
                    + String.format(Locale.ROOT, "%.2f%%", ratio(totalClassMissing, totalMutant)) + ","
                    + totalGraphDirMissing + ","
                    + String.format(Locale.ROOT, "%.2f%%", ratio(totalGraphDirMissing, totalMutant)) + ","
                    + totalGraphOutputJsonMissing + ","
                    + String.format(Locale.ROOT, "%.2f%%", ratio(totalGraphOutputJsonMissing, totalMutant)) + ","
                    + ",");
            w.newLine();

        } finally {
            w.close();
        }
    }

    private static String stringCell(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }

    private static double ratio(long missing, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return missing * 100.0 / total;
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static final class Stat {
        String project;
        String resultModuleHome = "";
        String firstGraphDir = "";

        Set<String> resultModuleHomes = new LinkedHashSet<String>();
        Set<String> graphDirs = new LinkedHashSet<String>();

        long mutantCount;
        long javaCount;
        long classCount;

        long graphDirCount;
        long graphOutputJsonCount;

        long javaMissing;
        long classMissing;
        long graphDirMissing;
        long graphOutputJsonMissing;

        double javaMissingRatio;
        double classMissingRatio;
        double graphDirMissingRatio;
        double graphOutputJsonMissingRatio;
    }
}