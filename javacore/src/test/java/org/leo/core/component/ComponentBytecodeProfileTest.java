package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.core.util.javassist.CloneWithJavassist;
import org.leo.core.util.request.ClassNameGenerator;
import org.objectweb.asm.ClassReader;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentBytecodeProfileTest {

    @Test
    void transformedWorkerPoolsUseTheRuntimeClassProfile() throws Exception {
        String session = "host-a|https://example.test/api";
        String[] components = {
                "PortScanComponent", "FingerprintComponent",
                "HostIsReachableComponent", "ReconScanComponent"
        };

        BytecodeLoader loader = new BytecodeLoader();
        for (String component : components) {
            String className = ClassNameGenerator.generateComponentClassName(session, component);
            byte[] bytecode = minimize(CloneWithJavassist.cloneClass(component, className,
                    ClassNameGenerator.stableSeed(session + "|" + component)));
            Object instance = loader.define(className, bytecode).getDeclaredConstructor().newInstance();
            Thread worker = ((ThreadFactory) instance).newThread(() -> { });

            assertTrue(worker.getName().startsWith(instance.getClass().getSimpleName() + "-"));
            assertFalse(worker.getName().startsWith("pool-"));
        }
    }

    @Test
    void producesStableJava6BytecodeForOneHostProfile() throws Exception {
        String component = "BasicInfoComponent";
        String session = "host-a|https://example.test/api";
        String className = ClassNameGenerator.generateComponentClassName(session, component);
        long seed = ClassNameGenerator.stableSeed(session + "|" + component);

        byte[] first = minimize(CloneWithJavassist.cloneClass(component, className, seed));
        byte[] second = minimize(CloneWithJavassist.cloneClass(component, className, seed));

        assertArrayEquals(first, second);
        assertEquals(50, majorVersion(first));
        assertEquals(className.replace('.', '/'), new ClassReader(first).getClassName());
        assertFalse(new String(first, StandardCharsets.ISO_8859_1)
                .contains("org/leo/core/component/BasicInfoComponent"));
        assertTrue(Runnable.class.isAssignableFrom(new BytecodeLoader().define(className, first)));
    }

    @Test
    void variesClassAndMemberProfileBetweenHosts() throws Exception {
        String component = "BasicInfoComponent";
        String sessionA = "host-a|https://example.test/api";
        String sessionB = "host-b|https://example.test/api";
        String classA = ClassNameGenerator.generateComponentClassName(sessionA, component);
        String classB = ClassNameGenerator.generateComponentClassName(sessionB, component);

        byte[] bytesA = minimize(CloneWithJavassist.cloneClass(component, classA,
                ClassNameGenerator.stableSeed(sessionA + "|" + component)));
        byte[] bytesB = minimize(CloneWithJavassist.cloneClass(component, classB,
                ClassNameGenerator.stableSeed(sessionB + "|" + component)));

        assertNotEquals(classA, classB);
        assertFalse(java.util.Arrays.equals(bytesA, bytesB));
        assertEquals(50, majorVersion(bytesB));
    }

    private byte[] minimize(byte[] value) {
        return ClassFileMinimizer.transform(value);
    }

    private int majorVersion(byte[] value) {
        return ((value[6] & 0xff) << 8) | (value[7] & 0xff);
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
