package org.leo.core.net.impl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class HttpCommunicationCookieTest extends TestCase {

    public void testLearnsSetCookieWithPathScope() throws Exception {
        AtomicReference<String> appCookie = new AtomicReference<>();
        AtomicReference<String> otherCookie = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/app; HttpOnly");
            write(exchange, "login");
        });
        server.createContext("/app/ping", exchange -> {
            appCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "app");
        });
        server.createContext("/other/ping", exchange -> {
            otherCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "other");
        });

        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpCommunication communication = new HttpCommunication(baseUrl + "/login", "POST", null, Proxy.NO_PROXY);

            communication.sendRequest(new byte[0]);
            communication.setRequestUrl(baseUrl + "/app/ping");
            communication.sendRequest(new byte[0]);
            communication.setRequestUrl(baseUrl + "/other/ping");
            communication.sendRequest(new byte[0]);

            assertEquals("sid=abc", appCookie.get());
            assertNull(otherCookie.get());
        } finally {
            server.stop(0);
        }
    }

    public void testConfiguredCookieWinsAndAutoCookieIsMerged() throws Exception {
        AtomicReference<String> nextCookie = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=server; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "extra=1; Path=/");
            write(exchange, "login");
        });
        server.createContext("/next", exchange -> {
            nextCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "next");
        });

        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", "sid=user; theme=dark");
            HttpCommunication communication = new HttpCommunication(baseUrl + "/login", "POST", headers, Proxy.NO_PROXY);

            communication.sendRequest(new byte[0]);
            communication.setRequestUrl(baseUrl + "/next");
            communication.sendRequest(new byte[0]);

            assertEquals("sid=user; theme=dark; extra=1", nextCookie.get());
        } finally {
            server.stop(0);
        }
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
