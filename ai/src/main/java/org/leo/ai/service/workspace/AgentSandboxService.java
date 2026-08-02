package org.leo.ai.service.workspace;

import jakarta.annotation.PreDestroy;
import org.leo.ai.config.AgentSandboxProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 仅通过 Docker 运行固定语言脚本；明确禁止宿主机 Shell 回退。 */
@Service
public class AgentSandboxService {

    private final AgentSandboxProperties properties;
    private final AgentWorkspaceService workspaceService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-sandbox");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    public AgentSandboxService(AgentSandboxProperties properties,
                               AgentWorkspaceService workspaceService) {
        this.properties = properties;
        this.workspaceService = workspaceService;
    }

    public Map<String, Object> start(AgentWorkspaceService.Workspace workspace,
                                     String runtime, String scriptPath,
                                     List<String> arguments) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "本地脚本沙箱未启用；设置 LEO_AI_SANDBOX_ENABLED=true 并确保 Docker 可用");
        }
        RuntimeSpec spec = runtime(runtime);
        Path script = workspaceService.resolveFile(workspace, scriptPath);
        String relative = workspace.filesRoot().relativize(script).toString().replace('\\', '/');
        if (!relative.toLowerCase(Locale.ROOT).endsWith(spec.extension())) {
            throw new IllegalArgumentException("脚本扩展名与 runtime 不匹配，要求 " + spec.extension());
        }
        List<String> safeArguments = validateArguments(arguments);
        cleanupExpired();
        String runId = UUID.randomUUID().toString();
        String containerName = "leo-ai-" + runId.substring(0, 12);
        RunState state = new RunState(runId, workspace, runtime.toLowerCase(Locale.ROOT),
                relative, containerName);
        runs.put(runId, state);
        executor.execute(() -> execute(state, spec, safeArguments));
        Map<String, Object> result = state.toMap();
        result.put("hint", "调用 sandboxRunStatus 查询状态和变更文件；运行环境默认断网且不能访问宿主机。");
        return result;
    }

    public Map<String, Object> status(AgentWorkspaceService.Workspace workspace,
                                      String runId) {
        RunState state = requireOwned(workspace, runId);
        Map<String, Object> result = state.toMap();
        synchronized (state) {
            result.put("logTail", tail(state.log, 20_000));
            result.put("changedFiles", state.changedFiles);
            result.put("logPath", state.logPath);
        }
        return result;
    }

    public Map<String, Object> cancel(AgentWorkspaceService.Workspace workspace,
                                      String runId) {
        RunState state = requireOwned(workspace, runId);
        state.cancelled = true;
        Process process = state.process;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            forceRemoveContainer(state.containerName);
        }
        state.status = "CANCELLED";
        state.finishedAt = Instant.now();
        return state.toMap();
    }

    private void execute(RunState state, RuntimeSpec spec, List<String> arguments) {
        state.status = "RUNNING";
        state.startedAt = Instant.now();
        var workspaceLock = state.workspace.lock().writeLock();
        workspaceLock.lock();
        try {
            if (state.cancelled) {
                state.status = "CANCELLED";
                return;
            }
            Map<String, String> before = workspaceService.snapshotHashes(state.workspace);
            workspaceService.archiveCurrentFiles(state.workspace);
            state.sandboxRoot = workspaceService.prepareSandboxCopy(state.workspace, state.runId);
            List<String> command = dockerCommand(state, spec, arguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            state.process = builder.start();
            Thread reader = new Thread(() -> readLog(state, state.process.getInputStream()),
                    "ai-sandbox-log");
            reader.setDaemon(true);
            reader.start();
            boolean finished = state.process.waitFor(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                state.status = "TIMED_OUT";
                state.process.destroyForcibly();
                forceRemoveContainer(state.containerName);
            } else if (state.cancelled) {
                state.status = "CANCELLED";
            } else {
                state.exitCode = state.process.exitValue();
                state.status = state.exitCode == 0 ? "COMPLETED" : "FAILED";
            }
            reader.join(2_000L);
            if ("COMPLETED".equals(state.status)) {
                Map<String, String> current = workspaceService.snapshotHashes(state.workspace);
                if (!current.equals(before)) {
                    state.status = "CONFLICT";
                    appendLog(state, "Workspace changed while sandbox was running; output was not committed.\n");
                } else {
                    synchronized (state) {
                        state.changedFiles = workspaceService.commitSandboxCopy(
                                state.workspace, state.sandboxRoot);
                    }
                }
            }
        } catch (IOException e) {
            appendLog(state, "Sandbox unavailable: " + e.getMessage() + "\n");
            state.status = "UNAVAILABLE";
        } catch (RuntimeException e) {
            appendLog(state, "Sandbox output rejected: " + e.getMessage() + "\n");
            state.status = e instanceof SecurityException ? "REJECTED" : "FAILED";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.status = "CANCELLED";
            if (state.process != null) state.process.destroyForcibly();
            forceRemoveContainer(state.containerName);
        } finally {
            state.process = null;
            state.finishedAt = Instant.now();
            synchronized (state) {
                byte[] logBytes = state.log.toByteArray();
                state.logPath = "logs/sandbox-" + state.runId + ".log";
                try {
                    workspaceService.writeGeneratedBytes(state.workspace, state.logPath, logBytes);
                } catch (RuntimeException error) {
                    state.logPath = null;
                }
            }
            deleteSandboxCopy(state.sandboxRoot, state.workspace.internalRoot());
            workspaceLock.unlock();
        }
    }

    private List<String> dockerCommand(RunState state, RuntimeSpec spec,
                                       List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(properties.getDockerCommand());
        command.addAll(List.of("run", "--rm", "--name", state.containerName,
                "--network", "none", "--cpus", properties.getCpus(),
                "--memory", properties.getMemory(), "--pids-limit",
                Integer.toString(properties.getPidsLimit()), "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "-v", state.sandboxRoot.toAbsolutePath() + ":/workspace:rw",
                "-w", "/workspace", spec.image(), spec.executable(),
                "/workspace/" + state.scriptPath));
        command.addAll(arguments);
        return command;
    }

    private RuntimeSpec runtime(String runtime) {
        String value = runtime == null ? "" : runtime.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "python" -> new RuntimeSpec(properties.getPythonImage(), "python", ".py");
            case "node", "javascript" -> new RuntimeSpec(properties.getNodeImage(), "node", ".js");
            case "java" -> new RuntimeSpec(properties.getJavaImage(), "java", ".java");
            default -> throw new IllegalArgumentException("runtime 只支持 python、node、java");
        };
    }

    private List<String> validateArguments(List<String> arguments) {
        if (arguments == null) return List.of();
        if (arguments.size() > 32) throw new IllegalArgumentException("arguments 最多32项");
        List<String> safe = new ArrayList<>();
        for (String value : arguments) {
            if (value == null || value.indexOf('\0') >= 0 || value.length() > 2_000) {
                throw new IllegalArgumentException("存在非法脚本参数");
            }
            safe.add(value);
        }
        return List.copyOf(safe);
    }

    private void readLog(RunState state, InputStream input) {
        byte[] buffer = new byte[8_192];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                synchronized (state) {
                    long remaining = properties.getMaxLogBytes() - state.log.size();
                    if (remaining > 0) state.log.write(buffer, 0, (int) Math.min(count, remaining));
                }
            }
        } catch (IOException ignored) { }
    }

    private void appendLog(RunState state, String text) {
        synchronized (state) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int remaining = (int) Math.max(0, properties.getMaxLogBytes() - state.log.size());
            state.log.write(bytes, 0, Math.min(bytes.length, remaining));
        }
    }

    private RunState requireOwned(AgentWorkspaceService.Workspace workspace, String runId) {
        RunState state = runs.get(runId);
        if (state == null || !state.workspace.userId().equals(workspace.userId())
                || !state.workspace.workspaceId().equals(workspace.workspaceId())) {
            throw new IllegalArgumentException("运行不存在或不属于当前任务");
        }
        return state;
    }

    private void forceRemoveContainer(String name) {
        try {
            new ProcessBuilder(properties.getDockerCommand(), "rm", "-f", name)
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private void cleanupExpired() {
        Instant cutoff = Instant.now().minusSeconds(24 * 60 * 60L);
        runs.entrySet().removeIf(entry -> entry.getValue().finishedAt != null
                && entry.getValue().finishedAt.isBefore(cutoff));
    }

    private String tail(ByteArrayOutputStream output, int maxChars) {
        String text = output.toString(StandardCharsets.UTF_8);
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    private void deleteSandboxCopy(Path sandboxRoot, Path internalRoot) {
        if (sandboxRoot == null) return;
        Path runRoot = sandboxRoot.getParent();
        if (runRoot == null || !runRoot.normalize().startsWith(
                internalRoot.resolve("sandbox").normalize())) return;
        try (var stream = java.nio.file.Files.walk(runRoot)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        } catch (IOException ignored) { }
    }

    @PreDestroy
    public void shutdown() {
        for (RunState state : runs.values()) {
            if (state.process != null && state.process.isAlive()) state.process.destroyForcibly();
        }
        executor.shutdownNow();
    }

    private record RuntimeSpec(String image, String executable, String extension) {}

    private static final class RunState {
        private final String runId;
        private final AgentWorkspaceService.Workspace workspace;
        private final String runtime;
        private final String scriptPath;
        private final String containerName;
        private final ByteArrayOutputStream log = new ByteArrayOutputStream();
        private volatile String status = "QUEUED";
        private volatile Integer exitCode;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Process process;
        private volatile Path sandboxRoot;
        private volatile boolean cancelled;
        private volatile List<Map<String, Object>> changedFiles = List.of();
        private volatile String logPath;

        private RunState(String runId, AgentWorkspaceService.Workspace workspace,
                         String runtime, String scriptPath, String containerName) {
            this.runId = runId;
            this.workspace = workspace;
            this.runtime = runtime;
            this.scriptPath = scriptPath;
            this.containerName = containerName;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("runId", runId);
            result.put("workspaceId", workspace.workspaceId());
            result.put("runtime", runtime);
            result.put("scriptPath", scriptPath);
            result.put("status", status);
            result.put("exitCode", exitCode);
            result.put("startedAt", startedAt != null ? startedAt.toString() : null);
            result.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
            return result;
        }
    }
}
