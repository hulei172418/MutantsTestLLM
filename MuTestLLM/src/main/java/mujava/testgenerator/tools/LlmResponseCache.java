package mujava.testgenerator.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-based cache for successful LLM HTTP responses.
 *
 * The cache key includes provider/model/apiUrl namespace and the full prompt.
 * This prevents the same prompt from being sent to the API again across threads
 * and across reruns of the generator, while keeping different providers isolated.
 */
public final class LlmResponseCache {
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    private final Path cacheDir;
    private final String namespace;

    public LlmResponseCache(Path cacheDir, ModelConfig cfg) {
        this.cacheDir = cacheDir;
        this.namespace = safe(cfg.getProvider())
                + "\n" + safe(cfg.getModel())
                + "\n" + safe(cfg.getApiUrl())
                + "\nthinking=" + safe(cfg.getThinkingType());
    }

    public CacheResult getOrGenerateWithMetadata(String prompt, ResponseGenerator generator) throws IOException {
        return getOrGenerateWithMetadata(prompt, "", generator);
    }

    public CacheResult getOrGenerateWithMetadata(String prompt, String extraKey, ResponseGenerator generator) throws IOException {
        String key = sha256(namespace + "\n" + safe(extraKey) + "\n" + safe(prompt));
        Path cacheFile = cacheDir.resolve(key + ".json");

        if (Files.isRegularFile(cacheFile)) {
            CacheResult result = new CacheResult();
            result.response = FileTextUtils.readUtf8(cacheFile);
            result.cacheHit = true;
            return result;
        }

        Object lock = LOCKS.computeIfAbsent(cacheFile.toAbsolutePath().normalize().toString(), k -> new Object());
        synchronized (lock) {
            if (Files.isRegularFile(cacheFile)) {
                CacheResult result = new CacheResult();
                result.response = FileTextUtils.readUtf8(cacheFile);
                result.cacheHit = true;
                return result;
            }

            String response = generator.generate();
            if (response == null || response.trim().isEmpty()) {
                throw new IOException("LLM returned an empty HTTP response; response is not cached.");
            }

            Files.createDirectories(cacheDir);
            Path tmp = cacheDir.resolve(key + ".tmp");
            Files.write(tmp, response.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }

            CacheResult result = new CacheResult();
            result.response = response;
            result.cacheHit = false;
            return result;
        }
    }

    public String getOrGenerate(String prompt, ResponseGenerator generator) throws IOException {
        return getOrGenerateWithMetadata(prompt, generator).response;
    }

    public interface ResponseGenerator {
        String generate() throws IOException;
    }

    public static final class CacheResult {
        public String response;
        public boolean cacheHit;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
