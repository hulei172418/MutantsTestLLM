package mujava.testgenerator.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * UTF-8 text file helpers.
 */
public final class FileTextUtils {
    private FileTextUtils() {
    }

    public static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static void writeText(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }
}
