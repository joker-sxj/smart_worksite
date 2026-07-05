package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessageRecord;
import com.xd.smartworksite.qa.domain.QaSession;

import java.util.Optional;

public interface QaConversationRepository {

    void createSession(QaSession session);

    Optional<QaSession> findSessionById(Long sessionId);

    void saveMessage(QaMessageRecord messageRecord);
}
