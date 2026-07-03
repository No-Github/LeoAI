package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;

import java.util.List;
import java.util.Map;


@Mapper
public interface AuditLogMapper {

    String AUDIT_LOG_FILTER_WHERE = """
            <where>
                <if test="query.userId != null and query.userId != ''">
                    AND user_id = #{query.userId}
                </if>
                <if test="query.userName != null and query.userName != ''">
                    AND user_name LIKE '%' || #{query.userName} || '%'
                </if>
                <if test="query.puppetId != null and query.puppetId != ''">
                    AND puppet_id = #{query.puppetId}
                </if>
                <if test="query.puppetName != null and query.puppetName != ''">
                    AND puppet_name LIKE '%' || #{query.puppetName} || '%'
                </if>
                <if test="query.sessionId != null and query.sessionId != ''">
                    AND session_id = #{query.sessionId}
                </if>
                <if test="query.operationType != null and query.operationType != ''">
                    AND operation_type = #{query.operationType}
                </if>
                <if test="query.status != null and query.status != ''">
                    AND status = #{query.status}
                </if>
                <if test="query.clientIp != null and query.clientIp != ''">
                    AND client_ip LIKE '%' || #{query.clientIp} || '%'
                </if>
                <if test="query.remark != null and query.remark != ''">
                    AND remark = #{query.remark}
                </if>
                <if test="query.startTime != null and query.startTime != ''">
                    AND create_time &gt;= #{query.startTime}
                </if>
                <if test="query.endTime != null and query.endTime != ''">
                    AND create_time &lt;= #{query.endTime}
                </if>
                <if test="query.keyword != null and query.keyword != ''">
                    AND (
                        operation_type LIKE '%' || #{query.keyword} || '%'
                        OR operation_name LIKE '%' || #{query.keyword} || '%'
                        OR operation_path LIKE '%' || #{query.keyword} || '%'
                        OR request_params LIKE '%' || #{query.keyword} || '%'
                        OR response_message LIKE '%' || #{query.keyword} || '%'
                        OR error_message LIKE '%' || #{query.keyword} || '%'
                        OR user_name LIKE '%' || #{query.keyword} || '%'
                        OR puppet_name LIKE '%' || #{query.keyword} || '%'
                        OR client_ip LIKE '%' || #{query.keyword} || '%'
                    )
                </if>
            </where>
            """;

    @Insert("INSERT INTO audit_logs (log_id, user_id, user_name, puppet_id, puppet_name, session_id, operation_type, operation_name, operation_path, request_params, response_code, response_message, status, error_message, client_ip, create_time, remark) VALUES (#{logId}, #{userId}, #{userName}, #{puppetId}, #{puppetName}, #{sessionId}, #{operationType}, #{operationName}, #{operationPath}, #{requestParams}, #{responseCode}, #{responseMessage}, #{status}, #{errorMessage}, #{clientIp}, #{createTime}, #{remark})")
    boolean insertAuditLog(@Param("logId") String logId,
                          @Param("userId") String userId,
                          @Param("userName") String userName,
                          @Param("puppetId") String puppetId,
                          @Param("puppetName") String puppetName,
                          @Param("sessionId") String sessionId,
                          @Param("operationType") String operationType,
                          @Param("operationName") String operationName,
                          @Param("operationPath") String operationPath,
                          @Param("requestParams") String requestParams,
                          @Param("responseCode") Integer responseCode,
                          @Param("responseMessage") String responseMessage,
                          @Param("status") String status,
                          @Param("errorMessage") String errorMessage,
                          @Param("clientIp") String clientIp,
                          @Param("createTime") String createTime,
                          @Param("remark") String remark);

    @Select("SELECT * FROM audit_logs WHERE log_id = #{logId}")
    AuditLog findAuditLogById(@Param("logId") String logId);

