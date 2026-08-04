package com.xd.smartworksite.policy.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyKnowledgeBaseApplicationService {
    public static final String POLICY_TYPE = "POLICY";
    private static final Long SYSTEM_USER_ID = 1L;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    public PolicyKnowledgeBaseApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                                 ProjectRepository projectRepository,
                                                 ObjectMapper objectMapper) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long resolve(Project project) {
        ObjectNode settings = readSettings(project);
        Long configuredId = longValue(settings.get("policyKnowledgeBaseId"));
        KnowledgeBase knowledgeBase = configuredId == null ? null : knowledgeBaseRepository.findById(configuredId)
                .filter(value -> project.getId().equals(value.getProjectId()))
                .filter(value -> POLICY_TYPE.equals(value.getKnowledgeBaseType()))
                .orElse(null);
        if (knowledgeBase == null) {
            knowledgeBase = knowledgeBaseRepository.findByProjectIdAndType(project.getId(), POLICY_TYPE).orElse(null);
        }
        if (knowledgeBase == null) {
            knowledgeBase = createPolicyKnowledgeBase(project.getId());
        } else if (!"ENABLED".equals(knowledgeBase.getStatus())) {
            requireUpdated(knowledgeBaseRepository.updateStatus(knowledgeBase.getId(), "ENABLED", SYSTEM_USER_ID),
                    "policy knowledge base enable failed");
            knowledgeBase.setStatus("ENABLED");
        }
        if (!knowledgeBase.getId().equals(configuredId)) {
            settings.put("policyKnowledgeBaseId", knowledgeBase.getId());
            persistSettings(project, settings);
        }
        return knowledgeBase.getId();
    }

    private KnowledgeBase createPolicyKnowledgeBase(Long projectId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setProjectId(projectId);
        knowledgeBase.setName("政策资讯库");
        knowledgeBase.setDomain("POLICY_CRAWLER");
        knowledgeBase.setKnowledgeBaseType(POLICY_TYPE);
        knowledgeBase.setStatus("ENABLED");
        knowledgeBase.setDescription("系统管理的政策资讯爬虫知识库");
        knowledgeBase.setCreatedBy(SYSTEM_USER_ID);
        knowledgeBase.setUpdatedBy(SYSTEM_USER_ID);
        knowledgeBaseRepository.insert(knowledgeBase);
        if (knowledgeBase.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "policy knowledge base id was not generated");
        }
        return knowledgeBase;
    }

    private ObjectNode readSettings(Project project) {
        try {
            if (project.getSettings() == null || project.getSettings().isBlank()) {
                return objectMapper.createObjectNode();
            }
            JsonNode value = objectMapper.readTree(project.getSettings());
            return value != null && value.isObject() ? (ObjectNode) value : objectMapper.createObjectNode();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project settings json parse failed");
        }
    }

    private void persistSettings(Project project, ObjectNode settings) {
        try {
            String json = objectMapper.writeValueAsString(settings);
            requireUpdated(projectRepository.updateSettings(project.getId(), json, SYSTEM_USER_ID),
                    "project policy knowledge base setting update failed");
            project.setSettings(json);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project settings json write failed");
        }
    }

    private Long longValue(JsonNode value) {
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.longValue();
    }

    private void requireUpdated(int updated, String message) {
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }
}
