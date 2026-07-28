package org.leo.jmg.mem.packer.obfuscation;

import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.mem.packer.jsp.JspDocument;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责 JSP/JSPX 的标签、scriptlet 和死代码噪声。
 */
public final class NoiseObfuscator {

    private static final Pattern SCRIPTLET_BOUNDARY =
            Pattern.compile("%>([ \t\r\n]*)(<%[!@=]?)", Pattern.MULTILINE);

    private static final String[] CSS_PROPERTIES = {
        "display:none", "visibility:hidden", "color:#fff",
        "margin:0", "padding:0 8px", "font-size:0",
        "opacity:0", "position:absolute", "z-index:-1"
    };

    private static final String[][] META_ATTRIBUTES = {
        {"name", "viewport", "content", "width=device-width"},
        {"name", "generator", "content", "WordPress"},
        {"name", "robots", "content", "noindex"},
        {"http-equiv", "X-UA-Compatible", "content", "IE=edge"},
        {"charset", "UTF-8"}
    };

    private static final String[] JAVASCRIPT_SNIPPETS = {
        "var _=window||{};",
        "if(document.readyState==='complete'){}",
        "window.__loaded=true;",
        "try{}catch(e){}",
        "(function(){})();",
        "/*@cc_on@*/",
        "var a=0,b=1;"
    };

    private static final String[] DEAD_CLASS_NAMES = {
        "org.springframework.context.ApplicationContext",
        "org.springframework.web.context.WebApplicationContext",
        "org.springframework.beans.factory.BeanFactory",
        "org.springframework.web.servlet.DispatcherServlet",
        "org.springframework.boot.SpringApplication",
        "org.springframework.context.annotation.Configuration",
        "org.springframework.web.bind.annotation.RestController",
        "org.apache.catalina.core.ApplicationContext",
        "javax.servlet.http.HttpServlet",
        "javax.servlet.Filter",
        "org.hibernate.SessionFactory",
        "org.apache.ibatis.session.SqlSession",
        "javax.persistence.EntityManager",
        "org.apache.log4j.Logger",
        "ch.qos.logback.classic.Logger",
        "org.slf4j.LoggerFactory",
        "org.apache.struts2.ServletActionContext",
        "com.opensymphony.xwork2.ActionContext",
        "org.apache.commons.lang3.StringUtils",
        "com.google.common.collect.Lists",
        "com.fasterxml.jackson.databind.ObjectMapper",
        "java.sql.DriverManager",
        "javax.sql.DataSource",
        "org.thymeleaf.TemplateEngine",
        "org.apache.velocity.app.VelocityEngine"
    };

    private static final String[] DEAD_PROPERTIES = {
        "server.name", "java.vendor", "os.arch", "user.timezone",
        "java.class.version", "file.encoding", "sun.jnu.encoding"
    };

    private static final String[] DEAD_HEADERS = {
        "Accept-Language", "Accept-Encoding", "Cache-Control",
        "X-Forwarded-For", "X-Real-IP", "Referer", "DNT"
    };

    private NoiseObfuscator() {
    }

