package globalquake.main;

import globalquake.core.database.Network;
import globalquake.core.database.Station;
import globalquake.core.database.StationDatabaseManager;
import org.tinylog.Logger;

/**
 * Programmatic "select all available stations", the headless equivalent of the station-select
 * UI's Select-All action ({@code globalquake.ui.stationselect.action.SelectAllAction}) without the
 * confirmation dialog. Must be called only AFTER the seedlink availability scan has populated each
 * channel's transient availability — before that, nothing reads as available on a fresh database.
 */
public final class StationAutoSelector {

    private StationAutoSelector() {
    }

    public static void selectAllAvailable(StationDatabaseManager stationDatabaseManager) {
        stationDatabaseManager.getStationDatabase().getDatabaseWriteLock().lock();
        try {
            for (Network network : stationDatabaseManager.getStationDatabase().getNetworks()) {
                network.getStations().forEach(Station::selectBestAvailableChannel);
            }
            stationDatabaseManager.fireUpdateEvent();
        } finally {
            stationDatabaseManager.getStationDatabase().getDatabaseWriteLock().unlock();
        }
        Logger.info("Auto-selected best available channel for all stations");
    }
}
