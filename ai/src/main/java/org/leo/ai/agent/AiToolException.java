package org.leo.ai.agent;

import java.util.Objects;

/**
 * 工具主动抛出的、可安全跨越 Agent 边界的领域错误。
 *
 * <p>工具只描述错误代码、恢复责任和给模型的安全提示；是否继续当前 Turn
 * 由统一的 {@link AiToolErrorHandler} 决定。
 */
public final class AiToolException extends RuntimeException {

    public enum Recovery {
        MODEL,
        SYSTEM,
        USER,
        FATAL
    }

    private final String code;
    private final Recovery recovery;
    private final String safeMessage;
    private final String hint;

    private AiToolException(String code,
                            Recovery recovery,
                            String safeMessage,
                            String hint,
                            Throwable cause) {
        super(safeMessage, cause);
        this.code = requireText(code, "code");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.safeMessage = requireText(safeMessage, "safeMessage");
        this.hint = hint != null && !hint.isBlank() ? hint.trim() : null;
    }

    public static AiToolException modelCorrectable(String code,
                                                   String message,
                                                   String hint) {
        return new AiToolException(
                code, Recovery.MODEL, message, hint, null);
    }

    public static AiToolException userActionRequired(String code,
                                                     String message,
                                                     String hint) {
        return new AiToolException(
                code, Recovery.USER, message, hint, null);
    }

    public static AiToolException systemRetryable(String code,
                                                  String message,
                                                  Throwable cause) {
        return new AiToolException(
                code, Recovery.SYSTEM, message, null, cause);
    }

    public static AiToolException fatal(String code,
                                        String message,
                                        Throwable cause) {
        return new AiToolException(
                code, Recovery.FATAL, message, null, cause);
    }

    public String code() {
        return code;
    }

    public Recovery recovery() {
        return recovery;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public String hint() {
        return hint;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
