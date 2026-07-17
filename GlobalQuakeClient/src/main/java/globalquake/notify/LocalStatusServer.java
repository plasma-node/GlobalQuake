package globalquake.notify;

import com.sun.net.httpserver.HttpServer;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;

/**
 * Tiny localhost HTTP server exposing the nearby-quakes feed so a local agent can poll it over HTTP
 * instead of reading the file. Plain HTTP, bound to a loopback/local address by design — for remote
 * or HTTPS access, front it with a reverse proxy (nginx/caddy). No external deps: uses the JDK's
 * built-in com.sun.net.httpserver.
 */
public class LocalStatusServer {

    private final HttpServer server;

    private LocalStatusServer(HttpServer server) {
        this.server = server;
    }

    public static LocalStatusServer start(String bind, int port, File jsonlFile) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);

            server.createContext("/nearby", exchange -> {
                byte[] body = read(jsonlFile);
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                writeResponse(exchange, body);
            });

            server.createContext("/", exchange -> {
                byte[] body = "GlobalQuake local feed.\nGET /nearby — nearby-quakes JSONL feed.\n"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                writeResponse(exchange, body);
            });

            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            Logger.info("Local status server listening on http://%s:%d/nearby".formatted(bind, port));
            return new LocalStatusServer(server);
        } catch (IOException e) {
            Logger.error(e, "Failed to start local status server");
            return null;
        }
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        if (body.length == 0) {
            exchange.sendResponseHeaders(200, -1); // no body
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] read(File f) {
        try {
            return f.exists() ? Files.readAllBytes(f.toPath()) : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public void stop() {
        server.stop(0);
    }
}
