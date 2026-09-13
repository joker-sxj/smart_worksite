package com.xd.smartworksite.ai.repository;

import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.ai.domain.ExternalCallLog;

import java.util.List;

public interface AiRepository {
    int saveExternalCallLog(ExternalCallLog log);

    List<ExternalCallLog> queryExternalCallLogs(Long projectId, List<Long> accessibleProjectIds, String serviceName, String callType, String status);

    DataSourceRecord findEnabledDataSource(Long projectId, Long dataSourceId);

    boolean existsEnabledKnowledgeBase(Long projectId, Long knowledgeBaseId);
}
