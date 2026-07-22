package org.leo.phpcore.disguise;

import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers protocol-v2 traffic profiles owned by the PHP runtime module. */
@Component
public final class PhpBuiltinDisguiseCatalog {

    public static final String JSON_API_ID = "inner_PHP_JSON_API_1.0.0";
    public static final String FORM_SYNC_ID = "inner_PHP_FORM_SYNC_1.0.0";

    public PhpBuiltinDisguiseCatalog(DisguiseManager disguiseManager) {
        for (Disguise disguise : createPresets()) {
            if (!disguiseManager.inStallDisguise(disguise)) {
                throw new IllegalStateException("PHP built-in disguise registration failed: "
                        + disguise.getDisguiseId());
            }
        }
    }

    public static List<Disguise> createPresets() {
        return List.of(jsonApiEnvelope(), formSync());
    }

    /** JSON API shaped envelope, suitable for both request and response layers. */
    static Disguise jsonApiEnvelope() {
        Disguise disguise = base(
                JSON_API_ID,
                "inner_PHP_JSON_API",
                "PHP JSON API 流量画像：协议数据位于 data 字段，附带常见状态、版本和时间字段",
                "application/json;charset=utf-8");
        disguise.setEncodeBody("public byte[] encode(java.util.HashMap params) throws Exception {\n" +
                "    byte[] json = org.leo.core.util.json.PortableJsonCodec.encode(params);\n" +
                "    org.leo.core.net.TransportLimits.requireMessageSize(json);\n" +
                "    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json);\n" +
                "    java.util.LinkedHashMap envelope = new java.util.LinkedHashMap();\n" +
                "    envelope.put(\"status\", \"ok\");\n" +
                "    envelope.put(\"version\", \"1.0\");\n" +
                "    envelope.put(\"timestamp\", java.lang.Long.valueOf(System.currentTimeMillis() / 1000L));\n" +
                "    envelope.put(\"data\", token);\n" +
                "    return org.leo.core.util.json.PortableJsonCodec.encode(envelope);\n" +
                "}");
        disguise.setDecodeBody("public java.util.HashMap decode(byte[] data) throws Exception {\n" +
                "    if (data == null || data.length > 22371674) throw new IllegalArgumentException(\"JSON API envelope too large\");\n" +
                "    java.util.Map envelope = org.leo.core.util.json.PortableJsonCodec.decode(data);\n" +
                "    if (!\"1.0\".equals(String.valueOf(envelope.get(\"version\")))) throw new IllegalArgumentException(\"JSON API version mismatch\");\n" +
                "    Object raw = envelope.get(\"data\");\n" +
                "    if (!(raw instanceof String) || ((String) raw).length() == 0) throw new IllegalArgumentException(\"JSON API data field missing\");\n" +
                "    if (((String) raw).length() > 22369624) throw new IllegalArgumentException(\"JSON API data field too large\");\n" +
                "    byte[] json = java.util.Base64.getUrlDecoder().decode((String) raw);\n" +
                "    org.leo.core.net.TransportLimits.requireMessageSize(json);\n" +
                "    return new java.util.HashMap(org.leo.core.util.json.PortableJsonCodec.decode(json));\n" +
                "}");
        disguise.setPhpEncodeBody("$json = json_encode(leo_wire_encode($payload), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);\n" +
                "if ($json === false) { throw new RuntimeException('JSON encode failed: ' . json_last_error_msg()); }\n" +
                "if (strlen($json) > 16777216) { throw new RuntimeException('JSON payload too large'); }\n" +
                "$token = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');\n" +
                "$result = json_encode(['status' => 'ok', 'version' => '1.0', 'timestamp' => time(), 'data' => $token], JSON_UNESCAPED_SLASHES);\n" +
                "if ($result === false) { throw new RuntimeException('Envelope encode failed: ' . json_last_error_msg()); }\n" +
                "return $result;");
        disguise.setPhpDecodeBody("if (!is_string($body) || strlen($body) > 22371674) { throw new RuntimeException('Invalid JSON API envelope size'); }\n" +
                "$envelope = json_decode($body, true);\n" +
                "if (!is_array($envelope) || !isset($envelope['version']) || (string)$envelope['version'] !== '1.0' || !isset($envelope['data']) || !is_string($envelope['data']) || $envelope['data'] === '') { throw new RuntimeException('Invalid JSON API envelope'); }\n" +
                "if (strlen($envelope['data']) > 22369624) { throw new RuntimeException('JSON API data too large'); }\n" +
                "$token = strtr($envelope['data'], '-_', '+/');\n" +
                "$remainder = strlen($token) % 4;\n" +
                "if ($remainder !== 0) { $token .= str_repeat('=', 4 - $remainder); }\n" +
                "$json = base64_decode($token, true);\n" +
                "if ($json === false) { throw new RuntimeException('Invalid JSON API data'); }\n" +
                "if (strlen($json) > 16777216) { throw new RuntimeException('JSON payload too large'); }\n" +
                "$decoded = json_decode($json, true);\n" +
                "if (!is_array($decoded)) { throw new RuntimeException('Invalid JSON payload: ' . json_last_error_msg()); }\n" +
                "return leo_wire_decode($decoded);");
        return disguise;
    }

