package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.TemplateRenderer;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    @Test
    void rendersGlobalAndStructuralPlaceholdersConsistently() {
        ClassPackerConfig config = config("demo.Example", "YWJj");
        String template = "class {{CLS:Loader}} { String {{VAR:value}} = \"{{className}}:{{base64Str}}\"; "
                + "String copy = {{VAR:value}}; {{CLS:Loader}} self; }";

        String rendered = TemplateRenderer.render(template, config);

        assertFalse(rendered.contains("{{"));
        assertTrue(rendered.contains("\"demo.Example:YWJj\""));
        Matcher value = Pattern.compile("String ([A-Za-z_$][A-Za-z0-9_$]*) =").matcher(rendered);
        assertTrue(value.find());
        assertTrue(rendered.contains("String copy = " + value.group(1) + ";"));
    }

    @Test
    void rejectsMissingRequiredGlobalValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("{{className}}", config(null, "YWJj")));
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("{{base64Str}}", config("demo.Example", null)));
    }

    @Test
    void rejectsUnknownOrMalformedPlaceholders() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("value={{unknown}}", config("demo.Example", "YWJj")));
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("value={{VAR:bad-name}}", config("demo.Example", "YWJj")));

        assertTrue(unknown.getMessage().contains("{{unknown}}"));
        assertTrue(malformed.getMessage().contains("{{VAR:bad-name}}"));
    }

    @Test
    void validatesAndRendersExtraPlaceholders() {
        String rendered = TemplateRenderer.render("value={{number}}", config("demo.Example", "YWJj"),
                Collections.singletonMap("number", "42"));
        assertEquals("value=42", rendered);

        assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("{{bad-key}}", config("demo.Example", "YWJj"),
                        Collections.singletonMap("bad-key", "42")));
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRenderer.render("{{number}}", config("demo.Example", "YWJj"),
                        Collections.singletonMap("number", null)));
    }

    private ClassPackerConfig config(String className, String base64) {
        ClassPackerConfig config = new ClassPackerConfig();
        config.setClassName(className);
        config.setClassBytesBase64Str(base64);
        return config;
    }
}
