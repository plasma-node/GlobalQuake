package globalquake.notify;

import globalquake.core.GlobalQuake;
import globalquake.core.Settings;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.regions.Regions;
import globalquake.ui.globe.GlobeRenderer;
import globalquake.ui.globe.Point2D;
import globalquake.ui.globe.RenderProperties;
import globalquake.ui.globe.feature.FeatureGeoPolygons;
import globalquake.ui.globe.feature.FeatureHorizon;
import globalquake.ui.globalquake.feature.FeatureArchivedEarthquake;
import globalquake.ui.globalquake.feature.FeatureCities;
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

/**
 * Renders the REAL app globe offscreen to a PNG, reusing the same feature layers as
 * {@code GlobalQuakePanel} — so the screenshot shows the intensity-hexagon shakemap, P/S wave rings,
 * the "M x.x" epicenter label, blue→red station dots and the fading archived-quake circles, exactly
 * like the desktop UI. A single shared {@link GlobeRenderer} is built lazily and reused (the feature
 * layers pull live data each render; building per-request would leak the shakemap event subscriber).
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
            // base layers (mirrors GlobePanel.createRenderer)
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
            // client layers (mirrors GlobalQuakePanel.addRenderFeatures)
            r.addFeature(new FeatureShakemap());
            r.addFeature(new FeatureGlobalStation(GlobalQuake.instance.getStationManager().getStations()));
            r.addFeature(new FeatureArchivedEarthquake(GlobalQuake.instance.getArchive().getArchivedQuakes()));
            r.addFeature(new FeatureEarthquake(GlobalQuake.instance.getEarthquakeAnalysis().getEarthquakes()));
            r.addFeature(new FeatureCluster(GlobalQuake.instance.getClusterAnalysis().getClusters()));
            r.addFeature(new FeatureCities());
            r.addFeature(new FeatureHomeLoc());
            renderer = r;
            Logger.info("Globe screenshot renderer initialised");
            return r;
        }
    }

    /**
     * @param centerLat NaN to auto-focus (most significant recent quake, else home)
     * @param scroll    NaN to auto-pick zoom (by focused quake magnitude, else defaultZoom)
     */
    public static byte[] renderPng(int width, int height, double centerLat, double centerLon,
                                   double scroll, double defaultZoom) {
        double lat = centerLat, lon = centerLon, zoom = scroll;

        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            Earthquake target = pickTarget();
            if (target != null) {
                lat = target.getLat();
                lon = target.getLon();
                if (Double.isNaN(zoom)) {
                    zoom = Math.max(0.05, target.getMag() / 50.0); // cinema-style: bigger quake, wider view
                }
            } else {
                lat = Settings.homeLat;
                lon = Settings.homeLon;
                if (Double.isNaN(zoom)) {
                    zoom = defaultZoom;
                }
            }
        }
        if (Double.isNaN(zoom)) {
            zoom = defaultZoom;
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        try {
            GlobeRenderer r = getRenderer();
            RenderProperties props = new RenderProperties(width, height, lat, lon, zoom);
            synchronized (r) {
                r.updateCamera(props);
                r.render(g, props);
            }
        } catch (Exception e) {
            Logger.error(e, "Globe render failed");
        }

        drawHud(g, width, height);
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
                double score = q.getMag() * 100 - distHome * 0.02 - ageMin * 1.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = q;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private static void drawHud(Graphics2D g, int width, int height) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        // timestamp, bottom-left
        String ts = TS.format(Instant.ofEpochMilli(System.currentTimeMillis()));
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(4, height - 22, 8 + g.getFontMetrics().stringWidth(ts), 18);
        g.setColor(Color.white);
        g.drawString(ts, 8, height - 9);

        // top quake summary, top-left
        Earthquake t = pickTarget();
        String line = t != null
                ? "M%.1f  %s  %.0fkm deep".formatted(t.getMag(),
                    t.getRegion() == null || t.getRegion().isBlank() ? "?" : t.getRegion(), t.getDepth())
                : "No active earthquakes";
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(4, 4, 8 + g.getFontMetrics().stringWidth(line), 18);
        g.setColor(t != null ? new Color(255, 170, 60) : new Color(170, 170, 170));
        g.drawString(line, 8, 17);
    }
}
