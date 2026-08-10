package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.leo.web.exception.ApiException;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerDatabaseTest {

    @Test
    void includesDatabaseErrorDetailsInTheUnifiedApiResponse() {
        ApiException error = ApiException.databaseError(
                503,
                "connection interrupted",
                Map.of("errorCategory", "CONNECTION_ERROR", "retryable", true, "sqlState", "08006"));

        ResponseEntity<?> response = new GlobalExceptionHandler().handleApiException(error);
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(503, response.getStatusCode().value());
        assertEquals(503, body.get("code"));
        assertEquals("CONNECTION_ERROR", body.get("errorCategory"));
        assertEquals(true, body.get("retryable"));
        assertEquals("08006", body.get("sqlState"));
    }

    @Test
    void keepsDatabaseAuthenticationSeparateFromApplicationLoginState() {
        ApiException error = ApiException.databaseError(
                401,
                "database credentials rejected",
                Map.of("databaseCode", 401, "errorCategory", "AUTHENTICATION_ERROR"));

        ResponseEntity<?> response = new GlobalExceptionHandler().handleApiException(error);
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(422, response.getStatusCode().value());
        assertEquals(422, body.get("code"));
        assertEquals(401, body.get("databaseCode"));
    }
}
