package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;          // 核心：记录是谁传的

    private String filename;
    private String status;        //UPLOADED, COMPLETED
    private String filePath;

    //下面这几个是新加的
    private String aiSummary;
    private String transcriptText;
    private String coverUrl;

    //【修改点】删掉了 @TableField(fill = ...) 注解
    //上传时间由数据库自动记录，Java 不插手，防止报错
    private LocalDateTime uploadTime;

    // ========== AI 分析任务状态字段（幂等 + 去重 + 重试） ==========

    /** AI 分析状态：NOT_STARTED / QUEUED / RUNNING / SUCCEEDED / FAILED */
    private String aiStatus;

    /** AI 分析已尝试次数（含首次执行），达到上限后不再自动重试 */
    private Integer aiAttempts;

    /** 最近一次失败的错误信息，方便排查 */
    private String aiLastError;

    /** AI 状态最近更新时间，便于排查卡死/超时 */
    private LocalDateTime aiUpdatedAt;
}