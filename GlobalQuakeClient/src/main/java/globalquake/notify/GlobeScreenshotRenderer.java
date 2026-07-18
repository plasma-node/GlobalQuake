package globalquake.notify;

import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import globalquake.core.archive.ArchivedQuake;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.earthquake.data.Hypocenter;
import globalquake.core.geo.taup.TauPTravelTimeCalculator;
import globalquake.core.intensity.IntensityScale;
import globalquake.core.intensity.IntensityScales;
import globalquake.core.intensity.Level;
import globalquake.core.regions.Regions;
import globalquake.ui.globe.GlobeRenderer;
import globalquake.ui.globe.Point2D;
import globalquake.ui.globe.RenderProperties;
import globalquake.ui.globe.feature.FeatureFaults;
import globalquake.ui.globe.feature.FeatureGeoPolygons;
import globalquake.ui.globe.feature.FeatureHorizon;
import globalquake.ui.globalquake.feature.FeatureArchivedEarthquake;
import globalquake.ui.globalquake.feature.FeatureCities;
import globalquake.ui.globalquake.feature.FeatureRegionalCapitals;
import globalquake.ui.globalquake.feature.FeatureCluster;
import globalquake.ui.globalquake.feature.FeatureEarthquake;
import globalquake.ui.globalquake.feature.FeatureGlobalStation;
import globalquake.ui.globalquake.feature.FeatureHomeLoc;
import globalquake.ui.globalquake.feature.FeatureShakemap;
import globalquake.utils.GeoUtils;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the REAL app globe offscreen to a PNG, reusing the same feature layers as
 * {@code GlobalQuakePanel} — intensity-hexagon shakemap, P/S wave rings, "M x.x" epicenter label,
 * blue→red station dots, fading archived-quake circles, home marker. A custom HUD is drawn on top:
 * a "shaking expected" banner with intensity + S-wave ETA to home, a focused-quake info line, and a
 * recent-quakes list. A single shared {@link GlobeRenderer} is built lazily and reused.
 */
public final class GlobeScreenshotRenderer {

    private static final Color BG = new Color(7, 20, 30);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private static GlobeRenderer renderer;
    private static final Object lock = new Object();

    private GlobeScreenshotRenderer() {
    }

