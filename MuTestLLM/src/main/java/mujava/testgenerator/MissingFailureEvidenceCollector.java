package mujava.testgenerator;

import mujava.MutationSystem;
import org.apache.poi.ss.usermodel.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;

import static mujava.cmd.TestRunner9_MultiProcess_batched.getCurr;
import static mujava.testgenerator.tools.TestNameUtils.buildGeneratedTestFqn;

public final class MissingFailureEvidenceCollector {

    private MissingFailureEvidenceCollector() {
    }

    private static final String DEFAULT_INPUT_EXCEL =
            "../MutantParse/data/missing_llm_class_rows.xlsx";

    private static final String DEFAULT_OUTPUT_JSON =
            "../MutantParse/data/grouped_minimal_failure_evidence_for_llm.json";

    private static final int COL_OPERATOR = 0;
    private static final int COL_LINE = 1;
    private static final int COL_METHOD = 2;
    private static final int COL_CLASS = 3;
    private static final int COL_CLASS_F = 4;
    private static final int COL_MUTATION_STATEMENT = 5;
    private static final int COL_PACKAGE = 6;
    private static final int COL_PROJECT = 7;
    private static final int COL_FILE_PATH = 8;
    private static final int COL_ORIGINAL_GRAPH_PATH = 9;
    private static final int COL_MUTANT_GRAPH_PATH = 10;
    private static final int COL_IS_KILLED = 11;

    public static void main(String[] args) throws Exception {
        String inputExcel = args != null && args.length > 0
                ? args[0]
                : System.getProperty("collector.input", DEFAULT_INPUT_EXCEL);

        String outputJson = args != null && args.length > 1
                ? args[1]
                : System.getProperty("collector.output", DEFAULT_OUTPUT_JSON);

        CollectorConfig config = CollectorConfig.fromSystemProperties();

        collect(Paths.get(inputExcel), Paths.get(outputJson), config);
    }

