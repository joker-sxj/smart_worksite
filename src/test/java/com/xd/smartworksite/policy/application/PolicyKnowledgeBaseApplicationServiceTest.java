package com.xd.smartworksite.policy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyKnowledgeBaseApplicationServiceTest {

    @Test
    void createsPolicyKnowledgeBaseAndPersistsReadOnlySetting() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project = project(7L, null);
        when(knowledgeBases.findByProjectIdAndType(7L, "POLICY")).thenReturn(Optional.empty());
        when(knowledgeBases.insert(any())).thenAnswer(invocation -> {
            KnowledgeBase value = invocation.getArgument(0);
            value.setId(44L);
            return value;
        });
        when(projects.updateSettings(eq(7L), any(), eq(1L))).thenReturn(1);

        Long id = new PolicyKnowledgeBaseApplicationService(knowledgeBases, projects, new ObjectMapper()).resolve(project);

        assertThat(id).isEqualTo(44L);
        verify(knowledgeBases).insert(argThat(value -> "POLICY".equals(value.getKnowledgeBaseType())
                && "POLICY_CRAWLER".equals(value.getDomain()) && "ENABLED".equals(value.getStatus())));
        assertThat(project.getSettings()).contains("\"policyKnowledgeBaseId\":44");
    }

    @Test
    void repairsStaleSettingByReusingExistingPolicyKnowledgeBase() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project = project(7L, "{\"policyKnowledgeBaseId\":999,\"defaultKnowledgeBaseId\":4}");
        KnowledgeBase existing = policyBase(55L, 7L, "DISABLED");
        when(knowledgeBases.findById(999L)).thenReturn(Optional.empty());
        when(knowledgeBases.findByProjectIdAndType(7L, "POLICY")).thenReturn(Optional.of(existing));
        when(knowledgeBases.updateStatus(55L, "ENABLED", 1L)).thenReturn(1);
        when(projects.updateSettings(eq(7L), any(), eq(1L))).thenReturn(1);

        Long id = new PolicyKnowledgeBaseApplicationService(knowledgeBases, projects, new ObjectMapper()).resolve(project);

        assertThat(id).isEqualTo(55L);
        verify(knowledgeBases).updateStatus(55L, "ENABLED", 1L);
        assertThat(project.getSettings()).contains("\"defaultKnowledgeBaseId\":4").contains("\"policyKnowledgeBaseId\":55");
    }

    private Project project(Long id, String settings) {
        Project project = new Project();
        project.setId(id);
        project.setSettings(settings);
        return project;
    }

    private KnowledgeBase policyBase(Long id, Long projectId, String status) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(id);
        base.setProjectId(projectId);
        base.setKnowledgeBaseType("POLICY");
        base.setStatus(status);
        return base;
    }
}
