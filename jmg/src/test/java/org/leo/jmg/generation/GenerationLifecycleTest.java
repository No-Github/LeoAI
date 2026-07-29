package org.leo.jmg.generation;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.ShellGenerator;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TransportProtocol;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationLifecycleTest {

    @Test
    void requestSnapshotsInputAtGeneratorBoundary() {
        ShellGeneratorConfig config = injectorConfig()
                .customJspTemplate("before")
                .build();

        GenerationRequest request = GenerationRequest.from(config);
        config.getReqDisguise().setDecodeBody("changed-after-snapshot");

        assertEquals("before", request.getCustomJspTemplate());
        assertFalse(request.isBypassJavaModule());
        assertEquals(TransportProtocol.HTTP_CHUNK, request.getProtocol());
        assertFalse("changed-after-snapshot".equals(
                request.createRequestDisguiseSnapshot().getDecodeBody()));
        assertThrows(UnsupportedOperationException.class,
                () -> request.getJspObfuscationSteps().add("another-step"));
    }

    @Test
    void planResolvesCatalogPackerAndDerivedFlagsOnce() {
        GenerationPlan plan = GenerationPlan.forInjector(
                GenerationRequest.from(injectorConfig().build()));

        assertEquals(GenerationPlan.ArtifactKind.INJECTOR, plan.getArtifactKind());
        assertEquals("FilterInjector",
                plan.getInjectorDescriptor().getInjectorName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoFilterChunkTpl",
                plan.getInjectorDescriptor().getShellTemplateName());
        assertNotNull(plan.getPacker());
        assertFalse(plan.isAbstractTranslet());
    }

    @Test
    void resultOwnsFinalStateAndDefensivelyCopiesBytes() {
        GenerationRequest request =
                GenerationRequest.from(injectorConfig().build());
        GenerationPlan plan = GenerationPlan.forInjector(request);
        GenerationWorkspace workspace = GenerationWorkspace.create(request);

        try (GenerationRandom.Scope ignored =
                     GenerationRandom.withSeed(request.getObfuscationSeed())) {
            workspace.resolveClassNames();
        }
        byte[] core = new byte[]{1, 2, 3};
        workspace.setCoreClassBytes(core);
        core[0] = 9;
        byte[] returned = workspace.getCoreClassBytes();
        returned[1] = 9;
        workspace.setShellClassBytes(new byte[]{4, 5});
        workspace.setInjectorClassBytes(new byte[]{6, 7});
        GenerationResult result =
                GenerationResult.forInjector(plan, workspace, "packed");

        assertArrayEquals(new byte[]{1, 2, 3}, workspace.getCoreClassBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, result.getCoreClassBytes());
        result.getCoreClassBytes()[2] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, result.getCoreClassBytes());
        assertNotNull(result.getShellClassName());
        assertNotNull(result.getInjectorClassName());
    }

    @Test
    void completePipelineReturnsAllFinalArtifacts() throws Exception {
        GenerationRequest request = GenerationRequest.from(injectorConfig()
                .obfuscationSeed(42L)
                .build());

        GenerationResult result =
                new ShellGenerator(request).generateFormattedInjector();

        assertNotNull(result.getCoreClassBytes());
        assertNotNull(result.getShellClassBytes());
        assertNotNull(result.getInjectorClassBytes());
        assertArrayEquals(result.getInjectorClassBytes(),
                Base64.getDecoder().decode(result.getContent()));
    }

    @Test
    void generatorUsesImmutableRequestSnapshot() throws Exception {
        ShellGeneratorConfig config = injectorConfig()
                .obfuscationSeed(84L)
                .build();
        GenerationRequest request = GenerationRequest.from(config);
        config.getReqDisguise().setDecodeBody(null);

        GenerationResult result =
                new ShellGenerator(request).generateFormattedInjector();

        assertNotNull(Base64.getDecoder().decode(result.getContent()));
        assertNotNull(result.getInjectorClassName());
    }

    @Test
    void generatorCanBeReusedAndCalledConcurrently() throws Exception {
        GenerationRequest request = GenerationRequest.from(injectorConfig()
                .obfuscationSeed(126L)
                .build());
        ShellGenerator generator = new ShellGenerator(request);

        GenerationResult first = generator.generateFormattedInjector();
        GenerationResult second = generator.generateFormattedInjector();
        assertEquivalent(first, second);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Callable<GenerationResult> task = generator::generateFormattedInjector;
            List<Future<GenerationResult>> futures =
                    executor.invokeAll(Arrays.asList(task, task, task));
            for (Future<GenerationResult> future : futures) {
                assertEquivalent(first, future.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertEquivalent(GenerationResult expected,
                                         GenerationResult actual) {
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.getCoreClassName(), actual.getCoreClassName());
        assertEquals(expected.getShellClassName(), actual.getShellClassName());
        assertEquals(expected.getInjectorClassName(), actual.getInjectorClassName());
        assertArrayEquals(expected.getCoreClassBytes(), actual.getCoreClassBytes());
        assertArrayEquals(expected.getShellClassBytes(), actual.getShellClassBytes());
        assertArrayEquals(expected.getInjectorClassBytes(), actual.getInjectorClassBytes());
    }

    private static ShellGeneratorConfig.Builder injectorConfig() {
        Disguise request = new Disguise();
        request.setDecodeBody(
                "public java.util.HashMap decode(byte[] data){return new java.util.HashMap();}");
        Disguise response = new Disguise();
        response.setEncodeBody(
                "public byte[] encode(java.util.HashMap data){return new byte[0];}");
        return ShellGeneratorConfig.builder(request, response)
                .protocol("httpchunk")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .header("X-Test", "secret")
                .urlPattern("/*")
                .jspObfuscationSteps(Arrays.asList("CHUNK_PAYLOAD"));
    }
}
