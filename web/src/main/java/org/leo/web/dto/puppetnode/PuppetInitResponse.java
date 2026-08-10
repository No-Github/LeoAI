package org.leo.web.dto.puppetnode;

import java.util.List;

public record PuppetInitResponse(String sessionId, String projectId,
                                 boolean cacheMode, List<String> capabilities) {
}
