-- =====================================================
-- LeoSpring 初始数据
-- =====================================================

-- 插入默认系统管理员团队
INSERT OR REPLACE INTO teams (
    team_id, 
    team_name, 
    leader_id, 
    description, 
    status, 
    create_time, 
    update_time, 
    remark
) VALUES (
    'system-admin',
    '系统管理员',
    'admin',
    '系统默认管理员团队',
    1,
    datetime('now'),
    datetime('now'),
    '系统初始化创建'
);

-- 管理员用户由 DataInitializer 创建，以便使用环境变量或随机引导密码，避免固定口令。

-- 插入系统配置
INSERT OR REPLACE INTO system_configs (
    config_key,
    config_value,
    config_type,
    description,
    create_time,
    update_time
) VALUES 
('system.name', 'LeoSpring', 'string', '系统名称', datetime('now'), datetime('now')),
('system.version', '2.1', 'string', '系统版本', datetime('now'), datetime('now')),
('system.description', 'LeoSpring - 轻量级远程主机管理平台', 'string', '系统描述', datetime('now'), datetime('now')),
('audit.mode', 'on', 'string', '审计日志模式：on=开启，write=关闭低风险读操作，off=完全关闭', datetime('now'), datetime('now')),
('log.retention.days', '30', 'number', '日志保留天数', datetime('now'), datetime('now')),
('session.timeout.minutes', '30', 'number', '会话超时时间(分钟)', datetime('now'), datetime('now')),
('heartbeat.interval.ms', '30000', 'number', '心跳间隔(毫秒)', datetime('now'), datetime('now')),
('max.file.upload.size.mb', '100', 'number', '最大文件上传大小(MB)', datetime('now'), datetime('now')),
('max.concurrent.sessions', '10', 'number', '最大并发会话数', datetime('now'), datetime('now')),
('security.password.min.length', '8', 'number', '密码最小长度', datetime('now'), datetime('now')),
('security.login.max.attempts', '5', 'number', '最大登录尝试次数', datetime('now'), datetime('now')),
('security.login.lock.seconds', '300', 'number', '登录失败锁定时长(秒)', datetime('now'), datetime('now'));
