package org.leo.jmg.mem.packer.jsp;

import org.leo.jmg.mem.packer.obfuscation.LiteralObfuscator;
import org.leo.jmg.mem.packer.obfuscation.NoiseObfuscator;
import org.leo.jmg.mem.packer.obfuscation.PayloadObfuscator;
import org.leo.jmg.mem.packer.obfuscation.PresentationObfuscator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSP 混淆步骤的单一事实来源：每个条目同时持有展示元数据、执行器和风险提示。
 */
public final class JspObfuscationStepCatalog {

    private static final Map<String, Definition> DEFINITIONS;

    static {
        Map<String, Definition> definitions = new LinkedHashMap<String, Definition>();

        add(definitions, descriptor(
                "XOR_PAYLOAD_ENCODE", "XOR Payload 编码",
                "对 base64 payload 随机单字节 XOR 扰动：解码→XOR→重编码，生成与原始 payload 完全不同的 base64 字符串，" +
                        "破坏 hash 指纹检测；注入随机命名的 helper 方法在运行时 XOR 还原，对调用方透明。" +
                        "建议在 Payload 分块之前执行，两者叠加效果更强",
                true, true, true,
                ids("PACK_PAYLOAD"),
                ids("CHUNK_PAYLOAD", "UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX")),
                PayloadObfuscator::xor, null);

        add(definitions, descriptor(
                "PACK_PAYLOAD", "Payload 双字符打包",
                "将 base64 payload 字符串相邻两个字符打包为一个 char（高低各 8 位），字符串长度减半，" +
                        "base64 字符集特征完全消失；注入随机命名 helper 在运行时还原，对 Base64.decode() 调用方透明。" +
                        "建议在 Payload 分块之前执行，两者叠加效果更强",
                true, true, true,
                ids("XOR_PAYLOAD_ENCODE"),
                ids("CHUNK_PAYLOAD", "UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX")),
                PayloadObfuscator::packTwoToOne, null);

        add(definitions, descriptor(
                "SPLIT_STRING_LITERALS", "字符串字面量拆分",
                "将敏感字符串在随机位置一分为二，如 \"defineClass\" → \"defin\"+\"eClass\"",
                true, true, true,
                ids("GHOST_BITS_ENCODE", "BYTE_ARRAY_ENCODE", "PACK_TWO_TO_ONE"),
                ids()),
                LiteralObfuscator::split, null);

        add(definitions, descriptor(
                "GHOST_BITS_ENCODE", "Ghost Bits 编码",
                "Cast Attack（Black Hat Asia 2026）：将敏感字符串替换为 helperName(\"汉字...\") 调用；" +
                        "汉字低字节 = 原始 ASCII，helper 方法名随机生成并注入 <%! %> 声明块；" +
                        "读代码时只见连续汉字，WAF 无特征可匹配，(byte)ch 截断在运行时完整还原。" +
                        "注意：CJK 字符壳已有 WAF 检测规则，免杀寿命有限，建议优先 BYTE_ARRAY_ENCODE 或 PACK_TWO_TO_ONE",
                true, true, true,
                ids("SPLIT_STRING_LITERALS", "BYTE_ARRAY_ENCODE", "PACK_TWO_TO_ONE"),
                ids()),
                LiteralObfuscator::ghostBits,
                "GHOST_BITS_ENCODE 的 CJK 字符壳已有对应 WAF 检测规则，免杀寿命有限，建议优先选用 BYTE_ARRAY_ENCODE 或 PACK_TWO_TO_ONE");

        add(definitions, descriptor(
                "BYTE_ARRAY_ENCODE", "字节数组编码",
                "将敏感字符串字面量替换为 new String(new byte[]{100,101,...}) 形式，" +
                        "无需注入 helper 方法；new String(new byte[]{}) 是 JDK 标准写法，在正常代码中大量出现，误报率高",
                true, true, true,
                ids("SPLIT_STRING_LITERALS", "GHOST_BITS_ENCODE", "PACK_TWO_TO_ONE"),
                ids()),
                LiteralObfuscator::byteArray, null);

        add(definitions, descriptor(
                "PACK_TWO_TO_ONE", "双字符打包编码",
                "将相邻两个 ASCII 字符打包进一个 char 的高低各 8 位，字符串长度减半；" +
                        "高低字节均来自真实源字符（无随机噪声），注入随机命名 helper 在运行时还原。" +
                        "仅处理全 ASCII 字面量，含非 ASCII 字符时自动跳过",
                true, true, true,
                ids("SPLIT_STRING_LITERALS", "GHOST_BITS_ENCODE", "BYTE_ARRAY_ENCODE"),
                ids()),
                LiteralObfuscator::packTwoToOne, null);

        add(definitions, descriptor(
                "CHUNK_PAYLOAD", "Payload 分块",
                "将 base64 payload 字符串随机切分为 16-40 字符的小块拼接，消除大段连续 base64 特征",
                true, true, true, ids(), ids()),
                PayloadObfuscator::chunk, null);

        add(definitions, descriptor(
                "IDENTIFIER_RENAME", "特征变量名重命名",
                "将 scriptlet/declaration 块内已知的 WebShell 特征变量名（classBytes、unsafe、clazz 等）替换为随机字段名，" +
                        "消除静态签名库和 YARA 规则对固定变量名的匹配；与 TemplateRenderer 的占位符随机化正交，作为补充层",
                true, true, true, ids(),
                ids("UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX")),
                PresentationObfuscator::renameIdentifiers, null);

        add(definitions, descriptor(
                "INJECT_SCRIPTLET_NOISE", "Scriptlet 内噪声注入",
                "在每个 scriptlet 块开头注入 1-2 条仅含常量表达式的 Java 声明；" +
                        "不读取系统属性、线程或类加载器状态，避免额外运行时行为",
                true, true, true, ids(),
                ids("UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX")),
                NoiseObfuscator::injectScriptletStatements, null);

        add(definitions, descriptor(
                "DEAD_BLOCK_INJECT", "死代码块注入",
                "在每个 scriptlet 块开头注入 1 条由 if(false) 包裹的 Java 代码块；" +
                        "保证不会实际触发类初始化、JNDI 查询或系统状态读取",
                true, true, true, ids(),
                ids("UNICODE_ENCODE_JSP", "UNICODE_ENCODE_JSPX")),
                NoiseObfuscator::injectDeadBlocks, null);

        add(definitions, descriptor(
                "INSERT_SCRIPT_NOISE", "噪声标签注入",
                "在 scriptlet 边界（%> 与 <%）之间随机插入 script/style/comment 噪声标签，打断 WAF 正则匹配",
                true, false, true, ids(), ids()),
                NoiseObfuscator::insertBoundaryTags, null);

        add(definitions, descriptor(
                "UNICODE_ENCODE_JSP", "Unicode 编码（JSP）",
                "将代码中字母数字字符随机转为 \\uXXXX 形式，随机混用 1-4 个 u 前缀变体，仅编码 scriptlet 内容。" +
                        "注意：多 u 转义是已知 WebShell 特征，部分 WAF 已专门识别，建议仅在必要时使用",
                true, false, true,
                ids("UNICODE_ENCODE_JSPX"), ids()),
                code -> JspUnicoder.encode(code, true),
                "UNICODE_ENCODE_JSP 的多 u 转义是已知 WebShell 特征，部分 WAF 已专门识别，建议仅在必要时使用");

        add(definitions, descriptor(
                "UNICODE_ENCODE_JSPX", "Unicode 编码（JSPX）",
                "同上，但跳过 JSPX XML 结构标签（jsp:root / jsp:declaration 等）。注意：同 JSP 模式，多 u 转义已被部分 WAF 识别",
                false, true, true,
                ids("UNICODE_ENCODE_JSP"), ids()),
                code -> JspUnicoder.encode(code, false),
                "UNICODE_ENCODE_JSPX 的多 u 转义是已知 WebShell 特征，部分 WAF 已专门识别，建议仅在必要时使用");

        add(definitions, descriptor(
                "WRAP_HTML_JS", "HTML 壳包裹",
                "在 JSP 外层套 HTML + jQuery ready 注释壳，使文件头看起来是普通 HTML，规避文件内容扫描；仅适用于内存马部署页",
                true, false, false, ids(), ids()),
                PresentationObfuscator::wrapWithHtml, null);

        add(definitions, descriptor(
                "NORMALIZE_WHITESPACE", "格式随机化",
                "随机化 scriptlet 块内的缩进风格（2/3/4空格或tab）与空行节奏，" +
                        "打破 LLM 生成代码的统计指纹（token 分布、缩进习惯等），使输出格式特征无规律可循；" +
                        "不改变代码语义，对 JSP 和 JSPX 均适用，建议放在 pipeline 最后执行",
                true, true, true, ids(), ids()),
                PresentationObfuscator::normalizeWhitespace, null);

        DEFINITIONS = Collections.unmodifiableMap(definitions);
    }

