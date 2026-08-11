package org.apache.tomcat.websocket.server;

/** Tomcat WebSocket bypass injector 的轻量运行时夹具。 */
public final class UpgradeUtil {
    public static Object[] lastArguments;

    private UpgradeUtil() {
    }

    public static void doUpgrade(Object container, Object request,
                                 Object response, Object config,
                                 Object pathParams) {
        lastArguments = new Object[]{container, request, response, config, pathParams};
    }
}
