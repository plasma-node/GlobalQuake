package globalquake.notify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import globalquake.core.station.AbstractStation;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Tiny localhost HTTP server exposing the quake feed, status, log, a map screenshot, a way to set
 * home, and test-injection endpoints — so a local agent can poll over HTTP and a deployed headless
 * box can be exercised/controlled without the Playground UI. Plain HTTP, loopback by design.
 * No external deps (JDK com.sun.net.httpserver).
 * <p>
 * Endpoints: /nearby, /all (JSON), /status (JSON), /log (recent error log), /screenshot (PNG,
 * debounced), /sethome?lat&amp;lon (persist home), /testnearbyquake[strong] (inject a test alert).
 */
public class LocalStatusServer {

    private final HttpServer server;

    // debounced screenshot cache (anti self-DDoS)
    private volatile byte[] cachedShot;
    private volatile long cachedShotAt;
    private volatile String cachedShotKey = "";

    private LocalStatusServer(HttpServer server) {
        this.server = server;
    }

    public static LocalStatusServer start(String bind, int port, NtfyService ntfy) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            LocalStatusServer self = new LocalStatusServer(server);

            server.createContext("/nearby", ex -> json(ex, ntfy.nearbyJson()));
            server.createContext("/all", ex -> json(ex, ntfy.allJson()));
            server.createContext("/status", ex -> json(ex, statusJson(ntfy)));
            server.createContext("/log", LocalStatusServer::serveLog);
            server.createContext("/sethome", LocalStatusServer::setHome);
            server.createContext("/screenshot", ex -> self.screenshot(ex, ntfy));
            // longest-prefix match: "...strong" resolves to its own context, not "/testnearbyquake"
            server.createContext("/testnearbyquakestrong", ex -> {
                ntfy.injectTestQuake(true);
                text(ex, "strong test quake injected\n");
            });
            server.createContext("/testnearbyquake", ex -> {
                ntfy.injectTestQuake(false);
                text(ex, "test quake injected\n");
            });
            server.createContext("/", ex -> text(ex, """
                    GlobalQuake local feed:
                      /status     running state, station + quake counts, home (JSON)
                      /nearby     quakes affecting your zones (JSON)
                      /all        all detected quakes, live + recent archived (JSON)
                      /log        recent error log
                      /screenshot map PNG (optional ?lat=&lon=&zoom=&fresh=1)
                      /sethome?lat=..&lon=..   set + persist home location
                      /testnearbyquake        inject a nearby test alert
                      /testnearbyquakestrong  inject a strong test alert
                    """));

            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            Logger.info("Local status server on http://%s:%d/ (/status /nearby /all /screenshot /sethome /log /testnearbyquake[strong])".formatted(bind, port));
            return self;
        } catch (IOException e) {
            Logger.error(e, "Failed to start local status server");
            return null;
        }
    }

    private static String statusJson(NtfyService ntfy) {
        GlobalQuake gq = GlobalQuake.instance;
        int total = 0, receiving = 0, live = 0, archived = 0;
        boolean sim = false;
        if (gq != null) {
            try {
                for (AbstractStation s : gq.getStationManager().getStations()) {
                    total++;
                    if (s.hasData()) {
                        receiving++;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                live = gq.getEarthquakeAnalysis().getEarthquakes().size();
            } catch (Exception ignored) {
            }
            try {
                archived = gq.getArchive().getArchivedQuakes().size();
            } catch (Exception ignored) {
            }
            try {
                sim = gq.isSimulation();
            } catch (Exception ignored) {
            }
        }
        return String.format(Locale.ROOT,
                "{\"running\":%b,\"simulation\":%b,\"home\":{\"lat\":%.4f,\"lon\":%.4f},\"stations\":{\"total\":%d,\"receivingData\":%d},\"quakes\":{\"live\":%d,\"archived\":%d},\"ntfyPushEnabled\":%b}",
                gq != null, sim, Settings.homeLat, Settings.homeLon, total, receiving, live, archived, ntfy.cfg().enabled);
    }

    private static void setHome(HttpExchange ex) throws IOException {
        double lat = queryDouble(ex, "lat", Double.NaN);
        double lon = queryDouble(ex, "lon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            text(ex, "usage: /sethome?lat=<deg>&lon=<deg>\n");
            return;
        }
        Settings.homeLat = lat;
        Settings.homeLon = lon;
        Settings.save();
        Logger.info("Home location set via /sethome to %.4f, %.4f".formatted(lat, lon));
        text(ex, "home set to %.4f, %.4f (persisted)\n".formatted(lat, lon));
    }

    private static void serveLog(HttpExchange ex) throws IOException {
        File f = new File(GlobalQuake.mainFolder, "logs/latest.log");
        if (!f.exists()) {
            text(ex, "(no error log yet — this file only records ERROR-level events; use journalctl for full logs)\n");
            return;
        }
        text(ex, tail(f, 64 * 1024));
    }

    private void screenshot(HttpExchange ex, NtfyService ntfy) throws IOException {
        NtfyConfig c = ntfy.cfg();
        double lat = queryDouble(ex, "lat", Settings.homeLat);
        double lon = queryDouble(ex, "lon", Settings.homeLon);
        double zoom = queryDouble(ex, "zoom", c.screenshotZoom);
        boolean fresh = queryDouble(ex, "fresh", 0) != 0;
        String key = lat + "," + lon + "," + zoom;
        long now = System.currentTimeMillis();

        byte[] png;
        if (!fresh && cachedShot != null && key.equals(cachedShotKey) && now - cachedShotAt < c.screenshotDebounceMs) {
            png = cachedShot; // reuse recent render (anti-DDoS)
        } else {
            png = FlatMapRenderer.renderPng(c.screenshotWidth, c.screenshotHeight, lat, lon, zoom, Settings.homeLat, Settings.homeLon);
            cachedShot = png;
            cachedShotAt = now;
            cachedShotKey = key;
        }
        if (png.length == 0) {
            ex.sendResponseHeaders(500, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        write(ex, png);
    }

    private static String tail(File f, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - maxBytes);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(could not read log: " + e.getMessage() + ")\n";
        }
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        write(ex, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void text(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        write(ex, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpExchange ex, byte[] body) throws IOException {
        if (body.length == 0) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static double queryDouble(HttpExchange ex, String key, double def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) {
            return def;
        }
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(key)) {
                try {
                    return Double.parseDouble(p.substring(i + 1));
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    public void stop() {
        server.stop(0);
    }
}