    private static void collect(Path inputExcel, Path outputJson, CollectorConfig config) throws Exception {
        if (!Files.isRegularFile(inputExcel)) {
            throw new IllegalArgumentException("Input excel not found: "
                    + inputExcel.toAbsolutePath().normalize());
        }

        long startedAt = System.currentTimeMillis();

        Map<String, String> resultModuleHomeByProjectCache = new HashMap<String, String>();

        Map<String, Map<String, List<Path>>> compileErrorIndexCache =
                new HashMap<String, Map<String, List<Path>>>();

        Map<String, Map<String, Path>> compactEvidenceIndexCache =
                new HashMap<String, Map<String, Path>>();

        Map<String, Path> outputJsonCache = new HashMap<String, Path>();
        Map<String, Integer> graphExistsCache = new HashMap<String, Integer>();

        Map<String, GroupBucket> groups = new LinkedHashMap<String, GroupBucket>();

        int totalRows = 0;
        int validRows = 0;
        int javaExistsRows = 0;
        int selectedCompileErrorExistsRows = 0;

        try (Workbook wb = WorkbookFactory.create(new FileInputStream(inputExcel.toFile()))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            int lastRow = sheet.getLastRowNum();

            for (int rowIndex = 1; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                totalRows++;

                String operator = cellString(row, COL_OPERATOR, formatter);
                String line = cellString(row, COL_LINE, formatter);
                String method = cellString(row, COL_METHOD, formatter);
                String className = cellString(row, COL_CLASS, formatter);
                String classF = cellString(row, COL_CLASS_F, formatter);
                String mutationStatement = cellString(row, COL_MUTATION_STATEMENT, formatter);
                String targetClassName = cellString(row, COL_PACKAGE, formatter);
                String project = cellString(row, COL_PROJECT, formatter);
                String rawFilePath = cellString(row, COL_FILE_PATH, formatter);
                String originalGraphPath = cellString(row, COL_ORIGINAL_GRAPH_PATH, formatter);
                String mutantGraphPath = cellString(row, COL_MUTANT_GRAPH_PATH, formatter);
                String isKilled = cellString(row, COL_IS_KILLED, formatter);

                if (isBlank(project) || isBlank(targetClassName) || isBlank(method)
                        || isBlank(operator) || isBlank(rawFilePath)) {
                    continue;
                }

                validRows++;

                String resultModuleHome;
                try {
                    resultModuleHome = resolveResultModuleHomeCachedByProject(
                            project,
                            rawFilePath,
                            resultModuleHomeByProjectCache
                    );
                } catch (Throwable t) {
                    resultModuleHome = "";
                }

                String testSetName = buildGeneratedTestFqn(targetClassName, operator);
                String simpleTestClassName = simpleNameOf(testSetName);

                Path javaFile = isBlank(resultModuleHome)
                        ? null
                        : Paths.get(
                        resultModuleHome,
                        MutationSystem.TESTSET_MODE_LLMS,
                        "src",
                        testSetName.replace('.', File.separatorChar) + ".java"
                ).toAbsolutePath().normalize();

                Path expectedClassFile = isBlank(resultModuleHome)
                        ? null
                        : Paths.get(
                        resultModuleHome,
                        MutationSystem.TESTSET_MODE_LLMS,
                        "classes",
                        testSetName.replace('.', File.separatorChar) + ".class"
                ).toAbsolutePath().normalize();

                Path outputJsonPath = null;
                if (config.collectOutputJsonStatus) {
                    outputJsonPath = locateOutputJsonCached(
                            rawFilePath,
                            mutantGraphPath,
                            outputJsonCache
                    );
                }

                Path compactEvidencePath = null;
                if (config.collectCompactEvidenceStatus) {
                    compactEvidencePath = locateCompactEvidenceCached(
                            resultModuleHome,
                            simpleTestClassName,
                            compactEvidenceIndexCache
                    );
                }

                List<Path> compileErrorFiles = Collections.emptyList();
                if (!isBlank(resultModuleHome)) {
                    Map<String, List<Path>> index = getOrBuildCompileErrorIndex(
                            resultModuleHome,
                            compileErrorIndexCache
                    );
                    List<Path> found = index.get(simpleTestClassName);
                    if (found != null) {
                        compileErrorFiles = found;
                    }
                }

                Path selectedCompileError = selectedCompileErrorFile(
                        compileErrorFiles,
                        config.compileErrorIndex,
                        config.fallbackToFirstAvailableError
                );

                boolean javaExists = javaFile != null && Files.isRegularFile(javaFile);
                boolean classExists = expectedClassFile != null && Files.isRegularFile(expectedClassFile);
                boolean selectedCompileErrorExists =
                        selectedCompileError != null && Files.isRegularFile(selectedCompileError);

                int graphExists = 0;
                if (config.collectGraphStatus) {
                    graphExists = graphDirectoryExistsCached(mutantGraphPath, graphExistsCache);
                }

                boolean outputJsonExists = outputJsonPath != null && Files.isRegularFile(outputJsonPath);
                boolean compactEvidenceExists = compactEvidencePath != null && Files.isRegularFile(compactEvidencePath);

                if (javaExists) {
                    javaExistsRows++;
                }
                if (selectedCompileErrorExists) {
                    selectedCompileErrorExistsRows++;
                }

                String groupKey = buildGroupKey(project, targetClassName, method);

                GroupBucket group = groups.get(groupKey);
                if (group == null) {
                    group = new GroupBucket();
                    group.groupKey = groupKey;
                    group.project = project;
                    group.targetClassName = targetClassName;
                    group.className = className;
                    group.classF = classF;
                    group.method = method;
                    groups.put(groupKey, group);
                }

                MutantRecord m = new MutantRecord();
                m.excelRowIndex = rowIndex;
                m.operator = operator;
                m.line = line;
                m.method = method;
                m.className = className;
                m.classF = classF;
                m.mutationStatement = mutationStatement;
                m.targetClassName = targetClassName;
                m.project = project;
                m.rawFilePath = rawFilePath;
                m.originalGraphPath = originalGraphPath;
                m.mutantGraphPath = mutantGraphPath;
                m.isKilled = isKilled;
                m.testSetName = testSetName;
                m.simpleTestClassName = simpleTestClassName;
                m.javaFile = javaFile;
                m.expectedClassFile = expectedClassFile;
                m.outputJsonPath = outputJsonPath;
                m.compactEvidencePath = compactEvidencePath;
                m.selectedCompileErrorFile = selectedCompileError;

                m.javaExists = javaExists;
                m.classExists = classExists;
                m.selectedCompileErrorExists = selectedCompileErrorExists;
                m.outputJsonExists = outputJsonExists;
                m.compactEvidenceExists = compactEvidenceExists;
                m.graphExists = graphExists;

                group.add(m, config);

                if (validRows % 5000 == 0) {
                    System.out.println("[PROGRESS] validRows=" + validRows
                            + ", groups=" + groups.size()
                            + ", javaExistsRows=" + javaExistsRows
                            + ", selectedCompileErrorExistsRows=" + selectedCompileErrorExistsRows
                            + ", selectedCompileErrorIndex=" + config.compileErrorIndex
                            + ", elapsedMs=" + (System.currentTimeMillis() - startedAt));
                }
            }
        }

        JSONObject root = new JSONObject();
        root.put("createdAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        root.put("groupBy", "project + targetClassName(package column) + method");
        root.put("description",
                "Minimal grouped failure evidence. Each group keeps one representative generated Java file and the selected compile error only. Paths are not exported.");

        JSONObject summary = new JSONObject();
        summary.put("totalRows", totalRows);
        summary.put("validRows", validRows);
        summary.put("groupCount", groups.size());
        summary.put("javaExistsRows", javaExistsRows);
        summary.put("selectedCompileErrorIndex", config.compileErrorIndex);
        summary.put("selectedCompileErrorExistsRows", selectedCompileErrorExistsRows);
        summary.put("elapsedMillis", System.currentTimeMillis() - startedAt);
        root.put("summary", summary);

        JSONObject cfg = new JSONObject();
        cfg.put("maxJavaChars", config.maxJavaChars);
        cfg.put("maxCompileErrorChars", config.maxCompileErrorChars);
        cfg.put("maxMutantsPerGroup", config.maxMutantsPerGroup);
        cfg.put("compileErrorIndex", config.compileErrorIndex);
        cfg.put("fallbackToFirstAvailableError", config.fallbackToFirstAvailableError);
        cfg.put("collectGraphStatus", config.collectGraphStatus);
        cfg.put("collectOutputJsonStatus", config.collectOutputJsonStatus);
        cfg.put("collectCompactEvidenceStatus", config.collectCompactEvidenceStatus);
        root.put("config", cfg);

        JSONArray groupArray = new JSONArray();
        int id = 1;
        for (GroupBucket g : groups.values()) {
            groupArray.put(g.toJson(id++, config));
        }
        root.put("groups", groupArray);

        Path parent = outputJson.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(outputJson, root.toString(2).getBytes(StandardCharsets.UTF_8));

        System.out.println("==================================================");
        System.out.println("[GROUPED MINIMAL FAILURE COLLECTION SUMMARY]");
        System.out.println("inputExcel                       = " + inputExcel.toAbsolutePath().normalize());
        System.out.println("outputJson                       = " + outputJson.toAbsolutePath().normalize());
        System.out.println("totalRows                        = " + totalRows);
        System.out.println("validRows                        = " + validRows);
        System.out.println("groupCount                       = " + groups.size());
        System.out.println("javaExistsRows                   = " + javaExistsRows);
        System.out.println("selectedCompileErrorIndex        = " + config.compileErrorIndex);
        System.out.println("fallbackToFirstAvailableError    = " + config.fallbackToFirstAvailableError);
        System.out.println("selectedCompileErrorExistsRows   = " + selectedCompileErrorExistsRows);
        System.out.println("collectGraphStatus               = " + config.collectGraphStatus);
        System.out.println("collectOutputJsonStatus          = " + config.collectOutputJsonStatus);
        System.out.println("collectCompactEvidenceStatus     = " + config.collectCompactEvidenceStatus);
        System.out.println("elapsedMillis                    = " + (System.currentTimeMillis() - startedAt));
    }

    private static String buildGroupKey(String project, String targetClassName, String method) {
        return safe(project) + "||" + safe(targetClassName) + "||" + safe(method);
    }

    private static String resolveResultModuleHomeCachedByProject(String project,
                                                                 String rawFilePath,
                                                                 Map<String, String> cache) {
        String key = project == null ? "" : project.trim();

        if (!key.isEmpty()) {
            String cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        String cleaned = stripWindowsLongPrefix(rawFilePath);
        File file = new File(cleaned);
        String parent = file.getParent();

        if (parent == null) {
            throw new IllegalArgumentException("Cannot get parent from file_path: " + rawFilePath);
        }

        String filepath = parent
                .replace("\\", "/")
                .replace("//?/", "");

        String resultModuleHome = Paths.get(getCurr(filepath)).normalize().toString();

        if (!key.isEmpty()) {
            cache.put(key, resultModuleHome);
        }

        return resultModuleHome;
    }

    private static Path selectedCompileErrorFile(List<Path> compileErrorFiles,
                                                 int compileErrorIndex,
                                                 boolean fallbackToFirstAvailable) {
        if (compileErrorFiles == null || compileErrorFiles.isEmpty()) {
            return null;
        }

        String expectedSuffix = "__compile_error_" + compileErrorIndex + ".txt";

        for (Path p : compileErrorFiles) {
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (name.endsWith(expectedSuffix)) {
                return p;
            }
        }

        if (fallbackToFirstAvailable) {
            return compileErrorFiles.get(0);
        }

        return null;
    }

    private static Map<String, List<Path>> getOrBuildCompileErrorIndex(String resultModuleHome,
                                                                       Map<String, Map<String, List<Path>>> cache)
            throws Exception {
        Map<String, List<Path>> cached = cache.get(resultModuleHome);
        if (cached != null) {
            return cached;
        }

        Map<String, List<Path>> index = new HashMap<String, List<Path>>();

        Path reportRoot = Paths.get(resultModuleHome, MutationSystem.TESTSET_MODE_LLMS, "report")
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(reportRoot)) {
            cache.put(resultModuleHome, index);
            return index;
        }

        Files.walkFileTree(reportRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();

                String simple = extractSimpleTestClassNameFromCompileError(name);
                if (simple == null || simple.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }

                List<Path> list = index.get(simple);
                if (list == null) {
                    list = new ArrayList<Path>();
                    index.put(simple, list);
                }
                list.add(file.toAbsolutePath().normalize());

                return FileVisitResult.CONTINUE;
            }
        });

