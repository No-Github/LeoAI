package org.leo.web.dto.puppetnode;

import java.util.List;

public record PuppetInitResponse(String sessionId, boolean cacheMode, List<String> capabilities) {
}
