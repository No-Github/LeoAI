package org.leo.ai.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolCatalog;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.core.entity.AiExecutionPolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOperationAssessmentServiceTest {

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
    }

    @Test
    void assessmentBindsExactArgumentsAndCanOnlyBeConsumedOnce() {
        AiToolCatalog catalog = catalog(new MutationTools());
        AiOperationAssessmentService service =
                new AiOperationAssessmentService(catalog);
        bindContext();

        service.assess("platform-thread", "exec",
                "{\"cmd\":\"whoami\",\"timeout\":0}",
                "LOW", false, "只读取当前用户", null, null);

        AiOperationAssessmentService.Assessment assessment =
                service.find("platform-thread", "exec",
                        "{\"timeout\":0,\"cmd\":\"whoami\"}");
        assertNotNull(assessment);
        assertFalse(assessment.requiresConfirmation());
        assertNull(service.find("platform-thread", "exec",
                "{\"cmd\":\"systemctl restart app\",\"timeout\":0}"));
        assertTrue(service.consume(assessment));
        assertFalse(service.consume(assessment));
        assertNull(service.find("platform-thread", "exec",
                "{\"cmd\":\"whoami\",\"timeout\":0}"));
    }

    @Test
    void refusesAssessmentForReadOnlyAndInternalTools() {
        AiToolCatalog catalog = catalog(new MutationTools(), new ReadTools(),
                new InternalTools());
        AiOperationAssessmentService service =
                new AiOperationAssessmentService(catalog);
        bindContext();

        assertThrows(RuntimeException.class, () -> service.assess(
                "platform-thread", "readFile", "{\"path\":\"/etc/passwd\"}",
                "LOW", false, "只读", null, null));
        assertThrows(RuntimeException.class, () -> service.assess(
                "platform-thread", "updatePlan", "{}",
                "LOW", false, "内部控制", null, null));
    }

    @Test
    void refusesHighRiskAssessmentWithoutConfirmation() {
        AiToolCatalog catalog = catalog(new MutationTools());
        AiOperationAssessmentService service = new AiOperationAssessmentService(catalog);
        bindContext();

        assertThrows(RuntimeException.class, () -> service.assess(
                "platform-thread", "exec", "{\"cmd\":\"rm -f data\"}",
                "CRITICAL", false, "不可逆删除", "数据丢失", null));
    }

    private static AiToolCatalog catalog(Object... sources) {
        AiToolCatalog catalog = new AiToolCatalog();
        for (Object source : sources) {
            ToolService.findTools(source).forEach(tool -> catalog.register(source, tool));
        }
        return catalog;
    }

    private static void bindContext() {
        AiToolContext.setFromMemoryId("platform-thread");
        AiExecutionPolicy policy = new AiExecutionPolicy();
        policy.setUserId("user-1");
        policy.setPrivilege("normal");
        AiToolContext.setExecutionPolicy(policy);
    }

    private static class MutationTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE)
        public String exec(String cmd, int timeout) {
            return cmd;
        }
    }

    private static class ReadTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY)
        public String readFile(String path) {
            return path;
        }
    }

    private static class InternalTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.CONTROL, operation = AiToolOperation.WRITE,
                business = false)
        public String updatePlan() {
            return "ok";
        }
    }
}
