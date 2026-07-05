package com.xd.smartworksite.qa.mapper;

import com.xd.smartworksite.qa.domain.QaMessageRecord;
import com.xd.smartworksite.qa.domain.QaSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface QaConversationMapper {

    int insertSession(QaSession session);

    QaSession selectSessionById(@Param("sessionId") Long sessionId);

    List<QaMessageRecord> selectRecentMessages(@Param("projectId") Long projectId,
                                               @Param("sessionId") Long sessionId,
                                               @Param("limit") int limit);

    int insertMessage(QaMessageRecord messageRecord);
}
