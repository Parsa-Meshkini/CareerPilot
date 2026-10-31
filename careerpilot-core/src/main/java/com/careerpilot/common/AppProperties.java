package com.careerpilot.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Typed view of application.yml; every value can be overridden from .env or the environment. */
@ConfigurationProperties(prefix = "careerpilot")
public class AppProperties {
    private Path storageRoot = Path.of(System.getProperty("user.home"), ".careerpilot", "storage");
    private final Llm llm = new Llm();
    private final Email email = new Email();
    private final Features features = new Features();

    public Path getStorageRoot() { return storageRoot; }
    public void setStorageRoot(Path storageRoot) { this.storageRoot = storageRoot; }
    public Llm getLlm() { return llm; }
    public Email getEmail() { return email; }
    public Features getFeatures() { return features; }

    public static class Llm {
        private String provider = "gemini";
        private String apiKey = "";
        private String model = "";
        private String baseUrl = "";
        private int userDailyTokenBudget = 200_000;
        private int runTokenBudget = 40_000;
        private int timeoutSeconds = 60;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getUserDailyTokenBudget() { return userDailyTokenBudget; }
        public void setUserDailyTokenBudget(int v) { this.userDailyTokenBudget = v; }
        public int getRunTokenBudget() { return runTokenBudget; }
        public void setRunTokenBudget(int v) { this.runTokenBudget = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    }

    public static class Email {
        private double autoApplyThreshold = 0.85;
        public double getAutoApplyThreshold() { return autoApplyThreshold; }
        public void setAutoApplyThreshold(double v) { this.autoApplyThreshold = v; }
    }

    public static class Features {
        private boolean autoTriage = true;
        public boolean isAutoTriage() { return autoTriage; }
        public void setAutoTriage(boolean v) { this.autoTriage = v; }
    }
}
