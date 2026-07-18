package globalquake.main;

import globalquake.core.Settings;
import globalquake.core.database.Network;
import globalquake.core.database.Station;
import globalquake.core.database.StationDatabaseManager;
import globalquake.utils.GeoUtils;
import org.tinylog.Logger;

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
