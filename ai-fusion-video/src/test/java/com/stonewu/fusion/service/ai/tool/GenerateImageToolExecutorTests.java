package com.stonewu.fusion.service.ai.tool;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateImageToolExecutorTests {

    @Test
    void shouldAttachProjectAndStoryboardCategoryWhenGeneratingStoryboardImage() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ImageGenerationConsumer imageGenerationConsumer = mock(ImageGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(21L)
                .code("flux")
                .build();
        when(aiModelService.getDefaultByType(2)).thenReturn(model);
        when(storyboardService.getItemById(501L)).thenReturn(StoryboardItem.builder()
                .id(501L)
                .storyboardId(70L)
                .build());
        when(storyboardService.getById(70L)).thenReturn(Storyboard.builder()
                .id(70L)
                .projectId(100L)
                .build());

        ImageTask completedTask = ImageTask.builder().id(91L).status(2).build();
        when(imageGenerationConsumer.submitAndWait(any(ImageTask.class), eq(1800000L))).thenReturn(completedTask);
        when(imageGenerationService.listItems(91L)).thenReturn(List.of(ImageItem.builder()
                .status(1)
                .imageUrl("https://example.test/shot.png")
                .build()));

        GenerateImageToolExecutor executor = new GenerateImageToolExecutor(
                aiModelService,
                imageGenerationService,
                imageGenerationConsumer,
                capabilityService,
                storyboardService);

        String result = executor.execute("{\"prompt\":\"分镜首帧图\",\"storyboardItemId\":501}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<ImageTask> taskCaptor = ArgumentCaptor.forClass(ImageTask.class);
        verify(imageGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(1800000L));
        assertThat(taskCaptor.getValue().getModelId()).isEqualTo(21L);
        assertThat(taskCaptor.getValue().getProjectId()).isEqualTo(100L);
        assertThat(taskCaptor.getValue().getCategory()).isEqualTo("storyboard_item:501");
        assertThat(result).contains("\"status\":\"success\"");
        assertThat(result).contains("shot.png");
    }

    @Test
    void shouldMergeNegativePromptIntoSubmittedPrompt() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ImageGenerationConsumer imageGenerationConsumer = mock(ImageGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(22L)
                .code("image-model")
                .build();
        when(aiModelService.getDefaultByType(2)).thenReturn(model);

        ImageTask completedTask = ImageTask.builder().id(92L).status(2).build();
        when(imageGenerationConsumer.submitAndWait(any(ImageTask.class), eq(1800000L))).thenReturn(completedTask);
        when(imageGenerationService.listItems(92L)).thenReturn(List.of(ImageItem.builder()
                .status(1)
                .imageUrl("https://example.test/asset.png")
                .build()));

        GenerateImageToolExecutor executor = new GenerateImageToolExecutor(
                aiModelService,
                imageGenerationService,
                imageGenerationConsumer,
                capabilityService,
                storyboardService);

        executor.execute("{\"prompt\":\"角色设定图\",\"negativePrompt\":\"bad face, crooked eyes\",\"projectId\":100}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<ImageTask> taskCaptor = ArgumentCaptor.forClass(ImageTask.class);
        verify(imageGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(1800000L));
        assertThat(taskCaptor.getValue().getPrompt())
                .contains("角色设定图")
                .contains("反向约束")
                .contains("bad face, crooked eyes");
        assertThat(taskCaptor.getValue().getNegativePrompt()).isEqualTo("bad face, crooked eyes");
    }
}
