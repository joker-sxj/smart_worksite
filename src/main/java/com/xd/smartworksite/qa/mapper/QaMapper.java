package com.xd.smartworksite.qa.mapper;

import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface QaMapper {
    int insertSession(QaSession session);

    QaSession selectSessionById(@Param("sessionId") Long sessionId);

    List<QaSession> selectSessions(@Param("projectId") Long projectId,
                                   @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                   @Param("status") String status,
                                   @Param("keyword") String keyword);

    int updateSessionTitle(@Param("sessionId") Long sessionId,
                           @Param("title") String title,
                           @Param("updatedBy") Long updatedBy);

    int archiveSession(@Param("sessionId") Long sessionId,
                       @Param("updatedBy") Long updatedBy);

    int insertMessage(QaMessage message);

    int updateMessage(QaMessage message);

    int assignTask(@Param("messageId") Long messageId,
                   @Param("taskId") Long taskId,
                   @Param("updatedBy") Long updatedBy);

    int markMessageProcessing(@Param("messageId") Long messageId,
                              @Param("taskId") Long taskId,
                              @Param("updatedBy") Long updatedBy);

    int markMessageCompleted(@Param("messageId") Long messageId,
                             @Param("taskId") Long taskId,
                             @Param("answer") String answer,
                             @Param("routeMode") String routeMode,
                             @Param("referencesJson") String referencesJson,
                             @Param("usageJson") String usageJson,
                             @Param("updatedBy") Long updatedBy);

    int markMessageFailed(@Param("messageId") Long messageId,
                          @Param("taskId") Long taskId,
                          @Param("errorMessage") String errorMessage,
                          @Param("updatedBy") Long updatedBy);

    QaMessage selectMessageById(@Param("messageId") Long messageId);

    List<QaMessage> selectMessagesBySessionId(@Param("sessionId") Long sessionId);

    List<QaMessage> selectLatestSuccessfulMessages(@Param("sessionId") Long sessionId,
                                                   @Param("beforeMessageId") Long beforeMessageId,
                                                   @Param("limit") int limit);

    int updateMessageFeedback(@Param("messageId") Long messageId,
                              @Param("feedbackJson") String feedbackJson,
                              @Param("updatedBy") Long updatedBy);
}
