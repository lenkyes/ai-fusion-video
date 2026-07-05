package com.stonewu.fusion.controller.cost;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.controller.cost.vo.BatchUpdateCostConfigReqVO;
import com.stonewu.fusion.service.cost.GenerationCostAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.stonewu.fusion.security.SecurityUtils.requireCurrentUserId;

@Tag(name = "生成成本分析")
@RestController
@RequestMapping("/api/cost-analysis")
@RequiredArgsConstructor
public class GenerationCostAnalysisController {

    private final GenerationCostAnalysisService costAnalysisService;

    @Operation(summary = "查询模型成本配置")
    @GetMapping("/configs")
    public CommonResult<List<GenerationCostAnalysisService.CostConfigRow>> listConfigs(
            @RequestParam(required = false) String mediaType) {
        requireCurrentUserId();
        return CommonResult.success(costAnalysisService.listModelCostConfigs(mediaType));
    }

    @Operation(summary = "批量更新模型成本配置")
    @PutMapping("/configs/batch")
    public CommonResult<Integer> batchUpdateConfigs(@RequestBody BatchUpdateCostConfigReqVO reqVO) {
        requireCurrentUserId();
        return CommonResult.success(costAnalysisService.batchUpdateConfigs(reqVO));
    }

    @Operation(summary = "查询项目生成成本汇总")
    @GetMapping("/projects/{projectId}/summary")
    public CommonResult<GenerationCostAnalysisService.ProjectCostSummary> getProjectSummary(@PathVariable Long projectId) {
        requireCurrentUserId();
        return CommonResult.success(costAnalysisService.getProjectCostSummary(projectId));
    }
}
