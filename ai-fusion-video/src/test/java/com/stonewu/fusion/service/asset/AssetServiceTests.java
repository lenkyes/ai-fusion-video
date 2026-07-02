package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.mapper.asset.AssetItemMapper;
import com.stonewu.fusion.mapper.asset.AssetMapper;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.team.TeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceTests {

    @Mock
    private AssetMapper assetMapper;

    @Mock
    private AssetItemMapper assetItemMapper;

        @Mock
        private ProjectService projectService;

        @Mock
        private TeamService teamService;

    @InjectMocks
    private AssetService assetService;

    @Test
        void createRejectsBase64CoverUrl() {
        Asset asset = Asset.builder()
                .projectId(1L)
                .type("character")
                .name("角色 A")
                .coverUrl(dataUrl("image/png", "cover-image"))
                .sourceType(1)
                .build();

        assertThatThrownBy(() -> assetService.create(asset))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("coverUrl 不支持 base64");
    }

    @Test
    void updateKeepsNormalCoverUrlUntouched() {
        when(assetMapper.selectById(7L)).thenReturn(Asset.builder().id(7L).build());

        Asset asset = Asset.builder()
                .id(7L)
                .coverUrl("https://example.com/cover.png")
                .build();

        assetService.update(asset);

        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).updateById(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getCoverUrl()).isEqualTo("https://example.com/cover.png");
    }

        @Test
        void createAssignsCurrentTeamOwnershipFromCreator() {
                when(teamService.getRequiredCurrentOwnerScopeByUser(9L)).thenReturn(new TeamService.OwnerScope(2, 5L));
                doAnswer(invocation -> {
                        Asset saved = invocation.getArgument(0);
                        saved.setId(13L);
                        return 1;
                }).when(assetMapper).insert(any(Asset.class));

                Asset asset = Asset.builder()
                                .projectId(1L)
                                .type("character")
                                .name("角色 B")
                                .userId(9L)
                                .build();

                assetService.create(asset);

                ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
                verify(assetMapper).insert(assetCaptor.capture());
                assertThat(assetCaptor.getValue().getOwnerType()).isEqualTo(2);
                assertThat(assetCaptor.getValue().getOwnerId()).isEqualTo(5L);
                assertThat(assetCaptor.getValue().getUserId()).isEqualTo(9L);
        }

        @Test
        void createCharacterCreatesInitialAndThreeViewItems() {
                doAnswer(invocation -> {
                        Asset saved = invocation.getArgument(0);
                        saved.setId(21L);
                        return 1;
                }).when(assetMapper).insert(any(Asset.class));

                Asset asset = Asset.builder()
                                .projectId(1L)
                                .type("character")
                                .name("张三")
                                .properties("{\"appearance\":\"黑发短发\"}")
                                .sourceType(2)
                                .build();

                assetService.create(asset);

                ArgumentCaptor<AssetItem> itemCaptor = ArgumentCaptor.forClass(AssetItem.class);
                verify(assetItemMapper, times(2)).insert(itemCaptor.capture());
                assertThat(itemCaptor.getAllValues())
                                .extracting(AssetItem::getItemType)
                                .containsExactly("initial", "three_view");
                assertThat(itemCaptor.getAllValues().get(1).getName()).isEqualTo("张三 三视图");
                assertThat(itemCaptor.getAllValues().get(1).getProperties()).isEqualTo("{\"appearance\":\"黑发短发\"}");
                assertThat(itemCaptor.getAllValues().get(1).getSortOrder()).isEqualTo(1);
        }

        @Test
        void createSceneCreatesOnlyInitialItem() {
                doAnswer(invocation -> {
                        Asset saved = invocation.getArgument(0);
                        saved.setId(22L);
                        return 1;
                }).when(assetMapper).insert(any(Asset.class));

                Asset asset = Asset.builder()
                                .projectId(1L)
                                .type("scene")
                                .name("咖啡厅")
                                .sourceType(2)
                                .build();

                assetService.create(asset);

                ArgumentCaptor<AssetItem> itemCaptor = ArgumentCaptor.forClass(AssetItem.class);
                verify(assetItemMapper).insert(itemCaptor.capture());
                assertThat(itemCaptor.getValue().getItemType()).isEqualTo("initial");
        }

        @Test
        void ensureCharacterThreeViewItemCreatesMissingItemFromInitialProperties() {
                when(assetItemMapper.selectList(any())).thenReturn(java.util.List.of(
                                AssetItem.builder()
                                                .assetId(31L)
                                                .itemType("initial")
                                                .properties("{\"appearance\":\"白发蓝衣\"}")
                                                .sortOrder(0)
                                                .build()));

                Asset asset = Asset.builder()
                                .id(31L)
                                .type("character")
                                .name("李四")
                                .properties("{\"appearance\":\"主资产属性\"}")
                                .sourceType(2)
                                .build();

                assetService.ensureCharacterThreeViewItem(asset);

                ArgumentCaptor<AssetItem> itemCaptor = ArgumentCaptor.forClass(AssetItem.class);
                verify(assetItemMapper).insert(itemCaptor.capture());
                assertThat(itemCaptor.getValue().getItemType()).isEqualTo("three_view");
                assertThat(itemCaptor.getValue().getName()).isEqualTo("李四 三视图");
                assertThat(itemCaptor.getValue().getProperties()).isEqualTo("{\"appearance\":\"白发蓝衣\"}");
                assertThat(itemCaptor.getValue().getSortOrder()).isEqualTo(1);
        }

    @Test
    void updateItemRejectsBase64ImageAndThumbnail() {
        when(assetItemMapper.selectById(9L)).thenReturn(AssetItem.builder()
                .id(9L)
                .assetId(3L)
                .itemType("variant")
                .build());

        AssetItem item = AssetItem.builder()
                .id(9L)
                .imageUrl(dataUrl("image/jpeg", "item-image"))
                .thumbnailUrl(dataUrl("image/webp", "thumb-image"))
                .build();

        assertThatThrownBy(() -> assetService.updateItem(item))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("imageUrl 不支持 base64");
    }

        @Test
        void pageAccessibleByUserUsesCurrentTeamScope() {
                when(teamService.getCurrentTeamIdByUser(9L)).thenReturn(5L);
                when(teamService.listMemberUserIds(5L)).thenReturn(java.util.List.of(9L, 10L));
                when(assetMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

                assetService.pageAccessibleByUser(9L, null, null, null, 1, 20);

                ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Asset>> wrapperCaptor =
                                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
                verify(assetMapper).selectPage(any(), wrapperCaptor.capture());
                verify(teamService).getCurrentTeamIdByUser(9L);
                verify(teamService).listMemberUserIds(5L);
        }

        @Test
        void canAccessAssetAllowsSameTeamProjectAsset() {
                Asset asset = Asset.builder()
                                .id(12L)
                                .userId(10L)
                                .projectId(21L)
                                .build();
                when(projectService.canAccessProject(21L, 9L)).thenReturn(true);

                assertThat(assetService.canAccessAsset(asset, 9L)).isTrue();
        }

    private static String dataUrl(String mimeType, String ignoredValue) {
        return "data:" + mimeType + ";base64,dGVzdA==";
    }
}
