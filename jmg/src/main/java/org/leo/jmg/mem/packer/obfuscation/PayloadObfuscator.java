package org.leo.jmg.mem.packer.obfuscation;

import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;

import java.util.Base64;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责长 Base64 payload 的编码和分块。
 */
public final class PayloadObfuscator {

    private static final Pattern LONG_BASE64_LITERAL =
            Pattern.compile("\"([A-Za-z0-9+/=]{100,})\"");

    private PayloadObfuscator() {
    }

    public static String xor(String code) {
        Matcher matcher = LONG_BASE64_LITERAL.matcher(code);
        if (!matcher.find()) {
            return code;
        }

        Random random = GenerationRandom.current();
        int key = 1 + random.nextInt(126);
        Set<String> helperNames = new HashSet<String>();
        String methodName = ClassNameGenerator.randomFieldName(helperNames);
        String decodeMethodName = ClassNameGenerator.randomFieldName(helperNames);
        String encodeMethodName = ClassNameGenerator.randomFieldName(helperNames);

        matcher.reset();
        StringBuffer result = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String payload = matcher.group(1);
            String encoded = xorBase64(payload, key);
            if (encoded == null) {
                matcher.appendReplacement(result,
                        Matcher.quoteReplacement("\"" + payload + "\""));
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    methodName + "(\"" + encoded + "\"," + key + ")"));
            changed = true;
        }
        matcher.appendTail(result);
        if (!changed) {
            return code;
        }
        return ObfuscationSupport.injectHelperDeclaration(
                result.toString(),
                buildXorHelperDeclaration(methodName, decodeMethodName, encodeMethodName),
                false);
    }

    public static String chunk(String code) {
        Matcher matcher = LONG_BASE64_LITERAL.matcher(code);
        StringBuffer result = new StringBuffer();
        Random random = GenerationRandom.current();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(chunkString(matcher.group(1), random)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String packTwoToOne(String code) {
        Matcher matcher = LONG_BASE64_LITERAL.matcher(code);
        if (!matcher.find()) {
            return code;
        }
        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());
        matcher.reset();
        StringBuffer result = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    ObfuscationSupport.buildTwoToOneCall(methodName, matcher.group(1))));
            changed = true;
        }
        matcher.appendTail(result);
        if (!changed) {
            return code;
        }
        return ObfuscationSupport.injectHelperDeclaration(
                result.toString(),
                ObfuscationSupport.buildTwoToOneHelperDeclaration(methodName),
                true);
    }

    private static String xorBase64(String payload, int key) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (bytes[index] ^ key);
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String chunkString(String value, Random random) {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (offset < value.length()) {
            int length = 16 + random.nextInt(25);
            int end = Math.min(offset + length, value.length());
            if (result.length() > 0) {
                result.append('+');
            }
            result.append('"').append(value, offset, end).append('"');
            offset = end;
        }
        return result.toString();
    }

    private static String buildXorHelperDeclaration(
            String methodName, String decodeMethodName, String encodeMethodName) {
        Random random = GenerationRandom.current();
        if (random.nextInt(2) == 0) {
            return "private static byte[] " + decodeMethodName + "(String s)throws Exception{"
                    + "try{Class c=Class.forName(\"java.util.Base64\");"
                    + "Object d=c.getMethod(\"getDecoder\",new Class[0]).invoke(null,new Object[0]);"
                    + "return(byte[])d.getClass().getMethod(\"decode\",new Class[]{String.class}).invoke(d,new Object[]{s});"
                    + "}catch(Throwable e){}"
                    + "try{Class c=Class.forName(\"javax.xml.bind.DatatypeConverter\");"
                    + "return(byte[])c.getMethod(\"parseBase64Binary\",new Class[]{String.class}).invoke(null,new Object[]{s});"
                    + "}catch(Throwable e){}"
                    + "Class c=Class.forName(\"sun.misc.BASE64Decoder\");Object d=c.newInstance();"
                    + "return(byte[])c.getMethod(\"decodeBuffer\",new Class[]{String.class}).invoke(d,new Object[]{s});}"
                    + "private static String " + encodeMethodName + "(byte[] b)throws Exception{"
                    + "try{Class c=Class.forName(\"java.util.Base64\");"
                    + "Object e=c.getMethod(\"getEncoder\",new Class[0]).invoke(null,new Object[0]);"
                    + "return(String)e.getClass().getMethod(\"encodeToString\",new Class[]{byte[].class}).invoke(e,new Object[]{b});"
                    + "}catch(Throwable e){}"
                    + "try{Class c=Class.forName(\"javax.xml.bind.DatatypeConverter\");"
                    + "return(String)c.getMethod(\"printBase64Binary\",new Class[]{byte[].class}).invoke(null,new Object[]{b});"
                    + "}catch(Throwable e){}"
                    + "Class c=Class.forName(\"sun.misc.BASE64Encoder\");Object e=c.newInstance();"
                    + "return(String)c.getMethod(\"encode\",new Class[]{byte[].class}).invoke(e,new Object[]{b});}"
                    + "private static String " + methodName + "(String s,int k){try{"
                    + "byte[] b=" + decodeMethodName + "(s);"
                    + "for(int i=0;i<b.length;i++)b[i]=(byte)(b[i]^k);"
                    + "return " + encodeMethodName + "(b);"
                    + "}catch(Throwable e){return s;}}";
        }
        return "private static String " + methodName + "(String s,int k){"
                + "byte[] b=null;"
                + "try{Class c=Class.forName(\"java.util.Base64\");Object d=c.getMethod(\"getDecoder\",new Class[0]).invoke(null,new Object[0]);b=(byte[])d.getClass().getMethod(\"decode\",new Class[]{String.class}).invoke(d,new Object[]{s});}"
                + "catch(Throwable e){try{Class c=Class.forName(\"javax.xml.bind.DatatypeConverter\");b=(byte[])c.getMethod(\"parseBase64Binary\",new Class[]{String.class}).invoke(null,new Object[]{s});}"
                + "catch(Throwable e2){try{Class c=Class.forName(\"sun.misc.BASE64Decoder\");Object d=c.newInstance();b=(byte[])c.getMethod(\"decodeBuffer\",new Class[]{String.class}).invoke(d,new Object[]{s});}catch(Throwable e3){return s;}}}"
                + "for(int i=0;i<b.length;i++)b[i]=(byte)(b[i]^k);"
                + "try{Class c=Class.forName(\"java.util.Base64\");Object e=c.getMethod(\"getEncoder\",new Class[0]).invoke(null,new Object[0]);return(String)e.getClass().getMethod(\"encodeToString\",new Class[]{byte[].class}).invoke(e,new Object[]{b});}"
                + "catch(Throwable e){try{Class c=Class.forName(\"javax.xml.bind.DatatypeConverter\");return(String)c.getMethod(\"printBase64Binary\",new Class[]{byte[].class}).invoke(null,new Object[]{b});}"
                + "catch(Throwable e2){try{Class c=Class.forName(\"sun.misc.BASE64Encoder\");Object e=c.newInstance();return(String)c.getMethod(\"encode\",new Class[]{byte[].class}).invoke(e,new Object[]{b});}catch(Throwable e3){return s;}}}"
                + "}";
    }
}
