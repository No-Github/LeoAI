package org.leo.ai.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 显式声明工具的调度、权限和统计语义。方法声明覆盖类声明。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AiToolPolicy {
    AiToolKind kind();
    AiToolOperation operation();
    boolean terminal() default false;
    boolean exclusive() default false;
    boolean parallelizable() default false;
    boolean business() default true;
}
