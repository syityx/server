package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {

    /**
     * CAS 原子更新 AI 状态：仅当当前状态等于 expectedStatus 时才更新为 newStatus。
     * 同时更新 ai_updated_at 为当前时间，用于消费端去重抢占（QUEUED -> RUNNING）。
     *
     * @param id             媒体文件 ID
     * @param expectedStatus 期望的当前状态（只有匹配时才执行更新）
     * @param newStatus      目标状态
     * @return 受影响行数：1 表示抢占成功，0 表示状态不匹配（被其他线程先抢占）
     */
    @Update("UPDATE media_files SET ai_status = #{newStatus}, ai_updated_at = NOW() " +
            "WHERE id = #{id} AND ai_status = #{expectedStatus}")
    int casUpdateAiStatus(@Param("id") Long id,
                          @Param("expectedStatus") String expectedStatus,
                          @Param("newStatus") String newStatus);
}