package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.enums.AiStatus;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AiService {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    // 【关键】必须注入 Redis 工具！
    @Autowired
    private StringRedisTemplate redisTemplate;


    /**
     * 执行 AI 分析（转写 + 总结），由 VideoAnalysisConsumer 线程池调用。
     * 成功时将 ai_status 更新为 SUCCEEDED；抛出异常则由调用方（Consumer）统一处理失败重试。
     */
    public void asyncAnalyze(Long mediaId) {
        System.out.println(" [线程池] 开始处理任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            // 1. 语音转文字(ffmpeg)
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);

            // 2. 智能总结
            String summary = aiAnalysisStrategy.generateSummary(mediaFile.getFilePath());
            mediaFile.setAiSummary(summary);

            // 3. 更新状态为 SUCCEEDED，记录完成时间
            mediaFile.setAiStatus(AiStatus.SUCCEEDED.name());
            mediaFile.setAiLastError(null); // 成功后清除上次错误信息
            mediaFile.setAiUpdatedAt(LocalDateTime.now());

            // 4. 保存数据库
            mediaFileMapper.updateById(mediaFile);

            // 5. 删除 Redis 列表缓存，确保前端下次轮询获得最新数据
            // Key 必须和 MediaController 里的逻辑完全一致！
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            String cacheKey = "media:list:user:" + userIdStr;
            Boolean deleteResult = redisTemplate.delete(cacheKey);
            if (Boolean.TRUE.equals(deleteResult)) {
                System.out.println(" [线程池] 缓存清除成功！Key: " + cacheKey);
            } else {
                System.out.println("⚠️ [线程池] 缓存不存在或清除失败 (但这不影响新数据写入)，Key: " + cacheKey);
            }

            System.out.println("✅ [线程池] 任务全部完成，前端轮询将在下一次命中新数据。");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ [线程池] 任务失败: " + e.getMessage());

            // 失败也要删缓存，否则前端会一直转圈看不到"失败"两个字
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            redisTemplate.delete("media:list:user:" + userIdStr);

            // 将异常向上抛出，由 VideoAnalysisConsumer.handleFailure() 统一处理重试逻辑
            throw new RuntimeException(e.getMessage(), e);
        }
    }



    //异步提取全文 (专门负责提取文字)
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println(" [线程池] 开始全文提取任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            mediaFile.setTranscriptText("[TRANSCRIBE] 提取中...");
            mediaFileMapper.updateById(mediaFile);

            //只做语音转文字
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);

            //保存数据库
            mediaFileMapper.updateById(mediaFile);

            //强制删除 Redis 缓存
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            String cacheKey = "media:list:user:" + userIdStr;
            redisTemplate.delete(cacheKey);

            System.out.println(" [线程池] 全文提取完成，缓存已清除！Key: " + cacheKey);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(" [线程池] 提取失败: " + e.getMessage());

            mediaFile.setTranscriptText("❌ 提取异常: " + e.getMessage());
            mediaFileMapper.updateById(mediaFile);

            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            redisTemplate.delete("media:list:user:" + userIdStr);
        }
    }
}
