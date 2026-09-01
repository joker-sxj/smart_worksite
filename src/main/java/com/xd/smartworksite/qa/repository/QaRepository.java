package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.domain.QaSessionMemory;

import java.util.List;
import java.util.Optional;

public interface QaRepository {
    default Optional<QaMessage> findMessageByClientRequestId(Long sessionId, Long createdBy, String clientRequestId) { return Optional.empty(); }
    default Optional<QaSessionMemory> findSessionMemory(Long sessionId, Long projectId, Long userId) { return Optional.empty(); }
    default int upsertSessionMemory(QaSessionMemory memory) { return 0; }
    default int updateMessageSuggestions(Long messageId, String suggestionsJson, String suggestionStatus, Long updatedBy) { return 0; }
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
                                     String referencesJson, String usageJson, Long updatedBy) { return 0; }

    default int markMessageCompleted(Long messageId, Long taskId, String answer, String routeMode,
                                     String referencesJson, String usageJson, String retrievalDiagnosticsJson,
                                     Long updatedBy) {
        return markMessageCompleted(messageId, taskId, answer, routeMode, referencesJson, usageJson, updatedBy);
    }

    default int markMessageFailed(Long messageId, Long taskId, String errorMessage, Long updatedBy) { return 0; }

    default int markMessageFailed(Long messageId, Long taskId, String errorMessage,
                                  String retrievalDiagnosticsJson, Long updatedBy) {
        return markMessageFailed(messageId, taskId, errorMessage, updatedBy);
    }

    Optional<QaMessage> findMessageById(Long messageId);

    List<QaMessage> findMessagesBySessionId(Long sessionId);

    default List<QaMessage> findLatestSuccessfulMessages(Long sessionId, Long beforeMessageId, int limit) {
        List<QaMessage> completed = findMessagesBySessionId(sessionId).stream()
                .filter(message -> "SUCCESS".equals(message.getStatus()))
                .filter(message -> message.getId() != null && message.getId() < beforeMessageId)
                .toList();
        int fromIndex = Math.max(0, completed.size() - limit);
        return completed.subList(fromIndex, completed.size());
    }

    int updateMessageFeedback(Long messageId, String feedbackJson, Long updatedBy);
}
