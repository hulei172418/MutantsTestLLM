package mujava.testgenerator.tools;

/**
 * Result of one logical LLM call, including cache and retry metadata.
 */
public final class LlmCallResult {
    public String response;
    public boolean cacheHit;
    public int attempts;
    public long elapsedMillis;
}
