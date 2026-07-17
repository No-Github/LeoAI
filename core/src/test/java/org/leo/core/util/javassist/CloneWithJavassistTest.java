package org.leo.core.util.javassist;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloneWithJavassistTest {

    @Test
    void rewritesSelfMethodCallsInsideStaticInitializer() throws Exception {
        String className = "org.leo.generated.StaticInit" + System.nanoTime();
        ClassPool pool = new ClassPool(true);
        CtClass generated = pool.makeClass(className);
        generated.addField(CtField.make("private static String marker = createMarker();", generated));
        generated.addMethod(CtNewMethod.make(
                "private static String createMarker() { return \"static-init-ok\"; }", generated));

        byte[] originalBytecode;
        try {
            originalBytecode = generated.toBytecode();
        } finally {
            generated.detach();
        }

        CtClass transformedClass = pool.makeClass(new ByteArrayInputStream(originalBytecode));
        byte[] bytecode;
        try {
            CloneWithJavassist.randomizeNames(transformedClass);
            bytecode = transformedClass.toBytecode();
        } finally {
            transformedClass.detach();
        }

        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        Field marker = transformed.getDeclaredFields()[0];
        marker.setAccessible(true);
        assertEquals("static-init-ok", marker.get(null));
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
