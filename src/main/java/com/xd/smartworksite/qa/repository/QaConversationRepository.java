package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessageRecord;
import com.xd.smartworksite.qa.domain.QaSession;

import java.util.List;
import java.util.Optional;

public interface QaConversationRepository {

    void createSession(QaSession session);

    Optional<QaSession> findSessionById(Long sessionId);

    List<QaMessageRecord> findRecentMessages(Long projectId, Long sessionId, int limit);

    void saveMessage(QaMessageRecord messageRecord);
}
