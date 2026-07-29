package org.leo.web.dto.puppetnode.sql;

import java.util.List;
import java.util.Map;

public final class SqlRequests {

    private SqlRequests() {
    }

    public interface ConnectionPayload {
        String sessionId();

        Map<String, Object> connection();

        default Map<String, Object> connectionOptions() {
            if (connection() == null) {
                throw new IllegalArgumentException("connection 不能为空");
            }
            return connection();
        }
    }

    public record ExecRequest(String sessionId,
                              Map<String, Object> connection,
                              String sql) implements ConnectionPayload {
    }

    public record MetadataRequest(String sessionId,
                                  Map<String, Object> connection,
                                  String database,
                                  String table) implements ConnectionPayload {
    }

    public record RuntimeCapabilitiesRequest(String sessionId,
                                             Map<String, Object> connection) implements ConnectionPayload {
    }

    public record QueryTableRequest(String sessionId,
                                    Map<String, Object> connection,
                                    String database,
                                    String table,
                                    Integer page,
                                    Integer pageSize,
                                    List<String> columns,
                                    List<Map<String, Object>> orderBy,
                                    List<Map<String, Object>> filters) implements ConnectionPayload {
    }

    public record CreateTableRequest(String sessionId,
                                     Map<String, Object> connection,
                                     String database,
                                     String table,
                                     List<Map<String, Object>> columns) implements ConnectionPayload {
    }

    public record CreateDatabaseRequest(String sessionId,
                                        Map<String, Object> connection,
                                        String database) implements ConnectionPayload {
    }

    public record InsertRowRequest(String sessionId,
                                   Map<String, Object> connection,
                                   String database,
                                   String table,
                                   Map<String, Object> row) implements ConnectionPayload {
    }

    public record UpdateRowRequest(String sessionId,
                                   Map<String, Object> connection,
                                   String database,
                                   String table,
                                   Map<String, Object> where,
                                   Map<String, Object> update) implements ConnectionPayload {
    }

    public record DeleteRowRequest(String sessionId,
                                   Map<String, Object> connection,
                                   String database,
                                   String table,
                                   Map<String, Object> where) implements ConnectionPayload {
    }

    public record ExportTableRequest(String sessionId,
                                     Map<String, Object> connection,
                                     String database,
                                     String table,
                                     String format) implements ConnectionPayload {
    }

    public record ExportDatabaseRequest(String sessionId,
                                        Map<String, Object> connection,
                                        String database,
                                        List<String> tables,
                                        Object includeStructure,
                                        Object includeData,
                                        String format) implements ConnectionPayload {
    }

    public record ExportResumeRequest(String sessionId,
                                      Map<String, Object> connection,
                                      String taskId) implements ConnectionPayload {
    }

    public record ExportTaskRequest(String taskId) {
    }

    public record ExportSessionRequest(String sessionId) {
    }
}
