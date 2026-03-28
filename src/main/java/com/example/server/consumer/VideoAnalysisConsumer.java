package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.enums.AiStatus;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
// 监听 "video-analysis-topic" 主题，组名随便起
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    /** 最大重试次数（含首次执行）；达到此次数后进入终态 FAILED。
     *  DebugController 入口幂等判断也使用此常量，确保两端一致。 */
    public static final int MAX_ATTEMPTS = 3;

    /** ai_last_error 字段对应数据库 VARCHAR(500)，截断时留 10 字符余量 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 490;

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    // 注入之前配置好的 IO 密集型线程池
    @Autowired
    private Executor aiTaskExecutor;

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        log.info("consumer::onMessage");
        Long mediaId = msg.getMediaId();
        log.info("⚡ [MQ消费者] 收到任务 ID: {}，准备 CAS 抢占...", mediaId);

        // 【消费端去重 + CAS 抢占】
        // 只有当数据库中 ai_status = QUEUED 时，才能原子性地将其改为 RUNNING。
        // 若返回 0（受影响行数为 0），说明状态已被其他线程/实例抢占或已非 QUEUED，直接丢弃重复消息。
        int affected = mediaFileMapper.casUpdateAiStatus(mediaId,
                AiStatus.QUEUED.name(), AiStatus.RUNNING.name());
        if (affected == 0) {
            log.warn("⚠️ [MQ消费者] 任务 ID={} 状态非 QUEUED，跳过（重复消息或已被抢占）", mediaId);
            return; // 安全丢弃重复消息，不再执行
        }

        log.info("✅ [MQ消费者] 抢占成功，任务 ID={} 状态已置为 RUNNING，派发至线程池...", mediaId);

        // CompletableFuture 异步编排，不阻塞 MQ 消费者线程
        CompletableFuture.runAsync(() -> {
            log.info("🧵 [线程池] 开始执行 AI 分析逻辑，ID: {}", mediaId);
            try {
                // 执行 AI 分析（内部会将状态更新为 SUCCEEDED 并落库）
                aiService.asyncAnalyze(mediaId);
            } catch (Exception e) {
                log.error("❌ [线程池] 任务执行失败，ID: {}，错误: {}", mediaId, e.getMessage(), e);
                // 执行失败：更新 attempts/last_error，按策略决定重试或终态
                handleFailure(mediaId, e.getMessage());
            }
        }, aiTaskExecutor);
    }

    /**
     * 处理任务失败：
     * 1. attempts 自增并记录错误信息。
     * 2. 若 attempts < MAX_ATTEMPTS，将状态重置为 QUEUED 并重新投递 MQ（触发重试）。
     * 3. 若 attempts >= MAX_ATTEMPTS，将状态置为 FAILED（终态），不再重试。
     */
    private void handleFailure(Long mediaId, String errorMsg) {
        MediaFile file = mediaFileMapper.selectById(mediaId);
        if (file == null) {
            log.warn("⚠️ [handleFailure] 找不到 ID={} 的记录，跳过", mediaId);
            return;
        }

        // 自增尝试次数并记录错误信息
        int attempts = file.getAiAttempts() == null ? 0 : file.getAiAttempts();
        attempts += 1;
        file.setAiAttempts(attempts);
        file.setAiLastError(errorMsg != null && errorMsg.length() > MAX_ERROR_MESSAGE_LENGTH
                ? errorMsg.substring(0, MAX_ERROR_MESSAGE_LENGTH) : errorMsg);
        file.setAiUpdatedAt(LocalDateTime.now());

        if (attempts < MAX_ATTEMPTS) {
            // 未达上限：重置为 QUEUED 并重新投递 MQ，触发自动重试
            file.setAiStatus(AiStatus.QUEUED.name());
            mediaFileMapper.updateById(file);
            log.info("🔄 [handleFailure] 任务 ID={} 第 {}/{} 次失败，重置为 QUEUED 并重投 MQ",
                    mediaId, attempts, MAX_ATTEMPTS);
            AnalysisTaskMsg retryMsg = new AnalysisTaskMsg(mediaId, AnalysisTaskMsg.ACTION_START_ANALYSIS);
            rocketMQTemplate.convertAndSend("video-analysis-topic", retryMsg);
        } else {
            // 已达上限：进入终态 FAILED，不再重试
            file.setAiStatus(AiStatus.FAILED.name());
            file.setAiSummary("❌ AI 分析失败（已重试 " + attempts + " 次）: " + errorMsg);
            mediaFileMapper.updateById(file);
            log.warn("🚫 [handleFailure] 任务 ID={} 已达最大重试次数（{}），置为 FAILED 终态",
                    mediaId, MAX_ATTEMPTS);
        }
    }
}
