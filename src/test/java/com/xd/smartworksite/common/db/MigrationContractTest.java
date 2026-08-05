package com.xd.smartworksite.common.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path MAPPER_DIR = Path.of("src/main/resources/mapper");
    private static final Path README = Path.of("README.md");
    private static final Path BACKEND_SOURCE_DIR = Path.of("src/main/java/com/xd/smartworksite");
    private static final Path FRONTEND_API_DIR = Path.of("frontend/src/api");
    private static final Path FRONTEND_FILE_API = Path.of("frontend/src/api/file.ts");
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final Pattern CONTROLLER_CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\((?:value\\s*=\\s*)?[\"']([^\"']+)[\"']\\)");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?:\\((.*?)\\))?", Pattern.DOTALL);
    private static final Pattern STRING_PATH =
            Pattern.compile("(?:value|path)\\s*=\\s*[\"']([^\"']*)[\"']|^[\"']([^\"']*)[\"']");
    private static final Pattern FRONTEND_REQUEST =
            Pattern.compile("request\\.(get|post|put|delete)(?:<[^>]*>)?\\(([^\\n;]+)");
    private static final Pattern FRONTEND_DOWNLOAD =
            Pattern.compile("downloadFile\\(([^\\n;]+)");

    @Test
    void flywayMigrationsAreContiguousAndUnique() throws IOException {
        Map<Integer, List<String>> byVersion = new HashMap<>();
        try (var stream = Files.list(MIGRATION_DIR)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(path -> {
                        Matcher matcher = MIGRATION_NAME.matcher(path.getFileName().toString());
                        assertThat(matcher.matches())
                                .as("migration file name must match V{number}__description.sql: %s", path)
                                .isTrue();
                        int version = Integer.parseInt(matcher.group(1));
                        byVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(path.getFileName().toString());
                    });
        }

        assertThat(byVersion).isNotEmpty();
        int maxVersion = byVersion.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        for (int version = 1; version <= maxVersion; version++) {
            assertThat(byVersion).containsKey(version);
            assertThat(byVersion.get(version))
                    .as("Flyway version V%s must be unique", version)
                    .hasSize(1);
        }
    }

    @Test
    void mapperXmlDoesNotUseJsonCastParameterWorkaround() throws IOException {
        List<Path> offenders = new ArrayList<>();
        try (var stream = Files.walk(MAPPER_DIR)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .forEach(path -> {
                        try {
                            String xml = Files.readString(path, StandardCharsets.UTF_8);
                            if (xml.contains("CAST(#{") || xml.contains("CAST(? AS JSON")) {
                                offenders.add(path);
                            }
                        } catch (IOException ex) {
                            throw new IllegalStateException("failed to read mapper xml: " + path, ex);
                        }
                    });
        }

        assertThat(offenders)
                .as("JSON values must be validated in application code and passed as plain parameters")
                .isEmpty();
    }

    @Test
    void finalReportAndTaskStatusContractsMatchP0States() throws IOException {
        String v9 = Files.readString(MIGRATION_DIR.resolve("V9__align_task_and_parse_status_contracts.sql"), StandardCharsets.UTF_8);
        String v10 = Files.readString(MIGRATION_DIR.resolve("V10__extend_generate_task_runtime_fields.sql"), StandardCharsets.UTF_8);

        assertThat(v9).contains("Report status: PENDING, PROCESSING, COMPLETED, FAILED, ARCHIVED");
        assertThat(v9).contains("Parse status: PENDING, RUNNING, SUCCESS, FAILED, CANCELED");
        assertThat(v10).contains("CREATE TABLE task_outbox");
        assertThat(v10).contains("status VARCHAR(32) NOT NULL DEFAULT 'PENDING'");
    }

    @Test
    void templateVariableDescriptionMigrationUsesFileScopedUniqueKey() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V17__add_template_variable_descriptions.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(migration).contains("CREATE TABLE template_variable_description");
        assertThat(migration).contains("project_id BIGINT NOT NULL");
        assertThat(migration).contains("UNIQUE KEY uk_template_file_variable (template_id, file_id, variable_name)");
        assertThat(migration).contains("deleted TINYINT NOT NULL DEFAULT 0");
    }

    @Test
    void reportVariableMigrationSupportsDurablePerVariableProgress() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V18__add_report_variable_values.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(migration).contains("CREATE TABLE report_variable_value");
        assertThat(migration).contains("variable_description VARCHAR(2000) NOT NULL");
        assertThat(migration).contains("variable_value LONGTEXT NULL");
        assertThat(migration).contains("UNIQUE KEY uk_report_variable (report_id, variable_name)");
        assertThat(migration).contains("status VARCHAR(32) NOT NULL DEFAULT 'PENDING'");
    }

    @Test
    void reportMultiSourceMigrationAddsVariableSourceSnapshots() throws IOException {
        String migration = Files.readString(
                MIGRATION_DIR.resolve("V20__add_report_multi_sources.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(migration).contains("template_variable_description");
        assertThat(migration).contains("data_source_ids JSON");
        assertThat(migration).contains("report_variable_value");
        assertThat(migration).contains("knowledge_base_ids JSON");
    }

    @Test
    void fileDownloadContractUsesAccessUrlEndpoint() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        String frontendFileApi = Files.readString(FRONTEND_FILE_API, StandardCharsets.UTF_8);

        assertThat(readme).contains("/api/files/{fileId}/access-url?usage=DOWNLOAD\\|PREVIEW");
        assertThat(readme).doesNotContain("/api/files/{fileId}/access-url?inline=true|false");
        assertThat(frontendFileApi).contains("`/files/${fileId}/access-url`");
        assertThat(frontendFileApi).doesNotContain("`/files/${fileId}/download`");
    }

    @Test
    void frontendNonOcrApiRoutesMatchBackendControllers() throws IOException {
        List<String> backendRoutes = readBackendRoutes();
        List<String> frontendRoutes = readFrontendRoutes();

        assertThat(frontendRoutes)
                .as("frontend non-OCR API calls must target implemented backend routes")
                .allSatisfy(route -> assertThat(backendRoutes).contains(route));
    }

    private List<String> readBackendRoutes() throws IOException {
        List<String> routes = new ArrayList<>();
        try (var stream = Files.walk(BACKEND_SOURCE_DIR)) {
            List<Path> controllers = stream
                    .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .filter(path -> !path.toString().contains("\\ocr\\"))
                    .toList();
            for (Path controller : controllers) {
                String source = Files.readString(controller, StandardCharsets.UTF_8);
                String basePath = "";
                Matcher classMapping = CONTROLLER_CLASS_MAPPING.matcher(source);
                if (classMapping.find()) {
                    basePath = classMapping.group(1);
                }
                Matcher matcher = METHOD_MAPPING.matcher(source);
                while (matcher.find()) {
                    String method = matcher.group(1).toUpperCase();
                    String args = matcher.group(2) == null ? "" : matcher.group(2).trim();
                    String path = extractAnnotationPath(args);
                    routes.add(method + " " + normalizeRoute(joinPaths(basePath, path)));
                }
            }
        }
        return routes;
    }

    private List<String> readFrontendRoutes() throws IOException {
        List<String> routes = new ArrayList<>();
        try (var stream = Files.list(FRONTEND_API_DIR)) {
            List<Path> apiFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".ts"))
                    .filter(path -> !"ocr.ts".equals(path.getFileName().toString()))
                    .filter(path -> !"types.ts".equals(path.getFileName().toString()))
                    .toList();
            for (Path apiFile : apiFiles) {
                String source = Files.readString(apiFile, StandardCharsets.UTF_8);
                collectFrontendRequestRoutes(source, FRONTEND_REQUEST, routes);
                collectFrontendRequestRoutes(source, FRONTEND_DOWNLOAD, routes);
            }
        }
        return routes;
    }

    private void collectFrontendRequestRoutes(String source, Pattern pattern, List<String> routes) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            boolean download = pattern == FRONTEND_DOWNLOAD;
            String method = download ? "GET" : matcher.group(1).toUpperCase();
            String argument = download ? matcher.group(1) : matcher.group(2);
            String path = extractFirstArgumentPath(argument);
            if (path == null || !path.startsWith("/")) {
                continue;
            }
            routes.add(method + " " + normalizeFrontendPath(path));
        }
    }

    private String extractAnnotationPath(String args) {
        Matcher matcher = STRING_PATH.matcher(args);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private String extractFirstArgumentPath(String argument) {
        String trimmed = argument.trim();
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            char quote = trimmed.charAt(0);
            int end = trimmed.indexOf(quote, 1);
            return end > 0 ? trimmed.substring(1, end) : null;
        }
        if (trimmed.startsWith("`")) {
            int end = trimmed.indexOf('`', 1);
            return end > 0 ? trimmed.substring(1, end) : null;
        }
        return null;
    }

    private String joinPaths(String basePath, String methodPath) {
        if (methodPath == null || methodPath.isBlank()) {
            return basePath;
        }
        if (basePath == null || basePath.isBlank()) {
            return methodPath;
        }
        return basePath.replaceAll("/$", "") + "/" + methodPath;
    }

    private String normalizeFrontendPath(String path) {
        String withoutQuery = path.split("\\?", 2)[0];
        String withApiPrefix = withoutQuery.startsWith("/api/") ? withoutQuery : "/api" + withoutQuery;
        return normalizeRoute(withApiPrefix);
    }

    private String normalizeRoute(String path) {
        String normalized = path.replaceAll("\\$\\{[^}]+}", "{}")
                .replaceAll("\\{[^/]+}", "{}")
                .replaceAll("/{2,}", "/")
                .replaceAll("/$", "");
        return normalized.isBlank() ? "/" : normalized;
    }
}
