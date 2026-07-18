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

    // GEM kinematic color scheme, remapped for visibility on the dark globe (GEM's black-for-reverse
    // would be invisible here).
    private static final Color COLOR_DEXTRAL = new Color(70, 130, 255);    // right-lateral
    private static final Color COLOR_SINISTRAL = new Color(185, 95, 255);  // left-lateral
    private static final Color COLOR_STRIKE_SLIP = new Color(0, 200, 90);  // undifferentiated
    private static final Color COLOR_NORMAL = new Color(230, 70, 70);      // normal / spreading
    private static final Color COLOR_REVERSE = new Color(255, 150, 35);    // reverse / thrust
    private static final Color COLOR_UNKNOWN = new Color(150, 150, 150);

    private final List<GQFault> faults;

    public FeatureFaults(List<GQFault> faults) {
        super(1);
        this.faults = faults;
    }

    public static Color colorFor(byte slipType) {
        return switch (slipType) {
            case GQFault.SLIP_DEXTRAL -> COLOR_DEXTRAL;
            case GQFault.SLIP_SINISTRAL -> COLOR_SINISTRAL;
            case GQFault.SLIP_STRIKE_SLIP -> COLOR_STRIKE_SLIP;
            case GQFault.SLIP_NORMAL -> COLOR_NORMAL;
            case GQFault.SLIP_REVERSE -> COLOR_REVERSE;
            default -> COLOR_UNKNOWN;
        };
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
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(colorFor(entity.getOriginal().getSlipType()));
        graphics.setStroke(new BasicStroke((float) Math.max(0.4, 1.2 * thickness)));
        graphics.draw(element.getShape());
    }
}
