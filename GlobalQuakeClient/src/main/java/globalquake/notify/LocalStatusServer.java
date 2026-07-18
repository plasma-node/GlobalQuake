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
 * Tiny localhost HTTP server exposing the quake feed, status/control, a map screenshot, and
 * test-injection endpoints — so a local agent can poll AND control a deployed headless box without
 * the Playground UI. Plain HTTP, loopback by design (front with a reverse proxy for remote/HTTPS).
 * No external deps (JDK com.sun.net.httpserver).
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

            // read-only feeds
            server.createContext("/nearby", ex -> json(ex, ntfy.nearbyJson()));
            server.createContext("/all", ex -> json(ex, ntfy.allJson()));
            server.createContext("/stations", ex -> json(ex, stationsJson()));
            server.createContext("/status", ex -> json(ex, statusJson(ntfy)));
            server.createContext("/log", LocalStatusServer::serveLog);
            server.createContext("/screenshot", ex -> self.screenshot(ex, ntfy));
            // control
            server.createContext("/sethome", LocalStatusServer::setHome);
            server.createContext("/togglentfy", ex -> toggleNtfy(ex, ntfy));
            server.createContext("/clearquakes", LocalStatusServer::clearQuakes);
            server.createContext("/restart", LocalStatusServer::restart);
            server.createContext("/shutdown", LocalStatusServer::shutdown);
            // test injection (longest-prefix: "...strong" resolves before "/testnearbyquake")
            server.createContext("/testnearbyquakestrong", ex -> {
                ntfy.injectTestQuake(true);
                text(ex, "strong test quake injected\n");
            });
            server.createContext("/testnearbyquake", ex -> {
                ntfy.injectTestQuake(false);
                text(ex, "test quake injected\n");
            });
            server.createContext("/cleantests", ex -> text(ex, "cleared %d test quake(s)\n".formatted(ntfy.clearTests())));
            server.createContext("/", LocalStatusServer::docs);

            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            Logger.info("Local status server on http://%s:%d/ (see / for the endpoint list)".formatted(bind, port));
            return self;
        } catch (java.net.BindException e) {
            Logger.error("############################################################");
            Logger.error("# LOCAL STATUS SERVER DID NOT START: %s:%d is already in use.".formatted(bind, port));
            Logger.error("# Another GlobalQuake instance is almost certainly still running.");
            Logger.error("# Stop it first (only ONE instance can hold the port). HTTP endpoints are OFF for this process.");
            Logger.error("############################################################");
            return null;
        } catch (IOException e) {
            Logger.error(e, "Failed to start local status server");
            return null;
        }
    }

    private static void docs(HttpExchange ex) throws IOException {
        text(ex, """
                GlobalQuake local API (loopback). All feeds are JSON; times are epoch ms + ISO.

                READ
                  GET /status      running state, home, station total/receiving, quake counts (JSON)
                  GET /nearby      quakes affecting your configured zones, incl. test quakes (JSON)
                  GET /all         all detected quakes: live + archived <24h, each with "near": bool (JSON)
                  GET /stations    every selected station: network, code, lat, lon, hasData (JSON)
                  GET /log         recent ERROR-level log lines (full logs = journalctl)
                  GET /screenshot  live map PNG — shakemap hexagons, P/S waves, stations, home marker,
                                   a shaking-expected banner + recent-quakes list. Defaults to HOME.
                                   Renders inline in a browser; add ?download=1 to save as a file.
                                   params:
                                     ?zoom=N          1=default view, higher=closer, lower=wider
                                     ?jumptonearest   centre on the biggest recent quake
                                     ?quake=<uuid>    centre + label a specific past/live quake (id from /all)
                                     ?lat=&lon=       manual centre
                                     ?stations=0      hide station dots
                                     ?faults=0|1      force fault lines off/on (default: app setting)
                                     ?download=1      send as a file download instead of inline
                                     ?fresh=1         bypass the 1-second render cache (force a new image)

                CONTROL
                  GET /sethome?lat=..&lon=..   set + persist home location (moves alerts live)
                  GET /togglentfy[?enabled=true|false]   turn phone push on/off (flips if no param)
                  GET /clearquakes             clear the archived-quake history (destructive)
                  GET /restart                 exit non-zero so systemd relaunches (re-selects stations
                                               around current home — use after /sethome when travelling)
                  GET /shutdown                exit cleanly (stays down under Restart=on-failure)

                TEST (verify the notify pipe without waiting for a real quake)
                  GET /testnearbyquake         inject a nearby SHAKING test alert (marked [TEST])
                  GET /testnearbyquakestrong   inject a STRONG test alert (also trips imminent)
                  GET /cleantests              remove all injected test quakes now
                """);
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

    private static String stationsJson() {
        GlobalQuake gq = GlobalQuake.instance;
        StringBuilder sb = new StringBuilder("[");
        if (gq != null) {
            boolean first = true;
            try {
                for (AbstractStation s : gq.getStationManager().getStations()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(String.format(Locale.ROOT,
                            "{\"network\":\"%s\",\"code\":\"%s\",\"channel\":\"%s\",\"lat\":%.4f,\"lon\":%.4f,\"hasData\":%b}",
                            esc(s.getNetworkCode()), esc(s.getStationCode()), esc(s.getChannelName()),
                            s.getLatitude(), s.getLongitude(), s.hasData()));
                }
            } catch (Exception ignored) {
            }
        }
        return sb.append(']').toString();
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
        text(ex, "home set to %.4f, %.4f (persisted). Note: station selection does NOT change until a /restart.\n".formatted(lat, lon));
    }

    private static void toggleNtfy(HttpExchange ex, NtfyService ntfy) throws IOException {
        String v = queryStr(ex, "enabled");
        boolean now = (v == null) ? ntfy.setPushEnabled(!ntfy.cfg().enabled) : ntfy.setPushEnabled(Boolean.parseBoolean(v));
        text(ex, "ntfy push " + (now ? "enabled" : "disabled") + " (runtime only; edit ntfy.properties to persist)\n");
    }

    private static void clearQuakes(HttpExchange ex) throws IOException {
        int n = 0;
        if (GlobalQuake.instance != null) {
            try {
                var arch = GlobalQuake.instance.getArchive().getArchivedQuakes();
                n = arch.size();
                arch.clear();
                GlobalQuake.instance.getArchive().saveArchive();
            } catch (Exception e) {
                Logger.error(e, "clearquakes failed");
            }
        }
        Logger.warn("Cleared %d archived quakes via /clearquakes".formatted(n));
        text(ex, "cleared %d archived quakes\n".formatted(n));
    }

    private static void restart(HttpExchange ex) throws IOException {
        text(ex, "restarting (systemd Restart=on-failure will relaunch)...\n");
        Logger.warn("Restart requested via /restart — exiting non-zero for the service manager to relaunch");
        exitAfterResponse(2);
    }

    private static void shutdown(HttpExchange ex) throws IOException {
        text(ex, "shutting down...\n");
        Logger.warn("Shutdown requested via /shutdown — exiting cleanly");
        exitAfterResponse(0);
    }

    private static void exitAfterResponse(int code) {
        // small delay so the HTTP response flushes before the JVM exits (shutdown hook saves archive)
        new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
            System.exit(code);
        }, "http-exit").start();
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
        // Default view is HOME. lat/lon override the centre; ?jumptonearest focuses the biggest
        // recent quake. zoom is an intuitive multiplier: 1 = default, higher = closer, lower = wider.
        double lat = queryDouble(ex, "lat", Double.NaN);
        double lon = queryDouble(ex, "lon", Double.NaN);
        double zoom = queryDouble(ex, "zoom", 1.0);
        boolean jump = queryStr(ex, "jumptonearest") != null;
        boolean stations = queryDouble(ex, "stations", 1) != 0;
        boolean fresh = queryDouble(ex, "fresh", 0) != 0;
        boolean download = queryDouble(ex, "download", 0) != 0;
        // ?faults=0|1 forces fault lines off/on for this shot; absent = honour the app setting.
        Boolean faultsOverride = queryStr(ex, "faults") == null ? null : queryDouble(ex, "faults", 1) != 0;

        // ?quake=<uuid> → centre + label a specific past (archived) or live quake
        String focusLabel = null;
        String quakeId = queryStr(ex, "quake");
        if (quakeId != null) {
            QuakeFocus qf = resolveQuake(quakeId);
            if (qf != null) {
                lat = qf.lat();
                lon = qf.lon();
                focusLabel = qf.label();
            }
        }

        String key = lat + "," + lon + "," + zoom + "," + jump + "," + stations + "," + focusLabel + "," + faultsOverride;
        long now = System.currentTimeMillis();

        byte[] png;
        if (!fresh && cachedShot != null && key.equals(cachedShotKey) && now - cachedShotAt < c.screenshotDebounceMs) {
            png = cachedShot; // reuse recent render (anti-DDoS)
        } else {
            png = GlobeScreenshotRenderer.renderPng(c.screenshotWidth, c.screenshotHeight, lat, lon, zoom, jump, stations, c.screenshotZoom, focusLabel, faultsOverride);
            cachedShot = png;
            cachedShotAt = now;
            cachedShotKey = key;
        }
        if (png.length == 0) {
            ex.sendResponseHeaders(500, -1);
            return;
        }
        if (download) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"globalquake.png\"");
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        write(ex, png);
    }

    private record QuakeFocus(double lat, double lon, String label) {
    }

    private static QuakeFocus resolveQuake(String uuid) {
        GlobalQuake gq = GlobalQuake.instance;
        if (gq == null) {
            return null;
        }
        try {
            for (globalquake.core.archive.ArchivedQuake a : gq.getArchive().getArchivedQuakes()) {
                if (a.getUuid().toString().equals(uuid)) {
                    return new QuakeFocus(a.getLat(), a.getLon(),
                            "M%.1f  %s  %.0fkm  (archived)".formatted(a.getMag(), rgn(a.getRegion()), a.getDepth()));
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (globalquake.core.earthquake.data.Earthquake e : gq.getEarthquakeAnalysis().getEarthquakes()) {
                if (e.getHypocenter() != null && e.getUuid().toString().equals(uuid)) {
                    return new QuakeFocus(e.getLat(), e.getLon(),
                            "M%.1f  %s  %.0fkm  (live)".formatted(e.getMag(), rgn(e.getRegion()), e.getDepth()));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String rgn(String r) {
        return r == null || r.isBlank() ? "?" : r;
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

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
        String v = queryStr(ex, key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String queryStr(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) {
            return null;
        }
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(key)) {
                return p.substring(i + 1);
            }
        }
        return null;
    }

    public void stop() {
        server.stop(0);
    }
}
