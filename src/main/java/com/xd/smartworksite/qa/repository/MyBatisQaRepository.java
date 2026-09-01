package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.domain.QaSessionMemory;
import com.xd.smartworksite.qa.mapper.QaMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisQaRepository implements QaRepository {
    private final QaMapper qaMapper;

    public MyBatisQaRepository(QaMapper qaMapper) {
        this.qaMapper = qaMapper;
    }

    @Override public Optional<QaMessage> findMessageByClientRequestId(Long sessionId, Long createdBy, String clientRequestId) {
        return Optional.ofNullable(qaMapper.selectMessageByClientRequestId(sessionId, createdBy, clientRequestId));
    }
    @Override public Optional<QaSessionMemory> findSessionMemory(Long sessionId, Long projectId, Long userId) {
        return Optional.ofNullable(qaMapper.selectSessionMemory(sessionId, projectId, userId));
    }
    @Override public int upsertSessionMemory(QaSessionMemory memory) { return qaMapper.upsertSessionMemory(memory); }
    @Override public int updateMessageSuggestions(Long messageId, String suggestionsJson, String suggestionStatus, Long updatedBy) {
        return qaMapper.updateMessageSuggestions(messageId, suggestionsJson, suggestionStatus, updatedBy);
    }

    @Override
    public QaSession insertSession(QaSession session) {
        qaMapper.insertSession(session);
        return session;
    }

    @Override
    public Optional<QaSession> findSessionById(Long sessionId) {
        return Optional.ofNullable(qaMapper.selectSessionById(sessionId));
    }

    @Override
    public List<QaSession> findSessions(Long projectId, List<Long> accessibleProjectIds, String status, String keyword) {
        return qaMapper.selectSessions(projectId, accessibleProjectIds, status, keyword);
    }

    @Override
    public int updateSessionTitle(Long sessionId, String title, Long updatedBy) {
        return qaMapper.updateSessionTitle(sessionId, title, updatedBy);
    }

    @Override
    public int archiveSession(Long sessionId, Long updatedBy) {
        return qaMapper.archiveSession(sessionId, updatedBy);
    }

    @Override
    public QaMessage insertMessage(QaMessage message) {
        int inserted = qaMapper.insertMessage(message);
        if (inserted == 0 || message.getId() == null) {
            throw new IllegalStateException("qa message insert failed or id was not generated");
        }
        return message;
    }

    @Override
    public int updateMessage(QaMessage message) {
        return qaMapper.updateMessage(message);
    }

    @Override
    public int assignTask(Long messageId, Long taskId, Long updatedBy) {
        return qaMapper.assignTask(messageId, taskId, updatedBy);
    }

    @Override
    public int markMessageProcessing(Long messageId, Long taskId, Long updatedBy) {
        return qaMapper.markMessageProcessing(messageId, taskId, updatedBy);
    }

    @Override
    public int markMessageCompleted(Long messageId, Long taskId, String answer, String routeMode,
                                    String referencesJson, String usageJson, String retrievalDiagnosticsJson, Long updatedBy) {
        return qaMapper.markMessageCompleted(messageId, taskId, answer, routeMode, referencesJson, usageJson,
                retrievalDiagnosticsJson, updatedBy);
    }

    @Override
    public int markMessageCompleted(Long messageId, Long taskId, String answer, String routeMode,
                                    String referencesJson, String usageJson, Long updatedBy) {
        return qaMapper.markMessageCompleted(messageId, taskId, answer, routeMode, referencesJson, usageJson, null, updatedBy);
    }

    @Override
    public int markMessageFailed(Long messageId, Long taskId, String errorMessage,
                                 String retrievalDiagnosticsJson, Long updatedBy) {
        return qaMapper.markMessageFailed(messageId, taskId, errorMessage, retrievalDiagnosticsJson, updatedBy);
    }

    @Override
    public int markMessageFailed(Long messageId, Long taskId, String errorMessage, Long updatedBy) {
        return qaMapper.markMessageFailed(messageId, taskId, errorMessage, null, updatedBy);
    }

    @Override
    public Optional<QaMessage> findMessageById(Long messageId) {
        return Optional.ofNullable(qaMapper.selectMessageById(messageId));
    }

    @Override
    public List<QaMessage> findMessagesBySessionId(Long sessionId) {
        return qaMapper.selectMessagesBySessionId(sessionId);
    }

    @Override
    public List<QaMessage> findLatestSuccessfulMessages(Long sessionId, Long beforeMessageId, int limit) {
        return qaMapper.selectLatestSuccessfulMessages(sessionId, beforeMessageId, limit);
    }

    @Override
    public int updateMessageFeedback(Long messageId, String feedbackJson, Long updatedBy) {
        return qaMapper.updateMessageFeedback(messageId, feedbackJson, updatedBy);
    }
}
