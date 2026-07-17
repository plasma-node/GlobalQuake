package globalquake.main;

import globalquake.client.GlobalQuakeLocal;
import globalquake.core.Settings;
import globalquake.core.database.StationDatabaseManager;
import globalquake.core.database.StationSource;
import globalquake.core.earthquake.GQHypocs;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.exception.FatalIOException;
import globalquake.core.training.EarthquakeAnalysisTraining;
import org.tinylog.Logger;

import java.util.stream.Collectors;

/**
 * Runs the desktop client with no Swing UI at all — for headless deployment (systemd on a server
 * with no desktop environment). Mirrors the asset-load + database-update chain of
 * {@link globalquake.ui.client.MainFrame} and the headless bootstrap of the server's
 * {@code gqserver.main.Main}, then starts the runtime without ever creating a frame.
 */
public final class HeadlessClient {

    private HeadlessClient() {
    }

    public static void run(boolean autoSelect) {
        Logger.info("Starting GlobalQuake client in headless mode");

        try {
            ClientBootstrap.loadAssets((status, value) -> Logger.info("Initialising %d%%: %s".formatted(value, status)));
        } catch (Exception e) {
            // The headless error handler logs but never exits; exit explicitly so a service
            // manager (systemd Restart=on-failure) can restart a broken process.
            Logger.error(e);
            System.exit(1);
        }

        StationDatabaseManager databaseManager = new StationDatabaseManager();
        try {
            databaseManager.load();
        } catch (FatalIOException e) {
            Logger.error(e);
            System.exit(1);
        }

        if (Settings.recalibrateOnLaunch) {
            Logger.info("Calibrating...");
            EarthquakeAnalysisTraining.calibrateResolution((status, value) -> {}, null, true);
            if (GQHypocs.isCudaLoaded()) {
                EarthquakeAnalysisTraining.calibrateResolution((status, value) -> {}, null, false);
            }
        }

        Logger.info("Updating station sources...");
        databaseManager.runUpdate(
                databaseManager.getStationDatabase().getStationSources().stream()
                        .filter(StationSource::isOutdated).collect(Collectors.toList()),
                () -> {
                    Logger.info("Checking seedlink networks...");
                    databaseManager.runAvailabilityCheck(databaseManager.getStationDatabase().getSeedlinkNetworks(), () -> {
                        if (autoSelect) {
                            StationAutoSelector.selectAllAvailable(databaseManager);
                        }
                        try {
                            databaseManager.save();
                        } catch (FatalIOException e) {
                            Logger.error(e);
                            System.exit(1);
                        }
                        launch(databaseManager);
                    });
                });
    }

    private static void launch(StationDatabaseManager databaseManager) {
        GlobalQuakeLocal globalQuake = new GlobalQuakeLocal(databaseManager);

        // Replicates the archive-on-close behaviour that GlobalQuakeLocal.createFrame() wires to the
        // window's windowClosing event — without a window we hang it on JVM shutdown (SIGTERM/Ctrl+C).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.info("Archiving quakes before shutdown...");
            for (Earthquake quake : globalQuake.getEarthquakeAnalysis().getEarthquakes()) {
                globalQuake.getArchive().archiveQuake(quake);
            }
            globalQuake.getArchive().saveArchive();
        }, "headless-archive-shutdown"));

        globalQuake.initStations().startRuntime();
        Logger.info("Headless runtime started with %d stations".formatted(globalQuake.getStationManager().getStations().size()));
    }
}
