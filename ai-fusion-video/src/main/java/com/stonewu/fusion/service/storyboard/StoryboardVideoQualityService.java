package com.stonewu.fusion.service.storyboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardVideoQuality;
import com.stonewu.fusion.mapper.storyboard.StoryboardVideoQualityMapper;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Scores and selects generated storyboard video candidates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoryboardVideoQualityService {

    public static final int DEFAULT_MIN_SELECT_SCORE = 75;

    private static final String REVIEWER_AUTO_RULE = "auto_rule";
    private static final String VERDICT_PASS = "pass";
    private static final String VERDICT_REVIEW = "review";
    private static final String VERDICT_REJECT = "reject";

    private final StoryboardService storyboardService;
    private final VideoGenerationService videoGenerationService;
    private final StoryboardVideoQualityMapper qualityMapper;

    public List<StoryboardVideoQuality> listByStoryboardItem(Long storyboardItemId) {
        if (storyboardItemId == null) {
            return List.of();
        }
        return qualityMapper.selectList(new LambdaQueryWrapper<StoryboardVideoQuality>()
                .eq(StoryboardVideoQuality::getStoryboardItemId, storyboardItemId)
                .orderByDesc(StoryboardVideoQuality::getSelected)
                .orderByDesc(StoryboardVideoQuality::getTotalScore)
                .orderByDesc(StoryboardVideoQuality::getUpdateTime)
                .orderByDesc(StoryboardVideoQuality::getId));
    }

    @Transactional
    public QualityReviewResult evaluateStoryboardItem(Long storyboardItemId, Long userId,
                                                      boolean autoSelect, Integer minScore) {
        StoryboardItem item = storyboardService.getItemById(storyboardItemId);
        int effectiveMinScore = minScore != null ? clamp(minScore, 0, 100) : DEFAULT_MIN_SELECT_SCORE;
        List<CandidateInput> candidates = collectCandidates(item, userId);
        if (candidates.isEmpty()) {
            return new QualityReviewResult(storyboardItemId, List.of(), null, "暂无可质检的视频候选");
        }

        List<StoryboardVideoQuality> saved = new ArrayList<>();
        for (CandidateInput candidate : candidates) {
            StoryboardVideoQuality scored = scoreCandidate(item, candidate);
            saved.add(saveScore(scored));
        }
        saved.sort(qualityComparator());

        StoryboardVideoQuality selected = null;
        if (autoSelect && !saved.isEmpty()) {
            StoryboardVideoQuality best = saved.get(0);
            if (best.getTotalScore() != null && best.getTotalScore() >= effectiveMinScore) {
                selected = selectCandidate(storyboardItemId, best.getId(), REVIEWER_AUTO_RULE);
                saved = listByStoryboardItem(storyboardItemId);
            }
        }
        if (selected == null) {
            selected = saved.stream()
                    .filter(q -> Boolean.TRUE.equals(q.getSelected()))
                    .findFirst()
                    .orElse(null);
        }

        String message = selected != null
                ? "已选中最佳候选，分数 " + selected.getTotalScore()
                : "已完成候选评分，等待人工确认或提高候选质量";
        return new QualityReviewResult(storyboardItemId, saved, selected, message);
    }

    @Transactional
    public StoryboardVideoQuality selectCandidate(Long storyboardItemId, Long qualityId) {
        return selectCandidate(storyboardItemId, qualityId, "manual");
    }

    private StoryboardVideoQuality selectCandidate(Long storyboardItemId, Long qualityId, String reviewerType) {
        if (storyboardItemId == null || qualityId == null) {
            throw new BusinessException("镜头ID和候选ID不能为空");
        }
        StoryboardVideoQuality quality = qualityMapper.selectById(qualityId);
        if (quality == null || !Objects.equals(quality.getStoryboardItemId(), storyboardItemId)) {
            throw new BusinessException("视频候选不存在或不属于该镜头");
        }
        return selectQuality(storyboardItemId, quality, reviewerType);
    }

    @Transactional
    public StoryboardVideoQuality selectCandidateByVideoUrl(Long storyboardItemId, String videoUrl) {
        if (storyboardItemId == null || StrUtil.isBlank(videoUrl)) {
            throw new BusinessException("镜头ID和视频URL不能为空");
        }
        StoryboardVideoQuality quality = qualityMapper.selectOne(new LambdaQueryWrapper<StoryboardVideoQuality>()
                .eq(StoryboardVideoQuality::getStoryboardItemId, storyboardItemId)
                .eq(StoryboardVideoQuality::getVideoUrl, videoUrl.trim())
                .last("LIMIT 1"));
        if (quality == null) {
            throw new BusinessException("未找到指定视频候选，请先执行质检评分");
        }
        return selectQuality(storyboardItemId, quality, "manual");
    }

    private StoryboardVideoQuality selectQuality(Long storyboardItemId, StoryboardVideoQuality quality, String reviewerType) {
        qualityMapper.update(null, new LambdaUpdateWrapper<StoryboardVideoQuality>()
                .eq(StoryboardVideoQuality::getStoryboardItemId, storyboardItemId)
                .set(StoryboardVideoQuality::getSelected, false));
        qualityMapper.update(null, new LambdaUpdateWrapper<StoryboardVideoQuality>()
                .eq(StoryboardVideoQuality::getId, quality.getId())
                .set(StoryboardVideoQuality::getSelected, true)
                .set(StoryboardVideoQuality::getReviewerType, reviewerType));

        StoryboardItem update = new StoryboardItem();
        update.setId(storyboardItemId);
        update.setGeneratedVideoUrl(quality.getVideoUrl());
        if (StrUtil.isNotBlank(quality.getPromptSnapshot())) {
            update.setVideoPrompt(quality.getPromptSnapshot());
        }
        storyboardService.updateItem(update);

        StoryboardVideoQuality selected = qualityMapper.selectById(quality.getId());
        log.info("[StoryboardVideoQuality] selected candidate: storyboardItemId={}, qualityId={}, score={}, url={}",
                storyboardItemId, quality.getId(), quality.getTotalScore(), quality.getVideoUrl());
        return selected;
    }

    private StoryboardVideoQuality saveScore(StoryboardVideoQuality scored) {
        StoryboardVideoQuality existing = qualityMapper.selectOne(new LambdaQueryWrapper<StoryboardVideoQuality>()
                .eq(StoryboardVideoQuality::getStoryboardItemId, scored.getStoryboardItemId())
                .eq(StoryboardVideoQuality::getVideoUrl, scored.getVideoUrl())
                .last("LIMIT 1"));
        if (existing == null) {
            qualityMapper.insert(scored);
            return scored;
        }
        scored.setId(existing.getId());
        scored.setSelected(existing.getSelected());
        qualityMapper.updateById(scored);
        StoryboardVideoQuality latest = qualityMapper.selectById(existing.getId());
        return latest != null ? latest : scored;
    }

    private List<CandidateInput> collectCandidates(StoryboardItem item, Long userId) {
        Map<String, CandidateInput> unique = new LinkedHashMap<>();
        String category = storyboardItemCategory(item.getId());
        List<VideoTask> tasks = videoGenerationService.listByCategoryFamily(category, userId, null);
        for (VideoTask task : tasks) {
            if (task.getId() == null) {
                continue;
            }
            List<VideoItem> items = videoGenerationService.listItems(task.getId());
            for (VideoItem videoItem : items) {
                if (videoItem == null || !isSuccessfulVideoItem(videoItem)) {
                    continue;
                }
                putCandidate(unique, new CandidateInput(task, videoItem, task.getGenerateMode()));
            }
        }

        if (StrUtil.isNotBlank(item.getGeneratedVideoUrl())) {
            putCandidate(unique, CandidateInput.virtual(item.getGeneratedVideoUrl(), "current_generated_video"));
        }
        if (StrUtil.isNotBlank(item.getVideoUrl())) {
            putCandidate(unique, CandidateInput.virtual(item.getVideoUrl(), "current_final_video"));
        }
        return new ArrayList<>(unique.values());
    }

    private boolean isSuccessfulVideoItem(VideoItem videoItem) {
        return StrUtil.isNotBlank(videoItem.getVideoUrl())
                && (videoItem.getStatus() == null || videoItem.getStatus() == 1);
    }

    private void putCandidate(Map<String, CandidateInput> unique, CandidateInput candidate) {
        if (candidate == null || StrUtil.isBlank(candidate.videoUrl())) {
            return;
        }
        unique.putIfAbsent(normalizeUrl(candidate.videoUrl()), candidate);
    }

    private StoryboardVideoQuality scoreCandidate(StoryboardItem item, CandidateInput candidate) {
        ScoreBreakdown character = scoreCharacterConsistency(item, candidate);
        ScoreBreakdown visual = scoreVisualQuality(candidate);
        ScoreBreakdown motion = scoreMotionContinuity(item, candidate);
        ScoreBreakdown audio = scoreAudioReadiness(item, candidate);

        int total = clamp((int) Math.round(
                character.score() * 0.40
                        + visual.score() * 0.25
                        + motion.score() * 0.20
                        + audio.score() * 0.15
        ), 0, 100);
        String verdict = total >= 82 ? VERDICT_PASS : total >= 62 ? VERDICT_REVIEW : VERDICT_REJECT;

        List<String> issues = new ArrayList<>();
        collectIssue(issues, "角色一致性偏弱", character.score());
        collectIssue(issues, "画面候选信息不足", visual.score());
        collectIssue(issues, "时长或运镜匹配不足", motion.score());
        collectIssue(issues, "对白/声音意图覆盖不足", audio.score());
        if (issues.isEmpty()) {
            issues.add("基础一致性质检通过，建议抽看画面细节确认");
        }

        String suggestion = buildSuggestion(verdict, issues);
        String details = JSONUtil.createObj()
                .set("source", candidate.source())
                .set("category", candidate.task() != null ? candidate.task().getCategory() : null)
                .set("generateMode", candidate.task() != null ? candidate.task().getGenerateMode() : null)
                .set("referenceImageCount", candidate.referenceImageCount())
                .set("expectedCharacterCount", parseIds(item.getCharacterIds()).size())
                .set("characterReasons", character.reasons())
                .set("visualReasons", visual.reasons())
                .set("motionReasons", motion.reasons())
                .set("audioReasons", audio.reasons())
                .toString();

        return StoryboardVideoQuality.builder()
                .storyboardItemId(item.getId())
                .videoTaskId(candidate.task() != null ? candidate.task().getId() : null)
                .videoItemId(candidate.item() != null ? candidate.item().getId() : null)
                .videoUrl(candidate.videoUrl())
                .coverUrl(candidate.coverUrl())
                .promptSnapshot(candidate.prompt())
                .totalScore(total)
                .characterConsistencyScore(character.score())
                .visualQualityScore(visual.score())
                .motionContinuityScore(motion.score())
                .audioReadinessScore(audio.score())
                .verdict(verdict)
                .issueSummary(String.join("；", issues))
                .suggestion(suggestion)
                .selected(false)
                .reviewerType(REVIEWER_AUTO_RULE)
                .scoreDetails(details)
                .build();
    }

    private ScoreBreakdown scoreCharacterConsistency(StoryboardItem item, CandidateInput candidate) {
        List<String> reasons = new ArrayList<>();
        int characterCount = parseIds(item.getCharacterIds()).size();
        int referenceCount = candidate.referenceImageCount();
        String prompt = StrUtil.blankToDefault(candidate.prompt(), "");
        int score = characterCount > 0 ? 45 : 86;

        if (characterCount == 0) {
            reasons.add("镜头未关联角色，不按角色一致性重罚");
        } else if (referenceCount >= characterCount) {
            score += 34;
            reasons.add("参考图数量覆盖关联角色");
        } else if (referenceCount > 0) {
            score += Math.min(24, referenceCount * 10);
            reasons.add("部分角色有参考图，仍需人工确认");
        } else {
            reasons.add("关联角色缺少视频候选参考图覆盖");
        }

        if (hasAnyText(prompt, "assetItemId", "一致", "角色", "referenceOrderPolicy", "characterLocks")) {
            score += 12;
            reasons.add("Prompt 包含角色一致性锁定信息");
        }
        if (candidate.task() != null && StrUtil.isNotBlank(candidate.task().getFirstFrameImageUrl())) {
            score += 6;
            reasons.add("使用首帧/图生视频约束主体形象");
        }
        return new ScoreBreakdown(clamp(score, 0, 100), reasons);
    }

    private ScoreBreakdown scoreVisualQuality(CandidateInput candidate) {
        List<String> reasons = new ArrayList<>();
        int score = 52;
        if (StrUtil.isNotBlank(candidate.videoUrl())) {
            score += 22;
            reasons.add("候选视频 URL 有效");
        }
        if (StrUtil.isNotBlank(candidate.coverUrl())) {
            score += 8;
            reasons.add("存在封面图，便于人工预览");
        }
        if (candidate.item() != null && candidate.item().getFileSize() != null && candidate.item().getFileSize() > 0) {
            score += 6;
            reasons.add("存在文件大小信息");
        }
        if (candidate.task() != null && Objects.equals(candidate.task().getStatus(), 2)) {
            score += 8;
            reasons.add("生成任务成功完成");
        }
        if (StrUtil.isNotBlank(candidate.prompt())) {
            score += 4;
            reasons.add("保留生成 Prompt 快照");
        }
        return new ScoreBreakdown(clamp(score, 0, 100), reasons);
    }

    private ScoreBreakdown scoreMotionContinuity(StoryboardItem item, CandidateInput candidate) {
        List<String> reasons = new ArrayList<>();
        int score = 58;
        Integer expectedDuration = durationToSeconds(item.getDuration());
        Integer actualDuration = candidate.item() != null ? candidate.item().getDuration() : null;
        if (expectedDuration == null || expectedDuration <= 0) {
            score += 12;
            reasons.add("镜头未设置目标时长，跳过时长重罚");
        } else if (actualDuration != null && actualDuration > 0) {
            int delta = Math.abs(expectedDuration - actualDuration);
            if (delta <= 1) {
                score += 24;
                reasons.add("生成时长与分镜时长匹配");
            } else if (delta <= 3) {
                score += 14;
                reasons.add("生成时长与分镜时长接近");
            } else {
                reasons.add("生成时长与分镜时长差异较大");
            }
        } else if (candidate.task() != null && candidate.task().getDuration() != null) {
            int delta = Math.abs(expectedDuration - candidate.task().getDuration());
            score += delta <= 1 ? 18 : delta <= 3 ? 10 : 0;
            reasons.add("使用任务时长做近似校验");
        } else {
            reasons.add("缺少候选实际时长");
        }

        String prompt = StrUtil.blankToDefault(candidate.prompt(), "");
        if (StrUtil.isNotBlank(item.getCameraMovement()) && hasAnyText(prompt, item.getCameraMovement(), "镜头", "运镜")) {
            score += 9;
            reasons.add("Prompt 覆盖镜头运动");
        }
        if (candidate.task() != null && StrUtil.isNotBlank(candidate.task().getLastFrameImageUrl())) {
            score += 5;
            reasons.add("存在尾帧约束，连续性更稳定");
        }
        return new ScoreBreakdown(clamp(score, 0, 100), reasons);
    }

    private ScoreBreakdown scoreAudioReadiness(StoryboardItem item, CandidateInput candidate) {
        List<String> reasons = new ArrayList<>();
        boolean audioExpected = StrUtil.isNotBlank(item.getDialogue())
                || StrUtil.isNotBlank(item.getSound())
                || StrUtil.isNotBlank(item.getSoundEffect())
                || StrUtil.isNotBlank(item.getMusic());
        if (!audioExpected) {
            reasons.add("镜头无明确对白/声音要求");
            return new ScoreBreakdown(84, reasons);
        }

        int score = 48;
        Boolean generateAudio = candidate.task() != null ? candidate.task().getGenerateAudio() : null;
        if (!Boolean.FALSE.equals(generateAudio)) {
            score += 24;
            reasons.add("候选任务启用或默认启用原生音频");
        } else {
            reasons.add("候选任务关闭原生音频，需后期补音");
        }

        String prompt = StrUtil.blankToDefault(candidate.prompt(), "");
        if (StrUtil.isNotBlank(item.getDialogue()) && hasAnyText(prompt, "对白", "台词", item.getDialogue())) {
            score += 12;
            reasons.add("Prompt 覆盖对白/台词");
        }
        if ((StrUtil.isNotBlank(item.getSound()) || StrUtil.isNotBlank(item.getSoundEffect()))
                && hasAnyText(prompt, "声音", "音效", "环境声")) {
            score += 8;
            reasons.add("Prompt 覆盖环境声或音效");
        }
        if (StrUtil.isNotBlank(item.getMusic()) && hasAnyText(prompt, "音乐", "配乐", item.getMusic())) {
            score += 5;
            reasons.add("Prompt 覆盖配乐意图");
        }
        return new ScoreBreakdown(clamp(score, 0, 100), reasons);
    }

    private void collectIssue(List<String> issues, String issue, int score) {
        if (score < 62) {
            issues.add(issue);
        }
    }

    private String buildSuggestion(String verdict, List<String> issues) {
        if (VERDICT_PASS.equals(verdict)) {
            return "可作为优选版本；建议人工快速抽看角色脸、服装、场景空间是否与参考资产一致。";
        }
        if (VERDICT_REJECT.equals(verdict)) {
            return "建议重新生成：补充角色/场景/道具参考图，强化 assetItemId 一致性锁定，并确认对白和时长参数。问题：" + String.join("；", issues);
        }
        return "建议人工复核后再选用；如画面不稳定，可带相同 consistencyContext 重新生成一版。问题：" + String.join("；", issues);
    }

    private Comparator<StoryboardVideoQuality> qualityComparator() {
        return Comparator.comparing(
                        (StoryboardVideoQuality q) -> q.getTotalScore() == null ? 0 : q.getTotalScore())
                .thenComparing(q -> q.getId() == null ? 0L : q.getId())
                .reversed();
    }

    private Integer durationToSeconds(BigDecimal duration) {
        if (duration == null || duration.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return duration.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private List<Long> parseIds(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(raw);
            List<Long> ids = new ArrayList<>();
            for (Object value : array) {
                Long id = toLong(value);
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasAnyText(String text, String... fragments) {
        if (StrUtil.isBlank(text) || fragments == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            if (StrUtil.isNotBlank(fragment) && normalized.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String storyboardItemCategory(Long itemId) {
        return "storyboard_item:" + itemId;
    }

    private String normalizeUrl(String url) {
        return StrUtil.trim(url);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record QualityReviewResult(Long storyboardItemId,
                                      List<StoryboardVideoQuality> candidates,
                                      StoryboardVideoQuality selectedCandidate,
                                      String message) {
    }

    private record ScoreBreakdown(int score, List<String> reasons) {
    }

    private record CandidateInput(VideoTask task, VideoItem item, String source) {

        static CandidateInput virtual(String videoUrl, String source) {
            VideoItem item = VideoItem.builder()
                    .videoUrl(videoUrl)
                    .status(1)
                    .build();
            return new CandidateInput(null, item, source);
        }

        String videoUrl() {
            return item != null ? item.getVideoUrl() : null;
        }

        String coverUrl() {
            return item != null ? item.getCoverUrl() : null;
        }

        String prompt() {
            return task != null ? task.getPrompt() : null;
        }

        int referenceImageCount() {
            return task != null ? countJsonArrayStatic(task.getReferenceImageUrls()) : 0;
        }

        private static int countJsonArrayStatic(String raw) {
            if (StrUtil.isBlank(raw)) {
                return 0;
            }
            try {
                return JSONUtil.parseArray(raw).size();
            } catch (Exception ignored) {
                return 0;
            }
        }
    }
}
