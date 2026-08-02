package org.leo.jmg.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebShellWrapperContractTest {

    @Test
    void acceptsBaselineForBothContainerFormats() {
        WebShellWrapperContract jsp = WebShellWrapperContract.create("jsp", "http");
        WebShellWrapperContract jspx = WebShellWrapperContract.create("jspx", "httpchunk");

        assertDoesNotThrow(() -> jsp.validate(jsp.getBaselineTemplate()));
        assertDoesNotThrow(() -> jspx.validate(jspx.getBaselineTemplate()));
    }

    @Test
    void rejectsMissingDuplicateUnknownAndReorderedPhases() {
        WebShellWrapperContract contract = WebShellWrapperContract.create("jsp", "http");
        String baseline = contract.getBaselineTemplate();

        assertThrows(IllegalArgumentException.class,
                () -> contract.validate(baseline.replace(WebShellWrapperContract.LOAD_CORE, "")));
        assertThrows(IllegalArgumentException.class,
                () -> contract.validate(baseline.replace(
                        WebShellWrapperContract.LOAD_CORE,
                        WebShellWrapperContract.LOAD_CORE + "\n" + WebShellWrapperContract.LOAD_CORE)));
        assertThrows(IllegalArgumentException.class,
                () -> contract.validate(baseline.replace(
                        WebShellWrapperContract.LOAD_CORE,
                        WebShellWrapperContract.LOAD_CORE + "\n    {{UNKNOWN_PHASE}}")));

        String reordered = baseline
                .replace(WebShellWrapperContract.LOAD_CORE, "{{TEMP_PHASE}}")
                .replace(WebShellWrapperContract.READ_REQUEST, WebShellWrapperContract.LOAD_CORE)
                .replace("{{TEMP_PHASE}}", WebShellWrapperContract.READ_REQUEST);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> contract.validate(reordered));
        assertTrue(error.getMessage().contains("顺序"));
    }

    @Test
    void rejectsNonStandaloneAndDangerousWrapperCode() {
        WebShellWrapperContract contract = WebShellWrapperContract.create("jsp", "http");
        String baseline = contract.getBaselineTemplate();

        assertThrows(IllegalArgumentException.class,
                () -> contract.validate(baseline.replace(
                        WebShellWrapperContract.DECLARE_STATE,
                        "if (true) " + WebShellWrapperContract.DECLARE_STATE)));
        assertThrows(IllegalArgumentException.class,
                () -> contract.validate(baseline.replace("<%\n", "<%\nRuntime.getRuntime().exec(\"id\");\n")));
        assertThrows(IllegalArgumentException.class,
                () -> contract.validate("<%@ include file=\"remote.jsp\" %>\n" + baseline));
        assertThrows(IllegalArgumentException.class,
                () -> contract.validate(baseline.replace(
                        "contentType=\"application/octet-stream\"",
                        "contentType=\"application/octet-stream\" import=\"java.io.File\"")));
    }

    @Test
    void requiresJspxPhasesInsideTheScriptletElement() {
        WebShellWrapperContract contract = WebShellWrapperContract.create("jspx", "http");
        String invalid = "<jsp:root version=\"2.0\" xmlns:jsp=\"http://java.sun.com/JSP/Page\">\n"
                + "<![CDATA[\n"
                + String.join("\n", contract.getRequiredPhases())
                + "\n]]>\n<jsp:scriptlet><![CDATA[int value = 1;]]></jsp:scriptlet>\n"
                + "</jsp:root>";

        assertThrows(IllegalArgumentException.class, () -> contract.validate(invalid));
    }

    @Test
    void rejectsWebsocketWrapperContract() {
        assertThrows(IllegalArgumentException.class,
                () -> WebShellWrapperContract.create("JSP", "websocket"));
    }
}
