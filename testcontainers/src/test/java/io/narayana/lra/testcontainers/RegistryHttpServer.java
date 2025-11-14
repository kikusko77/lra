package io.narayana.lra.testcontainers;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class RegistryHttpServer {

    public static void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(9000), 0);

        server.createContext("/coordinators", exchange -> {
            String json = RegistryDatabase.findAllAsJson();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();
    }
}
