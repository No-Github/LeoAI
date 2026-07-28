package org.leo.jmg.mem.packer.obfuscation;

import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Random;

/**
 * 负责敏感字符串字面量的等价变换。
 */
public final class LiteralObfuscator {

    private LiteralObfuscator() {
    }

    public static String split(String code) {
        Random random = GenerationRandom.current();
        for (String literal : ObfuscationSupport.collectSensitiveLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target) || literal.length() < 2) {
                continue;
            }
            int segments = literal.length() >= 3 ? 2 + random.nextInt(2) : 2;
            LinkedHashSet<Integer> cutPoints = new LinkedHashSet<Integer>();
            while (cutPoints.size() < segments - 1) {
                cutPoints.add(1 + random.nextInt(literal.length() - 1));
            }
            int[] points = cutPoints.stream().mapToInt(Integer::intValue).sorted().toArray();
            code = code.replace(target, buildSplitConcat(literal, points, random));
        }
        return code;
    }

    public static String ghostBits(String code) {
        Random random = GenerationRandom.current();
        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());
        boolean changed = false;
        for (String literal : ObfuscationSupport.collectSensitiveLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) {
                continue;
            }
            StringBuilder encoded = new StringBuilder(methodName).append("(\"");
            for (int index = 0; index < literal.length(); index++) {
                int low = literal.charAt(index) & 0xff;
                int high = random.nextInt(0x52) + 0x4e;
                encoded.append((char) ((high << 8) | low));
            }
            encoded.append("\")");
            code = code.replace(target, encoded.toString());
            changed = true;
        }
        if (!changed) {
            return code;
        }
        return ObfuscationSupport.injectHelperDeclaration(
                code, buildGhostBitsHelperDeclaration(methodName), true);
    }

    public static String packTwoToOne(String code) {
        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());
        boolean changed = false;
        for (String literal : ObfuscationSupport.collectSensitiveLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target) || !isAscii(literal)) {
                continue;
            }
            code = code.replace(target,
                    ObfuscationSupport.buildTwoToOneCall(methodName, literal));
            changed = true;
        }
        if (!changed) {
            return code;
        }
        return ObfuscationSupport.injectHelperDeclaration(
                code,
                ObfuscationSupport.buildTwoToOneHelperDeclaration(methodName),
                true);
    }

    public static String byteArray(String code) {
        for (String literal : ObfuscationSupport.collectSensitiveLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) {
                continue;
            }
            StringBuilder replacement = new StringBuilder("new String(new byte[]{");
            for (int index = 0; index < literal.length(); index++) {
                if (index > 0) {
                    replacement.append(',');
                }
                replacement.append((int) literal.charAt(index));
            }
            replacement.append("})");
            code = code.replace(target, replacement.toString());
        }
        return code;
    }

    public static String javascriptCharCodes(String code) {
        for (String literal : ObfuscationSupport.collectSensitiveLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) {
                continue;
            }
            StringBuilder replacement = new StringBuilder("String.fromCharCode(");
            for (int index = 0; index < literal.length(); index++) {
                if (index > 0) {
                    replacement.append(',');
                }
                replacement.append((int) literal.charAt(index));
            }
            replacement.append(')');
            code = code.replace(target, replacement.toString());
        }
        return code;
    }

    private static String buildSplitConcat(String literal, int[] points, Random random) {
        String[] parts = new String[points.length + 1];
        int previous = 0;
        for (int index = 0; index < points.length; index++) {
            parts[index] = literal.substring(previous, points[index]);
            previous = points[index];
        }
        parts[points.length] = literal.substring(previous);

        switch (random.nextInt(3)) {
            case 0:
                StringBuilder direct = new StringBuilder();
                for (String part : parts) {
                    if (direct.length() > 0) {
                        direct.append('+');
                    }
                    direct.append('"').append(part).append('"');
                }
                return direct.toString();
            case 1:
                StringBuilder builder = new StringBuilder("new StringBuilder()");
                for (String part : parts) {
                    builder.append(".append(\"").append(part).append("\")");
                }
                return builder.append(".toString()").toString();
            default:
                StringBuilder concat = new StringBuilder();
                for (int index = 0; index < parts.length; index++) {
                    if (index == 0) {
                        concat.append('"').append(parts[index]).append('"');
                    } else {
                        concat.append(".concat(\"").append(parts[index]).append("\")");
                    }
                }
                return concat.toString();
        }
    }

    private static String buildGhostBitsHelperDeclaration(String methodName) {
        Random random = GenerationRandom.current();
        switch (random.nextInt(3)) {
            case 0:
                return "private static String " + methodName + "(String s){"
                        + "byte[] b=new byte[s.length()];"
                        + "for(int i=0;i<s.length();i++)b[i]=(byte)s.charAt(i);"
                        + "return new String(b);}";
            case 1:
                return "private static String " + methodName + "(String s){"
                        + "byte[] b=new byte[s.length()];int i=0;"
                        + "for(char c:s.toCharArray())b[i++]=(byte)c;"
                        + "return new String(b);}";
            default:
                return "private static String " + methodName + "(String s){"
                        + "java.io.ByteArrayOutputStream bos=new java.io.ByteArrayOutputStream();"
                        + "for(int i=0;i<s.length();i++)bos.write((byte)s.charAt(i));"
                        + "return new String(bos.toByteArray());}";
        }
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                return false;
            }
        }
        return true;
    }
}
