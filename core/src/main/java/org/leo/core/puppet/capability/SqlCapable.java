package org.leo.core.puppet.capability;

import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.SqlCommand;

import java.util.Map;

/**
 * Capability marker for nodes that can execute SQL through their native
 * database provider (JDBC for Java, PDO for PHP).
 */
public interface SqlCapable {

    Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                   String sqlScript) throws Exception;

    /**
     * Executes a SQL command with values kept separate from the SQL text.
     * Implementations backed by prepared statements should override this
     * method. The default preserves the original functional-interface shape.
     */
    default Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                           SqlCommand command) throws Exception {
        return executeSql(connection, command.sql());
    }

    /**
     * Inspects the database provider inside the remote Puppet runtime.
     *
     * <p>The request is intentionally an incomplete connection description:
     * capability inspection must work before a connection can be validated.</p>
     */
    default Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) throws Exception {
        return Map.of(
                "code", 501,
                "runtime", "unknown",
                "provider", "unknown",
                "available", false,
                "msg", "当前 Puppet 不支持数据库运行时能力探测");
    }
}
