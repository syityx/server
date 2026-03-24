-- 创建用户表
CREATE TABLE `user` (
                        `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID（主键）',
                        `username` VARCHAR(50) NOT NULL COMMENT '用户名',
                        `password_hash` VARCHAR(255) NOT NULL COMMENT '密码哈希（不存储明文）',
                        `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
                        `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
                        `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        PRIMARY KEY (`id`),
                        UNIQUE KEY `uk_username` (`username`) COMMENT '用户名唯一索引',
                        UNIQUE KEY `uk_email` (`email`) COMMENT '邮箱唯一索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';