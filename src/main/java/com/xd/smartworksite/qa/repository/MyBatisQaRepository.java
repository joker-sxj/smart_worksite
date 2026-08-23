package com.xd.smartworksite.qa.repository;

import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
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
        qaMapper.insertMessage(message);
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
                                    String referencesJson, Long updatedBy) {
        return qaMapper.markMessageCompleted(messageId, taskId, answer, routeMode, referencesJson, updatedBy);
    }

    @Override
    public int markMessageFailed(Long messageId, Long taskId, String errorMessage, Long updatedBy) {
        return qaMapper.markMessageFailed(messageId, taskId, errorMessage, updatedBy);
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
    public int updateMessageFeedback(Long messageId, String feedbackJson, Long updatedBy) {
        return qaMapper.updateMessageFeedback(messageId, feedbackJson, updatedBy);
    }
}
