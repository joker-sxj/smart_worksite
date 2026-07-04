package com.xd.smartworksite.knowledge.domain;

public class KnowledgeBase {

    private Long id;
    private Long projectId;
    private String name;
    private String domain;
    private KnowledgeBaseStatus status;

    public boolean isEnabled() {
        return status == KnowledgeBaseStatus.ENABLED;
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

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public KnowledgeBaseStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeBaseStatus status) {
        this.status = status;
    }
}
