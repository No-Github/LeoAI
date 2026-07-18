package org.leo.core.net.layer;

import org.leo.core.entity.Disguise;

import java.util.Map;

public class RequestLayer {
    private String url;
    private Map<String, String> headers;
    private Disguise disguise;

    public RequestLayer(String url, Map<String, String> headers, Disguise disguise) {
        this.url = url;
        this.headers = headers;
        this.disguise = disguise;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Disguise getDisguise() {
        return disguise;
    }
}
