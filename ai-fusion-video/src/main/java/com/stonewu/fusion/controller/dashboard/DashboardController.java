package com.stonewu.fusion.controller.dashboard;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.common.PageParam;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.service.dashboard.DashboardService;
import com.stonewu.fusion.service.dashboard.DashboardService.DashboardAnalyticsResp;
import com.stonewu.fusion.service.dashboard.DashboardService.GenerationTaskResp;
import com.stonewu.fusion.service.dashboard.DashboardService.QueueSnapshotResp;
import com.stonewu.fusion.service.dashboard.DashboardService.RecoverTasksResp;
import com.stonewu.fusion.service.dashboard.DashboardService.RetryTaskResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.stonewu.fusion.security.SecurityUtils.requireCurrentUserId;

@Tag(name = "仪表盘")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "获取创作数据分析")
    @GetMapping("/analytics")
    public CommonResult<DashboardAnalyticsResp> analytics() {
        return CommonResult.success(dashboardService.getAnalytics(requireCurrentUserId()));
    }

    @Operation(summary = "分页查询生成任务")
    @GetMapping("/tasks")
    public CommonResult<PageResult<GenerationTaskResp>> tasks(PageParam pageParam,
                                                              @RequestParam(required = false) String type,
                                                              @RequestParam(required = false) Integer status) {
        return CommonResult.success(dashboardService.pageTasks(
                requireCurrentUserId(),
                type,
                status,
                pageParam.getPageNo(),
                pageParam.getPageSize()
        ));
    }

    @Operation(summary = "获取生成队列快照")
    @GetMapping("/queues")
    public CommonResult<List<QueueSnapshotResp>> queues() {
        return CommonResult.success(dashboardService.getQueueSnapshots());
    }

    @Operation(summary = "重试失败生成任务")
    @PostMapping("/tasks/{type}/{id}/retry")
    public CommonResult<RetryTaskResp> retry(@PathVariable String type, @PathVariable Long id) {
        return CommonResult.success(dashboardService.retryTask(requireCurrentUserId(), type, id));
    }

    @Operation(summary = "恢复当前用户卡住的运行中任务")
    @PostMapping("/tasks/recover-stale")
    public CommonResult<RecoverTasksResp> recoverStaleTasks() {
        return CommonResult.success(dashboardService.recoverMyExpiredRunningTasks(requireCurrentUserId()));
    }
}
