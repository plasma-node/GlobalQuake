package globalquake.notify;

import globalquake.core.GlobalQuake;
import globalquake.core.archive.ArchivedQuake;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.regions.GQPolygon;
import globalquake.core.regions.Regions;
import globalquake.core.station.AbstractStation;
import org.tinylog.Logger;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Offscreen flat (equirectangular-ish) map to a PNG, for the /screenshot endpoint. Reuses the same
 * lat/lon projection as {@code EarthquakeReporter.drawMap} and {@code Regions.raw_polygons*}. Pure
 * Java2D on a headless BufferedImage — no window required.
 */
public final class FlatMapRenderer {

    private static final Color oceanC = new Color(7, 37, 48);
    private static final Color landC = new Color(15, 47, 68);
    private static final Color borderC = new Color(153, 153, 153);

    private FlatMapRenderer() {
    }

    public static byte[] renderPng(int width, int height, double centerLat, double centerLon, double scroll,
                                   double homeLat, double homeLon, boolean showStations) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(oceanC);
        g.fillRect(0, 0, width, height);

        List<GQPolygon> pols = scroll < 0.6 ? Regions.raw_polygonsUHD
                : scroll < 4.8 ? Regions.raw_polygonsHD : Regions.raw_polygonsMD;
        for (GQPolygon polygon : pols) {
            Polygon awt = new Polygon();
            boolean onScreen = false;
            for (int i = 0; i < polygon.getSize(); i++) {
                double x = getX(polygon.getLons()[i], centerLon, scroll, width);
                double y = getY(polygon.getLats()[i], centerLat, scroll, height);
                if (!onScreen && x >= 0 && y >= 0 && x < width && y < height) {
                    onScreen = true;
                }
                awt.addPoint((int) x, (int) y);
            }
            if (onScreen) {
                g.setColor(landC);
                g.fill(awt);
                g.setColor(borderC);
                g.draw(awt);
            }
        }

        // stations first (drawn under quakes) — small dots: green = receiving data, gray = not
        if (showStations && GlobalQuake.instance != null) {
            try {
                for (AbstractStation s : GlobalQuake.instance.getStationManager().getStations()) {
                    double x = getX(s.getLongitude(), centerLon, scroll, width);
                    double y = getY(s.getLatitude(), centerLat, scroll, height);
                    if (x < 0 || y < 0 || x > width || y > height) {
                        continue;
                    }
                    g.setColor(s.hasData() ? new Color(60, 200, 90) : new Color(120, 120, 120));
                    g.fillOval((int) x - 1, (int) y - 1, 3, 3);
                }
            } catch (Exception ignored) {
            }
        }

        if (GlobalQuake.instance != null) {
            try {
                for (Earthquake q : GlobalQuake.instance.getEarthquakeAnalysis().getEarthquakes()) {
                    if (q.getHypocenter() == null || q.getOrigin() == 0) {
                        continue;
                    }
                    drawQuake(g, q.getLat(), q.getLon(), q.getMag(), centerLat, centerLon, scroll, width, height, true);
                }
            } catch (Exception ignored) {
            }
            try {
                for (ArchivedQuake a : GlobalQuake.instance.getArchive().getArchivedQuakes()) {
                    drawQuake(g, a.getLat(), a.getLon(), a.getMag(), centerLat, centerLon, scroll, width, height, false);
                }
            } catch (Exception ignored) {
            }
        }

        // home marker (cyan cross)
        double hx = getX(homeLon, centerLon, scroll, width);
        double hy = getY(homeLat, centerLat, scroll, height);
        g.setColor(Color.cyan);
        g.setStroke(new BasicStroke(2f));
        g.drawLine((int) hx - 7, (int) hy, (int) hx + 7, (int) hy);
        g.drawLine((int) hx, (int) hy - 7, (int) hx, (int) hy + 7);

        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            Logger.error(e, "Failed to render map PNG");
            return new byte[0];
        }
    }

    private static void drawQuake(Graphics2D g, double lat, double lon, double mag, double centerLat,
                                  double centerLon, double scroll, int width, int height, boolean live) {
        double x = getX(lon, centerLon, scroll, width);
        double y = getY(lat, centerLat, scroll, height);
        if (x < -20 || y < -20 || x > width + 20 || y > height + 20) {
            return;
        }
        double r = Math.max(4, 2 + mag * 2.2);
        Ellipse2D.Double ell = new Ellipse2D.Double(x - r / 2, y - r / 2, r, r);
        if (live) {
            // live quake: filled marker colored by magnitude, white outline
            Color c = mag < 3 ? new Color(80, 220, 80)
                    : mag < 5 ? new Color(230, 220, 40)
                    : mag < 6.5 ? new Color(240, 150, 30) : new Color(230, 40, 40);
            g.setColor(c);
            g.fill(ell);
            g.setColor(Color.white);
            g.setStroke(new BasicStroke(2f));
            g.draw(ell);
        } else {
            // archived/past quake: hollow red ring only (matches the desktop app)
            g.setColor(new Color(230, 40, 40));
            g.setStroke(new BasicStroke(2f));
            g.draw(ell);
        }
    }

    private static double getX(double lon, double centerLon, double scroll, int width) {
        return (lon - centerLon) / (scroll / 100.0) + width * 0.5;
    }

    private static double getY(double lat, double centerLat, double scroll, int height) {
        return (centerLat - lat) / (scroll / (300 - 200 * Math.cos(0.5 * Math.toRadians(centerLat + lat))))
                + height * 0.5;
    }
}
