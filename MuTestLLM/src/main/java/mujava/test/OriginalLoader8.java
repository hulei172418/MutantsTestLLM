package mujava.test;

import mujava.MutationSystem;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class OriginalLoader8 extends ClassLoader {

  private final List<String> classDirs;
  private final List<String> jarPaths;
  private final String preferLocalPrefix;

  private static final Map<String, byte[]> DIR_CLASS_CACHE = Collections.synchronizedMap(
          new LinkedHashMap<String, byte[]>(32768, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
              return size() > 32768;
            }
          });

  private static final Map<String, byte[]> JAR_CLASS_CACHE = Collections.synchronizedMap(
          new LinkedHashMap<String, byte[]>(65536, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
              return size() > 65536;
            }
          });

  public OriginalLoader8() {
    this(null, null, null);
  }

  public OriginalLoader8(List<String> classDirs, List<String> jarPaths) {
    this(classDirs, jarPaths, null);
  }

  public OriginalLoader8(List<String> classDirs, List<String> jarPaths, String preferLocalPrefix) {
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

  public synchronized Class<?> loadTestClass1(String name) throws ClassNotFoundException {
    return loadClass(name);
  }

  @Override
  public synchronized Class<?> loadClass(String name) throws ClassNotFoundException {
    Class<?> loaded = findLoadedClass(name);
    if (loaded != null) return loaded;

    // JDK / JUnit 等框架类交给系统类加载器，避免测试框架被重复定义。
    if (isFrameworkClass(name)) {
      try {
        return findSystemClass(name);
      } catch (ClassNotFoundException ignored) {}
    }

    // 非框架类统一本地优先，保证同一套业务类/依赖类由同一个自定义加载器加载，
    // 避免出现 app classloader 与 original loader 混用而触发 loader constraint violation。
    Class<?> c = tryLoadLocal(name);
    if (c != null) return c;

    try {
      return findSystemClass(name);
    } catch (ClassNotFoundException ignored) {}

    throw new ClassNotFoundException(name);
  }

  private boolean isFrameworkClass(String name) {
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

  private boolean isPreferLocalClass(String name) {
    return preferLocalPrefix != null
            && !preferLocalPrefix.trim().isEmpty()
            && name.startsWith(preferLocalPrefix + ".");
  }

  private Class<?> tryLoadLocal(String name) {
    try {
      byte[] data = getClassDataFromDirs(name, classDirs);
      return defineClass(name, data, 0, data.length);
    } catch (IOException ignored) {}

    try {
      byte[] data = getClassDataFromJars(name, jarPaths);
      return defineClass(name, data, 0, data.length);
    } catch (IOException ignored) {}

    try {
      byte[] data = getClassData(name, MutationSystem.TESTSET_PATH);
      return defineClass(name, data, 0, data.length);
    } catch (IOException ignored) {}

    return null;
  }

  private static List<String> buildClassDirs(List<String> extraDirs) {
    LinkedHashSet<String> dirs = new LinkedHashSet<String>();

    addIfNotBlank(dirs, MutationSystem.CLASS_PATH);
    addIfNotBlank(dirs, MutationSystem.TESTSET_PATH);

    String prop = System.getProperty("mujava.class.dirs");
    addPathList(dirs, prop);

    if (extraDirs != null) {
      for (String dir : extraDirs) {
        addIfNotBlank(dirs, dir);
      }
    }

    return new ArrayList<String>(dirs);
  }

  private static List<String> buildJarPaths(List<String> extraJars) {
    LinkedHashSet<String> jars = new LinkedHashSet<String>();

    String jarFilesProp = System.getProperty("mujava.jar.files");
    addPathList(jars, jarFilesProp);

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

  private static void addPathList(Set<String> target, String pathList) {
    if (pathList == null || pathList.trim().equals("")) return;
    String[] arr = pathList.split(File.pathSeparator);
    for (String p : arr) {
      addIfNotBlank(target, p);
    }
  }

  private static void collectJarsFromDir(Set<String> jars, String dirPath) {
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

  private static void addIfNotBlank(Set<String> set, String value) {
    if (value != null && !value.trim().equals("")) {
      set.add(value);
    }
  }

  private byte[] getClassDataFromDirs(String name, List<String> dirs) throws IOException {
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

  private byte[] getClassDataFromJars(String name, List<String> jars) throws IOException {
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

  private byte[] getClassDataFromJar(String name, String jarPath) throws IOException {
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

      InputStream in = null;
      ByteArrayOutputStream out = null;
      try {
        in = jarFile.getInputStream(entry);
        out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
        }
        byte[] data = out.toByteArray();
        JAR_CLASS_CACHE.put(cacheKey, data);
        return data;
      } finally {
        if (in != null) in.close();
        if (out != null) out.close();
      }
    }
  }

  private byte[] getClassData(String name, String directory) throws IOException {
    String filename = name.replace('.', File.separatorChar) + ".class";
    File f = new File(directory, filename);
    String cacheKey = f.getAbsolutePath();

    byte[] cached = DIR_CLASS_CACHE.get(cacheKey);
    if (cached != null) {
      return cached;
    }

    FileInputStream fis = null;
    BufferedInputStream bis = null;
    ByteArrayOutputStream out = null;
    try {
      fis = new FileInputStream(f);
      bis = new BufferedInputStream(fis);
      out = new ByteArrayOutputStream();

      byte[] buf = new byte[4096];
      int n;
      while ((n = bis.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
      byte[] data = out.toByteArray();
      DIR_CLASS_CACHE.put(cacheKey, data);
      return data;
    } finally {
      if (bis != null) bis.close();
      if (fis != null) fis.close();
      if (out != null) out.close();
    }
  }

  @Override
  public URL getResource(String name) {
    for (String dir : classDirs) {
      File resource = new File(dir, name);
      if (resource.exists()) {
        try {
          return resource.toURI().toURL();
        } catch (MalformedURLException ignored) {}
      }
    }

    for (String jarPath : jarPaths) {
      try (JarFile jarFile = new JarFile(jarPath)) {
        JarEntry entry = jarFile.getJarEntry(name);
        if (entry != null) {
          return new URL("jar:file:" + new File(jarPath).getAbsolutePath() + "!/" + name);
        }
      } catch (Exception ignored) {}
    }

    return null;
  }
}