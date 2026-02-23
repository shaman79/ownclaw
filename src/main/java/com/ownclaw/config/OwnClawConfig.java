package com.ownclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Root configuration mapping to the {@code ownclaw:} prefix in application.yaml.
 * Uses mutable POJOs because Spring Boot property binding requires setters for nested objects.
 */
@Component
@ConfigurationProperties(prefix = "ownclaw")
public class OwnClawConfig {

    private Executor executor = new Executor();
    private Mentor mentor = new Mentor();
    private Queue queue = new Queue();
    private Tasks tasks = new Tasks();
    private Telegram telegram = new Telegram();
    private Webui webui = new Webui();
    private Sandbox sandbox = new Sandbox();
    private Skills skills = new Skills();
    private Database database = new Database();
    private Confidence confidence = new Confidence();
    private Observability observability = new Observability();
    private Budgets budgets = new Budgets();
    private Feedback feedback = new Feedback();
    private PlanCache planCache = new PlanCache();

    // --- Nested classes ---

    public static class Executor {
        private String provider = "ollama";
        private String url = "http://localhost:11434";
        private String model = "qwen2.5:14b";
        private double temperature = 0.3;
        private int contextWindow = 16384;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getContextWindow() { return contextWindow; }
        public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    }

    public static class Mentor {
        private String provider = "openai";
        private String model = "gpt-4o";
        private String apiKey;
        private int maxTokensPerTask = 10000;
        private double temperature = 0.4;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getMaxTokensPerTask() { return maxTokensPerTask; }
        public void setMaxTokensPerTask(int maxTokensPerTask) { this.maxTokensPerTask = maxTokensPerTask; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class Queue {
        private int maxConcurrentTasks = 5;
        private int ollamaConcurrency = 1;
        private int cloudConcurrency = 3;
        private int sandboxConcurrency = 3;
        private int maxQueuedTasks = 50;

        public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
        public void setMaxConcurrentTasks(int v) { this.maxConcurrentTasks = v; }
        public int getOllamaConcurrency() { return ollamaConcurrency; }
        public void setOllamaConcurrency(int v) { this.ollamaConcurrency = v; }
        public int getCloudConcurrency() { return cloudConcurrency; }
        public void setCloudConcurrency(int v) { this.cloudConcurrency = v; }
        public int getSandboxConcurrency() { return sandboxConcurrency; }
        public void setSandboxConcurrency(int v) { this.sandboxConcurrency = v; }
        public int getMaxQueuedTasks() { return maxQueuedTasks; }
        public void setMaxQueuedTasks(int v) { this.maxQueuedTasks = v; }
    }

    public static class Tasks {
        private int defaultTimeout = 300;
        private int stepTimeout = 60;
        private int maxPlanSteps = 20;

        public int getDefaultTimeout() { return defaultTimeout; }
        public void setDefaultTimeout(int v) { this.defaultTimeout = v; }
        public int getStepTimeout() { return stepTimeout; }
        public void setStepTimeout(int v) { this.stepTimeout = v; }
        public int getMaxPlanSteps() { return maxPlanSteps; }
        public void setMaxPlanSteps(int v) { this.maxPlanSteps = v; }
    }

    public static class Telegram {
        private boolean enabled = true;
        private String botToken;
        private String registration = "open";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getRegistration() { return registration; }
        public void setRegistration(String registration) { this.registration = registration; }
    }

    public static class Webui {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Sandbox {
        private String type = "auto";
        private int defaultTimeout = 60;
        private String defaultNetwork = "deny";
        private String pythonPath = "python3";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getDefaultTimeout() { return defaultTimeout; }
        public void setDefaultTimeout(int v) { this.defaultTimeout = v; }
        public String getDefaultNetwork() { return defaultNetwork; }
        public void setDefaultNetwork(String v) { this.defaultNetwork = v; }
        public String getPythonPath() { return pythonPath; }
        public void setPythonPath(String v) { this.pythonPath = v; }
    }

    public static class Skills {
        private String corePath = "./skills/core";
        private String generatedPath = "./skills/generated";
        private String manifestPath = "./skills/manifest.json";
        private int maxVersionsKept = 5;

        public String getCorePath() { return corePath; }
        public void setCorePath(String v) { this.corePath = v; }
        public String getGeneratedPath() { return generatedPath; }
        public void setGeneratedPath(String v) { this.generatedPath = v; }
        public String getManifestPath() { return manifestPath; }
        public void setManifestPath(String v) { this.manifestPath = v; }
        public int getMaxVersionsKept() { return maxVersionsKept; }
        public void setMaxVersionsKept(int v) { this.maxVersionsKept = v; }
    }

    public static class Database {
        private String path = "./data/ownclaw.db";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class Confidence {
        private double mentorThreshold = 0.3;
        private double cacheSkipThreshold = 0.7;

        public double getMentorThreshold() { return mentorThreshold; }
        public void setMentorThreshold(double v) { this.mentorThreshold = v; }
        public double getCacheSkipThreshold() { return cacheSkipThreshold; }
        public void setCacheSkipThreshold(double v) { this.cacheSkipThreshold = v; }
    }

    public static class Observability {
        private int eventLogRetentionDays = 30;
        private boolean chatStatusMessages = true;
        private String statusVerbosity = "concise";

        public int getEventLogRetentionDays() { return eventLogRetentionDays; }
        public void setEventLogRetentionDays(int v) { this.eventLogRetentionDays = v; }
        public boolean isChatStatusMessages() { return chatStatusMessages; }
        public void setChatStatusMessages(boolean v) { this.chatStatusMessages = v; }
        public String getStatusVerbosity() { return statusVerbosity; }
        public void setStatusVerbosity(String v) { this.statusVerbosity = v; }
    }

    public static class Budgets {
        private long dailyCloudTokens = 500000;
        private int perTaskCloudTokens = 10000;
        private double warningThreshold = 0.8;

        public long getDailyCloudTokens() { return dailyCloudTokens; }
        public void setDailyCloudTokens(long v) { this.dailyCloudTokens = v; }
        public int getPerTaskCloudTokens() { return perTaskCloudTokens; }
        public void setPerTaskCloudTokens(int v) { this.perTaskCloudTokens = v; }
        public double getWarningThreshold() { return warningThreshold; }
        public void setWarningThreshold(double v) { this.warningThreshold = v; }
    }

    public static class Feedback {
        private int maxRounds = 3;
        private int teachingLogMaxEntries = 30;

        public int getMaxRounds() { return maxRounds; }
        public void setMaxRounds(int v) { this.maxRounds = v; }
        public int getTeachingLogMaxEntries() { return teachingLogMaxEntries; }
        public void setTeachingLogMaxEntries(int v) { this.teachingLogMaxEntries = v; }
    }

    public static class PlanCache {
        private boolean enabled = true;
        private int maxEntries = 500;
        private int ttlHours = 168;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int v) { this.maxEntries = v; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int v) { this.ttlHours = v; }
    }

    // --- Root getters/setters ---

    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor v) { this.executor = v; }
    public Mentor getMentor() { return mentor; }
    public void setMentor(Mentor v) { this.mentor = v; }
    public Queue getQueue() { return queue; }
    public void setQueue(Queue v) { this.queue = v; }
    public Tasks getTasks() { return tasks; }
    public void setTasks(Tasks v) { this.tasks = v; }
    public Telegram getTelegram() { return telegram; }
    public void setTelegram(Telegram v) { this.telegram = v; }
    public Webui getWebui() { return webui; }
    public void setWebui(Webui v) { this.webui = v; }
    public Sandbox getSandbox() { return sandbox; }
    public void setSandbox(Sandbox v) { this.sandbox = v; }
    public Skills getSkills() { return skills; }
    public void setSkills(Skills v) { this.skills = v; }
    public Database getDatabase() { return database; }
    public void setDatabase(Database v) { this.database = v; }
    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence v) { this.confidence = v; }
    public Observability getObservability() { return observability; }
    public void setObservability(Observability v) { this.observability = v; }
    public Budgets getBudgets() { return budgets; }
    public void setBudgets(Budgets v) { this.budgets = v; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback v) { this.feedback = v; }
    public PlanCache getPlanCache() { return planCache; }
    public void setPlanCache(PlanCache v) { this.planCache = v; }
}
