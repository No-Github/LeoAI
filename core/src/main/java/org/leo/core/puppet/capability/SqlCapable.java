package org.leo.core.puppet.capability;

import org.leo.core.puppet.database.DatabaseConnectionSpec;

import java.util.Map;

/**
 * Capability marker for nodes that can execute SQL through their native
 * database provider (JDBC for Java, PDO for PHP).
 */
public interface SqlCapable {

    Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                   String sqlScript) throws Exception;
}
