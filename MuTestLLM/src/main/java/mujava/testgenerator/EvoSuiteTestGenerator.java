package mujava.testgenerator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.*;
import java.nio.file.*;
import java.util.stream.*;
import java.math.BigDecimal;

import static mujava.util.ExcelUtils.exportFailedCompileToExcel;
import static mujava.util.ExcelUtils.readExcel;
import static mujava.util.LocalJarFinder.buildProjectClasspath;
import static mujava.util.LocalJarFinder.getMaven;

public class EvoSuiteTestGenerator {
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
    private static String graphPath;
    private static boolean rewrite = true; // 是否对已存在的graph进行重写覆盖
    private static String JimplePath;
    private static String logType;
    private static final Set<String> classSet = new HashSet<>();
    private static final Set<String> buildSet = new HashSet<>();
    private static final Map<String, String> projectKV = new HashMap<>();
    private static final String evosuiteJarPath = "./lib/evosuite-master-1.0.6.jar";
    private static String workDir;
    private static String cp;
    private static String targetCls;

    private static boolean logAppend = false;

    public static void main(String[] args) throws Exception {
        String mutantPath = "../MutantParse/data/commons-cli-1.11.0_oot.xlsx";
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
            lineNo = asStableString(excelData.get(i).get(1));
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
            workDir = workDir+"/"+projectName;
            if (projectKV.containsKey(projectName)) {
                cp = projectKV.get(projectName);
            } else {
                cp = buildProjectClasspath(workDir);
                projectKV.put(projectName, cp);
            }
            System.out.println(workDir+"/src/"+packageName.replace(".", "/"));
            evoSuiteTestGenerator();
            // runCompileTestsBatch();
        }
    }

    public static String asStableString(Object v) {
        if (v == null) {
            return "";
        }

        if (v instanceof String) {
            return ((String) v).trim();
        }

        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return String.valueOf(v);
        }

        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }

        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }

        if (v instanceof Boolean) {
            return String.valueOf(v);
        }

        return String.valueOf(v).trim();
    }

    public static void evoSuiteTestGenerator() throws Exception {
        // 1) 工作目录：相当于 cmd 的 `cd ...`

        // 2) java.exe 路径（如果你想强制用某个 JDK，就写绝对路径）
        String javaExe = "java";

        // 3) EvoSuite jar 路径
        String absEvosuiteJarPath = Paths.get(evosuiteJarPath).toAbsolutePath().normalize().toString();

        // 4) 组装命令参数（注意：不需要 ^，每个参数一个 list 元素）
        List<String> cmd = new ArrayList<>();
        String reDot = (projectName.contains("commons-math") || projectName.contains("commons-numbers")) ? "/../../" : "/../";
        String testDir = workDir+reDot+"evoSuite/evoSuite/tests";
        String reportDir = workDir+reDot+"evoSuite/evoSuite/report";

        cmd.add(javaExe);
        cmd.add("-jar");
        cmd.add(absEvosuiteJarPath);
        cmd.add("-generateSuite");
        cmd.add("-class");
        cmd.add(targetCls);
        cmd.add("-projectCP");
        cmd.add(cp);
        cmd.add("-Dtest_dir="+testDir);
        cmd.add("-Dreport_dir="+reportDir);
        cmd.add("-Dstopping_condition=MaxTime");
        cmd.add("-Dsearch_budget=60");
        cmd.add("-Dmax_size=20");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workDir));

        //
        pb.redirectErrorStream(true);

        Process p = pb.start();

        //
        Charset cs = Charset.defaultCharset();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), cs))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exit = p.waitFor();
        System.out.println("\n[EvoSuite] Exit code = " + exit);
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

    public void test() throws Exception {
        // 1) 工作目录：相当于 cmd 的 `cd ...`
        File workDir = new File("E:\\PHD\\testJava\\commons-lang3-3.17.0-src");

        // 2) java.exe 路径（如果你想强制用某个 JDK，就写绝对路径）
        //    如果不想写死，就改成 "java"
        String javaExe = "java";
        // String javaExe = "D:\\software\\java\\java-8u202\\jdk\\bin\\java.exe";

        // 3) EvoSuite jar 路径
        String evosuiteJar = "D:\\software\\apache-maven\\maven-repository-3.8.1\\org\\evosuite\\evosuite-master\\1.0.6\\evosuite-master-1.0.6.jar";

        // 4) 组装命令参数（注意：不需要 ^，每个参数一个 list 元素）
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-jar");
        cmd.add(evosuiteJar);
        cmd.add("-generateSuite");
        cmd.add("-class");
        cmd.add("org.apache.commons.lang3.ArrayFill");
        cmd.add("-projectCP");
        cmd.add("target\\classes");       // 等价于你写的 "target\\classes"
        cmd.add("-Dsearch_budget=30");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);

        // 合并 stdout/stderr，方便你看到完整输出
        pb.redirectErrorStream(true);

        Process p = pb.start();

        // Windows 中文控制台可能是 GBK；不确定就用系统默认
        Charset cs = Charset.defaultCharset();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), cs))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exit = p.waitFor();
        System.out.println("\n[EvoSuite] Exit code = " + exit);
    }

    public static void runCompileTestsBatch() throws Exception {
        // String reDot = (projectName.contains("commons-math") || projectName.contains("commons-numbers")) ? "/../../" : "/../";

        String javaExe = "java.exe";
        // String testSrcDir = workDir + reDot + projectName + "/" + "evoSuite/clean";
        // String outDir     = workDir + reDot + projectName + "/" + "evoSuite/classes";
        boolean isMulti = projectName.contains("commons-math") || projectName.contains("commons-numbers");
        String reDot = isMulti ? "/../../" : "/../";
        String baseDir = isMulti ? workDir + reDot + projectName + "/" : workDir + reDot;
        String testSrcDir = baseDir + "evoSuite/clean";
        String outDir     = baseDir + "evoSuite/classes";

        compileGeneratedTestsBatch(javaExe, workDir, testSrcDir, outDir, cp);
    }

    /** 单个执行：传入某个具体 java 文件绝对路径或相对路径 */
    public static void runCompileSingleTest(String javaFile) throws Exception {
        String reDot = (projectName.contains("commons-math") || projectName.contains("commons-numbers")) ? "/../../" : "/../";

        String javaExe = "java.exe";
        String outDir  = workDir + reDot + "evoSuite/classes";

        compileGeneratedTestSingle(javaExe, workDir, javaFile, outDir, cp);
    }

    private static String resolveMuJavaConfigPath() {
        String p = System.getProperty("mujava.config.path");
        if (p != null && !p.trim().isEmpty()) {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        }

        p = System.getenv("MUJAVA_CONFIG");
        if (p != null && !p.trim().isEmpty()) {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        }

        return Paths.get(System.getProperty("user.dir"), "mujava.config")
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private static String buildJarPathFromMavenRepo(String repositoryPath,
                                                    String groupId,
                                                    String artifactId,
                                                    String version) {
        String groupPath = groupId.replace('.', File.separatorChar);
        return Paths.get(repositoryPath, groupPath, artifactId, version,
                        artifactId + "-" + version + ".jar")
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private static String buildCleanTestCompileClasspath(String projectCp, String outClassesDir) throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<>();

        addClasspathEntries(entries, projectCp);

        // 让已经编出来的测试类彼此可见
        if (outClassesDir != null && !outClassesDir.trim().isEmpty()) {
            entries.add(Paths.get(outClassesDir).toAbsolutePath().normalize().toString());
        }

        String configPath = resolveMuJavaConfigPath();
        String mavenRepositoryPath = getMaven(configPath, "MAVEN_REPOSITORY_HOME");
        if (mavenRepositoryPath == null || mavenRepositoryPath.trim().isEmpty()) {
            throw new FileNotFoundException(
                    "MAVEN_REPOSITORY_HOME not found in mujava.config: " + configPath
            );
        }

        String junitJar = buildJarPathFromMavenRepo(
                mavenRepositoryPath, "junit", "junit", "4.12"
        );
        if (!Files.exists(Paths.get(junitJar))) {
            throw new FileNotFoundException("JUnit jar not found: " + junitJar);
        }
        entries.add(junitJar);

        String hamcrestJar = buildJarPathFromMavenRepo(
                mavenRepositoryPath, "org.hamcrest", "hamcrest-core", "1.3"
        );
        if (Files.exists(Paths.get(hamcrestJar))) {
            entries.add(hamcrestJar);
        }

        return String.join(File.pathSeparator, entries);
    }

    private static void addClasspathEntries(Set<String> entries, String cp) {
        if (cp == null || cp.trim().isEmpty()) {
            return;
        }

        String[] parts = cp.split(Pattern.quote(File.pathSeparator));
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String s = part.trim();
            if (!s.isEmpty()) {
                entries.add(s);
            }
        }
    }

    /** 批量编译：递归收集 testSrcDir 下所有 .java，并逐个编译 */
    public static void compileGeneratedTestsBatch(
            String javaExe,
            String workDir,
            String testSrcDir,
            String outClassesDir,
            String projectCp
    ) throws Exception {

        List<String> sources;
        try (Stream<Path> s = Files.walk(Paths.get(testSrcDir))) {
            sources = s.map(Path::toString)
                    .filter(p -> p.endsWith(".java"))
                    .collect(Collectors.toList());
        }
        if (sources.isEmpty()) {
            throw new FileNotFoundException("No .java files under: " + testSrcDir);
        }

        Files.createDirectories(Paths.get(outClassesDir));

        int ok = 0;
        List<String> failed = new ArrayList<>();

        for (String src : sources) {
            try {
                if (buildSet.contains(src))
                    continue;
                compileGeneratedTestSingle(javaExe, workDir, src, outClassesDir, projectCp);
                System.out.println(src);
                buildSet.add(src);
                ok++;
            } catch (Exception e) {
                failed.add(src);
                System.err.println("========== javac FAILED ==========");
                System.err.println("File: " + src);
                System.err.println(e.getMessage());
                System.err.println("=================================");
                // 若你想遇到第一个失败就停，打开下一行：
                // throw e;
                buildSet.add(src);
            }
        }

        exportFailedCompileToExcel(failed, testSrcDir+"/..");
        System.out.println("[DONE] total=" + sources.size() + ", ok=" + ok + ", failed=" + failed.size());
        if (!failed.isEmpty()) {
            System.out.println("Failed files:");
            for (String f : failed) System.out.println("  " + f);
            throw new RuntimeException("Some test files failed to compile, see logs above.");
        }
    }

    /** 单文件编译：只编译一个指定的 .java */
    public static void compileGeneratedTestSingle(
            String javaExe,
            String workDir,
            String javaFile,
            String outClassesDir,
            String projectCp
    ) throws Exception {

        Path srcPath = Paths.get(javaFile);
        if (!Files.exists(srcPath) || !javaFile.endsWith(".java")) {
            throw new FileNotFoundException("Java source not found or not a .java file: " + javaFile);
        }

        Files.createDirectories(Paths.get(outClassesDir));

        String javacExe = javaExe.replace("java.exe", "javac.exe");
        String compileCp = buildCleanTestCompileClasspath(projectCp, outClassesDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(javacExe);
        cmd.add("-encoding");
        cmd.add("UTF-8");
        cmd.add("-cp");
        cmd.add(compileCp);
        cmd.add("-d");
        cmd.add(outClassesDir);
        cmd.add(javaFile);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String log;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            log = br.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException(
                    "javac failed, exitCode=" + code +
                            "\n[compileCp]\n" + compileCp +
                            "\n[javac output]\n" + log
            );
        }
    }

    public static void runCompileTestsBatch1() throws Exception {
        String reDot = (projectName.contains("commons-math") || projectName.contains("commons-numbers")) ? "/../../" : "/../";

        String javaExe = "java.exe";
        String testSrcDir = workDir+reDot+"evoSuite/clean";
        String outDir = workDir+reDot+"evoSuite/classes";
        compileGeneratedTests1(
                javaExe,
                workDir,
                testSrcDir,
                outDir,
                cp
        );

    }

    public static void compileGeneratedTests1(
            String javaExe,
            String workDir,
            String testSrcDir,
            String outClassesDir,
            String projectCp
    ) throws Exception {

        // 1) 收集所有 .java（递归）
        List<String> sources;
        try (Stream<Path> s = Files.walk(Paths.get(testSrcDir))) {
            sources = s.map(Path::toString)
                    .filter(p -> p.endsWith(".java"))
                    .collect(Collectors.toList());
        }
        if (sources.isEmpty()) {
            throw new FileNotFoundException("No .java files under: " + testSrcDir);
        }

        // 2) 创建输出目录
        Files.createDirectories(Paths.get(outClassesDir));

        // 3) javac 路径
        String javacExe = javaExe.replace("java.exe", "javac.exe");

        // 4) 逐个编译
        int ok = 0;
        List<String> failed = new ArrayList<>();

        for (String src : sources) {
            List<String> cmd = new ArrayList<>();
            cmd.add(javacExe);
            cmd.add("-encoding"); cmd.add("UTF-8");
            cmd.add("-cp");       cmd.add(projectCp);
            cmd.add("-d");        cmd.add(outClassesDir);
            cmd.add(src);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            Process p = pb.start();
            String log;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                log = br.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            int code = p.waitFor();

            if (code == 0) {
                ok++;
            } else {
                failed.add(src);
                System.err.println("========== javac FAILED ==========");
                System.err.println("File: " + src);
                System.err.println("Exit: " + code);
                System.err.println(log);
                System.err.println("=================================");
                // 你想“遇到第一个失败就停止”，就取消下面这行注释：
                // throw new RuntimeException("javac failed on: " + src + "\n" + log);
            }
        }

        System.out.println("[DONE] total=" + sources.size() + ", ok=" + ok + ", failed=" + failed.size());
        if (!failed.isEmpty()) {
            System.out.println("Failed files:");
            for (String f : failed) System.out.println("  " + f);
            throw new RuntimeException("Some test files failed to compile, see logs above.");
        }

    }
}
