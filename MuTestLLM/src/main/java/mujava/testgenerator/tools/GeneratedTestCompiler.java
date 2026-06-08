package mujava.testgenerator.tools;

import mujava.util.LocalJarFinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Portable javac compilation support for generated JUnit4 tests.
 */
public final class GeneratedTestCompiler {
    private GeneratedTestCompiler() {
    }

    public static void compile(String sourceModuleHome,
                               Path javaFile,
                               Path outClassesDir,
                               String projectCp) throws Exception {
        Files.createDirectories(outClassesDir);
        String compileCp = buildCleanTestCompileClasspathPortable(projectCp, outClassesDir.toString());

        List<String> cmd = new ArrayList<String>();
        cmd.add(detectJavacBinary());
        cmd.add("-encoding");
        cmd.add("UTF-8");
        cmd.add("-J-Duser.language=en");
        cmd.add("-J-Duser.country=US");
        cmd.add("-J-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(compileCp);
        cmd.add("-d");
        cmd.add(outClassesDir.toAbsolutePath().normalize().toString());
        cmd.add(javaFile.toAbsolutePath().normalize().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(sourceModuleHome));
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String log;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(line);
            }
            log = sb.toString();
        }

        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("javac failed, exitCode=" + code
                    + "\n[compileCp]\n" + compileCp
                    + "\n[javac output]\n" + log);
        }
    }

    private static String buildCleanTestCompileClasspathPortable(String projectCp, String outClassesDir) throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<String>();
        addClasspathEntries(entries, projectCp);
        if (outClassesDir != null && !outClassesDir.trim().isEmpty()) {
            entries.add(Paths.get(outClassesDir).toAbsolutePath().normalize().toString());
        }

        String configPath = resolveMuJavaConfigPath();
        String mavenRepo = LocalJarFinder.getMaven(configPath, "MAVEN_REPOSITORY_HOME");
        if (mavenRepo == null || mavenRepo.trim().isEmpty()) {
            throw new FileNotFoundException("MAVEN_REPOSITORY_HOME not found in mujava.config: " + configPath);
        }

        String junitJar = buildJarPathFromMavenRepo(mavenRepo, "junit", "junit", "4.12");
        if (!Files.exists(Paths.get(junitJar))) {
            throw new FileNotFoundException("JUnit jar not found: " + junitJar);
        }
        entries.add(junitJar);

        String hamcrestJar = buildJarPathFromMavenRepo(mavenRepo, "org.hamcrest", "hamcrest-core", "1.3");
        if (Files.exists(Paths.get(hamcrestJar))) {
            entries.add(hamcrestJar);
        }

        return joinClasspath(entries);
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
                .toAbsolutePath().normalize().toString();
    }

    private static String buildJarPathFromMavenRepo(String repositoryPath,
                                                    String groupId,
                                                    String artifactId,
                                                    String version) {
        String groupPath = groupId.replace('.', File.separatorChar);
        return Paths.get(repositoryPath, groupPath, artifactId, version,
                        artifactId + "-" + version + ".jar")
                .toAbsolutePath().normalize().toString();
    }

    private static String detectJavacBinary() {
        String exeName = isWindows() ? "javac.exe" : "javac";
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && !envJavaHome.trim().isEmpty()) {
            Path p = Paths.get(envJavaHome, "bin", exeName);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            Path p1 = Paths.get(javaHome, "bin", exeName);
            if (Files.isRegularFile(p1)) {
                return p1.toAbsolutePath().normalize().toString();
            }
            Path home = Paths.get(javaHome).toAbsolutePath().normalize();
            Path fileName = home.getFileName();
            if (fileName != null && "jre".equalsIgnoreCase(fileName.toString())) {
                Path parent = home.getParent();
                if (parent != null) {
                    Path p2 = parent.resolve(Paths.get("bin", exeName));
                    if (Files.isRegularFile(p2)) {
                        return p2.toAbsolutePath().normalize().toString();
                    }
                }
            }
        }
        return exeName;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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

    private static String joinClasspath(Set<String> entries) {
        StringBuilder sb = new StringBuilder();
        for (String e : entries) {
            if (e == null || e.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(e.trim());
        }
        return sb.toString();
    }
}
