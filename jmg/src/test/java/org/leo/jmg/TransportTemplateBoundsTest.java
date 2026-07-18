package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.jsp.httpchunk.JspServer;
import org.leo.jmg.jsp.httpchunk.JspxServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTemplateBoundsTest {

    @Test
    void chunkedTemplatesBoundRequestsAndResponses() throws Exception {
        String jsp = new JspServer().wrap("org.example.Core", new byte[]{1, 2, 3}, 200);
        String jspx = new JspxServer().wrap("org.example.Core", new byte[]{1, 2, 3}, 200);

        assertTrue(jsp.contains("dataLen<0||dataLen>16777216"));
        assertTrue(jsp.contains("respData.length>16777216"));
        assertTrue(jsp.contains("readUnsignedByte()"));
        assertTrue(jsp.contains("readLong()"));
        assertTrue(jsp.contains("writeByte(responseType)"));
        assertTrue(jsp.contains("writeLong(transportId)"));
        assertTrue(jsp.contains("response.setStatus(200)"));
        assertFalse(jsp.contains("heartbeat"));
        assertTrue(jspx.contains("dataLen &lt; 0 || dataLen &gt; 16777216"));
        assertTrue(jspx.contains("respData.length &gt; 16777216"));
        assertTrue(jspx.contains("readUnsignedByte()"));
        assertTrue(jspx.contains("writeLong(transportId)"));
        assertFalse(jspx.contains("heartbeat"));
    }

    @Test
    void chunkedTemplatesRejectBodylessResponseStatuses() {
        assertThrows(IllegalArgumentException.class,
                () -> new JspServer().wrap("org.example.Core", new byte[]{1}, 204));
        assertThrows(IllegalArgumentException.class,
                () -> new JspxServer().wrap("org.example.Core", new byte[]{1}, 304));
    }

    @Test
    void packagedWebSocketTemplateContainsFragmentValidation() throws Exception {
        String resource = "shell-template/LeoWebSocketTpl.class";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertTrue(input != null, "WebSocket template resource should exist");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            String constants = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
            assertTrue(constants.contains("invalid frame metadata"));
            assertTrue(constants.contains("fragment sequence mismatch"));
            assertTrue(constants.contains("response exceeds message limit"));
        }
    }
}
