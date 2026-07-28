package org.leo.jmg.mem.packer.obfuscation;

import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.mem.packer.jsp.JspDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ObfuscationSupport {

    private static final String[] SENSITIVE_LITERALS = {
        "javax.xml.bind.DatatypeConverter",
        "java.util.Base64",
        "sun.misc.Unsafe",
        "Base64"
    };

    private static final Pattern REFLECTION_STRING_PATTERN = Pattern.compile(
            "(?:getMethod|getDeclaredMethod|getField|getDeclaredField|" +
                    "getDeclaredConstructor|Class\\.forName|loadClass)\\s*\\(\\s*\"([^\"\\\\]+)\"",
            Pattern.DOTALL);

    private ObfuscationSupport() {
    }

    static List<String> collectSensitiveLiterals(String code) {
        LinkedHashSet<String> literals = new LinkedHashSet<String>();
        for (String literal : SENSITIVE_LITERALS) {
            literals.add(literal);
        }
        Matcher matcher = REFLECTION_STRING_PATTERN.matcher(code);
        while (matcher.find()) {
            String argument = matcher.group(1);
            if (argument.length() > 1) {
                literals.add(argument);
            }
        }
        List<String> result = new ArrayList<String>(literals);
        result.sort(new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return right.length() - left.length();
            }
        });
        return result;
    }

    static String randomWord(Random random, int minLength, int maxLength) {
        int length = minLength + random.nextInt(maxLength - minLength);
        char[] chars = new char[length];
        for (int index = 0; index < length; index++) {
            chars[index] = (char) ('a' + random.nextInt(26));
        }
        return new String(chars);
    }

    static String injectHelperDeclaration(String code, String declaration, boolean requireUtf8) {
        boolean jspx = code.contains("<jsp:root")
                || code.contains("<jsp:scriptlet")
                || code.contains("<jsp:declaration");
        if (requireUtf8 && !jspx && !code.contains("pageEncoding")) {
            code = "<%@ page pageEncoding=\"UTF-8\" %>\n" + code;
        }
        return JspDocument.parse(code).appendDeclaration(declaration).render();
    }

    static String buildTwoToOneCall(String methodName, String literal) {
        StringBuilder result = new StringBuilder(methodName).append("(\"");
        for (int index = 0; index < literal.length(); index += 2) {
            char high = literal.charAt(index);
            char low = index + 1 < literal.length() ? literal.charAt(index + 1) : '\0';
            result.append((char) ((high << 8) | (low & 0xff)));
        }
        return result.append("\",").append(literal.length()).append(')').toString();
    }

    static String buildTwoToOneHelperDeclaration(String methodName) {
        Random random = GenerationRandom.current();
        switch (random.nextInt(3)) {
            case 0:
                return "private static String " + methodName + "(String s,int n){"
                        + "byte[] b=new byte[n];int k=0;"
                        + "for(int i=0;i<s.length()&&k<n;i++){char c=s.charAt(i);b[k++]=(byte)(c>>8);if(k<n)b[k++]=(byte)(c&0xFF);}"
                        + "return new String(b);}";
            case 1:
                return "private static String " + methodName + "(String s,int n){"
                        + "byte[] b=new byte[n];int i=0,k=0;"
                        + "while(i<s.length()&&k<n){char c=s.charAt(i++);b[k++]=(byte)(c>>8);if(k<n)b[k++]=(byte)c;}"
                        + "return new String(b);}";
            default:
                return "private static String " + methodName + "(String s,int n){"
                        + "byte[] b=new byte[n];char[] c=s.toCharArray();int k=0;"
                        + "for(int i=0;i<c.length&&k<n;i++){b[k++]=(byte)(c[i]>>8);if(k<n)b[k++]=(byte)c[i];}"
                        + "return new String(b);}";
        }
    }
}
