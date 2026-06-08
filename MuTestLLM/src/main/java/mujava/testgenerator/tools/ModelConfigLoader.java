package mujava.testgenerator.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Loads {@link ModelConfig} from environment variables, JVM properties, and llm.properties.
 *
 * Recommended llm.properties format:
 *
 *   llm.provider=deepseek
 *   llm.providers.deepseek.api.url=https://api.deepseek.com/chat/completions
 *   llm.providers.deepseek.model=deepseek-chat
 *   llm.providers.deepseek.api.key=${your key}
 *
 * To switch models/providers, only change llm.provider.
 * Provider names are arbitrary profile names, for example deepseek, openai, qwen, moonshot, ollama.
 */
public final class ModelConfigLoader {
    private static final String DEFAULT_PROVIDER = "deepseek";

    private ModelConfigLoader() {
    }

    public static ModelConfig load() {
        Properties props = loadProperties("llm.properties");

        ModelConfig cfg = new ModelConfig();
        cfg.provider = resolveProvider(props);

        String providerPrefix = "llm.providers." + cfg.provider + ".";
        String providerEnvPrefix = "LLM_" + toEnvName(cfg.provider) + "_";

        String legacyBaseUrl = firstNonBlank(
                System.getenv("DEEPSEEK_API_URL"),
                System.getProperty("deepseek.api.url"),
                props.getProperty("deepseek.api.url"),
                "https://api.deepseek.com"
        );
        legacyBaseUrl = removeTrailingSlash(legacyBaseUrl);

        cfg.apiUrl = firstNonBlank(
                System.getenv("LLM_API_URL"),
                System.getenv(providerEnvPrefix + "API_URL"),
                System.getProperty("llm.api.url"),
                System.getProperty(providerPrefix + "api.url"),
                props.getProperty(providerPrefix + "api.url"),
                props.getProperty("llm.api.url"),
                legacyBaseUrl + "/chat/completions"
        );

        cfg.apiKey = firstNonBlank(
                System.getenv("LLM_API_KEY"),
                System.getenv(providerEnvPrefix + "API_KEY"),
                System.getProperty("llm.api.key"),
                System.getProperty(providerPrefix + "api.key"),
                props.getProperty(providerPrefix + "api.key"),
                props.getProperty("llm.api.key")
        );

        cfg.model = firstNonBlank(
                System.getenv("LLM_MODEL"),
                System.getenv(providerEnvPrefix + "MODEL"),
                System.getProperty("llm.model"),
                System.getProperty(providerPrefix + "model"),
                props.getProperty(providerPrefix + "model"),
                props.getProperty("llm.model"),
                "deepseek-chat"
        );

        cfg.temperature = parseDouble(
                firstNonBlank(
                        System.getenv("LLM_TEMPERATURE"),
                        System.getenv(providerEnvPrefix + "TEMPERATURE"),
                        System.getProperty("llm.temperature"),
                        System.getProperty(providerPrefix + "temperature"),
                        props.getProperty(providerPrefix + "temperature"),
                        props.getProperty("llm.temperature"),
                        "0.2"
                ),
                "llm.temperature"
        );

        cfg.maxTokens = parseInt(
                firstNonBlank(
                        System.getenv("LLM_MAX_TOKENS"),
                        System.getenv(providerEnvPrefix + "MAX_TOKENS"),
                        System.getProperty("llm.max.tokens"),
                        System.getProperty(providerPrefix + "max.tokens"),
                        props.getProperty(providerPrefix + "max.tokens"),
                        props.getProperty("llm.max.tokens"),
                        "12000"
                ),
                "llm.max.tokens"
        );

        cfg.thinkingType = firstNonBlank(
                System.getenv("LLM_THINKING_TYPE"),
                System.getenv(providerEnvPrefix + "THINKING_TYPE"),
                System.getProperty("llm.thinking.type"),
                System.getProperty(providerPrefix + "thinking.type"),
                props.getProperty(providerPrefix + "thinking.type"),
                props.getProperty("llm.thinking.type"),
                ""
        );

        cfg.connectTimeoutMillis = parseInt(
                firstNonBlank(
                        System.getenv("LLM_CONNECT_TIMEOUT_MILLIS"),
                        System.getenv(providerEnvPrefix + "CONNECT_TIMEOUT_MILLIS"),
                        System.getProperty("llm.connect.timeout.millis"),
                        System.getProperty(providerPrefix + "connect.timeout.millis"),
                        props.getProperty(providerPrefix + "connect.timeout.millis"),
                        props.getProperty("llm.connect.timeout.millis"),
                        "30000"
                ),
                "llm.connect.timeout.millis"
        );

        cfg.readTimeoutMillis = parseInt(
                firstNonBlank(
                        System.getenv("LLM_READ_TIMEOUT_MILLIS"),
                        System.getenv(providerEnvPrefix + "READ_TIMEOUT_MILLIS"),
                        System.getProperty("llm.read.timeout.millis"),
                        System.getProperty(providerPrefix + "read.timeout.millis"),
                        props.getProperty(providerPrefix + "read.timeout.millis"),
                        props.getProperty("llm.read.timeout.millis"),
                        "120000"
                ),
                "llm.read.timeout.millis"
        );

        validate(cfg);
        return cfg;
    }

    private static String resolveProvider(Properties props) {
        String provider = firstNonBlank(
                System.getenv("LLM_PROVIDER"),
                System.getProperty("llm.provider"),
                props.getProperty("llm.provider"),
                DEFAULT_PROVIDER
        );
        return provider.trim();
    }

    private static void validate(ModelConfig cfg) {
        if (cfg.provider == null || cfg.provider.trim().isEmpty()) {
            throw new IllegalStateException("LLM provider is empty. Please set llm.provider in llm.properties.");
        }
        if (cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()) {
            throw new IllegalStateException("LLM API URL is empty for provider: " + cfg.provider);
        }
        if (cfg.model == null || cfg.model.trim().isEmpty()) {
            throw new IllegalStateException("LLM model is empty for provider: " + cfg.provider);
        }
        if (cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "LLM API key is empty for provider: " + cfg.provider + ". Please set " +
                            "llm.providers." + cfg.provider + ".api.key in llm.properties, " +
                            "environment variable LLM_API_KEY, or provider-specific environment variable."
            );
        }
    }

    private static Properties loadProperties(String resourceName) {
        Properties props = new Properties();
        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file: " + resourceName, e);
        }
        return props;
    }

    private static int parseInt(String value, String configName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer config: " + configName + " = " + value, e);
        }
    }

    private static double parseDouble(String value, String configName) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid double config: " + configName + " = " + value, e);
        }
    }

    private static String removeTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String toEnvName(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return "";
        }
        return provider.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
