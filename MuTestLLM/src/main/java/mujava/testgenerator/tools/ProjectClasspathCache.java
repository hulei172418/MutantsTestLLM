package mujava.testgenerator.tools;

import mujava.util.LocalJarFinder;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches project classpaths by source module home.
 *
 * In batch generation, many mutants usually belong to the same project/module.
 * Building the project classpath repeatedly is unnecessary and can be expensive.
 */
public final class ProjectClasspathCache {
    private static final ConcurrentMap<String, String> CACHE = new ConcurrentHashMap<String, String>();

    private ProjectClasspathCache() {
    }

    public static String getProjectClasspath(String sourceModuleHome) throws IOException {
        if (sourceModuleHome == null) {
            return "";
        }

        String key = sourceModuleHome.trim();
        if (key.isEmpty()) {
            return "";
        }

        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        String cp = LocalJarFinder.buildProjectClasspath(sourceModuleHome);
        if (cp == null) {
            cp = "";
        }

        String existing = CACHE.putIfAbsent(key, cp);
        return existing == null ? cp : existing;
    }
}
