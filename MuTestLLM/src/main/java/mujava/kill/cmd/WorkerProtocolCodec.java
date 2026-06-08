package mujava.kill.cmd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Shared codec for persistent worker protocol and property-file output. */
public final class WorkerProtocolCodec {

    private WorkerProtocolCodec() {}

    public static String decodeField(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    public static String encodeField(String text) {
        return Base64.getEncoder().encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    public static String encodePayload(String status,
                                       String message,
                                       List<String> testOrder,
                                       Map<String, String> results) throws Exception {
        Properties props = new Properties();
        props.setProperty("status", safe(status));
        props.setProperty("message", safe(message));
        props.setProperty("tests", join(testOrder));
        if (results != null) {
            for (Map.Entry<String, String> e : results.entrySet()) {
                props.setProperty("result." + e.getKey(), safe(e.getValue()));
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        props.store(bos, "server-worker-result");
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    public static DecodedPayload decodePayload(String base64Payload) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64Payload);
        Properties props = new Properties();
        props.load(new ByteArrayInputStream(bytes));

        DecodedPayload payload = new DecodedPayload();
        payload.status = props.getProperty("status", "");
        payload.message = props.getProperty("message", "");
        payload.testOrder = split(props.getProperty("tests", ""));
        payload.results = new LinkedHashMap<String, String>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("result.")) {
                payload.results.put(key.substring("result.".length()), props.getProperty(key, ""));
            }
        }
        return payload;
    }

    public static void storeResult(String outFile,
                                   String status,
                                   String message,
                                   List<String> testOrder,
                                   Map<String, String> results) throws Exception {
        Properties props = new Properties();
        props.setProperty("status", safe(status));
        props.setProperty("message", safe(message));
        props.setProperty("tests", join(testOrder));
        if (results != null) {
            for (Map.Entry<String, String> e : results.entrySet()) {
                props.setProperty("result." + e.getKey(), safe(e.getValue()));
            }
        }
        atomicStoreProperties(outFile, props, "stable-worker-result");
    }

    public static void storeError(String outFile, Throwable t) {
        try {
            Properties props = new Properties();
            props.setProperty("status", "error");
            props.setProperty("message", stackTraceToString(t));
            atomicStoreProperties(outFile, props, "stable-worker-error");
        } catch (Throwable ignored) {
        }
    }

    public static void atomicStoreProperties(String outFile, Properties props, String comment) throws Exception {
        File file = new File(outFile);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp.toFile());
            props.store(fos, comment);
            fos.getFD().sync();
        } finally {
            if (fos != null) {
                fos.close();
            }
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveError) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        if (items != null) {
            for (String item : items) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\u0001');
                }
                sb.append(item);
            }
        }
        return sb.toString();
    }

    public static List<String> split(String text) {
        List<String> result = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String[] arr = text.split("\\u0001", -1);
        for (String s : arr) {
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    public static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (t != null) {
            t.printStackTrace(pw);
        }
        pw.flush();
        return sw.toString();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    public static final class DecodedPayload {
        public String status;
        public String message;
        public List<String> testOrder;
        public LinkedHashMap<String, String> results;
    }
}
