package org.leo.core.util.javassist;


import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class JavassistDisguiseFactory {

    private static final AtomicLong CLASS_SEQUENCE = new AtomicLong();

    private JavassistDisguiseFactory() {
    }

    public static byte[] createDisguiseBytecode(String encodeBody, String decodeBody) throws Exception {

        ClassPool pool = ClassPool.getDefault();

        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);

        // encode 方法
        cc.addMethod(CtNewMethod.make(encodeBody, cc));

        // decode 方法
        cc.addMethod(CtNewMethod.make(decodeBody, cc));

        byte[] bytecode = cc.toBytecode();
        cc.detach();
        return bytecode;
    }
    public static Class<?> createDisguiseClass(String encodeBody, String decodeBody) throws Exception {

        ClassPool pool = ClassPool.getDefault();

        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);

        // encode 方法
        cc.addMethod(CtNewMethod.make(encodeBody, cc));

        // decode 方法
        cc.addMethod(CtNewMethod.make(decodeBody, cc));


        try {
            // Java 17+ 使用同包 lookup 定义类，避免依赖非法反射或 --add-opens JVM 参数。
            return cc.toClass(JavassistDisguiseFactory.class);
        } finally {
            cc.detach();
        }
    }
    public static boolean testDisguise(String encodeBody, String decodeBody) throws Exception {
        HashMap<String, Object> testHashMap = new HashMap<String, Object>();
        testHashMap.put("testString", "54ikun");
        ClassPool pool = ClassPool.getDefault();

        String tempClassName = buildTempClassName();
        CtClass cc = pool.makeClass(tempClassName);

        // encode 方法
        cc.addMethod(CtNewMethod.make(encodeBody, cc));

        // decode 方法
        cc.addMethod(CtNewMethod.make(decodeBody, cc));
        Class<?> tempClass;
        try {
            tempClass = cc.toClass(JavassistDisguiseFactory.class);
        } finally {
            cc.detach();
        }
        Object tempObject = tempClass.getDeclaredConstructor().newInstance();

        Method encode = tempClass.getMethod("encode", new Class[]{HashMap.class});
        Method decode = tempClass.getMethod("decode", new Class[]{byte[].class});

        encode.setAccessible(true);
        decode.setAccessible(true);
        byte[] encodeByte = (byte[]) encode.invoke(tempObject, new Object[]{testHashMap});
        @SuppressWarnings("unchecked")
        HashMap<String, Object> decodeHashMap = (HashMap<String, Object>) decode.invoke(tempObject, new Object[]{encodeByte});

        return testHashMap.equals(decodeHashMap);
    }


    private static String buildTempClassName() {
        return JavassistDisguiseFactory.class.getPackageName()
                + ".GeneratedDisguise_" + CLASS_SEQUENCE.incrementAndGet();
    }

}
