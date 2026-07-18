package globalquake.notify;

import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import org.tinylog.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads {@code .GlobalQuakeData/ntfy.properties}. Kept as its own file (not core Settings) so it
 * can be hand-edited over ssh on a headless box without a settings UI clobbering it, and so the
 * core/server build carries none of it. Writes a commented, disabled-by-default template on first
 * run.
 */
public class NtfyConfig {

    /** A monitored area. The special name "home" resolves to Settings.homeLat/homeLon at read time. */
    public record Zone(String name, double lat, double lon, double radiusKm) {
        public double lat() {
            return "home".equalsIgnoreCase(name) ? Settings.homeLat : lat;
        }

        public double lon() {
            return "home".equalsIgnoreCase(name) ? Settings.homeLon : lon;
        }
    }

    public boolean enabled;
    public String serverUrl = "https://ntfy.sh";
    public String topic = "";
    public String authToken = "";
    public final List<Zone> zones = new ArrayList<>();

    public int priorityNearby = 2;   // low
    public int priorityShaking = 3;  // default
    public int priorityStrong = 4;   // high
    public int priorityImminent = 5; // max
    public int priorityCancel = 1;   // min
    public String tagsNearby = "earthquake";
    public String tagsShaking = "warning";
    public String tagsStrong = "rotating_light";
    public String tagsImminent = "rotating_light,bangbang";

    public double nearbyMinMagnitude = 2.5;

    // "Shaking imminent" max-priority alert: fires once (per debounce window) when the S wave is
    // within `imminentSeconds` of a zone AND the shaking there is at least `imminentMinTier`.
    public boolean imminentEnabled = true;
    public int imminentSeconds = 10;
    public long imminentDebounceMs = 60000;
    public NotifyTier imminentMinTier = NotifyTier.SHAKING;

    public long notifyDelayMs = 4000;   // settle delay — NEARBY only when urgentImmediate=true
    public boolean urgentImmediate = true; // SHAKING/STRONG/IMMINENT skip the settle delay
    public double renotifyMagDelta = 0.7;
    public long rateFloorMs = 30000;
    public boolean cancelNotes = false;
    public long cancelGraceMs = 60000;

    public long allArchivedHours = 24;  // how far back /all includes archived quakes
    public long testTtlMs = 120000;     // test quakes auto-expire after this
    public int screenshotWidth = 900;
    public int screenshotHeight = 700;
    public double screenshotZoom = 4;   // projection scroll (smaller = more zoomed in)
    public long screenshotDebounceMs = 1000; // reuse a cached image within this window (anti-DDoS)

    public long fingerprintTimeToleranceMs = 30000;
    public double fingerprintDistanceKm = 100;

    public boolean jsonlEnabled = true;
    public long jsonlRetentionMinutes = 30;

    public boolean httpServerEnabled = false;
    public String httpServerBind = "127.0.0.1";
    public int httpServerPort = 8090;

    public boolean allowSimulated = false;

    public static NtfyConfig load() {
        NtfyConfig config = new NtfyConfig();
        File file = new File(GlobalQuake.mainFolder, "ntfy.properties");
        if (!file.exists()) {
            writeTemplate(file);
            Logger.info("Created ntfy config template at %s (disabled by default)".formatted(file.getAbsolutePath()));
            return config; // disabled
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            config.parse(p);
        } catch (Exception e) {
            Logger.error(e, "Failed to read ntfy.properties — notifications disabled");
            config.enabled = false;
        }
        return config;
    }

    private void parse(Properties p) {
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
        serverUrl = p.getProperty("serverUrl", serverUrl).trim();
        topic = p.getProperty("topic", "").trim();
        authToken = p.getProperty("authToken", "").trim();

        priorityNearby = intProp(p, "priorityNearby", priorityNearby);
        priorityShaking = intProp(p, "priorityShaking", priorityShaking);
        priorityStrong = intProp(p, "priorityStrong", priorityStrong);
        priorityImminent = intProp(p, "priorityImminent", priorityImminent);
        priorityCancel = intProp(p, "priorityCancel", priorityCancel);
        tagsNearby = p.getProperty("tagsNearby", tagsNearby).trim();
        tagsShaking = p.getProperty("tagsShaking", tagsShaking).trim();
        tagsStrong = p.getProperty("tagsStrong", tagsStrong).trim();
        tagsImminent = p.getProperty("tagsImminent", tagsImminent).trim();

        nearbyMinMagnitude = doubleProp(p, "nearbyMinMagnitude", nearbyMinMagnitude);

        imminentEnabled = Boolean.parseBoolean(p.getProperty("imminentEnabled", "true"));
        imminentSeconds = intProp(p, "imminentSeconds", imminentSeconds);
        imminentDebounceMs = longProp(p, "imminentDebounceMs", imminentDebounceMs);
        imminentMinTier = NotifyTier.parse(p.getProperty("imminentMinTier"), imminentMinTier);

        notifyDelayMs = longProp(p, "notifyDelayMs", notifyDelayMs);
        urgentImmediate = Boolean.parseBoolean(p.getProperty("urgentImmediate", "true"));
        renotifyMagDelta = doubleProp(p, "renotifyMagDelta", renotifyMagDelta);
        rateFloorMs = longProp(p, "rateFloorMs", rateFloorMs);
        cancelNotes = Boolean.parseBoolean(p.getProperty("cancelNotes", "false"));
        cancelGraceMs = longProp(p, "cancelGraceMs", cancelGraceMs);
        allArchivedHours = longProp(p, "allArchivedHours", allArchivedHours);
        testTtlMs = longProp(p, "testTtlMs", testTtlMs);
        screenshotWidth = intProp(p, "screenshotWidth", screenshotWidth);
        screenshotHeight = intProp(p, "screenshotHeight", screenshotHeight);
        screenshotZoom = doubleProp(p, "screenshotZoom", screenshotZoom);
        screenshotDebounceMs = longProp(p, "screenshotDebounceMs", screenshotDebounceMs);

        fingerprintTimeToleranceMs = longProp(p, "fingerprintTimeToleranceMs", fingerprintTimeToleranceMs);
        fingerprintDistanceKm = doubleProp(p, "fingerprintDistanceKm", fingerprintDistanceKm);

        jsonlEnabled = Boolean.parseBoolean(p.getProperty("jsonlEnabled", "true"));
        jsonlRetentionMinutes = longProp(p, "jsonlRetentionMinutes", jsonlRetentionMinutes);

        httpServerEnabled = Boolean.parseBoolean(p.getProperty("httpServerEnabled", "false"));
        httpServerBind = p.getProperty("httpServerBind", httpServerBind).trim();
        httpServerPort = intProp(p, "httpServerPort", httpServerPort);

        allowSimulated = Boolean.parseBoolean(p.getProperty("allowSimulated", "false"));

        parseZones(p.getProperty("zones", "home"));

        if (enabled && topic.isBlank()) {
            Logger.warn("ntfy is enabled but no topic is set — disabling notifications");
            enabled = false;
        }
    }

