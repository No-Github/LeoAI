package org.leo.service;

import org.leo.core.entity.Puppet;
import org.leo.dao.mapper.PuppetMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Puppet 管理服务。
 *
 * <p>Puppet permission 值：private / team / public（默认 private），兼容旧值 protected。
 */
@Service
public class PuppetService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PuppetMapper puppetMapper;

    @Autowired
    public PuppetService(PuppetMapper puppetMapper) {
        this.puppetMapper = puppetMapper;
    }

    // ── 基础查询 ─────────────────────────────────────────────────────────────────

    public Puppet findPuppetById(String id) {
        if (id == null || id.isBlank()) return null;
        return puppetMapper.findPuppetById(id.trim());
    }

    public List<Puppet> findPuppetByCreateUserId(String createUserId) {
        if (createUserId == null || createUserId.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByCreateUser(createUserId.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> findPuppetByParentPuppetId(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByParentPuppetId(puppetId.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> findPuppetByPermission(String permission) {
        if (permission == null || permission.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByPermission(permission.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> getAllPuppet() {
        List<Puppet> list = puppetMapper.getAllPuppet();
        return list != null ? list : new ArrayList<>();
    }

    // ── 写操作 ───────────────────────────────────────────────────────────────────

    public boolean insertPuppet(Puppet puppet) {
        if (puppet == null) throw new IllegalArgumentException("puppet参数不能为空");
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return puppetMapper.insertPuppet(
                puppet.getPuppetId(),
                puppet.getPuppetName(),
                puppet.getParentPuppetId(),
                puppet.getCreateByUserId(),
                puppet.getTeamId(),
                puppet.getConnLink(),
                puppet.getProtocol(),
                puppet.getHeaders(),
                puppet.getReqDisguiseId(),
                puppet.getRespDisguiseId(),
                puppet.getProxyEnabled(),
                puppet.getProxyType(),
                puppet.getProxyHost(),
                puppet.getProxyPort(),
                puppet.getBalanceEnabled(),
                puppet.getMaxReqCount(),
                puppet.getPermission(),
                puppet.getLastHeartbeat(),
                puppet.getHeartbeatInterval(),
                now, now,
                puppet.getRemark(),
                puppet.getUrlStrategy(),
                puppet.getPaddingStrategy(),
                puppet.getHeaderNoiseStrategy(),
                puppet.getTlsFingerprintStrategy(),
                puppet.getType()
        );
    }

    public boolean updatePuppetById(Puppet puppet) {
        if (puppet == null || puppet.getPuppetId() == null || puppet.getPuppetId().isBlank()) {
            throw new IllegalArgumentException("puppetId不能为空");
        }
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return puppetMapper.updatePuppetById(
                puppet.getPuppetId(),
                puppet.getPuppetName(),
                puppet.getParentPuppetId(),
                puppet.getCreateByUserId(),
                puppet.getTeamId(),
                puppet.getConnLink(),
                puppet.getProtocol(),
                puppet.getHeaders(),
                puppet.getReqDisguiseId(),
                puppet.getRespDisguiseId(),
                puppet.getProxyEnabled(),
                puppet.getProxyType(),
                puppet.getProxyHost(),
                puppet.getProxyPort(),
                puppet.getBalanceEnabled(),
                puppet.getMaxReqCount(),
                puppet.getPermission(),
                puppet.getLastHeartbeat(),
                puppet.getHeartbeatInterval(),
                now,
                puppet.getRemark(),
                puppet.getUrlStrategy(),
                puppet.getPaddingStrategy(),
                puppet.getHeaderNoiseStrategy(),
                puppet.getTlsFingerprintStrategy(),
                puppet.getType()
        );
    }

    /**
     * 仅更新 last_heartbeat 字段。
     * 连接测试成功或 Puppet 初始化成功后调用，避免全量更新。
     */
    public boolean updateLastHeartbeat(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return false;
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return puppetMapper.updateLastHeartbeat(puppetId.trim(), now, now);
    }

    public boolean deletePuppetById(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id参数不能为空");
        return puppetMapper.deletePuppetById(id.trim());
    }
}
