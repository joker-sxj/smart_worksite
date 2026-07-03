package com.xd.smartworksite.project.repository;

import com.xd.smartworksite.project.domain.Project;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    List<Project> findPage(String keyword);

    Optional<Project> findById(Long projectId);
}
