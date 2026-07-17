package globalquake.core.earthquake;

import globalquake.core.GlobalQuake;
import globalquake.core.HypocsSettings;
import globalquake.core.Settings;
import globalquake.core.events.specific.ClusterCreateEvent;
import globalquake.core.events.specific.ClusterLevelUpEvent;
import globalquake.core.events.specific.QuakeRemoveEvent;
import globalquake.core.geo.taup.TauPTravelTimeCalculator;
import globalquake.core.intensity.IntensityTable;
import globalquake.core.station.AbstractStation;
import globalquake.core.earthquake.data.*;
import globalquake.core.analysis.Event;
import globalquake.core.station.NearbyStationDistanceInfo;
import globalquake.utils.GeoUtils;
import globalquake.utils.monitorable.MonitorableConcurrentLinkedQueue;
import org.tinylog.Logger;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ClusterAnalysis {

    private final ReadWriteLock clustersLock = new ReentrantReadWriteLock();

    private final Lock clustersReadLock = clustersLock.readLock();
    private final Lock clustersWriteLock = clustersLock.writeLock();

    protected final Collection<Cluster> clusters;
    private final Collection<Earthquake> earthquakes;
    private final Collection<AbstractStation> stations;

    private static final double MERGE_THRESHOLD = 0.54;

    public ClusterAnalysis(List<Earthquake> earthquakes, Collection<AbstractStation> stations) {
        this.earthquakes = earthquakes;
        this.stations = stations;
        clusters = new MonitorableConcurrentLinkedQueue<>();
    }

    public ClusterAnalysis() {
        this(GlobalQuake.instance.getEarthquakeAnalysis().getEarthquakes(), GlobalQuake.instance.getStationManager().getStations());
    }

    public Lock getClustersReadLock() {
        return clustersReadLock;
    }

    /**
     * Master switch for the EXPERIMENTAL multi-quake detection path (release of misfit picks,
     * newborn-cluster survival, ghost absorption, origin-time & direction merge guards). OFF (0,
     * the default) preserves the original upstream behaviour exactly: nearby second quakes are
     * swallowed by the loose merge (README known issue #1), but there are no phantom-quake storms
     * beyond upstream's own M6+ behaviour (README known issue #2 — playground testing showed both
     * issues share the coda-retrigger root; see .ai/multi-quake-fix-design.md). Enable by putting
     * {@code multiQuakeMode=1} in {@code .GlobalQuakeData/hypocs.properties} — playground
     * experiments only until the coda-ghost problem is solved.
     */
    public static boolean multiQuakeMode() {
        return multiQuakeLevel() >= 1;
    }

    /**
     * Experimental-feature tier: 0 = exact upstream behaviour; 1 = cluster/solver-level multi-quake
     * detection (release, newborn survival, ghost guards, emission quarantine); 2 = level 1 PLUS
     * station-picker re-triggering (BetterAnalysis emits a fresh pick when a sharp new onset rides
     * on top of an open event — the root enabler for same-epicenter doublets). Each level strictly
     * adds to the previous, so any regression can be bisected by stepping the number down.
     */
    public static int multiQuakeLevel() {
        return HypocsSettings.getOrDefaultInt("multiQuakeMode", 0);
    }

    public void run() {
        clustersWriteLock.lock();
        try {
            clearSWaves();
            markSWaves();
            //assignEventsToExistingEarthquakeClusters(); VERY CONTROVERSIAL
            expandExistingClusters();
            if (multiQuakeMode()) {
                releaseMisfitEvents();
            }
            createNewClusters();
            if (multiQuakeMode()) {
                absorbGhostClusters();
            }
            stealEvents();
            mergeClusters();
            updateClusters();
            logMultiQuakeDebug();
        } finally {
            clustersWriteLock.unlock();
        }
    }

    /**
     * TEMPORARY multi-quake diagnostic instrumentation ([MQ] tag, INFO level so it shows on the
     * default console). Logging-only, no behaviour change. Prints a per-cycle census of P-picks
     * (assigned vs unassigned) and per-cluster state, so we can see whether a second nearby quake
     * actually generates independent picks and where they end up. Remove once the root fix lands.
     */
    private void logMultiQuakeDebug() {
        int pValid = 0, assigned = 0, unassigned = 0, stationsInEvent = 0;
        for (AbstractStation station : stations) {
            boolean stEvent = false;
            for (Event e : station.getAnalysis().getDetectedEvents()) {
                if (e.isValid() && !e.isSWave() && e.getpWave() > 0) {
                    pValid++;
                    if (e.assignedCluster == null) {
                        unassigned++;
                    } else {
                        assigned++;
                    }
                }
                if (e.isValid() && !e.hasEnded()) {
                    stEvent = true;
                }
            }
            if (stEvent) {
                stationsInEvent++;
            }
        }

        int clusterCount = 0;
        for (Cluster ignored : clusters) {
            clusterCount++;
        }

        if (clusterCount == 0 && unassigned == 0) {
            return; // idle / nothing interesting — stay quiet
        }

        Logger.tag("MQ").info("[MQ] cycle: clusters=%d Ppicks=%d assigned=%d unassigned=%d stationsInEvent=%d"
                .formatted(clusterCount, pValid, assigned, unassigned, stationsInEvent));

        for (Cluster c : clusters) {
            Earthquake eq = c.getEarthquake();
            String correct = c.getPreviousHypocenter() == null ? "-" : Integer.toString(c.getPreviousHypocenter().correctEvents);
            if (eq == null) {
                Logger.tag("MQ").info("[MQ]   C#%d eq=none assigned=%d correctEv=%s"
                        .formatted(c.id, c.getAssignedEvents().size(), correct));
            } else {
                long ageSec = (GlobalQuake.instance.currentTimeMillis() - eq.getOrigin()) / 1000;
                Logger.tag("MQ").info("[MQ]   C#%d eq=M%.1f depth=%.0fkm origin=%ds-ago assigned=%d correctEv=%s"
                        .formatted(c.id, eq.getMag(), eq.getDepth(), ageSec, c.getAssignedEvents().size(), correct));
            }
        }
    }

    private void markSWaves() {
        for(Cluster cluster: getClusters()){
            markPossibleSWaves(cluster);
        }
    }

    public void destroy() {
    }

    record EventIntensityInfo(Cluster cluster, AbstractStation station, double expectedIntensity){}

    private void stealEvents() {
        java.util.Map<Event, EventIntensityInfo> map = new HashMap<>();
        for(Cluster cluster : clusters) {
            if (cluster.getEarthquake() == null) {
                continue;
            }

            for (AbstractStation station : stations) {
                for (Event event : station.getAnalysis().getDetectedEvents()) {
                    if (event.isValid() && event.isSWave() && !couldBeArrival(event, cluster.getEarthquake(), true, false, true)) {
                        double distGC = GeoUtils.greatCircleDistance(event.getLatFromStation(), event.getLonFromStation(), cluster.getEarthquake().getLat(), cluster.getEarthquake().getLon());
                        double expectedIntensity = IntensityTable.getIntensity(cluster.getEarthquake().getMag(), GeoUtils.gcdToGeo(distGC));
                        EventIntensityInfo eventIntensityInfo = new EventIntensityInfo(cluster, station, expectedIntensity);
                        EventIntensityInfo old = map.putIfAbsent(event, eventIntensityInfo);
                        if(old != null && eventIntensityInfo.expectedIntensity > old.expectedIntensity){
                            map.put(event, eventIntensityInfo);
                        }
                    }
                }
            }
        }

        // reassign
        for(var entry : map.entrySet()){
            Event event = entry.getKey();
            AbstractStation station = entry.getValue().station();
            Cluster cluster = entry.getValue().cluster();

            if(!cluster.getAssignedEvents().containsKey(station)){
                if(event.assignedCluster != null){
                    event.assignedCluster.getAssignedEvents().remove(station);
                }

                event.assignedCluster = cluster;
                cluster.getAssignedEvents().put(station, event);
            }
        }
    }

    private void clearSWaves() {
        for(Cluster cluster : clusters) {
            if(cluster.getEarthquake() == null){
                continue;
            }

            for (AbstractStation station : stations) {
                for (Event event : station.getAnalysis().getDetectedEvents()) {
                    // In multi-quake mode the unmark decision must be pure timing
                    // (considerIntensity=false), or picks S-marked by absorbGhostClusters (which
                    // ignores the intensity gate) would be unmarked here every cycle and re-fuel
                    // ghost clusters in an endless loop. Original mode keeps the upstream gated test.
                    if (event.isValid() && event.isSWave() && (!couldBeSArrival(event, cluster.getEarthquake(), !multiQuakeMode())
                            || couldBeArrival(event, cluster.getEarthquake(), true, false, true))) {
                        event.setAsSWave(false);
                    }
                }
            }
        }
    }

    private void markPossibleSWaves(Cluster cluster) {
        for (AbstractStation station : stations) {
            for (Event event : station.getAnalysis().getDetectedEvents()) {
                if (event.isValid() && !event.isSWave() && (couldBeSArrival(event, cluster.getEarthquake())
                        && !couldBeArrival(event,cluster.getEarthquake(), true, false, true))) {
                    event.setAsSWave(true);
                }
            }
        }
    }


    /**
     * Dissolves newborn (earthquake-less) clusters that are actually GHOSTS of an existing quake —
     * S-wave / coda re-triggers picked as fresh P arrivals. Stations re-trigger when the S wavefront
     * hits (~tens of seconds after P); those picks are mutually consistent (they ride a real physical
     * wavefront), so the hypocenter solver would happily locate a phantom "second quake" from them
     * (observed in playground: phantom origins ~25 s late = the S−P delay, storms of 17 clusters, the
     * real quake getting eaten). The discriminator is phase-aware and intensity-free: a ghost's picks
     * fit the quake's S travel-time curve; a genuine doublet's picks fit neither P (offset by the
     * origin-time gap) nor S (coincides only on one distance ring, never a majority). Dissolving
     * (rather than merging into the quake) marks S-fitting picks as S-waves so they stop fueling new
     * ghosts AND stay out of the quake's P solution; the remainder is released with rejection memory.
     * Runs before mergeClusters() and, critically, before EarthquakeAnalysis.run() in the same tick
     * (see GlobalQuakeRuntime), so a dissolved ghost can never establish an earthquake.
     */
    private void absorbGhostClusters() {
        List<Cluster> toDissolve = null;
        for (Cluster cluster : clusters) {
            if (cluster.getEarthquake() != null) {
                continue; // established clusters are governed by canMerge's guards
            }
            for (Earthquake earthquake : earthquakes) {
                int pFit = 0, sFit = 0, total = 0;
                for (Event event : cluster.getAssignedEvents().values()) {
                    if (!event.isValid() || event.isSWave()) {
                        continue;
                    }
                    total++;
                    if (couldBeArrival(event, earthquake, false, false, false)) {
                        pFit++;
                    } else if (couldBeSArrival(event, earthquake, false)) {
                        sFit++;
                    }
                }

                // majority explained by this quake, and S-like arrivals dominate strict-P stragglers
                // (a straggler-dominated newborn is left for canMerge to fold back into the quake)
                if (total == 0 || (pFit + sFit) / (double) total <= MERGE_THRESHOLD || sFit <= pFit) {
                    continue;
                }

                for (Event event : cluster.getAssignedEvents().values()) {
                    if (event.isValid() && !event.isSWave() && couldBeSArrival(event, earthquake, false)) {
                        event.setAsSWave(true);
                    }
                    event.assignedCluster = null;
                    // the quake may not re-claim the leftovers with its wide window; a strict fit
                    // still readmits them (see expandPWaves)
                    event.rejectedByCluster = earthquake.getCluster();
                }
                if (toDissolve == null) {
                    toDissolve = new ArrayList<>();
                }
                toDissolve.add(cluster);
                Logger.tag("MQ").info("[MQ] ghost-DISSOLVE: newborn C#%d (%d picks) explained by M%.1f as sFit=%d pFit=%d -> S-marked & disbanded"
                        .formatted(cluster.id, total, earthquake.getMag(), sFit, pFit));
                break;
            }
        }
        if (toDissolve != null) {
            clusters.removeAll(toDissolve);
        }
    }

    private void mergeClusters() {
        for (Earthquake earthquake : earthquakes) {
            List<Cluster> toMerge = null;

            for (Cluster cluster : clusters) {
                if (earthquake.getCluster() == cluster) {
                    continue;
                }

                if (canMerge(earthquake, cluster)) {
                    if (toMerge == null) {
                        toMerge = new ArrayList<>();
                    }
                    toMerge.add(cluster);
                }
            }

            if (toMerge != null) {
                merge(earthquake, toMerge);
            }
        }
    }

    private void merge(Earthquake earthquake, List<Cluster> toMerge) {
        Cluster target = earthquake.getCluster();
        for (Cluster cluster : toMerge) {
            for (Entry<AbstractStation, Event> entry : cluster.getAssignedEvents().entrySet()) {
                if (target.getAssignedEvents().putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                    entry.getValue().assignedCluster = target;
                }
            }

            Earthquake earthquake1 = cluster.getEarthquake();

            if (earthquake1 != null) {
                earthquakes.remove(earthquake1);
                GlobalQuake.instance.getEventHandler().fireEvent(new QuakeRemoveEvent(earthquake1));
            }
        }

        clusters.removeAll(toMerge);
    }

    private boolean canMerge(Earthquake earthquake, Cluster cluster) {
        boolean established = cluster.getEarthquake() != null && cluster.getPreviousHypocenter() != null;
        if(established){
            int thatCorrect = cluster.getPreviousHypocenter().correctEvents;
            double dist = GeoUtils.greatCircleDistance(earthquake.getLat(), earthquake.getLon(), cluster.getEarthquake().getLat(), cluster.getEarthquake().getLon());
            double maxDist = 6000 / (1 + thatCorrect * 0.2);
            if(dist > maxDist){
                return false;
            }

            if (multiQuakeMode()) {
                // Two established earthquakes whose origin TIMES are far apart are distinct events (e.g. a
                // doublet or an aftershock at the same spot), not a duplicate to be merged away. Without
                // this, a genuine second quake near the first is deleted purely on spatial overlap.
                long dtOrigin = Math.abs(earthquake.getOrigin() - cluster.getEarthquake().getOrigin());
                if(dtOrigin > HypocsSettings.getOrDefaultInt("originTimeMergeSeparationMs", 15000)){
                    Logger.tag("MQ").info("[MQ] merge-BLOCK (origin-time guard): C#%d dtOrigin=%dms > %dms -> kept separate"
                            .formatted(cluster.id, dtOrigin, HypocsSettings.getOrDefaultInt("originTimeMergeSeparationMs", 15000)));
                    return false;
                }

                // Merge direction must favor the better-constrained solution. mergeClusters() keeps
                // `earthquake` and destroys `cluster`, in earthquake-list iteration order — without this
                // guard a young phantom could absorb (and thus delete) the real, well-constrained quake
                // just by being iterated first. The reverse merge (better one absorbing this cluster)
                // stays allowed, so consolidation still happens — just never backwards.
                Cluster ownCluster = earthquake.getCluster();
                if (ownCluster != null && ownCluster.getPreviousHypocenter() != null
                        && thatCorrect > ownCluster.getPreviousHypocenter().correctEvents) {
                    Logger.tag("MQ").info("[MQ] merge-BLOCK (direction guard): M%.1f (correct=%d) may not absorb better-constrained C#%d (correct=%d)"
                            .formatted(earthquake.getMag(), ownCluster.getPreviousHypocenter().correctEvents, cluster.id, thatCorrect));
                    return false;
                }
            }
        }
        int correct = 0;
        for (Event event : cluster.getAssignedEvents().values()) {
            // Multi-quake mode: a newborn cluster (no located earthquake yet) skips the
            // distance/origin-time guards above, so the fit test below is its ONLY protection from
            // being swallowed. Judge it with the same STRICT arrival test that releaseMisfitEvents()
            // uses — the loose ±10s window would re-absorb the very picks that were just released for
            // failing the strict window (which is why a distinct second quake's newborn cluster used
            // to be annihilated in the same cycle it formed, and release/merge oscillated every
            // cycle). Genuine stragglers of this quake still fit strictly and merge back; a distinct
            // quake's picks survive until the solver gives the cluster its own origin, after which
            // the guards above apply. Original mode: the loose test for everything (upstream
            // behaviour — swallows nearby newborns, ghosts and doublets alike).
            boolean fits = established || !multiQuakeMode()
                    ? couldBeArrival(event, earthquake, true, true, true)
                    : couldBeArrival(event, earthquake, false, false, false);
            if (fits) {
                correct++;
            }
        }

        double pct = correct / (double) cluster.getAssignedEvents().size();

        boolean merge = pct > MERGE_THRESHOLD;
        if (!established) {
            Logger.tag("MQ").info("[MQ] merge-eval newborn C#%d vs M%.1f: strictFit=%d/%d (%.0f%%) -> %s"
                    .formatted(cluster.id, earthquake.getMag(), correct, cluster.getAssignedEvents().size(),
                            pct * 100, merge ? "MERGED into existing quake" : "kept separate"));
        }
        return merge;
    }

    @SuppressWarnings("unused")
    private void assignEventsToExistingEarthquakeClusters() {
        for (AbstractStation station : stations) {
            for (Event event : station.getAnalysis().getDetectedEvents()) {
                if (event.isValid() && !event.isSWave() && event.getpWave() > 0 && event.assignedCluster == null) {
                    HashMap<Earthquake, Event> map = new HashMap<>();

                    for (Earthquake earthquake : earthquakes) {
                        if (couldBeArrival(event, earthquake, true, true, false)) {
                            map.putIfAbsent(earthquake, event);
                        }
                    }

                    for (Entry<Earthquake, Event> entry : map.entrySet()) {
                        Cluster cluster = entry.getKey().getCluster();
                        Event event2 = entry.getValue();
                        if (!cluster.containsStation(event2.getAnalysis().getStation())) {
                            if (cluster.getAssignedEvents().putIfAbsent(station, event2) == null) {
                                event2.assignedCluster = cluster;
                            }
                        }
                    }
                }
            }
        }

    }

    private boolean couldBeSArrival(Event event, Earthquake earthquake){
        return couldBeSArrival(event, earthquake, true);
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean couldBeSArrival(Event event, Earthquake earthquake, boolean considerIntensity){
        if (!event.isValid() || earthquake == null) {
            return false;
        }

        // The intensity gate keeps GLOBAL S-marking (markPossibleSWaves) conservative — don't
        // invalidate far, weak stations' picks as "S of a small quake". Pure timing checks
        // (ghost-cluster absorption, clearSWaves' keep-marked decision, quarantine S-confirmation)
        // pass considerIntensity=false: there the question is only whether the arrival rides this
        // quake's S wavefront.
        if (considerIntensity) {
            double distGC = GeoUtils.greatCircleDistance(earthquake.getLat(), earthquake.getLon(),
                    event.getLatFromStation(), event.getLonFromStation());
            double expectedIntensity = IntensityTable.getIntensity(earthquake.getMag(), GeoUtils.gcdToGeo(distGC));
            if (expectedIntensity < 3.0) {
                return false;
            }
        }

        return couldBeSArrivalTiming(event.getLatFromStation(), event.getLonFromStation(), event.getElevationFromStation(),
                event.getpWave(), earthquake.getLat(), earthquake.getLon(), earthquake.getDepth(), earthquake.getOrigin());
    }

    /**
     * Pure timing test: could an arrival picked at the given time be the S wave of a quake at the
     * given hypocenter? No intensity/magnitude term, so it also works for candidate hypocenters
     * that don't have an Earthquake object yet (quarantine S-confirmation in EarthquakeAnalysis).
     */
    @SuppressWarnings("RedundantIfStatement")
    public static boolean couldBeSArrivalTiming(double eventLat, double eventLon, double eventAlt, long pWave,
                                                double quakeLat, double quakeLon, double quakeDepth, long quakeOrigin){
        long actualTravel = pWave - quakeOrigin;

        double distGC = GeoUtils.greatCircleDistance(quakeLat, quakeLon, eventLat, eventLon);
        double angle = TauPTravelTimeCalculator.toAngle(distGC);
        double expectedTravelSRaw = TauPTravelTimeCalculator.getSWaveTravelTime(quakeDepth,
                angle);

        if (expectedTravelSRaw >= 0) {
            // 985 because GQ has high tendency to detect S waves earlier
            long expectedTravel = (long) ((expectedTravelSRaw + EarthquakeAnalysis.getElevationCorrection(eventAlt) * 1.5) * 1000);
            long diff = actualTravel - expectedTravel;
            if (diff > -2000 - expectedTravel * 0.03 && diff < 6000 + expectedTravel * 0.05) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unused")
    public static boolean couldBeArrival(PickedEvent pickedEvent, PreliminaryHypocenter bestHypocenter,
                                         boolean considerIntensity, boolean increasingPWindow, boolean pWaveOnly) {
        if (pickedEvent == null || bestHypocenter == null) {
            return false;
        }

        if(considerIntensity){
            throw new IllegalArgumentException("Preliminary Hypocenter doesn't have magnitude and cannot be assessed using intensity.");
        }

        return couldBeArrival(pickedEvent.lat(), pickedEvent.lon(), pickedEvent.elevation(), pickedEvent.pWave(),
                bestHypocenter.lat, bestHypocenter.lon, bestHypocenter.depth, bestHypocenter.origin, 0,
                false, increasingPWindow, pWaveOnly);
    }

    public static boolean couldBeArrival(Event event, Earthquake earthquake,
                                         boolean considerIntensity, boolean increasingPWindow, boolean pWaveOnly) {
        if (event == null || !event.isValid() || event.isSWave() || earthquake == null) {
            return false;
        }

        return couldBeArrival(event.getLatFromStation(), event.getLonFromStation(), event.getElevationFromStation(), event.getpWave(),
                earthquake.getLat(), earthquake.getLon(), earthquake.getDepth(), earthquake.getOrigin(), earthquake.getMag(),
                considerIntensity, increasingPWindow, pWaveOnly);
    }

    public static boolean couldBeArrival(PickedEvent event, Hypocenter earthquake,
                                         boolean considerIntensity, boolean increasingPWindow, boolean pWaveOnly) {
        if (event == null || earthquake == null) {
            return false;
        }

        return couldBeArrival(event.lat(), event.lon(), event.elevation(), event.pWave(),
                earthquake.lat, earthquake.lon, earthquake.depth, earthquake.origin, earthquake.magnitude,
                considerIntensity, increasingPWindow, pWaveOnly);
    }

    @SuppressWarnings("RedundantIfStatement")
    public static boolean couldBeArrival(double eventLat, double eventLon, double eventAlt, long pWave,
                                         double quakeLat, double quakeLon, double quakeDepth, long quakeOrigin, double quakeMag,
                                         boolean considerIntensity, boolean increasingPWindow, boolean pWaveOnly){
        long actualTravel = pWave - quakeOrigin;

        double distGC = GeoUtils.greatCircleDistance(quakeLat, quakeLon,
                eventLat, eventLon);
        double angle = TauPTravelTimeCalculator.toAngle(distGC);
        double expectedTravelPRaw = TauPTravelTimeCalculator.getPWaveTravelTime(quakeDepth,
                angle);

        if(considerIntensity) {
            double expectedRatio = IntensityTable.getRatio(quakeMag, GeoUtils.gcdToGeo(distGC));
            if (expectedRatio < 3.0) {
                return false;
            }
        }

        if (expectedTravelPRaw >= 0) {
            long expectedTravel = (long) ((expectedTravelPRaw + EarthquakeAnalysis.getElevationCorrection(eventAlt)) * 1000);
            if (Math.abs(expectedTravel - actualTravel) < (increasingPWindow ? Math.max(10000, 1000 + expectedTravel * 0.01) : Settings.pWaveInaccuracyThreshold)) {
                return true;
            }
        }

        if(pWaveOnly){
            return false;
        }

        double expectedTravelPKPRaw = TauPTravelTimeCalculator.getPKPWaveTravelTime(quakeDepth,
                angle);

        if (expectedTravelPKPRaw >= 0) {
            long expectedTravel = (long) ((expectedTravelPKPRaw + EarthquakeAnalysis.getElevationCorrection(eventAlt)) * 1000);
            if (Math.abs(expectedTravel - actualTravel) < (Math.max(6000, expectedTravel * 0.005))) {
                return true;
            }
        }

        double expectedTravelPKIKPRaw = TauPTravelTimeCalculator.getPKIKPWaveTravelTime(quakeDepth,
                angle);

        if (expectedTravelPKIKPRaw >= 0 && angle > 100) {
            long expectedTravel = (long) ((expectedTravelPKIKPRaw + EarthquakeAnalysis.getElevationCorrection(eventAlt)) * 1000);
            if (Math.abs(expectedTravel - actualTravel) < Math.max(6000, expectedTravel * 0.005)) {
                return true;
            }
        }

        return false;
    }

    private void expandExistingClusters() {
        for (Cluster c : clusters) {
            expandCluster(c);
        }
    }

    /**
     * Releases picks that a well-established earthquake cannot explain, so a genuinely distinct
     * second earthquake near it in space/time can form its own cluster.
     * <p>
     * Root problem this addresses: {@link #expandPWaves} claims any pick within a wide (~10 s) window
     * of an existing quake's predicted arrival, including the picks of a *second* quake at the same
     * epicenter (whose arrivals nearly coincide). Those picks are then owned by cluster #1 but trimmed
     * from its fit as outliers — invisible, and unavailable to {@link #createNewClusters} (which needs
     * unassigned picks). Here we detach the picks that fail the *strict* arrival test (P, PKP or PKIKP)
     * and mark them {@link Event#rejectedByCluster} so the same cluster will not immediately re-steal
     * them next cycle. A second cluster still only forms if enough released picks are mutually
     * consistent at a distinct origin (see {@code clusterMinSize} in {@link #createNewClusters}); a
     * cluster that turns out to be the *same* quake (same origin time) is folded back in
     * {@link #canMerge}'s time guard. Only quakes with at least {@code releaseMinCorrectEvents} well-fit
     * picks are eligible, so a still-forming solution is never disturbed.
     */
    private void releaseMisfitEvents() {
        int minCorrect = HypocsSettings.getOrDefaultInt("releaseMinCorrectEvents", 8);
        for (Cluster cluster : clusters) {
            Earthquake earthquake = cluster.getEarthquake();
            if (earthquake == null) {
                continue;
            }
            Integer correct = cluster.getPreviousHypocenter() == null ? null : cluster.getPreviousHypocenter().correctEvents;
            if (correct == null || correct < minCorrect) {
                // only release from well-constrained quakes, never from a forming solution
                Logger.tag("MQ").info("[MQ] release-GATE blocks C#%d (M%.1f): correctEv=%s < %d -> no picks released"
                        .formatted(cluster.id, earthquake.getMag(), correct == null ? "-" : correct.toString(), minCorrect));
                continue;
            }

            int released = 0;
            for (Iterator<Event> it = cluster.getAssignedEvents().values().iterator(); it.hasNext(); ) {
                Event event = it.next();
                if (!event.isValid() || event.isSWave() || event.getpWave() <= 0) {
                    continue; // invalid / S-wave handled by updateClusters; unpicked P ignored
                }
                // pWaveOnly=false so legitimate teleseismic PKP/PKIKP arrivals are NOT released
                // (that would manufacture the M6+ duplicate we are trying to avoid); increasingPWindow
                // =false so we use the strict window — only picks that fit no phase are freed.
                if (!couldBeArrival(event, earthquake, false, false, false)) {
                    event.assignedCluster = null;
                    event.rejectedByCluster = cluster;
                    it.remove();
                    released++;
                }
            }
            Logger.tag("MQ").info("[MQ] release: C#%d (M%.1f) correctEv=%d qualified -> released %d misfit picks (now assigned=%d)"
                    .formatted(cluster.id, earthquake.getMag(), correct, released, cluster.getAssignedEvents().size()));
        }
    }

    private void expandCluster(Cluster cluster) {
        if (cluster.getEarthquake() != null && cluster.getPreviousHypocenter() != null) {
            if(cluster.getPreviousHypocenter().correctEvents > 7) {
                expandPWaves(cluster);
            }
        }

        ArrayList<Event> list = new ArrayList<>(cluster.getAssignedEvents().values());
        while (!list.isEmpty()) {
            ArrayList<Event> newEvents = new ArrayList<>();
            mainLoop:
            for (Event e : list) {
                for (NearbyStationDistanceInfo info : e.getAnalysis().getStation().getNearbyStations()) {
                    if (!cluster.containsStation(info.station()) && !_contains(newEvents, info.station())) {
                        double dist = info.dist();
                        for (Event ev : info.station().getAnalysis().getDetectedEvents()) {
                            if (ev.rejectedByCluster != cluster && potentialArrival(ev, e, dist)) {
                                newEvents.add(ev);
                                continue mainLoop;
                            }
                        }
                    }
                }
            }

            for (Event event : newEvents) {
                if (cluster.getAssignedEvents().putIfAbsent(event.getAnalysis().getStation(), event) == null) {
                    event.assignedCluster = cluster;
                }
            }

            list.clear();
            list.addAll(newEvents);
        }
    }

    private void expandPWaves(Cluster cluster) {
        mainLoop:
        for (AbstractStation station : stations) {
            for (Event event : station.getAnalysis().getDetectedEvents()) {
                // A rejected (released) pick may only be re-claimed by the cluster that released it
                // if it fits STRICTLY now (hypocenter refined toward it). Rejection blocks the wide
                // window, not a strict fit — permanent exile starved a quake of its own picks when
                // its early rough hypocenter caused over-release (real quake flickering in/out).
                // Claim-back test == release test, so release/re-claim cannot oscillate.
                if (event.isValid() && !event.isSWave() &&
                        !cluster.containsStation(station) &&
                        (event.rejectedByCluster != cluster
                                || couldBeArrival(event, cluster.getEarthquake(), false, false, false)) &&
                        couldBeArrival(event, cluster.getEarthquake(), true, true, false)) {
                    if (cluster.getAssignedEvents().putIfAbsent(station, event) == null) {
                        event.assignedCluster = cluster;
                    }
                    continue mainLoop;
                }
            }
        }
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean potentialArrival(Event ev, Event e, double dist) {
        if (e.isValid() && ev.isValid() && !ev.isSWave() && !e.isSWave() && ev.getpWave() > 0 && ev.assignedCluster == null) {
            long earliestPossibleTimeOfThatEvent = e.getpWave() - (long) ((dist * 1000.0) / 5.0)
                    - 2500;
            long latestPossibleTimeOfThatEvent = e.getpWave() + (long) ((dist * 1000.0) / 5.0)
                    + 2500;
            if (ev.getpWave() >= earliestPossibleTimeOfThatEvent
                    && ev.getpWave() <= latestPossibleTimeOfThatEvent) {
                return true;
            }
        }

        return false;
    }

    private boolean _contains(ArrayList<Event> newEvents, AbstractStation station) {
        for (Event e : newEvents) {
            if (e.getAnalysis().getStation().getId() == station.getId()) {
                return true;
            }
        }
        return false;
    }

    private void createNewClusters() {
        int minSize = HypocsSettings.getOrDefaultInt("clusterMinSize", 4);
        int seeds = 0, created = 0, bestCorroboration = 0;
        for (AbstractStation station : stations) {
            for (Event event : station.getAnalysis().getDetectedEvents()) {
                if (event.isValid() && !event.isSWave() && event.getpWave() > 0 && event.assignedCluster == null) {
                    // so we have eligible event
                    seeds++;
                    ArrayList<Event> validEvents = new ArrayList<>();
                    validEvents.add(event);
                    closestLoop:
                    for (NearbyStationDistanceInfo info : station.getNearbyStations()) {
                        AbstractStation close = info.station();
                        double dist = info.dist();
                        for (Event e : close.getAnalysis().getDetectedEvents()) {
                            if (e.isValid() && !e.isSWave() && e.getpWave() > 0 && e.assignedCluster == null) {
                                long earliestPossibleTimeOfThatEvent = event.getpWave()
                                        - (long) ((dist * 1000.0) / 5.0) - 2500;
                                long latestPossibleTimeOfThatEvent = event.getpWave()
                                        + (long) ((dist * 1000.0) / 5.0) + 2500;
                                if (e.getpWave() >= earliestPossibleTimeOfThatEvent
                                        && e.getpWave() <= latestPossibleTimeOfThatEvent) {
                                    validEvents.add(e);
                                    continue closestLoop;
                                }
                            }
                        }
                    }

                    bestCorroboration = Math.max(bestCorroboration, validEvents.size());
                    // so no we have a list of all nearby events that could be earthquake
                    if (validEvents.size() >= minSize) {
                        expandCluster(createCluster(validEvents));
                        created++;
                    }
                }
            }
        }

        if (seeds > 0) {
            Logger.tag("MQ").info("[MQ] createNew: unassignedSeeds=%d bestCorroboration=%d (need>=%d) newClusters=%d"
                    .formatted(seeds, bestCorroboration, minSize, created));
        }
    }

    private void updateClusters() {
        Iterator<Cluster> it = clusters.iterator();
        List<Cluster> toBeRemoved = new ArrayList<>();
        List<Cluster> toBeRemovedBadly = new ArrayList<>();
        while (it.hasNext()) {
            Cluster cluster = it.next();
            int numberOfActiveEvents = 0;
            int minimum = (int) Math.max(2, cluster.getAssignedEvents().size() * 0.12);
            for (Iterator<Event> iterator = cluster.getAssignedEvents().values().iterator(); iterator.hasNext(); ) {
                Event event = iterator.next();
                if (!event.isValid() || event.isSWave()) {
                    event.assignedCluster = null;
                    iterator.remove();
                } else if (!event.hasEnded()) {
                    numberOfActiveEvents++;
                }
            }

            Earthquake earthquake = cluster.getEarthquake();

            boolean notEnoughEvents = cluster.getAssignedEvents().size() < HypocsSettings.getOrDefaultInt("clusterMinSize", 4);
            boolean eqRemoved = earthquake != null && EarthquakeAnalysis.shouldRemove(earthquake, 0);
            boolean tooOld = earthquake == null && numberOfActiveEvents < minimum && GlobalQuake.instance.currentTimeMillis() - cluster.getLastUpdate() > 2 * 60 * 1000;

            if ( notEnoughEvents || eqRemoved || tooOld) {
                Logger.tag("Hypocs").debug("Cluster #%d marked for removal (%s || %s || %s)".formatted(cluster.id, notEnoughEvents, eqRemoved, tooOld));
                toBeRemoved.add(cluster);
                if(notEnoughEvents){
                    toBeRemovedBadly.add(cluster);
                }
            } else {
                cluster.tick();
                // if level changes or if it got updated (root location)
                if(cluster.getLevel() != cluster.lastLevel || cluster.lastLastUpdate != cluster.getLastUpdate()){
                    GlobalQuake.instance.getEventHandler().fireEvent(new ClusterLevelUpEvent(cluster));
                    cluster.lastLevel = cluster.getLevel();
                    cluster.lastLastUpdate = cluster.getLastUpdate();
                }
            }
        }

        for(Cluster cluster : toBeRemovedBadly){
            if(cluster.getEarthquake() != null){
                earthquakes.remove(cluster.getEarthquake());
                GlobalQuake.instance.getEventHandler().fireEvent(new QuakeRemoveEvent(cluster.getEarthquake()));
            }
        }

        clusters.removeAll(toBeRemoved);
    }

    private Cluster createCluster(ArrayList<Event> validEvents) {
        Cluster cluster = new Cluster();
        for (Event ev : validEvents) {
            if (cluster.getAssignedEvents().putIfAbsent(ev.getAnalysis().getStation(), ev) == null) {
                ev.assignedCluster = cluster;
                cluster.addEvent();
            }
        }

        cluster.calculateRoot(true);

        Logger.tag("Hypocs").debug("New Cluster #" + cluster.id + " Has been created. It contains "
                + cluster.getAssignedEvents().size() + " events");
        clusters.add(cluster);

        if(GlobalQuake.instance != null){
            GlobalQuake.instance.getEventHandler().fireEvent(new ClusterCreateEvent(cluster));
        }

        return cluster;
    }

    public Collection<Cluster> getClusters() {
        return clusters;
    }

}
