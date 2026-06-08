package mujava.kill.test;

import java.util.List;

/** Original-program isolated loader after common logic extraction. */
public class OriginalLoader extends AbstractIsolatedProjectLoader {

    public OriginalLoader() {
        this(null, null, null);
    }

    public OriginalLoader(List<String> classDirs, List<String> jarPaths) {
        this(classDirs, jarPaths, null);
    }

    public OriginalLoader(List<String> classDirs, List<String> jarPaths, String preferLocalPrefix) {
        super(classDirs, jarPaths, preferLocalPrefix);
    }

    public synchronized Class<?> loadTestClass1(String name) throws ClassNotFoundException {
        return loadClass(name);
    }
}
