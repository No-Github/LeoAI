package org.leo.jmg.mem.packer.jsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * JSP/JSPX 的轻量分段模型。它不解析 Java 语法，只负责可靠地区分文本、指令、
 * 声明和 Scriptlet，使后续步骤不再各自维护边界正则。
 */
public final class JspDocument {

    public enum SegmentType {
        TEXT,
        DIRECTIVE,
        DECLARATION,
        SCRIPTLET,
        EXPRESSION,
        COMMENT,
        JSPX_DECLARATION,
        JSPX_SCRIPTLET
    }

    public interface ContentTransformer {
        String transform(String content);
    }

    private final List<Segment> segments;

    private JspDocument(List<Segment> segments) {
        this.segments = segments;
    }

    public static JspDocument parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("JSP/JSPX 内容不能为空");
        }
        return looksLikeJspx(source) ? parseJspx(source) : parseJsp(source);
    }

    public List<SegmentType> getSegmentTypes() {
        List<SegmentType> result = new ArrayList<SegmentType>(segments.size());
        for (Segment segment : segments) {
            result.add(segment.type);
        }
        return Collections.unmodifiableList(result);
    }

    public JspDocument transformJavaSegments(ContentTransformer transformer) {
        return transform(EnumSet.of(SegmentType.DECLARATION, SegmentType.SCRIPTLET,
                SegmentType.JSPX_DECLARATION, SegmentType.JSPX_SCRIPTLET), transformer);
    }

    public JspDocument transformJavaCodeSegments(ContentTransformer transformer) {
        return transform(EnumSet.of(SegmentType.DECLARATION, SegmentType.SCRIPTLET,
                SegmentType.EXPRESSION, SegmentType.JSPX_DECLARATION,
                SegmentType.JSPX_SCRIPTLET), transformer);
    }

    public JspDocument transformScriptlets(ContentTransformer transformer) {
        return transform(EnumSet.of(SegmentType.SCRIPTLET, SegmentType.JSPX_SCRIPTLET), transformer);
    }

    public JspDocument transform(Set<SegmentType> types, ContentTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("transformer 不能为空");
        }
        List<Segment> transformed = new ArrayList<Segment>(segments.size());
        for (Segment segment : segments) {
            transformed.add(types.contains(segment.type)
                    ? segment.transformJavaContent(transformer)
                    : segment);
        }
        return new JspDocument(transformed);
    }

    /** 向首个声明区追加声明；不存在声明区时在合法位置创建一个。 */
    public JspDocument appendDeclaration(final String declaration) {
        if (declaration == null || declaration.trim().isEmpty()) {
            throw new IllegalArgumentException("声明内容不能为空");
        }
        List<Segment> updated = new ArrayList<Segment>(segments);
        for (int i = 0; i < updated.size(); i++) {
            Segment segment = updated.get(i);
            if (segment.type == SegmentType.DECLARATION
                    || segment.type == SegmentType.JSPX_DECLARATION) {
                updated.set(i, segment.transformJavaContent(new ContentTransformer() {
                    @Override
                    public String transform(String content) {
                        return content + "\n" + declaration + "\n";
                    }
                }));
                return new JspDocument(updated);
            }
        }

        boolean jspx = isJspx();
        Segment created = jspx
                ? new Segment(SegmentType.JSPX_DECLARATION,
                        "<jsp:declaration><![CDATA[", "\n" + declaration + "\n",
                        "]]></jsp:declaration>")
                : new Segment(SegmentType.DECLARATION, "<%!", "\n" + declaration + "\n", "%>");
        int insertion = firstExecutableSegmentIndex();
        updated.add(insertion < 0 ? updated.size() : insertion, created);
        return new JspDocument(updated);
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        for (Segment segment : segments) {
            out.append(segment.open).append(segment.content).append(segment.close);
        }
        return out.toString();
    }

    private static boolean looksLikeJspx(String source) {
        return source.contains("<jsp:root")
                || source.contains("<jsp:scriptlet")
                || source.contains("<jsp:declaration");
    }

    private boolean isJspx() {
        for (Segment segment : segments) {
            if (segment.type == SegmentType.JSPX_DECLARATION
                    || segment.type == SegmentType.JSPX_SCRIPTLET
                    || segment.content.contains("<jsp:root")) {
                return true;
            }
        }
        return false;
    }

    private int firstExecutableSegmentIndex() {
        for (int i = 0; i < segments.size(); i++) {
            SegmentType type = segments.get(i).type;
            if (type == SegmentType.SCRIPTLET || type == SegmentType.EXPRESSION
                    || type == SegmentType.JSPX_SCRIPTLET) {
                return i;
            }
        }
        return -1;
    }

    private static JspDocument parseJsp(String source) {
        List<Segment> result = new ArrayList<Segment>();
        int cursor = 0;
        while (cursor < source.length()) {
            int start = source.indexOf("<%", cursor);
            if (start < 0) {
                result.add(Segment.text(source.substring(cursor)));
                break;
            }
            if (start > cursor) {
                result.add(Segment.text(source.substring(cursor, start)));
            }

            boolean comment = source.startsWith("<%--", start);
            String open;
            String close;
            SegmentType type;
            if (comment) {
                open = "<%--";
                close = "--%>";
                type = SegmentType.COMMENT;
            } else if (source.startsWith("<%@", start)) {
                open = "<%@";
                close = "%>";
                type = SegmentType.DIRECTIVE;
            } else if (source.startsWith("<%!", start)) {
                open = "<%!";
                close = "%>";
                type = SegmentType.DECLARATION;
            } else if (source.startsWith("<%=", start)) {
                open = "<%=";
                close = "%>";
                type = SegmentType.EXPRESSION;
            } else {
                open = "<%";
                close = "%>";
                type = SegmentType.SCRIPTLET;
            }

            int contentStart = start + open.length();
            int end = source.indexOf(close, contentStart);
            if (end < 0) {
                throw new IllegalArgumentException("未闭合的 JSP " + type + "，位置: " + start);
            }
            result.add(new Segment(type, open, source.substring(contentStart, end), close));
            cursor = end + close.length();
        }
        if (source.isEmpty()) {
            result.add(Segment.text(""));
        }
        return new JspDocument(result);
    }

    private static JspDocument parseJspx(String source) {
        List<Segment> result = new ArrayList<Segment>();
        int cursor = 0;
        while (cursor < source.length()) {
            TagMatch next = findNextJspxTag(source, cursor);
            if (next == null) {
                result.add(Segment.text(source.substring(cursor)));
                break;
            }
            if (next.start > cursor) {
                result.add(Segment.text(source.substring(cursor, next.start)));
            }
            int openEnd = source.indexOf('>', next.start);
            if (openEnd < 0) {
                throw new IllegalArgumentException("未闭合的 JSPX 起始标签，位置: " + next.start);
            }
            String close = next.type == SegmentType.JSPX_SCRIPTLET
                    ? "</jsp:scriptlet>" : "</jsp:declaration>";
            int end = source.indexOf(close, openEnd + 1);
            if (end < 0) {
                throw new IllegalArgumentException("未闭合的 JSPX 标签 " + close);
            }
            result.add(new Segment(next.type,
                    source.substring(next.start, openEnd + 1),
                    source.substring(openEnd + 1, end), close));
            cursor = end + close.length();
        }
        return new JspDocument(result);
    }

    private static TagMatch findNextJspxTag(String source, int from) {
        int scriptlet = source.indexOf("<jsp:scriptlet", from);
        int declaration = source.indexOf("<jsp:declaration", from);
        if (scriptlet < 0 && declaration < 0) {
            return null;
        }
        if (scriptlet >= 0 && (declaration < 0 || scriptlet < declaration)) {
            return new TagMatch(scriptlet, SegmentType.JSPX_SCRIPTLET);
        }
        return new TagMatch(declaration, SegmentType.JSPX_DECLARATION);
    }

    private static final class TagMatch {
        private final int start;
        private final SegmentType type;

        private TagMatch(int start, SegmentType type) {
            this.start = start;
            this.type = type;
        }
    }

    private static final class Segment {
        private final SegmentType type;
        private final String open;
        private final String content;
        private final String close;

        private Segment(SegmentType type, String open, String content, String close) {
            this.type = type;
            this.open = open;
            this.content = content;
            this.close = close;
        }

        private static Segment text(String value) {
            return new Segment(SegmentType.TEXT, "", value, "");
        }

        private Segment transformJavaContent(ContentTransformer transformer) {
            int cdataStart = content.indexOf("<![CDATA[");
            int cdataEnd = content.lastIndexOf("]]>");
            if (cdataStart >= 0 && cdataEnd > cdataStart) {
                int bodyStart = cdataStart + "<![CDATA[".length();
                String transformed = transformer.transform(content.substring(bodyStart, cdataEnd));
                return new Segment(type, open,
                        content.substring(0, bodyStart) + transformed + content.substring(cdataEnd),
                        close);
            }
            return new Segment(type, open, transformer.transform(content), close);
        }
    }
}
