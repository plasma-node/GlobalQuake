package globalquake.core.regions;

import globalquake.utils.GeoUtils;
import org.geojson.LngLatAlt;

import java.util.List;

/**
 * One open fault trace (polyline) from the GEM Global Active Faults database, plus its kinematic
 * category derived from the GeoJSON {@code slip_type} property. Mirrors {@link GQPolygon}'s parallel
 * float-array layout, but stores an open line rather than a closed ring.
 */
public class GQFault {

    // Kinematic categories (from GEM slip_type), used to pick a color at render time.
    public static final byte SLIP_UNKNOWN = 0;
    public static final byte SLIP_DEXTRAL = 1;      // right-lateral strike-slip
    public static final byte SLIP_SINISTRAL = 2;    // left-lateral strike-slip
    public static final byte SLIP_STRIKE_SLIP = 3;  // undifferentiated strike-slip
    public static final byte SLIP_NORMAL = 4;       // normal / spreading ridge
    public static final byte SLIP_REVERSE = 5;      // reverse / thrust / subduction

    // Traces at/above this length count as "major" and always render (never LOD-culled).
    public static final double MAJOR_LEN_KM = 180.0;

    private final int size;
    private final float[] lats;
    private final float[] lons;
    private final byte slipType;
    private final float lengthKm; // trace length; a proxy for prominence (LOD culling + line thickness)
    private final boolean major;  // plate boundary or long trace → always shown, drawn thicker

    public GQFault(List<LngLatAlt> coordinates, byte slipType, boolean plateBoundary) {
        this.size = coordinates.size();
        this.lats = new float[size];
        this.lons = new float[size];
        this.slipType = slipType;
        int i = 0;
        for (LngLatAlt c : coordinates) {
            lats[i] = (float) c.getLatitude();
            lons[i] = (float) c.getLongitude();
            i++;
        }
        double len = 0;
        for (int j = 1; j < size; j++) {
            len += GeoUtils.greatCircleDistance(lats[j - 1], lons[j - 1], lats[j], lons[j]);
        }
        this.lengthKm = (float) len;
        this.major = plateBoundary || lengthKm >= MAJOR_LEN_KM;
    }

    /**
     * Map a GEM {@code slip_type} string to a kinematic category. GEM uses the first (dominant)
     * component for compound types (e.g. "Reverse-Dextral" is dominantly reverse, "Subduction_Thrust"
     * is reverse), so we key off the leading token before the first separator ('-', '_' or whitespace).
     */
    public static byte categorize(String slipType) {
        return switch (firstToken(slipType)) {
            case "dextral" -> SLIP_DEXTRAL;
            case "sinistral" -> SLIP_SINISTRAL;
            case "strike" -> SLIP_STRIKE_SLIP;
            case "normal", "spreading" -> SLIP_NORMAL;
            case "reverse", "thrust", "subduction" -> SLIP_REVERSE;
            default -> SLIP_UNKNOWN;
        };
    }

    /** Plate-boundary structures (subduction zones, spreading ridges) — always drawn regardless of zoom. */
    public static boolean isPlateBoundary(String slipType) {
        String t = firstToken(slipType);
        return t.equals("subduction") || t.equals("spreading");
    }

    private static String firstToken(String slipType) {
        if (slipType == null || slipType.isBlank()) {
            return "";
        }
        String token = slipType.trim().toLowerCase();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch == '-' || ch == '_' || Character.isWhitespace(ch)) {
                return token.substring(0, i);
            }
        }
        return token;
    }

    public int getSize() {
        return size;
    }

    public float[] getLats() {
        return lats;
    }

    public float[] getLons() {
        return lons;
    }

    public byte getSlipType() {
        return slipType;
    }

    public float getLengthKm() {
        return lengthKm;
    }

    public boolean isMajor() {
        return major;
    }
}
