package globalquake.core.regions;

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

    private final int size;
    private final float[] lats;
    private final float[] lons;
    private final byte slipType;

    public GQFault(List<LngLatAlt> coordinates, byte slipType) {
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
    }

    /**
     * Map a GEM {@code slip_type} string to a kinematic category. GEM uses the first (dominant)
     * component for compound types (e.g. "Reverse-Dextral" is dominantly reverse, "Subduction_Thrust"
     * is reverse), so we key off the leading token before the first separator ('-', '_' or whitespace).
     */
    public static byte categorize(String slipType) {
        if (slipType == null || slipType.isBlank()) {
            return SLIP_UNKNOWN;
        }
        String token = slipType.trim().toLowerCase();
        int cut = token.length();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch == '-' || ch == '_' || Character.isWhitespace(ch)) {
                cut = i;
                break;
            }
        }
        token = token.substring(0, cut);
        return switch (token) {
            case "dextral" -> SLIP_DEXTRAL;
            case "sinistral" -> SLIP_SINISTRAL;
            case "strike" -> SLIP_STRIKE_SLIP;
            case "normal", "spreading" -> SLIP_NORMAL;
            case "reverse", "thrust", "subduction" -> SLIP_REVERSE;
            default -> SLIP_UNKNOWN;
        };
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
}
