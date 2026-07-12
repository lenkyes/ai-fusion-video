package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.project.Project;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.script.ScriptService;
import com.stonewu.fusion.service.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 更新剧本工具（update_script）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateScriptToolExecutor implements ToolExecutor {

    private final ScriptService scriptService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "update_script";
    }

    @Override
    public String getDisplayName() {
        return "更新剧本";
    }

    @Override
    public String getToolDescription() {
        return "更新剧本的内容、标题或原始文本。仅传入需要修改的字段。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "scriptId": { "type": "number", "description": "剧本ID" },
                        "title": { "type": "string", "description": "标题" },
                        "content": { "type": "string", "description": "剧本内容" },
                        "storySynopsis": { "type": "string", "description": "故事梗概" }
                    },
                    "required": ["scriptId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long scriptId = params.getLong("scriptId");
            if (scriptId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 scriptId").toString();
            }

            Script script = scriptService.getById(scriptId);
            StringBuilder updatedFields = new StringBuilder();

            if (params.containsKey("title")) {
                script.setTitle(params.getStr("title"));
                updatedFields.append("标题、");
            }
            if (params.containsKey("content")) {
                script.setContent(params.getStr("content"));
                updatedFields.append("内容、");
            }
            if (params.containsKey("storySynopsis")) {
                script.setStorySynopsis(params.getStr("storySynopsis"));
                updatedFields.append("故事梗概、");
            }

            if (updatedFields.isEmpty()) {
                return JSONUtil.createObj().set("status", "error").set("message", "未指定任何要更新的字段").toString();
            }

            scriptService.update(script);
            syncTemplateProjectName(script, params);
            updatedFields.setLength(updatedFields.length() - 1);
            return JSONUtil.createObj()
                    .set("scriptId", scriptId)
                    .set("message", "已更新剧本：" + updatedFields).toString();
        } catch (Exception e) {
            log.error("更新剧本失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "更新失败: " + e.getMessage()).toString();
        }
    }

    private void syncTemplateProjectName(Script script, JSONObject params) {
        if (!params.containsKey("title") || script.getProjectId() == null
                || script.getTitle() == null || script.getTitle().isBlank()) return;
        Project project = projectService.getById(script.getProjectId());
        String properties = project.getProperties();
        if (properties == null || !properties.contains("videoTemplateId")) return;
        String title = script.getTitle().trim();
        if (!title.equals(project.getName())) {
            Project update = new Project();
            update.setId(project.getId());
            update.setName(title);
            projectService.update(update);
            log.info("[update_script] 模板项目名称已同步: projectId={}, title={}", project.getId(), title);
        }
    }
}
