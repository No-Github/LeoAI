package org.leo.jmg.util.response;

import java.util.HashMap;
import java.util.Map;

public class ResponseUtil {

    private static final Map<String, String> METHOD_BODY_MAP = new HashMap<>();

    static {
        METHOD_BODY_MAP.put("tomcat", getCommonMethodBody());
        METHOD_BODY_MAP.put("jboss", getCommonMethodBody());
        METHOD_BODY_MAP.put("jbossas", getCommonMethodBody());
        METHOD_BODY_MAP.put("jbosseap6", getCommonMethodBody());
        METHOD_BODY_MAP.put("jbosseap7", getUndertowMethodBody());
        METHOD_BODY_MAP.put("wildfly", getUndertowMethodBody());
        METHOD_BODY_MAP.put("weblogic", getCommonMethodBody());
        METHOD_BODY_MAP.put("glassfish", getGlassFishMethodBody());
        METHOD_BODY_MAP.put("payara", getGlassFishMethodBody());
        METHOD_BODY_MAP.put("resin", getResinMethodBody());
        METHOD_BODY_MAP.put("jetty", getJettyMethodBody());
        METHOD_BODY_MAP.put("jetty5", getJetty5MethodBody());
        METHOD_BODY_MAP.put("websphere", getWebsphereMethodBody());
        METHOD_BODY_MAP.put("undertow", getUndertowMethodBody());
        METHOD_BODY_MAP.put("inforsuite", getCommonMethodBody());
        METHOD_BODY_MAP.put("bes", getCommonMethodBody());
        METHOD_BODY_MAP.put("tongweb", getTongwebMethodBody());
        METHOD_BODY_MAP.put("apusic", getApusicMethodBody());
    }

    public static String getMethodBody(String serverType) {
        String body = METHOD_BODY_MAP.get(serverType.toLowerCase());
        if (body == null) {
            throw new IllegalArgumentException(
                    "不支持的服务器类型：" + serverType + "，ListenerInjector 无对应的 getResponseFromRequest 实现");
        }
        return body;
    }

    private static String getCommonMethodBody() {
        return "{Object response = null;" +
                "        try {" +
                "            response = getFieldValue(getFieldValue($1, \"request\"), \"response\");" +
                "        } catch (Exception ex) {" +
                "            try {" +
                "                response = getFieldValue($1, \"response\");" +
                "            } catch (Exception ex1) {" +
                "            }" +
                "        }" +
                "        return response;}";
    }

    private static String getResinMethodBody() {
        return "{Object response;" +
                "        response = getFieldValue($1, \"_response\");" +
                "        return response;}";
    }

    private static String getJettyMethodBody() {
        return "{Object response;\n" +
                "        try{\n" +
                "            response = getFieldValue(getFieldValue($1,\"_channel\"),\"_response\");\n" +
                "        }catch (Exception e){\n" +
                "            try{\n" +
                "                response = getFieldValue(getFieldValue($1,\"_connection\"),\"_response\");\n" +
                "            }catch (Exception ex){\n" +
                "                response = getFieldValue(getFieldValue(getFieldValue($1,\"_servletChannel\"),\"_response\"),\"_servletApiResponse\");\n" +
                "            }\n" +
                "        }\n" +
                "        return response;}";
    }

    private static String getJetty5MethodBody() {
        return "{Object response;"
                + "try{response=getFieldValue($1,\"_servletHttpResponse\");}"
                + "catch(Exception e){try{response=getFieldValue(getFieldValue($1,\"_request\"),\"_servletHttpResponse\");}"
                + "catch(Exception ex){response=getFieldValue(getFieldValue($1,\"request\"),\"_servletHttpResponse\");}}"
                + "return response;}";
    }

    private static String getGlassFishMethodBody() {
        return "{Object response;"
                + "try{response=getFieldValue(getFieldValue($1,\"request\"),\"response\");}"
                + "catch(Exception e){try{response=getFieldValue($1,\"response\");}"
                + "catch(Exception ex){response=getFieldValue(getFieldValue($1,\"reqFacHelper\"),\"response\");}}"
                + "return response;}";
    }

    private static String getWebsphereMethodBody() {
        return "{Object response;" +
                "        response = getFieldValue(getFieldValue($1, \"_connContext\"), \"_response\");" +
                "        return response;}";
    }


    private static String getUndertowMethodBody() {
        return "{Object response = null;\n" +
                "java.util.Map map = (java.util.Map) getFieldValue(getFieldValue($1, \"exchange\"), \"attachments\");\n" +
                "Object[] keys = map.keySet().toArray();\n" +
                "for (int i = 0; i < keys.length; i++) {\n" +
                "    Object key = keys[i];\n" +
                "    if (map.get(key).toString().contains(\"ServletRequestContext\")) {\n" +
                "        response = getFieldValue(map.get(key), \"servletResponse\");\n" +
                "        break;\n" +
                "    }\n" +
                "}\n" +
                "return response;}";

    }

    private static String getTongwebMethodBody() {
        return "{Object response = null;" +
                "        try {" +
                "            response = getFieldValue(getFieldValue($1, \"request\"), \"response\");" +
                "        } catch (Exception ex) {" +
                "            try {" +
                "                response = getFieldValue($1, \"response\");" +
                "            } catch (Exception ex1) {" +
                "            }" +
                "        }\n" +
                "        return response;}";
    }

    private static String getApusicMethodBody() {
        return "{Object response;" +
                "        response = getFieldValue(getFieldValue($1, \"http\"),\"response\");" +
                "        return response;}";
    }

}
