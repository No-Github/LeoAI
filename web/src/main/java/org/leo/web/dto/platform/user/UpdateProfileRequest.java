package org.leo.web.dto.platform.user;

/** 当前用户可自行维护的个人资料字段。 */
public record UpdateProfileRequest(String email, String phone, String remark) {
}
