package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.PuppetDatabaseConnection;

import java.util.List;

@Mapper
public interface PuppetDatabaseConnectionMapper {

    @Insert("INSERT INTO puppet_database_connections (connection_id, connection_name, puppet_id, dialect, connection_spec, username, password, status, test_status, last_test_time, last_test_message, max_connections, timeout_seconds, create_user_id, create_time, update_time, description, remark) " +
            "VALUES (#{connectionId}, #{connectionName}, #{puppetId}, #{dialect}, #{connectionSpec}, #{username}, #{password}, #{status}, #{testStatus}, #{lastTestTime}, #{lastTestMessage}, #{maxConnections}, #{timeoutSeconds}, #{createUserId}, #{createTime}, #{updateTime}, #{description}, #{remark})")
    int insert(PuppetDatabaseConnection connection);

    @Update("UPDATE puppet_database_connections SET connection_name=#{connectionName}, puppet_id=#{puppetId}, dialect=#{dialect}, connection_spec=#{connectionSpec}, username=#{username}, password=#{password}, status=#{status}, test_status=#{testStatus}, last_test_time=#{lastTestTime}, last_test_message=#{lastTestMessage}, max_connections=#{maxConnections}, timeout_seconds=#{timeoutSeconds}, update_time=#{updateTime}, description=#{description}, remark=#{remark} WHERE connection_id=#{connectionId}")
    int update(PuppetDatabaseConnection connection);

    @Update("UPDATE puppet_database_connections SET test_status=#{testStatus}, "
            + "last_test_time=datetime('now'), last_test_message=#{testMessage}, "
            + "update_time=datetime('now') WHERE connection_id=#{connectionId}")
    int updateTestStatus(@Param("connectionId") String connectionId,
                         @Param("testStatus") Integer testStatus,
                         @Param("testMessage") String testMessage);

    @Update("UPDATE puppet_database_connections SET status=#{status}, "
            + "update_time=datetime('now') WHERE connection_id=#{connectionId} AND puppet_id=#{puppetId}")
    int updateStatusByPuppet(@Param("connectionId") String connectionId,
                            @Param("puppetId") String puppetId,
                            @Param("status") Integer status);

    @Delete("DELETE FROM puppet_database_connections WHERE connection_id=#{connectionId} AND puppet_id=#{puppetId}")
    int deleteByIdAndPuppet(@Param("connectionId") String connectionId,
                           @Param("puppetId") String puppetId);

    @Select("SELECT * FROM puppet_database_connections WHERE connection_id=#{connectionId}")
    PuppetDatabaseConnection selectById(@Param("connectionId") String connectionId);

    @Select("SELECT * FROM puppet_database_connections WHERE puppet_id=#{puppetId} ORDER BY create_time DESC")
    List<PuppetDatabaseConnection> selectByPuppetId(@Param("puppetId") String puppetId);

    @Select("<script>SELECT COUNT(*) > 0 FROM puppet_database_connections "
            + "WHERE puppet_id=#{puppetId} AND connection_name=#{connectionName} " +
            "<if test='excludeConnectionId != null and excludeConnectionId != \"\"'>AND connection_id != #{excludeConnectionId}</if></script>")
    boolean existsByName(@Param("puppetId") String puppetId,
                         @Param("connectionName") String connectionName,
                         @Param("excludeConnectionId") String excludeConnectionId);

}
