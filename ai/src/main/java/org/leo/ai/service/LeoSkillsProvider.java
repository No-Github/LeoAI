package org.leo.ai.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

/**
 * 为 system prompt 生成轻量 Skill 索引。
 *
 * <p>索引只使用 {@link SkillRegistryService} 已完成 catalog 级校验的元数据，
 * 不预加载 SKILL.md 正文和资源。完整指令由 {@code activate_skill} 按需读取，
 * 避免 catalog 增长后把所有 Skill 内容塞入内存或让单个损坏目录影响整个 scope。
 */
@Component
public class LeoSkillsProvider {

    private final SkillRegistryService skillRegistry;

    private volatile String puppetNodeIndex;
    private volatile String platformIndex;

    public LeoSkillsProvider(SkillRegistryService skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 返回指定 scope 下已发布、已启用且校验通过的 Agent Skills XML 索引。 */
    public String getFormattedSkills(String scope) {
        SkillRegistryService.validateScope(scope);
        if (SkillRegistryService.SCOPE_PUPPET_NODE.equals(scope)) {
            String index = puppetNodeIndex;
            if (index == null) {
                synchronized (this) {
                    if (puppetNodeIndex == null) puppetNodeIndex = buildIndex(scope);
                    index = puppetNodeIndex;
                }
            }
            return index;
        }

        String index = platformIndex;
        if (index == null) {
            synchronized (this) {
                if (platformIndex == null) platformIndex = buildIndex(scope);
                index = platformIndex;
            }
        }
        return index;
    }

    /** 按当前运行时权限生成索引；带过滤器的结果不进入全局缓存。 */
    public String getFormattedSkills(String scope, Predicate<SkillMeta> filter) {
        SkillRegistryService.validateScope(scope);
        return buildIndex(scope, filter != null ? filter : skill -> true);
    }

    /** 管理写操作后清空索引缓存；Registry 缓存由调用方同时失效。 */
    public synchronized void invalidate() {
        puppetNodeIndex = null;
        platformIndex = null;
    }

    private String buildIndex(String scope) {
        return buildIndex(scope, skill -> true);
    }

    private String buildIndex(String scope, Predicate<SkillMeta> filter) {
        List<SkillMeta> skills = skillRegistry.listSkills(scope).stream()
                .filter(filter)
                .toList();
        if (skills.isEmpty()) return "";

        StringBuilder xml = new StringBuilder("<available_skills>\n");
        for (SkillMeta skill : skills) {
            xml.append("<skill>\n")
                    .append("<name>").append(escapeXml(skill.getName())).append("</name>\n")
                    .append("<description>").append(escapeXml(skill.getDescription()))
                    .append("</description>\n")
                    .append("</skill>\n");
        }
        return xml.append("</available_skills>").toString();
    }

    private static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
