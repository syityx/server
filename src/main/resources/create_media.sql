CREATE TABLE `media_files` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                               `user_id` BIGINT DEFAULT NULL COMMENT '上传用户ID',
                               `filename` VARCHAR(255) DEFAULT NULL COMMENT '文件名',
                               `status` VARCHAR(50) DEFAULT NULL COMMENT '文件状态：UPLOADED, COMPLETED',
                               `file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件路径',
                               `ai_summary` TEXT COMMENT 'AI摘要',
                               `transcript_text` LONGTEXT COMMENT '转写文本',
                               `cover_url` VARCHAR(500) DEFAULT NULL COMMENT '封面地址',
                               `upload_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
                               PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体文件表';