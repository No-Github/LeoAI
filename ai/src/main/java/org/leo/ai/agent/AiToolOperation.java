package org.leo.ai.agent;

/** 工具对外部状态的影响级别，由 {@link AiToolPolicy} 显式声明。 */
public enum AiToolOperation {
    READ_ONLY,
    WRITE,
    DESTRUCTIVE;

    public boolean mutatesState() {
        return this != READ_ONLY;
    }

}
