package org.leo.jmg.mem.packer;

import java.lang.annotation.*;

/**
 * Packer 元数据注解，用于自注册机制。
 * <p>
 * 每个 {@link Packer} 实现类通过该注解声明自身的标识名、所属分组及排序权重，
 * 由 {@link PackerRegistry} 在启动时通过类路径扫描自动发现并注册。
 * <p>
 * 新增 Packer 只需：
 * <ol>
 *   <li>创建实现类并加上 {@code @PackerMeta} 注解</li>
 *   <li>{@link PackerScanner} 会在启动时自动扫描并完成注册</li>
 * </ol>
 *
 * @author LeoSpring
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PackerMeta {

    /**
     * 唯一标识名，与 {@link PackerRegistry} 中注册的名称一致（忽略大小写）。
     * 用于 API 请求中的 packerType 匹配。
     */
    String name();

    /**
     * 所属分组名称（如 "Base64"、"SpEL"、"Groovy"）。
     * 空字符串表示无分组。
     */
    String group() default "";

    /**
     * 分组内排序权重，越小越靠前。
     * 默认 100。
     */
    int order() default 100;

    /**
     * 是否要求生成的注入器继承 AbstractTranslet。
     * 对应原 {@code requiresAbstractTranslet} 逻辑。
     */
    boolean requiresAbstractTranslet() default false;

    /**
     * 生成结果所需的最低目标 JDK 主版本。默认 6，表示实现应优先使用 JDK 6
     * 可用的语法/API；高版本专用 Packer 必须显式声明。
     */
    int minTargetJava() default 6;

    /**
     * 该 Packer 直接依赖的其他 Packer 名称。注册中心会递归合并依赖项的能力要求。
     */
    String[] dependencies() default {};

    /**
     * 生成结果在目标运行环境中需要具备的能力。
     */
    PackerCapability[] requiredCapabilities() default {};

    /**
     * 目标环境中必须存在的类名，仅作为能力提示，不在生成器 JVM 中探测。
     */
    String[] requiredClasses() default {};

    /**
     * 该 Packer 支持包装的传输协议。
     * <p>
     * Packer 默认只处理生成后的 Injector 字节码，与传输层正交，因此默认支持全部
     * 内存构建协议；只有确有载体限制的实现才需要收窄该列表。
     */
    String[] supportedProtocols() default {"http", "httpchunk", "websocket"};

    /**
     * 该 Packer 支持的 JSP/JSPX 混淆步骤 ID 列表，顺序即推荐执行顺序。
     * <p>
     * 步骤 ID 与 {@link org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline} 中的常量名对应：
     * {@code SPLIT_STRING_LITERALS}、{@code CHUNK_PAYLOAD}、{@code GHOST_BITS_ENCODE}、
     * {@code INJECT_SCRIPTLET_NOISE}、{@code INSERT_SCRIPT_NOISE}、
     * {@code UNICODE_ENCODE_JSP}、{@code UNICODE_ENCODE_JSPX}、{@code WRAP_HTML_JS}。
     * <p>
     * 变量名/类名随机化由 {@link org.leo.jmg.mem.packer.TemplateRenderer} 在渲染阶段自动完成，
     * 无需在此列表中声明。
     * <p>
     * 空数组（默认）表示该 Packer 不支持混淆层配置。
     */
    String[] obfuscationSteps() default {};
}
