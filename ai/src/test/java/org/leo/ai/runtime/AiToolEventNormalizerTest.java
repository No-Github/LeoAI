package org.leo.ai.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolCatalog;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiToolEventNormalizerTest {

    private final AiToolEventNormalizer normalizer =
            new AiToolEventNormalizer(new AiToolCatalog());

    @Test
    void treatsRecoveryProtocolAsFailureWhenFrameworkDropsErrorFlag() {
        ToolExecution execution = ToolExecution.builder()
                .request(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("imaginedTool")
                        .arguments("{}")
                        .build())
                .result(ToolExecutionResult.builder()
                        .resultText("""
                                {"ok":false,"protocol":"leo.tool.error.v1","error":{"code":"UNKNOWN_TOOL"}}
                                """)
                        .isError(false)
                        .build())
                .startTime(LocalDateTime.now())
                .finishTime(LocalDateTime.now())
                .invocationContext(InvocationContext.builder()
                        .chatMemoryId("memory-1")
                        .build())
                .build();

        Map<String, Object> event =
                normalizer.completed(execution);

        assertEquals(false, event.get("success"));
        assertEquals("failed", event.get("status"));
    }

    @Test
    void preservesDatabaseCredentialsInPersistedToolArguments() {
        ToolExecution execution = ToolExecution.builder()
                .request(ToolExecutionRequest.builder()
                        .id("call-2")
                        .name("createDatabaseConnection")
                        .arguments("""
                                {"config":{"connection":{"username":"app","password":"secret",
                                "runtimeOptions":{"java":{"jdbcUrl":"jdbc:mysql://db/app?password=url-secret"}}}}}
                                """)
                        .build())
                .result(ToolExecutionResult.builder()
                        .resultText("{\"connectionId\":\"connection-1\"}")
                        .isError(false)
                        .build())
                .startTime(LocalDateTime.now())
                .finishTime(LocalDateTime.now())
                .invocationContext(InvocationContext.builder()
                        .chatMemoryId("memory-1")
                        .build())
                .build();

        Map<String, Object> event = normalizer.completed(execution);
        String arguments = String.valueOf(event.get("arguments"));

        assertEquals(true, arguments.contains("\"password\":\"secret\""));
        assertEquals(true, arguments.contains("password=url-secret"));
    }

    @Test
    void exposesProtocolProtectionMetadataForTraceAndSse() {
        ToolExecution execution = ToolExecution.builder()
                .request(ToolExecutionRequest.builder()
                        .id("call-timeout")
                        .name("getSlow")
                        .arguments("{}")
                        .build())
                .result(ToolExecutionResult.builder()
                        .resultText("""
                                {"ok":false,"protocol":"leo.tool.error.v1",
                                "code":"TOOL_TIMEOUT","retryable":true,
                                "metadata":{"operation":"READ_ONLY","timeoutMs":50,
                                "recoverableBy":"MODEL","attempt":1,"maxAttempts":3}}
                                """)
                        .isError(true)
                        .build())
                .startTime(LocalDateTime.now().minusNanos(50_000_000))
                .finishTime(LocalDateTime.now())
                .invocationContext(InvocationContext.builder()
                        .chatMemoryId("memory-1")
                        .build())
                .build();

        Map<String, Object> event = normalizer.completed(execution);

        assertEquals("TOOL_TIMEOUT", event.get("code"));
        assertEquals(true, event.get("retryable"));
        assertEquals("READ_ONLY", event.get("operation"));
        assertEquals(50, event.get("timeoutMs"));
        assertEquals("MODEL", event.get("recoverableBy"));
        assertEquals(1, event.get("attempt"));
    }
}
