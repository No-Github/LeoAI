package org.leo.web.controller.platform.task;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AsyncShellTask;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.ApiResponse;
import org.leo.service.DownloadEngineService;
import org.leo.service.UploadEngineService;
import org.leo.service.sql.SqlExportService;
import org.leo.web.exception.ApiException;
import org.leo.web.service.AsyncShellService;
import org.leo.web.service.PlatformAiThreadService;
import org.leo.web.service.PuppetNodeAiThreadService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨会话全局任务中心。
 *
 * <p>聚合节点后台 Shell、文件传输、数据库导出以及平台/节点 AI 运行状态。
 * 扫描等仅存在于浏览器内的临时任务由前端 TaskEngine 合并展示。</p>
 */
@RestController
@RequestMapping("/platform/task-center")
public class GlobalTaskCenterController {

    private final AsyncShellService asyncShellService;
    private final DownloadEngineService downloadEngineService;
    private final UploadEngineService uploadEngineService;
    private final SqlExportService sqlExportService;
    private final PuppetNodeAiThreadService puppetNodeAiThreadService;
    private final PlatformAiThreadService platformAiThreadService;

    public GlobalTaskCenterController(AsyncShellService asyncShellService,
                                      DownloadEngineService downloadEngineService,
                                      UploadEngineService uploadEngineService,
                                      SqlExportService sqlExportService,
                                      PuppetNodeAiThreadService puppetNodeAiThreadService,
                                      PlatformAiThreadService platformAiThreadService) {
        this.asyncShellService = asyncShellService;
        this.downloadEngineService = downloadEngineService;
        this.uploadEngineService = uploadEngineService;
        this.sqlExportService = sqlExportService;
        this.puppetNodeAiThreadService = puppetNodeAiThreadService;
        this.platformAiThreadService = platformAiThreadService;
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot(HttpServletRequest request) {
        User user = requireUser(request);
        List<Map<String, Object>> tasks = new ArrayList<>();

        for (Map.Entry<String, PuppetNodeSession> entry
                : PuppetNodeSessionContainer.getAllSession().entrySet()) {
            PuppetNodeSession session = entry.getValue();
            if (session == null || !ControllerUtil.canAccessSession(session, user)) {
                continue;
            }

            String sessionId = entry.getKey();
            String taskOwnerId = taskOwnerId(session, user);
            Puppet puppet = session.getPuppetNode() != null ? session.getPuppetNode().getPuppet() : null;
            String puppetName = puppet != null ? puppet.getPuppetName() : sessionId;
            String connLink = puppet != null ? puppet.getConnLink() : null;

            appendShellTasks(tasks, session, sessionId, puppetName, connLink);
            appendPuppetAiTasks(tasks, session, sessionId, puppetName, connLink);
            appendServiceTasks(tasks, "download", sessionId, puppetName, connLink,
                    safeTasks(() -> downloadEngineService.listBySessionId(taskOwnerId, sessionId)));
            appendServiceTasks(tasks, "upload", sessionId, puppetName, connLink,
                    safeTasks(() -> uploadEngineService.listBySessionId(taskOwnerId, sessionId)));
            appendServiceTasks(tasks, "db_export", sessionId, puppetName, connLink,
                    safeTasks(() -> sqlExportService.listBySessionId(taskOwnerId, sessionId)));
        }

        appendPlatformAiTasks(tasks, user);
        tasks.sort(Comparator.comparingLong(GlobalTaskCenterController::sortTime).reversed());

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", System.currentTimeMillis());
        data.put("tasks", tasks);
        data.put("summary", buildSummary(tasks));
        return ApiResponse.success(data);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody CancelRequest body,
                                      HttpServletRequest request) throws Exception {
        User user = requireUser(request);
        String kind = requireText(body != null ? body.kind() : null, "kind").toLowerCase(Locale.ROOT);
        String taskId = requireText(body != null ? body.taskId() : null, "taskId");
        String sessionId = body != null ? body.sessionId() : null;

        Object result;
        switch (kind) {
            case "shell" -> {
                PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(requireText(sessionId, "sessionId"));
                result = asyncShellService.cancel(session, taskId);
            }
            case "puppet_ai" -> {
                PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(requireText(sessionId, "sessionId"));
                AiThread thread = puppetNodeAiThreadService.requireThread(session, taskId);
                thread.stop("从全局任务中心停止");
                result = true;
            }
            case "platform_ai" -> {
                PlatformAiState state = platformAiThreadService.stateForUser(user, taskId);
                if (state == null) {
                    throw ApiException.notFound("平台 AI 任务不存在或已结束");
                }
                state.stopGeneration("从全局任务中心停止");
                result = true;
            }
            case "download" -> {
                String sid = requireText(sessionId, "sessionId");
                PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sid);
                String ownerId = taskOwnerId(session, user);
                requireTaskOwnership(downloadEngineService.listBySessionId(ownerId, sid), taskId);
                result = downloadEngineService.cancel(ownerId, taskId);
            }
            case "upload" -> {
                String sid = requireText(sessionId, "sessionId");
                PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sid);
                String ownerId = taskOwnerId(session, user);
                requireTaskOwnership(uploadEngineService.listBySessionId(ownerId, sid), taskId);
                result = uploadEngineService.cancel(ownerId, taskId);
            }
            case "db_export" -> {
                String sid = requireText(sessionId, "sessionId");
                PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sid);
                String ownerId = taskOwnerId(session, user);
                requireTaskOwnership(sqlExportService.listBySessionId(ownerId, sid), taskId);
                result = sqlExportService.stop(ownerId, taskId);
            }
            default -> throw ApiException.badRequest("不支持取消的任务类型: " + kind);
        }

        return ApiResponse.success(result);
    }

    private void appendShellTasks(List<Map<String, Object>> target,
                                  PuppetNodeSession session,
                                  String sessionId,
                                  String puppetName,
                                  String connLink) {
        for (AsyncShellTask task : session.getAsyncShellTasks()) {
            String status = normalizeStatus(task.getStatus().name());
            LinkedHashMap<String, Object> item = baseTask(
                    "shell", sessionId, puppetName, connLink, task.getTaskId(), task.getCommand(), status);
            item.put("startedAt", task.getStartTime());
            item.put("finishedAt", positiveOrNull(task.getEndTime()));
            item.put("updatedAt", task.getEndTime() > 0 ? task.getEndTime() : task.getStartTime());
            item.put("progress", terminal(status) ? 100 : 35);
            item.put("detail", task.getCommand());
            item.put("exitCode", task.getExitCode());
            item.put("cancellable", active(status));
            target.add(item);
        }
    }

    private void appendPuppetAiTasks(List<Map<String, Object>> target,
                                     PuppetNodeSession session,
                                     String sessionId,
                                     String puppetName,
                                     String connLink) {
        for (AiThread thread : session.listAiThreads()) {
            String status = normalizeStatus(thread.getRunStatus());
            if ("idle".equals(status)) {
                continue;
            }
            LinkedHashMap<String, Object> item = baseTask(
                    "puppet_ai", sessionId, puppetName, connLink,
                    thread.getThreadId(), thread.getTitle(), status);
            item.put("threadId", thread.getThreadId());
            item.put("startedAt", thread.getLastActiveAt());
            item.put("updatedAt", thread.getLastActiveAt());
            item.put("progress", terminal(status) ? 100 : 50);
            item.put("executing", thread.isExecuting());
            item.put("cancellable", active(status) || thread.isExecuting());
            target.add(item);
        }
    }

    private void appendPlatformAiTasks(List<Map<String, Object>> target, User user) {
        for (Map<String, Object> thread : platformAiThreadService.listThreads(user)) {
            String status = normalizeStatus(text(thread.get("runStatus")));
            if ("idle".equals(status)) {
                continue;
            }
            String threadId = text(thread.get("threadId"));
            if (threadId == null) {
                continue;
            }
            boolean executing = Boolean.TRUE.equals(thread.get("executing"));
            LinkedHashMap<String, Object> item = baseTask(
                    "platform_ai", null, "平台 AI", null,
                    threadId, firstText(thread, "title", "threadId"), status);
            item.put("threadId", threadId);
            item.put("startedAt", firstLong(thread, "lastActiveAt", "createdAt"));
            item.put("updatedAt", firstLong(thread, "lastActiveAt", "createdAt"));
            item.put("progress", terminal(status) ? 100 : 50);
            item.put("executing", executing);
            item.put("cancellable", active(status) || executing);
            target.add(item);
        }
    }

    private void appendServiceTasks(List<Map<String, Object>> target,
                                    String kind,
                                    String sessionId,
                                    String puppetName,
                                    String connLink,
                                    List<Map<String, Object>> rawTasks) {
        for (Map<String, Object> raw : rawTasks) {
            Map<String, Object> flattened = flatten(raw);
            String taskId = text(flattened.get("taskId"));
            if (taskId == null) {
                continue;
            }
            String status = normalizeStatus(firstText(flattened, "status", "state"));
            String title = firstText(flattened, "fileName", "filePath", "database", "taskType");
            if (title == null) {
                title = kind + " " + taskId;
            }
            LinkedHashMap<String, Object> item = baseTask(
                    kind, sessionId, puppetName, connLink, taskId, title, status);
            item.put("startedAt", firstLong(flattened, "startTime", "startedAt", "startAtMs", "createdTime", "createdAt", "createAtMs"));
            item.put("finishedAt", firstLong(flattened, "endTime", "endAt", "endAtMs"));
            item.put("updatedAt", firstLong(flattened, "updatedAt", "updatedAtMs", "endTime", "endAt", "endAtMs", "startTime", "startedAt", "startAtMs", "createdTime", "createdAt", "createAtMs"));
            item.put("progress", resolveProgress(kind, flattened, status));
            item.put("detail", firstText(flattened, "filePath", "downloadPath", "database"));
            item.put("error", firstText(flattened, "error", "lastError", "errorMessage"));
            item.put("currentStage", firstText(flattened, "currentStage"));
            item.put("errorStage", firstText(flattened, "errorStage"));
            item.put("fileSize", firstLong(flattened, "fileSize", "expectedLength", "totalBytes"));
            item.put("transferredBytes", firstLong(flattened, "uploadedBytes", "downloadedBytes"));
            item.put("cancellable", active(status));
            target.add(item);
        }
    }

    private static LinkedHashMap<String, Object> baseTask(String kind,
                                                           String sessionId,
                                                           String puppetName,
                                                           String connLink,
                                                           String taskId,
                                                           String title,
                                                           String status) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("id", kind + ":" + (sessionId != null ? sessionId : "platform") + ":" + taskId);
        item.put("kind", kind);
        item.put("source", "server");
        item.put("taskId", taskId);
        item.put("sessionId", sessionId);
        item.put("puppetName", puppetName);
        item.put("connLink", connLink);
        item.put("title", title);
        item.put("status", status);
        return item;
    }

    private static List<Map<String, Object>> safeTasks(TaskSupplier supplier) {
        try {
            return extractTasks(supplier.get());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractTasks(Map<String, Object> result) {
        if (result == null || !(result.get("tasks") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                tasks.add((Map<String, Object>) map);
            }
        }
        return tasks;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> raw) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (raw.get("meta") instanceof Map<?, ?> meta) {
            result.putAll((Map<String, Object>) meta);
        }
        result.putAll(raw);
        return result;
    }

    private static void requireTaskOwnership(Map<String, Object> taskList, String taskId) {
        boolean found = extractTasks(taskList).stream()
                .map(GlobalTaskCenterController::flatten)
                .anyMatch(task -> taskId.equals(text(task.get("taskId"))));
        if (!found) {
            throw ApiException.notFound("任务不存在或无权访问: " + taskId);
        }
    }

    private static Map<String, Object> buildSummary(List<Map<String, Object>> tasks) {
        long activeCount = tasks.stream().filter(task -> active(text(task.get("status")))).count();
        long failedCount = tasks.stream().filter(task -> "failed".equals(task.get("status"))).count();
        long completedCount = tasks.stream().filter(task -> "completed".equals(task.get("status"))).count();
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", tasks.size());
        summary.put("active", activeCount);
        summary.put("completed", completedCount);
        summary.put("failed", failedCount);
        return summary;
    }

    private static int resolveProgress(String kind, Map<String, Object> task, String status) {
        Long explicit = firstLong(task, "progress");
        if (explicit != null) {
            return (int) Math.max(0, Math.min(100, explicit));
        }
        if ("download".equals(kind)) {
            Long total = firstLong(task, "expectedLength", "fileSize");
            Long done = firstLong(task, "downloadedBytes");
            if (total != null && total > 0 && done != null) {
                return (int) Math.max(0, Math.min(100, done * 100L / total));
            }
        }
        return terminal(status) ? 100 : active(status) ? 35 : 0;
    }

    private static String normalizeStatus(String rawStatus) {
        String value = rawStatus == null ? "idle" : rawStatus.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "done", "completed", "success" -> "completed";
            case "running", "uploading", "downloading", "exporting", "scanning", "shell_running" -> "running";
            case "pending", "new" -> "pending";
            case "paused" -> "paused";
            case "failed", "error" -> "failed";
            case "cancelled", "canceled", "stopped" -> "cancelled";
            default -> value;
        };
    }

    private static boolean active(String status) {
        return "pending".equals(status)
                || "running".equals(status)
                || "paused".equals(status);
    }

    private static boolean terminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private static long sortTime(Map<String, Object> task) {
        Long value = firstLong(task, "updatedAt", "finishedAt", "startedAt");
        return value != null ? value : 0L;
    }

    private static Long positiveOrNull(long value) {
        return value > 0 ? value : null;
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = text(map.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long firstLong(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // try next field
                }
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + "不能为空");
        }
        return value.trim();
    }

    private static User requireUser(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        if (user == null) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user;
    }

    private static String taskOwnerId(PuppetNodeSession session, User fallbackUser) {
        String ownerId = session != null ? session.getCreateByUser() : null;
        if (ownerId != null && !ownerId.isBlank()) {
            return ownerId;
        }
        return fallbackUser.getUserId();
    }

    public record CancelRequest(String kind, String sessionId, String taskId) {
    }

    @FunctionalInterface
    private interface TaskSupplier {
        Map<String, Object> get() throws Exception;
    }
}
