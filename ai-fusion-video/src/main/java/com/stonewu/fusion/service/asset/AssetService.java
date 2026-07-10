package com.stonewu.fusion.service.asset;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.mapper.asset.AssetItemMapper;
import com.stonewu.fusion.mapper.asset.AssetMapper;
import com.stonewu.fusion.security.SecurityUtils;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资产服务
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final int OWNER_TYPE_TEAM = 2;
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String ITEM_TYPE_INITIAL = "initial";
    private static final String ITEM_TYPE_THREE_VIEW = "three_view";
    private static final Set<String> CHARACTER_APPEARANCE_ITEM_TYPES = Set.of(
            ITEM_TYPE_INITIAL, "variant", "age", "costume", "damaged");

    private final AssetMapper assetMapper;
    private final AssetItemMapper assetItemMapper;
    private final ProjectService projectService;
    private final TeamService teamService;

    // ========== 资产 ==========

    @Cacheable(value = "asset", key = "#id")
    public Asset getById(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null)
            throw new BusinessException("资产不存在: " + id);
        return asset;
    }

    public List<Asset> listByProject(Long projectId) {
        return assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .orderByDesc(Asset::getCreateTime));
    }

    public List<Asset> listByProject(Long projectId, String type, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .orderByDesc(Asset::getCreateTime);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Asset::getType, type);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Asset::getName, keyword.trim());
        }
        return assetMapper.selectList(wrapper);
    }

    public List<Map<String, Object>> listWithItemsByProject(Long projectId) {
        List<Asset> assets = listByProject(projectId);
        if (assets.isEmpty())
            return List.of();

        List<Long> assetIds = assets.stream().map(Asset::getId).collect(Collectors.toList());
        List<AssetItem> allItems = assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                .in(AssetItem::getAssetId, assetIds)
                .orderByAsc(AssetItem::getSortOrder));

        Map<Long, List<AssetItem>> itemsMap = allItems.stream()
                .collect(Collectors.groupingBy(AssetItem::getAssetId));

        return assets.stream().map(asset -> {
            Map<String, Object> map = BeanUtil.beanToMap(asset, false, true);
            map.put("items", itemsMap.getOrDefault(asset.getId(), List.of()));
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * 按用户分页查询资产（跨项目），支持可选的 projectId / type / keyword 过滤
     */
    public IPage<Asset> pageByUser(Long userId, Long projectId, String type, String keyword, int page, int size) {
        LambdaQueryWrapper<Asset> wrapper = buildUserQueryWrapper(userId, projectId, type, keyword);
        return assetMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public IPage<Asset> pageAccessibleByUser(Long userId, Long projectId, String type, String keyword, int page, int size) {
        LambdaQueryWrapper<Asset> wrapper = buildAccessibleQueryWrapper(userId, projectId, type, keyword);
        return assetMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 统计当前用户各类型资产数量（按 projectId / keyword 过滤，不按 type 过滤）
     * 返回 Map: type -> count
     */
    public Map<String, Long> countByUserGroupByType(Long userId, Long projectId, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = buildUserQueryWrapper(userId, projectId, null, keyword);
        List<Asset> all = assetMapper.selectList(
                wrapper.select(Asset::getType));
        return all.stream().collect(
                Collectors.groupingBy(Asset::getType, Collectors.counting()));
    }

    public Map<String, Long> countAccessibleByUserGroupByType(Long userId, Long projectId, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = buildAccessibleQueryWrapper(userId, projectId, null, keyword);
        List<Asset> all = assetMapper.selectList(
                wrapper.select(Asset::getType));
        return all.stream().collect(
                Collectors.groupingBy(Asset::getType, Collectors.counting()));
    }

    public boolean canAccessAsset(Long assetId, Long userId) {
        return canAccessAsset(getById(assetId), userId);
    }

    public boolean canAccessAsset(Asset asset, Long userId) {
        if (asset == null) {
            return false;
        }
        if (userId.equals(asset.getUserId()) || userId.equals(asset.getOwnerId())) {
            return true;
        }
        if (asset.getProjectId() != null) {
            return projectService.canAccessProject(asset.getProjectId(), userId);
        }
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        if (currentTeamId == null) {
            return false;
        }
        if (OWNER_TYPE_TEAM == asset.getOwnerType() && currentTeamId.equals(asset.getOwnerId())) {
            return true;
        }
        return teamService.listMemberUserIds(currentTeamId).contains(asset.getUserId());
    }

    private LambdaQueryWrapper<Asset> buildUserQueryWrapper(Long userId, Long projectId, String type, String keyword) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .orderByDesc(Asset::getUpdateTime);
        if (projectId != null) {
            wrapper.eq(Asset::getProjectId, projectId);
        }
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(Asset::getType, type);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Asset::getName, keyword.trim());
        }
        return wrapper;
    }

    private LambdaQueryWrapper<Asset> buildAccessibleQueryWrapper(Long userId, Long projectId, String type, String keyword) {
        Long currentTeamId = teamService.getCurrentTeamIdByUser(userId);
        if (currentTeamId == null) {
            return buildUserQueryWrapper(userId, projectId, type, keyword);
        }
        List<Long> memberUserIds = teamService.listMemberUserIds(currentTeamId);
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .and(scope -> scope
                        .and(teamOwned -> teamOwned
                                .eq(Asset::getOwnerType, OWNER_TYPE_TEAM)
                    .eq(Asset::getOwnerId, currentTeamId))
                        .or(memberOwned -> memberOwned
                                .in(Asset::getUserId, memberUserIds)))
                .orderByDesc(Asset::getUpdateTime);
        if (projectId != null) {
            wrapper.eq(Asset::getProjectId, projectId);
        }
        if (StrUtil.isNotBlank(type)) {
            wrapper.eq(Asset::getType, type);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Asset::getName, keyword.trim());
        }
        return wrapper;
    }

    public List<Asset> listByOwner(Integer ownerType, Long ownerId, String type) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getOwnerType, ownerType)
                .eq(Asset::getOwnerId, ownerId)
                .orderByDesc(Asset::getCreateTime);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Asset::getType, type);
        }
        return assetMapper.selectList(wrapper);
    }

    public List<Asset> listAccessibleByUser(Long userId, String type) {
        return assetMapper.selectList(buildAccessibleQueryWrapper(userId, null, type, null));
    }

    public Asset findByProjectTypeAndName(Long projectId, String type, String name) {
        return assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getType, type)
                .eq(Asset::getName, name)
                .last("LIMIT 1"));
    }

    @CacheEvict(value = { "asset", "assetItem" }, allEntries = true)
    @Transactional
    public Asset create(Asset asset) {
        validateAssetMediaUrls(asset);
        applyCurrentTeamOwnership(asset);
        assetMapper.insert(asset);

        AssetItem initialItem = buildInitialItem(asset);
        assetItemMapper.insert(initialItem);
        if (isCharacterAsset(asset)) {
            assetItemMapper.insert(buildCharacterThreeViewItem(
                    asset, initialItem, 1, resolveAppearanceProperties(asset, initialItem)));
        }

        return asset;
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public AssetItem ensureCharacterThreeViewItem(Asset asset) {
        List<AssetItem> threeViewItems = ensureCharacterThreeViewItems(asset);
        if (threeViewItems.isEmpty()) {
            return null;
        }
        List<AssetItem> items = new ArrayList<>(listItems(asset.getId()));
        Long initialItemId = items.stream()
                .filter(item -> ITEM_TYPE_INITIAL.equals(item.getItemType()))
                .map(AssetItem::getId)
                .findFirst()
                .orElse(null);
        return threeViewItems.stream()
                .filter(item -> Objects.equals(initialItemId, item.getParentItemId()))
                .findFirst()
                .orElse(threeViewItems.get(0));
    }

    /**
     * 为角色下每个形态根项补齐专属三视图。
     */
    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public List<AssetItem> ensureCharacterThreeViewItems(Asset asset) {
        if (!isCharacterAsset(asset) || asset.getId() == null) {
            return List.of();
        }

        List<AssetItem> items = new ArrayList<>(listItems(asset.getId()));
        List<AssetItem> appearanceItems = items.stream()
                .filter(this::isCharacterAppearanceItem)
                .toList();
        List<AssetItem> unboundThreeViews = new ArrayList<>(items.stream()
                .filter(this::isThreeViewItem)
                .filter(item -> item.getParentItemId() == null)
                .toList());
        List<AssetItem> result = new ArrayList<>();

        for (AssetItem appearanceItem : appearanceItems) {
            List<AssetItem> linked = items.stream()
                    .filter(this::isThreeViewItem)
                    .filter(item -> Objects.equals(appearanceItem.getId(), item.getParentItemId()))
                    .toList();
            if (linked.size() > 1) {
                throw new BusinessException("角色形态存在多个专属三视图: " + appearanceItem.getId());
            }
            if (!linked.isEmpty()) {
                result.add(linked.get(0));
                continue;
            }

            // 兼容迁移前的单个全局三视图，只能确定性地归到 initial。
            if (ITEM_TYPE_INITIAL.equals(appearanceItem.getItemType()) && unboundThreeViews.size() == 1) {
                AssetItem legacyThreeView = unboundThreeViews.remove(0);
                legacyThreeView.setParentItemId(appearanceItem.getId());
                assetItemMapper.updateById(legacyThreeView);
                result.add(legacyThreeView);
                continue;
            }

            AssetItem threeViewItem = buildCharacterThreeViewItem(
                    asset, appearanceItem, nextSortOrder(items), resolveAppearanceProperties(asset, appearanceItem));
            assetItemMapper.insert(threeViewItem);
            items.add(threeViewItem);
            result.add(threeViewItem);
        }

        return result;
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public AssetItem ensureCharacterThreeViewItem(Asset asset, AssetItem appearanceItem) {
        if (!isCharacterAsset(asset) || appearanceItem == null
                || !Objects.equals(asset.getId(), appearanceItem.getAssetId())
                || !isCharacterAppearanceItem(appearanceItem)) {
            return null;
        }
        AssetItem existing = findCanonicalThreeViewItem(appearanceItem.getId());
        if (existing != null) {
            return existing;
        }
        List<AssetItem> items = listItems(asset.getId());
        AssetItem threeViewItem = buildCharacterThreeViewItem(
                asset, appearanceItem, nextSortOrder(items), resolveAppearanceProperties(asset, appearanceItem));
        assetItemMapper.insert(threeViewItem);
        return threeViewItem;
    }

    @CacheEvict(value = "asset", allEntries = true)
    @Transactional
    public Asset update(Asset asset) {
        getById(asset.getId());
        validateAssetMediaUrls(asset);
        assetMapper.updateById(asset);
        return asset;
    }

    @CacheEvict(value = "asset", allEntries = true)
    @Transactional
    public void delete(Long id) {
        assetMapper.deleteById(id);
    }

    // ========== 子资产 ==========

    public AssetItem getItemById(Long id) {
        AssetItem item = assetItemMapper.selectById(id);
        if (item == null)
            throw new BusinessException("子资产不存在: " + id);
        return item;
    }

    @Cacheable(value = "assetItem", key = "'asset:' + #assetId")
    public List<AssetItem> listItems(Long assetId) {
        return assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                .eq(AssetItem::getAssetId, assetId)
                .orderByAsc(AssetItem::getSortOrder));
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public AssetItem createItem(AssetItem item) {
        validateAssetItemMediaUrls(item);
        if (StrUtil.isBlank(item.getItemType())) {
            item.setItemType("variant");
        }
        Asset asset = validateAndResolveItemRelationship(item, null);
        assetItemMapper.insert(item);
        if (isCharacterAsset(asset) && isCharacterAppearanceItem(item)) {
            ensureCharacterThreeViewItem(asset, item);
        }
        syncCoverIfAbsent(item);
        return item;
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public AssetItem updateItem(AssetItem item) {
        AssetItem existing = assetItemMapper.selectById(item.getId());
        if (existing == null)
            throw new BusinessException("子资产不存在: " + item.getId());
        if (item.getItemType() != null && !Objects.equals(item.getItemType(), existing.getItemType())
                && (ITEM_TYPE_THREE_VIEW.equals(item.getItemType())
                        || ITEM_TYPE_THREE_VIEW.equals(existing.getItemType()))) {
            throw new BusinessException("不支持在 three_view 与形态根项之间直接切换类型");
        }
        if (item.getItemType() != null && !Objects.equals(item.getItemType(), existing.getItemType())
                && isCharacterAppearanceItemType(item.getItemType())
                        != isCharacterAppearanceItemType(existing.getItemType())) {
            throw new BusinessException("不支持在角色形态根项与普通子项之间直接切换类型");
        }
        validateAssetItemMediaUrls(item);
        item.setAssetId(existing.getAssetId());
        if (item.getItemType() == null) {
            item.setItemType(existing.getItemType());
        }
        if (item.getParentItemId() == null) {
            item.setParentItemId(existing.getParentItemId());
        }
        Asset asset = validateAndResolveItemRelationship(item, existing.getId());
        assetItemMapper.updateById(item);
        // 部分更新时用 existing 补全返回值和后续同步所需字段。
        if (item.getImageUrl() == null) {
            item.setImageUrl(existing.getImageUrl());
        }
        if (item.getThumbnailUrl() == null) {
            item.setThumbnailUrl(existing.getThumbnailUrl());
        }
        if (item.getName() == null) {
            item.setName(existing.getName());
        }
        if (item.getProperties() == null) {
            item.setProperties(existing.getProperties());
        }
        if (item.getSortOrder() == null) {
            item.setSortOrder(existing.getSortOrder());
        }
        if (item.getSourceType() == null) {
            item.setSourceType(existing.getSourceType());
        }
        if (item.getAiPrompt() == null) {
            item.setAiPrompt(existing.getAiPrompt());
        }
        if (isCharacterAsset(asset) && isCharacterAppearanceItem(item)) {
            AssetItem threeViewItem = ensureCharacterThreeViewItem(asset, item);
            syncThreeViewMetadata(asset, item, threeViewItem);
        }
        syncCoverIfAbsent(item);
        return item;
    }

    @CacheEvict(value = { "assetItem", "asset" }, allEntries = true)
    @Transactional
    public void deleteItem(Long id) {
        AssetItem item = getItemById(id);
        if (isCharacterAppearanceItem(item)) {
            List<AssetItem> linkedThreeViews = assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                    .eq(AssetItem::getAssetId, item.getAssetId())
                    .eq(AssetItem::getParentItemId, item.getId())
                    .eq(AssetItem::getItemType, ITEM_TYPE_THREE_VIEW));
            linkedThreeViews.forEach(linked -> assetItemMapper.deleteById(linked.getId()));
        }
        assetItemMapper.deleteById(id);
    }

    public boolean isCharacterAppearanceItem(AssetItem item) {
        return item != null && isCharacterAppearanceItemType(item.getItemType());
    }

    public Long resolveAppearanceItemId(AssetItem item) {
        if (item == null) {
            return null;
        }
        if (isThreeViewItem(item)) {
            return item.getParentItemId();
        }
        return isCharacterAppearanceItem(item) ? item.getId() : null;
    }

    public AssetItem resolveAppearanceItem(AssetItem item) {
        Long appearanceItemId = resolveAppearanceItemId(item);
        if (appearanceItemId == null) {
            return null;
        }
        if (Objects.equals(appearanceItemId, item.getId())) {
            return item;
        }
        AssetItem appearanceItem = assetItemMapper.selectById(appearanceItemId);
        if (appearanceItem == null
                || !Objects.equals(item.getAssetId(), appearanceItem.getAssetId())
                || !isCharacterAppearanceItem(appearanceItem)) {
            return null;
        }
        return appearanceItem;
    }

    public AssetItem findCanonicalThreeViewItem(Long appearanceItemId) {
        if (appearanceItemId == null) {
            return null;
        }
        return assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                        .eq(AssetItem::getParentItemId, appearanceItemId)
                        .eq(AssetItem::getItemType, ITEM_TYPE_THREE_VIEW)
                        .orderByAsc(AssetItem::getSortOrder)
                        .orderByAsc(AssetItem::getId))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public Long resolveCanonicalThreeViewItemId(AssetItem item) {
        AssetItem canonicalThreeView = findCanonicalThreeViewItem(resolveAppearanceItemId(item));
        return canonicalThreeView != null ? canonicalThreeView.getId() : null;
    }

    /**
     * 角色形态优先使用其专属且已有图片的三视图；否则仅回退到该形态根项。
     */
    public AssetItem resolveCanonicalReferenceItem(AssetItem selectedItem) {
        if (selectedItem == null) {
            return null;
        }
        AssetItem appearanceItem = resolveAppearanceItem(selectedItem);
        if (appearanceItem == null) {
            return selectedItem;
        }
        AssetItem canonicalThreeView = findCanonicalThreeViewItem(appearanceItem.getId());
        if (canonicalThreeView != null && StrUtil.isNotBlank(canonicalThreeView.getImageUrl())) {
            return canonicalThreeView;
        }
        return appearanceItem;
    }

    private void validateAssetMediaUrls(Asset asset) {
        if (asset == null) {
            return;
        }
        rejectDataUrl(asset.getCoverUrl(), "coverUrl");
    }

    private void validateAssetItemMediaUrls(AssetItem item) {
        if (item == null) {
            return;
        }
        rejectDataUrl(item.getImageUrl(), "imageUrl");
        rejectDataUrl(item.getThumbnailUrl(), "thumbnailUrl");
    }

    private void rejectDataUrl(String rawUrl, String fieldName) {
        if (StrUtil.isNotBlank(rawUrl) && StrUtil.startWithIgnoreCase(rawUrl.trim(), "data:")) {
            throw new BusinessException(fieldName + " 不支持 base64，请先调用 /api/storage/upload 上传二进制文件");
        }
    }

    private void applyCurrentTeamOwnership(Asset asset) {
        Long creatorUserId = asset.getUserId() != null ? asset.getUserId() : SecurityUtils.getCurrentUserId();
        if (creatorUserId == null) {
            return;
        }
        asset.setUserId(creatorUserId);
        TeamService.OwnerScope ownerScope = teamService.getRequiredCurrentOwnerScopeByUser(creatorUserId);
        asset.setOwnerType(ownerScope.getOwnerType());
        asset.setOwnerId(ownerScope.getOwnerId());
    }

    private AssetItem buildInitialItem(Asset asset) {
        return AssetItem.builder()
                .assetId(asset.getId())
                .itemType(ITEM_TYPE_INITIAL)
                .name(asset.getName())
                .sortOrder(0)
                .sourceType(assetSourceType(asset))
                .build();
    }

    private AssetItem buildCharacterThreeViewItem(
            Asset asset, AssetItem appearanceItem, int sortOrder, String properties) {
        return AssetItem.builder()
                .assetId(asset.getId())
                .parentItemId(appearanceItem.getId())
                .itemType(ITEM_TYPE_THREE_VIEW)
                .name(threeViewItemName(StrUtil.blankToDefault(appearanceItem.getName(), asset.getName())))
                .sortOrder(sortOrder)
                .sourceType(appearanceItem.getSourceType() != null
                        ? appearanceItem.getSourceType()
                        : assetSourceType(asset))
                .properties(properties)
                .build();
    }

    private boolean isCharacterAsset(Asset asset) {
        return asset != null && ASSET_TYPE_CHARACTER.equals(asset.getType());
    }

    private int assetSourceType(Asset asset) {
        return asset.getSourceType() != null ? asset.getSourceType() : 1;
    }

    private String threeViewItemName(String assetName) {
        String name = StrUtil.blankToDefault(assetName, "角色") + " 三视图";
        return name.length() <= 128 ? name : name.substring(0, 128);
    }

    private String resolveAppearanceProperties(Asset asset, AssetItem appearanceItem) {
        return StrUtil.isNotBlank(appearanceItem.getProperties())
                ? appearanceItem.getProperties()
                : asset.getProperties();
    }

    private int nextSortOrder(List<AssetItem> items) {
        return items.stream()
                .map(AssetItem::getSortOrder)
                .filter(order -> order != null)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0) + 1;
    }

    private boolean isThreeViewItem(AssetItem item) {
        return item != null && ITEM_TYPE_THREE_VIEW.equals(item.getItemType());
    }

    private boolean isCharacterAppearanceItemType(String itemType) {
        return CHARACTER_APPEARANCE_ITEM_TYPES.contains(itemType);
    }

    private Asset validateAndResolveItemRelationship(AssetItem item, Long existingItemId) {
        if (item == null || item.getAssetId() == null) {
            throw new BusinessException("资产ID不能为空");
        }
        Asset asset = getById(item.getAssetId());
        if (isThreeViewItem(item)) {
            if (!isCharacterAsset(asset)) {
                throw new BusinessException("three_view 仅支持角色资产");
            }
            if (item.getParentItemId() == null) {
                List<AssetItem> appearanceItems = listItems(item.getAssetId()).stream()
                        .filter(this::isCharacterAppearanceItem)
                        .toList();
                if (appearanceItems.size() != 1) {
                    throw new BusinessException("角色存在多个形态，创建或修复 three_view 时必须指定 parentItemId");
                }
                item.setParentItemId(appearanceItems.get(0).getId());
            }
            AssetItem appearanceItem = assetItemMapper.selectById(item.getParentItemId());
            if (appearanceItem == null
                    || !Objects.equals(item.getAssetId(), appearanceItem.getAssetId())
                    || !isCharacterAppearanceItem(appearanceItem)) {
                throw new BusinessException("parentItemId 必须指向同一角色资产下的形态根项");
            }
            boolean duplicate = assetItemMapper.selectList(new LambdaQueryWrapper<AssetItem>()
                            .eq(AssetItem::getAssetId, item.getAssetId())
                            .eq(AssetItem::getParentItemId, item.getParentItemId())
                            .eq(AssetItem::getItemType, ITEM_TYPE_THREE_VIEW))
                    .stream()
                    .anyMatch(existing -> !Objects.equals(existingItemId, existing.getId()));
            if (duplicate) {
                throw new BusinessException("该角色形态已存在专属三视图: " + item.getParentItemId());
            }
        } else if (item.getParentItemId() != null) {
            throw new BusinessException("只有 three_view 可以设置 parentItemId");
        }
        return asset;
    }

    private void syncThreeViewMetadata(Asset asset, AssetItem appearanceItem, AssetItem threeViewItem) {
        if (threeViewItem == null || StrUtil.isNotBlank(threeViewItem.getImageUrl())) {
            return;
        }
        String expectedName = threeViewItemName(StrUtil.blankToDefault(appearanceItem.getName(), asset.getName()));
        String expectedProperties = resolveAppearanceProperties(asset, appearanceItem);
        if (Objects.equals(expectedName, threeViewItem.getName())
                && Objects.equals(expectedProperties, threeViewItem.getProperties())) {
            return;
        }
        threeViewItem.setName(expectedName);
        threeViewItem.setProperties(expectedProperties);
        assetItemMapper.updateById(threeViewItem);
    }

    /**
     * 同步主资产封面：
     * - initial 类型子资产：新增或更新图片时，始终同步为主资产封面
     * - 其他类型子资产：仅在主资产无封面时自动填充
     */
    private void syncCoverIfAbsent(AssetItem item) {
        if (StrUtil.isBlank(item.getImageUrl()) || item.getAssetId() == null) {
            return;
        }
        Asset asset = assetMapper.selectById(item.getAssetId());
        if (asset == null) {
            return;
        }
        if ("initial".equals(item.getItemType())) {
            asset.setCoverUrl(item.getImageUrl());
            assetMapper.updateById(asset);
        } else if (StrUtil.isBlank(asset.getCoverUrl())) {
            asset.setCoverUrl(item.getImageUrl());
            assetMapper.updateById(asset);
        }
    }
}
