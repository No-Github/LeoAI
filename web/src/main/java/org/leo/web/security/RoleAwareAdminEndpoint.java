package org.leo.web.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an admin-namespace controller that deliberately implements finer
 * leader/normal role checks itself. Unmarked /platform/admin endpoints are
 * administrator-only by default.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleAwareAdminEndpoint {
}
