package org.leo.ai.agent;

/** 工具对外部状态的影响级别。默认按写入处理，避免未知工具绕过幂等保护。 */
public enum AiToolOperation {
    READ_ONLY,
    WRITE,
    DESTRUCTIVE;

    public boolean mutatesState() {
        return this != READ_ONLY;
    }

    /**
     * 工具对象目前由多个历史模块提供，无法要求一次性给所有方法补注解，
     * 因此先用稳定的命名约定分类；后续新增工具可通过同名约定自动获得保护。
     */
    public static AiToolOperation classify(String toolName) {
        if (toolName == null || toolName.isBlank()) return WRITE;
        String name = toolName.trim().toLowerCase(java.util.Locale.ROOT);
        if (startsWithAny(name,
                "delete", "remove", "destroy", "drop", "purge", "wipe",
                "kill", "terminate", "uninstall", "revoke", "disable",
                "clear", "reset", "stop", "cancel", "close", "disconnect",
                "unload", "rollback")) {
            return DESTRUCTIVE;
        }
        if (startsWithAny(name,
                "get", "list", "query", "read", "fetch", "find", "search",
                "check", "test", "preview", "describe", "inspect", "show",
                "resolve", "validate", "health", "decompile", "recon",
                "calculate", "estimate", "fingerprint")) {
            return READ_ONLY;
        }
        return WRITE;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }
}