    private void parseZones(String raw) {
        zones.clear();
        for (String part : raw.split(";")) {
            String z = part.trim();
            if (z.isEmpty()) {
                continue;
            }
            if (z.equalsIgnoreCase("home")) {
                // radius only matters for the NEARBY tier; default a generous 300km around home
                zones.add(new Zone("home", 0, 0, 300));
                continue;
            }
            String[] f = z.split(":");
            if (f.length != 4) {
                Logger.warn("Ignoring malformed ntfy zone: '%s' (expected name:lat:lon:radiusKm)".formatted(z));
                continue;
            }
            try {
                zones.add(new Zone(f[0].trim(), Double.parseDouble(f[1].trim()), Double.parseDouble(f[2].trim()), Double.parseDouble(f[3].trim())));
            } catch (NumberFormatException e) {
                Logger.warn("Ignoring malformed ntfy zone: '%s'".formatted(z));
            }
        }
        if (zones.isEmpty()) {
            zones.add(new Zone("home", 0, 0, 300));
        }
    }

    private static int intProp(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longProp(Properties p, String key, long def) {
        try {
            return Long.parseLong(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double doubleProp(Properties p, String key, double def) {
        try {
            return Double.parseDouble(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeTemplate(File file) {
        String template = """
                # GlobalQuake ntfy notifications. Set enabled=true and a topic to activate.
                enabled=false
                serverUrl=https://ntfy.sh
                # Pick a long, unguessable topic — anyone who knows it can read your alerts.
                topic=
                # Optional access token for authenticated/self-hosted servers (leave blank for public ntfy.sh).
                authToken=

                # Monitored zones. "home" uses the home location set in GlobalQuake settings.
                # Extra zones: name:lat:lon:radiusKm ; separate multiple with semicolons.
                zones=home
                #zones=home;cabin:35.20:-120.60:150

                # ntfy priority per tier (1=min .. 5=max/urgent)
                # nearby=low, shaking=default, strong=high, imminent=max(reserved for "about to hit")
                priorityNearby=2
                priorityShaking=3
                priorityStrong=4
                priorityImminent=5
                priorityCancel=1
                tagsNearby=earthquake
                tagsShaking=warning
                tagsStrong=rotating_light
                tagsImminent=rotating_light,bangbang

                # A low-priority "nearby" alert is only sent for quakes at least this magnitude.
                nearbyMinMagnitude=2.5

                # "Shaking imminent" = the max-priority alert. Fires once (per debounce) when the S
                # wave is within imminentSeconds of a zone AND the shaking there reaches at least
                # imminentMinTier (SHAKING or STRONG). Set imminentMinTier=STRONG to only get it for
                # strong shaking.
                imminentEnabled=true
                imminentSeconds=10
                imminentDebounceMs=60000
                imminentMinTier=SHAKING

                # Debounce / dedupe
                notifyDelayMs=4000
                # SHAKING/STRONG/IMMINENT skip the settle delay above (send ASAP); NEARBY still waits.
                urgentImmediate=true
                renotifyMagDelta=0.7
                rateFloorMs=30000
                cancelNotes=false
                cancelGraceMs=60000

                # /all endpoint: how far back to include archived quakes. Test quakes auto-expire.
                allArchivedHours=24
                testTtlMs=120000
                # /screenshot flat-map size + zoom (smaller zoom = more zoomed in) + cache window.
                screenshotWidth=900
                screenshotHeight=700
                screenshotZoom=4
                screenshotDebounceMs=1000

                # Physical-quake matching (survives UUID churn / re-detections)
                fingerprintTimeToleranceMs=30000
                fingerprintDistanceKm=100

                # Jarvis / agent feed
                jsonlEnabled=true
                jsonlRetentionMinutes=30

                # Local HTTP feed: GET http://<bind>:<port>/nearby returns the JSONL. Loopback only
                # by design — for remote/HTTPS access put a reverse proxy in front. Works even if
                # push notifications above are disabled.
                httpServerEnabled=false
                httpServerBind=127.0.0.1
                httpServerPort=8090

                # Set true ONLY to test with Playground-mode (simulated) quakes
                allowSimulated=false
                """;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(template.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.error(e, "Failed to write ntfy config template");
        }
    }
}
