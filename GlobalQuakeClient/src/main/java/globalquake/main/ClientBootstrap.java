package globalquake.main;

import globalquake.core.earthquake.GQHypocs;
import globalquake.core.exception.RuntimeApplicationException;
import globalquake.core.geo.taup.TauPTravelTimeCalculator;
import globalquake.core.regions.Regions;
import globalquake.intensity.ShakeMap;
import globalquake.sounds.Sounds;
import globalquake.ui.ProgressUpdateFunction;
import globalquake.utils.Scale;

/**
 * Loads the client's heavy runtime assets (regions, scales, shakemap, sounds, travel tables,
 * CUDA). Extracted from MainFrame.initAll() so both the windowed UI (progress bar) and the
 * headless bootstrap (log lines) share the exact same sequence.
 */
public final class ClientBootstrap {

    private static final double PHASES = 6.0;

    private ClientBootstrap() {
    }

    public static void loadAssets(ProgressUpdateFunction progress) throws Exception {
        int phase = 0;
        progress.update("Loading regions...", (int) ((phase++ / PHASES) * 100.0));
        Regions.init();
        progress.update("Loading scales...", (int) ((phase++ / PHASES) * 100.0));
        Scale.load();
        progress.update("Loading shakemap...", (int) ((phase++ / PHASES) * 100.0));
        ShakeMap.init();
        progress.update("Loading sounds...", (int) ((phase++ / PHASES) * 100.0));
        try {
            //Sound may fail to load for a variety of reasons. If it does, this method disables sound.
            Sounds.load();
        } catch (Exception e) {
            RuntimeApplicationException error = new RuntimeApplicationException("Failed to load sounds. Sound will be disabled", e);
            Main.getErrorHandler().handleWarning(error);
        }
        progress.update("Loading travel table...", (int) ((phase++ / PHASES) * 100.0));
        TauPTravelTimeCalculator.init();

        progress.update("Trying to load CUDA library...", (int) ((phase++ / PHASES) * 100.0));
        GQHypocs.load();

        progress.update("Done", (int) ((phase / PHASES) * 100.0));
    }
}
