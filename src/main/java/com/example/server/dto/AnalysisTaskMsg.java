package com.example.server.dto;

import java.io.Serializable;

//必须实现Serializable接口，否则不能在网络上传输
public class AnalysisTaskMsg implements Serializable {

    /** 启动 AI 分析任务的动作常量，避免使用魔法字符串 */
    public static final String ACTION_START_ANALYSIS = "START_ANALYSIS";

    private Long mediaId;
    private String action; //例如 ACTION_START_ANALYSIS

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long mediaId, String action) {
        this.mediaId = mediaId;
        this.action = action;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}