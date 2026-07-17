package globalquake.notify;

import globalquake.client.GlobalQuakeLocal;
import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import globalquake.core.alert.Warnable;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.events.GlobalQuakeEventListener;
import globalquake.core.events.specific.QuakeArchiveEvent;
import globalquake.core.events.specific.QuakeCreateEvent;
import globalquake.core.events.specific.QuakeRemoveEvent;
import globalquake.core.events.specific.QuakeUpdateEvent;
import globalquake.core.intensity.IntensityScales;
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

    // fingerprint -> tracker; guarded by `lock`
    private final Map<String, QuakeTracker> trackers = new LinkedHashMap<>();
    private final Object lock = new Object();

    private String lastJsonl = "";

    public NtfyService() {
        this.config = NtfyConfig.load();

        if (!config.enabled) {
            Logger.info("ntfy notifications disabled");
            this.httpClient = null;
            this.tickService = null;
            return;
        }

        Logger.info("ntfy notifications enabled → %s/%s (%d zone(s))".formatted(config.serverUrl, config.topic, config.zones.size()));
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        registerListeners();

        this.tickService = Executors.newSingleThreadScheduledExecutor();
        this.tickService.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
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
                    processSends(t, now);
                }
                trackers.values().removeIf(t ->
                        (t.removedAt > 0 || t.archived) && now - Math.max(t.updatedAt, t.removedAt) >= retentionMs);
                snapshot = new ArrayList<>(trackers.values());
            }

            writeJsonl(snapshot);
        } catch (Exception e) {
            Logger.error(e, "ntfy tick failed");
        }
    }

    private NotifyTier computeTier(QuakeTracker t, double thFelt, double thStrong) {
        NotifyTier bestTier = NotifyTier.NONE;
        String bestZone = "";
        double bestDist = 0, bestPga = 0;

        for (NtfyConfig.Zone z : config.zones) {
            double distKm = GeoUtils.greatCircleDistance(t.lat, t.lon, z.lat(), z.lon());
            double distGEO = GeoUtils.geologicalDistance(t.lat, t.lon, -t.depth, z.lat(), z.lon(), 0.0);
            double pga = GeoUtils.pgaFunction(t.mag, distGEO, t.depth);

            NotifyTier zt = NotifyTier.NONE;
            if (distKm <= z.radiusKm()) {
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
                bestZone = z.name();
                bestDist = distKm;
                bestPga = pga;
            }
        }

        t.bestZone = bestZone.isEmpty() ? (config.zones.isEmpty() ? "home" : config.zones.get(0).name()) : bestZone;
        t.bestDistKm = bestDist;
        t.bestPga = bestPga;
        return NotifyTier.max(bestTier, t.floorTier);
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

        NotifyTier tier = t.currentTier;
        if (tier == NotifyTier.NONE) {
            return;
        }

        if (!t.notified) {
            if (now - t.firstSeen >= config.notifyDelayMs) {
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

    private void send(QuakeTracker t, NotifyTier tier) {
        String label = switch (tier) {
            case STRONG -> "Strong shaking expected";
            case SHAKING -> "Shaking expected";
            default -> "Earthquake nearby";
        };
        int priority = switch (tier) {
            case STRONG -> config.priorityStrong;
            case SHAKING -> config.priorityShaking;
            default -> config.priorityNearby;
        };
        String tags = switch (tier) {
            case STRONG -> config.tagsStrong;
            case SHAKING -> config.tagsShaking;
            default -> config.tagsNearby;
        };

        String title = "M%.1f — %s".formatted(t.mag, label);
        String body = "%s\nMagnitude %.1f, depth %.0f km\n%.0f km from %s".formatted(
                t.region == null || t.region.isBlank() ? "Unknown region" : t.region,
                t.mag, t.depth, t.bestDistKm, t.bestZone);
        post(title, body, priority, tags);
    }

    private void sendCancel(QuakeTracker t) {
        post("Earthquake alert cleared",
                "%s (M%.1f) is no longer being reported".formatted(
                        t.region == null || t.region.isBlank() ? "Earthquake" : t.region, t.mag),
                config.priorityCancel, "white_check_mark");
    }

    private void post(String title, String body, int priority, String tags) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.serverUrl + "/" + config.topic))
                    .timeout(Duration.ofSeconds(10))
                    .header("Title", title)
                    .header("Priority", String.valueOf(priority))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (tags != null && !tags.isBlank()) {
                b.header("Tags", tags);
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

    private void writeJsonl(List<QuakeTracker> snapshot) {
        if (!config.jsonlEnabled) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (QuakeTracker t : snapshot) {
            if (t.currentTier == NotifyTier.NONE) {
                continue;
            }
            sb.append(String.format(Locale.ROOT,
                    "{\"uuid\":\"%s\",\"fingerprint\":\"%s\",\"origin\":%d,\"lat\":%.4f,\"lon\":%.4f,\"depth\":%.1f,\"mag\":%.2f,\"zone\":\"%s\",\"distKm\":%.1f,\"pga\":%.4f,\"tier\":\"%s\",\"updatedAt\":%d}%n",
                    t.currentUuid, t.fingerprint, t.origin, t.lat, t.lon, t.depth, t.mag,
                    jsonEscape(t.bestZone), t.bestDistKm, t.bestPga, t.currentTier, t.updatedAt));
        }
        String content = sb.toString();
        if (content.equals(lastJsonl)) {
            return; // nothing changed — avoid needless disk churn
        }
        lastJsonl = content;

        File target = new File(GlobalQuake.mainFolder, "nearby_quakes.jsonl");
        File tmp = new File(GlobalQuake.mainFolder, "nearby_quakes.jsonl.tmp");
        try {
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Logger.error(e, "Failed to write nearby_quakes.jsonl");
        }
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void destroy() {
        if (tickService != null) {
            GlobalQuake.instance.stopService(tickService);
        }
    }
}