        for (List<Path> list : index.values()) {
            Collections.sort(list, new Comparator<Path>() {
                @Override
                public int compare(Path a, Path b) {
                    int ia = compileErrorOrder(a.getFileName().toString());
                    int ib = compileErrorOrder(b.getFileName().toString());
                    if (ia != ib) {
                        return Integer.compare(ia, ib);
                    }
                    return a.toString().compareTo(b.toString());
                }
            });
        }

        cache.put(resultModuleHome, index);
        System.out.println("[COMPILE-ERROR-INDEX] home=" + resultModuleHome
                + ", testCount=" + index.size());

        return index;
    }

    private static String extractSimpleTestClassNameFromCompileError(String fileName) {
        if (fileName == null) {
            return null;
        }

        String marker = "__compile_error_";
        int idx = fileName.indexOf(marker);
        if (idx > 0 && fileName.endsWith(".txt")) {
            return fileName.substring(0, idx);
        }

        String restore = "__restore_compile_error.txt";
        if (fileName.endsWith(restore)) {
            return fileName.substring(0, fileName.length() - restore.length());
        }

        return null;
    }

    private static int compileErrorOrder(String fileName) {
        if (fileName == null) {
            return Integer.MAX_VALUE;
        }

        String marker = "__compile_error_";
        int idx = fileName.indexOf(marker);
        if (idx < 0) {
            return Integer.MAX_VALUE - 1;
        }

        int start = idx + marker.length();
        int end = fileName.indexOf(".txt", start);
        if (end < 0) {
            end = fileName.length();
        }

        try {
            return Integer.parseInt(fileName.substring(start, end));
        } catch (Throwable ignored) {
            return Integer.MAX_VALUE - 2;
        }
    }

    private static Path locateCompactEvidenceCached(String resultModuleHome,
                                                    String simpleTestClassName,
                                                    Map<String, Map<String, Path>> cache) throws Exception {
        if (isBlank(resultModuleHome) || isBlank(simpleTestClassName)) {
            return null;
        }

        Map<String, Path> index = cache.get(resultModuleHome);
        if (index == null) {
            index = buildCompactEvidenceIndex(resultModuleHome);
            cache.put(resultModuleHome, index);
        }

        return index.get(simpleTestClassName);
    }

    private static Map<String, Path> buildCompactEvidenceIndex(String resultModuleHome) throws Exception {
        Map<String, Path> index = new HashMap<String, Path>();

        Path reportRoot = Paths.get(resultModuleHome, MutationSystem.TESTSET_MODE_LLMS, "report")
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(reportRoot)) {
            System.out.println("[COMPACT-EVIDENCE-INDEX] report root not found: " + reportRoot);
            return index;
        }

        final String suffix = "__compact_evidence.json";

        Files.walkFileTree(reportRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();

                if (name.endsWith(suffix)) {
                    String simple = name.substring(0, name.length() - suffix.length());
                    index.put(simple, file.toAbsolutePath().normalize());
                }

                return FileVisitResult.CONTINUE;
            }
        });

        System.out.println("[COMPACT-EVIDENCE-INDEX] home=" + resultModuleHome
                + ", count=" + index.size());

        return index;
    }

    private static Path locateOutputJsonCached(String rawFilePath,
                                               String rawMutantGraphPath,
                                               Map<String, Path> cache) {
        String key = safe(rawFilePath) + "||" + safe(rawMutantGraphPath);

        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        Path found = locateOutputJson(rawFilePath, rawMutantGraphPath);
        cache.put(key, found);
        return found;
    }

    private static Path locateOutputJson(String rawFilePath, String rawMutantGraphPath) {
        List<Path> candidates = new ArrayList<Path>();

        Path mutantDir = null;

        try {
            String cleanedFilePath = stripWindowsLongPrefix(rawFilePath);
            File mutantClassFile = new File(cleanedFilePath);
            File parent = mutantClassFile.getParentFile();
            if (parent != null) {
                mutantDir = parent.toPath().toAbsolutePath().normalize();
                candidates.add(mutantDir.resolve("output.json"));
                candidates.add(mutantDir.resolve("graph").resolve("output.json"));
            }
        } catch (Throwable ignored) {
        }

        try {
            String cleanedGraphPath = stripWindowsLongPrefix(rawMutantGraphPath);
            if (!isBlank(cleanedGraphPath)) {
                Path graphPath = Paths.get(cleanedGraphPath).toAbsolutePath().normalize();

                if (graphPath.getFileName() != null
                        && graphPath.getFileName().toString().equalsIgnoreCase("graph")) {
                    candidates.add(graphPath.resolve("output.json"));
                    if (graphPath.getParent() != null) {
                        candidates.add(graphPath.getParent().resolve("output.json"));
                    }
                } else {
                    candidates.add(graphPath.resolve("output.json"));
                    candidates.add(graphPath.resolve("graph").resolve("output.json"));
                }
            }
        } catch (Throwable ignored) {
        }

        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }

        return null;
    }

    private static int graphDirectoryExistsCached(String rawMutantGraphPath,
                                                  Map<String, Integer> cache) {
        String key = rawMutantGraphPath == null ? "" : rawMutantGraphPath.trim();

        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        int value = graphDirectoryExists(rawMutantGraphPath);
        cache.put(key, value);
        return value;
    }

    private static int graphDirectoryExists(String rawMutantGraphPath) {
        if (isBlank(rawMutantGraphPath)) {
            return 0;
        }

        try {
            String cleaned = stripWindowsLongPrefix(rawMutantGraphPath);
            Path p = Paths.get(cleaned).toAbsolutePath().normalize();

            if (Files.isDirectory(p)) {
                return 1;
            }

            if (p.getFileName() != null
                    && !p.getFileName().toString().equalsIgnoreCase("graph")) {
                Path graphChild = p.resolve("graph").toAbsolutePath().normalize();
                if (Files.isDirectory(graphChild)) {
                    return 1;
                }
            }

            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Payload readPayload(Path path, int maxChars) {
        JSONObject obj = new JSONObject();

        if (path == null) {
            obj.put("exists", false);
            obj.put("fileName", "");
            obj.put("text", JSONObject.NULL);
            obj.put("truncated", false);
            return new Payload(obj);
        }

        obj.put("fileName", path.getFileName() == null ? "" : path.getFileName().toString());

        if (!Files.isRegularFile(path)) {
            obj.put("exists", false);
            obj.put("text", JSONObject.NULL);
            obj.put("truncated", false);
            return new Payload(obj);
        }

        obj.put("exists", true);

        try {
            byte[] bytes = Files.readAllBytes(path);
            String text = decodeText(bytes);
            int originalChars = text.length();
            boolean truncated = false;

            if (maxChars > 0 && text.length() > maxChars) {
                text = text.substring(0, maxChars)
                        + "\n\n...[TRUNCATED " + (originalChars - maxChars) + " CHARS]...";
                truncated = true;
            }

            obj.put("text", text);
            obj.put("truncated", truncated);
            obj.put("sizeBytes", bytes.length);
            obj.put("originalChars", originalChars);
        } catch (Throwable t) {
            obj.put("text", JSONObject.NULL);
            obj.put("truncated", false);
            obj.put("readError", oneLine(t.getMessage()));
        }

        return new Payload(obj);
    }

    private static String decodeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return new String(bytes, Charset.defaultCharset());
        }
    }

    private static String cellString(Row row, int index, DataFormatter formatter) {
        if (row == null || index < 0) {
            return "";
        }

        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }

        String s = formatter.formatCellValue(cell);
        return s == null ? "" : s.trim();
    }

    private static String simpleNameOf(String fqn) {
        if (fqn == null) {
            return "";
        }

        int idx = fqn.lastIndexOf('.');
        if (idx < 0) {
            return fqn;
        }

        return fqn.substring(idx + 1);
    }

    private static String stripWindowsLongPrefix(String s) {
        if (s == null) {
            return "";
        }

        String v = s.trim();

        if (v.startsWith("\\\\?\\")) {
            return v.substring(4);
        }

        if (v.startsWith("//?/")) {
            return v.substring(4);
        }

        return v;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }

        return s.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static final class GroupBucket {
        String groupKey;
        String project;
        String targetClassName;
        String className;
        String classF;
        String method;

        int mutantCount;
        int javaExistsCount;
        int classExistsCount;
        int selectedCompileErrorExistsCount;
        int graphExistsCount;
        int graphMissingCount;

        MutantRecord representative;
        MutantRecord representativeWithJavaAndSelectedError;

        final List<MutantRecord> mutants = new ArrayList<MutantRecord>();

        void add(MutantRecord m, CollectorConfig config) {
            mutantCount++;

            if (m.javaExists) {
                javaExistsCount++;
            }
            if (m.classExists) {
                classExistsCount++;
            }
            if (m.selectedCompileErrorExists) {
                selectedCompileErrorExistsCount++;
            }
            if (m.graphExists == 1) {
                graphExistsCount++;
            } else {
                graphMissingCount++;
            }

            if (representative == null) {
                representative = m;
            }

            if (representativeWithJavaAndSelectedError == null
                    && m.javaExists
                    && m.selectedCompileErrorExists) {
                representativeWithJavaAndSelectedError = m;
            }

            if (mutants.size() < config.maxMutantsPerGroup) {
                mutants.add(m);
            }
        }

        JSONObject toJson(int id, CollectorConfig config) {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("groupKey", groupKey);
            obj.put("project", project);
            obj.put("targetClassName", targetClassName);
            obj.put("className", className);
            obj.put("classF", classF);
            obj.put("method", method);

            JSONObject stats = new JSONObject();
            stats.put("mutantCount", mutantCount);
            stats.put("javaExistsCount", javaExistsCount);
            stats.put("classExistsCount", classExistsCount);
            stats.put("selectedCompileErrorExistsCount", selectedCompileErrorExistsCount);
            stats.put("graphExistsCount", graphExistsCount);
            stats.put("graphMissingCount", graphMissingCount);
            obj.put("stats", stats);

            MutantRecord rep = representativeWithJavaAndSelectedError != null
                    ? representativeWithJavaAndSelectedError
                    : representative;

            JSONObject r = new JSONObject();
            if (rep != null) {
                r.put("operator", rep.operator);
                r.put("line", rep.line);
                r.put("mutationStatement", rep.mutationStatement);
                r.put("testSetName", rep.testSetName);
                r.put("simpleTestClassName", rep.simpleTestClassName);
                r.put("artifactStatus", rep.statusJson());
                r.put("javaSource", readPayload(rep.javaFile, config.maxJavaChars).json);
                r.put("selectedCompileErrorIndex", config.compileErrorIndex);
                r.put("selectedCompileError", readPayload(
                        rep.selectedCompileErrorFile,
                        config.maxCompileErrorChars
                ).json);
            }
            obj.put("representative", r);

            JSONArray ms = new JSONArray();
            for (MutantRecord m : mutants) {
                ms.put(m.summaryJson());
            }
            obj.put("mutants", ms);
            obj.put("omittedMutantCount", Math.max(0, mutantCount - mutants.size()));

            return obj;
        }
    }

    private static final class MutantRecord {
        int excelRowIndex;
        String operator;
        String line;
        String method;
        String className;
        String classF;
        String mutationStatement;
        String targetClassName;
        String project;
        String rawFilePath;
        String originalGraphPath;
        String mutantGraphPath;
        String isKilled;
        String testSetName;
        String simpleTestClassName;

        Path javaFile;
        Path expectedClassFile;
        Path outputJsonPath;
        Path compactEvidencePath;
        Path selectedCompileErrorFile;

        boolean javaExists;
        boolean classExists;
        boolean selectedCompileErrorExists;
        boolean outputJsonExists;
        boolean compactEvidenceExists;
        int graphExists;

        JSONObject summaryJson() {
            JSONObject obj = new JSONObject();
            obj.put("excelRowIndex", excelRowIndex);
            obj.put("operator", operator);
            obj.put("line", line);
            obj.put("mutationStatement", mutationStatement);
            obj.put("testSetName", testSetName);
            obj.put("simpleTestClassName", simpleTestClassName);
            obj.put("artifactStatus", statusJson());
            return obj;
        }

        JSONObject statusJson() {
            JSONObject obj = new JSONObject();
            obj.put("javaExists", javaExists);
            obj.put("classExists", classExists);
            obj.put("selectedCompileErrorExists", selectedCompileErrorExists);
            obj.put("outputJsonExists", outputJsonExists);
            obj.put("compactEvidenceExists", compactEvidenceExists);
            obj.put("mutantGraphExists", graphExists);
            return obj;
        }
    }

    private static final class Payload {
        final JSONObject json;

        Payload(JSONObject json) {
            this.json = json;
        }
    }

    private static final class CollectorConfig {
        int maxJavaChars;
        int maxCompileErrorChars;
        int maxMutantsPerGroup;

        int compileErrorIndex;
        boolean fallbackToFirstAvailableError;

        boolean collectGraphStatus;
        boolean collectOutputJsonStatus;
        boolean collectCompactEvidenceStatus;

        static CollectorConfig fromSystemProperties() {
            CollectorConfig c = new CollectorConfig();

            c.maxJavaChars = intProp("collector.max.java.chars", 12000);
            c.maxCompileErrorChars = intProp("collector.max.compileError.chars", 12000);
            c.maxMutantsPerGroup = intProp("collector.max.mutants.per.group", 0);

            c.compileErrorIndex = intProp("collector.compile.error.index", 1);
            c.fallbackToFirstAvailableError =
                    boolProp("collector.compile.error.fallback.first", false);

            c.collectGraphStatus =
                    boolProp("collector.collect.graph.status", true);
            c.collectOutputJsonStatus =
                    boolProp("collector.collect.outputJson.status", false);
            c.collectCompactEvidenceStatus =
                    boolProp("collector.collect.compactEvidence.status", false);

            return c;
        }

        private static int intProp(String key, int def) {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) {
                return def;
            }

            try {
                return Integer.parseInt(v.trim());
            } catch (Throwable ignored) {
                return def;
            }
        }

        private static boolean boolProp(String key, boolean def) {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) {
                return def;
            }

            return "true".equalsIgnoreCase(v.trim())
                    || "1".equals(v.trim())
                    || "yes".equalsIgnoreCase(v.trim())
                    || "y".equalsIgnoreCase(v.trim());
        }
    }
}