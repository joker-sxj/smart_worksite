package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;

import java.util.List;
import java.util.Optional;

public interface QaRepository {
    QaSession insertSession(QaSession session);

    Optional<QaSession> findSessionById(Long sessionId);

    List<QaSession> findSessions(Long projectId, List<Long> accessibleProjectIds, String status, String keyword);

    int updateSessionTitle(Long sessionId, String title, Long updatedBy);

    int archiveSession(Long sessionId, Long updatedBy);

    QaMessage insertMessage(QaMessage message);

    int updateMessage(QaMessage message);

    default int assignTask(Long messageId, Long taskId, Long updatedBy) { return 0; }

    default int markMessageProcessing(Long messageId, Long taskId, Long updatedBy) { return 0; }

    default int markMessageCompleted(Long messageId, Long taskId, String answer, String routeMode,
                                     String referencesJson, Long updatedBy) { return 0; }

    default int markMessageFailed(Long messageId, Long taskId, String errorMessage, Long updatedBy) { return 0; }

    Optional<QaMessage> findMessageById(Long messageId);

    List<QaMessage> findMessagesBySessionId(Long sessionId);

    int updateMessageFeedback(Long messageId, String feedbackJson, Long updatedBy);
}
