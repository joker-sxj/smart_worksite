package com.xd.smartworksite.project.mapper;

import com.xd.smartworksite.project.domain.Project;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ProjectMapper {

    List<Project> selectPage(@Param("keyword") String keyword);

    Project selectById(@Param("projectId") Long projectId);
}
