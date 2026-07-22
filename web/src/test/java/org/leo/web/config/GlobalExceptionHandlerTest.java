package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void returnsAQuietNonCacheable404ForMissingStaticAssets() {
        var servletResponse = new MockHttpServletResponse();
        var response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "assets/old-chunk.js"),
                servletResponse);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("no-store", servletResponse.getHeader("Cache-Control"));
        assertEquals(404, response.getBody().get("code"));
    }
}
