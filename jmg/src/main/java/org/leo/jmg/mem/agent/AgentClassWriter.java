package org.leo.jmg.mem.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/** 使用目标容器 ClassLoader 计算转换后字节码的公共父类与 StackMapFrame。 */
public final class AgentClassWriter extends ClassWriter {
    private final ClassLoader targetLoader;

    public AgentClassWriter(ClassReader reader, int flags, ClassLoader targetLoader) {
        super(reader, flags);
        this.targetLoader = targetLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            Class first = Class.forName(type1.replace('/', '.'), false, targetLoader);
            Class second = Class.forName(type2.replace('/', '.'), false, targetLoader);
            if (first.isAssignableFrom(second)) return type1;
            if (second.isAssignableFrom(first)) return type2;
            if (first.isInterface() || second.isInterface()) return "java/lang/Object";
            do {
                first = first.getSuperclass();
            } while (first != null && !first.isAssignableFrom(second));
            return first == null ? "java/lang/Object" : first.getName().replace('.', '/');
        } catch (Throwable ignored) {
            return "java/lang/Object";
        }
    }
}
