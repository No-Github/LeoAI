package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.service.DatabaseConnectionProfileException;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.PuppetAuditService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SqlTools {

    private static final java.util.Set<String> DDL_DML_KEYWORDS = java.util.Set.of(
            "insert", "update", "delete", "drop", "truncate", "alter",
            "create", "rename", "replace", "merge", "call", "exec",
            "execute", "grant", "revoke", "lock", "unlock"
    );

    /** 行注释 */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\n]*");
    /** 块注释 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 单引号字符串字面量 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'");

    private final PuppetAuditService auditService;
    private final DatabaseConnectionProfileService profileService;

    public SqlTools(PuppetAuditService auditService,
                    DatabaseConnectionProfileService profileService) {
        this.auditService = auditService;
        this.profileService = profileService;
    }

    @Tool("""
            执行 SQL 语句（允许写入和结构变更）。connection 可直接提供完整连接字段，
            也可传 {"connectionId":"已保存配置ID"} 使用当前 Puppet 已启用的数据库配置。
            验证连接、枚举库表、查询或提取证据；只读查询优先使用 querySql。
            """)
    public Map<String, Object> execSql(Map<String, Object> connection, String sqlScript) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        SqlCapable node = PuppetNodeSessionUtils.requireCapability(sessionId, SqlCapable.class);
        AbstractPuppetNode auditNode = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        ResolvedConnection resolved = resolveConnection(sessionId, connection);
        DatabaseConnectionSpec connectionSpec = resolved.spec();
        Map<String, Object> auditParams = sqlAuditParams(
                sessionId, resolved.connectionId(), connectionSpec, sqlScript);
        String operationPath = sqlOperationPath(connectionSpec, sqlScript);
        try {
            Map<String, Object> result = node.executeSql(connectionSpec, sqlScript);
            auditService.logSuccess(sessionId, auditNode, "SQL_EXEC", "AI执行SQL", operationPath,
                    auditParams, "AI执行SQL成功");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "SQL_EXEC", "AI执行SQL", operationPath,
                    auditParams, e.getMessage());
            throw e;
        }
    }

    @Tool("""
            执行只读 SQL 查询（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH）。connection 可直接提供完整连接字段，
            也可传 {"connectionId":"已保存配置ID"} 使用当前 Puppet 已启用的数据库配置。
            写入或结构变更请使用 execSql。
            """)
    public Map<String, Object> querySql(Map<String, Object> connection, String sqlScript) throws Exception {
        String violation = detectSqlViolation(sqlScript);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        String sessionId = AiToolContext.requireSessionId();
        SqlCapable node = PuppetNodeSessionUtils.requireCapability(sessionId, SqlCapable.class);
        AbstractPuppetNode auditNode = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        ResolvedConnection resolved = resolveConnection(sessionId, connection);
        DatabaseConnectionSpec connectionSpec = resolved.spec();
        Map<String, Object> auditParams = sqlAuditParams(
                sessionId, resolved.connectionId(), connectionSpec, sqlScript);
        String operationPath = sqlOperationPath(connectionSpec, sqlScript);
        try {
            Map<String, Object> result = node.executeSql(connectionSpec, sqlScript);
            auditService.logSuccess(sessionId, auditNode, "SQL_QUERY", "AI查询SQL", operationPath,
                    auditParams, "AI查询SQL成功");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "SQL_QUERY", "AI查询SQL", operationPath,
                    auditParams, e.getMessage());
            throw e;
        }
    }

    /**
     * 检测 SQL 是否违反只读约束。
     *
     * <p>两道防线：
     * <ol>
     *   <li>多语句检测：去掉注释和字符串字面量后按分号分割，
     *       若存在多条非空语句则拒绝。</li>
     *   <li>首 token 检测：每条语句的首个关键字不得为 DDL/DML。</li>
     * </ol>
     *
     * @return 违规原因字符串；无违规时返回 {@code null}
     */
    private String detectSqlViolation(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        // 去掉行注释（-- ...）和块注释（/* ... */）
        String stripped = LINE_COMMENT.matcher(sql).replaceAll(" ");
        stripped = BLOCK_COMMENT.matcher(stripped).replaceAll(" ").trim();

        // 去掉单引号字符串字面量（用占位符替换），避免字面量内的分号被误判为多语句分隔符
        // 例如 SELECT * FROM t WHERE name='hello; world' 不应被拒绝
        String noLiteral = STRING_LITERAL.matcher(stripped).replaceAll("''");

        // 按分号分割，过滤空段
        String[] segments = noLiteral.split(";");
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String seg : segments) {
            if (!seg.isBlank()) nonEmpty.add(seg.trim());
        }

        // 多语句拒绝
        if (nonEmpty.size() > 1) {
            return "querySql 不允许多语句（检测到 " + nonEmpty.size() + " 条语句），已拒绝执行。如需写操作请使用 execSql。";
        }

        // 逐条检测首 token（使用去字面量后的片段，避免引号内关键字误判）
        for (String seg : nonEmpty) {
            String[] tokens = seg.split("\\s+");
            if (tokens.length == 0) continue;
            String firstToken = tokens[0].toLowerCase();
            if (DDL_DML_KEYWORDS.contains(firstToken)) {
                return "querySql 仅允许只读查询（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH），检测到写入或结构变更语句（" + tokens[0] + "），已拒绝执行。如需写操作请使用 execSql。";
            }
        }
        return null;
    }

    private ResolvedConnection resolveConnection(String sessionId,
                                                 Map<String, Object> connection) {
        String connectionId = text(connection == null ? null : connection.get("connectionId"));
        if (connectionId == null) {
            return new ResolvedConnection(null, DatabaseConnectionSpec.fromMap(connection));
        }
        try {
            DatabaseConnectionSpec spec = profileService.resolveActive(
                    PuppetNodeSessionUtils.requireUserId(sessionId),
                    PuppetNodeSessionUtils.requirePuppetId(sessionId),
                    connectionId);
            return new ResolvedConnection(connectionId, spec);
        } catch (DatabaseConnectionProfileException error) {
            throw switch (error.getKind()) {
                case VALIDATION -> AiToolException.modelCorrectable(
                        "DATABASE_PROFILE_INVALID", error.getMessage(),
                        "检查配置是否已启用，或调用 listDatabaseConnections 查看状态。");
                case NOT_FOUND -> AiToolException.modelCorrectable(
                        "DATABASE_PROFILE_NOT_FOUND", error.getMessage(),
                        "调用 listDatabaseConnections 获取有效 connectionId。");
                case FORBIDDEN -> AiToolException.modelCorrectable(
                        "DATABASE_PROFILE_PUPPET_MISMATCH", error.getMessage(),
                        "只使用当前 Puppet 返回的 connectionId。");
                case PERSISTENCE -> AiToolException.systemRetryable(
                        "DATABASE_PROFILE_RESOLUTION_FAILED", error.getMessage(), error);
            };
        }
    }

    private Map<String, Object> sqlAuditParams(String sessionId,
                                               String connectionId,
                                               DatabaseConnectionSpec connection,
                                               String sqlScript) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("sessionId", sessionId);
        if (connectionId != null) params.put("connectionId", connectionId);
        Map<String, Object> safeConnection = new HashMap<>(connection.toMap());
        safeConnection.remove("password");
        params.put("connection", safeConnection);
        params.put("sql", sqlScript);
        return params;
    }

    private String sqlOperationPath(DatabaseConnectionSpec connection, String sqlScript) {
        String sql = truncate(sqlScript, 180);
        String target = connection.getDialect() + "://"
                + (connection.getHost() == null ? "local" : connection.getHost())
                + (connection.getDatabase() == null ? "" : "/" + connection.getDatabase());
        return sql == null || sql.isBlank() ? target : target + " | " + sql;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record ResolvedConnection(String connectionId, DatabaseConnectionSpec spec) {}
}
