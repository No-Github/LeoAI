package org.leo.core.util.javassist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavassistDisguiseFactoryTest {

    @Test
    void definesAndRunsGeneratedClassOnJava17WithoutModuleOpens() throws Exception {
        String encode = "public byte[] encode(java.util.HashMap params) { "
                + "return params.get(\"value\").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); }";
        String decode = "public java.util.HashMap decode(byte[] data) { "
                + "java.util.HashMap result = new java.util.HashMap(); "
                + "result.put(\"value\", new String(data, java.nio.charset.StandardCharsets.UTF_8)); "
                + "return result; }";

        assertTrue(JavassistDisguiseFactory.testDisguise(
                encode.replace("\"value\"", "\"testString\""),
                decode.replace("\"value\"", "\"testString\"")));
    }
}
