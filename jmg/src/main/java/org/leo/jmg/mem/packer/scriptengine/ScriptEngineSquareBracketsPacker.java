package org.leo.jmg.mem.packer.scriptengine;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerCapability;
import org.leo.jmg.mem.packer.PackerMeta;

@PackerMeta(
        name = "ScriptEngineSquareBrackets",
        group = "ScriptEngine",
        order = 2,
        requiredCapabilities = PackerCapability.JAVASCRIPT_ENGINE,
        requiredClasses = "javax.script.ScriptEngineManager"
)
public class ScriptEngineSquareBracketsPacker implements Packer {
    private final DefaultScriptEnginePacker delegate = new DefaultScriptEnginePacker();

    @Override
    public String pack(ClassPackerConfig config) {
        return toSquareBracketNotation(delegate.pack(config));
    }

    static String toSquareBracketNotation(String script) {
        StringBuilder result = new StringBuilder(script.length() + 128);
        char quote = 0;
        boolean escaped = false;

        for (int index = 0; index < script.length(); index++) {
            char current = script.charAt(index);
            if (quote != 0) {
                result.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }

            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                result.append(current);
                continue;
            }

            if (current == '.' && index + 1 < script.length()
                    && Character.isJavaIdentifierStart(script.charAt(index + 1))) {
                int end = index + 2;
                while (end < script.length()
                        && Character.isJavaIdentifierPart(script.charAt(end))) {
                    end++;
                }
                result.append("['")
                        .append(script, index + 1, end)
                        .append("']");
                index = end - 1;
                continue;
            }

            result.append(current);
        }
        return result.toString();
    }
}
