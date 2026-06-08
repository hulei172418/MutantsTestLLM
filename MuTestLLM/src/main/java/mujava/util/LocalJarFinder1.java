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
import java.util.regex.Pattern;

public class LocalJarFinder1 {
    static final String config =  System.getProperty("user.dir") + "/mujava.config";

    public static void main(String[] args) {
        String config = System.getProperty("user.dir") + "/config.config";
        String key = "MAVEN_REPOSITORY_HOME";
        String jsonResult = getFileDir(config, key);
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

        File dir = new File(projectDir);
        File tmp = File.createTempFile("maven_cp_", ".txt", dir);
        tmp.deleteOnExit(); // 兜底：JVM 退出时再删一次
        Path tmpPath = tmp.toPath();

        try {
            List<String> cmd = Arrays.asList(
                    mvnCmd,
                    "-DskipTests",
                    "dependency:build-classpath",
                    "-Dmdep.includeScope=compile",
                    "-Dmdep.pathSeparator=" + File.pathSeparator,
                    "-Dmdep.outputFile=" + tmp.getAbsolutePath()
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                while (br.readLine() != null) {
                    // ignore
                }
            }

            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("mvn failed, exit=" + code);
            }

            return new String(Files.readAllBytes(tmpPath), StandardCharsets.UTF_8).trim();

        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException e) {
                System.err.println("Failed to delete temp file: " + tmp.getAbsolutePath());
                e.printStackTrace();
            }
        }
    }
    public static String buildProjectClasspath(String systemHome) throws IOException {
        final String sep = File.pathSeparator;

        Path home = Paths.get(systemHome).normalize();
        Path target = home.resolve("target");

        Path classesPath = target.resolve("classes");
        String classesDir = classesPath.toString();

        // 依赖目录（如果你跑过 dependency:copy-dependencies）
        Path depDir = target.resolve("dependency");
        String depsWildcard = depDir.toString() + File.separator + "*";

        // 你已有：扫描 target 下 jar（可选）
        // 注意：很多实现希望传入以分隔符结尾的目录
        String jarsInTarget = getJarFilesAsString(target.toString() + File.separator);

        String mavenCp;
        try {
            String mvnCmd = getMaven(config, "MAVEN_HOME")+"/bin/mvn.cmd";
            mavenCp = buildMavenClasspathFromProject(systemHome, mvnCmd);
        } catch (Exception e) {
            // 动态获取失败就降级为空，避免影响整体流程
            mavenCp = "";
        }
        StringBuilder cp = new StringBuilder();

        // 1) classes 优先（如果不存在也不强行加，避免无效路径）
        if (Files.isDirectory(classesPath)) {
            cp.append(classesDir);
        }

        // 2) target/dependency/*（存在才加）
        if (Files.isDirectory(depDir)) {
            if (cp.length() > 0) cp.append(sep);
            cp.append(depsWildcard);
        }

        // 3) target 下扫描到的 jar（非空才加）
        String t = jarsInTarget.trim();
        if (!t.isEmpty()) {
            if (cp.length() > 0) cp.append(sep);
            cp.append(t);
        }

        // 4) cp.txt 里的 Maven 依赖 classpath（非空才加）
        String t1 = mavenCp.trim();
        if (!t1.isEmpty()) {
            if (cp.length() > 0) cp.append(sep);
            cp.append(t1);
        }

        return cp.toString();
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

