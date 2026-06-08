package mujava.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.io.File;
import java.util.LinkedHashSet;


public class LocalJarFinder {
    static final String config = resolveConfigPath();

    public static void main(String[] args) {
        String config = System.getProperty("user.dir") + "/config.config";
        String key = "MAVEN_REPOSITORY_HOME";
        String jsonResult = getFileDir(config, key);
    }

    private static String resolveConfigPath() {
        String p = System.getProperty("mujava.config.path");
        if (p != null && !p.trim().isEmpty()) {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        }

        p = System.getenv("MUJAVA_CONFIG");
        if (p != null && !p.trim().isEmpty()) {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        }

        return Paths.get(System.getProperty("user.dir"), "mujava.config")
                .toAbsolutePath().normalize().toString();
    }

    public static String getMaven(String maven, String key) {
        // 本地 Maven 仓库的路径
        String mavenRepositoryPath = "";
        try {
            BufferedReader reader = new BufferedReader(
                    new FileReader(maven));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key+"=")) {
                    mavenRepositoryPath = line.split("=", 2)[1].trim();
                    break;
                }
            }
        } catch (FileNotFoundException e1)
        {
            System.err.println("[ERROR] Can't find mujava.config file");
            e1.printStackTrace();
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return mavenRepositoryPath;
    }

    public static String getFileDir(String config, String key){
        // 本地 Maven 仓库的路径
        try
        {
            String mavenRepositoryPath = getMaven(config, key);

            assert mavenRepositoryPath != null;

            // JSON 格式
            String librariesJson = new String(Files.readAllBytes(Paths.get("libraries.json")), StandardCharsets.UTF_8);

            // 转换 JSON 字符串为 JSONArray
            JSONArray libraries = new JSONArray(librariesJson);

            // 输出 JSON 结果
            // System.out.println(jsonResult);
            return findJarFiles(mavenRepositoryPath, libraries);
        } catch (FileNotFoundException e1)
        {
            System.err.println("[ERROR] Can't find mujava.config file");
            e1.printStackTrace();
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    // 查找多个库的 .jar 文件并返回 JSON
    private static String findJarFiles(String repositoryPath, JSONArray libraries) throws Exception {
        JSONArray jarArray = new JSONArray();

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.getJSONObject(i);
            String groupId = library.getString("groupId");
            String artifactId = library.getString("artifactId");
            String version = library.getString("version");

            // 构建 .jar 文件的路径
            String jarFilePath = getJarFilePath(repositoryPath, groupId, artifactId, version);
            File jarFile = new File(jarFilePath);

            // 构建 JSON 对象
            JSONObject jarInfo = new JSONObject();
            jarInfo.put("groupId", groupId);
            jarInfo.put("artifactId", artifactId);
            jarInfo.put("version", version);
            if (jarFile.exists()) {
                jarInfo.put("jarPath", jarFile.getAbsolutePath());
            } else {
                jarInfo.put("jarPath", "[未找到]");
            }

            // 添加到数组
            jarArray.put(jarInfo);
        }

        // 返回 JSON 字符串
        return jarArray.toString(4);  // 4 表示缩进空格数
    }

    // 构建 .jar 文件的完整路径
    private static String getJarFilePath(String repositoryPath, String groupId, String artifactId, String version) {
        // 将 groupId 替换为路径格式（例如：org.apache.commons -> org/apache/commons）
        String groupPath = groupId.replace('.', '/');
        return repositoryPath + "/" + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    public static String buildMavenClasspathFromProject(String projectDir, String mvnCmd)
            throws IOException, InterruptedException {

        File project = new File(projectDir).getAbsoluteFile();

        Path parent = project.toPath().getParent();
        Path tmpDir;

        if (parent != null) {
            tmpDir = parent.resolve(".mujava_tmp").resolve("maven_cp")
                    .toAbsolutePath()
                    .normalize();
        } else {
            tmpDir = Paths.get(System.getProperty("java.io.tmpdir"),
                    "mujava_tmp",
                    "maven_cp"
            ).toAbsolutePath().normalize();
        }

        Files.createDirectories(tmpDir);

        Path tmpPath = Files.createTempFile(tmpDir, "maven_cp_", ".txt");

        try {
            List<String> cmd = Arrays.asList(
                    mvnCmd,
                    "-DskipTests",
                    "dependency:build-classpath",
                    "-Dmdep.includeScope=test",
                    "-Dmdep.pathSeparator=" + File.pathSeparator,
                    "-Dmdep.outputFile=" + tmpPath.toAbsolutePath().toString()
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(project);
            pb.redirectErrorStream(true);

            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                while (br.readLine() != null) {
                    // consume mvn output
                }
            }

            int code = p.waitFor();

            if (code != 0) {
                throw new IOException("mvn failed, exit=" + code);
            }

            if (!Files.isRegularFile(tmpPath)) {
                return "";
            }

            return new String(Files.readAllBytes(tmpPath), StandardCharsets.UTF_8).trim();

        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException e) {
                tmpPath.toFile().deleteOnExit();
            }
        }
    }
    public static String buildProjectClasspath(String systemHome) throws IOException {
        Path home = Paths.get(systemHome).normalize();
        StringBuilder cp = new StringBuilder();
        String sep = File.pathSeparator;

        if (isAntProject(home)) {
            appendPath(cp, buildAntClasspath(home), sep);

            // 如果同时也是 Maven 项目，Maven classpath 只作为补充
            if (isMavenProject(home)) {
                appendPath(cp, buildMavenStyleClasspath(home), sep);
            }
        } else {
            appendPath(cp, buildMavenStyleClasspath(home), sep);
        }

        return deduplicateClasspath(cp.toString());
    }

    private static boolean isMavenProject(Path home) {
        return Files.exists(home.resolve("pom.xml"));
    }

    private static boolean isAntProject(Path home) {
        return Files.exists(home.resolve("build.xml")) || Files.exists(home.resolve("build.bat"));
    }

    private static void appendPath(StringBuilder cp, String path, String sep) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        if (cp.length() > 0) {
            cp.append(sep);
        }
        cp.append(path.trim());
    }

    private static void appendIfDir(StringBuilder cp, Path dir, String sep) {
        if (dir != null && Files.isDirectory(dir)) {
            appendPath(cp, dir.toAbsolutePath().normalize().toString(), sep);
        }
    }

    private static void appendJarFiles(StringBuilder cp, Path dir, String sep) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        String jars = getJarFilesAsString(dir.toString());
        if (jars != null && !jars.trim().isEmpty()) {
            appendPath(cp, jars, sep);
        }
    }

    private static String buildMavenStyleClasspath(Path home) throws IOException {
        final String sep = File.pathSeparator;

        Path target = home.resolve("target");
        Path classesPath = target.resolve("classes");
        Path depDir = target.resolve("dependency");

        String jarsInTarget = getJarFilesAsString(target.toString());

        String mavenCp = "";
        try {
            String mavenHome = getMaven(config, "MAVEN_HOME");
            if (mavenHome == null || mavenHome.trim().isEmpty()) {
                throw new IOException("MAVEN_HOME not found in config: " + config);
            }

            String mvnCmd = Paths.get(mavenHome, "bin", "mvn.cmd").toString();
            mavenCp = buildMavenClasspathFromProject(home.toString(), mvnCmd);
        } catch (Exception e) {
            System.err.println("[WARN] buildProjectClasspath: failed to resolve Maven classpath, systemHome="
                    + home + ", config=" + config + ", reason=" + e.getMessage());
        }

        StringBuilder cp = new StringBuilder();

        appendIfDir(cp, classesPath, sep);

        if (Files.isDirectory(depDir)) {
            appendPath(cp, depDir.toAbsolutePath().normalize().toString() + File.separator + "*", sep);
        }

        appendPath(cp, jarsInTarget, sep);
        appendPath(cp, mavenCp, sep);

        return cp.toString();
    }

    private static String buildAntClasspath(Path home) throws IOException {
        final String sep = File.pathSeparator;

        StringBuilder cp = new StringBuilder();

        // 1) 编译后的主类
        appendIfDir(cp, home.resolve("build").resolve("classes"), sep);

        // 2) 如果有测试输出目录，也顺手加上
        appendIfDir(cp, home.resolve("build").resolve("testcases"), sep);
        appendIfDir(cp, home.resolve("build").resolve("test-classes"), sep);

        // 3) 依赖 jar：lib / build / dist 都扫一遍
        appendJarFiles(cp, home.resolve("lib"), sep);
        appendJarFiles(cp, home.resolve("build"), sep);
        appendJarFiles(cp, home.resolve("dist"), sep);
        return cp.toString();
    }

    public static String deduplicateClasspath(String cp) {
        if (cp == null || cp.trim().isEmpty()) {
            return "";
        }

        String sep = File.pathSeparator;
        String[] parts = cp.split(Pattern.quote(sep));
        Set<String> unique = new LinkedHashSet<>();

        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String s = part.trim();
            if (!s.isEmpty()) {
                unique.add(s);
            }
        }

        return String.join(sep, unique);
    }

    // 读取 cp.txt：有些情况下它可能是 1 行（用 ; 分隔），也可能被你打印成多行
    private static String readClasspathFile(Path cpTxt) throws IOException {
        if (!Files.exists(cpTxt)) return "";
        List<String> lines = Files.readAllLines(cpTxt, StandardCharsets.UTF_8);

        // 把所有行拼起来，再按分隔符/换行做归一
        String raw = String.join("\n", lines).trim();
        if (raw.isEmpty()) return "";

        // Windows 下 Maven 通常输出用 ';'，但你贴的像是“逐行打印”后的效果
        // 这里统一处理：把换行当成分隔符，再用系统分隔符拼回去
        String sep = File.pathSeparator;

        // 先把 \r\n / \n 替换成 sep
        raw = raw.replace("\r\n", "\n").replace("\n", sep);

        // 再把可能出现的重复分隔符合并
        raw = raw.replace(sep + sep, sep);

        // 去掉开头/结尾多余分隔符
        while (raw.startsWith(sep)) raw = raw.substring(1);
        while (raw.endsWith(sep)) raw = raw.substring(0, raw.length() - 1);

        return raw;
    }

    // 你现有的实现也可以，只要把 ";" 换成 File.pathSeparator 更稳
    public static String getJarFilesAsString(String targetDirectory) throws IOException {
        Pattern EXCLUDE_PATTERN = Pattern.compile(".*(sources|tests|test-sources|javadoc)\\.jar");
        StringBuilder jarFilesString = new StringBuilder();
        Path targetPath = Paths.get(targetDirectory).normalize();
        if (!Files.exists(targetPath)) {
            return "";
        }
        String sep = File.pathSeparator;

        Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fn = file.getFileName().toString();
                if (fn.endsWith(".jar") && !EXCLUDE_PATTERN.matcher(fn).matches()) {
                    if (jarFilesString.length() > 0) jarFilesString.append(sep);
                    jarFilesString.append(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return jarFilesString.toString();
    }
    public static String getJarFilesAsString1(String targetDirectory) throws IOException {
        Pattern EXCLUDE_PATTERN = Pattern.compile(".*(sources|tests|test-sources|javadoc)\\.jar");
        String sep = File.pathSeparator;

        Path targetPath = Paths.get(targetDirectory).normalize();
        if (!Files.exists(targetPath)) {
            return "";
        }
        List<String> jars = new ArrayList<>();

        Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fn = file.getFileName().toString();
                if (fn.endsWith(".jar") && !EXCLUDE_PATTERN.matcher(fn).matches()) {
                    jars.add(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        jars.sort(Comparator.naturalOrder());

        return String.join(sep, jars);
    }


    public static void collectMultiModuleOutputs(String sourceRootHome,
                                                 String currentModuleHome,
                                                 Set<String> classDirs,
                                                 Set<String> jarFiles,
                                                 Set<String> jarDirs) throws IOException {
        if (sourceRootHome == null || sourceRootHome.trim().isEmpty()) {
            return;
        }

        final Path root = Paths.get(sourceRootHome).normalize();
        if (!Files.exists(root)) {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (".git".equals(name) || ".idea".equals(name) || "result".equals(name)
                        || "evoSuite".equals(name) || "worker_runs".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                String normalized = dir.toString().replace('\\', '/');
                if (normalized.endsWith("/target/classes")) {
                    classDirs.add(dir.toAbsolutePath().toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (normalized.endsWith("/target/test-classes")) {
                    classDirs.add(dir.toAbsolutePath().toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (normalized.endsWith("/target/dependency")) {
                    jarDirs.add(dir.toAbsolutePath().toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString().toLowerCase();
                String normalized = file.toAbsolutePath().toString().replace('\\', '/');
                if (fileName.endsWith(".jar")
                        && !fileName.endsWith("-sources.jar")
                        && !fileName.endsWith("-javadoc.jar")
                        && !fileName.endsWith("-tests.jar")
                        && !fileName.endsWith("-test-sources.jar")
                        && normalized.contains("/target/")) {
                    jarFiles.add(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (currentModuleHome != null && !currentModuleHome.trim().isEmpty()) {
            Path currentClasses = Paths.get(currentModuleHome).normalize()
                    .resolve("target")
                    .resolve("classes");
            if (Files.isDirectory(currentClasses)) {
                String currentClassesPath = currentClasses.toAbsolutePath().toString();
                if (classDirs.remove(currentClassesPath)) {
                    // re-insert to keep current module classes near the front
                    java.util.LinkedHashSet<String> reordered = new java.util.LinkedHashSet<String>();
                    reordered.add(currentClassesPath);
                    reordered.addAll(classDirs);
                    classDirs.clear();
                    classDirs.addAll(reordered);
                }
            }
        }
    }

    public static void moveSrcClass(String javaFilePath, String targetDir) {

        File srcFolder = new File(javaFilePath);
        // 定义源文件路径和目标文件路径
        File targetFile = new File(targetDir, srcFolder.getName());

        try {
            Files.copy(srcFolder.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied: " + srcFolder.getName());
        } catch (IOException e) {
            System.err.println("Error copying file " + srcFolder.getName() + ": " + e.getMessage());
        }

        String classFilePath = classFind(javaFilePath);
        assert classFilePath != null;
        srcFolder = new File(classFilePath);
        // 定义源文件路径和目标文件路径
        targetFile = new File(targetDir, srcFolder.getName());

        try {
            // 复制文件
            Files.copy(srcFolder.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied: " + srcFolder.getName());
        } catch (IOException e) {
            System.err.println("Error copying file " + srcFolder.getName() + ": " + e.getMessage());
        }
    }

    public static String classFind(String javaFilePath) {
        // 你的项目的 target/classes 根目录
        String targetClassesDir = "target/classes";

        // 转换为相应的 class 文件路径
        String classFilePath = javaFilePath.replace("src/main/java", targetClassesDir)
                .replace(".java", ".class");

        // 输出对应的 .class 文件路径
        System.out.println("对应的 .class 文件路径: " + classFilePath);

        // 检查 .class 文件是否存在
        File classFile = new File(classFilePath);
        if (classFile.exists()) {
            System.out.println(".class 文件已生成： " + classFile.getAbsolutePath());
            return classFile.getAbsolutePath();
        } else {
            System.out.println(".class 文件未生成");
            return null;
        }
    }
}

