package globalquake.ui.globe.feature;

import globalquake.core.Settings;
import globalquake.core.regions.GQFault;
import globalquake.ui.globe.GlobeRenderer;
import globalquake.ui.globe.Point2D;
import globalquake.ui.globe.Polygon3D;
import globalquake.ui.globe.RenderProperties;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;

import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Draws the GEM Global Active Faults as open polylines on the globe, colored by kinematic slip type.
 * Modeled on {@link FeatureGeoPolygons}, but strokes the line instead of filling a ring and uses the
 * non-closing projection so open traces get no closing chord. Always visible (all zoom levels) when
 * {@link Settings#displayFaultLines} is on; off-globe traces are culled cheaply by the bounding-box
 * horizon test inside {@code project3D}.
 */
public class FeatureFaults extends RenderFeature<GQFault> {

    private static final int ALPHA = 165; // semi-transparent so dense fault networks don't overwhelm

    // FULL mode — GEM kinematic scheme, remapped for visibility on the dark globe (GEM's
    // black-for-reverse would be invisible here).
    private static final Color COLOR_DEXTRAL = new Color(70, 130, 255, ALPHA);    // right-lateral
    private static final Color COLOR_SINISTRAL = new Color(185, 95, 255, ALPHA);  // left-lateral
    private static final Color COLOR_STRIKE_SLIP = new Color(0, 200, 90, ALPHA);  // undifferentiated
    private static final Color COLOR_NORMAL = new Color(230, 70, 70, ALPHA);      // normal / spreading
    private static final Color COLOR_REVERSE = new Color(255, 150, 35, ALPHA);    // reverse / thrust
    private static final Color COLOR_UNKNOWN = new Color(150, 150, 150, ALPHA);

    // BASIC mode — three tectonic classes: transform (all strike-slip), extensional (normal /
    // divergent), compressional (reverse / subduction), plus other.
    private static final Color COLOR_TRANSFORM = new Color(0, 200, 160, ALPHA);
    private static final Color COLOR_EXTENSIONAL = new Color(230, 70, 70, ALPHA);
    private static final Color COLOR_COMPRESSIONAL = new Color(255, 150, 35, ALPHA);

    private final List<GQFault> faults;

    public FeatureFaults(List<GQFault> faults) {
        super(1);
        this.faults = faults;
    }

    public static Color colorFor(byte slipType, boolean simple) {
        if (simple) {
            return switch (slipType) {
                case GQFault.SLIP_DEXTRAL, GQFault.SLIP_SINISTRAL, GQFault.SLIP_STRIKE_SLIP -> COLOR_TRANSFORM;
                case GQFault.SLIP_NORMAL -> COLOR_EXTENSIONAL;
                case GQFault.SLIP_REVERSE -> COLOR_COMPRESSIONAL;
                default -> COLOR_UNKNOWN;
            };
        }
        return switch (slipType) {
            case GQFault.SLIP_DEXTRAL -> COLOR_DEXTRAL;
            case GQFault.SLIP_SINISTRAL -> COLOR_SINISTRAL;
            case GQFault.SLIP_STRIKE_SLIP -> COLOR_STRIKE_SLIP;
            case GQFault.SLIP_NORMAL -> COLOR_NORMAL;
            case GQFault.SLIP_REVERSE -> COLOR_REVERSE;
            default -> COLOR_UNKNOWN;
        };
    }

    /** Minimum trace length (km) to draw at a given zoom — hides minor faults when zoomed out so
     *  dense regions (e.g. China) stay legible and cheap; ~0 when zoomed in so everything shows. */
    private static double minLengthForScroll(double scroll) {
        return Math.max(0, (scroll - 0.05) * 320.0);
    }

    @Override
    public Collection<GQFault> getElements() {
        return faults;
    }

    @Override
    public boolean isEnabled(RenderProperties properties) {
        return Boolean.TRUE.equals(Settings.displayFaultLines);
    }

    @Override
    public boolean needsUpdateEntities() {
        return false;
    }

    @Override
    public boolean needsProject(RenderEntity<GQFault> entity, boolean propertiesChanged) {
        return propertiesChanged;
    }

    @Override
    public boolean needsCreatePolygon(RenderEntity<GQFault> entity, boolean propertiesChanged) {
        return false;
    }

    @Override
    public void createPolygon(GlobeRenderer renderer, RenderEntity<GQFault> entity, RenderProperties renderProperties) {
        Polygon3D result_pol = new Polygon3D();
        GQFault fault = entity.getOriginal();
        for (int i = 0; i < fault.getSize(); i++) {
            Vector3D vec = GlobeRenderer.createVec3D(new Vector2D(fault.getLats()[i], fault.getLons()[i]), 0);
            result_pol.addPoint(vec);
        }
        result_pol.finish();
        entity.getRenderElement(0).setPolygon(result_pol);
    }

    @Override
    public void project(GlobeRenderer renderer, RenderEntity<GQFault> entity, RenderProperties renderProperties) {
        RenderElement element = entity.getRenderElement(0);
        // LOD: skip minor faults (and their projection cost) when zoomed out.
        if (entity.getOriginal().getLengthKm() < minLengthForScroll(renderProperties.scroll)) {
            element.shouldDraw = false;
            return;
        }
        element.getShape().reset();
        element.shouldDraw = renderer.project3D(element.getShape(), element.getPolygon(), true, false, renderProperties);
    }

    @Override
    public Point2D getCenterCoords(RenderEntity<?> entity) {
        return null;
    }

    @Override
    public void render(GlobeRenderer renderer, Graphics2D graphics, RenderEntity<GQFault> entity, RenderProperties renderProperties) {
        RenderElement element = entity.getRenderElement(0);
        if (!element.shouldDraw) {
            return;
        }
        double thickness = Settings.faultLineThickness == null ? 1.0 : Settings.faultLineThickness;
        // Prominent (long) traces draw thicker; minor ones thinner.
        double widthFactor = 0.55 + Math.min(1.35, entity.getOriginal().getLengthKm() / 120.0);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(colorFor(entity.getOriginal().getSlipType(), Boolean.TRUE.equals(Settings.faultColorSimple)));
        graphics.setStroke(new BasicStroke((float) Math.max(0.4, widthFactor * thickness)));
        graphics.draw(element.getShape());
    }
}
