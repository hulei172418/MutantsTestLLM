package mujava.kill.cmd;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Centralizes the most common project-layout fields used by the batch layer.
 *
 * <p>This class is intentionally small and conservative: it only resolves data that is stable
 * across your current project structure, and avoids changing the execution path.</p>
 */
public final class ProjectLayoutResolver {

    private ProjectLayoutResolver() {}

    public static ProjectLayout resolve(String sourceModuleHome,
                                        String resultModuleHome,
                                        String targetClassName,
                                        List<String> testSetNames) {
        ProjectLayout layout = new ProjectLayout();
        layout.sourceModuleHome = normalize(sourceModuleHome);
        layout.resultModuleHome = normalize(resultModuleHome);
        layout.targetClassName = targetClassName;
        layout.testSetNames = testSetNames == null
                ? new ArrayList<String>()
                : new ArrayList<String>(testSetNames);
        return layout;
    }

    public static String normalize(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return new File(path).getAbsolutePath();
    }

    public static final class ProjectLayout {
        public String sourceModuleHome;
        public String resultModuleHome;
        public String targetClassName;
        public List<String> testSetNames = Collections.emptyList();
    }
}
