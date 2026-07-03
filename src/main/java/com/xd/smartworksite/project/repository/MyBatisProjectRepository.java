package com.xd.smartworksite.project.repository;

import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.mapper.ProjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisProjectRepository implements ProjectRepository {

    private final ProjectMapper projectMapper;

    public MyBatisProjectRepository(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    public List<Project> findPage(String keyword) {
        return projectMapper.selectPage(keyword);
    }

    @Override
    public Optional<Project> findById(Long projectId) {
        return Optional.ofNullable(projectMapper.selectById(projectId));
    }
}