    /** Form submission shaped envelope, useful for request layers and simple PHP endpoints. */
    static Disguise formSync() {
        Disguise disguise = base(
                FORM_SYNC_ID,
                "inner_PHP_FORM_SYNC",
                "PHP 表单同步流量画像：使用 application/x-www-form-urlencoded 承载版本、动作和数据字段",
                "application/x-www-form-urlencoded;charset=utf-8");
        disguise.setEncodeBody("public byte[] encode(java.util.HashMap params) throws Exception {\n" +
                "    byte[] json = org.leo.core.util.json.PortableJsonCodec.encode(params);\n" +
                "    org.leo.core.net.TransportLimits.requireMessageSize(json);\n" +
                "    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json);\n" +
                "    String body = \"action=sync&v=1&ts=\" + (System.currentTimeMillis() / 1000L)\n" +
                "            + \"&data=\" + java.net.URLEncoder.encode(token, \"UTF-8\");\n" +
                "    return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n" +
                "}");
        disguise.setDecodeBody("public java.util.HashMap decode(byte[] data) throws Exception {\n" +
                "    if (data == null || data.length > 22371674) throw new IllegalArgumentException(\"form envelope too large\");\n" +
                "    String body = new String(data, java.nio.charset.StandardCharsets.UTF_8);\n" +
                "    String[] pairs = body.split(\"&\");\n" +
                "    java.util.HashSet seen = new java.util.HashSet();\n" +
                "    String action = null;\n" +
                "    String version = null;\n" +
                "    String token = null;\n" +
                "    for (int i = 0; i < pairs.length; i++) {\n" +
                "        int separator = pairs[i].indexOf('=');\n" +
                "        String rawKey = separator < 0 ? pairs[i] : pairs[i].substring(0, separator);\n" +
                "        String rawValue = separator < 0 ? \"\" : pairs[i].substring(separator + 1);\n" +
                "        String key = java.net.URLDecoder.decode(rawKey, \"UTF-8\");\n" +
                "        String value = java.net.URLDecoder.decode(rawValue, \"UTF-8\");\n" +
                "        if (!seen.add(key)) throw new IllegalArgumentException(\"duplicate form field: \" + key);\n" +
                "        if (\"action\".equals(key)) action = value;\n" +
                "        else if (\"v\".equals(key)) version = value;\n" +
                "        else if (\"data\".equals(key)) token = value;\n" +
                "    }\n" +
                "    if (!\"sync\".equals(action) || !\"1\".equals(version)) throw new IllegalArgumentException(\"form envelope metadata mismatch\");\n" +
                "    if (token == null || token.length() == 0) throw new IllegalArgumentException(\"form data field missing\");\n" +
                "    if (token.length() > 22369624) throw new IllegalArgumentException(\"form data field too large\");\n" +
                "    byte[] json = java.util.Base64.getUrlDecoder().decode(token);\n" +
                "    org.leo.core.net.TransportLimits.requireMessageSize(json);\n" +
                "    return new java.util.HashMap(org.leo.core.util.json.PortableJsonCodec.decode(json));\n" +
                "}");
        disguise.setPhpEncodeBody("$json = json_encode(leo_wire_encode($payload), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);\n" +
                "if ($json === false) { throw new RuntimeException('JSON encode failed: ' . json_last_error_msg()); }\n" +
                "if (strlen($json) > 16777216) { throw new RuntimeException('JSON payload too large'); }\n" +
                "$token = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');\n" +
                "return http_build_query(['action' => 'sync', 'v' => '1', 'ts' => time(), 'data' => $token], '', '&', PHP_QUERY_RFC3986);");
        disguise.setPhpDecodeBody("if (!is_string($body) || strlen($body) > 22371674) { throw new RuntimeException('Invalid form envelope size'); }\n" +
                "$fields = []; $seen = [];\n" +
                "foreach (explode('&', $body) as $pair) {\n" +
                "    $parts = explode('=', $pair, 2);\n" +
                "    $key = urldecode($parts[0]);\n" +
                "    $value = count($parts) === 2 ? urldecode($parts[1]) : '';\n" +
                "    if (isset($seen[$key])) { throw new RuntimeException('Duplicate form field: ' . $key); }\n" +
                "    $seen[$key] = true; $fields[$key] = $value;\n" +
                "}\n" +
                "if (!isset($fields['action']) || $fields['action'] !== 'sync' || !isset($fields['v']) || $fields['v'] !== '1' || !isset($fields['data']) || $fields['data'] === '') { throw new RuntimeException('Invalid form envelope'); }\n" +
                "if (strlen($fields['data']) > 22369624) { throw new RuntimeException('Form data too large'); }\n" +
                "$token = strtr($fields['data'], '-_', '+/');\n" +
                "$remainder = strlen($token) % 4;\n" +
                "if ($remainder !== 0) { $token .= str_repeat('=', 4 - $remainder); }\n" +
                "$json = base64_decode($token, true);\n" +
                "if ($json === false) { throw new RuntimeException('Invalid form data'); }\n" +
                "if (strlen($json) > 16777216) { throw new RuntimeException('JSON payload too large'); }\n" +
                "$decoded = json_decode($json, true);\n" +
                "if (!is_array($decoded)) { throw new RuntimeException('Invalid JSON payload: ' . json_last_error_msg()); }\n" +
                "return leo_wire_decode($decoded);");
        return disguise;
    }

    private static Disguise base(String id, String name, String description, String contentType) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        disguise.setDisguiseName(name);
        disguise.setSchemaVersion(2);
        disguise.setProtocolVersion(2);
        disguise.setSupportedRuntimes(Set.of("php"));
        disguise.setHeaders(Map.of("Content-Type", contentType));
        disguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
        disguise.setCreateUserId("system");
        disguise.setVersion("1.0.0");
        disguise.setDescription(description);
        disguise.setRequirements(Map.of("php", Map.of(
                "minVersion", "5.6",
                "extensions", Set.of("json"),
                "functions", Set.of("base64_encode", "base64_decode", "json_encode", "json_decode",
                        "strlen", "strtr", "str_repeat", "http_build_query", "urldecode"))));
        return disguise;
    }
}
