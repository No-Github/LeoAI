package org.leo.jmg.generation;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CoreArtifactGenerationServiceTest {

    private final CoreArtifactGenerationService service = new CoreArtifactGenerationService();

    @Test
    void generatesReproducibleImmutableCoreArtifact() throws Exception {
        CoreArtifactGenerationCommand command = CoreArtifactGenerationCommand.builder(
                        requestDisguise(), responseDisguise())
                .protocol("http")
                .coreClassName("org.demo.GeneratedCore")
                .targetJavaVersion("8")
                .servletNamespace("javax")
                .obfuscationSeed(501L)
                .build();

        CoreArtifact first = service.generate(command);
        CoreArtifact second = service.generate(command);

        assertEquals("org.demo.GeneratedCore", first.getCoreClassName());
        assertFalse(first.getBytecodeSize() == 0);
        assertEquals(64, first.getSha256().length());
        assertEquals(first.getSha256(), second.getSha256());
        assertArrayEquals(first.getBytecode(), second.getBytecode());

        byte[] exposedCopy = first.getBytecode();
        exposedCopy[0] = (byte) (exposedCopy[0] + 1);
        assertFalse(exposedCopy[0] == first.getBytecode()[0]);
    }

    private static Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setDecodeBody(
                "public java.util.HashMap decode(byte[] data){return new java.util.HashMap();}");
        return disguise;
    }

    private static Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setEncodeBody(
                "public byte[] encode(java.util.HashMap data){return new byte[0];}");
        return disguise;
    }
}
