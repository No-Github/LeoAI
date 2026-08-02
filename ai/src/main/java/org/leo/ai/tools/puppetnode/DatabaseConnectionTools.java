package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.service.DatabaseConnectionProfileException;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.PuppetAuditService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node-AI tools for persisting database credentials discovered on a Puppet.
 * Returned views are sanitized by the shared profile service.
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public final class DatabaseConnectionTools {

    private final DatabaseConnectionProfileService profileService;
    private final PuppetAuditService auditService;

    public DatabaseConnectionTools(DatabaseConnectionProfileService profileService,
                                   PuppetAuditService auditService) {
        this.profileService = profileService;
        this.auditService = auditService;
    }

    @Tool("""
            列出当前 Puppet 已保存的数据库连接配置。返回 connectionId、名称、启用状态和不含密码的 connection。
            在编辑、删除或使用已保存连接前先调用本工具确认 connectionId。
            """)
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Map<String, Object>> listDatabaseConnections() {
        ToolScope scope = scope();
        return profileService.listByPuppet(scope.userId(), scope.puppetId());
    }

    @Tool("""
            为当前 Puppet 新增数据库连接配置，用于保存刚收集到的连接信息。
            config.connection 必填，标准连接至少包含 dialect、connectionMode="standard"、host、port、
            database、username、password；SQLite 使用 file；Oracle 可使用 service 或 sid。
            config 还可包含 connectionName、description、remark、status、maxConnections、timeoutSeconds。
            返回可供 querySql/execSql 使用的 connectionId，响应不会回显密码。
            """)
    public Map<String, Object> createDatabaseConnection(Map<String, Object> config) {
        ToolScope scope = scope();
        return auditedMutation(scope, "DATABASE_CONNECTION_CREATE", "AI新增数据库配置",
                "create", config,
                () -> profileService.create(scope.userId(), scope.puppetId(), config));
    }

    @Tool("""
            编辑当前 Puppet 的数据库连接配置。connectionId 必填；patch 只传需要修改的字段，
            可修改 connectionName、description、remark、status、maxConnections、timeoutSeconds，
            以及 patch.connection 中的 host、port、database、username、password 等连接字段。
            未传字段保持原值；返回内容不会回显密码。
            """)
    public Map<String, Object> updateDatabaseConnection(String connectionId,
                                                        Map<String, Object> patch) {
        ToolScope scope = scope();
        return auditedMutation(scope, "DATABASE_CONNECTION_UPDATE", "AI编辑数据库配置",
                connectionId, patch,
                () -> profileService.update(
                        scope.userId(), scope.puppetId(), connectionId, patch));
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("""
            删除当前 Puppet 已保存的数据库连接配置。先调用 listDatabaseConnections 确认 connectionId。
            删除成功后该 connectionId 将不能再用于 querySql 或 execSql。
            """)
    public Map<String, Object> deleteDatabaseConnection(String connectionId) {
        ToolScope scope = scope();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("connectionId", connectionId);
        return auditedMutation(scope, "DATABASE_CONNECTION_DELETE", "AI删除数据库配置",
                connectionId, params, () -> {
                    profileService.delete(scope.userId(), scope.puppetId(), connectionId);
                    return Map.of("connectionId", connectionId, "deleted", true);
                });
    }

    private ToolScope scope() {
        String sessionId = AiToolContext.requireSessionId();
        String puppetId = PuppetNodeSessionUtils.requirePuppetId(sessionId);
        String userId = PuppetNodeSessionUtils.requireUserId(sessionId);
        AbstractPuppetNode node = PuppetNodeSessionUtils.getSession(sessionId).getPuppetNode();
        return new ToolScope(sessionId, puppetId, userId, node);
    }

    private <T> T auditedMutation(ToolScope scope,
                                  String operationType,
                                  String operationName,
                                  String operationPath,
                                  Map<String, Object> params,
                                  Mutation<T> mutation) {
        Map<String, Object> auditParams = new LinkedHashMap<String, Object>();
        auditParams.put("puppetId", scope.puppetId());
        if (params != null) auditParams.putAll(params);
        try {
            T result = mutation.run();
            auditService.logSuccess(scope.sessionId(), scope.node(), operationType,
                    operationName, operationPath, auditParams, operationName + "成功");
            return result;
        } catch (DatabaseConnectionProfileException error) {
            auditService.logFailure(scope.sessionId(), scope.node(), operationType,
                    operationName, operationPath, auditParams, error.getMessage());
            throw toolFailure(error);
        }
    }

    private AiToolException toolFailure(DatabaseConnectionProfileException error) {
        return switch (error.getKind()) {
            case VALIDATION -> AiToolException.modelCorrectable(
                    "DATABASE_PROFILE_INVALID", error.getMessage(),
                    "检查连接字段格式，或先调用 listDatabaseConnections 获取当前配置。");
            case NOT_FOUND -> AiToolException.modelCorrectable(
                    "DATABASE_PROFILE_NOT_FOUND", error.getMessage(),
                    "调用 listDatabaseConnections 获取有效 connectionId。");
            case FORBIDDEN -> AiToolException.modelCorrectable(
                    "DATABASE_PROFILE_PUPPET_MISMATCH", error.getMessage(),
                    "只使用当前 Puppet 列表中返回的 connectionId。");
            case PERSISTENCE -> AiToolException.systemRetryable(
                    "DATABASE_PROFILE_PERSISTENCE_FAILED", error.getMessage(), error);
        };
    }

    private record ToolScope(String sessionId,
                             String puppetId,
                             String userId,
                             AbstractPuppetNode node) {}

    @FunctionalInterface
    private interface Mutation<T> {
        T run();
    }
}
