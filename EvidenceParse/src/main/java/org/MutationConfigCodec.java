package org;

import org.model.MutationConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MutationConfigCodec {
    private MutationConfigCodec() {
    }

    public static void write(Path file, MutationConfig config, int rowIndex) throws IOException {
        Properties p = toProperties(config, rowIndex);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "output-json worker config");
        }
    }

    public static WorkerInput read(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return fromProperties(p);
    }


    public static String encodeLine(MutationConfig config, int rowIndex) throws IOException {
        Properties p = toProperties(config, rowIndex);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        p.store(baos, "output-json worker config");
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    public static WorkerInput decodeLine(String line) throws IOException {
        if (line == null || line.trim().isEmpty()) {
            throw new IOException("Empty worker input line");
        }
        byte[] bytes = Base64.getDecoder().decode(line.trim());
        Properties p = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            p.load(in);
        }
        return fromProperties(p);
    }

    private static Properties toProperties(MutationConfig config, int rowIndex) {
        Properties p = new Properties();
        p.setProperty("rowIndex", String.valueOf(rowIndex));
        for (Field f : MutationConfig.class.getFields()) {
            try {
                Object value = f.get(config);
                if (value != null) {
                    p.setProperty(f.getName(), String.valueOf(value));
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return p;
    }

    private static WorkerInput fromProperties(Properties p) {
        MutationConfig c = new MutationConfig();
        for (Field f : MutationConfig.class.getFields()) {
            String v = p.getProperty(f.getName());
            if (v == null) {
                continue;
            }
            try {
                if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                    f.set(c, Boolean.parseBoolean(v));
                } else {
                    f.set(c, v);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        int rowIndex = parseInt(p.getProperty("rowIndex"), -1);
        return new WorkerInput(rowIndex, c);
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static final class WorkerInput {
        public final int rowIndex;
        public final MutationConfig config;

        WorkerInput(int rowIndex, MutationConfig config) {
            this.rowIndex = rowIndex;
            this.config = config;
        }
    }
}
