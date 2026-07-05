package com.xd.smartworksite.datasource.domain;

public class BusinessDataSource {

    private Long id;
    private Long projectId;
    private String name;
    private DatabaseType dbType;
    private String jdbcUrl;
    private String username;
    private String passwordCipher;
    private DataSourceStatus status;
    private String tableWhitelistJson;
    private String fieldWhitelistJson;

    public boolean isEnabled() {
        return status == DataSourceStatus.ENABLED;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DatabaseType getDbType() {
        return dbType;
    }

    public void setDbType(DatabaseType dbType) {
        this.dbType = dbType;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    public void setPasswordCipher(String passwordCipher) {
        this.passwordCipher = passwordCipher;
    }

    public DataSourceStatus getStatus() {
        return status;
    }

    public void setStatus(DataSourceStatus status) {
        this.status = status;
    }

    public String getTableWhitelistJson() {
        return tableWhitelistJson;
    }

    public void setTableWhitelistJson(String tableWhitelistJson) {
        this.tableWhitelistJson = tableWhitelistJson;
    }

    public String getFieldWhitelistJson() {
        return fieldWhitelistJson;
    }

    public void setFieldWhitelistJson(String fieldWhitelistJson) {
        this.fieldWhitelistJson = fieldWhitelistJson;
    }
}
