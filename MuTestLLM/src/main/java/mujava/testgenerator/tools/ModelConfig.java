package mujava.testgenerator.tools;

/**
 * OpenAI-compatible chat completion configuration.
 *
 * Resolution order is implemented in {@link ModelConfigLoader}:
 * environment variable -> JVM system property -> provider profile in llm.properties -> legacy llm.properties -> default.
 */
public final class ModelConfig {
    String provider;
    String apiUrl;
    String apiKey;
    String model;
    double temperature;
    int maxTokens;
    int connectTimeoutMillis;
    int readTimeoutMillis;
    String thinkingType;

    public String getThinkingType() {
        return thinkingType;
    }

    public String getProvider() {
        return provider;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }
}
