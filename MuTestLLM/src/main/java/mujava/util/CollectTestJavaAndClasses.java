package util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public class CollectTestJavaAndClasses {

    public static void main(String[] args) throws Exception {
        String projectName = "commons-numbers-1.0";
        String subProjectName = "commons-numbers-rootfinder";  // 多模块填名字；单模块留空

        String inp = "";
        String outp = "";
        boolean isMulti = projectName.contains("commons-math") || projectName.contains("commons-numbers");
        if(isMulti){
            Path base = Paths.get(System.getProperty("user.dir"))
                    .resolve("..").resolve("Programs").resolve(projectName).resolve(projectName);
            base = base.resolve(subProjectName);
            inp  = base.toString();
            outp = base.resolve("..").resolve("..").resolve(subProjectName).resolve("origin").toString();
        }else {
            Path base = Paths.get(System.getProperty("user.dir"))
                    .resolve("..").resolve("Programs").resolve(projectName);
            base = base.resolve(subProjectName);
            inp  = base.toString();
            outp = base.resolve("..").resolve("origin").toString();
        }
        Path projectsRoot = Paths.get(inp).toAbsolutePath().normalize();
        Path destRoot = Paths.get(outp).toAbsolutePath().normalize();


        boolean move = false;
        boolean includeMainClasses = true;

        if (!Files.isDirectory(projectsRoot)) {
            throw new IllegalArgumentException("projectsRootDir is not a directory: " + projectsRoot);
        }
        Files.createDirectories(destRoot);

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectsRoot)) {
            for (Path projectDir : ds) {
                if (!Files.isDirectory(projectDir)) continue;

                String proName = projectDir.getFileName().toString();
                System.out.println("\n[PROJECT] " + proName);

                Path projectOutRoot = destRoot.resolve(proName);
                Result r = processOneProject(projectDir, projectOutRoot, move, includeMainClasses);
                if (r.copiedAny) {
                    System.out.println("[DONE] java=" + r.testJavaCount + ", testClassesFiles=" + r.testClassesFileCount);
                } else {
                    System.out.println("[SKIP] no matched files");
                }
            }
        }

        System.out.println("\nALL DONE. Output -> " + destRoot);
    }

    static class Result {
        long testJavaCount;
        long testClassesFileCount;
        long mainClassFileCount;
        long skipped;
        boolean copiedAny;
    }

    private static Result processOneProject(Path projectDir, Path projectOutRoot, boolean move, boolean includeMainClasses) throws IOException {
        Result res = new Result();
        final boolean[] outCreated = {false};

        Files.walkFileTree(projectDir, new SimpleFileVisitor<Path>() {

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) { String name = dir.getFileName() == null ? "" : dir.getFileName().toString(); if (".git".equals(name) || ".idea".equals(name) || "node_modules".equals(name)) { return FileVisitResult.SKIP_SUBTREE; } return FileVisitResult.CONTINUE; }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;

                // 1) src/test/** 下的 .java
                if (file.getFileName().toString().endsWith(".java") && (isUnderTestJava(file, projectDir) || isUnderSrcTest(file, projectDir))) {
                    ensureOutRoot(projectOutRoot, outCreated);
                    Path rel = projectDir.relativize(file);
                    Path dst = projectOutRoot.resolve(rel);
                    transfer(file, dst, move);
                    res.testJavaCount++;
                    res.copiedAny = true;
                    return FileVisitResult.CONTINUE;
                }

                // 2) target/test-classes/** 下的所有文件
                if (isUnderTargetTestClasses(file, projectDir)) {
                    ensureOutRoot(projectOutRoot, outCreated);
                    Path rel = projectDir.relativize(file);
                    Path dst = projectOutRoot.resolve(rel);
                    transfer(file, dst, move);
                    res.testClassesFileCount++;
                    res.copiedAny = true;
                    return FileVisitResult.CONTINUE;
                }

                // 3) 可选：target/classes/** 下的 .class
                if (includeMainClasses && file.getFileName().toString().endsWith(".class") && isUnderTargetClasses(file, projectDir)) {
                    ensureOutRoot(projectOutRoot, outCreated);
                    Path rel = projectDir.relativize(file);
                    Path dst = projectOutRoot.resolve(rel);
                    transfer(file, dst, move);
                    res.mainClassFileCount++;
                    res.copiedAny = true;
                    return FileVisitResult.CONTINUE;
                }

                res.skipped++;
                return FileVisitResult.CONTINUE;
            }
        });

        return res;
    }

    private static void ensureOutRoot(Path projectOutRoot, boolean[] outCreated) throws IOException {
        if (!outCreated[0]) {
            Files.createDirectories(projectOutRoot);
            outCreated[0] = true;
        }
    }

    private static boolean isUnderSrcTest(Path file, Path projectDir) {
        Path rel = projectDir.relativize(file).normalize();
        return startsWithSegments(rel, "src", "test");
    }

    private static boolean isUnderTestJava(Path file, Path projectDir) {
        Path rel = projectDir.relativize(file).normalize();
        return startsWithSegments(rel, "test", "java");
    }

    private static boolean isUnderTargetTestClasses(Path file, Path projectDir) {
        Path rel = projectDir.relativize(file).normalize();
        return startsWithSegments(rel, "test-classes");
    }

    private static boolean isUnderTargetClasses(Path file, Path projectDir) {
        Path rel = projectDir.relativize(file).normalize();
        return startsWithSegments(rel, "target", "classes");
    }

    private static boolean startsWithSegments(Path rel, String... segs) {
        if (rel.getNameCount() < segs.length) return false;
        for (int i = 0; i < segs.length; i++) {
            if (!Objects.equals(rel.getName(i).toString(), segs[i])) return false;
        }
        return true;
    }

    private static void transfer(Path src, Path dst, boolean move) throws IOException {
        Files.createDirectories(dst.getParent());
        if (move) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }


}

