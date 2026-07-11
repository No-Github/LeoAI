package org.leo.web.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * Keeps the legacy {@code {code,msg,data}} response contract while making the
 * HTTP status line express the same outcome. Controllers can therefore migrate
 * to {@code ApiException} incrementally without returning HTTP 200 for failures.
 */
@ControllerAdvice
public class ApiResponseStatusAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof Map<?, ?> map && map.get("code") instanceof Number number) {
            int code = number.intValue();
            if (code >= 100 && code <= 599) {
                response.setStatusCode(HttpStatusCode.valueOf(code));
            }
        }
        return body;
    }
}
