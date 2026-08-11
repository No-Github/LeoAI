package org.leo.jmg.util.javassist;

import javassist.ClassMap;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.NotFoundException;
import org.leo.jmg.ServletNamespace;

import java.util.Collection;

public class JavassistUtil {

    public static void addMethod(CtClass ctClass, String methodName, String methodBody) throws Exception {
        ctClass.defrost();
        try {
            // 已存在，修改
            CtMethod ctMethod = ctClass.getDeclaredMethod(methodName);
            ctMethod.setBody(methodBody);
        } catch (NotFoundException ignored) {
            // 不存在，直接添加
            CtMethod method = CtNewMethod.make(methodBody, ctClass);
            ctClass.addMethod(method);
        }
    }

    public static void replaceStaticField(CtClass ctClass,
                                          String fieldName,
                                          String fieldSource) throws Exception {
        try {
            ctClass.removeField(ctClass.getDeclaredField(fieldName));
        } catch (NotFoundException ignored) {
        }
        ctClass.addField(CtField.make(fieldSource, ctClass));
    }

    public static void replaceStaticFieldIfDeclared(CtClass ctClass,
                                                    String fieldName,
                                                    String fieldSource) throws Exception {
        try {
            ctClass.removeField(ctClass.getDeclaredField(fieldName));
            ctClass.addField(CtField.make(fieldSource, ctClass));
        } catch (NotFoundException ignored) {
        }
    }

    public static String escapeJavaString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * 将模板字节码中的 javax Servlet/WebSocket 类型引用整体映射到 Jakarta 命名空间。
     * Javassist 会同步更新方法描述符、字段描述符、异常表和栈映射中的类名。
     */
    public static void applyServletNamespace(CtClass ctClass, ServletNamespace namespace) {
        if (ctClass == null || namespace == null || namespace.resolve() != ServletNamespace.JAKARTA) {
            return;
        }
        ClassMap classMap = new ClassMap();
        @SuppressWarnings("unchecked")
        Collection<String> referencedClasses = ctClass.getRefClasses();
        for (String className : referencedClasses) {
            if (className.startsWith("javax.servlet.")) {
                classMap.put(className, "jakarta.servlet." + className.substring("javax.servlet.".length()));
            } else if (className.startsWith("javax.websocket.")) {
                classMap.put(className, "jakarta.websocket." + className.substring("javax.websocket.".length()));
            }
        }
        ctClass.replaceClassName(classMap);
    }
}
