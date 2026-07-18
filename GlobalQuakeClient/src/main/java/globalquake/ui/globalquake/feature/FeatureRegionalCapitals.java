package globalquake.ui.globalquake.feature;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import globalquake.core.Settings;
import globalquake.core.intensity.CityLocation;
import globalquake.ui.globe.GlobeRenderer;
import globalquake.ui.globe.Point2D;
import globalquake.ui.globe.RenderProperties;
import globalquake.ui.globe.feature.RenderElement;
import globalquake.ui.globe.feature.RenderEntity;
import globalquake.ui.globe.feature.RenderFeature;
import org.tinylog.Logger;

import java.awt.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.List;

/**
 * State/province capitals of the US and Canada, drawn smaller than the national capitals of
 * {@link FeatureCities} and only when regionally zoomed in. Sourced from the already-bundled
 * {@code cities/worldcities.csv} (rows where {@code iso2 ∈ {US, CA}} and {@code capital == "admin"}).
 */
public class FeatureRegionalCapitals extends RenderFeature<CityLocation> {

    // State/province borders (raw_polygonsUS etc.) draw up to scroll 0.5; match that so capital labels
    // vanish exactly when the borders they belong to do.
    private static final double LIVE_SHOW_SCROLL = 0.5;

    private final Collection<CityLocation> cityLocations;
    private final boolean alwaysShow; // screenshots show them at every zoom; the live map uses a threshold

    public FeatureRegionalCapitals() {
        this(false);
    }

    public FeatureRegionalCapitals(boolean alwaysShow) {
        super(1);
        this.alwaysShow = alwaysShow;
        cityLocations = Collections.unmodifiableList(load());
    }

    private List<CityLocation> load() {
        List<CityLocation> result = new ArrayList<>();
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(Objects.requireNonNull(
                ClassLoader.getSystemClassLoader().getResource("cities/worldcities.csv")).openStream()))
                .withSkipLines(1).build()) {
            // columns: 0 city, 1 city_ascii, 2 lat, 3 lng, 4 country, 5 iso2, 6 iso3, 7 admin_name,
            //          8 capital, 9 population, 10 id
            String[] fields;
            while ((fields = reader.readNext()) != null) {
                if (fields.length < 9) {
                    continue;
                }
                String iso2 = fields[5];
                String capital = fields[8];
                if (!("US".equals(iso2) || "CA".equals(iso2)) || !"admin".equals(capital)) {
                    continue;
                }
                try {
                    String name = fields[1].isBlank() ? fields[0] : fields[1]; // city_ascii for font safety
                    double lat = Double.parseDouble(fields[2]);
                    double lon = Double.parseDouble(fields[3]);
                    int population = fields.length > 9 && !fields[9].isBlank() ? (int) Double.parseDouble(fields[9]) : 0;
                    result.add(new CityLocation(name, lat, lon, population));
                } catch (NumberFormatException e) {
                    // skip malformed row
                }
            }
        } catch (IOException | CsvValidationException e) {
            Logger.error(e);
        }
        Logger.info("Loaded %d US/Canada regional capitals".formatted(result.size()));
        return result;
    }

    @Override
    public Collection<CityLocation> getElements() {
        return cityLocations;
    }

    @Override
    public void createPolygon(GlobeRenderer renderer, RenderEntity<CityLocation> entity, RenderProperties renderProperties) {
        RenderElement elementCross = entity.getRenderElement(0);
        double size = Math.min(22, renderer.pxToDeg(2.5, renderProperties)); // ~60% of national capitals
        renderer.createSquare(elementCross.getPolygon(),
                entity.getOriginal().lat(),
                entity.getOriginal().lon(), size, 0.0);
    }

    @Override
    public boolean isEnabled(RenderProperties props) {
        return Boolean.TRUE.equals(Settings.displayRegionalCapitals);
    }

    @Override
    public boolean needsUpdateEntities() {
        return false;
    }

    @Override
    public boolean needsCreatePolygon(RenderEntity<CityLocation> entity, boolean propertiesChanged) {
        return propertiesChanged;
    }

    @Override
    public boolean needsProject(RenderEntity<CityLocation> entity, boolean propertiesChanged) {
        return propertiesChanged;
    }

    @Override
    public void project(GlobeRenderer renderer, RenderEntity<CityLocation> entity, RenderProperties renderProperties) {
        RenderElement element = entity.getRenderElement(0);
        element.getShape().reset();
        element.shouldDraw = renderer.project3D(element.getShape(), element.getPolygon(), true, renderProperties);
    }

    @Override
    public void render(GlobeRenderer renderer, Graphics2D graphics, RenderEntity<CityLocation> entity, RenderProperties renderProperties) {
        RenderElement element = entity.getRenderElement(0);
        if (!element.shouldDraw) {
            return;
        }
        // In the live map, hide once state borders drop out (scroll >= 0.5); screenshots always show.
        if (!alwaysShow && renderProperties.scroll >= LIVE_SHOW_SCROLL) {
            return;
        }
        graphics.setColor(new Color(210, 210, 210));
        graphics.setStroke(new BasicStroke(2f));
        graphics.fill(element.getShape());

        var point3D = GlobeRenderer.createVec3D(getCenterCoords(entity));
        var centerPonint = renderer.projectPoint(point3D, renderProperties);

        String str = entity.getOriginal().name();
        graphics.setFont(new Font("Calibri", Font.PLAIN, 11));
        graphics.drawString(str, (int) centerPonint.x - graphics.getFontMetrics().stringWidth(str) / 2, (int) centerPonint.y - 7);
    }

    @Override
    public Point2D getCenterCoords(RenderEntity<?> entity) {
        return new Point2D(((CityLocation) (entity.getOriginal())).lat(), ((CityLocation) (entity.getOriginal())).lon());
    }
}
