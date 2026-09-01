package com.xd.smartworksite.project.application;

import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectAccessApplicationService {

    public static final String PROJECT_STATUS_ENABLED = "ENABLED";
    public static final String PROJECT_ROLE_ADMIN = "PROJECT_ADMIN";

    private final ProjectRepository projectRepository;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectAccessApplicationService(ProjectRepository projectRepository,
                                           ProjectMemberMapper projectMemberMapper) {
        this.projectRepository = projectRepository;
        this.projectMemberMapper = projectMemberMapper;
    }

    public Project requireProject(Long projectId) {
        if (projectId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "projectId is required");
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "project not found"));
    }

    public Project requireProjectAccess(Long projectId) {
        Project project = requireProject(projectId);
        if (SecurityUtils.isPlatformAdmin()) {
            return project;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (projectMemberMapper.countActiveMember(projectId, currentUserId) == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "no permission to access this project");
        }
        return project;
    }

    public Project requireProjectManage(Long projectId) {
        Project project = requireProject(projectId);
        if (SecurityUtils.isPlatformAdmin()) {
            return project;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ProjectMember member = projectMemberMapper.selectByProjectIdAndUserId(projectId, currentUserId);
        if (member == null
                || !PROJECT_STATUS_ENABLED.equals(member.getStatus())
                || !PROJECT_ROLE_ADMIN.equals(member.getProjectRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "no permission to manage this project");
        }
        return project;
    }

    public Project requireProjectWritableAccess(Long projectId) {
        Project project = requireProjectAccess(projectId);
        ensureProjectWritable(project);
        return project;
    }

    public Project requireProjectWritableManage(Long projectId) {
        Project project = requireProjectManage(projectId);
        ensureProjectWritable(project);
        return project;
    }

    public Project requireProjectWritableForSystem(Long projectId) {
        Project project = requireProject(projectId);
        ensureProjectWritable(project);
        return project;
    }

    public Project requireUserProjectWritableAccess(Long projectId, Long userId) {
        Project project = requireProject(projectId);
        if (userId == null || projectMemberMapper.countActiveMember(projectId, userId) == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "user no longer has access to this project");
        }
        ensureProjectWritable(project);
        return project;
    }

    public List<Project> findAccessibleProjects(String keyword) {
        if (SecurityUtils.isPlatformAdmin()) {
            return projectRepository.findPage(keyword, null);
        }
        List<Long> projectIds = currentUserAccessibleProjectIds();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return projectRepository.findPageByProjectIds(keyword, null, projectIds);
    }

    public List<Long> currentUserAccessibleProjectIds() {
        if (SecurityUtils.isPlatformAdmin()) {
            return List.of();
        }
        return projectMemberMapper.selectProjectIdsByUserId(SecurityUtils.getCurrentUserId());
    }

    private void ensureProjectWritable(Project project) {
        if (!PROJECT_STATUS_ENABLED.equals(project.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "project is not enabled");
        }
    }
}
