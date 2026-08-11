package org.leo.jmg.mem;

/**
 * 应用服务器 / 运行环境类型（与生成器能力目录中的 server 段一致）
 */
public enum ServerType {

    TOMCAT("Tomcat"),
    JETTY5("Jetty5"),
    JETTY("Jetty"),
    JBOSS("JBoss"),
    JBOSS_AS("JBossAS"),
    JBOSS_EAP6("JBossEAP6"),
    UNDERTOW("Undertow"),
    JBOSS_EAP7("JBossEAP7"),
    WILDFLY("Wildfly"),
    RESIN("Resin"),
    RESIN2("Resin2"),
    GLASSFISH("Glassfish"),
    PAYARA("Payara"),
    WEBLOGIC("WebLogic"),
    WEBSPHERE("WebSphere"),
    SPRING_WEBMVC("SpringWebMVC"),
    APUSIC("Apusic"),
    BES("BES"),
    INFORSUITE("InforSuite"),
    TONGWEB("TongWeb"),
    STRUTS2("Struts2");

    private final String value;

    ServerType(String value) {
        this.value = value;
    }

    /** 与 Mapper、API 中使用的字符串一致 */
    public String getValue() {
        return value;
    }

    /** 解析 API / 用户输入，大小写不敏感。 */
    public static ServerType fromString(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String t = s.trim();
        for (ServerType e : values()) {
            if (e.value.equalsIgnoreCase(t)) {
                return e;
            }
        }
        return null;
    }
}
