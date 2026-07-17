package org.leo.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PuppetDatabaseConnectionSerializationTest {

    @Test
    void neverSerializesDatabasePassword() throws Exception {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setConnectionId("connection-1");
        connection.setPassword("must-not-leak");

        String json = new ObjectMapper().writeValueAsString(connection);

        assertFalse(json.contains("password"));
        assertFalse(json.contains("must-not-leak"));
    }
}
