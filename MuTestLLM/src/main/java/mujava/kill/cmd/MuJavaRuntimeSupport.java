
package mujava.kill.cmd;

import mujava.MutationSystem;
import mujava.util.LocalJarFinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public final class MuJavaRuntimeSupport {

    private MuJavaRuntimeSupport() {}

    private static final Map<String, ClasspathSnapshot> CLASSPATH_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, ClasspathSnapshot>());

    private static final String CLASSPATH_OWNER_KEY = "mujava.classpath.owner.key";


    public static void initializeProject(String projectHome, String targetClassName) throws IOException {
        String resultHome = System.getProperty("mujava.result.module.home");
        initializeProject(projectHome, resultHome, targetClassName);
    }

    public static void initializeProject(String sourceModuleHome,
                                         String resultModuleHome,
                                         String targetClassName) throws IOException {

        if (hasText(resultModuleHome)) {
            System.setProperty("mujava.result.module.home", resultModuleHome);
        } else {
            System.clearProperty("mujava.result.module.home");
        }

        if (sourceModuleHome != null && !sourceModuleHome.trim().isEmpty()) {
            MutationSystem.setJMutationStructure(sourceModuleHome, resultModuleHome);
        } else {
            MutationSystem.setJMutationStructure();
        }

        configureMuJavaClasspath();
        initializeTargetPaths(targetClassName);
    }

    public static void initializeTargetPaths(String targetClassName) {
        int index = targetClassName.lastIndexOf('.');
        if (index < 0) {
            MutationSystem.CLASS_NAME = targetClassName;
        } else {
            MutationSystem.CLASS_NAME = targetClassName.substring(index + 1);
        }

        MutationSystem.DIR_NAME = targetClassName;
        MutationSystem.CLASS_MUTANT_PATH = MutationSystem.MUTANT_HOME + File.separator + targetClassName
                + File.separator + MutationSystem.CM_DIR_NAME;
        MutationSystem.TRADITIONAL_MUTANT_PATH = MutationSystem.MUTANT_HOME + File.separator + targetClassName
                + File.separator + MutationSystem.TM_DIR_NAME;
        MutationSystem.EXCEPTION_MUTANT_PATH = MutationSystem.MUTANT_HOME + File.separator + targetClassName
                + File.separator + MutationSystem.EM_DIR_NAME;
    }

    private static void addExistingPathList(Set<String> set, String pathList) {
        if (pathList == null || pathList.trim().isEmpty()) return;

        String[] arr = pathList.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String item : arr) {
            if (item == null) continue;
            item = item.trim();
            if (item.isEmpty()) continue;
            set.add(new File(item).getAbsolutePath());
        }
    }

    public static void configureMuJavaClasspath() throws IOException {
        String sep = File.pathSeparator;
        String cacheKey = safe(MutationSystem.SYSTEM_HOME) + "||"
                + safe(System.getProperty("mujava.result.module.home")) + "||"
                + testSetMode();

        ClasspathSnapshot cached = CLASSPATH_CACHE.get(cacheKey);
        if (cached != null) {
            applySnapshot(cached);
            return;
        }

        String ownerKey = System.getProperty(CLASSPATH_OWNER_KEY, "");
        boolean reuseExistingProperties = cacheKey.equals(ownerKey);

        if (!reuseExistingProperties) {
            System.clearProperty("mujava.class.dirs");
            System.clearProperty("mujava.jar.files");
            System.clearProperty("mujava.jar.dirs");
        }

        boolean hasPrecomputed = reuseExistingProperties &&
                (hasText(System.getProperty("mujava.class.dirs")) ||
                        hasText(System.getProperty("mujava.jar.files")) ||
                        hasText(System.getProperty("mujava.jar.dirs")));

        String cp = "";
        if (!hasPrecomputed) {
            cp = LocalJarFinder.buildProjectClasspath(MutationSystem.SYSTEM_HOME);
        } else {
            System.out.println("[TIMER] buildProjectClasspath = skipped (use precomputed mujava.* properties)");
        }

        LinkedHashSet<String> classDirs = new LinkedHashSet<String>();
        LinkedHashSet<String> jarFiles = new LinkedHashSet<String>();
        LinkedHashSet<String> jarDirs = new LinkedHashSet<String>();

        addExistingPathList(classDirs, System.getProperty("mujava.class.dirs"));
        addExistingPathList(jarFiles, System.getProperty("mujava.jar.files"));
        addExistingPathList(jarDirs, System.getProperty("mujava.jar.dirs"));

        addIfDir(classDirs, MutationSystem.CLASS_PATH);
        for (String root : resolveTestRoots()) {
            addIfDir(classDirs, root);
        }

        if (cp != null && !cp.trim().isEmpty()) {
            String[] entries = cp.split(java.util.regex.Pattern.quote(sep));
            for (String raw : entries) {
                if (raw == null) continue;
                String entry = raw.trim();
                if (entry.isEmpty()) continue;

                if (entry.endsWith(File.separator + "*") || entry.endsWith("/*") || entry.endsWith("\\*")) {
                    File parent = new File(entry).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        jarDirs.add(parent.getAbsolutePath());
                    }
                    continue;
                }

                File f = new File(entry);

                if (f.isDirectory()) {
                    if (isAnyTestOutputDir(f) && !isSelectedTestOutputDir(f)) {
                        System.out.println("[CP-FILTER] skip non-selected test dir from project cp: " + f.getAbsolutePath());
                        continue;
                    }
                    classDirs.add(f.getAbsolutePath());
                } else if (f.isFile() && entry.toLowerCase().endsWith(".jar")) {
                    jarFiles.add(f.getAbsolutePath());
                }
            }
        }

        collectAllTargetClasses(MutationSystem.SYSTEM_HOME, classDirs);

        if (!classDirs.isEmpty()) {
            System.setProperty("mujava.class.dirs", join(classDirs, sep));
        }
        if (!jarFiles.isEmpty()) {
            System.setProperty("mujava.jar.files", join(jarFiles, sep));
        }
        if (!jarDirs.isEmpty()) {
            System.setProperty("mujava.jar.dirs", join(jarDirs, sep));
        }

        System.setProperty(CLASSPATH_OWNER_KEY, cacheKey);

        ClasspathSnapshot snapshot = new ClasspathSnapshot();
        snapshot.classDirs = System.getProperty("mujava.class.dirs", "");
        snapshot.jarFiles = System.getProperty("mujava.jar.files", "");
        snapshot.jarDirs = System.getProperty("mujava.jar.dirs", "");
        snapshot.ownerKey = cacheKey;
        CLASSPATH_CACHE.put(cacheKey, snapshot);
    }


    private static void applySnapshot(ClasspathSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        setOrClear("mujava.class.dirs", snapshot.classDirs);
        setOrClear("mujava.jar.files", snapshot.jarFiles);
        setOrClear("mujava.jar.dirs", snapshot.jarDirs);
        setOrClear(CLASSPATH_OWNER_KEY, snapshot.ownerKey);
    }

    private static void setOrClear(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String testSetMode() {
        return MutationSystem.getTestSetMode();
    }

    private static String normPath(File f) {
        return f.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean isAnyTestOutputDir(File f) {
        String p = normPath(f);
        if (p.endsWith("/target/test-classes")
                || p.contains("/target/test-classes/")
                || p.endsWith("/testset")
                || p.contains("/testset/")) {
            return true;
        }
        return isManagedResultTestClassesDir(f);
    }

    private static boolean isManagedResultTestClassesDir(File f) {
        String resultHome = System.getProperty("mujava.result.module.home");
        if (!hasText(resultHome)) {
            return false;
        }

        Path resultRoot = Paths.get(resultHome).toAbsolutePath().normalize();
        Path dir = f.toPath().toAbsolutePath().normalize();
        if (!dir.startsWith(resultRoot)) {
            return false;
        }

        String p = normPath(f);
        if (p.endsWith("/origin/target/test-classes")
                || p.contains("/origin/target/test-classes/")) {
            return true;
        }

        Path rel = resultRoot.relativize(dir);
        return rel.getNameCount() == 2
                && "classes".equalsIgnoreCase(rel.getName(1).toString());
    }

    private static boolean isSelectedTestOutputDir(File f) {
        String p = normPath(f);
        for (String root : resolveTestRoots()) {
            String rp = new File(root).getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (p.equals(rp) || p.startsWith(rp + "/")) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public static String buildTestSetName(String sourceModuleHome,
                                          String resultModuleHome,
                                          String targetClassName) throws IOException {
        List<String> names = resolveTestSetNames(sourceModuleHome, resultModuleHome, targetClassName);
        if (!names.isEmpty()) {
            return names.get(0);
        }

        String realFqn = normalizeTargetClassName(targetClassName);
        return realFqn + "Test";
    }

    public static String buildTestSetName(String targetClassName) {
        try {
            List<String> names = resolveTestSetNames(targetClassName);
            if (!names.isEmpty()) {
                return names.get(0);
            }
        } catch (IOException ignored) {
        }

        String realFqn = normalizeTargetClassName(targetClassName);
        return realFqn + "Test";
    }

    public static List<String> resolveTestSetNames(String sourceModuleHome,
                                                   String resultModuleHome,
                                                   String targetClassName) throws IOException {
        initializeProject(sourceModuleHome, resultModuleHome, targetClassName);
        return resolveTestSetNames(targetClassName);
    }

    public static List<String> resolveTestSetNames(String targetClassName) throws IOException {
        String realFqn = normalizeTargetClassName(targetClassName);
        String pkg = packageName(realFqn);
        String simple = simpleName(realFqn);

        LinkedHashSet<String> result = new LinkedHashSet<String>();

        // 1. 显式映射优先
        addMappedTests(result, realFqn);

        // 2. 固定命名优先
        addCandidateNames(result, pkg, simple);

        // 3. 扫描 class 文件，严格匹配
        addScannedTests(result, pkg, simple);

        // 4. 只保留真实存在的测试类
        List<String> existing = new ArrayList<String>();
        for (String c : result) {
            if (testClassExists(c)) {
                existing.add(c);
            }
        }
        return existing;
    }

    public static List<String> resolveTestSetNames1(String targetClassName) throws IOException {
        String realFqn = normalizeTargetClassName(targetClassName);
        String pkg = packageName(realFqn);
        String simple = simpleName(realFqn);

        LinkedHashSet<String> result = new LinkedHashSet<String>();

        addMappedTests(result, realFqn);

        addCandidateNames(result, pkg, simple);
        addScannedTests(result, pkg, simple);

        List<String> existing = new ArrayList<String>();
        for (String c : result) {
            if (testClassExists(c)) {
                existing.add(c);
            }
        }
        return existing;
    }

    private static String normalizeTargetClassName(String targetClassName) {
        return targetClassName.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "");
    }

    private static void addMappedTests(Set<String> out, String targetFqn) throws IOException {
        Path[] candidates = new Path[] {
                getOptionalPath(System.getProperty("mujava.test.mapping")),
                Paths.get("test-mapping.properties").toAbsolutePath().normalize(),
                Paths.get("test_mapping.properties").toAbsolutePath().normalize()
        };

        for (Path mapping : candidates) {
            if (mapping == null || !Files.isRegularFile(mapping)) {
                continue;
            }
            Properties p = new Properties();
            InputStream in = null;
            try {
                in = new FileInputStream(mapping.toFile());
                p.load(in);
            } finally {
                if (in != null) in.close();
            }

            String v = p.getProperty(targetFqn);
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            for (String s : v.split("[,;]")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
    }

    private static Path getOptionalPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private static void addCandidateNames(Set<String> out, String pkg, String simple) {
        if (!pkg.isEmpty()) {
            out.add(pkg + "." + simple + "Test");
            out.add(pkg + ".Test" + simple);
            out.add(pkg + "." + simple + "TestCase");
            out.add(pkg + "." + simple + "Tests");
        } else {
            out.add(simple + "Test");
            out.add("Test" + simple);
            out.add(simple + "TestCase");
            out.add(simple + "Tests");
        }
    }

    private static void addScannedTests(Set<String> out, String pkg, String simple) throws IOException {
        LinkedHashSet<String> allTests = discoverAllTestClasses();
        if (allTests.isEmpty()) {
            return;
        }

        String c1 = qualifiedName(pkg, simple + "Test");
        String c2 = qualifiedName(pkg, "Test" + simple);
        String c3 = qualifiedName(pkg, simple + "TestCase");
        String c4 = qualifiedName(pkg, simple + "Tests");

        // 只做严格命名匹配
        addIfPresent(out, allTests, c1);
        addIfPresent(out, allTests, c2);
        addIfPresent(out, allTests, c3);
        addIfPresent(out, allTests, c4);
    }

    private static LinkedHashSet<String> discoverAllTestClasses() throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<String>();

        for (String rootStr : resolveTestRoots()) {
            Path root = Paths.get(rootStr).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;

            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().contains("$"))
                        .forEach(p -> {
                            String className = toClassName(root, p);
                            if (looksLikeTestClass(className)) {
                                result.add(className);
                            }
                        });
            }
        }

        return result;
    }

    private static List<String> resolveTestRoots() {
        LinkedHashSet<String> roots = new LinkedHashSet<String>();

        String resultHome = System.getProperty("mujava.result.module.home");
        String mode = testSetMode();

        if (MutationSystem.TESTSET_MODE_ORIGIN.equalsIgnoreCase(mode)) {
            if (hasText(resultHome)) {
                addIfDir(roots, Paths.get(resultHome, "origin", "target", "test-classes").toString());
            }
            if (hasText(MutationSystem.SYSTEM_HOME)) {
                addIfDir(roots, Paths.get(MutationSystem.SYSTEM_HOME, "target", "test-classes").toString());
            }
        } else {
            if (hasText(resultHome)) {
                addIfDir(roots, Paths.get(resultHome, mode, "classes").toString());
            }
        }

        addIfDir(roots, MutationSystem.TESTSET_PATH);
        return new ArrayList<String>(roots);
    }
    private static boolean testClassExists(String testClassName) {
        for (String rootStr : resolveTestRoots()) {
            Path root = Paths.get(rootStr).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;

            Path p = root.resolve(testClassName.replace('.', File.separatorChar) + ".class");
            if (Files.exists(p)) {
                return true;
            }
        }
        return false;
    }

    private static String qualifiedName(String pkg, String simple) {
        if (pkg == null || pkg.trim().isEmpty()) return simple;
        return pkg + "." + simple;
    }

    private static boolean samePackage(String pkg, String className) {
        return packageName(className).equals(pkg == null ? "" : pkg);
    }

    private static String packageName(String className) {
        if (className == null) return "";
        int idx = className.lastIndexOf('.');
        return idx < 0 ? "" : className.substring(0, idx);
    }

    private static String simpleName(String className) {
        if (className == null) return "";
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    private static void addIfPresent(Set<String> out, Set<String> all, String className) {
        if (all.contains(className)) {
            out.add(className);
        }
    }

    private static boolean looksLikeTestClass(String className) {
        return looksLikeTestSimpleName(simpleName(className));
    }

    private static boolean looksLikeTestSimpleName(String simple) {
        if (simple == null || simple.trim().isEmpty()) return false;
        return simple.endsWith("Test")
                || simple.startsWith("Test")
                || simple.endsWith("TestCase")
                || simple.endsWith("Tests");
    }

    private static String toClassName(Path root, Path cls) {
        String rel = root.relativize(cls).toString();
        rel = rel.substring(0, rel.length() - ".class".length());
        return rel.replace(File.separatorChar, '.').replace('/', '.').replace('\\', '.');
    }

    public static String runtimePackagePrefix(String testSetName) {
        int idx = testSetName.lastIndexOf('.');
        return (idx > 0) ? testSetName.substring(0, idx) : "";
    }

    public static String testId(String testSetName, String methodName) {
        return testSetName + "#" + methodName;
    }

    public static List<String> readMethodSignatures(String traditionalMutantRoot) throws IOException {
        File methodList = new File(traditionalMutantRoot, "method_list.txt");
        List<String> signatures = new ArrayList<String>();
        if (!methodList.isFile()) {
            return signatures;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(methodList));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    signatures.add(line);
                }
            }
        } finally {
            if (reader != null) reader.close();
        }
        return signatures;
    }

    public static List<String> listMutantDirectories(String mutantMethodPath) {
        File root = new File(mutantMethodPath);
        if (!root.isDirectory()) {
            return Collections.emptyList();
        }

        File[] children = root.listFiles();
        List<String> names = new ArrayList<String>();
        if (children == null) {
            return names;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                names.add(child.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    public static String safeFileName(String text) {
        if (text == null || text.isEmpty()) {
            return "_";
        }
        return text.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }


    private static class ClasspathSnapshot {
        String classDirs;
        String jarFiles;
        String jarDirs;
        String ownerKey;
    }

    private static void collectAllTargetClasses(String rootDir, Set<String> classDirs) throws IOException {
        if (rootDir == null || rootDir.trim().isEmpty()) return;

        Path root = Paths.get(rootDir).normalize();
        if (!Files.exists(root)) return;

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                    .forEach(p -> {
                        String s = p.toString().replace('\\', '/');
                        if (s.endsWith("/target/classes")) {
                            classDirs.add(p.toAbsolutePath().toString());
                        }
                    });
        }
    }

    private static void addIfDir(Set<String> set, String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        File f = new File(dir);
        if (f.isDirectory()) {
            set.add(f.getAbsolutePath());
        }
    }

    private static String join(Set<String> set, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
