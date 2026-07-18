package globalquake.notify;

import globalquake.client.GlobalQuakeLocal;
import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import globalquake.core.alert.Warnable;
import globalquake.core.archive.ArchivedQuake;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.events.GlobalQuakeEventListener;
import globalquake.core.events.specific.QuakeArchiveEvent;
import globalquake.core.events.specific.QuakeCreateEvent;
import globalquake.core.events.specific.QuakeRemoveEvent;
import globalquake.core.events.specific.QuakeUpdateEvent;
import globalquake.core.geo.taup.TauPTravelTimeCalculator;
import globalquake.core.intensity.IntensityScale;
import globalquake.core.intensity.IntensityScales;
import globalquake.core.intensity.Level;
import globalquake.events.GlobalQuakeLocalEventListener;
import globalquake.events.specific.AlertIssuedEvent;
import globalquake.utils.GeoUtils;
import org.tinylog.Logger;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes tiered earthquake notifications to an ntfy server and maintains a JSONL feed of quakes
 * currently affecting the configured zones (for an external agent to read). Reuses the client's own
 * alert decisions: the "nearby" tier comes from {@code AlertManager}'s {@link AlertIssuedEvent}, and
 * the shaking/strong tiers use the same home-PGA formulas as {@code SoundsService}.
 * <p>
 * Fully additive and headless-safe: never touches Swing, does all HTTP/file I/O on its own thread,
 * and is an inert no-op when {@code ntfy.properties} is absent or disabled.
 */
public class NtfyService {

    private final NtfyConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService tickService;
    private final LocalStatusServer statusServer;
    private String endpoint;

    // fingerprint -> tracker; guarded by `lock`
    private final Map<String, QuakeTracker> trackers = new LinkedHashMap<>();
    private final Object lock = new Object();

    private String lastJsonl = "";

    public NtfyService() {
        this.config = NtfyConfig.load();

        // The tracker/tick machinery (and thus the JSONL feed + local status server) runs whenever
        // EITHER push notifications OR the status server is enabled — you can have the local feed
        // without pushing to your phone.
        boolean active = config.enabled || config.httpServerEnabled;
        if (!active) {
            Logger.info("ntfy push AND local status server are both OFF — set enabled=true and/or httpServerEnabled=true in %s"
                    .formatted(new File(GlobalQuake.mainFolder, "ntfy.properties").getAbsolutePath()));
            this.httpClient = null;
            this.tickService = null;
            this.statusServer = null;
            return;
        }

        if (config.enabled) {
            // Normalise so a trailing slash on serverUrl (or a leading slash on topic) can't produce
            // a "https://ntfy.sh//topic" URL — ntfy answers that with a 307 redirect the client would
            // otherwise drop. followRedirects is belt-and-suspenders for self-hosted setups.
            this.endpoint = config.serverUrl.replaceAll("/+$", "") + "/" + config.topic.replaceAll("^/+", "");
            Logger.info("ntfy notifications enabled → %s (%d zone(s))".formatted(endpoint, config.zones.size()));
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } else {
            Logger.info("ntfy push disabled; local status/JSONL feed active");
            this.httpClient = null;
        }

        registerListeners();

        this.tickService = Executors.newSingleThreadScheduledExecutor();
        this.tickService.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);

