package org.leo.ai.tools.platform;

import org.leo.core.entity.Disguise;
import org.leo.service.disguise.DisguiseService;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolAccess;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component()
@AiToolAccess(AiToolAccess.Level.ADMIN)
public class DisguiseTools {

    private final DisguiseService disguiseService;

    public DisguiseTools(DisguiseService disguiseService) {
        this.disguiseService = disguiseService;
    }

    @Tool("获取当前平台所有 Disguise。")
    public Map<String, Object> getDisguises() throws Exception {
        HashMap<String, Object> result = successResult("fetched");
        result.put("data", disguiseService.getDisguises());
        return result;
    }

    @Tool("根据 disguiseId 获取 Disguise 详情。")
    public Map<String, Object> getDisguiseById(String disguiseId) throws Exception {
        HashMap<String, Object> result = successResult("fetched");
        result.put("data", disguiseService.getDisguiseById(disguiseId));
        return result;
    }

    @Tool("测试 encodeBody 和 decodeBody 是否能正确互逆。测试会传入完整 HashMap（包含 testString=54ikun），decode(encode(map)) 必须返回完全相等的 HashMap，不能只处理 data 单字段。")
    public Map<String, Object> testDisguise(String encodeBody, String decodeBody) throws Exception {
        disguiseService.testDisguise(encodeBody, decodeBody);
        HashMap<String, Object> result = successResult("passed");
        result.put("message", "测试通过：encode和decode方法可以正确互逆");
        return result;
    }

    @Tool("创建并保存新的 Disguise。headersJson 必须是 JSON 字符串；未传 disguiseId 时会自动生成。保存前会校验 encode/decode 对完整 HashMap 可互逆。生成过程中的候选方案不得保存；只能在最终 Disguise 确定并通过测试后调用一次。")
    public Map<String, Object> addDisguise(String userId, String disguiseName, String encodeBody,
                                           String decodeBody, String headersJson, String version,
                                           String description, String remark, String disguiseId) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("disguiseName", requireNonBlank(disguiseName, "disguiseName不能为空"));
        params.put("encodeBody", requireNonBlank(encodeBody, "encodeBody不能为空"));
        params.put("decodeBody", requireNonBlank(decodeBody, "decodeBody不能为空"));
        params.put("headers", requireNonBlank(headersJson, "headersJson不能为空"));
        putIfNotBlank(params, "version", version);
        putIfNotBlank(params, "description", description);
        putIfNotBlank(params, "remark", remark);
        putIfNotBlank(params, "disguiseId", disguiseId);

        disguiseService.addDisguise(params, requireNonBlank(userId, "userId不能为空"));
        String resolvedDisguiseId = isBlank(disguiseId) ? buildGeneratedDisguiseId(disguiseName, version) : disguiseId.trim();
        return buildResult("created", resolvedDisguiseId, disguiseName);
    }

    @Tool("更新已有 Disguise。disguiseId 必填，其余字段按需更新。headersJson 必须是 JSON 字符串。保存前会校验 encode/decode 对完整 HashMap 可互逆。生成过程中的候选方案不得保存；只能在最终 Disguise 确定并通过测试后调用一次。")
    public Map<String, Object> updateDisguise(String disguiseId, String disguiseName,
                                              String encodeBody, String decodeBody, String headersJson,
                                              String version, String description, String remark) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("disguiseId", requireNonBlank(disguiseId, "disguiseId不能为空"));
        putIfNotBlank(params, "disguiseName", disguiseName);
        putIfNotBlank(params, "encodeBody", encodeBody);
        putIfNotBlank(params, "decodeBody", decodeBody);
        putIfNotBlank(params, "headers", headersJson);
        putIfNotBlank(params, "version", version);
        putIfNotBlank(params, "description", description);
        putIfNotBlank(params, "remark", remark);

        disguiseService.updateDisguise(params);
        Disguise updated = disguiseService.getDisguiseById(disguiseId);
        return buildResult("updated", updated.getDisguiseId(), updated.getDisguiseName());
    }

    @Tool("删除指定 Disguise。")
    public Map<String, Object> deleteDisguise(String disguiseId) throws Exception {
        Disguise disguise = disguiseService.getDisguiseById(requireNonBlank(disguiseId, "disguiseId不能为空"));
        disguiseService.deleteDisguise(disguise.getDisguiseId());
        return buildResult("deleted", disguise.getDisguiseId(), disguise.getDisguiseName());
    }

    private Map<String, Object> buildResult(String status, String disguiseId, String disguiseName) {
        HashMap<String, Object> result = successResult(status);
        result.put("disguiseId", disguiseId);
        result.put("disguiseName", disguiseName);
        return result;
    }

    private HashMap<String, Object> successResult(String status) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("success", true);
        return result;
    }

    private void putIfNotBlank(HashMap<String, Object> params, String key, String value) {
        if (!isBlank(value)) {
            params.put(key, value.trim());
        }
    }

    private String requireNonBlank(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String buildGeneratedDisguiseId(String disguiseName, String version) {
        String safeName = disguiseName == null ? "" : disguiseName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (isBlank(safeName)) {
            safeName = "Disguise_" + System.currentTimeMillis();
        }
        String safeVersion = isBlank(version) ? "1.0.0" : version.trim();
        return safeName + "_" + safeVersion;
    }
}
