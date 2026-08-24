package com.xd.smartworksite.task.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.redis.RedisKeys;
import com.xd.smartworksite.common.redis.RedisQueueService;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.knowledge.application.KnowledgeBaseApplicationService;
import com.xd.smartworksite.policy.application.PolicyApplicationService;
import com.xd.smartworksite.qa.application.QaApplicationService;
import com.xd.smartworksite.review.application.ReviewApplicationService;
import com.xd.smartworksite.report.application.ReportGenerationApplicationService;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.application.TaskWorkerApplicationService;
import com.xd.smartworksite.task.dto.TaskClaimResult;
import com.xd.smartworksite.task.dto.TaskQueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@EnableConfigurationProperties(TaskWorkerProperties.class)
@ConditionalOnProperty(prefix = "app.task.worker", name = "enabled", havingValue = "true")
public class TaskQueueWorker {
    private static final String TASK_TYPE_REPORT_GENERATION = "REPORT_GENERATION";
    private static final String TASK_TYPE_KNOWLEDGE_INDEXING = "KNOWLEDGE_INDEXING";
    private static final String TASK_TYPE_POLICY_CRAWL = "POLICY_CRAWL";
    private static final String TASK_TYPE_QA_GENERATION = "QA_GENERATION";
    private static final String TASK_TYPE_COMPLIANCE_REVIEW = "COMPLIANCE_REVIEW";
    private static final String STAGE_FINISH = "FINISH";
    private static final String STAGE_WORKER_FAILED = "WORKER_FAILED";
    private static final Logger log = LoggerFactory.getLogger(TaskQueueWorker.class);

    private final RedisQueueService redisQueueService;
    private final TaskWorkerApplicationService taskWorkerApplicationService;
    private final ReportGenerationApplicationService reportGenerationApplicationService;
    private final KnowledgeBaseApplicationService knowledgeBaseApplicationService;
    private final PolicyApplicationService policyApplicationService;
    private final QaApplicationService qaApplicationService;
    private final ReviewApplicationService reviewApplicationService;
    private final TaskWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public TaskQueueWorker(RedisQueueService redisQueueService,
                           TaskWorkerApplicationService taskWorkerApplicationService,
                           ReportGenerationApplicationService reportGenerationApplicationService,
                           KnowledgeBaseApplicationService knowledgeBaseApplicationService,
                           TaskWorkerProperties properties,
                           ObjectMapper objectMapper) {
        this(redisQueueService, taskWorkerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService, null, null, null, properties, objectMapper);
    }

    public TaskQueueWorker(RedisQueueService redisQueueService,
                           TaskWorkerApplicationService taskWorkerApplicationService,
                           ReportGenerationApplicationService reportGenerationApplicationService,
                           KnowledgeBaseApplicationService knowledgeBaseApplicationService,
                           PolicyApplicationService policyApplicationService,
                           TaskWorkerProperties properties,
                           ObjectMapper objectMapper) {
        this(redisQueueService, taskWorkerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService, policyApplicationService, null, null, properties, objectMapper);
    }

    @Autowired
    public TaskQueueWorker(RedisQueueService redisQueueService,
                           TaskWorkerApplicationService taskWorkerApplicationService,
                           ReportGenerationApplicationService reportGenerationApplicationService,
                           KnowledgeBaseApplicationService knowledgeBaseApplicationService,
                           PolicyApplicationService policyApplicationService,
                           @Nullable QaApplicationService qaApplicationService,
                           @Nullable ReviewApplicationService reviewApplicationService,
                           TaskWorkerProperties properties,
                           ObjectMapper objectMapper) {
        this.redisQueueService = redisQueueService;
        this.taskWorkerApplicationService = taskWorkerApplicationService;
        this.reportGenerationApplicationService = reportGenerationApplicationService;
        this.knowledgeBaseApplicationService = knowledgeBaseApplicationService;
        this.policyApplicationService = policyApplicationService;
        this.qaApplicationService = qaApplicationService;
        this.reviewApplicationService = reviewApplicationService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.task.worker.poll-delay-ms:2000}")
    public void pollOnce() {
        String payload = redisQueueService.pop(
                RedisKeys.queue(TaskOutboxApplicationService.TASK_QUEUE_NAME),
                Duration.ofMillis(properties.getPopTimeoutMs()));
        if (payload == null || payload.isBlank()) {
            return;
        }
        TaskQueueMessage message;
        try {
            message = parseMessage(payload);
        } catch (RuntimeException ex) {
            log.error("task queue message rejected, reason={}, payloadSummary={}",
                    ex.getMessage(), summarizePayload(payload), ex);
            return;
        }
        TaskClaimResult claim = taskWorkerApplicationService.claimQueuedTask(
                message.getTaskId(), properties.getWorkerId(), properties.getLeaseSeconds());
        if (!claim.isClaimed()) {
            log.info("task queue message ignored because task was not claimable, eventId={}, taskId={}",
                    message.getEventId(), message.getTaskId());
            return;
        }
        try {
            execute(message, claim);
            taskWorkerApplicationService.completeSuccess(message.getTaskId(), properties.getWorkerId(), STAGE_FINISH);
        } catch (RuntimeException ex) {
            taskWorkerApplicationService.completeFailure(message.getTaskId(), properties.getWorkerId(),
                    STAGE_WORKER_FAILED, ex.getMessage());
            log.error("task worker execution failed, eventId={}, taskId={}, taskType={}",
                    message.getEventId(), message.getTaskId(), claim.getTask().getTaskType(), ex);
        }
    }

    private void execute(TaskQueueMessage message, TaskClaimResult claim) {
        if (TASK_TYPE_REPORT_GENERATION.equals(claim.getTask().getTaskType())) {
            reportGenerationApplicationService.executeReportTask(claim.getTask().getBizId(), message.getTaskId());
            return;
        }
        if (TASK_TYPE_KNOWLEDGE_INDEXING.equals(claim.getTask().getTaskType())) {
            knowledgeBaseApplicationService.executeIndexTask(claim.getTask().getBizId(), message.getTaskId());
            return;
        }
        if (TASK_TYPE_POLICY_CRAWL.equals(claim.getTask().getTaskType())) {
            if (policyApplicationService == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "policy crawl service is not configured");
            }
            policyApplicationService.executeCrawlTask(message.getTaskId());
            return;
        }
        if (TASK_TYPE_QA_GENERATION.equals(claim.getTask().getTaskType())) {
            if (qaApplicationService == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "qa generation service is not configured");
            }
            qaApplicationService.executeGenerationTask(claim.getTask().getBizId(), message.getTaskId());
            return;
        }
        if (TASK_TYPE_COMPLIANCE_REVIEW.equals(claim.getTask().getTaskType())) {
            if (reviewApplicationService == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "compliance review service is not configured");
            }
            reviewApplicationService.executeReviewTask(claim.getTask().getBizId(), message.getTaskId(), properties.getWorkerId(), properties.getLeaseSeconds());
            return;
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported task type: " + claim.getTask().getTaskType());
    }

    private TaskQueueMessage parseMessage(String payload) {
        try {
            TaskQueueMessage message = objectMapper.readValue(payload, TaskQueueMessage.class);
            if (message.getTaskId() == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "task queue message missing taskId");
            }
            return message;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "task queue message json parse failed");
        }
    }

    private String summarizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "<blank>";
        }
        String normalized = payload.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