        this.statusServer = config.httpServerEnabled
                ? LocalStatusServer.start(config.httpServerBind, config.httpServerPort, this)
                : null;
    }

    NtfyConfig cfg() {
        return config;
    }

    /** Runtime toggle of phone push (in-memory; reverts to the file value on restart). The status
     *  feed keeps running either way. */
    boolean setPushEnabled(boolean enabled) {
        config.enabled = enabled;
        Logger.info("ntfy push %s via endpoint".formatted(enabled ? "ENABLED" : "DISABLED"));
        return config.enabled;
    }

    /** True if the epicenter is within any configured zone's radius (used for the /all "near" flag). */
    boolean isNear(double lat, double lon) {
        for (NtfyConfig.Zone z : config.zones) {
            if (GeoUtils.greatCircleDistance(lat, lon, z.lat(), z.lon()) <= z.radiusKm()) {
                return true;
            }
        }
        return false;
    }

    private boolean ignoreSource() {
        return GlobalQuake.instance.isSimulation() && !config.allowSimulated;
    }

    private void registerListeners() {
        GlobalQuake.instance.getEventHandler().registerEventListener(new GlobalQuakeEventListener() {
            @Override
            public void onQuakeCreate(QuakeCreateEvent event) {
                if (!ignoreSource()) {
                    upsert(event.earthquake());
                }
            }

            @Override
            public void onQuakeUpdate(QuakeUpdateEvent event) {
                if (!ignoreSource()) {
                    upsert(event.earthquake());
                }
            }

            @Override
            public void onQuakeRemove(QuakeRemoveEvent event) {
                if (!ignoreSource()) {
                    markRemoved(event.earthquake(), false);
                }
            }

            @Override
            public void onQuakeArchive(QuakeArchiveEvent event) {
                if (!ignoreSource()) {
                    markRemoved(event.earthquake(), true);
                }
            }
        });

        GlobalQuakeLocal.instance.getLocalEventHandler().registerEventListener(new GlobalQuakeLocalEventListener() {
            @Override
            public void onWarningIssued(AlertIssuedEvent event) {
                if (ignoreSource()) {
                    return;
                }
                Warnable warnable = event.warnable();
                if (warnable instanceof Earthquake earthquake) {
                    synchronized (lock) {
                        QuakeTracker t = upsertLocked(earthquake);
                        t.floorTier = NotifyTier.max(t.floorTier, NotifyTier.NEARBY);
                    }
                }
            }
        });
    }

    private void upsert(Earthquake earthquake) {
        synchronized (lock) {
            upsertLocked(earthquake);
        }
    }

    private QuakeTracker upsertLocked(Earthquake earthquake) {
        long now = System.currentTimeMillis();
        QuakeTracker t = find(earthquake);
        if (t == null) {
            String fp = "fp-%d-%.2f-%.2f".formatted(earthquake.getOrigin() / 1000, earthquake.getLat(), earthquake.getLon());
            // in the rare event of a fingerprint-string collision, keep it unique
            while (trackers.containsKey(fp)) {
                fp += "x";
            }
            t = new QuakeTracker(fp, now);
            trackers.put(fp, t);
        }
        t.currentUuid = earthquake.getUuid();
        t.lat = earthquake.getLat();
        t.lon = earthquake.getLon();
        t.depth = earthquake.getDepth();
        t.mag = earthquake.getMag();
        t.origin = earthquake.getOrigin();
        t.region = earthquake.getRegion();
        t.updatedAt = now;
        t.removedAt = 0; // a fresh sighting resurrects a quake that was briefly removed (UUID churn)
        t.archived = false;
        return t;
    }

    /** Match an existing tracker by UUID, else fuzzily by origin time + epicenter (same physical quake). */
    private QuakeTracker find(Earthquake earthquake) {
        for (QuakeTracker t : trackers.values()) {
            if (earthquake.getUuid().equals(t.currentUuid)) {
                return t;
            }
        }
        for (QuakeTracker t : trackers.values()) {
            if (Math.abs(earthquake.getOrigin() - t.origin) <= config.fingerprintTimeToleranceMs
                    && GeoUtils.greatCircleDistance(earthquake.getLat(), earthquake.getLon(), t.lat, t.lon) <= config.fingerprintDistanceKm) {
                return t;
            }
        }
        return null;
    }

    private void markRemoved(Earthquake earthquake, boolean archived) {
        synchronized (lock) {
            QuakeTracker t = find(earthquake);
            if (t != null) {
                t.removedAt = System.currentTimeMillis();
                t.archived = archived;
            }
        }
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            double thFelt = IntensityScales.INTENSITY_SCALES[Settings.shakingLevelScale].getLevels().get(Settings.shakingLevelIndex).getPga();
            double thStrong = IntensityScales.INTENSITY_SCALES[Settings.strongShakingLevelScale].getLevels().get(Settings.strongShakingLevelIndex).getPga();
            long retentionMs = config.jsonlRetentionMinutes * 60_000L;

            List<QuakeTracker> snapshot;
            synchronized (lock) {
                for (QuakeTracker t : trackers.values()) {
                    t.currentTier = computeTier(t, thFelt, thStrong);
                    if (config.enabled) {
                        processSends(t, now);
                    }
                }
                trackers.values().removeIf(t ->
                        ((t.removedAt > 0 || t.archived) && now - Math.max(t.updatedAt, t.removedAt) >= retentionMs)
                        || (t.test && now - t.firstSeen >= config.testTtlMs));
                snapshot = new ArrayList<>(trackers.values());
            }

            writeFeed(snapshot);
        } catch (Exception e) {
            Logger.error(e, "ntfy tick failed");
        }
    }

    /** Injects a synthetic TEST quake near home so the whole notify + feed path can be exercised
     *  on a deployed headless box (no Playground). Marked test=true → "[TEST]" in the notification
     *  and the JSON. Strong variant also trips the max-priority imminent path. Auto-expires. */
    public void injectTestQuake(boolean strong) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            String fp = "test-" + now;
            QuakeTracker t = new QuakeTracker(fp, now);
            t.test = true;
            t.currentUuid = UUID.randomUUID();
            t.lat = Settings.homeLat + 0.36; // ~40 km north of home, for a realistic S-wave ETA
            t.lon = Settings.homeLon;
            t.depth = 10;
            t.mag = strong ? 7.0 : 4.5;
            t.origin = GlobalQuake.instance.currentTimeMillis();
            t.region = "[TEST] near home";
            t.forcedTier = strong ? NotifyTier.STRONG : NotifyTier.SHAKING;
            trackers.put(fp, t);
        }
        Logger.info("Injected %s test quake".formatted(strong ? "STRONG" : "nearby"));
    }

    private NotifyTier computeTier(QuakeTracker t, double thFelt, double thStrong) {
        if (t.forcedTier != null) { // test quake: fixed tier, real coords → real distance/ETA
            NtfyConfig.Zone z = config.zones.isEmpty() ? null : config.zones.get(0);
            t.bestZone = z != null ? z.name() : "home";
            t.bestZoneLat = z != null ? z.lat() : Settings.homeLat;
            t.bestZoneLon = z != null ? z.lon() : Settings.homeLon;
            t.bestDistKm = GeoUtils.greatCircleDistance(t.lat, t.lon, t.bestZoneLat, t.bestZoneLon);
            t.bestPga = t.forcedTier.atLeast(NotifyTier.STRONG) ? thStrong : thFelt; // representative, for intensity
            return t.forcedTier;
        }
        boolean magOkForNearby = t.mag >= config.nearbyMinMagnitude;
        NotifyTier bestTier = NotifyTier.NONE;
        NtfyConfig.Zone bestZone = null;
        double bestDist = 0, bestPga = 0;

        for (NtfyConfig.Zone z : config.zones) {
            double distKm = GeoUtils.greatCircleDistance(t.lat, t.lon, z.lat(), z.lon());
            double distGEO = GeoUtils.geologicalDistance(t.lat, t.lon, -t.depth, z.lat(), z.lon(), 0.0);
            double pga = GeoUtils.pgaFunction(t.mag, distGEO, t.depth);

            NotifyTier zt = NotifyTier.NONE;
            if (distKm <= z.radiusKm() && magOkForNearby) {
                zt = NotifyTier.NEARBY;
            }
            if (pga >= thFelt) {
                zt = NotifyTier.max(zt, NotifyTier.SHAKING);
            }
            if (pga >= thStrong) {
                zt = NotifyTier.max(zt, NotifyTier.STRONG);
            }

            if (zt.ordinal() > bestTier.ordinal() || (zt == bestTier && pga > bestPga)) {
                bestTier = zt;
                bestZone = z;
                bestDist = distKm;
                bestPga = pga;
            }
        }

        NtfyConfig.Zone info = bestZone != null ? bestZone : (config.zones.isEmpty() ? null : config.zones.get(0));
        t.bestZone = info != null ? info.name() : "home";
        t.bestZoneLat = info != null ? info.lat() : Settings.homeLat;
        t.bestZoneLon = info != null ? info.lon() : Settings.homeLon;
        t.bestDistKm = bestZone != null ? bestDist
                : (info != null ? GeoUtils.greatCircleDistance(t.lat, t.lon, info.lat(), info.lon()) : 0);
        t.bestPga = bestPga;

        // The warning-driven "nearby" floor still respects the nearby magnitude gate.
        return NotifyTier.max(bestTier, magOkForNearby ? t.floorTier : NotifyTier.NONE);
    }

    private void processSends(QuakeTracker t, long now) {
        if (t.removedAt > 0) {
            if (config.cancelNotes && t.notified && !t.cancelSent && !t.archived
                    && now - t.removedAt >= config.cancelGraceMs) {
                sendCancel(t);
                t.cancelSent = true;
            }
            return;
        }

        checkImminent(t, now);

        NotifyTier tier = t.currentTier;
        if (tier == NotifyTier.NONE) {
            return;
        }

        if (!t.notified) {
            // SHAKING and above skip the settle delay (urgent) — only NEARBY waits for magnitude
            // to firm up, which avoids spamming borderline in-area quakes.
            boolean urgent = config.urgentImmediate && tier.atLeast(NotifyTier.SHAKING);
            if (urgent || now - t.firstSeen >= config.notifyDelayMs) {
                send(t, tier);
                t.notified = true;
                t.lastNotifiedTier = tier;
                t.lastNotifiedMag = t.mag;
                t.lastSentAt = now;
            }
            return;
        }

        boolean tierChanged = tier != t.lastNotifiedTier;
        boolean bigMagChange = Math.abs(t.mag - t.lastNotifiedMag) >= config.renotifyMagDelta;
        if (tierChanged || bigMagChange) {
            boolean upgrade = tier.ordinal() > t.lastNotifiedTier.ordinal();
            if (upgrade || now - t.lastSentAt >= config.rateFloorMs) {
                send(t, tier);
                t.lastNotifiedTier = tier;
                t.lastNotifiedMag = t.mag;
                t.lastSentAt = now;
            }
        }
    }

    /**
     * The max-priority "shaking imminent" alert — fires once per debounce window when the S wave is
     * within imminentSeconds of the best zone and the expected shaking there is at least
     * imminentMinTier. Independent of the normal first-notify delay (this is EEW — it must be fast).
     */
    private void checkImminent(QuakeTracker t, long now) {
        if (!config.imminentEnabled || !t.currentTier.atLeast(config.imminentMinTier)) {
            return;
        }
        double sTravel = TauPTravelTimeCalculator.getSWaveTravelTime(t.depth, TauPTravelTimeCalculator.toAngle(t.bestDistKm));
        if (sTravel < 0) {
            return;
        }
        double etaS = sTravel - (GlobalQuake.instance.currentTimeMillis() - t.origin) / 1000.0;
        if (etaS > config.imminentSeconds || etaS < -5) {
            return; // not imminent yet, or the S wave already swept past
        }
        if (now - t.lastImminentAt < config.imminentDebounceMs) {
            return; // debounced
        }
        t.lastImminentAt = now;
        send(t, NotifyTier.IMMINENT);
    }

    private void send(QuakeTracker t, NotifyTier tier) {
        String label = switch (tier) {
            case IMMINENT -> "Shaking imminent";
            case STRONG -> "Strong shaking expected";
            case SHAKING -> "Shaking expected";
            default -> "Earthquake nearby";
        };
        int priority = switch (tier) {
            case IMMINENT -> config.priorityImminent;
            case STRONG -> config.priorityStrong;
            case SHAKING -> config.priorityShaking;
            default -> config.priorityNearby;
        };
        String tags = switch (tier) {
            case IMMINENT -> config.tagsImminent;
            case STRONG -> config.tagsStrong;
            case SHAKING -> config.tagsShaking;
            default -> config.tagsNearby;
        };

        double angle = TauPTravelTimeCalculator.toAngle(t.bestDistKm);
        double pTravel = TauPTravelTimeCalculator.getPWaveTravelTime(t.depth, angle);
        double sTravel = TauPTravelTimeCalculator.getSWaveTravelTime(t.depth, angle);
        double age = (GlobalQuake.instance.currentTimeMillis() - t.origin) / 1000.0;

        String title = "%sM%.1f - %s".formatted(t.test ? "[TEST] " : "", t.mag, label);
        StringBuilder body = new StringBuilder();
        if (t.test) {
            body.append("** TEST NOTIFICATION **\n");
        }
        if (sTravel >= 0) {
            body.append("ETA ").append(formatEta(sTravel - age)).append('\n'); // time until shaking (S wave)
        }
        // Expected shaking intensity at the best zone, on the user's selected scale (MMI/Shindo).
        IntensityScale scale = IntensityScales.getIntensityScale();
        Level level = scale.getLevel(t.bestPga);
        if (level != null) {
            body.append("Est. intensity %s %s\n".formatted(level.getFullName(), scale.getNameShort()));
        }
        body.append(t.region == null || t.region.isBlank() ? "Unknown region" : t.region).append('\n');
        body.append("Magnitude %.1f, depth %.0f km\n".formatted(t.mag, t.depth));
        body.append("%.0f mi from %s".formatted(kmToMiles(t.bestDistKm), t.bestZone));
        if (pTravel >= 0 || sTravel >= 0) {
            body.append('\n').append("P wave %s, S wave %s".formatted(arrivalText(pTravel, age), arrivalText(sTravel, age)));
        }
        post(title, body.toString(), priority, tags);
    }

    /** Compact ETA for the body header: at most two digits — "Ns" under 100s, else floored to "Nm". */
    private static String formatEta(double etaSeconds) {
        long s = (long) Math.floor(etaSeconds);
        if (s <= 0) {
            return "now";
        }
        return s <= 99 ? s + "s" : (s / 60) + "m";
    }

    private static double kmToMiles(double km) {
        return km * 0.621371;
    }

    private static String arrivalText(double travel, double age) {
        if (travel < 0) {
            return "n/a";
        }
        long eta = Math.round(travel - age);
        return eta > 0 ? "in " + eta + "s" : "now";
    }

    private void sendCancel(QuakeTracker t) {
        post("Earthquake alert cleared",
                "%s (M%.1f) is no longer being reported".formatted(
                        t.region == null || t.region.isBlank() ? "Earthquake" : t.region, t.mag),
                config.priorityCancel, "white_check_mark");
    }

    private void post(String title, String body, int priority, String tags) {
        try {
            // HTTP header values must be ASCII (java.net.http rejects anything else). The message
            // BODY is sent as UTF-8 so accented region names etc. survive there; only headers are
            // sanitised.
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Title", asciiHeader(title))
                    .header("Priority", String.valueOf(priority))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (tags != null && !tags.isBlank()) {
                b.header("Tags", asciiHeader(tags));
            }
            if (!config.authToken.isBlank()) {
                b.header("Authorization", "Bearer " + config.authToken);
            }
            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                Logger.warn("ntfy POST returned HTTP %d: %s".formatted(resp.statusCode(), resp.body()));
            } else {
                Logger.info("ntfy sent: %s".formatted(title));
            }
        } catch (Exception e) {
            Logger.error(e, "Failed to send ntfy notification");
        }
    }

    private void writeFeed(List<QuakeTracker> snapshot) {
        if (!config.jsonlEnabled) {
            return;
        }
        String content = buildNearbyJson(snapshot);
        if (content.equals(lastJsonl)) {
            return; // nothing changed — avoid needless disk churn
        }
        lastJsonl = content;

        File target = new File(GlobalQuake.mainFolder, "nearby_quakes.json");
        File tmp = new File(GlobalQuake.mainFolder, "nearby_quakes.json.tmp");
        try {
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Logger.error(e, "Failed to write nearby_quakes.json");
        }
    }

    /** JSON array of quakes currently affecting a zone (served at /nearby and written to the file). */
    String nearbyJson() {
        List<QuakeTracker> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(trackers.values());
        }
        return buildNearbyJson(snapshot);
    }

    private String buildNearbyJson(List<QuakeTracker> snapshot) {
        IntensityScale scale = IntensityScales.getIntensityScale();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (QuakeTracker t : snapshot) {
            if (t.currentTier == NotifyTier.NONE) {
                continue;
            }
            Level level = scale.getLevel(t.bestPga);
            String intensity = level != null ? level.getFullName() : "";
            String intensityDesc = level != null ? intensityDescription(level) : "";
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(String.format(Locale.ROOT,
                    "{\"uuid\":\"%s\",\"fingerprint\":\"%s\",\"test\":%b,\"tier\":\"%s\",\"mag\":%.2f,\"depth\":%.1f,\"lat\":%.4f,\"lon\":%.4f,\"region\":\"%s\",\"zone\":\"%s\",\"distMi\":%.1f,\"pga\":%.4f,\"intensity\":\"%s\",\"intensityDesc\":\"%s\",\"origin\":%d,\"originTime\":\"%s\",\"updatedAt\":%d}",
                    t.currentUuid, t.fingerprint, t.test, t.currentTier, t.mag, t.depth, t.lat, t.lon,
                    jsonEscape(cleanRegion(t.region)), jsonEscape(t.bestZone), kmToMiles(t.bestDistKm), t.bestPga,
                    intensity, jsonEscape(intensityDesc), t.origin, isoTime(t.origin), t.updatedAt));
        }
        return sb.append(']').toString();
    }

    /** JSON array of ALL detected quakes — live plus recently archived (not just zone-affecting). */
    String allJson() {
        if (GlobalQuake.instance == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean[] first = {true};
        try {
            for (Earthquake q : GlobalQuake.instance.getEarthquakeAnalysis().getEarthquakes()) {
                if (q.getHypocenter() == null || q.getOrigin() == 0) {
                    continue;
                }
                appendAll(sb, first, q.getUuid().toString(), q.getLat(), q.getLon(), q.getDepth(), q.getMag(), q.getOrigin(), q.getRegion(), "live");
            }
        } catch (Exception ignored) {
        }
        long cutoff = GlobalQuake.instance.currentTimeMillis() - config.allArchivedHours * 3600_000L;
        try {
            for (ArchivedQuake a : GlobalQuake.instance.getArchive().getArchivedQuakes()) {
                if (a.getOrigin() < cutoff) {
                    break; // list is sorted newest-first
                }
                appendAll(sb, first, a.getUuid().toString(), a.getLat(), a.getLon(), a.getDepth(), a.getMag(), a.getOrigin(), a.getRegion(), "archived");
            }
        } catch (Exception ignored) {
        }
        return sb.append(']').toString();
    }

    private void appendAll(StringBuilder sb, boolean[] first, String uuid, double lat, double lon,
                           double depth, double mag, long origin, String region, String source) {
        if (!first[0]) {
            sb.append(',');
        }
        first[0] = false;
        sb.append(String.format(Locale.ROOT,
                "{\"uuid\":\"%s\",\"source\":\"%s\",\"near\":%b,\"mag\":%.2f,\"depth\":%.1f,\"lat\":%.4f,\"lon\":%.4f,\"region\":\"%s\",\"origin\":%d,\"originTime\":\"%s\"}",
                uuid, source, isNear(lat, lon), mag, depth, lat, lon, jsonEscape(cleanRegion(region)), origin, isoTime(origin)));
    }

    /** Standard worded descriptor for an intensity level (the scales store none). Detects Shindo
     *  (numeric names) vs MMI (roman) from the level's own name. */
    private static String intensityDescription(Level level) {
        String n = level.getFullName();
        if (n == null || n.isEmpty()) {
            return "";
        }
        if (Character.isDigit(n.charAt(0))) { // Shindo / JMA
            return switch (n) {
                case "1" -> "Slight";
                case "2" -> "Weak";
                case "3" -> "Rather strong";
                case "4" -> "Strong";
                case "5-", "5+" -> "Very strong";
                case "6-", "6+" -> "Severe";
                case "7" -> "Extreme";
                default -> "";
            };
        }
        return switch (n) { // MMI
            case "I" -> "Not felt";
            case "II", "III" -> "Weak";
            case "IV" -> "Light";
            case "V" -> "Moderate";
            case "VI" -> "Strong";
            case "VII" -> "Very strong";
            case "VIII" -> "Severe";
            case "IX" -> "Violent";
            default -> "Extreme"; // X, XI, XII
        };
    }

    private static String cleanRegion(String r) {
        return (r == null || r.isBlank()) ? "Unknown region" : r;
    }

    private static String isoTime(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs).toString();
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** HTTP header values must be ASCII; replace anything else (em dashes, accents) with '?'. */
    private static String asciiHeader(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 128 ? c : '?');
        }
        return sb.toString();
    }

    public void destroy() {
        if (tickService != null) {
            GlobalQuake.instance.stopService(tickService);
        }
        if (statusServer != null) {
            statusServer.stop();
        }
    }
}
