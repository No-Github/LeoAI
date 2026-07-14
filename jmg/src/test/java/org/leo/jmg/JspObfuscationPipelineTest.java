package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;
import org.leo.jmg.mem.packer.jsp.JspDocument;
import org.leo.jmg.mem.packer.jsp.JspUnicoder;
import org.leo.jmg.mem.packer.Util;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.jsp.ClassLoaderJspPacker;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JspObfuscationPipelineTest {

    @Test
    void rejectsUnknownNullAndBlankStepIds() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(Arrays.asList("NOT_A_STEP")));
        assertTrue(unknown.getMessage().contains("NOT_A_STEP"));

        assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(Arrays.asList("CHUNK_PAYLOAD", null)));
        assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(Arrays.asList(" ")));
    }

    @Test
    void trimsKnownStepIdsBeforeBuildingPipeline() {
        JspObfuscationPipeline pipeline = JspObfuscationPipeline.fromStepIds(
                Arrays.asList("  NORMALIZE_WHITESPACE  "));

        assertEquals("plain text", pipeline.apply("plain text"));
    }

    @Test
    void descriptorConstraintSetsAreImmutable() {
        JspObfuscationPipeline.StepDescriptor descriptor = null;
        for (JspObfuscationPipeline.StepDescriptor candidate
                : JspObfuscationPipeline.getStepDescriptors()) {
            if (!candidate.getIncompatibleWith().isEmpty()) {
                descriptor = candidate;
                break;
            }
        }

        assertTrue(descriptor != null, "缺少带互斥约束的步骤描述");
        JspObfuscationPipeline.StepDescriptor selected = descriptor;
        assertThrows(UnsupportedOperationException.class,
                () -> selected.getIncompatibleWith().clear());
    }

    @Test
    void rejectsStepsThatDoNotMatchArtifactContext() {
        IllegalArgumentException jspx = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(
                        Arrays.asList("INSERT_SCRIPT_NOISE"),
                        JspObfuscationPipeline.PlanContext.webShell(
                                JspObfuscationPipeline.ArtifactFormat.JSPX)));
        assertTrue(jspx.getMessage().contains("不支持 JSPX"));

        IllegalArgumentException webShell = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(
                        Arrays.asList("WRAP_HTML_JS"),
                        JspObfuscationPipeline.PlanContext.webShell(
                                JspObfuscationPipeline.ArtifactFormat.JSP)));
        assertTrue(webShell.getMessage().contains("不适用于 WebShell"));
    }

    @Test
    void rejectsUnsupportedPackerStepsAndMutualExclusions() {
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(
                        Arrays.asList("NORMALIZE_WHITESPACE"),
                        JspObfuscationPipeline.PlanContext.packer(
                                JspObfuscationPipeline.ArtifactFormat.JSP,
                                Arrays.asList("CHUNK_PAYLOAD"))));
        assertTrue(unsupported.getMessage().contains("当前 Packer 不支持"));

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.compile(
                        Arrays.asList("XOR_PAYLOAD_ENCODE", "PACK_PAYLOAD"),
                        JspObfuscationPipeline.PlanContext.webShell(
                                JspObfuscationPipeline.ArtifactFormat.JSP)));
        assertTrue(conflict.getMessage().contains("步骤互斥"));
    }

    @Test
    void automaticallyOrdersDependenciesAndReportsDiagnostics() {
        JspObfuscationPipeline.CompiledPlan plan = JspObfuscationPipeline.compile(
                Arrays.asList("CHUNK_PAYLOAD", "XOR_PAYLOAD_ENCODE", "CHUNK_PAYLOAD"),
                JspObfuscationPipeline.PlanContext.webShell(
                        JspObfuscationPipeline.ArtifactFormat.JSP));

        assertEquals(Arrays.asList("XOR_PAYLOAD_ENCODE", "CHUNK_PAYLOAD"),
                plan.getEffectiveStepIds());
        assertEquals(2, plan.getWarnings().size());
    }

    @Test
    void identifierRenamePreservesStringsCommentsAndMethodNames() {
        String source = "<% String classBytes=\"classBytes\"; "
                + "// classBytes\nObject value=loader.loadClass(\"loadClass\"); %>";

        String transformed = Util.renameIdentifiers(source);

        assertTrue(!transformed.contains("String classBytes="));
        assertTrue(transformed.contains("\"classBytes\""));
        assertTrue(transformed.contains("// classBytes"));
        assertTrue(transformed.contains(".loadClass(\"loadClass\")"));
    }

    @Test
    void injectedNoiseDoesNotReadRuntimeState() {
        String source = "<% int original=1; %>";

        String noise = Util.injectScriptletNoise(source);
        assertTrue(!noise.contains("Runtime.getRuntime"));
        assertTrue(!noise.contains("System."));
        assertTrue(!noise.contains("Thread."));

        String deadBlock = Util.injectDeadBlocks(source);
        assertTrue(deadBlock.contains("if(false)"));
    }

    @Test
    void fixedSeedReproducesPipelineOutput() {
        String source = "<%@ page contentType=\"text/plain\" %>\n"
                + "<%! String classBytes=\"classBytes\"; %>\n"
                + "<% int original=1; %>";
        java.util.List<String> steps = Arrays.asList(
                "IDENTIFIER_RENAME", "INJECT_SCRIPTLET_NOISE", "NORMALIZE_WHITESPACE");

        JspObfuscationPipeline first = JspObfuscationPipeline.fromStepIds(
                steps, JspObfuscationPipeline.PlanContext.webShell(
                        JspObfuscationPipeline.ArtifactFormat.JSP, 123456L));
        JspObfuscationPipeline second = JspObfuscationPipeline.fromStepIds(
                steps, JspObfuscationPipeline.PlanContext.webShell(
                        JspObfuscationPipeline.ArtifactFormat.JSP, 123456L));
        JspObfuscationPipeline different = JspObfuscationPipeline.fromStepIds(
                steps, JspObfuscationPipeline.PlanContext.webShell(
                        JspObfuscationPipeline.ArtifactFormat.JSP, 654321L));

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
}
