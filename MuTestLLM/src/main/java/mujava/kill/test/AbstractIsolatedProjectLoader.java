package mujava.kill.test;

import mujava.MutationSystem;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Shared base for original/mutant isolated class loaders.
 * Framework classes still delegate to the system loader, while business classes are loaded locally first.
 */
public abstract class AbstractIsolatedProjectLoader extends ClassLoader {

    protected final List<String> classDirs;
    protected final List<String> jarPaths;
    protected final String preferLocalPrefix;

    protected static final Map<String, byte[]> DIR_CLASS_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(32768, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 32768;
                }
            });

    protected static final Map<String, byte[]> JAR_CLASS_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(65536, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 65536;
                }
            });

    protected AbstractIsolatedProjectLoader(List<String> classDirs,
                                            List<String> jarPaths,
                                            String preferLocalPrefix) {
        super(null);
        this.classDirs = buildClassDirs(classDirs);
        this.jarPaths = buildJarPaths(jarPaths);
        this.preferLocalPrefix = preferLocalPrefix;
    }

    public synchronized Class<?> loadTestClass(String name) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            return loaded;
        }

        try {
            byte[] data = getClassData(name, MutationSystem.TESTSET_PATH);
            return defineClass(name, data, 0, data.length);
        } catch (IOException ignored) {
        }

        return loadClass(name);
    }

    @Override
    public synchronized Class<?> loadClass(String name) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            return loaded;
        }

        if (isFrameworkClass(name)) {
            try {
                return findSystemClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }

        Class<?> local = tryLoadLocal(name);
        if (local != null) {
            return local;
        }

        try {
            return findSystemClass(name);
        } catch (ClassNotFoundException ignored) {
        }

        throw new ClassNotFoundException(name);
    }

    protected Class<?> tryLoadLocal(String name) {
        try {
            byte[] primary = tryLoadPrimaryBytes(name);
            if (primary != null) {
                return defineClass(name, primary, 0, primary.length);
            }
        } catch (IOException ignored) {
        }

        try {
            byte[] data = getClassDataFromDirs(name, classDirs);
            return defineClass(name, data, 0, data.length);
        } catch (IOException ignored) {
        }

        try {
            byte[] data = getClassDataFromJars(name, jarPaths);
            return defineClass(name, data, 0, data.length);
        } catch (IOException ignored) {
        }

        try {
            byte[] data = getClassData(name, MutationSystem.TESTSET_PATH);
            return defineClass(name, data, 0, data.length);
        } catch (IOException ignored) {
        }

        return null;
    }

    protected byte[] tryLoadPrimaryBytes(String name) throws IOException {
        return null;
    }

    protected boolean isFrameworkClass(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("org.junit.")
                || name.startsWith("junit.")
                || name.startsWith("org.hamcrest.")
                || name.startsWith("org.opentest4j.")
                || name.startsWith("org.apiguardian.");
    }

    protected boolean isPreferLocalClass(String name) {
        return preferLocalPrefix != null
                && !preferLocalPrefix.trim().isEmpty()
                && name.startsWith(preferLocalPrefix + ".");
    }

    protected static List<String> buildClassDirs(List<String> extraDirs) {
        LinkedHashSet<String> dirs = new LinkedHashSet<String>();
        addIfNotBlank(dirs, MutationSystem.CLASS_PATH);
        addIfNotBlank(dirs, MutationSystem.TESTSET_PATH);
        addPathList(dirs, System.getProperty("mujava.class.dirs"));
        if (extraDirs != null) {
            for (String dir : extraDirs) {
                addIfNotBlank(dirs, dir);
            }
        }
        return new ArrayList<String>(dirs);
    }

    protected static List<String> buildJarPaths(List<String> extraJars) {
        LinkedHashSet<String> jars = new LinkedHashSet<String>();
        addPathList(jars, System.getProperty("mujava.jar.files"));

        String jarDirsProp = System.getProperty("mujava.jar.dirs");
        if (jarDirsProp != null && !jarDirsProp.trim().equals("")) {
            String[] dirs = jarDirsProp.split(File.pathSeparator);
            for (String dir : dirs) {
                collectJarsFromDir(jars, dir);
            }
        }

        if (extraJars != null) {
            for (String jar : extraJars) {
                addIfNotBlank(jars, jar);
            }
        }
        return new ArrayList<String>(jars);
    }

    protected static void addPathList(Set<String> target, String pathList) {
        if (pathList == null || pathList.trim().equals("")) return;
        String[] arr = pathList.split(File.pathSeparator);
        for (String p : arr) {
            addIfNotBlank(target, p);
        }
    }

    protected static void collectJarsFromDir(Set<String> jars, String dirPath) {
        if (dirPath == null || dirPath.trim().equals("")) return;
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                jars.add(f.getAbsolutePath());
            }
        }
    }

    protected static void addIfNotBlank(Set<String> set, String value) {
        if (value != null && !value.trim().equals("")) {
            set.add(value);
        }
    }

    protected byte[] getClassDataFromDirs(String name, List<String> dirs) throws IOException {
        IOException last = null;
        for (String dir : dirs) {
            try {
                return getClassData(name, dir);
            } catch (FileNotFoundException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new FileNotFoundException("Class not found in classDirs: " + name);
    }

    protected byte[] getClassDataFromJars(String name, List<String> jars) throws IOException {
        IOException last = null;
        for (String jarPath : jars) {
            try {
                return getClassDataFromJar(name, jarPath);
            } catch (FileNotFoundException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new FileNotFoundException("Class not found in jars: " + name);
    }

    protected byte[] getClassDataFromJar(String name, String jarPath) throws IOException {
        String entryName = name.replace('.', '/') + ".class";
        String cacheKey = new File(jarPath).getAbsolutePath() + "!/" + entryName;
        byte[] cached = JAR_CLASS_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try (JarFile jarFile = new JarFile(jarPath)) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                throw new FileNotFoundException("Class not found in JAR: " + name + " @ " + jarPath);
            }
            try (InputStream in = jarFile.getInputStream(entry);
                 ByteArrayOutputStream outBytes = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    outBytes.write(buf, 0, n);
                }
                byte[] data = outBytes.toByteArray();
                JAR_CLASS_CACHE.put(cacheKey, data);
                return data;
            }
        }
    }

    protected byte[] getClassData(String name, String directory) throws IOException {
        String filename = name.replace('.', File.separatorChar) + ".class";
        File f = new File(directory, filename);
        String cacheKey = f.getAbsolutePath();

        byte[] cached = DIR_CLASS_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        byte[] data = readClassBytes(f);
        DIR_CLASS_CACHE.put(cacheKey, data);
        return data;
    }

    protected byte[] getClassDataNoCache(String name, String directory) throws IOException {
        String filename = name.replace('.', File.separatorChar) + ".class";
        File f = new File(directory, filename);
        return readClassBytes(f);
    }

    protected byte[] readClassBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ByteArrayOutputStream outBytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = bis.read(buf)) != -1) {
                outBytes.write(buf, 0, n);
            }
            return outBytes.toByteArray();
        }
    }

    @Override
    public URL getResource(String name) {
        for (String dir : classDirs) {
            File resource = new File(dir, name);
            if (resource.exists()) {
                try {
                    return resource.toURI().toURL();
                } catch (MalformedURLException ignored) {
                }
            }
        }

        for (String jarPath : jarPaths) {
            try (JarFile jarFile = new JarFile(jarPath)) {
                JarEntry entry = jarFile.getJarEntry(name);
                if (entry != null) {
                    return new URL("jar:file:" + new File(jarPath).getAbsolutePath() + "!/" + name);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
