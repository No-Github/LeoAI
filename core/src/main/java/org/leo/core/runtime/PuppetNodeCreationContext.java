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

    /** Resolves transport and disguise layers from the same immutable Puppet route. */
    default ConnectionPlan createConnectionPlan(Puppet puppet) throws Exception {
        return new ConnectionPlan(createCommunication(puppet), createTransportLayers(puppet));
    }

    final class ConnectionPlan {
        private final Communication communication;
        private final TransportLayers transportLayers;

        public ConnectionPlan(Communication communication, TransportLayers transportLayers) {
            if (communication == null) throw new IllegalArgumentException("communication不能为空");
            this.communication = communication;
            this.transportLayers = transportLayers == null
                    ? new TransportLayers(Collections.emptyList(), Collections.emptyList())
                    : transportLayers;
        }

        public Communication getCommunication() {
            return communication;
        }

        public TransportLayers getTransportLayers() {
            return transportLayers;
        }
    }

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
