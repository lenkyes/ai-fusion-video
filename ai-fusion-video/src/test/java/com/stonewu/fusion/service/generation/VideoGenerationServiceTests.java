package com.stonewu.fusion.service.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.mapper.generation.VideoItemMapper;
import com.stonewu.fusion.mapper.generation.VideoTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoGenerationServiceTests {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), VideoTask.class);
    }

    @Mock
    private VideoTaskMapper taskMapper;

    @Mock
    private VideoItemMapper itemMapper;

    @InjectMocks
    private VideoGenerationService service;

    @Test
    void pageByUserCategoryAndSessionShouldScopeQueryToCurrentSession() {
        when(taskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.pageByUserCategoryAndSession(42L, "dashboard_video_gen", 99L, 1, 100);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectPage(any(Page.class), wrapperCaptor.capture());

        LambdaQueryWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .contains("user_id")
                .contains("category")
                .contains("session_id");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(42L, "dashboard_video_gen", 99L);
    }

    @Test
    void pageByUserAndCategoryShouldOnlyReturnSessionScopedTasks() {
        when(taskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.pageByUserAndCategory(42L, "dashboard_video_gen", 1, 100);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectPage(any(Page.class), wrapperCaptor.capture());

        LambdaQueryWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .contains("user_id")
                .contains("category")
                .contains("session_id IS NOT NULL");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(42L, "dashboard_video_gen");
    }
}
