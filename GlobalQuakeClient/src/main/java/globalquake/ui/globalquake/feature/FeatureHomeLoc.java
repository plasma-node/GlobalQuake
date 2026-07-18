package globalquake.ui.globalquake.feature;

import globalquake.ui.globe.GlobeRenderer;
import globalquake.ui.globe.Point2D;
import globalquake.ui.globe.RenderProperties;
import globalquake.ui.globe.feature.RenderElement;
import globalquake.ui.globe.feature.RenderEntity;
import globalquake.ui.globe.feature.RenderFeature;
import globalquake.core.Settings;

import java.awt.*;
import java.util.Collection;
import java.util.List;

public class FeatureHomeLoc extends RenderFeature<LocationPlaceholder> {

    private final Collection<LocationPlaceholder> placeholders;

    public FeatureHomeLoc() {
        super(1);
        placeholders = List.of(new HomeLocationPlaceholder());
    }

    @Override
    public Collection<LocationPlaceholder> getElements() {
        return placeholders;
    }

    @Override
    public void createPolygon(GlobeRenderer renderer, RenderEntity<LocationPlaceholder> entity, RenderProperties renderProperties) {
        RenderElement elementCross = entity.getRenderElement(0);

        renderer.createCross(elementCross.getPolygon(),
                entity.getOriginal().getLat(),
                entity.getOriginal().getLon(), renderer
                        .pxToDeg(9.5, renderProperties), 0.0); // a smidge longer arms than the old 8px
    }

    @Override
    public boolean isEnabled(RenderProperties props) {
        return Settings.displayHomeLocation;
    }

    @Override
    public boolean needsCreatePolygon(RenderEntity<LocationPlaceholder> entity, boolean propertiesChanged) {
        return propertiesChanged;
    }

    @Override
    public boolean needsProject(RenderEntity<LocationPlaceholder> entity, boolean propertiesChanged) {
        return propertiesChanged;
    }

    @Override
    public boolean needsUpdateEntities() {
        return false;
    }

    @Override
    public void project(GlobeRenderer renderer, RenderEntity<LocationPlaceholder> entity, RenderProperties renderProperties) {
        RenderElement element = entity.getRenderElement(0);
        element.getShape().reset();
        element.shouldDraw = renderer.project3D(element.getShape(), element.getPolygon(), true, renderProperties);
    }

    @Override
    public void render(GlobeRenderer renderer, Graphics2D graphics, RenderEntity<LocationPlaceholder> entity, RenderProperties renderProperties) {
        RenderElement elementCross = entity.getRenderElement(0);
        if (elementCross.shouldDraw) {
            // Maximum-visibility crosshair: a 1px purple outline for definition, with a 4px XOR core
            // that inverts whatever is behind it, so it reads on land, ocean, faults or quakes alike.
            Object oldAA = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            graphics.setPaintMode();
            graphics.setColor(new Color(170, 50, 255)); // purple outline
            graphics.setStroke(new BasicStroke(6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            graphics.draw(elementCross.getShape());

            graphics.setXORMode(Color.black); // XOR with black + white paint == invert background
            graphics.setColor(Color.white);
            graphics.setStroke(new BasicStroke(4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            graphics.draw(elementCross.getShape());
            graphics.setPaintMode();

            if (oldAA != null) {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
            }
        }
    }

    @Override
    public Point2D getCenterCoords(RenderEntity<?> entity) {
        return new Point2D(((LocationPlaceholder) (entity.getOriginal())).getLat(), ((LocationPlaceholder) (entity.getOriginal())).getLon());
    }
}
