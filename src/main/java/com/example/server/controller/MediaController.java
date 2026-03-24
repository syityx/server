package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
//import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioUtils minioUtils;

//    @Autowired
//    private YtDlpUtils ytDlpUtils;

    @Autowired
    private MediaService mediaService;

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload() {
        String uploadId = mediaService.initChunkedUpload();
        return ResponseEntity.ok(uploadId);
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(@RequestParam("uploadId") String uploadId,
                                         @RequestParam("chunkIndex") Integer chunkIndex,
                                         @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = mediaService.saveChunk(uploadId, chunkIndex, file);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("chunk upload failed: " + e.getMessage());
        }
    }

    @GetMapping("/upload-progress")
    public ResponseEntity<Map<String, Object>> uploadProgress(@RequestParam("uploadId") String uploadId,
                                                              @RequestParam(value = "totalChunks", required = false) Integer totalChunks) {
        List<Integer> uploaded = mediaService.getUploadedChunks(uploadId);
        Map<String, Object> data = new HashMap<>();
        data.put("uploadId", uploadId);
        data.put("uploadedChunks", uploaded);
        data.put("uploadedCount", uploaded.size());
        if (totalChunks != null && totalChunks > 0) {
            data.put("totalChunks", totalChunks);
            data.put("completed", uploaded.size() >= totalChunks);
        }
        return ResponseEntity.ok(data);
    }

    @PostMapping("/complete-upload")
    public ResponseEntity<?> completeUpload(@RequestParam("uploadId") String uploadId,
                                            @RequestParam("filename") String filename,
                                            @RequestParam("totalChunks") Integer totalChunks,
                                            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            Map<String, Object> result = mediaService.completeChunkedUpload(uploadId, filename, totalChunks, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("complete upload failed: " + e.getMessage());
        }
    }


    /**
     * 文件上传接口，支持用户关联和缓存更新
     * @param file 二进制
     * @param userId ex:1
     * @return 上传结果
     */
    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "userId", required = false) Long userId) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Upload failed: file is empty");
        }
        if (mediaFileMapper == null) {
            return ResponseEntity.status(500).body("Upload failed: database not ready");
        }
        try {
            System.out.println("Uploading to MinIO...");
            String fileUrl = minioUtils.uploadFile(file);
            System.out.println("MinIO upload success, url: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            if (userId != null) {
                String cacheKey = "media:list:user:" + userId;
                redisTemplate.delete(cacheKey);
                System.out.println("Cache cleared: " + cacheKey);
            }

            return ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }

//    @PostMapping("/upload-url")
//    public ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
//                                                                     @RequestParam(value = "userId", required = false) Long userId) {
//        File tempFile = null;
//        try {
//            if (url == null || url.isBlank()) {
//                return ResponseEntity.badRequest().body("Upload failed: url is empty");
//            }
//            if (mediaFileMapper == null) {
//                return ResponseEntity.status(500).body("Upload failed: database not ready");
//            }
//            System.out.println("Received upload url: " + url);
//
//            tempFile = ytDlpUtils.downloadVideo(url);
//
//            String fileUrl = minioUtils.uploadLocalFile(tempFile);
//
//            MediaFile mediaFile = new MediaFile();
//            mediaFile.setFilename("WEB_" + tempFile.getName());
//            mediaFile.setFilePath(fileUrl);
//            mediaFile.setStatus("COMPLETED");
//            mediaFile.setUploadTime(LocalDateTime.now());
//
//            if (userId != null) {
//                mediaFile.setUserId(userId);
//            }
//
//            mediaFileMapper.insert(mediaFile);
//
//            if (userId != null) {
//                String cacheKey = "media:list:user:" + userId;
//                redisTemplate.delete(cacheKey);
//                System.out.println("Cache cleared: " + cacheKey);
//            }
//
//            return ResponseEntity.ok("Upload success");
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
//        } finally {
//            if (tempFile != null && tempFile.exists()) {
//                tempFile.delete();
//            }
//        }
//    }

    @GetMapping("/list")
    public List<MediaFile> getList(@RequestParam(value = "userId", required = false) Long userId) {
        String cacheKey = "media:list:user:" + (userId == null ? "anon" : userId);

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        if (userId != null) {
            query.eq("user_id", userId);
        } else {
            return List.of();
        }
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    //删除接口
    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "userId", required = false) Long userId) {

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return "文件不存在";

        if (userId != null && !media.getUserId().equals(userId)) {
            return "无权删除他人的文件";
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        mediaFileMapper.deleteById(id);

        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("缓存已清除: " + cacheKey);
        }

        return "删除成功";
    }
}
