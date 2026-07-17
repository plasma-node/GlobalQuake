package globalquake.notify;

/**
 * Escalating notification tiers, ordered by severity (ordinal comparisons are meaningful).
 * NONE  = below any threshold (no notification)
 * NEARBY   = in the area but not expected to be felt        → low priority
 * SHAKING  = shaking expected at a zone (felt threshold)    → default priority
 * STRONG   = strong shaking expected                        → high priority
 * IMMINENT = strong/felt shaking arriving within seconds    → max priority (time-based, debounced)
 */
public enum NotifyTier {
    NONE,
    NEARBY,
    SHAKING,
    STRONG,
    IMMINENT;

    public boolean atLeast(NotifyTier other) {
        return ordinal() >= other.ordinal();
    }

    public static NotifyTier max(NotifyTier a, NotifyTier b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Parse a config value (case-insensitive), falling back to def on anything unrecognised. */
    public static NotifyTier parse(String s, NotifyTier def) {
        if (s == null) {
            return def;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
