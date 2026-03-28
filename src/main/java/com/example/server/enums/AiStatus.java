package com.example.server.enums;

/**
 * AI 分析任务状态枚举
 * NOT_STARTED  - 未开始（初始默认状态）
 * QUEUED       - 已入队（消息已投递至 MQ，等待消费）
 * RUNNING      - 执行中（消费者已抢占，正在调用 AI 接口）
 * SUCCEEDED    - 成功（分析完成，结果已落库）
 * FAILED       - 失败（达到最大重试次数，进入终态）
 */
public enum AiStatus {
    NOT_STARTED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
