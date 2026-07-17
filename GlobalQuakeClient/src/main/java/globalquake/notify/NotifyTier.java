package globalquake.notify;

/**
 * Escalating notification tiers, ordered by severity (ordinal comparisons are meaningful).
 * NONE = below any alert threshold (no notification).
 */
public enum NotifyTier {
    NONE,
    NEARBY,
    SHAKING,
    STRONG;

    public boolean atLeast(NotifyTier other) {
        return ordinal() >= other.ordinal();
    }

    public static NotifyTier max(NotifyTier a, NotifyTier b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
