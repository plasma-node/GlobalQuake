package globalquake.main;

import globalquake.core.Settings;
import globalquake.core.database.Channel;
import globalquake.core.database.Network;
import globalquake.core.database.SeedlinkNetwork;
import globalquake.core.database.Station;
import globalquake.core.database.StationDatabaseManager;
import globalquake.utils.GeoUtils;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Programmatic station selection, the headless equivalent of the station-select UI's Select-All
 * action ({@code globalquake.ui.stationselect.action.SelectAllAction}) without the dialog. Must be
 * called only AFTER the seedlink availability scan has populated each channel's transient
 * availability — before that, nothing reads as available on a fresh database.
 */
public final class StationAutoSelector {

    private static final double MILES_TO_KM = 1.60934;

    private StationAutoSelector() {
    }

    /** Which seedlink networks to probe in the availability check. With {@code --fastscan} we probe
     *  only the networks that already carry a selected station (fast warm boot, fewer public-server
     *  queries); otherwise (and as a fallback when nothing is selected yet, e.g. first boot) we probe
     *  every known seedlink network. */
    public static List<SeedlinkNetwork> seedlinkNetworksForScan(StationDatabaseManager stationDatabaseManager) {
        List<SeedlinkNetwork> all = stationDatabaseManager.getStationDatabase().getSeedlinkNetworks();
        if (!Main.fastScan) {
            return all;
        }
        List<SeedlinkNetwork> filtered = networksForSelectedStations(stationDatabaseManager);
        if (filtered.isEmpty()) {
            Logger.info("--fastscan requested but no stations are selected yet — scanning all %d seedlink networks this boot.".formatted(all.size()));
            return all;
        }
        Logger.info("--fastscan: probing only the %d seedlink networks carrying selected stations (of %d total).".formatted(filtered.size(), all.size()));
        return filtered;
    }

    /** The seedlink networks that carry at least one currently-selected station (for --fastscan).
     *  Empty on a fresh boot with no selection → caller should fall back to scanning all networks. */
    public static List<SeedlinkNetwork> networksForSelectedStations(StationDatabaseManager stationDatabaseManager) {
        Set<SeedlinkNetwork> set = new LinkedHashSet<>();
        stationDatabaseManager.getStationDatabase().getDatabaseReadLock().lock();
        try {
            for (Network network : stationDatabaseManager.getStationDatabase().getNetworks()) {
                for (Station s : network.getStations()) {
                    Channel ch = s.getSelectedChannel();
                    if (ch != null) {
                        set.addAll(ch.getSeedlinkNetworks().keySet());
                    }
                }
            }
        } finally {
            stationDatabaseManager.getStationDatabase().getDatabaseReadLock().unlock();
        }
        return new ArrayList<>(set);
    }

    /** Select every available station globally. Heavy — thousands of stations. Prefer a radius. */
    public static void selectAllAvailable(StationDatabaseManager stationDatabaseManager) {
        int selected = 0;
        stationDatabaseManager.getStationDatabase().getDatabaseWriteLock().lock();
        try {
            for (Network network : stationDatabaseManager.getStationDatabase().getNetworks()) {
                for (Station s : network.getStations()) {
                    s.selectBestAvailableChannel();
                    if (s.getSelectedChannel() != null) {
                        selected++;
                    }
                }
            }
            stationDatabaseManager.fireUpdateEvent();
        } finally {
            stationDatabaseManager.getStationDatabase().getDatabaseWriteLock().unlock();
        }
        Logger.warn("Auto-selected ALL %d available stations globally — this uses a lot of memory/CPU. Use --autoselect-radius <miles> to limit it.".formatted(selected));
    }

    /** Select available stations within {@code radiusMiles} of the home location, and DESELECT the
     *  rest (so a previously-selected global set is pruned back). This is the resource-safe option. */
    public static void selectWithinRadius(StationDatabaseManager stationDatabaseManager, double radiusMiles) {
        double homeLat = Settings.homeLat;
        double homeLon = Settings.homeLon;
        if (homeLat == 0 && homeLon == 0) {
            Logger.warn("--autoselect-radius given but home location is 0,0 (unset). Set your home in globalQuake.properties (homeLat/homeLon) first — otherwise nothing useful is selected.");
        }
        double radiusKm = radiusMiles * MILES_TO_KM;
        int selected = 0;
        int deselected = 0;
        stationDatabaseManager.getStationDatabase().getDatabaseWriteLock().lock();
        try {
            for (Network network : stationDatabaseManager.getStationDatabase().getNetworks()) {
                for (Station s : network.getStations()) {
                    double dist = GeoUtils.greatCircleDistance(homeLat, homeLon, s.getLatitude(), s.getLongitude());
                    if (dist <= radiusKm) {
                        s.selectBestAvailableChannel();
                        if (s.getSelectedChannel() != null) {
                            selected++;
                        }
                    } else if (s.getSelectedChannel() != null) {
                        s.setSelectedChannel(null); // prune stations outside the radius
                        deselected++;
                    }
                }
            }
            stationDatabaseManager.fireUpdateEvent();
        } finally {
            stationDatabaseManager.getStationDatabase().getDatabaseWriteLock().unlock();
        }
        Logger.info("Auto-selected %d stations within %.0f mi of home (pruned %d outside the radius).".formatted(selected, radiusMiles, deselected));
    }
}