    private static GlobeRenderer getRenderer() {
        synchronized (lock) {
            if (renderer != null) {
                return renderer;
            }
            GlobeRenderer r = new GlobeRenderer();
            r.addFeature(new FeatureHorizon(new Point2D(Settings.homeLat, Settings.homeLon), 1));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsMD, 0.5, Double.MAX_VALUE));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsHDFiltered, 0.25, 0.5));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsUHDFiltered, 0, 0.25));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsUS, 0, 0.5));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsAK, 0, 0.5));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsJP, 0, 0.5));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsNZ, 0, 0.5));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsHW, 0, 0.5));
            r.addFeature(new FeatureGeoPolygons(Regions.raw_polygonsIT, 0, 0.20));
            r.addFeature(new FeatureFaults(Regions.raw_faults));
            r.addFeature(new FeatureShakemap());
            r.addFeature(new FeatureGlobalStation(GlobalQuake.instance.getStationManager().getStations()));
            r.addFeature(new FeatureArchivedEarthquake(GlobalQuake.instance.getArchive().getArchivedQuakes()));
            r.addFeature(new FeatureEarthquake(GlobalQuake.instance.getEarthquakeAnalysis().getEarthquakes()));
            r.addFeature(new FeatureCluster(GlobalQuake.instance.getClusterAnalysis().getClusters()));
            r.addFeature(new FeatureCities());
            r.addFeature(new FeatureRegionalCapitals(true)); // always show in screenshots
            r.addFeature(new FeatureHomeLoc());
            renderer = r;
            Logger.info("Globe screenshot renderer initialised");
            return r;
        }
    }

    /**
     * @param lat,lon        NaN to use home (or the focused quake if jumpToNearest)
     * @param zoomMultiplier intuitive zoom: 1.0 = default view, higher = closer, lower = wider
     * @param jumpToNearest  center on the most significant recent quake instead of home
     * @param showStations   draw station dots
     * @param baseScroll     globe scroll at zoomMultiplier 1.0 (config screenshotZoom)
     */
    public static byte[] renderPng(int width, int height, double lat, double lon, double zoomMultiplier,
                                   boolean jumpToNearest, boolean showStations, double baseScroll, String focusLabel) {
        return renderPng(width, height, lat, lon, zoomMultiplier, jumpToNearest, showStations, baseScroll, focusLabel, null, 1.0);
    }

    /**
     * @param faultsOverride  null = honour {@link Settings#displayFaultLines}; TRUE/FALSE force fault
     *                        lines on/off for this one shot (the {@code ?faults=} param).
     * @param stationSizeMul  screenshot station-dot size relative to the app's own station-size setting.
     */
    public static byte[] renderPng(int width, int height, double lat, double lon, double zoomMultiplier,
                                   boolean jumpToNearest, boolean showStations, double baseScroll, String focusLabel,
                                   Boolean faultsOverride, double stationSizeMul) {
        if (zoomMultiplier < 0.001) {
            zoomMultiplier = 1.0;
        }
        Earthquake focus = pickTarget(); // for the HUD (and for jumpToNearest centering)

        double centerLat, centerLon, scroll;
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
            centerLat = lat;
            centerLon = lon;
            scroll = baseScroll / zoomMultiplier;
        } else if (jumpToNearest && focus != null) {
            centerLat = focus.getLat();
            centerLon = focus.getLon();
            scroll = Math.max(0.02, focus.getMag() / 50.0) / zoomMultiplier;
        } else {
            centerLat = Settings.homeLat;
            centerLon = Settings.homeLon;
            scroll = baseScroll / zoomMultiplier;
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                Boolean.FALSE.equals(Settings.antialiasingText) ? RenderingHints.VALUE_TEXT_ANTIALIAS_OFF : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        double viewMiles = 0;
        try {
            GlobeRenderer r = getRenderer();
            RenderProperties props = new RenderProperties(width, height, centerLat, centerLon, scroll);
            synchronized (r) {
                Double oldMul = Settings.stationsSizeMul;
                double base = oldMul == null ? 1.0 : oldMul;
                // scale station dots by the configured screenshot multiplier; 0 hides them (?stations=0)
                Settings.stationsSizeMul = showStations ? base * stationSizeMul : 0.0;
                Boolean oldFaults = Settings.displayFaultLines;
                if (faultsOverride != null) {
                    Settings.displayFaultLines = faultsOverride; // ?faults=0|1 forces this one shot
                }
                try {
                    r.updateCamera(props);
                    r.render(g, props);
                    // approximate ground width of the view (deg across → km → miles)
                    viewMiles = r.pxToDeg(width, props) * 111.32 * 0.621371;
                } finally {
                    Settings.stationsSizeMul = oldMul;
                    Settings.displayFaultLines = oldFaults;
                }
            }
        } catch (Exception e) {
            Logger.error(e, "Globe render failed");
        }

        drawHud(g, width, height, focus, focusLabel, centerLat, centerLon, viewMiles);
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            Logger.error(e, "Failed to encode screenshot PNG");
            return new byte[0];
        }
    }

    /** Most significant recent live quake: favour large magnitude, near home, recent. */
    private static Earthquake pickTarget() {
        GlobalQuake gq = GlobalQuake.instance;
        if (gq == null) {
            return null;
        }
        Earthquake best = null;
        double bestScore = -1e18;
        try {
            long now = gq.currentTimeMillis();
            for (Earthquake q : gq.getEarthquakeAnalysis().getEarthquakes()) {
                if (q.getHypocenter() == null || q.getOrigin() == 0) {
                    continue;
                }
                double distHome = GeoUtils.greatCircleDistance(q.getLat(), q.getLon(), Settings.homeLat, Settings.homeLon);
                double ageMin = (now - q.getOrigin()) / 60000.0;
                double score = q.getMag() * 100 - distHome * 0.02 - ageMin;
                if (score > bestScore) {
                    bestScore = score;
                    best = q;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private static void drawHud(Graphics2D g, int width, int height, Earthquake focus, String focusLabel,
                                double centerLat, double centerLon, double viewMiles) {
        Font base = new Font("SansSerif", Font.PLAIN, 12);
        Font bold = new Font("SansSerif", Font.BOLD, 13);

        // focused region (closest state/province/country to the camera centre) + approx view width,
        // stacked just above the timestamp, bottom-left
        g.setFont(base);
        String region = null;
        try {
            region = globalquake.core.regions.Regions.getRegion(centerLat, centerLon);
        } catch (Exception ignored) {
        }
        String label = region != null && !region.isBlank() ? region : "";
        if (viewMiles > 0) {
            String across = "~%s mi across".formatted(formatMiles(viewMiles));
            label = label.isEmpty() ? across : label + "   " + across;
        }
        if (!label.isEmpty()) {
            box(g, 4, height - 40, label, new Color(150, 210, 255));
        }

        // timestamp, bottom-left
        g.setFont(base);
        String ts = TS.format(Instant.ofEpochMilli(System.currentTimeMillis()));
        box(g, 4, height - 22, ts, new Color(220, 220, 220));

        // historical mode: a specific quake was requested — show its label, no live banner
        if (focusLabel != null) {
            g.setFont(bold);
            box(g, 4, 4, focusLabel, new Color(255, 180, 70));
            drawRecentList(g, width, height);
            return;
        }

        // focused-quake info line, top-left
        if (focus != null) {
            Hypocenter h = focus.getHypocenter();
            String quality = (h != null && h.quality != null && h.quality.getSummary() != null) ? h.quality.getSummary().name() : "?";
            String line = "M%.1f  Q:%s  rev %d  %.3f, %.3f  %.0fkm"
                    .formatted(focus.getMag(), quality, focus.getRevisionID(), focus.getLat(), focus.getLon(), focus.getDepth());
            g.setFont(bold);
            box(g, 4, 4, line, new Color(255, 180, 70));

            // "<< SHAKING EXPECTED >>" banner + intensity + S ETA, when home shaking is expected
            drawShakingBanner(g, width, focus);
        } else {
            g.setFont(bold);
            box(g, 4, 4, "No active earthquakes", new Color(170, 170, 170));
        }

        drawRecentList(g, width, height);
    }

    private static void drawShakingBanner(Graphics2D g, int width, Earthquake q) {
        double distGeo = GeoUtils.geologicalDistance(q.getLat(), q.getLon(), -q.getDepth(), Settings.homeLat, Settings.homeLon, 0.0);
        double pgaHome = GeoUtils.pgaFunction(q.getMag(), distGeo, q.getDepth());
        double feltThreshold = IntensityScales.INTENSITY_SCALES[Settings.shakingLevelScale].getLevels().get(Settings.shakingLevelIndex).getPga();
        if (pgaHome < feltThreshold) {
            return;
        }
        IntensityScale scale = IntensityScales.getIntensityScale();
        Level level = scale.getLevel(pgaHome);
        String intensity = level != null ? level.getFullName() + " " + scale.getNameShort() : "?";

        double distGcd = GeoUtils.greatCircleDistance(q.getLat(), q.getLon(), Settings.homeLat, Settings.homeLon);
        double sTravel = TauPTravelTimeCalculator.getSWaveTravelTime(q.getDepth(), TauPTravelTimeCalculator.toAngle(distGcd));
        double age = (GlobalQuake.instance.currentTimeMillis() - q.getOrigin()) / 1000.0;
        String eta = sTravel < 0 ? "" : "  S wave " + (sTravel - age > 0 ? "in " + Math.round(sTravel - age) + "s" : "now");

        String banner = "<< SHAKING EXPECTED >>   intensity %s%s".formatted(intensity, eta);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        int w = g.getFontMetrics().stringWidth(banner);
        int x = (width - w) / 2;
        g.setColor(new Color(150, 20, 20, 210));
        g.fillRect(x - 10, 30, w + 20, 24);
        g.setColor(Color.white);
        g.drawString(banner, x, 47);
    }

    private static void drawRecentList(Graphics2D g, int width, int height) {
        GlobalQuake gq = GlobalQuake.instance;
        if (gq == null) {
            return;
        }
        List<Earthquake> recent = new ArrayList<>();
        try {
            for (Earthquake q : gq.getEarthquakeAnalysis().getEarthquakes()) {
                if (q.getHypocenter() != null && q.getOrigin() != 0) {
                    recent.add(q);
                }
            }
        } catch (Exception ignored) {
        }
        recent.sort(Comparator.comparingLong(Earthquake::getOrigin).reversed());
        if (recent.isEmpty()) {
            return;
        }

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        int lineH = 15;
        int boxW = 260;
        int magW = 44;              // column for "M4.5"
        int ageW = 42;              // right-aligned column for "- 4m"
        int regionW = boxW - magW - ageW - 14;
        int shown = Math.min(6, recent.size());
        long now = gq.currentTimeMillis();

        // pre-wrap so the box height fits the (possibly multi-line) region names
        List<List<String>> wrapped = new ArrayList<>();
        int totalLines = 0;
        for (int i = 0; i < shown; i++) {
            String region = recent.get(i).getRegion() == null || recent.get(i).getRegion().isBlank() ? "?" : recent.get(i).getRegion();
            List<String> ls = wrap(g, region, regionW, 2);
            wrapped.add(ls);
            totalLines += ls.size();
        }

        int boxH = 22 + totalLines * lineH;
        int x = width - boxW - 6;
        int y = height - boxH - 6;
        g.setColor(new Color(0, 0, 0, 215));
        g.fillRect(x, y, boxW, boxH);
        g.setColor(new Color(200, 200, 200));
        g.drawString("Recent earthquakes", x + 8, y + 15);

        int yy = y + 15 + lineH;
        for (int i = 0; i < shown; i++) {
            Earthquake q = recent.get(i);
            int entryTopY = yy;
            g.setColor(new Color(255, 190, 90));
            g.drawString("M%.1f".formatted(q.getMag()), x + 8, yy);
            g.setColor(new Color(220, 220, 220));
            for (String ls : wrapped.get(i)) {
                g.drawString(ls, x + magW, yy);
                yy += lineH;
            }
            // age since origin, right-aligned; "T+" makes clear it's elapsed time, not an ETA
            String age = "T+" + fmtAge(now - q.getOrigin());
            g.setColor(new Color(180, 180, 180));
            int aw = g.getFontMetrics().stringWidth(age);
            g.drawString(age, x + boxW - 8 - aw, entryTopY);
        }
    }

    /** Round a mileage to a tidy magnitude: <100 exact, <1000 nearest 10, else nearest 100 w/ commas. */
    private static String formatMiles(double mi) {
        if (mi < 100) {
            return String.valueOf(Math.round(mi));
        }
        if (mi < 1000) {
            return String.valueOf(Math.round(mi / 10.0) * 10);
        }
        return String.format(Locale.ENGLISH, "%,d", Math.round(mi / 100.0) * 100);
    }

    /** Compact "time since" like 8s / 4m / 2h / 3d. */
    private static String fmtAge(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) {
            return sec + "s";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + "m";
        }
        long hr = min / 60;
        if (hr < 24) {
            return hr + "h";
        }
        return (hr / 24) + "d";
    }

    /** Word-wrap text to fit maxWidth px, up to maxLines lines (last line ellipsised if it overflows). */
    private static List<String> wrap(Graphics2D g, String text, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (g.getFontMetrics().stringWidth(trial) <= maxWidth || cur.length() == 0) {
                cur = new StringBuilder(trial);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(word);
                if (out.size() == maxLines - 1) {
                    break;
                }
            }
        }
        String rest = cur.toString();
        while (g.getFontMetrics().stringWidth(rest) > maxWidth && rest.length() > 1) {
            rest = rest.substring(0, rest.length() - 2) + "…";
        }
        out.add(rest);
        return out;
    }

    private static void box(Graphics2D g, int x, int y, String s, Color fg) {
        int w = g.getFontMetrics().stringWidth(s);
        int h = g.getFontMetrics().getHeight();
        g.setColor(new Color(0, 0, 0, 210));
        g.fillRect(x, y, w + 8, h + 2);
        g.setColor(fg);
        g.drawString(s, x + 4, y + h - 2);
    }
}
