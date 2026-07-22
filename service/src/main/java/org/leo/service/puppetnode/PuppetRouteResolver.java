package org.leo.service.puppetnode;

import org.leo.core.entity.Puppet;
import org.leo.service.PuppetService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves one immutable child-to-transport Puppet chain with cycle checks. */
final class PuppetRouteResolver {

    private static final int MAX_DEPTH = 100;
    private final PuppetService puppetService;

    PuppetRouteResolver(PuppetService puppetService) {
        this.puppetService = puppetService;
    }

    Route resolve(Puppet requested) {
        if (requested == null) throw new IllegalArgumentException("Puppet不能为空");

        List<Puppet> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Puppet current = requested;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String currentId = normalized(current.getPuppetId());
            if (currentId != null && !visited.add(currentId)) {
                throw new IllegalArgumentException("Puppet 父链存在循环: " + currentId);
            }
            chain.add(current);

            String parentId = normalized(current.getParentPuppetId());
            if (parentId == null || "root".equals(parentId)) {
                return new Route(requested, current, chain);
            }
            if (visited.contains(parentId)) {
                throw new IllegalArgumentException("Puppet 父链存在循环: " + parentId);
            }
            if (puppetService == null) {
                throw new IllegalStateException("PuppetService 未初始化");
            }
            current = puppetService.findPuppetById(parentId);
            if (current == null) {
                throw new IllegalArgumentException("Puppet 父节点不存在: " + parentId);
            }
        }
        throw new IllegalArgumentException("Puppet 父链超过最大深度: " + MAX_DEPTH);
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static final class Route {
        private final Puppet requested;
        private final Puppet transport;
        private final List<Puppet> chain;

        private Route(Puppet requested, Puppet transport, List<Puppet> chain) {
            this.requested = requested;
            this.transport = transport;
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        }

        Puppet requested() { return requested; }
        Puppet transport() { return transport; }
        List<Puppet> chain() { return chain; }
    }
}
