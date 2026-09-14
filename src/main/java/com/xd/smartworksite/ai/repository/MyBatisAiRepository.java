package com.xd.smartworksite.ai.repository;

import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.ai.domain.ExternalCallLog;
import com.xd.smartworksite.ai.mapper.AiMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisAiRepository implements AiRepository {
    private final AiMapper aiMapper;

    public MyBatisAiRepository(AiMapper aiMapper) {
        this.aiMapper = aiMapper;
    }

    @Override
    public int saveExternalCallLog(ExternalCallLog log) {
        return aiMapper.insertExternalCallLog(log);
    }

    @Override
    public List<ExternalCallLog> queryExternalCallLogs(Long projectId, List<Long> accessibleProjectIds, String serviceName, String callType, String status) {
        return aiMapper.selectExternalCallLogPage(projectId, accessibleProjectIds, serviceName, callType, status);
    }

    @Override
    public DataSourceRecord findEnabledDataSource(Long projectId, Long dataSourceId) {
        return aiMapper.selectDataSourceById(projectId, dataSourceId);
    }

    @Override
    public boolean existsEnabledKnowledgeBase(Long projectId, Long knowledgeBaseId) {
        return aiMapper.countEnabledKnowledgeBase(projectId, knowledgeBaseId) > 0;
    }
}
