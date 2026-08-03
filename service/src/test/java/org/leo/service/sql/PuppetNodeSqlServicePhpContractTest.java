package org.leo.service.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.util.json.PortableJsonCodec;
import org.leo.phpcore.database.PhpDatabaseConnectionAdapter;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuppetNodeSqlServicePhpContractTest {

    private Path component;
    private Path database;
    private PuppetNodeSqlService service;
    private Map<String, Object> connection;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("php", "-v"), "PHP CLI is not installed");
        Assumptions.assumeTrue(commandSucceeds("php", "-r",
                "exit(in_array('sqlite',PDO::getAvailableDrivers(),true)?0:1);"), "pdo_sqlite is not installed");
        URL resource = Objects.requireNonNull(getClass().getResource("/components/DatabaseComponent.php"));
        String urlString = resource.toString();
        if (urlString.startsWith("jar:")) {
            try (var in = resource.openStream()) {
                component = Files.createTempFile("leo-database-component-", ".php");
                component.toFile().deleteOnExit();
                Files.copy(in, component, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            component = Paths.get(resource.toURI());
        }
        database = Files.createTempFile("leo-php-sql-service-", ".sqlite");
        service = new PuppetNodeSqlService(new SqlDialectRegistry());
        connection = new LinkedHashMap<>();
        connection.put("dialect", "sqlite");
        connection.put("connectionMode", "standard");
        connection.put("variant", "file");
        connection.put("file", database.toAbsolutePath().toString());
        connection.put("username", "");
        connection.put("password", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) Files.deleteIfExists(database);
    }

    @Test
    void supportsTheDatabaseManagementWorkflowThroughSqlCapable() throws Exception {
        SqlCapable php = new PhpComponentSqlCapable(component);

        Map<String, Object> tested = service.testConnection(php, connection);
        assertTrue(String.valueOf(tested.get("databaseVersion")).matches("\\d+\\.\\d+.*"));

        Map<String, Object> created = service.createTable(php, connection, "main", "inventory", List.of(
                Map.of("name", "id", "type", "INTEGER", "nullable", false, "primaryKey", true),
                Map.of("name", "name", "type", "TEXT", "nullable", false),
                Map.of("name", "quantity", "type", "INTEGER", "nullable", true)));
        assertEquals("inventory", created.get("table"));

        assertEquals(1, ((Number) service.insertRow(php, connection, "main", "inventory",
                Map.of("id", 1, "name", "alpha", "quantity", 3)).get("affectedRows")).intValue());
        assertEquals(1, ((Number) service.insertRow(php, connection, "main", "inventory",
                Map.of("id", 2, "name", "beta", "quantity", 5)).get("affectedRows")).intValue());

        List<?> databases = (List<?>) service.getDatabases(php, connection).get("databases");
        assertEquals("main", ((Map<?, ?>) databases.get(0)).get("name"));

        List<?> tables = (List<?>) service.getTables(php, connection, "main").get("tables");
        Map<?, ?> inventory = tables.stream().map(Map.class::cast)
                .filter(item -> "inventory".equals(item.get("name"))).findFirst().orElseThrow();
        assertEquals(2L, ((Number) inventory.get("rowCount")).longValue());

        List<?> columns = (List<?>) service.getTableColumns(php, connection, "main", "inventory").get("columns");
        assertEquals(List.of("id", "name", "quantity"), columns.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList());
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) columns.get(0)).get("primaryKey")));

        Map<String, Object> page = service.queryTable(php, connection, "main", "inventory",
                1, 1, List.of("id", "name", "quantity"),
                List.of(Map.of("field", "id", "direction", "DESC")), List.of());
        assertEquals(2L, ((Number) ((Map<?, ?>) page.get("pagination")).get("total")).longValue());
        List<?> pageRows = (List<?>) page.get("rows");
        assertEquals(1, pageRows.size());
        assertEquals("beta", ((Map<?, ?>) pageRows.get(0)).get("name"));
        assertFalse(((List<?>) page.get("columns")).isEmpty());

        assertEquals(1, ((Number) service.updateRows(php, connection, "main", "inventory",
                Map.of("type", "pk", "values", Map.of("id", 1)),
                Map.of("quantity", 9)).get("affectedRows")).intValue());
        Map<String, Object> query = service.executeSql(php, connection,
                "SELECT quantity FROM inventory WHERE id = 1");
        assertEquals(9, ((Number) ((Map<?, ?>) ((List<?>) query.get("rows")).get(0)).get("quantity")).intValue());

        assertEquals(1, ((Number) service.deleteRows(php, connection, "main", "inventory",
                Map.of("type", "pk", "values", Map.of("id", 2))).get("affectedRows")).intValue());
    }

    private static final class PhpComponentSqlCapable implements SqlCapable {
        private final Path component;

        private PhpComponentSqlCapable(Path component) {
            this.component = component;
        }

        @Override
        public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) throws Exception {
            Map<String, Object> params = new LinkedHashMap<>(new PhpDatabaseConnectionAdapter().adapt(connection));
            params.put("sql", sqlScript);
            String encoded = Base64.getEncoder().encodeToString(PortableJsonCodec.encode(params));
            String script = "function leo_binary($value){return array('$leoBinary'=>base64_encode($value));}"
                    + "$component=require $argv[1];"
                    + "$params=json_decode(base64_decode($argv[2]),true);"
                    + "echo json_encode(call_user_func($component['handle'],'exec',$params));";
            Process process = new ProcessBuilder("php", "-r", script, component.toString(), encoded)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PHP database component timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException(output);
            return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
