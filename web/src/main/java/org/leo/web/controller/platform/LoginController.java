package org.leo.web.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.UserService;
import org.leo.web.dto.platform.user.ChangePasswordRequest;
import org.leo.web.dto.platform.user.LoginRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.security.LoginAttemptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户登录控制器。
 * Passwords use salted PBKDF2 hashes; legacy MD5 rows are upgraded on login.
 */
@RestController
@RequestMapping("/platform/user")
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private static final String SESSION_ATTR_USER  = "user";

    private final UserService userService;
    private final PermissionService permissionService;
    private final LoginAttemptService loginAttemptService;

    public LoginController(UserService userService, PermissionService permissionService,
                           LoginAttemptService loginAttemptService) {
        this.userService = userService;
        this.permissionService = permissionService;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * 用户登录。旧 MD5 记录会在验证成功后透明升级。
     */
    @PostMapping("/login")
    public Map<String, Object> login(HttpServletRequest request,
                                     @RequestBody LoginRequest body) {
        String username = requireText(body != null ? body.username() : null, "username不能为空");
        String password = requireText(body != null ? body.password() : null, "password不能为空");
        String remoteAddress = request.getRemoteAddr();
        long retryAfter = loginAttemptService.retryAfterSeconds(username, remoteAddress);
        if (retryAfter > 0L) {
            throw ApiException.tooManyRequests("登录失败次数过多，请在 " + retryAfter + " 秒后重试");
        }

        User user = userService.getUserByName(username);
        if (user == null || !PasswordUtil.verify(password, user.getPassword())) {
            loginAttemptService.recordFailure(username, remoteAddress);
            logger.warn("登录失败，用户名或密码错误: {}", username);
            throw ApiException.unauthorized("用户名或密码错误");
        }
        loginAttemptService.recordSuccess(username, remoteAddress);

        if (PasswordUtil.needsRehash(user.getPassword())) {
            user.setPassword(PasswordUtil.hash(password));
            userService.updateUser(user);
        }

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SESSION_ATTR_USER, user);
        logger.info("用户登录成功: {} ({})", username, user.getPrivilege());
        return ApiResponse.success();
    }

    /**
     * 用户登出。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        User user = permissionService.getCurrentUser(request);
        if (user != null) {
            logger.info("用户登出: {}", user.getUserName());
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.success();
    }

    /**
     * 获取当前登录状态及用户信息（不含密码）。
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        User user = permissionService.getCurrentUser(request);
        HashMap<String, Object> data = new HashMap<>();
        data.put("isLoggedIn", user != null);
        if (user != null) {
            data.put("userId",    user.getUserId());
            data.put("userName",  user.getUserName());
            data.put("privilege", user.getPrivilege());
            data.put("teamId",    user.getTeamId());
        }
        return ApiResponse.success(data);
    }

    /**
     * 修改密码。oldPassword 为明文，newPassword 使用带盐 PBKDF2 保存。
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(HttpServletRequest request,
                                              @RequestBody ChangePasswordRequest body) {
        User sessionUser = permissionService.requireLogin(request);
        String oldPassword = requireText(body != null ? body.oldPassword() : null, "oldPassword不能为空");
        String newPassword = requireText(body != null ? body.newPassword() : null, "newPassword不能为空");

        User user = userService.getUserById(sessionUser.getUserId());
        if (user == null || !PasswordUtil.verify(oldPassword, user.getPassword())) {
            logger.warn("修改密码失败，旧密码不正确，userId: {}", sessionUser.getUserId());
            throw ApiException.badRequest("旧密码不正确");
        }

        user.setPassword(PasswordUtil.hash(newPassword));
        userService.updateUser(user);

        // 更新 Session 中的用户信息
        request.getSession().setAttribute(SESSION_ATTR_USER, user);
        logger.info("密码修改成功，userId: {}", user.getUserId());
        return ApiResponse.success("密码修改成功");
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }
}
