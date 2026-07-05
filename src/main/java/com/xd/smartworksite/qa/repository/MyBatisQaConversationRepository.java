package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessageRecord;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.mapper.QaConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MyBatisQaConversationRepository implements QaConversationRepository {

    private final QaConversationMapper qaConversationMapper;

    public MyBatisQaConversationRepository(QaConversationMapper qaConversationMapper) {
        this.qaConversationMapper = qaConversationMapper;
    }

    @Override
    public void createSession(QaSession session) {
        qaConversationMapper.insertSession(session);
    }

    @Override
    public Optional<QaSession> findSessionById(Long sessionId) {
        return Optional.ofNullable(qaConversationMapper.selectSessionById(sessionId));
    }

    @Override
    public void saveMessage(QaMessageRecord messageRecord) {
        qaConversationMapper.insertMessage(messageRecord);
    }
}
