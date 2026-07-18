package globalquake.ui.globalquake;

import globalquake.ui.globe.GlobeRenderer;
import globalquake.ui.globe.Point2D;
import globalquake.ui.globe.RenderProperties;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/**
 * Small screen-space HUD overlays shared by the live map ({@link GlobalQuakePanel}) and the
 * screenshot renderer: a maximum-visibility home crosshair and a classic map scale bar. Kept in
 * screen space (not projected geometry) so they render pixel-perfect and always on top.
 */
public final class MapOverlays {

    private MapOverlays() {
    }

    /**
     * Ground miles per screen pixel at the view centre, measured empirically from the actual
     * projection (project two points a known latitude apart and divide by their pixel separation).
     * Robust across the perspective globe's non-linear scaling; returns 0 if it can't be measured.
     */
    public static double milesPerPixel(GlobeRenderer r, RenderProperties props) {
        double lat0 = props.centerLat;
        // small baseline for zoomed-in accuracy; larger fallbacks so it still measures when zoomed out
        for (double dLat : new double[]{0.05, 1.0, 10.0}) {
            double lat1 = lat0 + dLat > 89.9 ? lat0 - dLat : lat0 + dLat;
            var a = GlobeRenderer.createVec3D(new Point2D(lat0, props.centerLon));
            var b = GlobeRenderer.createVec3D(new Point2D(lat1, props.centerLon));
            if (!r.isAboveHorizon(a, props) || !r.isAboveHorizon(b, props)) {
                continue;
            }
            var pa = r.projectPoint(a, props);
            var pb = r.projectPoint(b, props);
            double px = Math.hypot(pa.x - pb.x, pa.y - pb.y);
            if (px >= 1.0) {
                double miles = Math.abs(lat1 - lat0) * 111.32 * 0.621371; // deg lat → km → miles
                return miles / px;
            }
        }
        return 0;
    }

    /** A "+" reticle centred at (cx, cy): a thick black outline for definition on light backgrounds
     *  plus a bright magenta core for dark ones — reliably visible over anything, unlike XOR. */
    public static void drawCrosshair(Graphics2D g, int cx, int cy) {
        final int arm = 11;  // half-length of each bar
        final int half = 2;  // half-width → 4px bars
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setPaintMode();

        g.setColor(Color.black); // outline (bars inflated 1px each side)
        g.fillRect(cx - arm - 1, cy - half - 1, 2 * arm + 2, 2 * half + 2);
        g.fillRect(cx - half - 1, cy - arm - 1, 2 * half + 2, 2 * arm + 2);

        g.setColor(Color.magenta); // bright core
        g.fillRect(cx - arm, cy - half, 2 * arm, 2 * half);
        g.fillRect(cx - half, cy - arm, 2 * half, 2 * arm);

        if (oldAA != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    /** Classic scale bar centred horizontally at {@code centerX} with its rule on baseline {@code y}.
     *  Picks a round mileage (1/2/5 × 10ⁿ) whose on-screen length is legible at the current zoom. */
    public static void drawScaleBar(Graphics2D g, int centerX, int y, double milesPerPixel) {
        if (!(milesPerPixel > 0) || Double.isInfinite(milesPerPixel) || Double.isNaN(milesPerPixel)) {
            return;
        }
        double niceMiles = niceRound(110 * milesPerPixel); // aim ~110px
        int barPx = (int) Math.round(niceMiles / milesPerPixel);
        if (barPx < 16) {
            return;
        }
        int x0 = centerX - barPx / 2;
        int x1 = x0 + barPx;
        String label = scaleLabel(niceMiles);

        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        int lw = fm.stringWidth(label);

        int boxW = Math.max(barPx, lw) + 16;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(centerX - boxW / 2, y - 26, boxW, 34, 8, 8);

        // black underlay then white bar + end ticks
        g.setColor(Color.black);
        g.setStroke(new BasicStroke(4f));
        drawRule(g, x0, x1, y);
        g.setColor(Color.white);
        g.setStroke(new BasicStroke(2f));
        drawRule(g, x0, x1, y);

        g.setColor(Color.white);
        g.drawString(label, centerX - lw / 2, y - 8);

        if (oldAA != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    private static void drawRule(Graphics2D g, int x0, int x1, int y) {
        g.drawLine(x0, y, x1, y);
        g.drawLine(x0, y - 5, x0, y + 5);
        g.drawLine(x1, y - 5, x1, y + 5);
    }

    private static double niceRound(double x) {
        double exp = Math.floor(Math.log10(x));
        double base = Math.pow(10, exp);
        double f = x / base; // 1..10
        double nice = f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10;
        return nice * base;
    }

    private static String scaleLabel(double mi) {
        if (mi >= 1) {
            return String.format(Locale.ENGLISH, "%,d mi", Math.round(mi));
        }
        if (mi >= 0.1) {
            return String.format(Locale.ENGLISH, "%.1f mi", mi);
        }
        if (mi >= 0.01) {
            return String.format(Locale.ENGLISH, "%.2f mi", mi);
        }
        return String.format(Locale.ENGLISH, "%.3f mi", mi);
    }
}
