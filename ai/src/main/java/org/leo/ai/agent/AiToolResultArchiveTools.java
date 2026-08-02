package org.leo.ai.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 模型读取被截断工具结果的分页工具。 */
@Component
@AiToolPolicy(
        kind = AiToolKind.CONTEXT,
        operation = AiToolOperation.READ_ONLY,
        parallelizable = true, business = false)
public class AiToolResultArchiveTools {

    private final AiToolResultArchive archive;

    public AiToolResultArchiveTools(AiToolResultArchive archive) {
        this.archive = archive;
    }

    @Tool(name = "get_tool_result_archive", value = "读取当前会话中被截断的大型工具结果。"
            + "传入 archiveId，offset 从 0 开始，limit 建议不超过 8000；"
            + "必须使用工具执行结果中返回的 archiveId，不要猜测。")
    public Map<String, Object> get(
            @ToolMemoryId String memoryId,
            @P(name = "archiveId") String archiveId,
            @P(name = "offset", defaultValue = "0") int offset,
            @P(name = "limit", defaultValue = "8000") int limit) {
        if (archiveId == null || archiveId.isBlank()) {
            throw AiToolException.modelCorrectable(
                    "ARCHIVE_ID_REQUIRED", "archiveId 不能为空。",
                    "请使用上一个工具结果中的 archiveId。");
        }
        AiToolResultArchive.ArchivePage page = archive.page(memoryId, archiveId, offset, limit);
        if (page == null) {
            throw AiToolException.modelCorrectable(
                    "ARCHIVE_NOT_FOUND", "归档不存在、已过期或不属于当前会话。",
                    "请重新执行产生大型结果的工具，或使用仍然有效的 archiveId。");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("archiveId", page.archiveId());
        result.put("toolName", page.toolName());
        result.put("content", page.content());
        result.put("offset", page.offset());
        result.put("endOffset", page.endOffset());
        result.put("totalChars", page.totalChars());
        result.put("hasMore", page.hasMore());
        if (page.hasMore()) result.put("nextOffset", page.endOffset());
        if (!page.metadata().isEmpty()) result.put("metadata", page.metadata());
        return result;
    }
}
