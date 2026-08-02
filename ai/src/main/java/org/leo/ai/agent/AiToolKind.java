package org.leo.ai.agent;

/** 工具在 Agent 架构中的职责，而不是其 Java 实现形式。 */
public enum AiToolKind {
    CONTROL,
    CONTEXT,
    QUERY,
    COMMAND,
    ARTIFACT,
    DELEGATION
}
