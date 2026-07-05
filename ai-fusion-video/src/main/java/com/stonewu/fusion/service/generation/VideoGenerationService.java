package com.stonewu.fusion.service.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.mapper.generation.VideoItemMapper;
import com.stonewu.fusion.mapper.generation.VideoTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生视频任务服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoGenerationService {

    private final VideoTaskMapper taskMapper;
    private final VideoItemMapper itemMapper;

    @Cacheable(value = "videoTask", key = "#id")
    public VideoTask getById(Long id) {
        VideoTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException("生视频任务不存在: " + id);
        return task;
    }

    @Cacheable(value = "videoTask", key = "'taskId:' + #taskId")
    public VideoTask getByTaskId(String taskId) {
        VideoTask task = taskMapper.selectOne(new LambdaQueryWrapper<VideoTask>().eq(VideoTask::getTaskId, taskId));
        if (task == null) throw new BusinessException("生视频任务不存在: " + taskId);
        return task;
    }

    public PageResult<VideoTask> pageByUser(Long userId, int pageNo, int pageSize) {
        return PageResult.of(taskMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<VideoTask>()
                        .eq(VideoTask::getUserId, userId)
                        .orderByDesc(VideoTask::getCreateTime)));
    }

    @CacheEvict(value = "videoTask", allEntries = true)
    @Transactional
    public VideoTask create(VideoTask task) {
        taskMapper.insert(task);
        return task;
    }

    @CacheEvict(value = "videoTask", allEntries = true)
    @Transactional
    public VideoTask update(VideoTask task) {
        taskMapper.updateById(task);
        return task;
    }

    @CacheEvict(value = "videoTask", allEntries = true)
    @Transactional
    public void updateStatus(Long id, Integer status, String errorMsg) {
        getById(id);
        taskMapper.update(null, new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, id)
                .set(VideoTask::getStatus, status)
                .set(VideoTask::getErrorMsg, errorMsg));
    }

    @CacheEvict(value = "videoTask", allEntries = true)
    @Transactional
    public void delete(Long id) {
        taskMapper.deleteById(id);
    }

    // ========== Video Items ==========

    @Cacheable(value = "videoItems", key = "#taskId")
    public List<VideoItem> listItems(Long taskId) {
        return itemMapper.selectList(new LambdaQueryWrapper<VideoItem>().eq(VideoItem::getTaskId, taskId));
    }

    public VideoItem getItemById(Long id) {
        VideoItem item = itemMapper.selectById(id);
        if (item == null) throw new BusinessException("生成视频条目不存在: " + id);
        return item;
    }

    @CacheEvict(value = "videoItems", allEntries = true)
    @Transactional
    public VideoItem createItem(VideoItem item) {
        itemMapper.insert(item);
        return item;
    }

    @CacheEvict(value = "videoItems", allEntries = true)
    @Transactional
    public VideoItem updateItem(VideoItem item) {
        itemMapper.updateById(item);
        return item;
    }

    public List<VideoTask> findPendingTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<VideoTask>().in(VideoTask::getStatus, 0, 1));
    }

    public List<VideoTask> findRunningBefore(LocalDateTime before, Long userId) {
        return taskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getStatus, 1)
                .lt(before != null, VideoTask::getUpdateTime, before)
                .eq(userId != null, VideoTask::getUserId, userId)
                .orderByAsc(VideoTask::getUpdateTime));
    }

    @Caching(evict = {
            @CacheEvict(value = "videoTask", allEntries = true),
            @CacheEvict(value = "videoItems", allEntries = true)
    })
    @Transactional
    public VideoTask resetForRetry(Long id) {
        VideoTask task = getById(id);
        if (task.getStatus() != null && task.getStatus() != 3) {
            throw new BusinessException("仅失败任务可重试");
        }
        task.setStatus(0);
        task.setErrorMsg(null);
        task.setSuccessCount(0);
        taskMapper.update(null, new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, id)
                .set(VideoTask::getStatus, 0)
                .set(VideoTask::getErrorMsg, null)
                .set(VideoTask::getSuccessCount, 0));

        itemMapper.update(null, new LambdaUpdateWrapper<VideoItem>()
                .eq(VideoItem::getTaskId, id)
                .set(VideoItem::getPlatformTaskId, null)
                .set(VideoItem::getVideoUrl, null)
                .set(VideoItem::getCoverUrl, null)
                .set(VideoItem::getDuration, null)
                .set(VideoItem::getFileSize, null)
                .set(VideoItem::getFirstFrameUrl, null)
                .set(VideoItem::getLastFrameUrl, null)
                .set(VideoItem::getStatus, 0)
                .set(VideoItem::getErrorMsg, null));

        long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<VideoItem>()
                .eq(VideoItem::getTaskId, id));
        int expectedCount = task.getCount() != null && task.getCount() > 0 ? task.getCount() : 1;
        for (long i = itemCount; i < expectedCount; i++) {
            itemMapper.insert(VideoItem.builder()
                    .taskId(id)
                    .status(0)
                    .build());
        }
        return task;
    }

    @CacheEvict(value = "videoTask", allEntries = true)
    @Transactional
    public VideoTask markRecoveredToQueued(Long id, String message) {
        VideoTask task = getById(id);
        task.setStatus(0);
        task.setErrorMsg(message);
        taskMapper.update(null, new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, id)
                .set(VideoTask::getStatus, 0)
                .set(VideoTask::getErrorMsg, message));
        return task;
    }

    public VideoTask findLatestByCategory(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return null;
        }
        LambdaQueryWrapper<VideoTask> wrapper = new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getCategory, category)
                .eq(userId != null, VideoTask::getUserId, userId)
                .eq(modelId != null, VideoTask::getModelId, modelId)
                .orderByDesc(VideoTask::getCreateTime)
                .last("LIMIT 1");
        return taskMapper.selectOne(wrapper);
    }

    public VideoTask findLatestByCategoryFamily(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return null;
        }
        String scopedPrefix = category + ":";
        LambdaQueryWrapper<VideoTask> wrapper = new LambdaQueryWrapper<VideoTask>()
                .and(w -> w.eq(VideoTask::getCategory, category)
                        .or()
                        .likeRight(VideoTask::getCategory, scopedPrefix))
                .eq(userId != null, VideoTask::getUserId, userId)
                .eq(modelId != null, VideoTask::getModelId, modelId)
                .orderByDesc(VideoTask::getCreateTime)
                .last("LIMIT 1");
        return taskMapper.selectOne(wrapper);
    }

    public VideoTask findLatestActiveByCategoryFamily(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return null;
        }
        String scopedPrefix = category + ":";
        LambdaQueryWrapper<VideoTask> wrapper = new LambdaQueryWrapper<VideoTask>()
                .and(w -> w.eq(VideoTask::getCategory, category)
                        .or()
                        .likeRight(VideoTask::getCategory, scopedPrefix))
                .in(VideoTask::getStatus, 0, 1)
                .eq(userId != null, VideoTask::getUserId, userId)
                .eq(modelId != null, VideoTask::getModelId, modelId)
                .orderByDesc(VideoTask::getCreateTime)
                .last("LIMIT 1");
        return taskMapper.selectOne(wrapper);
    }

    public List<VideoTask> listByCategoryFamily(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return List.of();
        }
        String scopedPrefix = category + ":";
        return taskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                .and(w -> w.eq(VideoTask::getCategory, category)
                        .or()
                        .likeRight(VideoTask::getCategory, scopedPrefix))
                .eq(userId != null, VideoTask::getUserId, userId)
                .eq(modelId != null, VideoTask::getModelId, modelId)
                .orderByDesc(VideoTask::getCreateTime)
                .orderByDesc(VideoTask::getId));
    }
}