    @Select("SELECT * FROM audit_logs WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsByUserId(@Param("userId") String userId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT * FROM audit_logs WHERE puppet_id = #{puppetId} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsByPuppetId(@Param("puppetId") String puppetId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT * FROM audit_logs WHERE operation_type = #{operationType} ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsByOperationType(@Param("operationType") String operationType, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT * FROM audit_logs ORDER BY create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAllAuditLogs(@Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select({
            "<script>",
            "SELECT * FROM audit_logs",
            AUDIT_LOG_FILTER_WHERE,
            "ORDER BY create_time DESC, log_id DESC",
            "LIMIT #{query.limit} OFFSET #{query.offset}",
            "</script>"
    })
    List<AuditLog> searchAuditLogs(@Param("query") AuditLogQuery query);

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM audit_logs",
            AUDIT_LOG_FILTER_WHERE,
            "</script>"
    })
    Integer countAuditLogs(@Param("query") AuditLogQuery query);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE user_id = #{userId}")
    Integer countAuditLogsByUserId(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE user_id = #{userId} AND create_time >= datetime('now', '-' || #{days} || ' days')")
    Integer countRecentAuditLogsByUserId(@Param("userId") String userId, @Param("days") Integer days);

    @Select("SELECT * FROM audit_logs WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT 1")
    AuditLog findLatestAuditLogByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM audit_logs WHERE session_id = #{sessionId} ORDER BY create_time ASC LIMIT #{limit} OFFSET #{offset}")
    List<AuditLog> findAuditLogsBySessionId(@Param("sessionId") String sessionId, @Param("limit") Integer limit, @Param("offset") Integer offset);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE session_id = #{sessionId}")
    Integer countAuditLogsBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE puppet_id = #{puppetId}")
    Integer countAuditLogsByPuppetId(@Param("puppetId") String puppetId);

    @Select("SELECT COUNT(*) FROM audit_logs WHERE puppet_id = #{puppetId} AND create_time >= datetime('now', '-' || #{days} || ' days')")
    Integer countRecentAuditLogsByPuppetId(@Param("puppetId") String puppetId, @Param("days") Integer days);

    @Select("SELECT * FROM audit_logs WHERE puppet_id = #{puppetId} ORDER BY create_time DESC LIMIT 1")
    AuditLog findLatestAuditLogByPuppetId(@Param("puppetId") String puppetId);

    @Select("SELECT COUNT(*) FROM audit_logs a JOIN users u ON a.user_id = u.user_id WHERE u.team_id = #{teamId}")
    Integer countAuditLogsByTeamId(@Param("teamId") String teamId);

    @Select("SELECT COUNT(*) FROM audit_logs a JOIN users u ON a.user_id = u.user_id WHERE u.team_id = #{teamId} AND a.create_time >= datetime('now', '-' || #{days} || ' days')")
    Integer countRecentAuditLogsByTeamId(@Param("teamId") String teamId, @Param("days") Integer days);

    @Select("SELECT a.* FROM audit_logs a JOIN users u ON a.user_id = u.user_id WHERE u.team_id = #{teamId} ORDER BY a.create_time DESC LIMIT 1")
    AuditLog findLatestAuditLogByTeamId(@Param("teamId") String teamId);

    @Select("SELECT COUNT(*) FROM audit_logs")
    Integer countAllAuditLogs();

    @Select("SELECT operation_type AS operation, MIN(operation_name) AS operationName, COUNT(*) AS count FROM audit_logs GROUP BY operation_type ORDER BY count DESC")
    List<Map<String, Object>> countAuditLogsByOperationType();

    @Select("SELECT strftime('%Y-%m-%d', create_time) AS day, COUNT(*) AS count FROM audit_logs WHERE create_time >= datetime('now', '-' || #{days} || ' days') GROUP BY day ORDER BY day ASC")
    List<Map<String, Object>> countAuditLogsByDay(@Param("days") Integer days);

    @Delete("DELETE FROM audit_logs WHERE create_time < datetime('now', '-' || #{days} || ' days')")
    Integer deleteOldAuditLogs(@Param("days") Integer days);

    @Delete({
            "<script>",
            "DELETE FROM audit_logs WHERE log_id IN",
            "<foreach collection='logIds' item='logId' open='(' separator=',' close=')'>",
            "#{logId}",
            "</foreach>",
            "</script>"
    })
    Integer deleteAuditLogsByIds(@Param("logIds") List<String> logIds);

    @Delete({
            "<script>",
            "DELETE FROM audit_logs",
            AUDIT_LOG_FILTER_WHERE,
            "</script>"
    })
    Integer deleteAuditLogsByFilter(@Param("query") AuditLogQuery query);
}
