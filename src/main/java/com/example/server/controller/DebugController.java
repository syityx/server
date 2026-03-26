package com.example.server.controller;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.SimpleRedisLock;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RateIntervalUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/debug")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    private static final String AI_LIMIT_KEY = "limit:ai:global";
    private static final long AI_LIMIT_RATE = 10;
    private static final long AI_LIMIT_INTERVAL = 1;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private AiService aiService;


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

//    @Autowired
//    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private RedissonClient redissonClient;

    //AI总结接口(分布式锁 + 限流 + MQ)
    @GetMapping("/ai")
    public String aiAnalyze(@RequestParam Long id) {
        //【Redisson 分布式锁】防瞬时并发连点
        String lockKey = "lock:analyze:" + id;
        RLock lock = redissonClient.getLock(lockKey);
//        SimpleRedisLock lock = new SimpleRedisLock(lockKey, redisTemplate);
//        boolean b = lock.tryLock(1000);

        try {
            if (!lock.tryLock(0, -1, TimeUnit.SECONDS)) {
                return "⚠️ 任务提交中，请勿重复点击！";
            }


            // 尝试获取全局令牌，限制 AI 分析调用速率
            if (!tryAcquireAiToken()) {
                return "⚠️ 系统繁忙(限流中)，请 1 分钟后再试！";
            }

            //查库校验
            MediaFile file = mediaFileMapper.selectById(id);
            if (file == null) return "文件不存在";
            if (file.getAiSummary() != null && file.getAiSummary().contains("正在")) {
                return "任务已在后台运行，无需重复提交";
            }

            //更新状态
            file.setAiSummary("[MQ] 已进入消息队列，等待调度...");
            mediaFileMapper.updateById(file);
            String userIdKey = (file.getUserId() == null) ? "anon" : String.valueOf(file.getUserId());
            redisTemplate.delete("media:list:user:" + userIdKey);

            //发送消息
            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS");
//            rocketMQTemplate.convertAndSend("video-analysis-topic", msg);

            return "✅ 任务已投递至 RocketMQ！";

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ 提交失败: " + e.getMessage();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            // redisson的isHeldByCurrentThread
//            lock.unlock();
        }
    }

    //纯文字提取接口
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return "❌ 找不到文件记录";

        String transcript = mediaFile.getTranscriptText();
        if (transcript != null && !transcript.isBlank()) {
            if (isTranscribeRunning(transcript)) {
                return "⚠️ 任务正在提取中，请稍后刷新结果";
            }
            if (!isTranscribeFailed(transcript)) {
                return "✅ 当前记录已有可用字幕，无需重复提取";
            }
        }

        // 调用异步服务
        aiService.asyncTranscribe(id);

        return "✅ 提取任务已后台运行！请稍后查看结果。";
    }

    private boolean isTranscribeRunning(String transcript) {
        String normalized = transcript.toLowerCase(Locale.ROOT);
        return normalized.contains("提取中") || normalized.contains("正在");
    }

    private boolean isTranscribeFailed(String transcript) {
        String normalized = transcript.toLowerCase(Locale.ROOT);
        return normalized.startsWith("❌")
                || normalized.contains("失败")
                || normalized.contains("异常")
                || normalized.contains("ffmpeg 转换失败");
    }

    private boolean tryAcquireAiToken() {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(AI_LIMIT_KEY);
        if (!rateLimiter.isExists()) {
            rateLimiter.trySetRate(RateType.OVERALL, AI_LIMIT_RATE, AI_LIMIT_INTERVAL, RateIntervalUnit.MINUTES);
        }
        return rateLimiter.tryAcquire(1);
    }

    //下载音频接口
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) throws IOException {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return ResponseEntity.notFound().build();

        String inputPath = mediaFile.getFilePath();

        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) return ResponseEntity.notFound().build();
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";
        System.out.println("⬇ 下载请求，正在从源地址转码音频: " + inputPath);

        boolean success = runFfmpeg(inputPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        String fileName = "audio.mp3";
        if (mediaFile.getFilename() != null) {
            fileName = mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    private boolean runFfmpeg(String inputPath, String outputPath) {
        try {
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            return process.waitFor(15, TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}