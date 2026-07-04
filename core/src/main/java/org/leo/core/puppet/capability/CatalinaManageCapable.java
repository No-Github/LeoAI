package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect and manage Java web containers.
 */
public interface CatalinaManageCapable {

    Map<String, Object> getCatalinaInfo(String catalinaName, String webFramework) throws Exception;

    Map<String, Object> unloadCatalinaFilter(String catalinaName, String contextName, String filterName) throws Exception;

    Map<String, Object> unloadCatalinaServlet(String catalinaName, String contextName, String servletPattern) throws Exception;

    Map<String, Object> unloadCatalinaValve(String catalinaName, String valveId) throws Exception;

    Map<String, Object> unloadCatalinaListener(String catalinaName, String listenerId) throws Exception;

    Map<String, Object> unloadSpringController(String webFramework, String mappingInfo) throws Exception;

    Map<String, Object> unloadSpringInterceptor(String webFramework, String interceptorId) throws Exception;
}
