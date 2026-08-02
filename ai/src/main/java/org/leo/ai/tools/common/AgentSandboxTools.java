package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.workspace.AgentSandboxService;
import org.leo.ai.service.workspace.AgentWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 任务工作空间的 Docker 隔离脚本执行工具。 */
@Component
public class AgentSandboxTools {

    private final AgentWorkspaceService workspaceService;
    private final AgentSandboxService sandboxService;

    public AgentSandboxTools(AgentWorkspaceService workspaceService,
                             AgentSandboxService sandboxService) {
        this.workspaceService = workspaceService;
        this.sandboxService = sandboxService;
    }

    @Tool("在 Docker 沙箱中异步运行当前 Agent 工作空间脚本。仅支持 python/node/java；默认断网、只挂载当前任务 files 目录，不会回退到宿主机 Shell。")
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE,
            exclusive = true)
    public Map<String, Object> sandboxRun(
            @P("python | node | java") String runtime,
            @P("工作空间中的脚本相对路径") String scriptPath,
            @P("直接作为 argv 传入的参数列表，不经 Shell 解释") List<String> arguments) {
        var workspace = workspaceService.workspaceFromContext();
        return sandboxService.start(workspace, runtime, scriptPath, arguments);
    }

    @Tool("查询当前 Agent 工作空间中一次 sandboxRun 的状态、日志尾部及文件变更。")
    @AiToolPolicy(kind = AiToolKind.CONTEXT, operation = AiToolOperation.READ_ONLY,
            parallelizable = true)
    public Map<String, Object> sandboxRunStatus(@P("sandboxRun 返回的 runId") String runId) {
        var workspace = workspaceService.workspaceFromContext();
        return sandboxService.status(workspace, runId);
    }

    @Tool("强制终止当前 Agent 任务中的沙箱运行；执行前必须得到用户确认。")
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.DESTRUCTIVE,
            exclusive = true)
    public Map<String, Object> sandboxCancel(@P("sandboxRun 返回的 runId") String runId) {
        var workspace = workspaceService.workspaceFromContext();
        return sandboxService.cancel(workspace, runId);
    }
}
