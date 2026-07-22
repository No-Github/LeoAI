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
                webSource.resolve("service/PlatformAiService.java"),
                webSource.resolve("service/PuppetNodeAiThreadService.java"));

        for (Path entryPoint : entryPoints) {
            String source = Files.readString(entryPoint);
            assertFalse(source.contains("Executors.newCachedThreadPool"), entryPoint.toString());
            assertFalse(source.contains("static final ExecutorService"), entryPoint.toString());
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
