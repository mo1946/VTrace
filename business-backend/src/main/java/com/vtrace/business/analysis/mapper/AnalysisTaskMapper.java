package com.vtrace.business.analysis.mapper;

import com.vtrace.business.analysis.model.AnalysisTask;
import com.vtrace.business.analysis.model.NewAnalysisTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AnalysisTaskMapper {
    @Select("""
            SELECT id AS task_id, media_id, state, stage, attempt_count,
                   progress_current, progress_total, progress_message, error,
                   created_at, updated_at, result AS report
            FROM analysis_tasks
            WHERE id = #{taskId}
            """)
    AnalysisTask findById(@Param("taskId") String taskId);

    @Select("""
            SELECT id AS task_id, media_id, state, stage, attempt_count,
                   progress_current, progress_total, progress_message, error,
                   created_at, updated_at, result AS report
            FROM analysis_tasks WHERE id = #{taskId} AND user_id = #{userId}
            """)
    AnalysisTask findByIdForUser(@Param("taskId") String taskId, @Param("userId") int userId);

    @Select("SELECT id FROM media_files WHERE id = #{mediaId} AND user_id = #{userId}")
    Integer findOwnedMedia(@Param("mediaId") int mediaId, @Param("userId") int userId);

    @Select("""
            SELECT id FROM analysis_tasks
            WHERE media_id = #{mediaId} AND goal_hash = #{goalHash}
              AND state IN ('QUEUED', 'PROCESSING', 'RETRYING')
            LIMIT 1
            """)
    String findActiveTask(@Param("mediaId") int mediaId, @Param("goalHash") String goalHash);

    @Insert("""
            INSERT INTO analysis_tasks
              (id, media_id, user_id, goal, goal_hash, active_key, state, stage,
               progress_current, progress_total, progress_message, attempt_count,
               max_attempts, created_at, updated_at)
            VALUES
              (#{taskId}, #{mediaId}, #{userId}, #{goal}, #{goalHash}, #{activeKey},
               'QUEUED', 'QUEUED', 0, 0, '任务已排队，等待 Worker 接收', 0,
               #{maxAttempts}, #{now}, #{now})
            """)
    int insertTask(NewAnalysisTask task);

    @Update("UPDATE media_files SET status = 'PROCESSING' WHERE id = #{mediaId} AND user_id = #{userId}")
    int markMediaProcessing(@Param("mediaId") int mediaId, @Param("userId") int userId);
}
