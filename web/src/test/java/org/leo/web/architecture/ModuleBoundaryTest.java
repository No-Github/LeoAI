package org.leo.web.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBoundaryTest {

    @Test
    void upperLayersDoNotImportRuntimeImplementationPackages() throws Exception {
        Path root = repositoryRoot();
        List<Path> sourceRoots = List.of(
                root.resolve("service/src/main/java"),
                root.resolve("ai/src/main/java"),
                root.resolve("web/src/main/java"));
        for (Path sourceRoot : sourceRoots) {
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
    void parentPomKeepsApplicationLibrariesInDependencyManagement() throws Exception {
        Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(repositoryRoot().resolve("pom.xml").toFile()).getDocumentElement();
        Set<String> applicationArtifacts = Set.of(
                "mybatis-spring-boot-starter", "sqlite-jdbc",
                "langchain4j", "langchain4j-open-ai", "langchain4j-http-client-jdk");

        for (Element dependencies : directChildren(project, "dependencies")) {
            for (Element dependency : directChildren(dependencies, "dependency")) {
                String artifactId = directText(dependency, "artifactId");
                assertFalse(applicationArtifacts.contains(artifactId), artifactId);
            }
        }
    }

    @Test
    void aiSseEntryPointsDoNotCreateUnboundedCachedExecutors() throws Exception {
        Path webSource = repositoryRoot().resolve("web/src/main/java/org/leo/web");
        List<Path> entryPoints = List.of(
                webSource.resolve("controller/platform/ai/PlatformAiController.java"),
                webSource.resolve("controller/puppetnode/ai/PuppetNodeAiController.java"),
                webSource.resolve("service/PlatformAiTurnService.java"),
                webSource.resolve("service/PuppetNodeAiTurnService.java"),
                webSource.resolve("service/PuppetNodeAiDelegationService.java"));

        for (Path entryPoint : entryPoints) {
            String source = Files.readString(entryPoint);
            assertFalse(source.contains("Executors.newCachedThreadPool"), entryPoint.toString());
            assertFalse(source.contains("static final ExecutorService"), entryPoint.toString());
        }
    }

    @Test
    void aiEntryPointsUseSharedTurnCoordinatorForLifecycleTransitions() throws Exception {
        Path webSource = repositoryRoot().resolve("web/src/main/java/org/leo/web");
        List<Path> entryPoints = List.of(
                webSource.resolve("controller/platform/ai/PlatformAiController.java"),
                webSource.resolve("controller/puppetnode/ai/PuppetNodeAiController.java"),
                webSource.resolve("service/PlatformAiTurnService.java"),
                webSource.resolve("service/PuppetNodeAiTurnService.java"),
                webSource.resolve("service/PuppetNodeAiDelegationService.java"));
        List<String> directTransitions = List.of(
                ".claimExecution(",
                ".markExecuting(",
                ".markCompleted(",
                ".markFailed(",
                ".markCancelled(",
                ".clearExecuting(");

        for (Path entryPoint : entryPoints) {
            String source = Files.readString(entryPoint);
            for (String transition : directTransitions) {
                assertFalse(source.contains(transition), entryPoint + " 直接调用了 " + transition);
            }
        }
    }

    @Test
    void aiWebServicesDoNotManipulateLangChainMemoryDirectly() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> services = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiDelegationService.java"));

        for (Path service : services) {
            String source = Files.readString(service);
            assertFalse(source.contains("import dev.langchain4j.memory.ChatMemory"), service.toString());
            assertFalse(source.contains(".getChatMemory("), service.toString());
            assertFalse(source.contains("rollbackCancelledTurn"), service.toString());
            assertFalse(source.contains("withPersistedHistoryContext"), service.toString());
        }
    }

    @Test
    void aiWebServicesDelegateLangChainStreamingCallbacksToExecutionEngine() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> services = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiDelegationService.java"));
        List<String> callbacks = List.of(
                ".onPartialThinkingWithContext(",
                ".onPartialResponseWithContext(",
                ".onPartialToolCallWithContext(",
                ".beforeToolExecution(",
                ".onToolExecuted(",
                ".onCompleteResponse(",
                ".onError(");

        for (Path service : services) {
            String source = Files.readString(service);
            for (String callback : callbacks) {
                assertFalse(source.contains(callback),
                        service + " 直接注册了 LangChain 回调 " + callback);
            }
        }
    }

    @Test
    void aiWebServicesDelegateSseTransportAndAgentRuntimeCaching() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> services = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"));
        List<String> transportInternals = List.of(
                "SseEmitter.event()",
                "AiSseEventPump",
                "startQueueDrain(",
                "sendExistingEvent(");
        List<String> agentRuntimeInternals = List.of(
                "AiAgentFactory",
                "DynamicModelProvider",
                "plannedRuntimeCacheKey(",
                "buildRuntime(",
                "ConcurrentMap<");

        for (Path service : services) {
            String source = Files.readString(service);
            for (String internal : transportInternals) {
                assertFalse(source.contains(internal),
                        service + " 直接实现了 SSE transport 细节 " + internal);
            }
            for (String internal : agentRuntimeInternals) {
                assertFalse(source.contains(internal),
                        service + " 直接实现了 Agent runtime 缓存细节 " + internal);
            }
        }
    }

    @Test
    void aiApplicationServicesKeepThreadAndTurnUseCasesSeparate() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> threadServices = List.of(
                serviceSource.resolve("PlatformAiThreadService.java"),
                serviceSource.resolve("PuppetNodeAiThreadService.java"));
        List<Path> turnServices = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiDelegationService.java"));

        for (Path service : threadServices) {
            String source = Files.readString(service);
            assertFalse(source.contains("AiTurnExecutionEngine"), service.toString());
            assertFalse(source.contains("SseEmitter"), service.toString());
            assertFalse(source.contains("executeChat("), service.toString());
        }
        for (Path service : turnServices) {
            String source = Files.readString(service);
            assertFalse(source.contains("listThreads("), service.toString());
            assertFalse(source.contains("createThread("), service.toString());
            assertFalse(source.contains("deleteThread("), service.toString());
            assertFalse(source.contains("restorePersistedThread("), service.toString());
        }
    }

    @Test
    void aiTurnServicesDelegatePersistenceFinalization() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> turnServices = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiDelegationService.java"));
        List<String> finalizationInternals = List.of(
                ".completeTurn(",
                ".discardTurn(",
                ".recordSuccess(",
                ".recordFailure(",
                "managedMemory.rebuild(");

        for (Path service : turnServices) {
            String source = Files.readString(service);
            for (String internal : finalizationInternals) {
                assertFalse(source.contains(internal),
                        service + " 绕过了统一 Turn 终结器: " + internal);
            }
        }
    }

    @Test
    void aiTurnServicesDelegateExecutionOrchestration() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> turnServices = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiDelegationService.java"));
        List<String> orchestrationInternals = List.of(
                "AiTurnExecutionEngine",
                "AiTurnExecutionListener",
                ".commit(",
                ".discard(",
                ".recoverTerminalFailure(");

        for (Path service : turnServices) {
            String source = Files.readString(service);
            for (String internal : orchestrationInternals) {
                assertFalse(source.contains(internal),
                        service + " 绕过了统一 Turn 编排器: " + internal);
            }
            assertFalse(source.contains("AiTurnOrchestrator") && !source.contains(
                    "turnOrchestrator.execute("), service.toString());
        }
    }

    @Test
    void aiTurnServicesDelegateTerminalPresentation() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        Path platform = serviceSource.resolve("PlatformAiTurnService.java");
        Path puppet = serviceSource.resolve("PuppetNodeAiTurnService.java");
        Path delegation = serviceSource.resolve("PuppetNodeAiDelegationService.java");
        List<String> presentationInternals = List.of(
                "new AiTurnOrchestrator.Lifecycle",
                "AiTimelineRecorder",
                "AiTurnTimelineEventAdapter",
                "AiSseTransport",
                "AiControllerUtil",
                "onCommitted(",
                "onDiscarded(",
                "onTerminal(");

        for (Path service : List.of(platform, puppet, delegation)) {
            String source = Files.readString(service);
            for (String internal : presentationInternals) {
                assertFalse(source.contains(internal),
                        service + " 绕过了 Turn Presenter: " + internal);
            }
        }

        String platformSource = Files.readString(platform);
        String puppetSource = Files.readString(puppet);
        String delegationSource = Files.readString(delegation);
        assertTrue(platformSource.contains("sseTurnPresenter.open("), platform.toString());
        assertTrue(puppetSource.contains("sseTurnPresenter.open("), puppet.toString());
        assertTrue(delegationSource.contains("delegationPresenter.open("),
                delegation.toString());
    }

    @Test
    void aiTurnServicesPropagateOneTraceAndDelegateItsFinalization() throws Exception {
        Path serviceSource = repositoryRoot().resolve("web/src/main/java/org/leo/web/service");
        List<Path> services = List.of(
                serviceSource.resolve("PlatformAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiTurnService.java"),
                serviceSource.resolve("PuppetNodeAiDelegationService.java"));

        for (Path service : services) {
            String source = Files.readString(service);
            assertTrue(source.contains("AiTurnTrace.start("),
                    service + " 未在准备阶段创建 trace");
            assertTrue(source.contains("AiTurnTrace.Checkpoint.AGENT_RESOLVED"),
                    service + " 未记录 Agent 解析阶段");
            assertTrue(source.contains("agentRuntime.runtimeJson(),")
                            && (source.contains("trace);")
                                    || source.contains("trace,")),
                    service + " 未把同一 trace 绑定到持久化 Run");
            assertFalse(source.contains(".updateRunTrace("),
                    service + " 不应直接完成 trace 持久化");
            assertFalse(source.contains("telemetryRegistry.record("),
                    service + " 不应直接完成终态指标");
        }
    }

    @Test
    void aiBackgroundServicesUseManagedExecutors() throws Exception {
        Path aiSource = repositoryRoot().resolve("ai/src/main/java/org/leo/ai");
        List<Path> services = List.of(
                aiSource.resolve("channel/AiModelCapabilityProbeService.java"),
                aiSource.resolve("service/SessionWarmupService.java"));

        for (Path service : services) {
            String source = Files.readString(service);
            assertFalse(source.contains("Executors.newCachedThreadPool"), service.toString());
            assertFalse(source.contains("static final ExecutorService"), service.toString());
        }
    }

    @Test
    void transferServicesUseManagedExecutors() throws Exception {
        Path serviceSource = repositoryRoot().resolve("service/src/main/java/org/leo/service");
        List<Path> services = List.of(
                serviceSource.resolve("sql/SqlExportService.java"),
                serviceSource.resolve("UploadEngineService.java"),
                serviceSource.resolve("downloadengine/DownloadTask.java"));

        for (Path service : services) {
            String source = Files.readString(service);
            assertFalse(source.contains("Executors.newCachedThreadPool"), service.toString());
            assertFalse(source.contains("Executors.newFixedThreadPool"), service.toString());
            assertFalse(source.contains("new ThreadPoolExecutor"), service.toString());
        }
    }

    @Test
    void coreNetworkEnginesAvoidPerTaskAndCachedExecutors() throws Exception {
        Path coreSource = repositoryRoot().resolve("core/src/main/java/org/leo/core");
        Path reverseTunnel = coreSource.resolve("engine/reverse/ReverseTunnelServer.java");
        Path httpSender = coreSource.resolve("puppet/http/HttpSenderEngine.java");

        String reverseSource = Files.readString(reverseTunnel);
        assertFalse(reverseSource.contains("Executors.newCachedThreadPool"), reverseTunnel.toString());
        assertFalse(reverseSource.contains("Executors.newFixedThreadPool"), reverseTunnel.toString());

        String senderSource = Files.readString(httpSender);
        assertFalse(senderSource.contains("Executors.newCachedThreadPool"), httpSender.toString());
        assertFalse(senderSource.contains("Executors.newFixedThreadPool"), httpSender.toString());
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

    private List<Element> directChildren(Element parent, String name) {
        java.util.ArrayList<Element> result = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private String directText(Element parent, String name) {
        List<Element> values = directChildren(parent, name);
        return values.isEmpty() ? "" : values.get(0).getTextContent().trim();
    }
}
