package org.leo.web.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBoundaryTest {

    @Test
    void keepsRuntimeImplementationsBehindTheModuleBoundary() throws Exception {
        Path root = repositoryRoot();
        for (Path sourceRoot : List.of(
                root.resolve("service/src/main/java"),
                root.resolve("ai/src/main/java"),
                root.resolve("web/src/main/java"))) {
            try (var files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    assertFalse(source.contains("import org.leo.javacore"), file.toString());
                    assertFalse(source.contains("import org.leo.phpcore"), file.toString());
                }
            }
        }
    }

    @Test
    void asynchronousBoundariesUseManagedExecutors() throws Exception {
        Path root = repositoryRoot();
        List<Path> sources = List.of(
                root.resolve("web/src/main/java/org/leo/web/controller/platform/ai/PlatformAiController.java"),
                root.resolve("web/src/main/java/org/leo/web/controller/puppetnode/ai/PuppetNodeAiController.java"),
                root.resolve("web/src/main/java/org/leo/web/service/PlatformAiTurnService.java"),
                root.resolve("web/src/main/java/org/leo/web/service/PuppetNodeAiTurnService.java"),
                root.resolve("web/src/main/java/org/leo/web/service/PuppetNodeAiDelegationService.java"),
                root.resolve("ai/src/main/java/org/leo/ai/channel/AiModelCapabilityProbeService.java"),
                root.resolve("ai/src/main/java/org/leo/ai/service/SessionWarmupService.java"),
                root.resolve("service/src/main/java/org/leo/service/sql/SqlExportService.java"),
                root.resolve("service/src/main/java/org/leo/service/UploadEngineService.java"),
                root.resolve("service/src/main/java/org/leo/service/downloadengine/DownloadTask.java"),
                root.resolve("core/src/main/java/org/leo/core/engine/reverse/ReverseTunnelServer.java"),
                root.resolve("core/src/main/java/org/leo/core/puppet/http/HttpSenderEngine.java"));
        List<String> unmanagedFactories = List.of(
                "Executors.newCachedThreadPool",
                "Executors.newFixedThreadPool",
                "static final ExecutorService");

        for (Path sourceFile : sources) {
            String source = Files.readString(sourceFile);
            for (String factory : unmanagedFactories) {
                assertFalse(source.contains(factory), sourceFile + " uses " + factory);
            }
        }
    }

    @Test
    void aiTurnServicesKeepLifecycleWorkInSharedComponents() throws Exception {
        Path services = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        Path platform = services.resolve("PlatformAiTurnService.java");
        Path puppet = services.resolve("PuppetNodeAiTurnService.java");
        Path delegation = services.resolve("PuppetNodeAiDelegationService.java");
        List<String> forbiddenInternals = List.of(
                ".claimExecution(", ".markCompleted(", ".markFailed(",
                "import dev.langchain4j.memory.ChatMemory", ".getChatMemory(",
                ".onPartialResponseWithContext(", ".onCompleteResponse(",
                ".completeTurn(", ".discardTurn(", "managedMemory.rebuild(",
                "new AiTurnOrchestrator.Lifecycle", "AiTimelineRecorder",
                "AiTurnTimelineEventAdapter", "SseEmitter.event()");

        for (Path service : List.of(platform, puppet, delegation)) {
            String source = Files.readString(service);
            for (String internal : forbiddenInternals) {
                assertFalse(source.contains(internal), service + " bypasses shared lifecycle: " + internal);
            }
            assertTrue(source.contains("turnOrchestrator.execute("), service.toString());
        }
        assertTrue(Files.readString(platform).contains("sseTurnPresenter.open("));
        assertTrue(Files.readString(puppet).contains("sseTurnPresenter.open("));
        assertTrue(Files.readString(delegation).contains("delegationPresenter.open("));
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("core")) && Files.isDirectory(current.resolve("web"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("core"))
                && Files.isDirectory(parent.resolve("web"))) {
            return parent;
        }
        throw new IllegalStateException("repository root not found from " + current);
    }
}
