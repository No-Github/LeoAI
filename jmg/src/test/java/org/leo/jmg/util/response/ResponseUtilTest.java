package org.leo.jmg.util.response;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;
import org.junit.jupiter.api.Test;
import org.leo.jmg.util.javassist.JavassistUtil;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ResponseUtilTest {

    @Test
    void listenerResponseBodiesCompileWithoutServletApiOnGeneratorClasspath() throws Exception {
        List<String> serverTypes = Arrays.asList(
                "Tomcat", "Resin", "Jetty", "Jetty5", "GlassFish",
                "WebSphere", "Undertow", "TongWeb", "Apusic");

        ClassPool pool = new ClassPool(null);
        pool.appendSystemPath();
        CtClass owner = pool.makeClass("test.ListenerResponseBodyFixture");
        try {
            owner.addMethod(CtNewMethod.make(
                    "public static Object getFieldValue(Object value, String name) "
                            + "throws Exception { return null; }",
                    owner));
            owner.addMethod(CtNewMethod.make(
                    "private Object getResponseFromRequest(Object request) "
                            + "throws Exception { return null; }",
                    owner));

            for (String serverType : serverTypes) {
                String body = ResponseUtil.getMethodBody(serverType);
                assertFalse(body.contains("javax.servlet"), serverType);
                assertFalse(body.contains("jakarta.servlet"), serverType);
                JavassistUtil.addMethod(owner, "getResponseFromRequest", body);
            }

            owner.toBytecode();
        } finally {
            owner.detach();
        }
    }
}
