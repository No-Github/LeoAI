package org.leo.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetJdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PuppetJdbcSerializationTest {

    @Test
    void neverSerializesJdbcPassword() throws Exception {
        PuppetJdbc connection = new PuppetJdbc();
        connection.setConnId("conn-1");
        connection.setPassword("must-not-leak");

        String json = new ObjectMapper().writeValueAsString(connection);

        assertFalse(json.contains("password"));
        assertFalse(json.contains("must-not-leak"));
    }
}
