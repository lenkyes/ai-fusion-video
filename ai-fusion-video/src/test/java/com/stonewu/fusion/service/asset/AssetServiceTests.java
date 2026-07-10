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

import java.util.List;

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
                long[] nextItemId = { 101L };
                doAnswer(invocation -> {
                        AssetItem saved = invocation.getArgument(0);
                        saved.setId(nextItemId[0]++);
                        return 1;
                }).when(assetItemMapper).insert(any(AssetItem.class));

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
                assertThat(itemCaptor.getAllValues().get(1).getParentItemId())
                                .isEqualTo(itemCaptor.getAllValues().get(0).getId());
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
                                                .id(311L)
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
                assertThat(itemCaptor.getValue().getParentItemId()).isEqualTo(311L);
                assertThat(itemCaptor.getValue().getName()).isEqualTo("李四 三视图");
                assertThat(itemCaptor.getValue().getProperties()).isEqualTo("{\"appearance\":\"白发蓝衣\"}");
                assertThat(itemCaptor.getValue().getSortOrder()).isEqualTo(1);
        }

        @Test
        void ensureCharacterThreeViewItemsCreatesOnePerAppearance() {
                AssetItem initial = AssetItem.builder()
                                .id(401L)
                                .assetId(41L)
                                .itemType("initial")
                                .name("张三")
                                .properties("{\"age\":\"青年\"}")
                                .sortOrder(0)
                                .build();
                AssetItem oldAge = AssetItem.builder()
                                .id(402L)
                                .assetId(41L)
                                .itemType("age")
                                .name("老年张三")
                                .properties("{\"age\":\"老年\"}")
                                .sortOrder(1)
                                .build();
                when(assetItemMapper.selectList(any())).thenReturn(List.of(initial, oldAge));

                Asset asset = Asset.builder()
                                .id(41L)
                                .type("character")
                                .name("张三")
                                .sourceType(2)
                                .build();

                List<AssetItem> threeViews = assetService.ensureCharacterThreeViewItems(asset);

                assertThat(threeViews)
                                .extracting(AssetItem::getParentItemId)
                                .containsExactly(401L, 402L);
                assertThat(threeViews)
                                .extracting(AssetItem::getName)
                                .containsExactly("张三 三视图", "老年张三 三视图");
                assertThat(threeViews.get(1).getProperties()).isEqualTo("{\"age\":\"老年\"}");
                verify(assetItemMapper, times(2)).insert(any(AssetItem.class));
        }

        @Test
        void ensureKeepsAmbiguousLegacyThreeViewsUnboundAndCreatesDeterministicInitialPair() {
                AssetItem initial = AssetItem.builder()
                                .id(501L)
                                .assetId(51L)
                                .itemType("initial")
                                .name("张三")
                                .sortOrder(0)
                                .build();
                AssetItem legacyA = AssetItem.builder()
                                .id(502L)
                                .assetId(51L)
                                .itemType("three_view")
                                .sortOrder(1)
                                .build();
                AssetItem legacyB = AssetItem.builder()
                                .id(503L)
                                .assetId(51L)
                                .itemType("three_view")
                                .sortOrder(2)
                                .build();
                when(assetItemMapper.selectList(any())).thenReturn(List.of(initial, legacyA, legacyB));

                List<AssetItem> result = assetService.ensureCharacterThreeViewItems(Asset.builder()
                                .id(51L)
                                .type("character")
                                .name("张三")
                                .build());

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getParentItemId()).isEqualTo(501L);
                assertThat(legacyA.getParentItemId()).isNull();
                assertThat(legacyB.getParentItemId()).isNull();
                verify(assetItemMapper).insert(result.get(0));
        }

        @Test
        void createCharacterItemDefaultsToVariantAndCreatesLinkedThreeView() {
                Asset asset = Asset.builder().id(61L).type("character").name("张三").build();
                when(assetMapper.selectById(61L)).thenReturn(asset);
                AssetItem oldAge = AssetItem.builder()
                                .assetId(61L)
                                .name("老年张三")
                                .properties("{\"age\":\"老年\"}")
                                .sortOrder(2)
                                .sourceType(2)
                                .build();
                long[] nextItemId = { 601L };
                doAnswer(invocation -> {
                        AssetItem saved = invocation.getArgument(0);
                        saved.setId(nextItemId[0]++);
                        return 1;
                }).when(assetItemMapper).insert(any(AssetItem.class));
                when(assetItemMapper.selectList(any()))
                                .thenReturn(List.of())
                                .thenReturn(List.of(oldAge));

                assetService.createItem(oldAge);

                ArgumentCaptor<AssetItem> captor = ArgumentCaptor.forClass(AssetItem.class);
                verify(assetItemMapper, times(2)).insert(captor.capture());
                AssetItem threeView = captor.getAllValues().get(1);
                assertThat(oldAge.getItemType()).isEqualTo("variant");
                assertThat(threeView.getItemType()).isEqualTo("three_view");
                assertThat(threeView.getParentItemId()).isEqualTo(601L);
                assertThat(threeView.getName()).isEqualTo("老年张三 三视图");
        }

        @Test
        void createManualThreeViewRejectsAmbiguousAppearanceInference() {
                when(assetMapper.selectById(71L))
                                .thenReturn(Asset.builder().id(71L).type("character").build());
                when(assetItemMapper.selectList(any())).thenReturn(List.of(
                                AssetItem.builder().id(701L).assetId(71L).itemType("initial").build(),
                                AssetItem.builder().id(702L).assetId(71L).itemType("age").build()));

                AssetItem threeView = AssetItem.builder()
                                .assetId(71L)
                                .itemType("three_view")
                                .name("未明确形态的三视图")
                                .build();

                assertThatThrownBy(() -> assetService.createItem(threeView))
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("必须指定 parentItemId");
        }

        @Test
        void updateAllowsBindingLegacyThreeViewToExplicitAppearance() {
                AssetItem legacyThreeView = AssetItem.builder()
                                .id(801L)
                                .assetId(81L)
                                .itemType("three_view")
                                .name("旧三视图")
                                .build();
                AssetItem oldAge = AssetItem.builder()
                                .id(802L)
                                .assetId(81L)
                                .itemType("age")
                                .name("老年张三")
                                .build();
                when(assetItemMapper.selectById(801L)).thenReturn(legacyThreeView);
                when(assetItemMapper.selectById(802L)).thenReturn(oldAge);
                when(assetMapper.selectById(81L))
                                .thenReturn(Asset.builder().id(81L).type("character").build());
                when(assetItemMapper.selectList(any())).thenReturn(List.of());

                AssetItem update = AssetItem.builder().id(801L).parentItemId(802L).build();
                AssetItem saved = assetService.updateItem(update);

                assertThat(saved.getParentItemId()).isEqualTo(802L);
                verify(assetItemMapper).updateById(update);
        }

        @Test
        void updateRejectsSwitchingAppearanceRootIntoThreeView() {
                when(assetItemMapper.selectById(811L)).thenReturn(AssetItem.builder()
                                .id(811L)
                                .assetId(81L)
                                .itemType("age")
                                .build());

                assertThatThrownBy(() -> assetService.updateItem(AssetItem.builder()
                                .id(811L)
                                .itemType("three_view")
                                .parentItemId(812L)
                                .build()))
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("不支持在 three_view 与形态根项之间直接切换类型");
        }

        @Test
        void updateAppearanceDoesNotRewriteMetadataOfGeneratedThreeView() {
                AssetItem appearance = AssetItem.builder()
                                .id(821L)
                                .assetId(82L)
                                .itemType("age")
                                .name("老年张三")
                                .properties("{\"age\":\"老年\"}")
                                .build();
                AssetItem generatedThreeView = AssetItem.builder()
                                .id(822L)
                                .assetId(82L)
                                .parentItemId(821L)
                                .itemType("three_view")
                                .name("已生成的旧名称")
                                .properties("{\"age\":\"旧值\"}")
                                .imageUrl("https://example.com/generated.png")
                                .build();
                when(assetItemMapper.selectById(821L)).thenReturn(appearance);
                when(assetMapper.selectById(82L))
                                .thenReturn(Asset.builder().id(82L).type("character").name("张三").build());
                when(assetItemMapper.selectList(any())).thenReturn(List.of(generatedThreeView));

                assetService.updateItem(AssetItem.builder()
                                .id(821L)
                                .name("更老的张三")
                                .properties("{\"age\":\"高龄\"}")
                                .build());

                verify(assetItemMapper, times(1)).updateById(any(AssetItem.class));
                assertThat(generatedThreeView.getName()).isEqualTo("已生成的旧名称");
                assertThat(generatedThreeView.getProperties()).isEqualTo("{\"age\":\"旧值\"}");
        }

        @Test
        void deleteAppearanceAlsoDeletesLinkedThreeView() {
                AssetItem appearance = AssetItem.builder()
                                .id(901L)
                                .assetId(91L)
                                .itemType("age")
                                .build();
                AssetItem threeView = AssetItem.builder()
                                .id(902L)
                                .assetId(91L)
                                .parentItemId(901L)
                                .itemType("three_view")
                                .build();
                when(assetItemMapper.selectById(901L)).thenReturn(appearance);
                when(assetItemMapper.selectList(any())).thenReturn(List.of(threeView));

                assetService.deleteItem(901L);

                verify(assetItemMapper).deleteById(902L);
                verify(assetItemMapper).deleteById(901L);
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
