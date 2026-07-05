package com.xd.smartworksite.qa.mapper;

import com.xd.smartworksite.qa.domain.QaMessageRecord;
import com.xd.smartworksite.qa.domain.QaSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QaConversationMapper {

    int insertSession(QaSession session);

    QaSession selectSessionById(@Param("sessionId") Long sessionId);

    int insertMessage(QaMessageRecord messageRecord);
}
