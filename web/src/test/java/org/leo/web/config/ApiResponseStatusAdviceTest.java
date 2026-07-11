package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.leo.core.util.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiResponseStatusAdviceTest {

    private final ApiResponseStatusAdvice advice = new ApiResponseStatusAdvice();

    @Test
    void mirrorsEnvelopeErrorCodeToHttpStatus() {
        var body = ApiResponse.conflict("资源已存在");
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);

        Object result = advice.beforeBodyWrite(body, null, null, null,
                new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/test")), response);

        assertSame(body, result);
        assertEquals(HttpStatus.CONFLICT.value(), servletResponse.getStatus());
    }

    @Test
    void keepsNonEnvelopeBodiesUntouched() {
        var body = new byte[]{1, 2, 3};
        var servletResponse = new MockHttpServletResponse();
        var response = new ServletServerHttpResponse(servletResponse);

        Object result = advice.beforeBodyWrite(body, null, null, null,
                new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/download")), response);

        assertSame(body, result);
        assertEquals(HttpStatus.OK.value(), servletResponse.getStatus());
    }
}
