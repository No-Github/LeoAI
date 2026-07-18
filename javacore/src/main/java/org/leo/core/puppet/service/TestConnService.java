package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.rpc.PuppetOperation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TestConnService extends ComponentService {

    public TestConnService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> testConn() {
        return run(PuppetOperation.PING, null, null, Collections.emptyMap());
    }
}
