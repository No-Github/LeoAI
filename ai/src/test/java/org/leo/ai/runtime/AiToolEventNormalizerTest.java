package org.leo.ai.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiToolEventNormalizerTest {

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
                AiToolEventNormalizer.completed(execution);

        assertEquals(false, event.get("success"));
        assertEquals("failed", event.get("status"));
    }

    @Test
    void redactsDatabaseCredentialsFromPersistedToolArguments() {
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

        Map<String, Object> event = AiToolEventNormalizer.completed(execution);
        String arguments = String.valueOf(event.get("arguments"));

        assertFalse(arguments.contains("secret"));
        assertFalse(arguments.contains("url-secret"));
        assertEquals(true, arguments.contains("***"));
    }
}
