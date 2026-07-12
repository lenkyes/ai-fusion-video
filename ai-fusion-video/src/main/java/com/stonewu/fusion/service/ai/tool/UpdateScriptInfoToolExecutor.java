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
 * 更新剧本信息工具（update_script_info）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateScriptInfoToolExecutor implements ToolExecutor {

    private final ScriptService scriptService;
    private final ProjectService projectService;

    @Override
    public String getToolName() {
        return "update_script_info";
    }

    @Override
    public String getDisplayName() {
        return "更新剧本信息";
    }

    @Override
    public String getToolDescription() {
        return """
                更新剧本的总体信息，包括故事梗概(storySynopsis)和人物表(charactersJson)等字段。
                scriptId 必填，各字段均为可选，仅传入需要更新的字段。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "scriptId": { "type": "number", "description": "剧本ID（必填）" },
                        "storySynopsis": { "type": "string", "description": "全剧故事梗概（200-500字概述）" },
                        "charactersJson": { "type": "array", "description": "人物表JSON快照", "items": { "type": "object", "properties": { "name": { "type": "string" }, "assetId": { "type": "number" }, "description": { "type": "string" }, "importance": { "type": "string" } }, "required": ["name"] } },
                        "title": { "type": "string", "description": "剧本标题" },
                        "genre": { "type": "string", "description": "类型/风格" }
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

            if (params.containsKey("storySynopsis")) {
                script.setStorySynopsis(params.getStr("storySynopsis"));
                updatedFields.append("故事梗概、");
            }
            if (params.containsKey("charactersJson")) {
                Object chars = params.get("charactersJson");
                script.setCharactersJson(chars instanceof String ? (String) chars : JSONUtil.toJsonStr(chars));
                updatedFields.append("人物表、");
            }
            if (params.containsKey("title")) {
                script.setTitle(params.getStr("title"));
                updatedFields.append("标题、");
            }
            if (params.containsKey("genre")) {
                script.setGenre(params.getStr("genre"));
                updatedFields.append("类型、");
            }

            scriptService.update(script);
            syncTemplateProjectName(script, params);

            if (!updatedFields.isEmpty()) {
                updatedFields.setLength(updatedFields.length() - 1);
            }
            return JSONUtil.createObj()
                    .set("scriptId", scriptId)
                    .set("message", "已更新剧本信息：" + updatedFields).toString();
        } catch (Exception e) {
            log.error("更新剧本信息失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "更新失败: " + e.getMessage()).toString();
        }
    }

    private void syncTemplateProjectName(Script script, JSONObject params) {
        if (!params.containsKey("title") || script.getProjectId() == null
                || script.getTitle() == null || script.getTitle().isBlank()) {
            return;
        }
        Project project = projectService.getById(script.getProjectId());
        String properties = project.getProperties();
        if (properties == null || !properties.contains("\"videoTemplateId\"")) {
            return;
        }
        String title = script.getTitle().trim();
        if (!title.equals(project.getName())) {
            Project update = new Project();
            update.setId(project.getId());
            update.setName(title);
            projectService.update(update);
            log.info("[update_script_info] 模板项目名称已同步: projectId={}, title={}", project.getId(), title);
        }
    }
}
