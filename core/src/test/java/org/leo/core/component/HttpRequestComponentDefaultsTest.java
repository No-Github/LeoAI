package org.leo.core.component;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestComponentDefaultsTest {

    @Test
    void suppliesBrowserHeadersWithoutOverridingCallerHeaders() throws Exception {
        HttpRequestComponent component = prepare(new HashMap());
        RecordingConnection connection = new RecordingConnection();
        Map headers = new HashMap();
        headers.put("user-agent", "Custom-Agent/1.0");
        headers.put("Accept", "application/json");

        for (Object entryObject : headers.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            connection.setRequestProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        component.applyBrowserRequestHeaders(connection, headers);

        assertEquals("Custom-Agent/1.0", connection.getRequestProperty("user-agent"));
        assertEquals("application/json", connection.getRequestProperty("Accept"));
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", connection.getRequestProperty("Accept-Language"));
    }

    @Test
    void suppliesRealisticUserAgentWhenHeadersAreAbsent() throws Exception {
        HttpRequestComponent component = prepare(new HashMap());
        RecordingConnection connection = new RecordingConnection();

        component.applyBrowserRequestHeaders(connection, null);

        assertTrue(connection.getRequestProperty("User-Agent").startsWith("Mozilla/5.0"));
        assertTrue(connection.getRequestProperty("User-Agent").contains("Chrome/"));
    }

    private HttpRequestComponent prepare(HashMap params) throws Exception {
        HttpRequestComponent component = new HttpRequestComponent();
        Field field = HttpRequestComponent.class.getDeclaredField("params");
        field.setAccessible(true);
        field.set(component, params);
        return component;
    }

    private static class RecordingConnection extends HttpURLConnection {
        RecordingConnection() throws Exception {
            super(new URL("http://127.0.0.1/"));
        }

        public void disconnect() {
        }

        public boolean usingProxy() {
            return false;
        }

        public void connect() {
        }
    }
}
