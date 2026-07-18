package globalquake.notify;

import java.util.UUID;

/**
 * Per-physical-earthquake notification state, keyed by a fuzzy fingerprint (origin time + epicenter)
 * so it survives GlobalQuake deleting and re-creating the same quake under a new UUID.
 */
public class QuakeTracker {

    final String fingerprint;      // stable id, minted at first sighting
    UUID currentUuid;

    double lat, lon, depth, mag;
    long origin;
    String region = "";

    final long firstSeen;          // wall clock
    long updatedAt;                // wall clock
    long removedAt;                // wall clock, 0 = active
    boolean archived;              // ended naturally (no cancel note)

    NotifyTier floorTier = NotifyTier.NONE;   // raised by AlertManager warnings (NEARBY)
    NotifyTier currentTier = NotifyTier.NONE; // recomputed each tick

    // best-affected zone this tick, for message + JSONL + ETA
    String bestZone = "";
    double bestZoneLat, bestZoneLon;
    double bestDistKm;
    double bestPga;

    long lastImminentAt; // wall clock; debounces the max-priority imminent alert

    boolean notified;
    NotifyTier lastNotifiedTier = NotifyTier.NONE;
    double lastNotifiedMag;
    long lastSentAt;
    boolean cancelSent;

    boolean test;                  // injected via the test endpoints; marked in notif + JSON
    NotifyTier forcedTier;         // non-null on test quakes: computeTier returns this directly

    QuakeTracker(String fingerprint, long now) {
        this.fingerprint = fingerprint;
        this.firstSeen = now;
        this.updatedAt = now;
    }
}
