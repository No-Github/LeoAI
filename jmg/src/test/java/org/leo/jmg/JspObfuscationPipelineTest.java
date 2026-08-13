package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;
import org.leo.jmg.mem.packer.jsp.JspDocument;
import org.leo.jmg.mem.packer.jsp.JspUnicoder;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.jsp.ClassLoaderJspPacker;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlan;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlanContext;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPlanner;
import org.leo.jmg.mem.packer.jsp.JspObfuscationStepCatalog;
import org.leo.jmg.mem.packer.jsp.JspObfuscationStepDescriptor;
import org.leo.jmg.mem.packer.obfuscation.NoiseObfuscator;
import org.leo.jmg.mem.packer.obfuscation.PresentationObfuscator;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JspObfuscationPipelineTest {

    @Test
    void catalogDescriptorsHaveUniqueExecutableDefinitions() {
        Set<String> ids = new HashSet<String>();
        for (JspObfuscationStepDescriptor descriptor
                : JspObfuscationStepCatalog.getDescriptors()) {
            assertTrue(ids.add(descriptor.getId()), "重复步骤: " + descriptor.getId());
            JspObfuscationPlanContext.Format format = descriptor.isJspCompatible()
                    ? JspObfuscationPlanContext.Format.JSP
                    : JspObfuscationPlanContext.Format.JSPX;
            JspObfuscationPipeline pipeline = JspObfuscationPlanner.compile(
                    Arrays.asList(descriptor.getId()),
                    JspObfuscationPlanContext.packer(
                            format, Arrays.asList(descriptor.getId()), 123L))
                    .getPipeline();
            assertTrue(pipeline.apply("plain text") != null);
        }
        assertTrue(!ids.isEmpty());
    }

    @Test
    void rejectsUnknownNullAndBlankStepIds() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList("NOT_A_STEP"), webShellContext()));
        assertTrue(unknown.getMessage().contains("NOT_A_STEP"));

        assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList("CHUNK_PAYLOAD", null), webShellContext()));
        assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList(" "), webShellContext()));
    }

    @Test
    void rejectsStepsThatDoNotMatchArtifactContext() {
        IllegalArgumentException jspx = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList("INSERT_SCRIPT_NOISE"),
                        JspObfuscationPlanContext.webShell(
                                JspObfuscationPlanContext.Format.JSPX)));
        assertTrue(jspx.getMessage().contains("不支持 JSPX"));

        IllegalArgumentException webShell = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList("WRAP_HTML_JS"),
                        JspObfuscationPlanContext.webShell(
                                JspObfuscationPlanContext.Format.JSP)));
        assertTrue(webShell.getMessage().contains("不适用于 WebShell"));
    }

    @Test
    void rejectsUnsupportedPackerStepsAndMutualExclusions() {
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList("NORMALIZE_WHITESPACE"),
                        JspObfuscationPlanContext.packer(
                                JspObfuscationPlanContext.Format.JSP,
                                Arrays.asList("CHUNK_PAYLOAD"))));
        assertTrue(unsupported.getMessage().contains("当前 Packer 不支持"));

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPlanner.compile(
                        Arrays.asList("XOR_PAYLOAD_ENCODE", "PACK_PAYLOAD"),
                        JspObfuscationPlanContext.webShell(
                                JspObfuscationPlanContext.Format.JSP)));
        assertTrue(conflict.getMessage().contains("步骤互斥"));
    }

    @Test
    void automaticallyOrdersDependenciesAndReportsDiagnostics() {
        JspObfuscationPlan plan = JspObfuscationPlanner.compile(
                Arrays.asList("CHUNK_PAYLOAD", "XOR_PAYLOAD_ENCODE", "CHUNK_PAYLOAD"),
                JspObfuscationPlanContext.webShell(
                        JspObfuscationPlanContext.Format.JSP));

        assertEquals(Arrays.asList("XOR_PAYLOAD_ENCODE", "CHUNK_PAYLOAD"),
                plan.getEffectiveStepIds());
        assertEquals(2, plan.getWarnings().size());
    }

    @Test
    void identifierRenamePreservesStringsCommentsAndMethodNames() {
        String source = "<% String classBytes=\"classBytes\"; "
                + "// classBytes\nObject value=loader.loadClass(\"loadClass\"); %>";

        String transformed = PresentationObfuscator.renameIdentifiers(source);

        assertTrue(!transformed.contains("String classBytes="));
        assertTrue(transformed.contains("\"classBytes\""));
        assertTrue(transformed.contains("// classBytes"));
        assertTrue(transformed.contains(".loadClass(\"loadClass\")"));
    }

    @Test
    void injectedNoiseDoesNotReadRuntimeState() {
        String source = "<% int original=1; %>";

        String noise = NoiseObfuscator.injectScriptletStatements(source);
        assertTrue(!noise.contains("Runtime.getRuntime"));
        assertTrue(!noise.contains("System."));
        assertTrue(!noise.contains("Thread."));

        String deadBlock = NoiseObfuscator.injectDeadBlocks(source);
        assertTrue(deadBlock.contains("if(false)"));
    }

    @Test
    void fixedSeedReproducesPipelineOutput() {
        String source = "<%@ page contentType=\"text/plain\" %>\n"
                + "<%! String classBytes=\"classBytes\"; %>\n"
                + "<% int original=1; %>";
        java.util.List<String> steps = Arrays.asList(
                "IDENTIFIER_RENAME", "INJECT_SCRIPTLET_NOISE", "NORMALIZE_WHITESPACE");

        JspObfuscationPipeline first = JspObfuscationPlanner.compile(
                steps, JspObfuscationPlanContext.webShell(
                        JspObfuscationPlanContext.Format.JSP, 123456L)).getPipeline();
        JspObfuscationPipeline second = JspObfuscationPlanner.compile(
                steps, JspObfuscationPlanContext.webShell(
                        JspObfuscationPlanContext.Format.JSP, 123456L)).getPipeline();
        JspObfuscationPipeline different = JspObfuscationPlanner.compile(
                steps, JspObfuscationPlanContext.webShell(
                        JspObfuscationPlanContext.Format.JSP, 654321L)).getPipeline();

        assertEquals(first.apply(source), second.apply(source));
        assertTrue(!first.apply(source).equals(different.apply(source)));
    }

    @Test
    void documentSegmentsJspAndJspxWithoutTouchingNonScriptContent() {
        String jsp = "head<%@ page contentType=\"text/plain\" %>"
                + "<%!int declaration=1;%><%int script=2;%><%=script%><%-- note --%>tail";
        JspDocument jspDocument = JspDocument.parse(jsp);
        String transformedJsp = jspDocument.transformScriptlets(content -> "MARK" + content).render();
        assertTrue(transformedJsp.contains("<%MARKint script=2;%>"));
        assertTrue(transformedJsp.contains("<%!int declaration=1;%>"));
        assertTrue(transformedJsp.contains("<%=script%>"));
        assertTrue(transformedJsp.contains("<%-- note --%>"));

        String jspx = "<jsp:root><jsp:declaration><![CDATA[int d=1;]]></jsp:declaration>"
                + "<jsp:scriptlet><![CDATA[int s=2;]]></jsp:scriptlet></jsp:root>";
        String transformedJspx = JspDocument.parse(jspx)
                .transformScriptlets(content -> "MARK" + content).render();
        assertTrue(transformedJspx.contains("<![CDATA[MARKint s=2;]]>"));
        assertTrue(transformedJspx.contains("<![CDATA[int d=1;]]>"));
    }

    @Test
    void documentRejectsUnclosedSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> JspDocument.parse("<% int value=1;"));
        assertThrows(IllegalArgumentException.class,
                () -> JspDocument.parse("<jsp:root><jsp:scriptlet>broken</jsp:root>"));
    }

    @Test
    void fixedSeedAlsoReproducesTemplateRenderingAndPackerOutput() {
        ClassPackerConfig first = packerConfig(98765L);
        ClassPackerConfig second = packerConfig(98765L);
        ClassPackerConfig different = packerConfig(56789L);
        ClassLoaderJspPacker packer = new ClassLoaderJspPacker();

        String firstOutput = packer.pack(first);
        assertEquals(firstOutput, packer.pack(second));
        assertTrue(!firstOutput.equals(packer.pack(different)));
    }

    @Test
    void unicodeEncodingOnlyTouchesJavaSegments() {
        String source = "plain-text<%@ page contentType=\"text/plain\" %>"
                + "<%int value=1;%><%=value%>tail-text";

        String encoded = JspUnicoder.encode(source, true);

        assertTrue(encoded.contains("plain-text"));
        assertTrue(encoded.contains("tail-text"));
        assertTrue(encoded.contains("<%@ page contentType=\"text/plain\" %>"));
        assertTrue(!encoded.contains("<%int value=1;%>"));
        assertTrue(encoded.contains("<%="));
    }

    @Test
    void documentCreatesDeclarationBlocksForJspAndJspx() {
        String jsp = JspDocument.parse("header<%int value=1;%>")
                .appendDeclaration("private int helper(){return 1;}").render();
        assertTrue(jsp.contains("<%!\nprivate int helper(){return 1;}\n%>"));
        assertTrue(jsp.indexOf("<%!") < jsp.indexOf("<%int value"));

        String jspx = JspDocument.parse(
                "<jsp:root><jsp:scriptlet><![CDATA[int value=1;]]></jsp:scriptlet></jsp:root>")
                .appendDeclaration("private int helper(){return 1;}").render();
        assertTrue(jspx.contains("<jsp:declaration><![CDATA["));
        assertTrue(jspx.indexOf("<jsp:declaration") < jspx.indexOf("<jsp:scriptlet"));
    }

    private ClassPackerConfig packerConfig(long seed) {
        ClassPackerConfig config = new ClassPackerConfig();
        config.setClassName("example.Generated");
        config.setClassBytes(new byte[]{1, 2, 3});
        config.setClassBytesBase64Str("AQID");
        config.setObfuscationSeed(seed);
        return config;
    }

    private JspObfuscationPlanContext webShellContext() {
        return JspObfuscationPlanContext.webShell(
                JspObfuscationPlanContext.Format.JSP, 123L);
    }
}
