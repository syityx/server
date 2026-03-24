package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    //注入数据库操作接口 (MyBatis-Plus 自动代理)
    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MinioUtils minioUtils;

    private final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";
    private static final String CHUNK_UPLOAD_KEY_PREFIX = "upload:chunked:";

    public MediaService() {
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public String initChunkedUpload() {
        String uploadId = UUID.randomUUID().toString();
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        redisTemplate.opsForValue().set(redisKey, "INIT", 1, TimeUnit.DAYS);
        return uploadId;
    }

    public Map<String, Object> saveChunk(String uploadId, int chunkIndex, MultipartFile chunkFile) throws IOException {
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        String state = redisTemplate.opsForValue().get(redisKey);
        if (state == null) {
            throw new IllegalArgumentException("uploadId 不存在或已过期");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex 必须 >= 0");
        }
        if (chunkFile == null || chunkFile.isEmpty()) {
            throw new IllegalArgumentException("分片文件为空");
        }

        File chunkDir = getChunkDir(uploadId);
        if (!chunkDir.exists() && !chunkDir.mkdirs()) {
            throw new IOException("创建分片目录失败: " + chunkDir.getAbsolutePath());
        }

        File chunkTarget = new File(chunkDir, buildChunkFilename(chunkIndex));
        chunkFile.transferTo(chunkTarget);

        String partsKey = buildPartsKey(uploadId);
        redisTemplate.opsForSet().add(partsKey, String.valueOf(chunkIndex));
        redisTemplate.expire(partsKey, 1, TimeUnit.DAYS);
        redisTemplate.expire(redisKey, 1, TimeUnit.DAYS);

        Map<String, Object> result = new HashMap<>();
        result.put("uploadId", uploadId);
        result.put("chunkIndex", chunkIndex);
        result.put("size", chunkFile.getSize());
        result.put("message", "chunk uploaded");
        return result;
    }

    public List<Integer> getUploadedChunks(String uploadId) {
        String partsKey = buildPartsKey(uploadId);
        Set<String> values = redisTemplate.opsForSet().members(partsKey);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(Integer::parseInt)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public Map<String, Object> completeChunkedUpload(String uploadId,
                                                     String filename,
                                                     int totalChunks,
                                                     Long userId) throws Exception {
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        String state = redisTemplate.opsForValue().get(redisKey);
        if (state == null) {
            throw new IllegalArgumentException("uploadId 不存在或已过期");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks 必须 > 0");
        }

        File chunkDir = getChunkDir(uploadId);
        if (!chunkDir.exists() || !chunkDir.isDirectory()) {
            throw new IllegalArgumentException("分片目录不存在");
        }

        Set<Integer> missing = new HashSet<>();
        for (int i = 0; i < totalChunks; i++) {
            File f = new File(chunkDir, buildChunkFilename(i));
            if (!f.exists() || f.length() == 0) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("仍有分片未上传: " + missing);
        }

        String safeFilename = (filename == null || filename.isBlank()) ? "chunk-upload.bin" : filename;
        String suffix = "";
        int dotIdx = safeFilename.lastIndexOf('.');
        if (dotIdx >= 0) {
            suffix = safeFilename.substring(dotIdx);
        }

        File mergedFile = new File(UPLOAD_DIR, UUID.randomUUID() + suffix);
        mergeChunks(chunkDir, totalChunks, mergedFile);

        try {
            String fileUrl = minioUtils.uploadLocalFile(mergedFile);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(safeFilename);
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());
            mediaFile.setUserId(userId);
            mediaFileMapper.insert(mediaFile);

            if (userId != null) {
                redisTemplate.delete("media:list:user:" + userId);
            }

            cleanupChunkUpload(uploadId, chunkDir);

            Map<String, Object> result = new HashMap<>();
            result.put("uploadId", uploadId);
            result.put("fileUrl", fileUrl);
            result.put("mediaId", mediaFile.getId());
            result.put("message", "complete");
            return result;
        } finally {
            if (mergedFile.exists()) {
                mergedFile.delete();
            }
        }
    }

    private File getChunkDir(String uploadId) {
        return new File(UPLOAD_DIR, "chunks/" + uploadId);
    }

    private String buildPartsKey(String uploadId) {
        return CHUNK_UPLOAD_KEY_PREFIX + uploadId + ":parts";
    }

    private String buildChunkFilename(int chunkIndex) {
        return String.format("chunk-%06d.part", chunkIndex);
    }

    private void mergeChunks(File chunkDir, int totalChunks, File mergedFile) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(mergedFile))) {
            byte[] buffer = new byte[8192];
            for (int i = 0; i < totalChunks; i++) {
                File chunk = new File(chunkDir, buildChunkFilename(i));
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(chunk))) {
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
            }
            out.flush();
        }
    }

    private void cleanupChunkUpload(String uploadId, File chunkDir) {
        redisTemplate.delete(CHUNK_UPLOAD_KEY_PREFIX + uploadId);
        redisTemplate.delete(buildPartsKey(uploadId));

        File[] files = chunkDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.exists()) {
                    f.delete();
                }
            }
        }
        if (chunkDir.exists()) {
            chunkDir.delete();
        }
    }

    public String convertVideoToAudio(MultipartFile file) throws IOException, InterruptedException {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(file.getOriginalFilename());
        mediaFile.setStatus("PROCESSING"); //状态：处理中
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setFilePath(""); //暂时为空

        //这一步执行后，MySQL 里就会多一行数据
        mediaFileMapper.insert(mediaFile);

        // --- 下面是原有的文件处理逻辑 ---
        String fileId = UUID.randomUUID().toString();
        String inputPath = UPLOAD_DIR + fileId + "_input.mp4";
        String outputPath = UPLOAD_DIR + fileId + "_output.mp3";

        File inputFile = new File(inputPath);
        file.transferTo(inputFile);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-vn");
        command.add("-acodec");
        command.add("libmp3lame");
        command.add("-q:a");
        command.add("2");
        command.add(new File(outputPath).getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (process.waitFor() == 0) {
            inputFile.delete(); // 删掉原视频

            // --- 数据库操作：更新状态为完成 ---
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFilePath(outputPath);
            mediaFileMapper.updateById(mediaFile); // 根据 ID 更新这一行

            return outputPath;
        } else {
            // --- 数据库操作：记录失败 ---
            mediaFile.setStatus("FAILED");
            mediaFileMapper.updateById(mediaFile);
            throw new RuntimeException("FFmpeg 转换失败");
        }
    }
}
