package globalquake.main;

import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import globalquake.core.earthquake.GQHypocs;
import globalquake.core.exception.ApplicationErrorHandler;
import globalquake.core.exception.FatalIOException;
import globalquake.sounds.SoundsService;
import globalquake.ui.client.MainFrame;
import org.apache.commons.cli.*;
import org.tinylog.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Objects;

public class Main {

    private static ApplicationErrorHandler errorHandler;
    public static final String fullName = "GlobalQuake " + GlobalQuake.version;
    public static final File MAIN_FOLDER = new File("./.GlobalQuakeData/");

    private static boolean headless = false;
    public static boolean autoSelect = false;
    public static double autoSelectRadiusMiles = -1; // -1 = select all (no radius limit)
    public static boolean fastScan = false; // only scan seedlink networks carrying selected stations

    public static final Image LOGO = new ImageIcon(Objects.requireNonNull(ClassLoader.getSystemClassLoader().getResource("logo/logo.png"))).getImage();

    public static void main(String[] args) {
        Options options = new Options();

        Option maxGpuMemOption = new Option("g", "gpu-max-mem", true, "maximum GPU memory limit in GB");
        maxGpuMemOption.setRequired(false);
        options.addOption(maxGpuMemOption);

        Option headlessOption = new Option("h", "headless", false, "run without any graphical UI (for servers)");
        headlessOption.setRequired(false);
        options.addOption(headlessOption);

        Option noSoundOption = new Option("n", "nosound", false, "disable all alert sounds");
        noSoundOption.setRequired(false);
        options.addOption(noSoundOption);

        Option strongOnlyOption = new Option("q", "sound-strong-only", false, "only play the strong-shaking alert sound, mute everything else");
        strongOnlyOption.setRequired(false);
        options.addOption(strongOnlyOption);

        Option autoSelectOption = new Option("a", "autoselect", false, "select ALL available stations globally (heavy: thousands of stations, high memory/CPU - prefer --autoselect-radius)");
        autoSelectOption.setRequired(false);
        options.addOption(autoSelectOption);

        Option autoRadiusOption = new Option("r", "autoselect-radius", true, "select available stations within this many miles of your home location (recommended, e.g. -r 600)");
        autoRadiusOption.setRequired(false);
        options.addOption(autoRadiusOption);

        Option homeOption = new Option("L", "home", true, "set and persist home location as lat,lon (e.g. --home 48.0,-121.0). Combine with --autoselect-radius to re-home and re-select stations for travel.");
        homeOption.setRequired(false);
        options.addOption(homeOption);

        Option fastScanOption = new Option("f", "fastscan", false, "only scan the seedlink networks that carry your already-selected stations (much faster warm boot + fewer public-server queries; won't discover newly-available stations until a full boot)");
        fastScanOption.setRequired(false);
        options.addOption(fastScanOption);

        CommandLineParser parser = new org.apache.commons.cli.BasicParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        // --help handled before parsing so it works even alongside other/invalid args (and this
        // Commons CLI version can't register a long-only option cleanly).
        for (String arg : args) {
            if ("--help".equals(arg) || "-help".equals(arg)) {
                formatter.printHelp("globalquake", options);
                System.exit(0);
            }
        }

        // Parse CLI FIRST — parsing touches neither the error handler nor Settings, but both depend
        // on the flags it produces (headless mode, sound toggles), so their init must follow it.
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            formatter.printHelp("globalquake", options);

            System.exit(1);
        }

        headless = cmd.hasOption(headlessOption.getOpt());
        autoSelect = cmd.hasOption(autoSelectOption.getOpt());
        fastScan = cmd.hasOption(fastScanOption.getOpt());
        if (cmd.hasOption(autoRadiusOption.getOpt())) {
            try {
                autoSelectRadiusMiles = Double.parseDouble(cmd.getOptionValue(autoRadiusOption.getOpt()));
                if (autoSelectRadiusMiles <= 0) {
                    throw new NumberFormatException();
                }
                autoSelect = true;
            } catch (NumberFormatException e) {
                System.err.println("--autoselect-radius must be a positive number of miles");
                System.exit(1);
            }
        }

        initErrorHandler();
        initMainDirectory();
        GlobalQuake.prepare(MAIN_FOLDER, getErrorHandler());

        // Settings-affecting flags only AFTER prepare() — Settings' static initialiser reads
        // GlobalQuake.mainFolder, so it must not be touched any earlier.
        if (cmd.hasOption(noSoundOption.getOpt())) {
            Settings.enableSound = false;
            Logger.info("Sound disabled via --nosound");
        }
        if (cmd.hasOption(strongOnlyOption.getOpt())) {
            SoundsService.strongShakingSoundOnly = true;
            Logger.info("Only the strong-shaking alert sound will play (--sound-strong-only)");
        }
        if (cmd.hasOption(homeOption.getOpt())) {
            String[] parts = cmd.getOptionValue(homeOption.getOpt()).split(",");
            try {
                Settings.homeLat = Double.parseDouble(parts[0].trim());
                Settings.homeLon = Double.parseDouble(parts[1].trim());
                Settings.save();
                Logger.info("Home location set to %.4f, %.4f (persisted)".formatted(Settings.homeLat, Settings.homeLon));
            } catch (Exception e) {
                System.err.println("--home must be lat,lon, e.g. --home 48.0,-121.0");
                System.exit(1);
            }
        }

        if(cmd.hasOption(maxGpuMemOption.getOpt())) {
            try {
                double maxMem =  Double.parseDouble(cmd.getOptionValue(maxGpuMemOption.getOpt()));
                if(maxMem <= 0){
                    throw new IllegalArgumentException("Invalid maximum GPU memory amount");
                }
                GQHypocs.MAX_GPU_MEM = maxMem;
                Logger.info("Maximum GPU memory allocation will be limited to around %.2f GB".formatted(maxMem));
            } catch(IllegalArgumentException e){
                Logger.error(e);
                System.exit(1);
            }
        }

        if (headless) {
            HeadlessClient.run(autoSelect, autoSelectRadiusMiles);
        } else {
            MainFrame mainFrame = new MainFrame();
            mainFrame.setVisible(true);
        }
    }

    private static void initMainDirectory() {
        if (!MAIN_FOLDER.exists()) {
            if (!MAIN_FOLDER.mkdirs()) {
                getErrorHandler().handleException(new FatalIOException("Unable to create main directory!", null));
            }
        }
        File VOLUME_FOLDER = new File(MAIN_FOLDER, "volume/");
        if (!VOLUME_FOLDER.exists()) {
            if (!VOLUME_FOLDER.mkdirs()) {
                getErrorHandler().handleException(new FatalIOException("Unable to create volume directory!", null));
            }
        }
    }

    public static ApplicationErrorHandler getErrorHandler() {
        if(errorHandler == null) {
            errorHandler = new ApplicationErrorHandler(null, headless);
        }
        return errorHandler;
    }

    public static void initErrorHandler() {
        Thread.setDefaultUncaughtExceptionHandler(getErrorHandler());
    }
}
