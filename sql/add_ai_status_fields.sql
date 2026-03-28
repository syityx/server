-- AI 分析链路增强迁移脚本
-- 为 media_files 表新增 AI 任务状态相关字段
-- 执行环境：MySQL 5.7+

ALTER TABLE `media_files`
    -- AI 分析状态：NOT_STARTED / QUEUED / RUNNING / SUCCEEDED / FAILED
    ADD COLUMN `ai_status`     VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'AI分析状态',

    -- 已重试次数（含首次执行），达到上限后不再自动重试
    ADD COLUMN `ai_attempts`   INT          NOT NULL DEFAULT 0             COMMENT 'AI分析已尝试次数',

    -- 最近一次失败的错误信息，用于排查
    ADD COLUMN `ai_last_error` VARCHAR(500)          DEFAULT NULL          COMMENT 'AI分析最近失败原因',

    -- AI 状态最近更新时间，便于排查卡死/超时问题
    ADD COLUMN `ai_updated_at` DATETIME              DEFAULT NULL          COMMENT 'AI状态最近更新时间';

-- 为幂等查询加索引（可选，数据量小时可省略）
ALTER TABLE `media_files`
    ADD INDEX `idx_ai_status` (`ai_status`);
