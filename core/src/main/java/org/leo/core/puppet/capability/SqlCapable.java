package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can execute SQL through remote JDBC drivers.
 */
public interface SqlCapable {

    Map<String, Object> execSql(String driverClassName,
                                String jdbcUrl,
                                String user,
                                String password,
                                String sqlScript) throws Exception;
}