    public static String insertBoundaryTags(String code) {
        Matcher matcher = SCRIPTLET_BOUNDARY.matcher(code);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = "%>" + matcher.group(1)
                    + randomNoiseTag() + "\n" + matcher.group(2);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String injectScriptletStatements(String code) {
        final Random random = GenerationRandom.current();
        final Set<String> used = new HashSet<String>();
        return JspDocument.parse(code).transformScriptlets(
                new JspDocument.ContentTransformer() {
                    @Override
                    public String transform(String content) {
                        return buildScriptletNoise(random, used) + content;
                    }
                }).render();
    }

    public static String injectDeadBlocks(String code) {
        final Random random = GenerationRandom.current();
        final Set<String> used = new HashSet<String>();
        return JspDocument.parse(code).transformScriptlets(
                new JspDocument.ContentTransformer() {
                    @Override
                    public String transform(String content) {
                        return buildDeadBlock(random, used) + content;
                    }
                }).render();
    }

    private static String randomNoiseTag() {
        Random random = GenerationRandom.current();
        switch (random.nextInt(6)) {
            case 0:
                return "<script>"
                        + JAVASCRIPT_SNIPPETS[random.nextInt(JAVASCRIPT_SNIPPETS.length)]
                        + "</script>";
            case 1:
                int words = 2 + random.nextInt(4);
                StringBuilder comment = new StringBuilder("<!--");
                for (int index = 0; index < words; index++) {
                    comment.append(' ').append(
                            ObfuscationSupport.randomWord(random, 3, 8));
                }
                return comment.append(" -->").toString();
            case 2:
                return "<style>."
                        + ObfuscationSupport.randomWord(random, 4, 10)
                        + "{" + CSS_PROPERTIES[random.nextInt(CSS_PROPERTIES.length)]
                        + "}</style>";
            case 3:
                String[] attributes =
                        META_ATTRIBUTES[random.nextInt(META_ATTRIBUTES.length)];
                StringBuilder meta = new StringBuilder("<meta");
                for (int index = 0; index + 1 < attributes.length; index += 2) {
                    meta.append(' ').append(attributes[index]).append("=\"")
                            .append(attributes[index + 1]).append('"');
                }
                return meta.append('>').toString();
            case 4:
                return "<noscript><p>"
                        + ObfuscationSupport.randomWord(random, 4, 12)
                        + "</p></noscript>";
            default:
                return "<div id=\""
                        + ObfuscationSupport.randomWord(random, 4, 8)
                        + "\" style=\"display:none\"></div>";
        }
    }

    private static String buildScriptletNoise(Random random, Set<String> used) {
        int count = 1 + random.nextInt(2);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            String name = ClassNameGenerator.randomFieldName(used);
            switch (random.nextInt(5)) {
                case 0:
                    result.append("\n    int ").append(name).append(" = ")
                            .append(random.nextInt(1024)).append(';');
                    break;
                case 1:
                    result.append("\n    long ").append(name).append(" = ")
                            .append(random.nextInt(65536)).append("L;");
                    break;
                case 2:
                    result.append("\n    boolean ").append(name)
                            .append(random.nextBoolean() ? " = true;" : " = false;");
                    break;
                case 3:
                    result.append("\n    String ").append(name).append(" = \"")
                            .append(ObfuscationSupport.randomWord(random, 3, 9))
                            .append("\";");
                    break;
                default:
                    result.append("\n    Object ").append(name).append(" = null;");
                    break;
            }
        }
        return result.append('\n').toString();
    }

    private static String buildDeadBlock(Random random, Set<String> used) {
        String name = ClassNameGenerator.randomFieldName(used);
        switch (random.nextInt(5)) {
            case 0:
                String className =
                        DEAD_CLASS_NAMES[random.nextInt(DEAD_CLASS_NAMES.length)];
                return "\n    if(false){try{Class.forName(\"" + className
                        + "\");}catch(ClassNotFoundException " + name + "){}}";
            case 1:
                return "\n    if(false){try{new javax.naming.InitialContext().lookup(\"java:comp/env\");}"
                        + "catch(Exception " + name + "){}}";
            case 2:
                String property =
                        DEAD_PROPERTIES[random.nextInt(DEAD_PROPERTIES.length)];
                return "\n    if(false){String " + name
                        + "=System.getProperty(\"" + property + "\",\"\");}";
            case 3:
                return "\n    if(false){int " + name
                        + "=request.getContentLength();if(" + name + "<0)return;}";
            default:
                String header = DEAD_HEADERS[random.nextInt(DEAD_HEADERS.length)];
                return "\n    if(false){String " + name
                        + "=request.getHeader(\"" + header + "\");}";
        }
    }
}
