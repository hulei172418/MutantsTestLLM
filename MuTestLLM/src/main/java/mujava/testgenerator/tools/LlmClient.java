package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Minimal OpenAI-compatible chat-completion client used by the generator.
 */
public final class LlmClient {
    private final ModelConfig cfg;

    public LlmClient(ModelConfig cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
    }

    public String generate(String userPrompt) throws IOException {
        return generate(userPrompt, cfg.maxTokens);
    }

    public String generate(String userPrompt, int maxTokensOverride) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(cfg.apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(cfg.connectTimeoutMillis);
            conn.setReadTimeout(cfg.readTimeoutMillis);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            }

            JSONObject body = new JSONObject();
            body.put("model", cfg.model);
            body.put("temperature", cfg.temperature);
            body.put("max_tokens", maxTokensOverride > 0 ? maxTokensOverride : cfg.maxTokens);

            if (cfg.thinkingType != null && !cfg.thinkingType.trim().isEmpty()) {
                String thinkingType = cfg.thinkingType.trim();

                if ("disabled".equalsIgnoreCase(thinkingType)
                        || "false".equalsIgnoreCase(thinkingType)
                        || "off".equalsIgnoreCase(thinkingType)
                        || "none".equalsIgnoreCase(thinkingType)) {
                    body.put("enable_thinking", false);
                } else if ("enabled".equalsIgnoreCase(thinkingType)
                        || "true".equalsIgnoreCase(thinkingType)
                        || "on".equalsIgnoreCase(thinkingType)) {
                    body.put("enable_thinking", true);
                }
            }

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You generate compilable Java JUnit4 tests. Output Java source code only."));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userPrompt));
            body.put("messages", messages);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String response = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException("LLM HTTP error " + code + ": " + response);
            }
            return response;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, java.nio.charset.Charset.defaultCharset()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
