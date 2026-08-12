package org.leo.core.repository.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.file.Files;

/** Persistent puppet-level reconnaissance summary storage. */
@Repository
public class PuppetReconRepository {

    private static final String RECON_SUMMARY_MD = "recon-summary.md";
    private final AtomicFileStore fileStore;

    public PuppetReconRepository(AtomicFileStore fileStore) {
        this.fileStore = fileStore;
    }

    public synchronized File save(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            PuppetNodeSession session = requireSession(sessionId);
            File file = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(
                    session.getCreateByUser(), session.resolvePuppetId()), RECON_SUMMARY_MD);
            String normalized = normalize(content);
            if (normalized == null) {
                Files.deleteIfExists(file.toPath());
                sync(session, null);
                return null;
            }
            File saved = fileStore.writeText(file, normalized);
            sync(session, normalized);
            return saved;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized String append(String sessionId, String content) {
        String addition = normalize(content);
        if (sessionId == null || sessionId.isBlank() || addition == null) return null;
        try {
            PuppetNodeSession session = requireSession(sessionId);
            File file = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(
                    session.getCreateByUser(), session.resolvePuppetId()), RECON_SUMMARY_MD);
            String current = fileStore.readText(file);
            if (current == null) current = normalize(session.getReconSummary());
            String updated = current == null ? addition : current + "\n\n" + addition;
            fileStore.writeText(file, updated);
            sync(session, updated);
            return updated;
        } catch (Exception e) {
            return null;
        }
    }

    public String load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            PuppetNodeSession session = requireSession(sessionId);
            return load(session.getCreateByUser(), session.resolvePuppetId());
        } catch (Exception e) {
            return null;
        }
    }

    public String load(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return null;
        try {
            return fileStore.readText(new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), RECON_SUMMARY_MD));
        } catch (Exception e) {
            return null;
        }
    }

    private PuppetNodeSession requireSession(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null || session.resolvePuppetId() == null) {
            throw new IllegalStateException("session 未绑定 puppetId: " + sessionId);
        }
        return session;
    }

    private void sync(PuppetNodeSession source, String content) {
        String puppetId = source.resolvePuppetId();
        String userId = source.getCreateByUser();
        for (PuppetNodeSession session : PuppetNodeSessionContainer.getAllSession().values()) {
            if (session == null) continue;
            if (!java.util.Objects.equals(userId, session.getCreateByUser())) continue;
            if (!java.util.Objects.equals(puppetId, session.resolvePuppetId())) continue;
            session.setReconSummary(content);
        }
    }

    private String normalize(String content) {
        return content == null || content.isBlank() ? null : content.strip();
    }
}
