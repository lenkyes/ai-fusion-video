package com.stonewu.fusion.service.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.mapper.generation.ImageItemMapper;
import com.stonewu.fusion.mapper.generation.ImageTaskMapper;
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
 * 生图任务服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageGenerationService {

    private final ImageTaskMapper taskMapper;
    private final ImageItemMapper itemMapper;

    @Cacheable(value = "imageTask", key = "#id")
    public ImageTask getById(Long id) {
        ImageTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException("生图任务不存在: " + id);
        return task;
    }

    @Cacheable(value = "imageTask", key = "'taskId:' + #taskId")
    public ImageTask getByTaskId(String taskId) {
        ImageTask task = taskMapper.selectOne(new LambdaQueryWrapper<ImageTask>().eq(ImageTask::getTaskId, taskId));
        if (task == null) throw new BusinessException("生图任务不存在: " + taskId);
        return task;
    }

    public PageResult<ImageTask> pageByUser(Long userId, int pageNo, int pageSize) {
        return PageResult.of(taskMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ImageTask>()
                        .eq(ImageTask::getUserId, userId)
                        .orderByDesc(ImageTask::getCreateTime)));
    }

    @CacheEvict(value = "imageTask", allEntries = true)
    @Transactional
    public ImageTask create(ImageTask task) {
        taskMapper.insert(task);
        return task;
    }

    @CacheEvict(value = "imageTask", allEntries = true)
    @Transactional
    public ImageTask update(ImageTask task) {
        taskMapper.updateById(task);
        return task;
    }

    @CacheEvict(value = "imageTask", allEntries = true)
    @Transactional
    public void updateStatus(Long id, Integer status, String errorMsg) {
        getById(id);
        taskMapper.update(null, new LambdaUpdateWrapper<ImageTask>()
                .eq(ImageTask::getId, id)
                .set(ImageTask::getStatus, status)
                .set(ImageTask::getErrorMsg, errorMsg));
    }

    public PageResult<ImageTask> pageByUserAndCategory(Long userId, String category, int pageNo, int pageSize) {
        return PageResult.of(taskMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ImageTask>().eq(ImageTask::getUserId, userId)
                        .eq(ImageTask::getCategory, category)
                        .isNotNull(ImageTask::getSessionId)
                        .orderByDesc(ImageTask::getCreateTime)));
    }

    public PageResult<ImageTask> pageByUserCategoryAndSession(Long userId, String category, Long sessionId,
                                                               int pageNo, int pageSize) {
        return PageResult.of(taskMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ImageTask>().eq(ImageTask::getUserId, userId)
                        .eq(ImageTask::getCategory, category)
                        .eq(ImageTask::getSessionId, sessionId)
                        .orderByDesc(ImageTask::getCreateTime)));
    }

    @CacheEvict(value = "imageTask", allEntries = true)
    @Transactional
    public void delete(Long id) {
        taskMapper.deleteById(id);
    }

    // ========== Image Items ==========

    @Cacheable(value = "imageItems", key = "#taskId")
    public List<ImageItem> listItems(Long taskId) {
        return itemMapper.selectList(new LambdaQueryWrapper<ImageItem>().eq(ImageItem::getTaskId, taskId));
    }

    @CacheEvict(value = "imageItems", allEntries = true)
    @Transactional
    public ImageItem createItem(ImageItem item) {
        itemMapper.insert(item);
        return item;
    }

    @CacheEvict(value = "imageItems", allEntries = true)
    @Transactional
    public ImageItem updateItem(ImageItem item) {
        itemMapper.updateById(item);
        return item;
    }

    public List<ImageTask> findPendingTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<ImageTask>().in(ImageTask::getStatus, 0, 1));
    }

    public List<ImageTask> findRunningBefore(LocalDateTime before, Long userId) {
        return taskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                .eq(ImageTask::getStatus, 1)
                .lt(before != null, ImageTask::getUpdateTime, before)
                .eq(userId != null, ImageTask::getUserId, userId)
                .orderByAsc(ImageTask::getUpdateTime));
    }

    @Caching(evict = {
            @CacheEvict(value = "imageTask", allEntries = true),
            @CacheEvict(value = "imageItems", allEntries = true)
    })
    @Transactional
    public ImageTask resetForRetry(Long id) {
        ImageTask task = getById(id);
        if (task.getStatus() != null && task.getStatus() != 3) {
            throw new BusinessException("仅失败任务可重试");
        }
        task.setStatus(0);
        task.setErrorMsg(null);
        task.setSuccessCount(0);
        taskMapper.update(null, new LambdaUpdateWrapper<ImageTask>()
                .eq(ImageTask::getId, id)
                .set(ImageTask::getStatus, 0)
                .set(ImageTask::getErrorMsg, null)
                .set(ImageTask::getSuccessCount, 0));

        itemMapper.update(null, new LambdaUpdateWrapper<ImageItem>()
                .eq(ImageItem::getTaskId, id)
                .set(ImageItem::getPlatformTaskId, null)
                .set(ImageItem::getImageUrl, null)
                .set(ImageItem::getThumbnailUrl, null)
                .set(ImageItem::getWidth, null)
                .set(ImageItem::getHeight, null)
                .set(ImageItem::getFileSize, null)
                .set(ImageItem::getStatus, 0)
                .set(ImageItem::getErrorMsg, null));

        long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<ImageItem>()
                .eq(ImageItem::getTaskId, id));
        int expectedCount = task.getCount() != null && task.getCount() > 0 ? task.getCount() : 1;
        for (long i = itemCount; i < expectedCount; i++) {
            itemMapper.insert(ImageItem.builder()
                    .taskId(id)
                    .status(0)
                    .build());
        }
        return task;
    }

    @CacheEvict(value = "imageTask", allEntries = true)
    @Transactional
    public ImageTask markRecoveredToQueued(Long id, String message) {
        ImageTask task = getById(id);
        task.setStatus(0);
        task.setErrorMsg(message);
        taskMapper.update(null, new LambdaUpdateWrapper<ImageTask>()
                .eq(ImageTask::getId, id)
                .set(ImageTask::getStatus, 0)
                .set(ImageTask::getErrorMsg, message));
        return task;
    }
}
