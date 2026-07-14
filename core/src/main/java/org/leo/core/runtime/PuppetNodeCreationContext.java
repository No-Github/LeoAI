package org.leo.core.runtime;

import org.leo.core.entity.Puppet;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared transport services supplied to an equal-status runtime module.
 *
 * <p>Runtime modules own node construction and component behavior. The service
 * layer owns persisted Puppet-chain lookup, proxies and physical transports.
 */
public interface PuppetNodeCreationContext {

    Communication createCommunication(Puppet puppet) throws Exception;

    TransportLayers createTransportLayers(Puppet puppet) throws Exception;

    final class TransportLayers {
        private final List<RequestLayer> requestLayers;
        private final List<ResponseLayer> responseLayers;

        public TransportLayers(List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
            this.requestLayers = immutableCopy(requestLayers);
            this.responseLayers = immutableCopy(responseLayers);
        }

        public List<RequestLayer> getRequestLayers() {
            return requestLayers;
        }

        public List<ResponseLayer> getResponseLayers() {
            return responseLayers;
        }

        private static <T> List<T> immutableCopy(List<T> source) {
            return source == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(source));
        }
    }
}
