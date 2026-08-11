package org.leo.jmg.mem.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AgentInjectorSupportTest {
    private static final String TARGET =
            "com/tongweb/server/core/ApplicationFilterChain";

    @Test
    void transformedMethodStopsOriginalChainWhenAgentShellHandlesRequest() throws Exception {
        Recorder.invocations = 0;
        byte[] original = fixtureBytes(false);
        FixtureTransformer transformer = new FixtureTransformer();
        FixtureLoader loader = new FixtureLoader(getClass().getClassLoader());

        byte[] transformed = transformer.transform(loader, TARGET, null, null, original);
        assertNotEquals(java.util.Arrays.toString(original),
                java.util.Arrays.toString(transformed));

        Class target = loader.define(TARGET.replace('/', '.'), transformed);
        Method method = target.getMethod("doFilter", Object.class, Object.class);
        method.invoke(target.newInstance(), new Object(), new Object());
        assertEquals(0, Recorder.invocations);
    }

    @Test
    void transformedIntValveReturnsEndPipelineCodeWhenHandled() throws Exception {
        FixtureTransformer transformer = new FixtureTransformer();
        FixtureLoader loader = new FixtureLoader(getClass().getClassLoader());
        byte[] transformed = transformer.transform(
                loader, TARGET, null, null, fixtureBytes(true));

        Class target = loader.define(TARGET.replace('/', '.'), transformed);
        Method method = target.getMethod("doFilter", Object.class, Object.class);
        assertEquals(Integer.valueOf(2),
                method.invoke(target.newInstance(), new Object(), new Object()));
    }

    public static class FixtureShell {
        @Override
        public boolean equals(Object value) {
            return value instanceof Object[] && ((Object[]) value).length == 2;
        }
    }

    public static class Recorder {
        static int invocations;

        public static void mark() {
            invocations++;
        }
    }

    private static class FixtureTransformer extends AgentInjectorSupport {
        @Override
        protected String[] targetClasses() {
            return new String[]{TARGET};
        }

        @Override
        protected String targetMethodName() {
            return "doFilter";
        }

        @Override
        protected String shellClassName() {
            return FixtureShell.class.getName();
        }

        @Override
        protected String shellClassPayload() {
            return "";
        }
    }

    private static class FixtureLoader extends ClassLoader {
        FixtureLoader(ClassLoader parent) {
            super(parent);
        }

        Class define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] fixtureBytes(boolean returnsInt) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
                | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null,
                "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "doFilter", "(Ljava/lang/Object;Ljava/lang/Object;)"
                        + (returnsInt ? "I" : "V"), null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                Recorder.class.getName().replace('.', '/'), "mark", "()V", false);
        if (returnsInt) {
            method.visitIntInsn(Opcodes.BIPUSH, 9);
            method.visitInsn(Opcodes.IRETURN);
        } else {
            method.visitInsn(Opcodes.RETURN);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
