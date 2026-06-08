import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.*;
import java.util.*;

// 可视化分析本项目文件的依赖关系（供重构用）

public class ClassDependencyScanner {

    private static final List<String[]> records = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        File dir = new File("src/main/java"); // 你的源码路径
        scanDirectory(dir);
        saveToCsv("class_dependencies.csv");
    }

    private static void scanDirectory(File dir) throws IOException {
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                scanDirectory(file);
            } else if (file.getName().endsWith(".java")) {
                analyzeFile(file);
            }
        }
    }

    private static void analyzeFile(File file) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        String className = file.getName().replace(".java", "");
        Set<String> dependencies = new HashSet<>();

        cu.getImports().forEach(im -> {
            String name = im.getNameAsString();
            if (!name.startsWith("java.") && !name.startsWith("javax.")) {
                dependencies.add(name);
            }
        });

        cu.findAll(ClassOrInterfaceType.class).forEach(type -> {
            String name = type.getNameAsString();
            // 不以 java/javax 开头，且不为原始类型（例如 int、String 等）时保留
            if (!name.startsWith("java.") && !name.startsWith("javax.")
                    && !isPrimitiveOrStandard(name)) {
                dependencies.add(name);
            }
        });

        if (dependencies.isEmpty()) {
            records.add(new String[]{className, ""});
        } else {
            for (String dep : dependencies) {
                records.add(new String[]{className, dep});
            }
        }
    }

    private static boolean isPrimitiveOrStandard(String name) {
        return Arrays.asList("String", "Integer", "Boolean", "Long", "Double", "Float", "Short", "Byte", "Character").contains(name);
    }

    private static void saveToCsv(String fileName) throws IOException {
        try (PrintWriter writer = new PrintWriter(new File(fileName))) {
            writer.println("Class,DependsOn");
            for (String[] record : records) {
                writer.println(record[0] + "," + record[1]);
            }
            System.out.println("CSV saved to: " + fileName);
        }
    }
}
