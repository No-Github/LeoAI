package org.leo.ai.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明 Agent 工具对象或方法所需的最低权限。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface AiToolAccess {

    Level value() default Level.AUTHENTICATED;

    enum Level {
        AUTHENTICATED,
        ADMIN
    }
}
