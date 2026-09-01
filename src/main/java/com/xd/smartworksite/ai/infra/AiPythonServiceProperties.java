package com.xd.smartworksite.ai.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.python-service")
public class AiPythonServiceProperties {
    private String baseUrl = "http://127.0.0.1:8015";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
    private int retryCount = 1;
    private String apiKey = "";
    private Paths paths = new Paths();
    private Database database = new Database();
    private Security security = new Security();
    private AutoStart autoStart = new AutoStart();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Paths getPaths() { return paths; }
    public void setPaths(Paths paths) { this.paths = paths; }
    public Database getDatabase() { return database; }
    public void setDatabase(Database database) { this.database = database; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public AutoStart getAutoStart() { return autoStart; }
    public void setAutoStart(AutoStart autoStart) { this.autoStart = autoStart; }

    public static class AutoStart {
        private boolean enabled = true;
        private String workingDirectory = "python-ai-service";
        private String pythonExecutable = "";
        private int startupTimeoutSeconds = 45;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
        public String getPythonExecutable() { return pythonExecutable; }
        public void setPythonExecutable(String pythonExecutable) { this.pythonExecutable = pythonExecutable; }
        public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
        public void setStartupTimeoutSeconds(int startupTimeoutSeconds) { this.startupTimeoutSeconds = startupTimeoutSeconds; }
    }

    public static class Paths {
        private String modelInvoke = "/v1/model/invoke";
        private String agentInvoke = "/v1/agent/invoke";
        private String ragSearch = "/v1/rag/search";
        private String ragDynamicSearch = "/v1/rag/dynamic-search";
        private String ragIndex = "/v1/rag/index";
        private String ragDelete = "/v1/rag/delete";
        private String route = "/v1/route";
        private String contextPrepare = "/v1/context/prepare";
        private String contextResolve = "/v1/context/resolve-question";
        private String contextFinalize = "/v1/context/finalize-answer";
        private String databaseGenerateQuery = "/v1/database/generate-query";
        private String databaseSummarizeResult = "/v1/database/summarize-result";
        private String ocrRecognize = "/v1/ocr/recognize";
        private String policyCrawl = "/v1/policy/crawl";
        private String documentUnderstand = "/v1/document/understand";
        public String getModelInvoke() { return modelInvoke; }
        public void setModelInvoke(String modelInvoke) { this.modelInvoke = modelInvoke; }
        public String getAgentInvoke() { return agentInvoke; }
        public void setAgentInvoke(String agentInvoke) { this.agentInvoke = agentInvoke; }
        public String getRagSearch() { return ragSearch; }
        public void setRagSearch(String ragSearch) { this.ragSearch = ragSearch; }
        public String getRagDynamicSearch() { return ragDynamicSearch; }
        public void setRagDynamicSearch(String ragDynamicSearch) { this.ragDynamicSearch = ragDynamicSearch; }
        public String getRagIndex() { return ragIndex; }
        public void setRagIndex(String ragIndex) { this.ragIndex = ragIndex; }
        public String getRagDelete() { return ragDelete; }
        public void setRagDelete(String ragDelete) { this.ragDelete = ragDelete; }
        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
        public String getContextPrepare() { return contextPrepare; }
        public void setContextPrepare(String contextPrepare) { this.contextPrepare = contextPrepare; }
        public String getContextResolve() { return contextResolve; }
        public void setContextResolve(String contextResolve) { this.contextResolve = contextResolve; }
        public String getContextFinalize() { return contextFinalize; }
        public void setContextFinalize(String contextFinalize) { this.contextFinalize = contextFinalize; }
        public String getDatabaseGenerateQuery() { return databaseGenerateQuery; }
        public void setDatabaseGenerateQuery(String databaseGenerateQuery) { this.databaseGenerateQuery = databaseGenerateQuery; }
        public String getDatabaseSummarizeResult() { return databaseSummarizeResult; }
        public void setDatabaseSummarizeResult(String databaseSummarizeResult) { this.databaseSummarizeResult = databaseSummarizeResult; }
        public String getOcrRecognize() { return ocrRecognize; }
        public void setOcrRecognize(String ocrRecognize) { this.ocrRecognize = ocrRecognize; }
        public String getPolicyCrawl() { return policyCrawl; }
        public void setPolicyCrawl(String policyCrawl) { this.policyCrawl = policyCrawl; }
        public String getDocumentUnderstand() { return documentUnderstand; }
        public void setDocumentUnderstand(String documentUnderstand) { this.documentUnderstand = documentUnderstand; }
    }

    public static class Database {
        private int maxRows = 100;
        private int queryTimeoutSeconds = 15;
        private int queryMaxAttempts = 4;
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
        public int getQueryMaxAttempts() { return queryMaxAttempts; }
        public void setQueryMaxAttempts(int queryMaxAttempts) { this.queryMaxAttempts = queryMaxAttempts; }
    }

    public static class Security {
        private static final String DEFAULT_DEVELOPMENT_DATA_SOURCE_PASSWORD_KEY = "0123456789abcdef0123456789abcdef";
        private String dataSourcePasswordKey = DEFAULT_DEVELOPMENT_DATA_SOURCE_PASSWORD_KEY;
        public String getDataSourcePasswordKey() { return dataSourcePasswordKey; }
        public void setDataSourcePasswordKey(String dataSourcePasswordKey) {
            this.dataSourcePasswordKey = dataSourcePasswordKey == null || dataSourcePasswordKey.isBlank()
                    ? DEFAULT_DEVELOPMENT_DATA_SOURCE_PASSWORD_KEY
                    : dataSourcePasswordKey;
        }
    }
}
