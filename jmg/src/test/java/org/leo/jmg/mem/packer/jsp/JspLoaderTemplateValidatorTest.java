package org.leo.jmg.mem.packer.jsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JspLoaderTemplateValidatorTest {

    private static final String VALID_TEMPLATE =
            "<% String {{VAR:data}} = \"{{base64Str}}\"; "
                    + "java.lang.reflect.Method {{VAR:define}} = ClassLoader.class.getDeclaredMethod("
                    + "\"defineClass\", byte[].class, int.class, int.class); "
                    + "((Class) {{VAR:define}}.invoke(Thread.currentThread().getContextClassLoader(), "
                    + "new byte[0], 0, 0)).newInstance(); %>";

    @Test
    void acceptsRequiredLoaderStructure() {
        assertDoesNotThrow(() -> JspLoaderTemplateValidator.validate(VALID_TEMPLATE));
    }

    @Test
    void rejectsDuplicatePayloadUnknownPlaceholderAndDangerousOperations() {
        assertThrows(IllegalArgumentException.class,
                () -> JspLoaderTemplateValidator.validate(
                        VALID_TEMPLATE.replace("{{base64Str}}", "{{base64Str}}{{base64Str}}")));
        assertThrows(IllegalArgumentException.class,
                () -> JspLoaderTemplateValidator.validate(
                        VALID_TEMPLATE.replace("{{VAR:data}}", "{{UNKNOWN}}")));
        assertThrows(IllegalArgumentException.class,
                () -> JspLoaderTemplateValidator.validate(
                        VALID_TEMPLATE.replace("<%", "<% Runtime.getRuntime().exec(\"id\");")));
    }
}