    private JspObfuscationStepCatalog() {
    }

    static boolean contains(String id) {
        return DEFINITIONS.containsKey(id);
    }

    static JspObfuscationStepDescriptor descriptor(String id) {
        Definition definition = DEFINITIONS.get(id);
        return definition == null ? null : definition.descriptor;
    }

    static JspObfuscationStep step(String id) {
        Definition definition = DEFINITIONS.get(id);
        return definition == null ? null : definition.step;
    }

    static String riskWarning(String id) {
        Definition definition = DEFINITIONS.get(id);
        return definition == null ? null : definition.riskWarning;
    }

    public static List<JspObfuscationStepDescriptor> getDescriptors() {
        List<JspObfuscationStepDescriptor> result =
                new ArrayList<JspObfuscationStepDescriptor>(DEFINITIONS.size());
        for (Definition definition : DEFINITIONS.values()) {
            result.add(definition.descriptor);
        }
        return result;
    }

    private static void add(Map<String, Definition> definitions,
                            JspObfuscationStepDescriptor descriptor,
                            JspObfuscationStep step,
                            String riskWarning) {
        Objects.requireNonNull(descriptor, "JSP 混淆步骤描述不能为空");
        Objects.requireNonNull(step, "JSP 混淆步骤执行器不能为空: " + descriptor.getId());
        Definition previous = definitions.put(descriptor.getId(),
                new Definition(descriptor, step, riskWarning));
        if (previous != null) {
            throw new IllegalStateException("重复的 JSP 混淆步骤: " + descriptor.getId());
        }
    }

    private static JspObfuscationStepDescriptor descriptor(
            String id, String nameZh, String description,
            boolean jspCompatible, boolean jspxCompatible, boolean webshellCompatible,
            String[] incompatibleWith, String[] mustPrecede) {
        return new JspObfuscationStepDescriptor(
                id, nameZh, description,
                jspCompatible, jspxCompatible, webshellCompatible,
                incompatibleWith, mustPrecede);
    }

    private static String[] ids(String... ids) {
        return ids;
    }

    private static final class Definition {
        private final JspObfuscationStepDescriptor descriptor;
        private final JspObfuscationStep step;
        private final String riskWarning;

        private Definition(JspObfuscationStepDescriptor descriptor,
                           JspObfuscationStep step,
                           String riskWarning) {
            this.descriptor = descriptor;
            this.step = step;
            this.riskWarning = riskWarning;
        }
    }
}
