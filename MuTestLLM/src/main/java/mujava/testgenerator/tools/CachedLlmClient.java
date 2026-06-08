package mujava.testgenerator.tools;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Adds file-cache and bounded retry behavior around one LlmClient.
 */
public final class CachedLlmClient {
    private final LlmClient delegate;
    private final LlmResponseCache cache;
    private final int maxApiAttempts;

    public CachedLlmClient(LlmClient delegate, Path cacheDir, ModelConfig cfg, int maxApiAttempts) {
        this.delegate = delegate;
        this.cache = new LlmResponseCache(cacheDir, cfg);
        this.maxApiAttempts = Math.max(1, maxApiAttempts);
    }

    public LlmCallResult generate(final String prompt) throws IOException {
        return generate(prompt, true, 0);
    }

    /**
     * Generates one LLM response.
     *
     * @param prompt   user prompt sent to the model
     * @param useCache when false, do not read from or write to llm_api_cache; always call the API
     */
    public LlmCallResult generate(final String prompt, boolean useCache) throws IOException {
        return generate(prompt, useCache, 0);
    }

    public LlmCallResult generate(final String prompt, boolean useCache, final int maxTokensOverride) throws IOException {
        if (!useCache) {
            return generateWithoutCache(prompt, maxTokensOverride);
        }

        final int[] usedAttempts = new int[]{0};
        final long start = System.nanoTime();

        String budgetKey = maxTokensOverride > 0 ? "max_tokens=" + maxTokensOverride : "max_tokens=default";
        LlmResponseCache.CacheResult cacheResult = cache.getOrGenerateWithMetadata(prompt, budgetKey, new LlmResponseCache.ResponseGenerator() {
            @Override
            public String generate() throws IOException {
                LlmCallResult generated = generateWithoutCache(prompt, maxTokensOverride);
                usedAttempts[0] = generated.attempts;
                return generated.response;
            }
        });

        LlmCallResult result = new LlmCallResult();
        result.response = cacheResult.response;
        result.cacheHit = cacheResult.cacheHit;
        result.attempts = cacheResult.cacheHit ? 0 : usedAttempts[0];
        result.elapsedMillis = cacheResult.cacheHit ? 0L : (System.nanoTime() - start) / 1_000_000L;
        return result;
    }

    private LlmCallResult generateWithoutCache(final String prompt) throws IOException {
        return generateWithoutCache(prompt, 0);
    }

    private LlmCallResult generateWithoutCache(final String prompt, int maxTokensOverride) throws IOException {
        IOException last = null;
        long start = System.nanoTime();

        for (int attempt = 1; attempt <= maxApiAttempts; attempt++) {
            try {
                String response = delegate.generate(prompt, maxTokensOverride);
                if (response == null || response.trim().isEmpty()) {
                    throw new IOException("LLM returned an empty HTTP response.");
                }

                LlmCallResult result = new LlmCallResult();
                result.response = response;
                result.cacheHit = false;
                result.attempts = attempt;
                result.elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
                return result;
            } catch (IOException e) {
                last = e;
                System.err.println("[LLM-API-RETRY] attempt=" + attempt + "/" + maxApiAttempts
                        + " failed: " + e.getMessage());
                if (attempt >= maxApiAttempts) {
                    break;
                }
                sleepBeforeRetry(attempt);
            }
        }

        throw new IOException("LLM API failed after " + maxApiAttempts + " attempt(s).", last);
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(Math.min(2_000L, 500L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry LLM API call.", e);
        }
    }
}
